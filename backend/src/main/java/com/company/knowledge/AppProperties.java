package com.company.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app")
public record AppProperties(boolean demo, String bootstrapPassword, Storage storage, Ai ai) {
    public record Storage(String type, String path, String endpoint, String accessKey, String secretKey, String bucket) {}
    public record Ai(String baseUrl, String apiKey, String chatModel, String embeddingModel,
                     int embeddingDimensions, double minScore, String embeddingBaseUrl,
                     String embeddingApiKey, boolean embeddingDimensionsEnabled) {}
}
