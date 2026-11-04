-- Safe metadata only: never store raw model content, prompts, reasoning, or API credentials.
ALTER TABLE model_calls ADD COLUMN problem_id uuid;
ALTER TABLE model_calls ADD COLUMN operation text;
ALTER TABLE model_calls ADD COLUMN finish_reason text;
ALTER TABLE model_calls ADD COLUMN content_length integer;
ALTER TABLE model_calls ADD COLUMN content_type text;
ALTER TABLE model_calls ADD COLUMN reasoning_length integer;
