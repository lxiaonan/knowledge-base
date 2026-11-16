package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class Repository {
    private final JdbcClient db;
    private final ObjectMapper json;
    public Repository(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }
    public JdbcClient db() { return db; }
    public static String id() { return UUID.randomUUID().toString(); }
    static User user(ResultSet r, int i) throws SQLException {
        return new User(r.getString("id"), r.getString("username"), r.getString("display_name"), r.getString("role"));
    }
    static Document document(ResultSet r, int i) throws SQLException {
        return new Document(r.getString("id"), r.getString("knowledge_base_id"), r.getString("name"),
            r.getString("file_key"), r.getString("content_type"), r.getLong("size_bytes"), r.getString("status"),
            r.getString("error_message"), r.getInt("chunk_count"), r.getTimestamp("created_at").toInstant());
    }
    static Chunk chunk(ResultSet r, int i) throws SQLException {
        return new Chunk(r.getString("id"), r.getString("document_id"), r.getString("document_name"),
            r.getInt("ordinal"), r.getObject("page_number", Integer.class), r.getString("content"));
    }
    static Conversation conversation(ResultSet r, int i) throws SQLException {
        return new Conversation(r.getString("id"), r.getString("knowledge_base_id"), r.getString("title"), r.getTimestamp("created_at").toInstant());
    }
    public User findUser(String username) {
        return db.sql("SELECT * FROM app_user WHERE username = ?").param(username).query(Repository::user).optional().orElseThrow(ApiException::missing);
    }
    public Document findDocument(String id) {
        return db.sql("SELECT * FROM document WHERE id = ?").param(id).query(Repository::document).optional().orElseThrow(ApiException::missing);
    }
    public List<Chunk> chunks(String kbId) {
        return db.sql("SELECT c.*, d.name document_name FROM document_chunk c JOIN document d ON d.id = c.document_id WHERE d.knowledge_base_id = ? AND d.status = 'READY'")
            .param(kbId).query(Repository::chunk).list();
    }
    public List<Message> messages(String conversationId) {
        return db.sql("SELECT * FROM chat_message WHERE conversation_id = ? ORDER BY created_at, id").param(conversationId)
            .query((r, i) -> new Message(r.getString("id"), r.getString("role"), r.getString("content"),
                readSources(r.getString("sources_json")), r.getTimestamp("created_at").toInstant())).list();
    }
    public List<Message> recentContext(String conversationId) {
        var recent=new java.util.ArrayList<>(db.sql("SELECT id,role,LEFT(content,2100) content,created_at FROM chat_message WHERE conversation_id=? ORDER BY created_at DESC,id DESC LIMIT 16")
            .param(conversationId).query((r,i)->new Message(r.getString("id"),r.getString("role"),r.getString("content"),List.of(),r.getTimestamp("created_at").toInstant())).list());
        java.util.Collections.reverse(recent);
        return ChatContext.recentCompleted(recent);
    }
    public Message saveMessage(String conversationId, String role, String content, List<Source> sources) {
        String id = id();
        db.sql("INSERT INTO chat_message(id, conversation_id, role, content, sources_json) VALUES (?, ?, ?, ?, ?)")
            .params(id, conversationId, role, content, write(sources)).update();
        return db.sql("SELECT * FROM chat_message WHERE id = ?").param(id).query((r,i) -> new Message(id, role, content, sources, r.getTimestamp("created_at").toInstant())).single();
    }
    private List<Source> readSources(String value) {
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored source metadata", e); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
}
