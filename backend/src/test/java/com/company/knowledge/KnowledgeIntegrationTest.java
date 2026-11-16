package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:knowledge-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.storage.path=target/test-files"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class KnowledgeIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Repository repo;
    @Autowired DocumentService documents;
    @Autowired RetrievalService retrieval;

    MockHttpSession login(String username,String password) throws Exception {
        var result=mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("username",username,"password",password))))
            .andExpect(status().isOk()).andReturn();
        return (MockHttpSession)result.getRequest().getSession(false);
    }
    JsonNode body(MvcResult result) throws Exception {return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));}
    String createBase(MockHttpSession session,String name) throws Exception {
        return body(mvc.perform(post("/api/knowledge-bases").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(java.util.Map.of("name",name,"description","测试知识库")))).andExpect(status().isOk()).andReturn()).get("id").asText();
    }
    @Test void requiresLoginAndCsrfAndValidPassword() throws Exception {
        mvc.perform(get("/api/knowledge-bases")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"DemoAdmin123!\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"admin\",\"password\":\"incorrect\"}"))
            .andExpect(status().isUnauthorized());
        var session=login("admin","DemoAdmin123!");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("admin"));
        mvc.perform(post("/api/knowledge-bases").session(session).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"无令牌\"}"))
            .andExpect(status().isForbidden());
    }
    @Test void uploadRetrieveCiteDeleteAndPermissionBoundaries() throws Exception {
        var admin=login("admin","DemoAdmin123!");
        var member=login("member","DemoMember123!");
        String kb=createBase(admin,"隔离的研发知识库");
        var file=new MockMultipartFile("file","研发制度.md","text/plain","# 独立研发制度\n\n星河项目代码评审必须由两名工程师批准后才能合并。".getBytes(StandardCharsets.UTF_8));
        var result=mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(file).session(admin).with(csrf()))
            .andExpect(status().isOk()).andReturn();
        String id=body(result).get("id").asText();
        repo.db().sql("UPDATE document SET status='PROCESSING' WHERE id=?").param(id).update();
        documents.process(id);
        assertThat(repo.findDocument(id).status()).isEqualTo("READY");
        mvc.perform(get("/api/documents/"+id+"/preview").session(admin)).andExpect(status().isOk())
            .andExpect(jsonPath("$.format").value("md"))
            .andExpect(jsonPath("$.pages[0].content").value("# 独立研发制度\n\n星河项目代码评审必须由两名工程师批准后才能合并。"));
        var sources=retrieval.search(kb,"星河项目代码评审的合并条件");
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().excerpt()).contains("两名工程师");
        String chunk=sources.getFirst().chunkId();
        mvc.perform(get("/api/knowledge-bases/"+kb+"/documents").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/"+id+"/file").session(member)).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge-bases/"+kb+"/search").session(member).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"代码评审\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/knowledge-bases/"+kb+"/search").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"question\":\"星河项目代码评审\"}")).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].documentId").value(id));
        mvc.perform(get("/api/documents/"+id+"/preview").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/sources/"+chunk).session(member)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/"+id).session(member).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(file).session(member).with(csrf())).andExpect(status().isForbidden());
        String conversation=body(mvc.perform(post("/api/knowledge-bases/"+kb+"/conversations").session(admin).with(csrf()))
            .andExpect(status().isOk()).andReturn()).get("id").asText();
        var async=mvc.perform(post("/api/conversations/"+conversation+"/chat").session(admin).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"星河项目代码评审怎么合并？\"}"))
            .andExpect(request().asyncStarted()).andReturn();
        async.getAsyncResult(10_000);
        String stream=mvc.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:sources","两名工程师","event:done");
        assertThat(repo.messages(conversation)).hasSize(2);
        mvc.perform(post("/api/knowledge-bases/"+kb+"/members").session(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"member\"}"))
            .andExpect(status().isOk());
        mvc.perform(get("/api/sources/"+chunk).session(member)).andExpect(status().isOk());
        mvc.perform(get("/api/documents/"+id+"/preview").session(member)).andExpect(status().isOk());
        // Even another member of the same knowledge base cannot read a private conversation.
        mvc.perform(get("/api/conversations/"+conversation+"/messages").session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/knowledge-bases/"+kb+"/members").session(member)).andExpect(status().isForbidden());
        String memberId=repo.findUser("member").id();
        mvc.perform(delete("/api/knowledge-bases/"+kb+"/members/"+memberId).session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(get("/api/sources/"+chunk).session(member)).andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/"+id+"/preview").session(member)).andExpect(status().isForbidden());
        assertThat(retrieval.search(kb,"北极熊冬眠的生物学特征")).isEmpty();
        mvc.perform(delete("/api/documents/"+id).session(admin).with(csrf())).andExpect(status().isOk());
        assertThat(retrieval.search(kb,"星河项目代码评审")).isEmpty();
        mvc.perform(get("/api/sources/"+chunk).session(admin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/documents/"+id+"/preview").session(admin)).andExpect(status().isNotFound());
        assertThat(repo.messages(conversation).getLast().sources()).hasSize(1); // Historical citations retain their snapshot.
    }
    @Test void previewUsesCompleteOriginalWithoutOverlappingRetrievalChunks() throws Exception {
        var admin=login("admin","DemoAdmin123!");
        String kb=createBase(admin,"全文在线阅读");
        String text="# 完整制度\n\n"+"这是需要完整保留的企业条款。\n".repeat(180)+"\n最后一条：全文结束。";
        var file=new MockMultipartFile("file","long-policy.md","text/plain",text.getBytes(StandardCharsets.UTF_8));
        String id=body(mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(file).session(admin).with(csrf()))
            .andExpect(status().isOk()).andReturn()).get("id").asText();
        // Reading the original remains available independently of embedding/index status.
        repo.db().sql("UPDATE document SET status='FAILED',error_message='模型暂不可用' WHERE id=?").param(id).update();
        mvc.perform(get("/api/documents/"+id+"/preview")).andExpect(status().isUnauthorized());
        var preview=body(mvc.perform(get("/api/documents/"+id+"/preview").session(admin)).andExpect(status().isOk()).andReturn());
        assertThat(preview.get("pages")).hasSize(1);
        assertThat(preview.get("pages").get(0).get("content").asText()).isEqualTo(text);
    }
    @Test void invalidDocumentsFailClearlyAndCanRetry() throws Exception {
        var admin=login("admin","DemoAdmin123!");
        String kb=createBase(admin,"文件校验");
        var invalid=new MockMultipartFile("file","bad.exe","application/octet-stream",new byte[]{1});
        mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(invalid).session(admin).with(csrf())).andExpect(status().isBadRequest());
        var empty=new MockMultipartFile("file","empty.txt","text/plain",new byte[0]);
        mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(empty).session(admin).with(csrf())).andExpect(status().isBadRequest());
        var broken=new MockMultipartFile("file","broken.pdf","application/pdf","not a pdf".getBytes(StandardCharsets.UTF_8));
        String id=body(mvc.perform(multipart("/api/knowledge-bases/"+kb+"/documents").file(broken).session(admin).with(csrf())).andExpect(status().isOk()).andReturn()).get("id").asText();
        repo.db().sql("UPDATE document SET status='PROCESSING' WHERE id=?").param(id).update();documents.process(id);
        assertThat(repo.findDocument(id).status()).isEqualTo("FAILED");
        mvc.perform(get("/api/documents/"+id+"/preview").session(admin)).andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.message").value("无法预览此文档，请确认文件未损坏，或下载原文查看"));
        mvc.perform(post("/api/documents/"+id+"/retry").session(admin).with(csrf())).andExpect(status().isOk());
        mvc.perform(post("/api/documents/"+id+"/retry").session(admin).with(csrf())).andExpect(status().isConflict());
    }
}
