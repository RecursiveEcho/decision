import json, tempfile, unittest, uuid
from datetime import datetime,timedelta,timezone
from pathlib import Path
from unittest.mock import patch
from decision_observer.core import ObserverError,Runner,Store,observation,valid_url,notify

PROBLEM=str(uuid.uuid4())
CONFIG={'deviceId':str(uuid.uuid4()),'deviceToken':'synthetic-token','backendUrl':'http://127.0.0.1:8080/api/v1','pensieveUrl':'http://127.0.0.1:8839','libraryId':1,'folderId':1,'pensieveTimezone':'Asia/Shanghai'}
def entity(at=None,ident=1):
    return {'id':ident,'file_created_at':(at or datetime.now(timezone.utc)).isoformat(),'filepath':'/private/example/screenshot.webp','metadata_entries':[{'key':'ocr_result','value':json.dumps([{'rec_txt':f'虚构测试：今天读书 {ident}','score':.99}])},{'key':'active_app','value':'SyntheticApp'}]}
class Api:
    def __init__(self): self.calls=[]; self.desired='running'; self.fail=False; self.delivery=None; self.ack_fail=False
    def request(self,method,path,body=None,key=None):
        self.calls.append((method,path,body,key))
        if path.endswith('/heartbeat'): return {'device':{'desiredState':self.desired}}
        if path=='/observations/batch':
            if self.fail: raise ObserverError('Service unavailable')
            return {'accepted':[i['externalId'] for i in body['items']]}
        if '/deliveries?' in path: return {'items':[self.delivery] if self.delivery else []}
        if path.endswith('/ack'):
            if self.ack_fail: raise ObserverError('Service unavailable')
            return body
        raise AssertionError(path)
class Source:
    def __init__(self): self.calls=0; self.items=[]; self.after=lambda:None
    def read(self,since): self.calls+=1; self.after(); return self.items
