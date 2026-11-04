#!/usr/bin/env python3
"""Generate the checked-in, dependency-free OpenAPI 3.0 description of CONTRACT v1."""
import json
from pathlib import Path

def string(nullable=False, **kw): return dict(type='string', **({'nullable':True} if nullable else {}), **kw)
def arr(items=None): return {'type':'array','items':items or string()}
def obj(properties, required=None): return {'type':'object','properties':properties,'required':list(properties) if required is None else required}
def ref(name): return {'$ref':'#/components/schemas/'+name}
def enum(*values): return string(enum=list(values))
uuid=string(format='uuid'); time=string(format='date-time'); nullable_time=string(True,format='date-time')
schemas={}
def entity(name,fields,created=True,updated=True):
    fields={'id':uuid,**fields}
    if created: fields['createdAt']=time
    if updated: fields['updatedAt']=time
    schemas[name]=obj(fields)
schemas['Error']=obj({'code':string(),'message':string(),'retryable':{'type':'boolean'}})
schemas['ErrorResponse']=obj({'error':ref('Error'),'requestId':string()})
schemas['Evidence']=obj({'sourceId':uuid,'quote':string(),'relation':enum('supports','contradicts','context')})
ev=arr(ref('Evidence')); mode=enum('real','mock')
entity('Problem',{'title':string(),'goal':string(),'constraints':arr(),'unknowns':arr()})
entity('Source',{'problemId':uuid,'kind':enum('article','observation','user_statement'),'title':string(),'text':string(),'originUrl':string(True),'author':string(True),'capturedAt':time,'status':enum('ready','partial','failed'),'extractionMethod':enum('user_input','http','ocr','accessibility'),'locator':{'type':'object'}},updated=False)
entity('Message',{'problemId':uuid,'role':enum('user','assistant'),'content':string(),'evidence':ev,'uncertainties':arr(),'mode':mode},updated=False)
entity('Job',{'kind':enum('chat','source_ingest','topic_sync','analysis'),'status':enum('queued','running','succeeded','failed'),'result':{'type':'object','nullable':True,'description':'chat: userMessageId,assistantMessageId; source_ingest: sourceId,topicIds; topic_sync: topicId,wikiUrl; analysis: discussionIds'},'error':{'allOf':[ref('Error')],'nullable':True},'mode':mode})
entity('Memory',{'problemId':uuid,'content':string(),'kind':enum('user_reported','observed','inferred'),'evidence':ev,'status':enum('active','retracted'),'revision':{'type':'integer'}})
entity('Topic',{'problemId':uuid,'title':string(),'markdown':string(),'evidence':ev,'revision':{'type':'integer'},'wikiPageId':string(True),'wikiUrl':string(True),'syncStatus':enum('pending_configuration','pending','synced','failed')},created=False)
entity('Discussion',{'problemId':uuid,'title':string(),'reason':string(),'evidence':ev,'status':enum('unread','read','snoozed','dismissed'),'snoozeUntil':nullable_time})
entity('Review',{'outcome':string(),'reflection':string(),'evidence':ev,'recordedAt':time},False,False)
entity('Decision',{'problemId':uuid,'choice':string(),'rationale':string(),'expectedOutcome':string(),'evidence':ev,'confirmedAt':time,'reviewAt':nullable_time,'revision':{'type':'integer'},'reviews':arr(ref('Review'))},False,False)
entity('Device',{'name':string(),'desiredState':enum('paused','running'),'reportedState':enum('paused','running','error','offline'),'lastHeartbeatAt':nullable_time,'lastObservationAt':nullable_time,'lastError':string(True)},False,False)
entity('Delivery',{'discussionId':uuid,'deviceId':uuid,'channel':enum('desktop'),'status':enum('pending','sent','failed','user_confirmed'),'error':string(True)},created=False)
schemas['Status']=obj({'model':obj({'configured':{'type':'boolean'},'lastCallStatus':string()}),'wiki':obj({'configured':{'type':'boolean'},'status':string()}),'observer':obj({'lastSeenAt':nullable_time}),'usage':obj({'requests':{'type':'integer'},'inputTokens':{'type':'integer'},'outputTokens':{'type':'integer'},'costEstimate':{'type':'number','nullable':True},'currency':string(True)})})
paths={}

def route(method,path,response,code=200,body=None,required=None,query=(),public=False):
    operation={'operationId':method+'_'+path.strip('/').replace('/','_').replace('{','').replace('}',''),'responses':{str(code):{'description':'Success'}}}
    if response is not None: operation['responses'][str(code)]['content']={'application/json':{'schema':response}}
    for status in [400,401,403,404,409,429,502,503]:operation['responses'][str(status)]={'description':'Structured error','content':{'application/json':{'schema':ref('ErrorResponse')}}}
    parameters=[]
    for part in path.split('/'):
        if part.startswith('{'):parameters.append({'name':part[1:-1],'in':'path','required':True,'schema':uuid})
    if method=='post':parameters.append({'name':'Idempotency-Key','in':'header','required':True,'schema':uuid,'description':'Same subject/path/key and payload replays the response; changed payload returns 409.'})
    for name in query:parameters.append({'name':name,'in':'query','schema':({'type':'integer','default':30,'minimum':1,'maximum':100} if name=='limit' else string())})
    if parameters:operation['parameters']=parameters
    if body is not None:operation['requestBody']={'required':True,'content':{'application/json':{'schema':obj(body,required)}}}
    if public:operation['security']=[]
    paths.setdefault(path,{})[method]=operation

