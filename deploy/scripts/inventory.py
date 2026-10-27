#!/usr/bin/env python3
"""保存必要的运行状态和摘要，不输出容器环境变量或配置明文。"""
import hashlib
import json
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path('/usr/dazao-character')
PROTECTED_FILES = [
    '/usr/saya/compose.yaml', '/usr/saya/.env', '/usr/saya/postgresql/compose.yaml',
    '/etc/nginx/conf.d/saya-wecom-callback.conf', '/etc/nginx/sites-available/default',
]


def command(args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=30)
    return {'code': result.returncode, 'stdout': result.stdout.strip(), 'stderr': result.stderr.strip()}


def snapshot():
    ids = subprocess.check_output(['docker', 'ps', '-q'], text=True).split()
    containers = json.loads(subprocess.check_output(['docker', 'inspect'] + ids, text=True)) if ids else []
    protected = []
    for item in containers:
        if not (item['Name'].startswith('/saya') or item['Name'].startswith('/dazao-prod-')):
            continue
        protected.append({
            'name': item['Name'], 'id': item['Id'], 'image': item['Image'],
            'started': item['State']['StartedAt'], 'restarts': item['RestartCount'],
            'running': item['State']['Running'], 'oom': item['State']['OOMKilled'],
            'configHash': item['Config'].get('Labels', {}).get('com.docker.compose.config-hash'),
        })
    hashes = {name: hashlib.sha256(Path(name).read_bytes()).hexdigest()
              for name in PROTECTED_FILES if Path(name).is_file()}
    return {
        'containers': sorted(protected, key=lambda item: item['name']), 'configHashes': hashes,
        'memory': command(['free', '-m']), 'disk': command(['df', '-h', '/']),
        'ingressHash': hashlib.sha256(Path('/etc/nginx/snippets/dazao-migration.locations.conf').read_bytes()).hexdigest(),
    }


def verify_unchanged(before, after):
    if before['containers'] != after['containers'] or before['configHashes'] != after['configHashes']:
        raise RuntimeError('Protected containers or configurations changed; stop and inspect.')


def health_probes():
    # 只读取现有 Redis 启动参数取得密码，通过 stdin 传递，避免出现在命令行或输出中。
    redis = json.loads(subprocess.check_output(['docker', 'inspect', 'saya-redis'], text=True))[0]['Config']
    args = redis.get('Cmd') or []
    values = dict(item.split('=', 1) for item in redis.get('Env', []) if '=' in item)
    password = values.get('REDIS_PASSWORD', '')
    if '--requirepass' in args:
        password = args[args.index('--requirepass') + 1]
    redis_probe = subprocess.run(['docker', 'exec', '-i', 'saya-redis', 'sh', '-c',
                                  'read -r REDISCLI_AUTH; export REDISCLI_AUTH; redis-cli ping'],
                                 input=password + '\n', capture_output=True, text=True, timeout=15)
    probes = {
        'sayaMysql': command(['docker', 'exec', 'saya-mysql', 'sh', '-c',
                              'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -Nse "SELECT 1"']),
        'sayaRedis': {'code': redis_probe.returncode, 'stdout': redis_probe.stdout.strip(),
                     'stderr': redis_probe.stderr.strip()},
        'sayaPostgres': command(['docker', 'exec', 'saya-postgresql', 'sh', '-c',
                                 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"']),
    }
    try:
        with urllib.request.urlopen('http://127.0.0.1:8848/nacos/', timeout=10) as response:
            probes['sayaNacos'] = {'code': response.status}
    except Exception as error:
        probes['sayaNacos'] = {'errorType': type(error).__name__}
    return probes


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['before', 'after'])
    args = parser.parse_args()
    if Path.cwd().resolve() != ROOT:
        raise SystemExit('Run only from /usr/dazao-character')
    (ROOT / 'backups').mkdir(exist_ok=True, mode=0o700)
    state = snapshot()
    state['probes'] = health_probes()
    if args.mode == 'before':
        target = ROOT / 'backups/before.json'
        if target.exists():
            raise SystemExit('Baseline already exists; refusing to overwrite.')
    else:
        before = json.loads((ROOT / 'backups/before.json').read_text())
        verify_unchanged(before, state)
        state['protectedServicesUnchanged'] = True
        target = ROOT / 'backups/after.json'
    target.write_text(json.dumps(state, indent=2))
    target.chmod(0o600)
    print(json.dumps({'mode': args.mode, 'protectedContainers': len(state['containers']),
                      'protectedServicesUnchanged': state.get('protectedServicesUnchanged'), 'probes': state['probes']}))