class Tests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.store=Store(Path(self.tmp.name)); self.addCleanup(self.store.db.close)
        self.api=Api(); self.source=Source(); self.runner=Runner(CONFIG,self.store,PROBLEM,self.api,self.source)
    def active(self):
        self.runner.consent=True; self.runner.since-=timedelta(seconds=1); self.source.items=[entity()]
    def uploads(self): return [c for c in self.api.calls if c[1]=='/observations/batch']
    def test_default_no_consent(self):
        with self.assertRaises(ObserverError): self.runner.tick()
        self.assertEqual(self.source.calls,0)
    def test_server_pause(self):
        self.active(); self.api.desired='paused'
        with self.assertRaises(ObserverError): self.runner.tick()
        self.assertEqual(self.source.calls,0)
    def test_chinese_locator_dedup(self):
        self.active(); self.runner.tick(); self.runner.tick(); self.assertEqual(len(self.uploads()),1)
        item=self.uploads()[0][2]['items'][0]
        self.assertIn('虚构',item['text']); self.assertNotIn('/private',json.dumps(item)); self.assertNotIn('screenshot',json.dumps(item)); self.assertEqual(len(item['contentHash']),64)
    def test_pause_during_read(self):
        self.active(); self.source.after=lambda:self.runner.stop_file.touch()
        with self.assertRaises(ObserverError): self.runner.tick()
        self.assertFalse(self.uploads())
    def test_remote_pause_before_upload(self):
        self.active(); self.source.after=lambda:setattr(self.api,'desired','paused')
        with self.assertRaises(ObserverError): self.runner.tick()
        self.assertFalse(self.uploads())
    def test_run_failure_revokes_consent(self):
        self.api.desired='paused'
        with self.assertRaises(ObserverError): self.runner.run()
        self.assertFalse(self.runner.consent); self.assertEqual(self.source.calls,0)
    def test_persisted_queue_stable_idempotency(self):
        self.active(); self.api.fail=True
        with self.assertRaises(ObserverError): self.runner.tick()
        key=self.store.pending(PROBLEM)[2]; reopened=Store(Path(self.tmp.name)); self.addCleanup(reopened.db.close)
        self.assertEqual(reopened.pending(PROBLEM)[2],key)
        self.api.fail=False; self.runner.tick(); self.assertIsNone(self.store.pending(PROBLEM)); self.assertEqual([c[3] for c in self.uploads()],[key,key])
    def test_resume_problem_guard(self):
        self.active(); self.api.fail=True
        with self.assertRaises(ObserverError): self.runner.tick()
        other=Runner(CONFIG,self.store,str(uuid.uuid4()),self.api,self.source)
        with self.assertRaises(ObserverError): other.run(resume=True)
    def test_default_run_drops_queue(self):
        self.active(); self.api.fail=True
        with self.assertRaises(ObserverError): self.runner.tick()
        self.api.desired='paused'
        with self.assertRaises(ObserverError): self.runner.run()
        self.assertIsNone(self.store.pending(PROBLEM))
    def test_no_backfill_or_future(self):
        now=datetime.now(timezone.utc)
        for delta in (-120,120): self.assertIsNone(observation(entity(now+timedelta(seconds=delta)),CONFIG,PROBLEM,now,now))
    def test_naive_timezone(self):
        now=datetime(2026,11,4,9,tzinfo=timezone.utc); e=entity(); e['file_created_at']='2026-11-04T17:00:00'
        self.assertEqual(observation(e,CONFIG,PROBLEM,now,now)['capturedAt'],now.isoformat())
    def test_delayed_ocr(self):
        self.active(); original=self.source.items[0]['metadata_entries']; self.source.items[0]['metadata_entries']=[]
        self.runner.tick(); self.assertFalse(self.uploads()); self.source.items[0]['metadata_entries']=original
        self.runner.tick(); self.assertEqual(len(self.uploads()),1)
    def test_empty_completed_ocr_not_pending(self):
        self.active(); self.source.items[0]['metadata_entries']=[{'key':'ocr_result','value':'[]'}]
        self.runner.tick(); self.assertFalse(self.runner.pending_ocr); self.assertFalse(self.uploads())
    def test_queue_bound(self):
        now=datetime.now(timezone.utc)
        for i in range(50): self.store.enqueue(observation(entity(now,i),CONFIG,PROBLEM,now,now))
        with self.assertRaises(ObserverError): self.store.enqueue(observation(entity(now,51),CONFIG,PROBLEM,now,now))
    def test_url_boundaries(self):
        for u in ('http://example.com','http://u:p@localhost','file:///tmp/a'):
            with self.assertRaises(ObserverError): valid_url(u)
        with self.assertRaises(ObserverError): valid_url('https://example.com',True)
    def test_notification_ack_retry(self):
        calls=[]; self.runner.notifier=lambda *a:calls.append(a)
        self.api.delivery={'delivery':{'id':str(uuid.uuid4())},'discussion':{'title':'合成议题','reason':'测试'}}
        self.api.ack_fail=True
        with self.assertRaises(ObserverError): self.runner.deliver()
        self.api.ack_fail=False; self.runner.deliver(); self.assertEqual(len(calls),1)
    def test_notification_failure(self):
        def fail(*a): raise ObserverError('Rejected')
        self.runner.notifier=fail; self.api.delivery={'delivery':{'id':str(uuid.uuid4())},'discussion':{'title':'合成','reason':'测试'}}
        self.runner.deliver(); self.assertEqual(self.api.calls[-1][2]['status'],'failed')
    def test_same_content_different_entity_suppressed(self):
        self.active(); self.runner.tick()
        same=entity(); same['id']=2; self.source.items=[same]; self.runner.tick()
        self.assertEqual(len(self.uploads()),1)
    def test_full_queue_drains_before_read(self):
        self.active(); now=datetime.now(timezone.utc)
        for i in range(50): self.store.enqueue(observation(entity(now,i),CONFIG,PROBLEM,now,now))
        self.source.items=[entity(ident=60)]; self.runner.tick()
        self.assertEqual(len(self.uploads()[0][2]['items']),50)
        self.assertIsNone(self.store.pending(PROBLEM))
    def test_continuous_window_not_false_overflow(self):
        self.active(); now=datetime.now(timezone.utc)
        first=observation(entity(now,0),CONFIG,PROBLEM,self.runner.since,now)
        self.store.enqueue(first); self.store.accepted(first['externalId'])
        self.source.items=[entity(now,i) for i in reversed(range(400))]
        self.runner.tick(); self.assertTrue(self.uploads())
    def test_unread_window_overflow_stops(self):
        self.active(); self.source.items=[entity(ident=i) for i in reversed(range(400))]
        with self.assertRaisesRegex(ObserverError,'Unread observations'): self.runner.tick()
        self.assertFalse(self.uploads())
    def test_real_run_upload_disconnect_revokes_consent(self):
        self.api.fail=True
        def read(since): return [entity()]
        self.source.read=read
        with self.assertRaises(ObserverError): self.runner.run()
        self.assertFalse(self.runner.consent); self.assertIsNotNone(self.store.pending(PROBLEM))
    def test_long_chinese_batch_split_by_encoded_bytes(self):
        self.active(); now=datetime.now(timezone.utc)
        for i in range(20):
            e=entity(now,i); e['metadata_entries'][0]['value']=json.dumps([{'rec_txt':('长'*19990)+str(i)}])
            self.store.enqueue(observation(e,CONFIG,PROBLEM,now,now))
        while self.store.pending(PROBLEM): self.runner.flush()
        self.assertGreater(len(self.uploads()),1)
        self.assertEqual(sum(len(c[2]['items']) for c in self.uploads()),20)
        self.assertTrue(all(len(json.dumps(c[2]).encode())<=900000 for c in self.uploads()))
    def test_known_rejection_changes_key(self):
        self.active(); now=datetime.now(timezone.utc)
        self.store.enqueue(observation(entity(now),CONFIG,PROBLEM,now,now)); old=self.store.pending(PROBLEM)[2]
        real=self.api.request
        def reject(method,path,body=None,key=None):
            if path=='/observations/batch': return {'accepted':[], 'rejected':[{'externalId':body['items'][0]['externalId'],'code':'DEVICE_PAUSED'}]}
            return real(method,path,body,key)
        self.api.request=reject
        with self.assertRaises(ObserverError): self.runner.flush()
        self.assertNotEqual(self.store.pending(PROBLEM)[2],old)
        self.api.request=real; self.runner.flush(); self.assertIsNone(self.store.pending(PROBLEM))
    def test_failed_notification_does_not_starve_next(self):
        first,second=str(uuid.uuid4()),str(uuid.uuid4()); calls=[]
        def notification(title,reason):
            calls.append(title)
            if title=='fail': raise ObserverError('failure')
        self.runner.notifier=notification; real=self.api.request
        def pages(method,path,body=None,key=None):
            if '/deliveries?' in path:
                return {'items':[{'delivery':{'id':first},'discussion':{'title':'fail','reason':'synthetic'}}, {'delivery':{'id':second},'discussion':{'title':'ok','reason':'synthetic'}}], 'nextCursor':'synthetic-page-2'}
            return real(method,path,body,key)
        self.api.request=pages; self.runner.deliver(); self.runner.deliver()
        self.assertEqual(calls,['fail','ok']); self.assertEqual(self.runner.delivery_cursor,'synthetic-page-2')
    def test_locator_uses_supported_opaque_ids(self):
        now=datetime.now(timezone.utc); item=observation(entity(now,42),CONFIG,PROBLEM,now,now)
        self.assertEqual(item['sourceLocator'],{'resourceId':'pensieve:library:1','frameId':'pensieve:entity:42'})
    def test_emoji_respects_java_utf16_limits(self):
        now=datetime.now(timezone.utc); e=entity(now)
        e['metadata_entries']=[{'key':'ocr_result','value':json.dumps([{'rec_txt':'中'+'😀'*12000}])},{'key':'active_app','value':'😀'*150}]
        item=observation(e,CONFIG,PROBLEM,now,now)
        self.assertLessEqual(len(item['text'].encode('utf-16-le'))//2,20000)
        self.assertLessEqual(len(item['appName'].encode('utf-16-le'))//2,200)
    def test_snooze_requeues_same_delivery_new_generation(self):
        calls=[]; self.runner.notifier=lambda *a:calls.append(a)
        delivery={'id':str(uuid.uuid4()),'status':'pending','updatedAt':'2026-11-04T09:00:00Z'}
        self.api.delivery={'delivery':delivery,'discussion':{'title':'合成稍后提醒','reason':'测试'}}
        self.runner.deliver(); self.runner.deliver()
        self.assertEqual(len(calls),1)
        old_key=self.api.calls[-1][3]
        delivery['updatedAt']='2026-11-04T09:10:00Z'
        self.runner.deliver()
        self.assertEqual(len(calls),2)
        self.assertNotEqual(self.api.calls[-1][3],old_key)
    def test_applescript_strings_are_data(self):
        with patch('decision_observer.core.sys.platform','darwin'),patch('decision_observer.core.subprocess.run') as run:
            title='" & do shell script "bad'; notify(title,'合成通知'); args=run.call_args.args[0]
            self.assertNotIn(title,args[2]); self.assertEqual(args[-2],title)
if __name__=='__main__': unittest.main()
