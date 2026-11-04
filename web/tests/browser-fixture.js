// Explicit browser-only contract fixture; never imported by the application.
async (page) => {
  const id='11111111-1111-4111-8111-111111111111', sid='22222222-2222-4222-8222-222222222222';
  const evidence=[{sourceId:sid,quote:'仅为虚构测试文本',relation:'context'}];
  const problem={id,title:'契约测试问题',goal:'检查页面交互，不代表真实验收',constraints:[],unknowns:['外部服务尚未验证']};
  const source={id:sid,title:'虚构资料',text:'仅为虚构测试文本',kind:'article',status:'ready',originUrl:'javascript:alert(1)',capturedAt:new Date().toISOString()};
  let messages=[], decisions=[], reviews=[], memory={id:'m1',content:'虚构偏好',kind:'inferred',status:'active',revision:1,evidence};
  await page.route('**/api/v1/**',async route=>{
    const r=route.request(), u=new URL(r.url()), path=u.pathname.replace('/api/v1',''), method=r.method(), body=r.postDataJSON(); let data;let status=200;
    const rows=items=>({items,nextCursor:null});
    if(method==='POST'&&!r.headers()['idempotency-key']) throw new Error('Missing idempotency key');
    if(path==='/auth/login') data={accessToken:'fixture-only',expiresAt:new Date(Date.now()+3600000).toISOString()};
    else if(path==='/problems') data=method==='GET'?rows([problem]):problem;
    else if(path.endsWith('/messages')) {if(method==='GET') data=rows(messages);else {messages.push({id:'u1',role:'user',content:body.content,evidence:[],uncertainties:[],mode:'mock'},{id:'a1',role:'assistant',content:'这是契约替身回答',evidence,uncertainties:['未经真实模型验证'],mode:'mock'});status=202;data={id:'j1',status:'queued',mode:'mock',error:null}}}
    else if(path==='/jobs/j1') data={id:'j1',status:'succeeded',mode:'mock',error:null};
    else if(path==='/sources') {if(method==='GET')data=rows([source]);else {status=202;data={id:'j1',status:'queued',mode:'mock',error:null}}}
    else if(path===`/sources/${sid}`) data=source;
    else if(path==='/topics') data=rows([{id:'t1',title:'测试主题',markdown:'仅为契约替身',wikiUrl:null,syncStatus:'pending_configuration',evidence}]);
    else if(path==='/memories') data=method==='GET'?rows([memory]):{...memory,content:body.content,kind:'user_reported'};
    else if(path==='/memories/m1') {memory={...memory,...body,revision:memory.revision+1};data=memory}
    else if(path==='/discussions') data=rows([{id:'d1',title:'测试议题',reason:'这不是真实通知',status:'unread',evidence}]);
    else if(path==='/discussions/d1')data={id:'d1',...body};
    else if(path==='/decisions') {if(method==='GET')data=rows(decisions.map(d=>({...d,reviews})));else {data={id:'c1',...body,confirmedAt:new Date().toISOString(),reviews:[]};decisions.push(data);status=201}}
    else if(path==='/decisions/c1/reviews'){data={id:'r1',...body};reviews.push(data);status=201}
    else if(path==='/status')data={model:{configured:false,lastCallStatus:'未配置（契约替身）'},wiki:{configured:false,status:'pending_configuration'},observer:{lastSeenAt:null},usage:{requests:0,inputTokens:0,outputTokens:0,costEstimate:null,currency:null}};
    else if(path==='/devices')data=rows([{id:'dev1',name:'测试设备',desiredState:'paused',reportedState:'offline',lastHeartbeatAt:null,lastError:null}]);
    else if(path==='/devices/dev1')data={id:'dev1',...body};
    else {status=503;data={error:{message:'契约测试未配置此操作'}}}
    await route.fulfill({status,contentType:'application/json',body:JSON.stringify(data)});
  });
}
