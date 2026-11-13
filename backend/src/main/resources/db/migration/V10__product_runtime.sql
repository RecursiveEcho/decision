CREATE TABLE model_settings (
  account_id uuid PRIMARY KEY REFERENCES accounts(id),
  data jsonb NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE model_test_requests (
  account_id uuid NOT NULL REFERENCES accounts(id),
  request_key uuid NOT NULL,
  request_hash text NOT NULL,
  result jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(account_id, request_key)
);
CREATE TABLE job_events (
  job_id uuid NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
  account_id uuid NOT NULL REFERENCES accounts(id),
  seq bigint NOT NULL,
  type text NOT NULL CHECK(type IN ('stage','delta','terminal')),
  data jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY(job_id,seq)
);
CREATE INDEX job_events_account ON job_events(account_id,job_id,seq);
