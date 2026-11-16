CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE document_chunk ADD COLUMN embedding vector(${embedding_dimensions});
CREATE INDEX idx_chunk_embedding ON document_chunk USING hnsw (embedding vector_cosine_ops);
