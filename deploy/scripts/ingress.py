#!/usr/bin/env python3
"""只切换大枣专用入口片段；备份和摘要保护回退，平滑重载宿主机 Nginx。"""
import hashlib
import json
import os
import shutil
import subprocess
from pathlib import Path
from inventory import ROOT, snapshot, verify_unchanged

ROUTE = Path('/etc/nginx/snippets/dazao-migration.locations.conf')
PROXY = Path('/etc/nginx/snippets/dazao-character.proxy.conf')
LIMITS = Path('/etc/nginx/conf.d/00-dazao-character-limits.conf')
BACKUP = ROOT / 'backups/ingress'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def check():
    subprocess.run(['nginx', '-t'], check=True, capture_output=True)


def replace(path, content):
    temporary = path.with_name(path.name + '.dzstudio.tmp')
    if temporary.exists():
        raise RuntimeError('Unexpected temporary Nginx file.')
    temporary.write_bytes(content)
    temporary.chmod(0o644)
    os.replace(temporary, path)


def install():
    if BACKUP.exists() or PROXY.exists() or LIMITS.exists():
        raise RuntimeError('Ingress install already present; refusing to overwrite.')
    before = json.loads((ROOT / 'backups/before.json').read_text())
    current = snapshot()
    verify_unchanged(before, current)
    if current['ingressHash'] != before['ingressHash']:
        raise RuntimeError('Dazao route changed after baseline.')
    check()
    BACKUP.mkdir(mode=0o700)
    shutil.copy2(ROUTE, BACKUP / 'old-route.conf')
    expected = {ROUTE: (ROOT / 'nginx/host-locations.conf').read_bytes(),
                PROXY: (ROOT / 'nginx/proxy.conf').read_bytes(),
                LIMITS: (ROOT / 'nginx/limits.conf').read_bytes()}
    written = []
    try:
        for path, content in expected.items():
            replace(path, content)
            written.append(path)
        check()
        subprocess.run(['nginx', '-s', 'reload'], check=True, capture_output=True)
        verify_unchanged(before, snapshot())
        metadata = {'originalHash': before['ingressHash'],
                    'installedHashes': {str(path): digest(path) for path in expected}}
        (BACKUP / 'manifest.json').write_text(json.dumps(metadata, indent=2))
        print('DAZAO_CHARACTER_INGRESS_INSTALLED')
    except Exception:
        for path in written:
            if path.read_bytes() != expected[path]:
                raise RuntimeError('Concurrent Nginx edit detected; do not overwrite.')
        if ROUTE in written:
            replace(ROUTE, (BACKUP / 'old-route.conf').read_bytes())
        for path in (PROXY, LIMITS):
            if path in written:
                path.unlink()
        check()
        subprocess.run(['nginx', '-s', 'reload'], check=True, capture_output=True)
        raise


def rollback():
    manifest = json.loads((BACKUP / 'manifest.json').read_text())
    for name, expected in manifest['installedHashes'].items():
        if digest(Path(name)) != expected:
            raise RuntimeError('Nginx file changed after cutover; manual review required.')
    old = BACKUP / 'old-route.conf'
    if digest(old) != manifest['originalHash']:
        raise RuntimeError('Original ingress backup does not match.')
    replace(ROUTE, old.read_bytes())
    PROXY.unlink()
    LIMITS.unlink()
    check()
    subprocess.run(['nginx', '-s', 'reload'], check=True, capture_output=True)
    print('OLD_DAZAO_INGRESS_RESTORED; NEW_STUDIO_DATA_RETAINED')


if __name__ == '__main__':
    import sys
    if Path.cwd().resolve() != ROOT or len(sys.argv) != 2:
        raise SystemExit('Run from /usr/dazao-character with install or rollback')
    os.umask(0o077)
    if sys.argv[1] == 'install':
        install()
    elif sys.argv[1] == 'rollback':
        rollback()
    else:
        raise SystemExit('Unknown operation')
