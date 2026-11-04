-- Internal context epoch, never part of the public Message JSON.
-- Existing NULL history remains readable in the UI but is not sent back to the model:
-- it may contain facts corrected or withdrawn before this migration.
ALTER TABLE records ADD COLUMN context_revision bigint;
CREATE INDEX records_message_context ON records(problem_id, context_revision, created_at, id)
 WHERE kind='message' AND NOT deleted;
