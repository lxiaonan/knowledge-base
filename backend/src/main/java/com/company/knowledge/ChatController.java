package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class ChatController {
    private static final Logger log=LoggerFactory.getLogger(ChatController.class);
    private final Repository repo;
    private final Access access;
    private final RetrievalService retrieval;
    private final AiService ai;
    private final Set<String> busy=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(16);
    public ChatController(Repository repo,Access access,RetrievalService retrieval,AiService ai) { this.repo=repo;this.access=access;this.retrieval=retrieval;this.ai=ai; }
    public record Question(@NotBlank @Size(max=2000) String question) {}
    public record ChatQuestion(@NotBlank @Size(max=2000) String question,boolean useContext) {}
    @PostMapping("/api/knowledge-bases/{id}/search") List<Source> search(@PathVariable String id,@Valid @RequestBody Question input) {
        var user=access.current();access.knowledgeBase(id,user);
        var sources=retrieval.search(id,input.question().strip());
        access.knowledgeBase(id,user);
        return sources;
    }
    @GetMapping("/api/knowledge-bases/{id}/conversations") List<Conversation> conversations(@PathVariable String id) {
        var user=access.current(); access.knowledgeBase(id,user);
        return repo.db().sql("SELECT * FROM conversation WHERE knowledge_base_id=? AND user_id=? ORDER BY created_at DESC,id")
            .params(id,user.id()).query(Repository::conversation).list();
    }
    @PostMapping("/api/knowledge-bases/{id}/conversations") Conversation create(@PathVariable String id) {
        var user=access.current();access.knowledgeBase(id,user);
        String conversationId=Repository.id();
        repo.db().sql("INSERT INTO conversation(id,knowledge_base_id,user_id,title) VALUES (?,?,?,'新对话')").params(conversationId,id,user.id()).update();
        return access.conversation(conversationId,user);
    }
    @GetMapping("/api/conversations/{id}/messages") List<Message> messages(@PathVariable String id) {
        access.conversation(id,access.current()); return repo.messages(id);
    }
    @PostMapping(value="/api/conversations/{id}/chat",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter chat(@PathVariable String id,@Valid @RequestBody ChatQuestion input) {
        var user=access.current();var conversation=access.conversation(id,user);
        if (!busy.add(id)) throw new ApiException(HttpStatus.CONFLICT,"此对话正在生成回答，请等待完成");
        if (!capacity.tryAcquire()) { busy.remove(id);throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"当前提问人数较多，请稍后重试"); }
        var emitter=new SseEmitter(180_000L);
        var cancelled=new AtomicBoolean(false);
        emitter.onCompletion(() -> cancelled.set(true));
        emitter.onTimeout(() -> {cancelled.set(true);emitter.complete();});
        emitter.onError(e -> cancelled.set(true));
        Thread.ofVirtual().name("chat-"+id).start(() -> {
            try {
                String question=input.question().strip();
                var history=input.useContext()?repo.recentContext(id):List.<Message>of();
                repo.saveMessage(id,"user",question,List.of());
                repo.db().sql("UPDATE conversation SET title=? WHERE id=? AND title='新对话'").params(question.substring(0,Math.min(40,question.length())),id).update();
                String retrievalQuestion=question;
                if(!history.isEmpty()) {
                    send(emitter,cancelled,"status",Map.of("message","正在结合最近对话理解追问…"));
                    retrievalQuestion=ai.resolveQuestion(question,history);
                }
                access.conversation(id,user);
                send(emitter,cancelled,"context",Map.of("enabled",input.useContext(),"used",!history.isEmpty(),"query",retrievalQuestion,"turns",history.size()/2));
                send(emitter,cancelled,"status",Map.of("message","正在检索相关资料…"));
                var sources=retrieval.search(conversation.knowledgeBaseId(),retrievalQuestion);
                // Recheck access after potentially slow retrieval, before exposing any source.
                access.conversation(id,user);
                send(emitter,cancelled,"sources",sources);
                StringBuilder answer=new StringBuilder();
                send(emitter,cancelled,"status",Map.of("message","正在根据检索资料生成回答…"));
                ai.answer(question,retrievalQuestion,history,sources,token -> {
                    if (answer.length()+token.length()>30_000) throw new IllegalStateException("Answer too long");
                    answer.append(token);send(emitter,cancelled,"token",Map.of("text",token));
                });
                if (answer.isEmpty()) throw new IllegalStateException("Empty model response");
                access.conversation(id,user);
                var message=repo.saveMessage(id,"assistant",answer.toString(),sources);
                send(emitter,cancelled,"done",message);
                emitter.complete();
            } catch (Exception e) {
                log.warn("Chat failed, conversation={}, type={}",id,e.getClass().getSimpleName());
                if (!cancelled.get()) {
                    String message=e instanceof ApiException ? e.getMessage() : "回答未完成，请检查模型服务或稍后重新提问";
                    try { send(emitter,cancelled,"error",Map.of("message",message));emitter.complete(); }
                    catch (Exception ignored) { emitter.completeWithError(e); }
                }
            } finally { busy.remove(id);capacity.release(); }
        });
        return emitter;
    }
    private void send(SseEmitter emitter,AtomicBoolean cancelled,String name,Object data) {
        if (cancelled.get()) throw new IllegalStateException("Client disconnected");
        try { emitter.send(SseEmitter.event().name(name).data(data,MediaType.APPLICATION_JSON)); }
        catch (IOException e) {cancelled.set(true);throw new IllegalStateException("Client disconnected",e);}
    }
}
