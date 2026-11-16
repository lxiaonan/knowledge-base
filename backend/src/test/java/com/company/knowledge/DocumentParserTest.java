package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class DocumentParserTest {
    private final DocumentParser parser=new DocumentParser();
    @Test void retainsPdfPageNumbers() throws Exception {
        byte[] bytes;
        try (var pdf=new PDDocument();var out=new ByteArrayOutputStream()) {
            for (String text:List.of("Accommodation allowance 400 RMB", "Submit expenses within 15 days")) {
                var page=new PDPage();pdf.addPage(page);
                try (var canvas=new PDPageContentStream(pdf,page)) {
                    canvas.beginText();canvas.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
                    canvas.newLineAtOffset(40,700);canvas.showText(text);canvas.endText();
                }
            }
            pdf.save(out);bytes=out.toByteArray();
        }
        var pieces=parser.split(parser.parse("policy.pdf",bytes));
        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(0).page()).isEqualTo(1);
        assertThat(pieces.get(1).page()).isEqualTo(2);
        assertThat(pieces.get(0).content()).contains("400 RMB");
    }
    @Test void retainsDocxTableHeadersWithRows() throws Exception {
        byte[] bytes;
        try (var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("住宿标准");
            var table=doc.createTable(2,2);
            table.getRow(0).getCell(0).setText("人员");table.getRow(0).getCell(1).setText("金额");
            table.getRow(1).getCell(0).setText("一般员工");table.getRow(1).getCell(1).setText("400 元");
            doc.write(out);bytes=out.toByteArray();
        }
        assertThat(parser.parse("policy.docx",bytes).getFirst().content()).contains("人员 | 金额 → 一般员工 | 400 元");
    }
    @Test void rejectsEmptyAndNonUtf8Text() {
        assertThatThrownBy(() -> parser.parse("empty.txt",new byte[0])).isInstanceOf(ApiException.class).hasMessageContaining("未提取到文字");
        assertThatThrownBy(() -> parser.parse("binary.txt",new byte[]{(byte)0xff})).isInstanceOf(ApiException.class).hasMessageContaining("UTF-8");
    }
    @Test void chunksCoverAllTextAndRetainPage() throws Exception {
        String text="这是需要完整保留的企业知识段落。\n".repeat(160);
        var pieces=parser.split(parser.parse("test.md",text.getBytes(StandardCharsets.UTF_8)));
        assertThat(pieces.size()).isGreaterThan(2);
        assertThat(pieces).allSatisfy(p -> {assertThat(p.content().length()).isLessThanOrEqualTo(901);assertThat(p.page()).isNull();});
        assertThat(pieces.getLast().content()).endsWith("企业知识段落。");
    }
}
