CREATE TABLE attachments (
 id uuid PRIMARY KEY,
 message_id uuid NOT NULL REFERENCES records(id) ON DELETE CASCADE,
 problem_id uuid NOT NULL REFERENCES records(id) ON DELETE CASCADE,
 position integer NOT NULL,
 bytes bytea NOT NULL,
 metadata jsonb NOT NULL,
 parsed jsonb,
 UNIQUE(message_id,position)
);
CREATE INDEX attachments_message ON attachments(message_id,position);
