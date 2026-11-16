package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

@Component
public class DocumentParser {
    public static final int MAX_CHARACTERS = 1_000_000;
    public List<PageText> parse(String filename, byte[] bytes) throws Exception {
        List<PageText> pages = new ArrayList<>();
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            try (var pdf = Loader.loadPDF(bytes)) {
                if (pdf.getNumberOfPages() > 500) throw ApiException.bad("文档不能超过 500 页，请拆分后上传");
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                int size = 0;
                for (int page=1; page<=pdf.getNumberOfPages(); page++) {
                    stripper.setStartPage(page); stripper.setEndPage(page);
                    String text = stripper.getText(pdf);
                    size += text.length();
                    if (size > MAX_CHARACTERS) throw ApiException.bad("文档文字过多，请拆分后上传");
                    pages.add(new PageText(page, text));
                }
            }
        } else if (lower.endsWith(".docx")) {
            ZipSecureFile.setMaxEntrySize(25_000_000);
            try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                StringBuilder text = new StringBuilder();
                for (IBodyElement item : doc.getBodyElements()) {
                    if (item instanceof XWPFParagraph paragraph) text.append(paragraph.getText()).append("\n\n");
                    if (item instanceof XWPFTable table) {
                        String header = table.getRows().isEmpty() ? "" : rowText(table.getRows().getFirst());
                        for (int i=0; i<table.getRows().size(); i++) {
                            text.append(i == 0 ? "" : header + " → ").append(rowText(table.getRows().get(i))).append('\n');
                        }
                        text.append('\n');
                    }
                    if (text.length() > MAX_CHARACTERS) throw ApiException.bad("文档文字过多，请拆分后上传");
                }
                pages.add(new PageText(null, text.toString()));
            }
        } else {
            try {
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                if (text.indexOf('\0') >= 0) throw ApiException.bad("文件不是有效的文本文件");
                pages.add(new PageText(null, text));
            } catch (CharacterCodingException e) { throw ApiException.bad("文本文件请使用 UTF-8 编码"); }
        }
        if (pages.stream().mapToInt(p -> p.content().length()).sum() > MAX_CHARACTERS)
            throw ApiException.bad("文档文字过多，请拆分后上传");
        if (pages.stream().allMatch(p -> p.content().isBlank()))
            throw ApiException.bad("未提取到文字。扫描 PDF 和图片需要先进行 OCR 识别，当前版本支持文字型文档");
        return pages;
    }
    private String rowText(XWPFTableRow row) {
        return row.getTableCells().stream().map(XWPFTableCell::getText).collect(java.util.stream.Collectors.joining(" | "));
    }
    public List<Piece> split(List<PageText> pages) {
        List<Piece> pieces = new ArrayList<>();
        for (PageText page : pages) {
            String text = page.content().replace("\r\n", "\n").replace('\r','\n').replace("\u0000", "").strip();
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(start+900, text.length());
                if (end < text.length()) {
                    int boundary = Math.max(text.lastIndexOf('\n',end), text.lastIndexOf('。',end));
                    if (boundary > start+450) end=boundary+1;
                }
                // Do not split a UTF-16 surrogate pair.
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end-1))) end--;
                String content = text.substring(start,end).strip();
                if (!content.isBlank()) pieces.add(new Piece(page.page(),content));
                if (end == text.length()) break;
                start = end-100;
                if (Character.isLowSurrogate(text.charAt(start))) start++;
                if (pieces.size()>1500) throw ApiException.bad("文档分段过多，请拆分后上传");
            }
        }
        return pieces;
    }
}
