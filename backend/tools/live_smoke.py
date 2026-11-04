#!/usr/bin/env python3
"""Explicit real-service smoke test; creates only synthetic problem/source/decision data.
Usage: python3 tools/live_smoke.py /path/to/ignored/.env http://127.0.0.1:8080
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

token=call('POST','/auth/login',{'accessCode':config['APP_OWNER_ACCESS_CODE']})['accessToken']
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
for _ in range(100):
    topics=call('GET','/topics?problemId='+pid)['items']
    if topics and all(t['syncStatus']=='synced' for t in topics):break
    if any(t['syncStatus']=='failed' for t in topics):raise RuntimeError('Wiki sync failed')
    time.sleep(1)
else:raise RuntimeError('Wiki sync not completed')
print(json.dumps({'event':'wiki_synced_and_backend_readback_verified','topicCount':len(topics),'linksUseLoopback':all('127.0.0.1' in t['wikiUrl'] for t in topics)}),flush=True)
decision=call('POST','/decisions',{'problemId':pid,'choice':'仅为合成验收，暂选虚构方案乙','rationale':'保持虚构两小时时间约束','expectedOutcome':'验证记录和复盘链路','evidence':assistant['evidence']})
call('POST','/decisions/'+decision['id']+'/reviews',{'outcome':'虚构验收结果','reflection':'原理由应保持不变'})
actual=call('GET','/decisions?problemId='+pid)['items'][0]
assert actual['rationale']==decision['rationale'] and len(actual['reviews'])==1
print(json.dumps({'event':'owner_decision_and_append_review_verified','problemId':pid}),flush=True)
