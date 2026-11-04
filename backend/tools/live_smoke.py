#!/usr/bin/env python3
"""Explicit real-service smoke test; creates only synthetic problem/source/decision data.
Usage: python3 tools/live_smoke.py /path/to/ignored/.env http://127.0.0.1:8080
Add --reanalyze PROBLEM_UUID to retry one existing synthetic problem without recreating data.
Never prints credentials, access tokens, model output, or source text.
"""
import json,sys,time,uuid,urllib.request,urllib.error
from pathlib import Path
config={}
for line in Path(sys.argv[1]).read_text().splitlines():
    if line and not line.startswith('#') and '=' in line:
        k,v=line.split('=',1);config[k]=v.strip().strip('"').strip("'")
base=sys.argv[2].rstrip('/')+'/api/v1'
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
token=None

def call(method,path,body=None):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    if method=='POST':headers['Idempotency-Key']=str(uuid.uuid4())
    request=urllib.request.Request(base+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:
        with opener.open(request,timeout=15) as response:return json.load(response)
    except urllib.error.HTTPError as error:
        data=json.load(error);raise RuntimeError('HTTP '+str(error.code)+': '+data.get('error',{}).get('code','UNKNOWN')) from None

def job(j):
    for _ in range(180):
        result=call('GET','/jobs/'+j['id'])
        if result['status'] in ['failed','succeeded']:
            if result['status']=='failed':raise RuntimeError('Job '+result['kind']+' failed: '+result['error']['code'])
            assert result['mode']=='real','Refuse to call mock completion a real-service verification'
            return result['result']
        time.sleep(1)
    raise RuntimeError('Job timed out')

def wait_summary():
    for _ in range(180):
        topics=call('GET','/topics?problemId='+pid)['items']
        if any('摘要生成未完成' in t['markdown'] for t in topics):raise RuntimeError('Real topic synthesis failed; inspect analysis Job')
        if any(t['syncStatus']=='failed' for t in topics):raise RuntimeError('Wiki sync failed')
        if topics and all(t['syncStatus']=='synced' and '## 观点' in t['markdown'] and '## 适用条件' in t['markdown'] and '## 不确定性' in t['markdown'] for t in topics):return topics
        time.sleep(1)
    raise RuntimeError('Real summary and Wiki sync not completed')

def wiki_readback(topic):
    payload={'query':'query($id:Int!){pages{single(id:$id){content path}}}','variables':{'id':int(topic['wikiPageId'])}}
    request=urllib.request.Request(config['WIKI_BASE_URL'].rstrip('/')+'/graphql',data=json.dumps(payload).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+config['WIKI_ACCESS_TOKEN']})
    with opener.open(request,timeout=15) as response:data=json.load(response)
    assert not data.get('errors'),'Wiki independent readback rejected'
    assert data['data']['pages']['single']['content']==topic['markdown'],'Wiki content mismatch'

token=call('POST','/auth/login',{'accessCode':config['APP_OWNER_ACCESS_CODE']})['accessToken']
if len(sys.argv)>3:
    if len(sys.argv)!=5 or sys.argv[3]!='--reanalyze':raise SystemExit('Optional arguments: --reanalyze PROBLEM_UUID')
    pid=str(uuid.UUID(sys.argv[4]))
    before_sources=call('GET','/sources?problemId='+pid)['items']
    before_messages=call('GET','/problems/'+pid+'/messages')['items']
    before_topics=call('GET','/topics?problemId='+pid)['items']
    queued=call('POST','/problems/'+pid+'/analyze',{})
    print(json.dumps({'event':'explicit_reanalysis_queued','problemId':pid,'jobId':queued['id']}),flush=True)
    job(queued)
    topics=wait_summary()
    assert {t['id'] for t in topics}=={t['id'] for t in before_topics},'Reanalysis duplicated topics'
    for topic in topics:
        assert topic['evidence'],'Reanalysis has no verifiable source evidence'
        for evidence in topic['evidence']:
            source=call('GET','/sources/'+evidence['sourceId']);assert evidence['quote'] in source['text']
        wiki_readback(topic)
    assert {s['id'] for s in call('GET','/sources?problemId='+pid)['items']}=={s['id'] for s in before_sources},'Reanalysis duplicated sources'
    assert {m['id'] for m in call('GET','/problems/'+pid+'/messages')['items']}=={m['id'] for m in before_messages},'Reanalysis duplicated messages'
    print(json.dumps({'event':'real_reanalysis_and_wiki_readback_verified','problemId':pid,'sourceCount':len(before_sources),'topicCount':len(topics)}),flush=True)
    raise SystemExit(0)
