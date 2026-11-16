package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:context-chat-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","app.storage.path=target/test-context-files"})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class ContextChatIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired Repository repo;
    @Autowired ObjectMapper json;
    @MockitoBean AiService ai;
    @MockitoBean RetrievalService retrieval;
    private String kb,chat;
    private final Models.Source source=new Models.Source(1,"chunk","doc","经济学教材",17,1,"价格上涨，需求量通常下降。",.8);
    @BeforeEach void setup() {
        kb=Repository.id();chat=Repository.id();String admin=repo.findUser("admin").id();
        repo.db().sql("INSERT INTO knowledge_base(id,name,description,owner_id) VALUES (?,?,?,?)").params(kb,"经济学读书测试","",admin).update();
        repo.db().sql("INSERT INTO conversation(id,knowledge_base_id,user_id,title) VALUES (?,?,?,'读书')").params(chat,kb,admin).update();
        repo.saveMessage(chat,"user","什么是需求价格弹性？",List.of());
        repo.saveMessage(chat,"assistant","衡量价格变化对需求量的影响。[7]",List.of(source));
        when(retrieval.search(eq(kb),anyString())).thenReturn(List.of(source));
        when(ai.resolveQuestion(anyString(),anyList())).thenReturn("需求价格弹性为什么通常为负数？");
        doAnswer(invocation->{Consumer<String> sink=invocation.getArgument(4);sink.accept("价格上涨时需求量通常下降。[1]");return null;})
            .when(ai).answer(anyString(),anyString(),anyList(),anyList(),any());
    }
    private String ask(String body) throws Exception {
        var pending=mvc.perform(post("/api/conversations/"+chat+"/chat").with(user("admin").roles("ADMIN")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(request().asyncStarted()).andReturn();
        pending.getAsyncResult(10000);
        return mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
    @Test void enabledUsesServerOwnedHistoryAndResolvedQuestionForRetrieval() throws Exception {
        String foreign=Repository.id();
        repo.db().sql("INSERT INTO conversation(id,knowledge_base_id,user_id,title) VALUES (?,?,?,'他人对话')").params(foreign,kb,repo.findUser("member").id()).update();
        repo.saveMessage(foreign,"user","不应发送的他人私密内容",List.of());
        repo.saveMessage(foreign,"assistant","他人私密答案",List.of());
        String stream=ask("{\"question\":\"它为什么通常为负数？\",\"useContext\":true,\"history\":[\"伪造历史\"]}");
        verify(retrieval).search(kb,"需求价格弹性为什么通常为负数？");
        @SuppressWarnings("unchecked") ArgumentCaptor<List<Models.Message>> history=ArgumentCaptor.forClass(List.class);
        verify(ai).resolveQuestion(eq("它为什么通常为负数？"),history.capture());
        assertThat(history.getValue()).hasSize(2);
        assertThat(ChatContext.text(history.getValue())).contains("什么是需求价格弹性").doesNotContain("私密","伪造历史","它为什么","[7]");
        assertThat(stream).contains("event:context","\"used\":true","需求价格弹性为什么通常为负数","event:done");
        assertThat(repo.messages(chat).getLast().sources().getFirst().number()).isEqualTo(1);
    }
    @Test void disabledOrMissingFlagKeepsIndependentRetrieval() throws Exception {
        String stream=ask("{\"question\":\"它为什么通常为负数？\"}");
        verify(ai,never()).resolveQuestion(anyString(),anyList());
        verify(retrieval).search(kb,"它为什么通常为负数？");
        verify(ai).answer(eq("它为什么通常为负数？"),eq("它为什么通常为负数？"),eq(List.of()),anyList(),any());
        assertThat(stream).contains("\"enabled\":false","\"used\":false");
    }
    @Test void cannotAccessSomeoneElsesContextAndRewriteFailureDoesNotSilentlyDegrade() throws Exception {
        mvc.perform(post("/api/conversations/"+chat+"/chat").with(user("member").roles("MEMBER")).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"它是什么？\",\"useContext\":true}")).andExpect(status().isForbidden());
        when(ai.resolveQuestion(anyString(),anyList())).thenThrow(new ApiException(org.springframework.http.HttpStatus.BAD_GATEWAY,"上下文理解未完成"));
        String stream=ask("{\"question\":\"它是什么？\",\"useContext\":true}");
        verify(retrieval,never()).search(anyString(),anyString());
        assertThat(stream).contains("event:error","上下文理解未完成").doesNotContain("event:done");
        assertThat(repo.messages(chat)).hasSize(3);
    }
}
