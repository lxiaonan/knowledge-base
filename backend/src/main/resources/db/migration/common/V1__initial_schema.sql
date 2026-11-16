CREATE TABLE app_setting (
    setting_key VARCHAR(80) PRIMARY KEY,
    setting_value TEXT NOT NULL
);
CREATE TABLE app_user (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_base (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500) NOT NULL,
    owner_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE knowledge_member (
    knowledge_base_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    PRIMARY KEY (knowledge_base_id, user_id)
);
CREATE TABLE document (
    id VARCHAR(36) PRIMARY KEY,
    knowledge_base_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    file_key VARCHAR(80) NOT NULL UNIQUE,
    content_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    chunk_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_document_kb ON document(knowledge_base_id);
CREATE TABLE document_chunk (
    id VARCHAR(36) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL,
    page_number INTEGER,
    content TEXT NOT NULL,
    UNIQUE(document_id, ordinal)
);
CREATE TABLE conversation (
    id VARCHAR(36) PRIMARY KEY,
    knowledge_base_id VARCHAR(36) NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id),
    title VARCHAR(100) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_conversation_user ON conversation(user_id, knowledge_base_id);
CREATE TABLE chat_message (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role VARCHAR(16) NOT NULL,
    content TEXT NOT NULL,
    sources_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_message_conversation ON chat_message(conversation_id, created_at);