p=call('POST','/problems',{'title':'合成验收：虚构读书安排','goal':'每周用两小时读一本虚构书并保留讨论时间','constraints':['仅合成资料，无真实个人数据'],'unknowns':['虚构计划是否留有余量']})
pid=p['id'];print(json.dumps({'event':'problem_created','problemId':pid}),flush=True)
src=job(call('POST','/sources',{'problemId':pid,'kind':'article','title':'合成验收文章','text':'这是一份用于系统工程验收的虚构文章，不是真实建议。虚构方案甲需要每周六小时，而问题目标只允许每周两小时。方案乙把每次阅读分成二十分钟，并预留讨论时间。适用条件是读者能接受较慢的进度；不确定性是书籍难度。作者认为应优先核实时间预算。'}))
print(json.dumps({'event':'source_ingested','topicCount':len(src['topicIds'])}),flush=True)
result=job(call('POST','/problems/'+pid+'/messages',{'content':'仅根据这份虚构资料讨论时间预算，列出未知，不代替本人决定。请给出逐字原文引用。','sourceIds':[src['sourceId']]}))
messages=call('GET','/problems/'+pid+'/messages')['items'];assistant=next(m for m in messages if m['id']==result['assistantMessageId'])
assert assistant['mode']=='real';assert assistant['evidence'],'No verified evidence in real model response'
source=call('GET','/sources/'+src['sourceId'])
for e in assistant['evidence']:assert e['sourceId']==src['sourceId'] and e['quote'] in source['text']
print(json.dumps({'event':'real_model_with_verified_evidence','evidenceCount':len(assistant['evidence'])}),flush=True)
assert not call('GET','/decisions?problemId='+pid)['items'],'Model must not confirm decisions'
topics=wait_summary();assert len(topics)==1;before=topics[0];assert before['evidence'];wiki_readback(before)
print(json.dumps({'event':'real_model_topic_and_independent_wiki_readback','revision':before['revision']}),flush=True)
second=job(call('POST','/sources',{'problemId':pid,'kind':'article','title':'合成验收补充文章','text':'此为第二份相关的虚构工程验收资料。方案乙每周两小时可以拆为四次二十分钟阅读，再留四十分钟讨论；这份方案只适用于章节短且可分割的虚构书。与第一篇文章相比，新增限制是不得占用本已预留的休息时间。作者认为必须核实书籍章节是否能拆分。'}))
assert second['topicIds']==src['topicIds'],'Related sources must update the existing topic'
topics=wait_summary();assert len(topics)==1;after=topics[0]
assert after['id']==before['id'] and after['wikiPageId']==before['wikiPageId'],'Topic/Wiki identity changed'
assert after['revision']>before['revision'] and after['markdown']!=before['markdown'],'Topic summary was not revised'
assert any(e['sourceId']==second['sourceId'] for e in after['evidence']),'Updated summary has no evidence from new source'
for e in after['evidence']:
    actual_source=call('GET','/sources/'+e['sourceId']);assert e['quote'] in actual_source['text']
wiki_readback(after)
print(json.dumps({'event':'related_article_merged_and_real_wiki_updated','revision':after['revision'],'sameWikiPage':True,'evidenceCount':len(after['evidence'])}),flush=True)
decision=call('POST','/decisions',{'problemId':pid,'choice':'仅为合成验收，暂选虚构方案乙','rationale':'保持虚构两小时时间约束','expectedOutcome':'验证记录和复盘链路','evidence':assistant['evidence']})
call('POST','/decisions/'+decision['id']+'/reviews',{'outcome':'虚构验收结果','reflection':'原理由应保持不变'})
actual=call('GET','/decisions?problemId='+pid)['items'][0]
assert actual['rationale']==decision['rationale'] and len(actual['reviews'])==1
print(json.dumps({'event':'owner_decision_and_append_review_verified','problemId':pid}),flush=True)
