#!/usr/bin/env python3
"""只验证新项目：临时账号、角色生成、持久化和真实 SSE；输出不包含密码或密钥。"""
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

    def call(path, method='GET', body=None, form=False, streaming=False):
        headers = {}
        if method != 'GET':
            with client.open(BASE + '/api/auth/csrf', timeout=15) as response:
                token = json.load(response)
            headers[token['headerName']] = token['token']
        payload = None
        if body is not None:
            if form:
                payload = urlencode(body).encode()
                headers['Content-Type'] = 'application/x-www-form-urlencoded'
            else:
                payload = json.dumps(body).encode()
                headers['Content-Type'] = 'application/json'
        if streaming:
            headers['Accept'] = 'text/event-stream, application/json'
        request = urllib.request.Request(BASE + path, data=payload, headers=headers, method=method)
        response = client.open(request, timeout=150 if streaming else 110)
        if streaming:
            return response
        with response:
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
            raise RuntimeError('Session cookie is not isolated by name and path')
        result['checks']['registerLoginCsrfCookie'] = True
        definition = call('/api/characters/generate', 'POST', {
            'brief': '创造一个深夜经营旧书店的猫妖，外冷内热，说话简短，有克制的冷幽默。各字段简洁，每个字段不超过80汉字，名称不超过8字。',
            'sourceType': 'original',
        })
        character = call('/api/characters', 'POST', definition)
        conversation = call('/api/conversations', 'POST', {'characterId': character['id']})
        result['checks']['realCharacterGeneration'] = True
        result['checks']['characterFields'] = list(definition)
        events = []
        buffer = []
        with call('/api/conversations/' + conversation['id'] + '/messages', 'POST',
                  {'content': '今晚店里还有其他客人吗？请用一句话回答。'}, streaming=True) as stream:
            for raw in stream:
                line = raw.decode().rstrip('\r\n')
                if line:
                    buffer.append(line)
                    continue
                event = next((v[6:].strip() for v in buffer if v.startswith('event:')), '')
                payload = '\n'.join(v[5:].strip() for v in buffer if v.startswith('data:'))
                buffer = []
                if event:
                    events.append(event)
                if event == 'error':
                    raise RuntimeError('Model stream reported failure')
                if event == 'done':
                    if not json.loads(payload).get('content'):
                        raise RuntimeError('Empty streamed response')
                    break
        if 'delta' not in events or 'done' not in events:
            raise RuntimeError('SSE delta/done not received')
        stored = call('/api/conversations/' + conversation['id'])
        if stored['turns'][0]['status'] != 'DONE' or not stored['turns'][0]['assistantContent']:
            raise RuntimeError('Completed turn was not persisted')
        result['checks']['realSseAndPersistence'] = True
        result['realProviderTested'] = True
    finally:
        if character:
            call('/api/characters/' + character['id'], 'DELETE')
            result['checks']['testCharacterAndConversationDeleted'] = True
        try:
            call('/api/auth/logout', 'POST')
        except Exception:
            pass
        target = ROOT / 'backups/preflight.json'
        target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
        target.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    if Path.cwd().resolve() != ROOT:
        raise SystemExit('Run only from /usr/dazao-character')
    main()
