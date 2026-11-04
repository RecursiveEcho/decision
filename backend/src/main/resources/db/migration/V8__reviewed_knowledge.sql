-- Reviewed knowledge is independent from automatic Topic/Wiki summaries.
ALTER TABLE records DROP CONSTRAINT records_kind_check;
ALTER TABLE records ADD CONSTRAINT records_kind_check CHECK (kind IN
 ('problem','source','message','job','memory','topic','discussion','decision','review','device','delivery',
  'knowledge_entry','knowledge_proposal'));
CREATE UNIQUE INDEX knowledge_proposal_in_flight ON jobs(problem_id,(input->>'fingerprint'))
 WHERE state IN ('queued','running') AND input->>'operation'='knowledge_proposal';
