package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:settings-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.storage.path=target/test-settings-files", "app.settings-key-path=target/test-settings-config/settings.key"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ChatSettingsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Repository repo;
    @Autowired ChatSettings settings;
    @Autowired AiService ai;
    @Autowired AppProperties properties;
    @Autowired ObjectMapper json;
    private String body(String endpoint,String model,String key) throws Exception {
        return json.writeValueAsString(Map.of("baseUrl",endpoint,"model",model,"apiKey",key));
    }
    @Test void administratorCanSaveEncryptedConfigurationWithoutLeakingItAndApplyItImmediately() throws Exception {
        String secret="test-settings-private-key";
        mvc.perform(get("/api/settings/chat")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/settings/chat").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/chat").with(user("member").roles("MEMBER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body("https://ai-api.m2time.com","gpt-6.1-sol",secret))).andExpect(status().isForbidden());
        mvc.perform(put("/api/settings/chat").with(user("admin").roles("ADMIN")).contentType(MediaType.APPLICATION_JSON)
            .content(body("https://ai-api.m2time.com","gpt-6.1-sol",secret))).andExpect(status().isForbidden());
        var saved=mvc.perform(put("/api/settings/chat").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body("https://ai-api.m2time.com/","gpt-6.1-sol",secret))).andExpect(status().isOk())
            .andExpect(jsonPath("$.keyConfigured").value(true)).andExpect(jsonPath("$.apiKey").doesNotExist()).andReturn();
        assertThat(saved.getResponse().getContentAsString()).doesNotContain(secret);
        String stored=repo.db().sql("SELECT setting_value FROM app_setting WHERE setting_key='chat_config_encrypted'").query(String.class).single();
        assertThat(stored).startsWith("v1:").doesNotContain(secret);
        assertThat(ai.chatConfigured()).isTrue();
        var reloaded=new ChatSettings(repo,properties,"target/test-settings-config/settings.key");
        assertThat(reloaded.current().apiKey()).isEqualTo(secret);
        assertThat(Files.size(Path.of("target/test-settings-config/settings.key"))).isEqualTo(32);
        mvc.perform(get("/api/settings/chat").with(user("admin").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(put("/api/settings/chat").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body("https://ai-api.m2time.com","gpt-6.1-sol",""))).andExpect(status().isOk()).andExpect(jsonPath("$.keyConfigured").value(true));
        assertThat(settings.current().apiKey()).isEqualTo(secret);
        mvc.perform(get("/api/system")).andExpect(status().isOk()).andExpect(jsonPath("$.chatConfigured").value(true));
        mvc.perform(put("/api/settings/chat").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body("file:///etc/passwd","gpt-6.1-sol",secret))).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/settings/chat/key").with(user("member").roles("MEMBER")).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(delete("/api/settings/chat/key").with(user("admin").roles("ADMIN")).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.keyConfigured").value(false));
        assertThat(ai.chatConfigured()).isFalse();
        mvc.perform(post("/api/settings/chat/test").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(body("https://ai-api.m2time.com","gpt-6.1-sol",""))).andExpect(status().isBadRequest());
    }
    @Test void connectionTestUsesDraftConfigurationWithoutSavingIt() throws Exception {
        var request=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String data="data: {\"id\":\"test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"gpt-6.1-sol\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
            byte[] bytes=data.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        var before=settings.current();
        try {
            mvc.perform(post("/api/settings/chat/test").with(user("admin").roles("ADMIN")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body("http://127.0.0.1:"+server.getAddress().getPort(),"gpt-6.1-sol","draft-test-key")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("连接成功，指定模型已返回响应"));
            assertThat(json.readTree(request.get()).get("model").asText()).isEqualTo("gpt-6.1-sol");
            assertThat(request.get()).doesNotContain("企业制度","员工报销");
            assertThat(settings.current()).isEqualTo(before);
        } finally {server.stop(0);}
    }
}
