-- Internal flags/relationships only; public Problem and Job JSON stay compatible.
ALTER TABLE records ADD COLUMN auto_title boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX jobs_chat_retry_parent ON jobs ((input->>'retryOfJobId'))
 WHERE input->>'operation'='chat' AND input ? 'retryOfJobId';
