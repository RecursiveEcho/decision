-- Additive schema. Apply only to isolated databases until an upgrade is separately approved.
ALTER TABLE records ADD COLUMN pos_revision bigint NOT NULL DEFAULT 1 CHECK (pos_revision > 0);
CREATE FUNCTION bump_pos_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.pos_revision := OLD.pos_revision + 1;
  RETURN NEW;
END;
$$;
CREATE TRIGGER records_pos_revision BEFORE UPDATE ON records
  FOR EACH ROW EXECUTE FUNCTION bump_pos_revision();

-- Origin dialogue/proposals are not Decision problems, messages, jobs, or business attachments.
CREATE TABLE origin_records (
  account_id uuid NOT NULL REFERENCES accounts(id),
  id uuid NOT NULL,
  seq bigint GENERATED ALWAYS AS IDENTITY,
  kind text NOT NULL CHECK (kind IN ('session','message','generation','proposal','authorization')),
  session_id uuid,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id,id),
  FOREIGN KEY(account_id,session_id) REFERENCES origin_records(account_id,id)
);
CREATE INDEX origin_records_session ON origin_records(account_id,session_id,kind,seq);
CREATE UNIQUE INDEX origin_generation_active ON origin_records(account_id,session_id)
  WHERE kind='generation' AND data->>'state' IN ('queued','running');
CREATE TABLE origin_requests (
  account_id uuid NOT NULL REFERENCES accounts(id),
  scope text NOT NULL,
  request_key uuid NOT NULL,
  request_hash text NOT NULL,
  response text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id,scope,request_key)
);
CREATE TABLE origin_attachments (
  account_id uuid NOT NULL,
  id uuid NOT NULL,
  session_id uuid NOT NULL,
  message_id uuid NOT NULL,
  position integer NOT NULL,
  bytes bytea NOT NULL,
  metadata jsonb NOT NULL,
  parsed jsonb,
  PRIMARY KEY(account_id,id),
  FOREIGN KEY(account_id,session_id) REFERENCES origin_records(account_id,id),
  FOREIGN KEY(account_id,message_id) REFERENCES origin_records(account_id,id)
);
CREATE TABLE origin_generation_events (
  account_id uuid NOT NULL,
  generation_id uuid NOT NULL,
  seq bigint GENERATED ALWAYS AS IDENTITY,
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id,generation_id,seq),
  FOREIGN KEY(account_id,generation_id) REFERENCES origin_records(account_id,id)
);
