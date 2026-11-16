package com.company.knowledge;

import static com.company.knowledge.Models.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeController {
    private final Repository repo;
    private final Access access;
    public KnowledgeController(Repository repo, Access access) { this.repo=repo; this.access=access; }
    public record NewBase(@NotBlank @Size(max=80) String name, @Size(max=500) String description) {}
    public record Member(@NotBlank @Size(max=64) String username) {}
    @GetMapping List<KnowledgeBase> list() {
        var user = access.current();
        return repo.db().sql("""
            SELECT k.*, (SELECT COUNT(*) FROM document d WHERE d.knowledge_base_id = k.id) document_count,
              (SELECT COUNT(*) FROM document d WHERE d.knowledge_base_id = k.id AND d.status = 'READY') ready_count
            FROM knowledge_base k WHERE ? = 'ADMIN' OR k.owner_id = ? OR EXISTS
              (SELECT 1 FROM knowledge_member m WHERE m.knowledge_base_id = k.id AND m.user_id = ?)
            ORDER BY k.created_at, k.id
            """).params(user.role(), user.id(), user.id()).query((r,i) -> new KnowledgeBase(r.getString("id"),r.getString("name"),r.getString("description"),
                r.getString("owner_id"),r.getLong("document_count"),r.getLong("ready_count"),"ADMIN".equals(user.role()) || user.id().equals(r.getString("owner_id")))).list();
    }
    @PostMapping KnowledgeBase create(@Valid @RequestBody NewBase input) {
        var user = access.current();
        String id = Repository.id();
        repo.db().sql("INSERT INTO knowledge_base(id, name, description, owner_id) VALUES (?, ?, ?, ?)")
            .params(id, input.name().strip(), input.description() == null ? "" : input.description().strip(), user.id()).update();
        return list().stream().filter(k -> k.id().equals(id)).findFirst().orElseThrow();
    }
    @GetMapping("/{id}/members") List<User> members(@PathVariable String id) {
        access.manage(id, access.current());
        return repo.db().sql("SELECT u.* FROM app_user u WHERE u.id = (SELECT owner_id FROM knowledge_base WHERE id = ?) OR EXISTS (SELECT 1 FROM knowledge_member m WHERE m.user_id=u.id AND m.knowledge_base_id=?) ORDER BY u.username")
            .params(id,id).query(Repository::user).list();
    }
    @PostMapping("/{id}/members") User addMember(@PathVariable String id, @Valid @RequestBody Member input) {
        access.manage(id, access.current());
        var user = repo.findUser(input.username());
        if (repo.db().sql("SELECT COUNT(*) FROM knowledge_member WHERE knowledge_base_id=? AND user_id=?").params(id,user.id()).query(Long.class).single() == 0)
            repo.db().sql("INSERT INTO knowledge_member(knowledge_base_id, user_id) VALUES (?, ?)").params(id,user.id()).update();
        return user;
    }
    @DeleteMapping("/{id}/members/{userId}") void removeMember(@PathVariable String id, @PathVariable String userId) {
        access.manage(id, access.current());
        repo.db().sql("DELETE FROM knowledge_member WHERE knowledge_base_id=? AND user_id=?").params(id,userId).update();
    }
}