route('get','/health',obj({'status':enum('ok'),'version':string()}),public=True)
route('post','/auth/login',obj({'accessToken':string(),'expiresAt':time}),body={'accessCode':string()},public=True)
route('get','/status',ref('Status'))
route('post','/problems',ref('Problem'),201,{'title':string(),'goal':string(),'constraints':arr(),'unknowns':arr()},['title','goal'])
route('patch','/problems/{id}',ref('Problem'),body={'title':string(),'goal':string(),'constraints':arr(),'unknowns':arr()},required=[])
route('post','/problems/{id}/messages',ref('Job'),202,{'content':string(),'sourceIds':arr(uuid)},['content'])
route('get','/problems/{id}/messages',obj({'items':arr(ref('Message')),'nextCursor':string(True)}),query=['cursor','limit'])
route('get','/jobs/{id}',ref('Job'))
route('post','/sources',ref('Job'),202,{'problemId':uuid,'kind':enum('article','user_statement'),'title':string(),'text':string(),'url':string(),'author':string()},['problemId','kind'])
route('get','/sources/{id}',ref('Source'))
route('delete','/sources/{id}',None,204)
route('post','/memories',ref('Memory'),201,{'problemId':uuid,'content':string(),'kind':enum('user_reported')})
route('patch','/memories/{id}',ref('Memory'),body={'content':string(),'status':enum('active','retracted'),'expectedRevision':{'type':'integer'}},required=['expectedRevision'])
route('delete','/memories/{id}',None,204)
route('post','/topics/{id}/sync',ref('Job'),202,{})
route('patch','/discussions/{id}',ref('Discussion'),body={'status':enum('unread','read','snoozed','dismissed'),'snoozeUntil':nullable_time},required=['status'])
route('post','/decisions',ref('Decision'),201,{'problemId':uuid,'choice':string(),'rationale':string(),'expectedOutcome':string(),'evidence':ev,'reviewAt':nullable_time},['problemId','choice','rationale','expectedOutcome'])
route('post','/decisions/{id}/reviews',ref('Review'),201,{'outcome':string(),'reflection':string(),'evidence':ev},['outcome','reflection'])
route('post','/devices',obj({'device':ref('Device'),'deviceToken':string()}),201,{'name':string()})
route('patch','/devices/{id}',ref('Device'),body={'desiredState':enum('paused','running')})
route('post','/devices/{id}/heartbeat',obj({'device':ref('Device')}),body={'reportedState':enum('paused','running','error','offline'),'lastObservationAt':nullable_time,'lastError':string(True)},required=['reportedState'])
observation=obj({'externalId':string(),'problemId':uuid,'text':string(maxLength=20000),'appName':string(),'capturedAt':time,'extractionMethod':enum('ocr','accessibility'),'sourceLocator':{'type':'object','description':'Only opaque frameId/resourceId persisted, never paths or screenshots.'},'contentHash':string(pattern='^[a-fA-F0-9]{64}$')})
route('post','/observations/batch',obj({'accepted':arr(),'duplicates':arr(),'rejected':arr(obj({'externalId':string(),'code':string(),'message':string()}))}),body={'deviceId':uuid,'items':{**arr(observation),'maxItems':50}})
route('get','/devices/{id}/deliveries',obj({'items':arr(obj({'delivery':ref('Delivery'),'discussion':obj({'id':uuid,'title':string(),'reason':string()})})),'nextCursor':string(True)}),query=['cursor','limit'])
route('post','/deliveries/{id}/ack',ref('Delivery'),body={'status':enum('sent','failed'),'error':string(True)},required=['status'])
for resource,name in [('problems','Problem'),('sources','Source'),('memories','Memory'),('topics','Topic'),('discussions','Discussion'),('decisions','Decision'),('devices','Device')]:
    query=['cursor','limit']+([] if resource in ['problems','devices'] else ['problemId'])+(['status'] if resource=='discussions' else [])
    route('get','/'+resource,obj({'items':arr(ref(name)),'nextCursor':string(True)}),query=query)
spec={'openapi':'3.0.3','info':{'title':'Decision MVP API','version':'1.0.0','description':'Single-owner CONTRACT v1. No registration. Device tokens are restricted to heartbeat, observation upload, own deliveries/acks and owned jobs. Mock mode is explicit and disabled by default.'},'servers':[{'url':'/api/v1'}],'security':[{'bearerAuth':[]}],'paths':paths,'components':{'securitySchemes':{'bearerAuth':{'type':'http','scheme':'bearer'}},'schemas':schemas}}
Path(__file__).resolve().parents[1].joinpath('openapi.json').write_text(json.dumps(spec,ensure_ascii=False,indent=2)+'\n')
