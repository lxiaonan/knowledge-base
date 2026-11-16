package com.company.knowledge;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatContextTest {
    private Models.Message message(String role,String content) {return new Models.Message(Repository.id(),role,content,List.of(),Instant.now());}
    @Test void onlyCompletedPairsAndLastThreeTurnsAreIncluded() {
        var history=new ArrayList<Models.Message>();
        history.add(message("assistant","没有问题的答案"));
        history.add(message("user","失败的问题"));
        for(int i=1;i<=5;i++) {history.add(message("user","问题"+i));history.add(message("assistant","答案"+i+"[8]"));}
        history.add(message("user","尚未回答的追问"));
        var context=ChatContext.recentCompleted(history);
        assertThat(context).hasSize(6);
        assertThat(context.getFirst().content()).isEqualTo("问题3");
        assertThat(context.getLast().content()).isEqualTo("答案5");
        assertThat(ChatContext.text(context)).doesNotContain("失败的问题","尚未回答","[8]","问题1");
    }
    @Test void boundsLongContentAndDoesNotCopyHistoricalSources() {
        var history=List.of(message("user","😀".repeat(2000)),new Models.Message("old-answer","assistant","答案".repeat(3000),
            List.of(new Models.Source(1,"old-chunk","old-doc","旧资料",null,1,"不应发送的原文",.8)),Instant.now()));
        var context=ChatContext.recentCompleted(history);
        assertThat(context).allSatisfy(m->{assertThat(m.content().length()).isLessThan(2050);assertThat(m.sources()).isEmpty();});
        assertThat(ChatContext.text(context)).doesNotContain("不应发送的原文");
        assertThat(context.getFirst().content()).contains("历史内容已截断");
    }
}
