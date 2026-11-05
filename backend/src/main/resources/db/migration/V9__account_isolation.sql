-- The sole pre-registration principal keeps every existing row, including deleted ones.
CREATE TABLE accounts (
  id uuid PRIMARY KEY,
  username text NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9_][a-z0-9_.-]{2,31}$'),
  role text NOT NULL CHECK (role IN ('admin','member')),
  password_hash text,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((role='admin' AND id='00000000-0000-0000-0000-000000000001' AND username='owner')
    OR (role='member' AND password_hash IS NOT NULL))
);
INSERT INTO accounts(id,username,role) VALUES ('00000000-0000-0000-0000-000000000001','owner','admin');

-- Defaults only backfill the V8 rows. New writes must explicitly name their account.
DO $$
DECLARE table_name text;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['records','jobs','model_calls','idempotency_keys',
      'context_version','chat_memory_state','chat_memory_tombstones'] LOOP
    EXECUTE format('ALTER TABLE %I ADD COLUMN account_id uuid NOT NULL DEFAULT %L REFERENCES accounts(id)',
      table_name, '00000000-0000-0000-0000-000000000001');
    EXECUTE format('ALTER TABLE %I ALTER COLUMN account_id DROP DEFAULT', table_name);
  END LOOP;
END $$;

ALTER TABLE context_version DROP CONSTRAINT context_version_pkey, ADD PRIMARY KEY(account_id,id);
ALTER TABLE chat_memory_state DROP CONSTRAINT chat_memory_state_pkey, ADD PRIMARY KEY(account_id,id);
ALTER TABLE chat_memory_tombstones DROP CONSTRAINT chat_memory_tombstones_pkey,
  ADD PRIMARY KEY(account_id,scope_key,fact_key);
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey, ADD PRIMARY KEY(account_id,scope,key);

-- A record's UUID and account are immutable. Child records cannot use another account's parent.
ALTER TABLE records ADD UNIQUE(account_id,id);
ALTER TABLE records ADD FOREIGN KEY(account_id,problem_id) REFERENCES records(account_id,id);
ALTER TABLE jobs ADD FOREIGN KEY(account_id,id) REFERENCES records(account_id,id),
  ADD FOREIGN KEY(account_id,problem_id) REFERENCES records(account_id,id);
CREATE INDEX records_account_kind_problem ON records(account_id,kind,problem_id) WHERE NOT deleted;
CREATE INDEX jobs_account_state ON jobs(account_id,state);
CREATE INDEX model_calls_account_time ON model_calls(account_id,created_at DESC);

-- V8 login receipts contained SHA-256(canonical access-code body). Keep the key and
-- encrypted receipt, but remove that offline verifier. Replay requires validating the
-- actual configured access code before this legacy marker may be used.
UPDATE idempotency_keys SET request_hash='legacy-access-code-verified'
 WHERE scope='anonymous:/api/v1/auth/login';
