#!/usr/bin/env python3
"""上线前的第一方验收：只访问本机新服务，不调用任何外部模型。"""
import http.cookiejar
import json
import re
import secrets
import urllib.request
from urllib.parse import urlencode
from pathlib import Path

ROOT = Path('/usr/dazao-character')
BASE = 'http://127.0.0.1:18199/dzproject'


def main():
    cookies = http.cookiejar.CookieJar()
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookies))
    username = 'deploy_check_' + secrets.token_hex(6)
    password = secrets.token_urlsafe(24)
    character = None
    result = {'usernameForCleanup': username, 'checks': {}, 'realProviderTested': False}

    def call(path, method='GET', body=None, form=False):
        headers = {}
        if method != 'GET':
            with client.open(BASE + '/api/auth/csrf', timeout=15) as response:
                token = json.load(response)
            headers[token['headerName']] = token['token']
        payload = None
        if body is not None:
            payload = urlencode(body).encode() if form else json.dumps(body).encode()
            headers['Content-Type'] = 'application/x-www-form-urlencoded' if form else 'application/json'
        request = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
        with client.open(request, timeout=15) as response:
            data = response.read()
            return json.loads(data) if data else None

    try:
        with client.open(BASE + '/', timeout=15) as response:
            html = response.read().decode()
        assets = re.findall(r'(?:src|href)="(/dzproject/(?:assets/[^" ]+|favicon.svg))"', html)
        if not assets:
            raise RuntimeError('Deployment asset prefix missing')
        for asset in assets:
            with client.open('http://127.0.0.1:18199' + asset, timeout=15) as response:
                if response.status != 200:
                    raise RuntimeError('Static asset unavailable')
        result['checks']['assetsUnderPrefix'] = len(assets)
        call('/api/auth/register', 'POST', {'username': username, 'password': password})
        call('/api/auth/login', 'POST', {'username': username, 'password': password}, form=True)
        if not call('/api/auth/session')['authenticated']:
            raise RuntimeError('Login did not create a session')
        if not any(c.name == 'DZSTUDIOSESSION' and c.path == '/dzproject' for c in cookies):
            raise RuntimeError('Session cookie isolation failed')
        result['checks']['registerLoginCsrfCookie'] = True
        definition = {'name': '部署校验角色', 'description': '仅用于手工创建接口验证',
                      'personality': '简洁', 'scenario': '', 'firstMessage': '你好。',
                      'messageExample': '', 'appearance': '', 'speechStyle': '',
                      'sourceType': 'original', 'work': '', 'canonVersion': ''}
        character = call('/api/characters', 'POST', definition)
        conversation = call('/api/conversations', 'POST', {'characterId': character['id']})
        saved = call('/api/conversations/' + conversation['id'])
        if saved['conversation']['definition']['name'] != definition['name'] or saved['turns'] != []:
            raise RuntimeError('Conversation snapshot or chat_turns table query failed')
        card = call('/api/characters/' + character['id'] + '/export')
        if card['spec'] != 'chara_card_v2':
            raise RuntimeError('Character card export failed')
        result['checks']['manualCharacterConversationSnapshotAndExport'] = True
        result['modelConfigured'] = call('/api/status')['aiConfigured']
    finally:
        if character:
            call('/api/characters/' + character['id'], 'DELETE')
            result['checks']['testCharacterAndConversationDeleted'] = True
        try:
            call('/api/auth/logout', 'POST')
        except Exception:
            pass
        path = ROOT / 'backups/preflight.json'
        path.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        path.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    if Path.cwd().resolve() != ROOT:
        raise SystemExit('Run only from /usr/dazao-character')
    main()
