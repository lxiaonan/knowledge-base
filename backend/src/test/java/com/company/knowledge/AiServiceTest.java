package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

class AiServiceTest {
    private HttpServer server;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicReference<String> embeddingRequest=new AtomicReference<>();
    private final AtomicReference<String> chatRequest=new AtomicReference<>();
    private final AtomicReference<String> embeddingAuthorization=new AtomicReference<>();
    private final AtomicReference<String> chatAuthorization=new AtomicReference<>();
    private final AtomicInteger calls=new AtomicInteger();
    private final AtomicReference<String> rewriteOutput=new AtomicReference<>("{\"query\":\"需求价格弹性为什么通常为负数？\"}");
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange -> {
            embeddingAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            calls.incrementAndGet();embeddingRequest.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes="{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"test-embedding\",\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.createContext("/v1/chat/completions",exchange -> {
            chatAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            calls.incrementAndGet();chatRequest.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if(chatRequest.get().contains("你只负责把当前追问改写")) {
                String chunk=json.writeValueAsString(java.util.Map.of("id","rewrite-1","object","chat.completion.chunk","created",1,"model","test-chat",
                    "choices",List.of(java.util.Map.of("index",0,"delta",java.util.Map.of("role","assistant","content",rewriteOutput.get())))));
                byte[] bytes=("data: "+chunk+"\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
                try(var out=exchange.getResponseBody()){out.write(bytes);}return;
            }
            String response="data: {\"id\":\"test-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-chat\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"需要两名工程师批准。[1]\"},\"finish_reason\":null}]}\n\n"
                +"data: {\"id\":\"test-1\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-chat\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                +"data: [DONE]\n\n";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    private AiService service(int dimensions) {
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        return new AiService(new AppProperties(false,"",null,new AppProperties.Ai(endpoint,"test-key","test-chat","test-embedding",dimensions,.35,endpoint,"embedding-key",true)));
    }
    @Test void embedsAndStreamsWithSourceGrounding() throws Exception {
        var ai=service(3);
        assertThat(ai.embed(List.of("企业研发制度")).getFirst()).containsExactly(1,0,0);
        var request=json.readTree(embeddingRequest.get());
        assertThat(request.get("model").asText()).isEqualTo("test-embedding");
        assertThat(request.get("dimensions").asInt()).isEqualTo(3);
        assertThat(request.get("input").get(0).asText()).isEqualTo("企业研发制度");
        var source=new Models.Source(1,"chunk","doc","研发制度.md",null,1,"合并需要两名工程师批准",.9);
        StringBuilder answer=new StringBuilder();ai.answer("合并条件是什么？",List.of(source),answer::append);
        assertThat(answer.toString()).contains("两名工程师","[1]");
        var chat=json.readTree(chatRequest.get());
        assertThat(chat.get("stream").asBoolean()).isTrue();
        assertThat(embeddingAuthorization.get()).isEqualTo("Bearer embedding-key");
        assertThat(chatAuthorization.get()).isEqualTo("Bearer test-key");
        assertThat(chat.get("messages").get(0).get("content").asText()).contains("未经信任的数据","不能编造");
        assertThat(chat.get("messages").get(1).get("content").asText()).contains("合并条件是什么","[1] 文档：研发制度.md","两名工程师");
    }
    @Test void refusesWithoutSendingAnythingToModel() {
        StringBuilder answer=new StringBuilder();service(3).answer("未知问题",List.of(),answer::append);
        assertThat(answer.toString()).contains("没有找到足够相关的资料");assertThat(calls.get()).isZero();
    }
    @Test void rejectsEmbeddingDimensionMismatch() {
        assertThatThrownBy(() -> service(4).embed(List.of("制度"))).isInstanceOf(IllegalStateException.class).hasMessageContaining("dimension");
    }
    @Test void localEmbeddingsWorkWithoutChatKeyAndDoNotInventAnAnswer() throws Exception {
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        var ai=new AiService(new AppProperties(false,"",null,new AppProperties.Ai(
            "https://ai-api.m2time.com","","gpt-6.1-sol","bge-m3",3,.55,endpoint,"ollama",false)));
        assertThat(ai.chatConfigured()).isFalse();
        assertThat(ai.embed(List.of("本地向量测试")).getFirst()).hasSize(3);
        assertThat(json.readTree(embeddingRequest.get()).has("dimensions")).isFalse();
        assertThat(embeddingAuthorization.get()).isEqualTo("Bearer ollama");
        var source=new Models.Source(1,"chunk","doc","研发制度.md",null,1,"两人审批",.8);
        StringBuilder output=new StringBuilder();
        assertThatThrownBy(() -> ai.answer("审批要求",List.of(source),output::append))
            .isInstanceOf(ApiException.class).hasMessageContaining("尚未配置大模型密钥");
        assertThat(output).isEmpty();
        assertThat(chatRequest.get()).isNull();
    }
    @Test void appliesReplacedAndClearedCredentialsWithoutRestart() {
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort();
        var current=new AtomicReference<>(new ChatSettings.Config(endpoint,"first-model","first-key"));
        var settings=org.mockito.Mockito.mock(ChatSettings.class);
        org.mockito.Mockito.when(settings.current()).thenAnswer(invocation -> current.get());
        var props=new AppProperties(false,"",null,new AppProperties.Ai(endpoint,"","unused-default","test-embedding",3,.35,endpoint,"embedding-key",true));
        var ai=new AiService(props,settings);
        var source=new Models.Source(1,"chunk","doc","制度",null,1,"两人审批",.8);
        ai.answer("审批要求",List.of(source),text -> {});
        assertThat(chatAuthorization.get()).isEqualTo("Bearer first-key");
        current.set(new ChatSettings.Config(endpoint,"second-model","replacement-key"));
        ai.answer("审批要求",List.of(source),text -> {});
        assertThat(chatAuthorization.get()).isEqualTo("Bearer replacement-key");
        assertThat(chatRequest.get()).contains("second-model");
        int previousCalls=calls.get();
        current.set(new ChatSettings.Config(endpoint,"second-model",""));
        assertThatThrownBy(() -> ai.answer("审批要求",List.of(source),text -> {})).isInstanceOf(ApiException.class);
        assertThat(calls.get()).isEqualTo(previousCalls);
    }
    @Test void rewritesFollowupBeforeRetrievalAndBoundsHistoryEvidence() throws Exception {
        var ai=service(3);
        var history=ChatContext.recentCompleted(List.of(
            new Models.Message("q1","user","什么是需求价格弹性？",List.of(),null),
            new Models.Message("a1","assistant","需求价格弹性衡量需求量对价格变化的反应。[9]",List.of(),null)));
        String resolved=ai.resolveQuestion("它为什么通常为负数？",history);
        assertThat(resolved).isEqualTo("需求价格弹性为什么通常为负数？");
        var rewrite=json.readTree(chatRequest.get());
        assertThat(rewrite.get("messages").get(1).get("content").asText()).contains("需求价格弹性","它为什么").doesNotContain("[9]");
        StringBuilder answer=new StringBuilder();
        var source=new Models.Source(1,"c","d","经济学教材",17,1,"一般情况下，价格上涨，需求量下降。",.8);
        ai.answer("它为什么通常为负数？",resolved,history,List.of(source),answer::append);
        var prompt=json.readTree(chatRequest.get());
        assertThat(prompt.get("messages").get(0).get("content").asText()).contains("不作为事实证据","历史引用编号不沿用");
        assertThat(prompt.get("messages").get(1).get("content").asText()).contains("结合上下文后的检索问题","需求价格弹性为什么通常为负数","[1] 文档：经济学教材").doesNotContain("[9]");
    }
    @Test void firstQuestionSkipsRewriteAndInvalidRewriteFailsClearly() {
        var ai=service(3);
        assertThat(ai.resolveQuestion("什么是机会成本？",List.of())).isEqualTo("什么是机会成本？");
        assertThat(calls.get()).isZero();
        rewriteOutput.set("不是有效 JSON");
        var history=List.of(new Models.Message("q","user","机会成本",List.of(),null));
        assertThatThrownBy(() -> ai.resolveQuestion("它是什么",history)).isInstanceOf(ApiException.class).hasMessageContaining("上下文理解未完成");
    }
}
