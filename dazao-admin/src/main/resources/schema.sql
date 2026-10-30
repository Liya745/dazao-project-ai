CREATE TABLE IF NOT EXISTS studio_users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS characters (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES studio_users(id),
    version INT NOT NULL,
    definition CLOB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS conversations (
    id VARCHAR(36) PRIMARY KEY,
    owner_id BIGINT NOT NULL REFERENCES studio_users(id),
    character_id VARCHAR(36) NOT NULL REFERENCES characters(id),
    character_version INT NOT NULL,
    definition CLOB NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS chat_turns (
    id VARCHAR(36) PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL REFERENCES conversations(id),
    user_content CLOB NOT NULL,
    assistant_content CLOB NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_characters_owner ON characters(owner_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_conversations_owner ON conversations(owner_id, created_at);
CREATE INDEX IF NOT EXISTS idx_turns_conversation ON chat_turns(conversation_id, created_at);
