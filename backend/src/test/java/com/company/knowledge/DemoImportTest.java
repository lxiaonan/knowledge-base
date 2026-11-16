package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

class DemoImportTest {
    @TempDir Path directory;
    private final AppProperties config=new AppProperties(false,"",null,null);
    private String url(Path file) {return "jdbc:h2:file:"+file.toString().replace('\\','/')+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";}
    private void source(Path file,boolean corrupt) throws Exception {
        try(var connection=DriverManager.getConnection(url(file),"sa","");var statement=connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/common/V1__initial_schema.sql"));
            statement.executeUpdate("INSERT INTO app_user(id,username,display_name,password_hash,role) VALUES ('user-1','admin','原管理员','retained-hash','ADMIN')");
            statement.executeUpdate("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES ('kb-1','原知识库','说明','user-1')");
            statement.executeUpdate("INSERT INTO knowledge_member(knowledge_base_id,user_id) VALUES ('kb-1','user-1')");
            statement.executeUpdate("INSERT INTO document(id,knowledge_base_id,name,file_key,content_type,size_bytes,status,chunk_count) VALUES ('doc-1','kb-1','制度.md','file.md','text/plain',10,'READY',1)");
            statement.executeUpdate("INSERT INTO document_chunk(id,document_id,ordinal,page_number,content) VALUES ('chunk-1','doc-1',1,NULL,'需要保留的原文')");
            statement.executeUpdate("INSERT INTO conversation(id,knowledge_base_id,user_id,title) VALUES ('chat-1','kb-1','user-1','历史对话')");
            statement.executeUpdate("INSERT INTO chat_message(id,conversation_id,role,content,sources_json) VALUES ('message-1','chat-1','assistant','历史答案','[]')");
            if(corrupt) statement.executeUpdate("DROP TABLE chat_message");
        }
    }
    private DriverManagerDataSource destination() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:import-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        try(var connection=ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/common/V1__initial_schema.sql"));
        }
        return ds;
    }
    @Test void preservesDataRequeuesDocumentsAndImportsOnlyOnce() throws Exception {
        Path file=directory.resolve("source");source(file,false);
        var ds=destination();var repo=new Repository(JdbcClient.create(ds),new ObjectMapper());
        var importer=new DemoImport(repo,new TransactionTemplate(new DataSourceTransactionManager(ds)),config,file.toString());
        importer.run(null);importer.run(null);
        assertThat(repo.findUser("admin").displayName()).isEqualTo("原管理员");
        assertThat(repo.db().sql("SELECT password_hash FROM app_user").query(String.class).single()).isEqualTo("retained-hash");
        assertThat(repo.findDocument("doc-1").status()).isEqualTo("QUEUED");
        assertThat(repo.db().sql("SELECT id FROM document_chunk").query(String.class).single()).isEqualTo("chunk-1");
        assertThat(repo.db().sql("SELECT content FROM chat_message").query(String.class).single()).isEqualTo("历史答案");
        assertThat(repo.db().sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isEqualTo(1);
        var original=new JdbcTemplate(new DriverManagerDataSource(url(file),"sa",""));
        assertThat(original.queryForObject("SELECT status FROM document",String.class)).isEqualTo("READY");
    }
    @Test void rollsBackEntireImportOnInvalidSource() throws Exception {
        Path file=directory.resolve("broken");source(file,true);
        var ds=destination();var repo=new Repository(JdbcClient.create(ds),new ObjectMapper());
        var importer=new DemoImport(repo,new TransactionTemplate(new DataSourceTransactionManager(ds)),config,file.toString());
        assertThatThrownBy(() -> importer.run(null)).isInstanceOf(IllegalStateException.class);
        assertThat(repo.db().sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isZero();
        assertThat(repo.db().sql("SELECT COUNT(*) FROM document_chunk").query(Long.class).single()).isZero();
        assertThat(repo.db().sql("SELECT COUNT(*) FROM app_setting").query(Long.class).single()).isZero();
    }
}
