#!/usr/bin/env python3
"""Initialize the project's fresh local Wiki.js 2 instance without printing credentials."""
import hashlib
import hmac
import json
import secrets
import os
import time
import urllib.error
import urllib.parse
import urllib.request


class LocalRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        before = urllib.parse.urlsplit(req.full_url)
        after = urllib.parse.urlsplit(newurl)
        if (before.scheme, before.netloc) != (after.scheme, after.netloc):
            raise urllib.error.HTTPError(req.full_url, code, 'Wiki redirected outside its origin',
                                         headers, fp)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class WikiClient:
    def __init__(self, base, *, identity_key=None):
        parsed = urllib.parse.urlsplit(base)
        if (parsed.scheme != 'http' or parsed.hostname not in ('127.0.0.1', 'localhost')
                or parsed.username or parsed.password or parsed.path not in ('', '/')
                or parsed.query or parsed.fragment):
            raise RuntimeError('This setup helper only initializes the local project Wiki.')
        self.base = base.rstrip('/')
        self.identity_key = bytes.fromhex(identity_key) if identity_key else None
        if self.identity_key is not None and len(self.identity_key) != 32:
            raise ValueError('Invalid Wiki identity key')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), LocalRedirect())

    def verify_identity(self):
        if self.identity_key is None:
            return
        challenge = secrets.token_hex(32)
        expected = hmac.new(self.identity_key, challenge.encode(), hashlib.sha256).hexdigest()
        request = urllib.request.Request(self.base + '/_desktop/ready',
                                         headers={'X-Decision-Challenge': challenge})
        with self.opener.open(request, timeout=3) as response:
            actual = response.read(128).decode('ascii')
        if not hmac.compare_digest(actual, expected):
            raise RuntimeError('Wiki service identity verification failed')

    def post(self, path, payload, token=None):
        self.verify_identity()
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.base + path, data=json.dumps(payload).encode(), headers=headers)
        with self.opener.open(request, timeout=45) as response:
            return json.load(response)

    def gql(self, query, variables=None, token=None):
        result = self.post('/graphql', {'query':query, 'variables':variables or {}}, token)
        if result.get('errors'):
            # Error paths and messages only; never dump the request or auth response.
            raise RuntimeError('; '.join(e.get('message','GraphQL failure') for e in result['errors']))
        return result['data']

    def home(self, timeout):
        self.verify_identity()
        with self.opener.open(self.base, timeout=timeout) as response:
            return response.read().decode()


def initialize_wiki(client, email, password, token=''):
    gql = client.gql
    html = client.home(10)
    if '<title>Wiki.js Setup</title>' in html:
        result = client.post('/finalize', {'adminEmail': email, 'adminPassword': password,
                                           'adminPasswordConfirm': password,
                                           'siteUrl': client.base, 'telemetry': False})
        if not result.get('ok'):
            raise RuntimeError('Wiki initial setup failed; inspect local service logs.')
        print('Local Wiki administrator created; telemetry disabled.')
        # Setup shuts down and boots the normal HTTP application.
        for _ in range(30):
            time.sleep(1)
            try:
                body = client.home(2)
                if '<title>Wiki.js Setup</title>' not in body:
                    break
            except (OSError, urllib.error.URLError):
                pass

    login = gql('mutation($u:String!,$p:String!){authentication{login(username:$u,password:$p,strategy:"local"){responseResult{succeeded message} jwt}}}',
                {'u': email, 'p': password})['authentication']['login']
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
    if not token:
        created=gql('mutation($group:Int!){authentication{createApiKey(name:"Decision local integration",expiration:"365d",fullAccess:false,group:$group){responseResult{succeeded message} key}}}',{'group':group['id']},admin)['authentication']['createApiKey']
        if not created['responseResult']['succeeded']:
            raise RuntimeError('Cannot create application Wiki token.')
        token = created['key']
    gql('{pages{list{id title path}}}', token=token)
    return token


def main():
    from manage import read_env, ENV_FILE, ROOT

    values = read_env()
    base = values.get('WIKI_BASE_URL', 'http://127.0.0.1:8090').rstrip('/')
    current_token = values.get('WIKI_ACCESS_TOKEN', '')
    token = initialize_wiki(WikiClient(base), values['WIKI_ADMIN_EMAIL'],
                            values['WIKI_ADMIN_PASSWORD'], current_token)
    if token != current_token:
        values['WIKI_ACCESS_TOKEN'] = token
        values['WIKI_LOCALE'] = 'en'  # Fresh Wiki includes en; page contents can still be Chinese.
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
    login_file=ROOT / '.local/wiki-login.txt'
    login_file.parent.mkdir(exist_ok=True)
    fd=os.open(login_file,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o600)
    with os.fdopen(fd,'w') as out:
        out.write('Wiki: '+base+'\nEmail: '+values['WIKI_ADMIN_EMAIL']+'\nPassword: '+values['WIKI_ADMIN_PASSWORD']+'\n')
    print('Wiki authenticated API read: OK. Actual page-write verification is the next integration step.')


if __name__=='__main__':
    main()
