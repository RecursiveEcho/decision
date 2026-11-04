#!/usr/bin/env python3
"""Initialize the project's fresh local Wiki.js 2 instance without printing credentials."""
import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from manage import read_env, ENV_FILE, ROOT


def main():
    values = read_env()
    base = values.get('WIKI_BASE_URL', 'http://127.0.0.1:8090').rstrip('/')
    if urllib.parse.urlparse(base).hostname not in ('127.0.0.1', 'localhost'):
        raise RuntimeError('This setup helper only initializes the local project Wiki.')

    def post(path, payload, token=None):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(base + path, data=json.dumps(payload).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=45) as response:
            return json.load(response)

    def gql(query, variables=None, token=None):
        result = post('/graphql', {'query':query, 'variables':variables or {}}, token)
        if result.get('errors'):
            # Error paths and messages only; never dump the request or auth response.
            raise RuntimeError('; '.join(e.get('message','GraphQL failure') for e in result['errors']))
        return result['data']

    html = urllib.request.urlopen(base, timeout=10).read().decode()
    if '<title>Wiki.js Setup</title>' in html:
        result = post('/finalize', {'adminEmail':values['WIKI_ADMIN_EMAIL'],
                                   'adminPassword':values['WIKI_ADMIN_PASSWORD'],
                                   'adminPasswordConfirm':values['WIKI_ADMIN_PASSWORD'],
                                   'siteUrl':base, 'telemetry':False})
        if not result.get('ok'):
            raise RuntimeError('Wiki initial setup failed; inspect local service logs.')
        print('Local Wiki administrator created; telemetry disabled.')
        # Setup shuts down and boots the normal HTTP application.
        for _ in range(30):
            time.sleep(1)
            try:
                body = urllib.request.urlopen(base,timeout=2).read().decode()
                if '<title>Wiki.js Setup</title>' not in body:
                    break
            except (OSError, urllib.error.URLError):
                pass

    login = gql('mutation($u:String!,$p:String!){authentication{login(username:$u,password:$p,strategy:"local"){responseResult{succeeded message} jwt}}}',
                {'u':values['WIKI_ADMIN_EMAIL'],'p':values['WIKI_ADMIN_PASSWORD']})['authentication']['login']
    if not login['responseResult']['succeeded'] or not login.get('jwt'):
        raise RuntimeError('Wiki admin login failed. Do not reset an existing instance automatically.')
    admin = login['jwt']
    update = 'mutation($id:Int!,$name:String!,$perms:[String]!,$rules:[PageRuleInput]!){groups{update(id:$id,name:$name,redirectOnLogin:"",permissions:$perms,pageRules:$rules){responseResult{succeeded message}}}}'
    result = gql(update, {'id':2,'name':'Guests','perms':[],'rules':[]},admin)
    if not result['groups']['update']['responseResult']['succeeded']:
        raise RuntimeError('Cannot disable guest access.')
    print('Wiki guest permissions removed.')
    result = gql('mutation{authentication{setApiState(enabled:true){responseResult{succeeded message}}}}', token=admin)
    if not result['authentication']['setApiState']['responseResult']['succeeded']:
        raise RuntimeError('Cannot enable local Wiki API.')
    groups = gql('{groups{list{id name}}}',token=admin)['groups']['list']
    group = next((g for g in groups if g['name']=='Decision application'),None)
    if not group:
        created = gql('mutation{groups{create(name:"Decision application"){responseResult{succeeded message} group{id name}}}}',token=admin)['groups']['create']
        if not created['responseResult']['succeeded']:
            raise RuntimeError('Cannot create Wiki application group.')
        group=created['group']
    perms=['read:pages','write:pages','manage:pages','delete:pages']
    rules=[{'id':'decision-app','deny':False,'match':'START','roles':perms,'path':'','locales':[]}]
    result=gql(update,{'id':group['id'],'name':group['name'],'perms':perms,'rules':rules},admin)
    if not result['groups']['update']['responseResult']['succeeded']:
        raise RuntimeError('Cannot set Wiki application permissions.')
    if not values.get('WIKI_ACCESS_TOKEN'):
        created=gql('mutation($group:Int!){authentication{createApiKey(name:"Decision local integration",expiration:"365d",fullAccess:false,group:$group){responseResult{succeeded message} key}}}',{'group':group['id']},admin)['authentication']['createApiKey']
        if not created['responseResult']['succeeded']:
            raise RuntimeError('Cannot create application Wiki token.')
        values['WIKI_ACCESS_TOKEN']=created['key']
        values['WIKI_LOCALE']='en'  # Fresh Wiki includes en; page contents can still be Chinese.
        # Preserve concurrent user edits (for example entering the model key).
        current=read_env()
        current.update({k:values[k] for k in ('WIKI_ACCESS_TOKEN','WIKI_LOCALE')})
        temporary=ENV_FILE.with_suffix('.tmp')
        fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
        with os.fdopen(fd,'w') as out:
            out.write('# Local only. Do not commit or paste into chat.\n'+''.join(f'{k}={v}\n' for k,v in current.items()))
        temporary.replace(ENV_FILE)
        ENV_FILE.chmod(0o600)
        print('Scoped application token saved to ignored .env (not printed).')
    gql('{pages{list{id title path}}}',token=values['WIKI_ACCESS_TOKEN'])
    login_file=ROOT / '.local/wiki-login.txt'
    login_file.parent.mkdir(exist_ok=True)
    fd=os.open(login_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as out:
        out.write('Wiki: '+base+'\nEmail: '+values['WIKI_ADMIN_EMAIL']+'\nPassword: '+values['WIKI_ADMIN_PASSWORD']+'\n')
    print('Wiki authenticated API read: OK. Actual page-write verification is the next integration step.')


if __name__=='__main__':
    main()
