package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:data-browser-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.storage.path=target/test-data-browser-files"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class DataBrowserIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Repository repo;
    @Autowired ObjectMapper json;
    private JsonNode read(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(mvc.perform(request.with(user("admin").roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    @Test void restrictsTablesAndParametersToReadOnlyAdministratorQueries() throws Exception {
        mvc.perform(get("/api/admin/data/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/data/overview").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/data/tables/document").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/data/tables/document/rows/invalid").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        var overview=read(get("/api/admin/data/overview"));
        assertThat(overview.get("database").asText()).isEqualTo("H2");
        assertThat(overview.get("tables")).hasSize(8);
        mvc.perform(get("/api/admin/data/tables/{table}","app_user;DROP TABLE app_user").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/data/tables/flyway_schema_history").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
        for(String[] parameter:new String[][]{{"page","0"},{"pageSize","101"},{"page","bad"},{"state","UNKNOWN"},{"q","x".repeat(201)}})
            mvc.perform(get("/api/admin/data/tables/document").param(parameter[0],parameter[1]).with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
        assertThat(read(get("/api/admin/data/tables/document").param("q","' OR 1=1 --")).get("total").asLong()).isZero();
        assertThat(read(get("/api/admin/data/tables/document").param("q","%")).get("total").asLong()).isZero();
        assertThat(repo.db().sql("SELECT COUNT(*) FROM app_user").query(Long.class).single()).isEqualTo(2);
    }
    @Test void paginatesFiltersAndReturnsFullTextInDetail() throws Exception {
        String kb=Repository.id(),owner=repo.findUser("admin").id();
        repo.db().sql("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES (?,?,?,?)").params(kb,"数据库查询测试","分页数据",owner).update();
        String docId="";
        for(int i=0;i<23;i++) {
            String id=Repository.id();docId=id;
            repo.db().sql("INSERT INTO document(id,knowledge_base_id,name,file_key,content_type,size_bytes,status) VALUES (?,?,?,?,?,?,'READY')")
                .params(id,kb,"分页文档 "+i+".md",id+".md","text/plain",100).update();
        }
        var first=read(get("/api/admin/data/tables/document").param("knowledgeBaseId",kb).param("pageSize","10").param("state","READY"));
        var second=read(get("/api/admin/data/tables/document").param("knowledgeBaseId",kb).param("pageSize","10").param("page","2"));
        assertThat(first.get("total").asLong()).isEqualTo(23);
        assertThat(first.get("rows")).hasSize(10);assertThat(second.get("rows")).hasSize(10);
        var keys=new HashSet<String>();first.get("rows").forEach(row->keys.add(row.get("key").asText()));
        second.get("rows").forEach(row->assertThat(keys).doesNotContain(row.get("key").asText()));
        var last=read(get("/api/admin/data/tables/document").param("knowledgeBaseId",kb).param("pageSize","10").param("page","999"));
        assertThat(last.get("page").asInt()).isEqualTo(3);assertThat(last.get("rows")).hasSize(3);
        var document=read(get("/api/admin/data/tables/document/rows/"+first.get("rows").get(0).get("key").asText()));
        assertThat(document.get("values").get("knowledge_base_id").asText()).isEqualTo(kb);
        String chunk=Repository.id(),content="完整知识原文。".repeat(40)+"末尾唯一检索词";
        repo.db().sql("INSERT INTO document_chunk(id,document_id,ordinal,content) VALUES (?,?,1,?)").params(chunk,docId,content).update();
        var rows=read(get("/api/admin/data/tables/document_chunk").param("q","末尾唯一检索词").param("knowledgeBaseId",kb));
        assertThat(rows.get("rows")).hasSize(1);
        assertThat(rows.get("rows").get(0).get("values").get("content_preview").asText()).hasSize(160);
        var detail=read(get("/api/admin/data/tables/document_chunk/rows/"+rows.get("rows").get(0).get("key").asText()));
        assertThat(detail.get("values").get("content").asText()).isEqualTo(content);
        assertThat(read(get("/api/admin/data/tables/document_chunk").param("knowledgeBaseId",kb).param("state","INDEXED")).get("total").asLong()).isZero();
        mvc.perform(get("/api/admin/data/tables/document/rows/bad-key").with(user("admin").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
    @Test void excludesSecretsInBothListAndDetailAndSupportsCompositeKeys() throws Exception {
        var users=read(get("/api/admin/data/tables/app_user"));
        assertThat(users.toString()).doesNotContain("password_hash","$2a$");
        var userDetail=read(get("/api/admin/data/tables/app_user/rows/"+users.get("rows").get(0).get("key").asText()));
        assertThat(userDetail.toString()).doesNotContain("password_hash","$2a$");
        repo.db().sql("INSERT INTO app_setting(setting_key,setting_value) VALUES ('data_browser_secret_fixture','test-private-value')").update();
        var settings=read(get("/api/admin/data/tables/app_setting").param("q","data_browser_secret_fixture"));
        var settingDetail=read(get("/api/admin/data/tables/app_setting/rows/"+settings.get("rows").get(0).get("key").asText()));
        assertThat(settings.toString()).doesNotContain("test-private-value","setting_value");
        assertThat(settingDetail.toString()).doesNotContain("test-private-value","setting_value");
        var members=read(get("/api/admin/data/tables/knowledge_member"));
        assertThat(members.get("rows").size()).isGreaterThan(0);
        var memberDetail=read(get("/api/admin/data/tables/knowledge_member/rows/"+members.get("rows").get(0).get("key").asText()));
        assertThat(memberDetail.get("values").has("knowledge_base_id")).isTrue();
        assertThat(memberDetail.get("values").has("user_id")).isTrue();
    }
}
