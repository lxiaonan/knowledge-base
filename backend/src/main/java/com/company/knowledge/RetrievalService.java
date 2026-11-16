package com.company.knowledge;

import static com.company.knowledge.Models.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private final Repository repo;
    private final AiService ai;
    private final AppProperties config;
    public RetrievalService(Repository repo, AiService ai, AppProperties config) { this.repo=repo; this.ai=ai; this.config=config; }
    public List<Source> search(String kbId, String question) {
        // Caller must have authorized this knowledge base. The SQL scope is applied before ranking.
        List<Ranked> ranked;
        if (config.demo()) {
            Set<String> terms=terms(question);
            ranked = repo.chunks(kbId).stream().map(c -> new Ranked(c,lexical(terms,c.content())))
                .filter(r -> r.score()>=0.16).sorted(Comparator.comparingDouble(Ranked::score).reversed()).limit(4).toList();
        } else {
            String vector = vector(ai.embed(List.of(question)).getFirst());
            ranked = repo.db().sql("""
                SELECT c.*,d.name document_name,1-(c.embedding <=> CAST(? AS vector)) similarity
                FROM document_chunk c JOIN document d ON d.id=c.document_id
                WHERE d.knowledge_base_id=? AND d.status='READY' AND c.embedding IS NOT NULL
                ORDER BY c.embedding <=> CAST(? AS vector) LIMIT 6
                """).params(vector,kbId,vector).query((r,i) -> new Ranked(Repository.chunk(r,i),r.getDouble("similarity"))).list()
                .stream().filter(r -> r.score()>=config.ai().minScore()).toList();
        }
        List<Source> sources = new ArrayList<>();
        for (Ranked r:ranked) {
            var c=r.chunk();
            sources.add(new Source(sources.size()+1,c.id(),c.documentId(),c.documentName(),c.pageNumber(),c.ordinal(),c.content(),Math.round(r.score()*1000)/1000.0));
        }
        return sources;
    }
    record Ranked(Chunk chunk,double score) {}
    static String vector(float[] vector) {
        StringJoiner out=new StringJoiner(",","[","]");
        for (float f:vector) { if (!Float.isFinite(f)) throw new IllegalArgumentException("Invalid embedding"); out.add(Float.toString(f)); }
        return out.toString();
    }
    static Set<String> terms(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("请问|一下|如何|怎么|什么|多少|可以|我们|公司|是否", " ");
        Set<String> terms=new HashSet<>();
        var words=Pattern.compile("[a-z0-9_-]{2,}|[\\p{IsHan}]+").matcher(normalized);
        while (words.find()) {
            String word=words.group();
            if (word.charAt(0)<128) terms.add(word);
            else for (int i=0;i<word.length()-1;i++) terms.add(word.substring(i,i+2));
        }
        return terms;
    }
    private double lexical(Set<String> terms,String text) {
        if (terms.isEmpty()) return 0;
        String normalized=text.toLowerCase(Locale.ROOT);
        return (double)terms.stream().filter(normalized::contains).count()/terms.size();
    }
}
