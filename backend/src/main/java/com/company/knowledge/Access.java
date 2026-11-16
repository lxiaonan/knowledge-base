package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.util.Map;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class Access {
    private final Repository repo;
    public Access(Repository repo) { this.repo = repo; }
    public User current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal()))
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "请先登录");
        return repo.findUser(auth.getName());
    }
    public void knowledgeBase(String id, User user) {
        var owner = repo.db().sql("SELECT owner_id FROM knowledge_base WHERE id = ?").param(id).query(String.class).optional().orElseThrow(ApiException::missing);
        if ("ADMIN".equals(user.role()) || owner.equals(user.id())) return;
        boolean member = repo.db().sql("SELECT COUNT(*) FROM knowledge_member WHERE knowledge_base_id = ? AND user_id = ?")
            .params(id, user.id()).query(Long.class).single() > 0;
        if (!member) throw ApiException.forbidden();
    }
    public void manage(String id, User user) {
        knowledgeBase(id, user);
        String owner = repo.db().sql("SELECT owner_id FROM knowledge_base WHERE id = ?").param(id).query(String.class).single();
        if (!"ADMIN".equals(user.role()) && !owner.equals(user.id())) throw ApiException.forbidden();
    }
    public Conversation conversation(String id, User user) {
        var row = repo.db().sql("SELECT * FROM conversation WHERE id = ?").param(id).query((r,i) -> Map.of("owner", r.getString("user_id"), "conversation", Repository.conversation(r,i)))
            .optional().orElseThrow(ApiException::missing);
        if (!user.id().equals(row.get("owner"))) throw ApiException.forbidden();
        var conversation = (Conversation) row.get("conversation");
        knowledgeBase(conversation.knowledgeBaseId(), user);
        return conversation;
    }
}
