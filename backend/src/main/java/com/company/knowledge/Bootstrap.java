package com.company.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Bootstrap implements ApplicationRunner {
    private final Repository repo;
    private final AppProperties config;
    private final PasswordEncoder passwords;
    private final FileStorage files;
    private final TransactionTemplate tx;
    public Bootstrap(Repository repo,AppProperties config,PasswordEncoder passwords,FileStorage files,TransactionTemplate tx) {
        this.repo=repo;this.config=config;this.passwords=passwords;this.files=files;this.tx=tx;
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        if (!config.demo()) {
            String signature=config.ai().embeddingBaseUrl()+"|"+config.ai().embeddingModel()+"|"+config.ai().embeddingDimensions();
            var existing=repo.db().sql("SELECT setting_value FROM app_setting WHERE setting_key='embedding_signature'").query(String.class).optional();
            if (existing.isPresent() && !existing.get().equals(signature))
                throw new IllegalStateException("Embedding configuration differs from the indexed model. Use a new database or explicitly rebuild all document vectors first.");
            if (existing.isEmpty()) repo.db().sql("INSERT INTO app_setting(setting_key,setting_value) VALUES ('embedding_signature',?)").param(signature).update();
            if (repo.db().sql("SELECT COUNT(*) FROM app_user WHERE role='ADMIN'").query(Long.class).single()==0) {
                String password=config.bootstrapPassword();
                if (password==null || password.length()<12 || password.getBytes(StandardCharsets.UTF_8).length>72)
                    throw new IllegalStateException("Set BOOTSTRAP_ADMIN_PASSWORD to a password of at least 12 characters (at most 72 UTF-8 bytes)");
                insertUser("admin","管理员",password,"ADMIN");
            }
            return;
        }
        if (repo.db().sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()>0) return;
        String admin=insertUser("admin","知识库管理员","DemoAdmin123!","ADMIN");
        String member=insertUser("member","示例成员","DemoMember123!","MEMBER");
        String kb=Repository.id();
        repo.db().sql("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES (?,?,?,?)")
            .params(kb,"企业制度与员工指南","从入职到日常协作，让每一个问题都有据可查。",admin).update();
        repo.db().sql("INSERT INTO knowledge_member(knowledge_base_id,user_id) VALUES (?,?)").params(kb,member).update();
        for (String filename:List.of("员工报销管理办法.md","员工休假指南.md","新员工入职清单.md")) {
            byte[] bytes=new ClassPathResource("samples/"+filename).getContentAsByteArray();
            String id=Repository.id(), key=id+".md";
            files.put(key,bytes,"text/plain");
            repo.db().sql("INSERT INTO document(id,knowledge_base_id,name,file_key,content_type,size_bytes,status) VALUES (?,?,?,?,?,?,'QUEUED')")
                .params(id,kb,filename,key,"text/plain",bytes.length).update();
        }
    }
    private String insertUser(String username,String name,String password,String role) {
        String id=Repository.id();
        repo.db().sql("INSERT INTO app_user(id,username,display_name,password_hash,role) VALUES (?,?,?,?,?)")
            .params(id,username,name,passwords.encode(password),role).update();
        return id;
    }
}
