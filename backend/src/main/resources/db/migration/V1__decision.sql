-- Single-owner document aggregate store; kind has an explicit domain, all writes transactional.
CREATE TABLE records (
 id uuid PRIMARY KEY, kind text NOT NULL CHECK (kind IN ('problem','source','message','job','memory','topic','discussion','decision','review','device','delivery')),
 problem_id uuid, data jsonb NOT NULL, deleted boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX records_kind_problem ON records(kind, problem_id, updated_at DESC) WHERE NOT deleted;
CREATE TABLE record_history (seq bigserial PRIMARY KEY, record_id uuid NOT NULL, kind text NOT NULL, data jsonb NOT NULL, recorded_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE idempotency_keys (scope text NOT NULL, key uuid NOT NULL, request_hash text NOT NULL, status integer NOT NULL, response text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(scope,key));
CREATE TABLE jobs (id uuid PRIMARY KEY REFERENCES records(id), problem_id uuid, input jsonb NOT NULL, state text NOT NULL DEFAULT 'queued', created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX jobs_pending ON jobs(created_at) WHERE state = 'queued';
CREATE UNIQUE INDEX analysis_in_flight ON jobs(problem_id) WHERE state IN ('queued','running') AND input->>'operation' = 'analysis';
CREATE TABLE analysis_pending (problem_id uuid PRIMARY KEY, dirty boolean NOT NULL DEFAULT true);
CREATE TABLE observation_keys (device_id uuid NOT NULL, external_id text NOT NULL, content_hash text NOT NULL, source_id uuid NOT NULL, PRIMARY KEY(device_id,external_id));
CREATE TABLE discussion_keys (problem_id uuid NOT NULL, fingerprint text NOT NULL, discussion_id uuid NOT NULL, PRIMARY KEY(problem_id,fingerprint));
CREATE TABLE model_calls (id uuid PRIMARY KEY, model text NOT NULL, status text NOT NULL, input_tokens bigint NOT NULL DEFAULT 0, output_tokens bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE login_attempts (address_hash text PRIMARY KEY, failures integer NOT NULL, window_start timestamptz NOT NULL);
CREATE TABLE context_version (id integer PRIMARY KEY CHECK (id=1), revision bigint NOT NULL);
INSERT INTO context_version VALUES (1,0);
