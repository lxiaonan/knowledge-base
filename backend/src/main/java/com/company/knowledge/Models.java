package com.company.knowledge;

import java.time.Instant;
import java.util.List;

public final class Models {
    private Models() {}
    public record User(String id, String username, String displayName, String role) {}
    public record KnowledgeBase(String id, String name, String description, String ownerId,
                                long documentCount, long readyCount, boolean canManage) {}
    public record Document(String id, String knowledgeBaseId, String name, String fileKey, String contentType,
                           long sizeBytes, String status, String errorMessage, int chunkCount, Instant createdAt) {}
    public record Chunk(String id, String documentId, String documentName, int ordinal, Integer pageNumber, String content) {}
    public record Source(int number, String chunkId, String documentId, String documentName,
                         Integer pageNumber, int ordinal, String excerpt, double score) {}
    public record Conversation(String id, String knowledgeBaseId, String title, Instant createdAt) {}
    public record Message(String id, String role, String content, List<Source> sources, Instant createdAt) {}
    public record PageText(Integer page, String content) {}
    public record Piece(Integer page, String content) {}
}
