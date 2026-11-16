package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Only completed user/assistant pairs are eligible for conversational context. */
public final class ChatContext {
    private ChatContext() {}
    public static List<Message> recentCompleted(List<Message> chronological) {
        List<List<Message>> pairs=new ArrayList<>();
        Message question=null;
        for(var message:chronological) {
            if ("user".equals(message.role())) question=message;
            else if ("assistant".equals(message.role()) && question!=null) {
                pairs.add(List.of(trim(question),trim(message)));question=null;
            }
        }
        List<Message> result=new ArrayList<>();
        for(var pair:pairs.subList(Math.max(0,pairs.size()-3),pairs.size())) result.addAll(pair);
        return Collections.unmodifiableList(result);
    }
    private static Message trim(Message message) {
        String content=message.content().replaceAll("\\[\\d+\\]", "");
        if(content.length()>2000) {
            int end=Character.isHighSurrogate(content.charAt(1999))?1999:2000;
            content=content.substring(0,end)+"（历史内容已截断）";
        }
        return new Message(message.id(),message.role(),content,List.of(),message.createdAt());
    }
    public static String text(List<Message> history) {
        return history.stream().map(message->("user".equals(message.role())?"用户：":"助手：")+message.content())
            .collect(java.util.stream.Collectors.joining("\n\n"));
    }
}
