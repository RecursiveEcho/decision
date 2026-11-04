-- Stable insertion order: retry processing order must not make an old message look new.
ALTER TABLE records ADD COLUMN chat_source_seq bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE records ADD COLUMN memory_epoch bigint NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX records_chat_source_seq ON records(chat_source_seq);

CREATE TABLE chat_memory_state (
 id integer PRIMARY KEY CHECK(id=1),
 epoch bigint NOT NULL
);
INSERT INTO chat_memory_state VALUES (1,0);

CREATE TABLE chat_memory_processed (
 message_id uuid PRIMARY KEY REFERENCES records(id) ON DELETE CASCADE,
 source_seq bigint NOT NULL,
 epoch bigint NOT NULL,
 processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE chat_memory_meta (
 memory_id uuid PRIMARY KEY REFERENCES records(id) ON DELETE CASCADE,
 fact_key text NOT NULL,
 scope_key text NOT NULL,
 source_seq bigint NOT NULL,
 topic_terms text NOT NULL DEFAULT '[]',
 protected boolean NOT NULL DEFAULT false
);
CREATE INDEX chat_memory_fact ON chat_memory_meta(scope_key,fact_key);

CREATE TABLE chat_memory_tombstones (
 scope_key text NOT NULL,
 fact_key text NOT NULL,
 fact_text text NOT NULL,
 blocked_through_seq bigint NOT NULL,
 PRIMARY KEY(scope_key,fact_key)
);
