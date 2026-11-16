package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
@EnableScheduling
public class DocumentService {
    private static final Logger log=LoggerFactory.getLogger(DocumentService.class);
    private final Repository repo;
    private final Access access;
    private final FileStorage files;
    private final DocumentParser parser;
    private final AiService ai;
    private final AppProperties config;
    private final TransactionTemplate tx;
    private volatile boolean ready;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),Thread.ofVirtual().name("document-",0).factory());
    public DocumentService(Repository repo,Access access,FileStorage files,DocumentParser parser,AiService ai,AppProperties config,TransactionTemplate tx) {
        this.repo=repo; this.access=access; this.files=files; this.parser=parser; this.ai=ai; this.config=config; this.tx=tx;
    }
    @EventListener(ApplicationReadyEvent.class) void recover() {
        repo.db().sql("UPDATE document SET status='QUEUED',error_message=NULL WHERE status='PROCESSING'").update();
        ready=true;
    }
    @Scheduled(fixedDelay=1500) void dispatch() {
        if (!ready) return;
        var ids=repo.db().sql("SELECT id FROM document WHERE status='QUEUED' ORDER BY created_at LIMIT 2").query(String.class).list();
        for (String id:ids) {
            if (repo.db().sql("UPDATE document SET status='PROCESSING' WHERE id=? AND status='QUEUED'").param(id).update()!=1) continue;
            try { workers.execute(() -> process(id)); }
            catch (RejectedExecutionException e) { repo.db().sql("UPDATE document SET status='QUEUED' WHERE id=? AND status='PROCESSING'").param(id).update(); }
        }
    }
    public Document upload(String kbId,MultipartFile file,User user) throws Exception {
        access.knowledgeBase(kbId,user);
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("document").replace('\\','/');
        name=name.substring(name.lastIndexOf('/')+1).strip();
        if (name.isBlank() || name.length()>255 || name.chars().anyMatch(Character::isISOControl)) throw ApiException.bad("文件名无效或过长");
        int dot=name.lastIndexOf('.');
        String extension=dot<0?"":name.substring(dot+1).toLowerCase(Locale.ROOT);
        if (!Set.of("pdf","docx","md","txt").contains(extension)) throw ApiException.bad("支持 PDF、DOCX、Markdown 和 TXT 文件");
        if (file.isEmpty()) throw ApiException.bad("不能上传空文件");
        if (file.getSize()>20L*1024*1024) throw ApiException.bad("单个文件不能超过 20 MB");
        long count=repo.db().sql("SELECT COUNT(*) FROM document WHERE knowledge_base_id=?").param(kbId).query(Long.class).single();
        if (count>=200) throw ApiException.bad("当前版本每个知识库最多 200 份文档");
        String id=Repository.id(),key=id+"."+extension;
        String mime=switch(extension) { case "pdf" -> "application/pdf"; case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; default -> "text/plain"; };
        files.put(key,file.getBytes(),mime);
        try {
            repo.db().sql("INSERT INTO document(id,knowledge_base_id,name,file_key,content_type,size_bytes,status) VALUES (?,?,?,?,?,?,'QUEUED')")
                .params(id,kbId,name,key,mime,file.getSize()).update();
        } catch (Exception e) { files.delete(key); throw e; }
        return repo.findDocument(id);
    }
    void process(String id) {
        try {
            var document=repo.findDocument(id);
            var pieces=parser.split(parser.parse(document.name(),files.read(document.fileKey())));
            List<float[]> vectors=new ArrayList<>();
            if (!config.demo()) for (int start=0;start<pieces.size();start+=16)
                vectors.addAll(ai.embed(pieces.subList(start,Math.min(start+16,pieces.size())).stream().map(Piece::content).toList()));
            tx.executeWithoutResult(status -> {
                var state=repo.db().sql("SELECT status FROM document WHERE id=? FOR UPDATE").param(id).query(String.class).optional();
                if (state.isEmpty() || !"PROCESSING".equals(state.get())) return;
                Map<Integer,String> previousIds=new HashMap<>();
                repo.db().sql("SELECT id,ordinal FROM document_chunk WHERE document_id=?").param(id)
                    .query((r,i) -> Map.entry(r.getInt("ordinal"),r.getString("id"))).list()
                    .forEach(entry -> previousIds.put(entry.getKey(),entry.getValue()));
                repo.db().sql("DELETE FROM document_chunk WHERE document_id=?").param(id).update();
                for (int i=0;i<pieces.size();i++) {
                    var piece=pieces.get(i);
                    String chunkId=previousIds.getOrDefault(i+1,Repository.id());
                    repo.db().sql("INSERT INTO document_chunk(id,document_id,ordinal,page_number,content) VALUES (?,?,?,?,?)")
                        .param(chunkId).param(id).param(i+1).param(new org.springframework.jdbc.core.SqlParameterValue(java.sql.Types.INTEGER,piece.page())).param(piece.content()).update();
                    if (!config.demo()) repo.db().sql("UPDATE document_chunk SET embedding=CAST(? AS vector) WHERE id=?")
                        .params(RetrievalService.vector(vectors.get(i)),chunkId).update();
                }
                repo.db().sql("UPDATE document SET status='READY',chunk_count=?,error_message=NULL WHERE id=?").params(pieces.size(),id).update();
            });
        } catch (Exception e) {
            log.warn("Document processing failed, document={}, type={}",id,e.getClass().getSimpleName());
            String message=e instanceof ApiException ? e.getMessage() : "解析或模型服务调用失败，请确认文件格式、模型配置和网络后重试";
            repo.db().sql("UPDATE document SET status='FAILED',error_message=? WHERE id=? AND status='PROCESSING'").params(message,id).update();
        }
    }
    public void retry(String id,User user) {
        var doc=repo.findDocument(id); access.knowledgeBase(doc.knowledgeBaseId(),user);
        if (repo.db().sql("UPDATE document SET status='QUEUED',error_message=NULL WHERE id=? AND status='FAILED'").param(id).update()!=1)
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT,"只有处理失败的文档可以重试");
    }
    public void delete(String id,User user) throws Exception {
        var doc=repo.findDocument(id); access.knowledgeBase(doc.knowledgeBaseId(),user);
        tx.executeWithoutResult(status -> {
            repo.db().sql("SELECT id FROM document WHERE id=? FOR UPDATE").param(id).query(String.class).optional();
            repo.db().sql("DELETE FROM document WHERE id=?").param(id).update();
        });
        try { files.delete(doc.fileKey()); }
        catch (Exception e) { log.warn("Orphan file needs cleanup: {}",doc.fileKey()); }
    }
    @PreDestroy void stop() { ready=false; workers.shutdownNow(); }
}
