package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class DocumentController {
    private final Repository repo;
    private final Access access;
    private final DocumentService service;
    private final FileStorage files;
    private final DocumentParser parser;
    public DocumentController(Repository repo,Access access,DocumentService service,FileStorage files,DocumentParser parser) { this.repo=repo;this.access=access;this.service=service;this.files=files;this.parser=parser; }
    @GetMapping("/api/knowledge-bases/{id}/documents") List<Document> list(@PathVariable String id) {
        access.knowledgeBase(id,access.current());
        return repo.db().sql("SELECT * FROM document WHERE knowledge_base_id=? ORDER BY created_at DESC,id").param(id).query(Repository::document).list();
    }
    @PostMapping("/api/knowledge-bases/{id}/documents") Document upload(@PathVariable String id,@RequestParam("file") MultipartFile file) throws Exception {
        return service.upload(id,file,access.current());
    }
    @PostMapping("/api/documents/{id}/retry") void retry(@PathVariable String id) { service.retry(id,access.current()); }
    @DeleteMapping("/api/documents/{id}") void delete(@PathVariable String id) throws Exception { service.delete(id,access.current()); }
    public record Preview(String id, String name, String format, List<PageText> pages) {}
    @GetMapping("/api/documents/{id}/preview") Preview preview(@PathVariable String id) throws Exception {
        var doc=repo.findDocument(id);
        access.knowledgeBase(doc.knowledgeBaseId(),access.current());
        // Use the original, not overlapping retrieval chunks, so the full text appears once.
        byte[] bytes=files.read(doc.fileKey());
        List<PageText> pages;
        try { pages=parser.parse(doc.name(),bytes); }
        catch (ApiException e) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage()); }
        catch (Exception e) { throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"无法预览此文档，请确认文件未损坏，或下载原文查看"); }
        access.knowledgeBase(doc.knowledgeBaseId(),access.current());
        String format=doc.name().substring(doc.name().lastIndexOf('.')+1).toLowerCase(java.util.Locale.ROOT);
        return new Preview(doc.id(),doc.name(),format,pages);
    }
    @GetMapping("/api/documents/{id}/file") ResponseEntity<byte[]> download(@PathVariable String id) throws Exception {
        var doc=repo.findDocument(id);access.knowledgeBase(doc.knowledgeBaseId(),access.current());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(doc.name(),StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options","nosniff").body(files.read(doc.fileKey()));
    }
    @GetMapping("/api/sources/{id}") Chunk source(@PathVariable String id) {
        var chunk=repo.db().sql("SELECT c.*,d.name document_name FROM document_chunk c JOIN document d ON d.id=c.document_id WHERE c.id=?")
            .param(id).query(Repository::chunk).optional().orElseThrow(ApiException::missing);
        access.knowledgeBase(repo.findDocument(chunk.documentId()).knowledgeBaseId(),access.current());
        return chunk;
    }
}
