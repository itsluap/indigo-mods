"""Build and pin indigo_client (extras/indigo-client): Indigo's client-only defaults mod (first-launch texture packs).

  python scripts/indigo-client-build.py build   reproducible jar -> .work/indigo-client/build/indigo_client-<v>.jar
  python scripts/indigo-client-build.py pin     record its size and hashes in manifest/stack.json customMods
"""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'extras/indigo-client'
OUT = ROOT / '.work/indigo-client/build'
VERSION = '0.4.3'
JAR = OUT / f'indigo_client-{VERSION}.jar'


def eb():
    spec = importlib.util.spec_from_file_location('economy_build', ROOT / 'scripts/economy-build.py')
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def build():
    b = eb()
    OUT.mkdir(parents=True, exist_ok=True)
    b.compile_to(OUT / 'classes', sorted((SRC / 'src').rglob('*.java')), b.client_classpath())
    b.jar(JAR, OUT / 'classes', SRC / 'resources', extra=[('LICENSE', (SRC / 'LICENSE').read_bytes())])
    print(f'{JAR.name}  {JAR.stat().st_size} bytes  sha256 {hashlib.sha256(JAR.read_bytes()).hexdigest()}')


def pin():
    mp = ROOT / 'manifest/stack.json'
    raw = mp.read_bytes().decode('utf-8-sig')
    crlf = chr(13) + chr(10) in raw
    stack = json.loads(raw)
    data = JAR.read_bytes()
    m = next((m for m in stack.get('customMods', []) if m['modId'] == 'indigo_client'), None)
    if m is None:
        sys.exit('indigo_client is not in manifest customMods')
    m.update(versionNumber=VERSION, filename=JAR.name, artifact=JAR.relative_to(ROOT).as_posix(), fileSize=len(data),
             sha1=hashlib.sha1(data).hexdigest(), sha256=hashlib.sha256(data).hexdigest(), sha512=hashlib.sha512(data).hexdigest())
    text = json.dumps(stack, indent=2, ensure_ascii=False) + chr(10)
    mp.write_bytes((text.replace(chr(10), chr(13) + chr(10)) if crlf else text).encode('utf-8'))
    print(f'pinned {JAR.name} sha256 {m["sha256"]}')


if __name__ == '__main__':
    {'build': build, 'pin': pin}[sys.argv[1]]()
