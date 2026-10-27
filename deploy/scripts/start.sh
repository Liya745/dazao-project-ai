#!/bin/sh
set -eu
cd /usr/dazao-character
test -f .env
test -f app/application.jar
test -d data
python3 - <<'PY'
import json, pathlib, subprocess
lock = json.loads(pathlib.Path('artifact.json').read_text())
image = subprocess.check_output(['docker','image','inspect',lock['imageAlias'],'--format','{{.Id}}'],text=True).strip()
if image != lock['imageId']:
    raise SystemExit('Java runtime image differs from the verified baseline.')
PY
exec docker compose --ansi never --progress plain -p dazao-character -f compose.yaml up -d --wait --wait-timeout 180 studio
