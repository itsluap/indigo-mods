"""Build and headless-test the Indigo account economy prototype (LAB ONLY).

  core-test   compile the Minecraft-independent ledger/link/bridge classes against Gson only
              (proves they need no game classes) and run extras/indigo-economy/test/CoreTest
  company-test  Indigo companies: build a real pre-company journal with the core from LEGACY_REF (git), run
              extras/indigo-economy/test/CompanyTest against the current core, then check every journal it
              leaves with the independent scripts/economy-ledger-verify.py (company replay included)
  build       compile the mod (server + optional client screen) and the two lab-only probes
              against the exact NeoForge 21.1.248 / Minecraft 1.21.1 libraries Prism installed
              (read-only), and write jars to .work/economy/build/

Nothing here touches a Prism instance, the permanent world or the canonical mod set.
"""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'extras/indigo-economy'
PROBES = ROOT / 'extras/indigo-economy-probe'
OUT = ROOT / '.work/economy/build'
PRISM_LIBS = Path(os.environ.get('APPDATA', '')) / 'PrismLauncher/libraries'   # read-only
VERSION = '0.5.4'
MOD_JAR = OUT / f'indigo_economy-{VERSION}.jar'
SERVER_PROBE_JAR = OUT / 'indigo-economy-lab-probe.jar'
CLIENT_PROBE_JAR = OUT / 'indigo-economy-client-probe.jar'


def javac():
    return str(Path(shutil.which('java')).with_name('javac.exe' if os.name == 'nt' else 'javac'))


# The core is plain Java + Gson. Minecraft 1.21.1 ships gson 2.10.1; outside Windows/Prism (a cloud or CI checkout)
# pass that jar with --gson or INDIGO_GSON_JAR (Maven Central com.google.code.gson:gson:2.10.1, sha256 below).
GSON_2_10_1_SHA256 = '4241c14a7727c34feea6507ec801318a3d4a90f070e4525681079fb94ee4c593'
# main before companies (the merge base of claude/indigo-companies): its core writes the "legacy" journal
LEGACY_REF = '040c576590e5b10d35229d9b3b9799ee477b6d24'


def gson(a=None):
    explicit = (getattr(a, 'gson', None) or os.environ.get('INDIGO_GSON_JAR', '')).strip()
    if explicit:
        j = Path(explicit)
        if not j.is_file():
            sys.exit(f'gson jar not found: {j}')
        if j.name == 'gson-2.10.1.jar' and hashlib.sha256(j.read_bytes()).hexdigest() != GSON_2_10_1_SHA256:
            sys.exit(f'{j} does not match the gson 2.10.1 sha256')
        return j
    jars = sorted(PRISM_LIBS.glob('com/google/code/gson/gson/2.10*/gson-*.jar'))
    if not jars:
        sys.exit('gson 2.10.x not found in the Prism libraries (pass --gson or set INDIGO_GSON_JAR)')
    return jars[-1]


def client_classpath():
    client = list((PRISM_LIBS / 'net/minecraft/client').rglob('client-*-srg.jar'))
    neo = PRISM_LIBS / 'net/neoforged/neoforge/21.1.248/neoforge-21.1.248-client.jar'
    if not client or not neo.exists():
        sys.exit('NeoForge 21.1.248 client libraries not found (Prism must have launched this version once)')
    rest = [j for j in PRISM_LIBS.rglob('*.jar')
            if 'net/minecraft/client' not in j.as_posix() and 'neoforged/neoforge' not in j.as_posix()
            and 'gson-2.8' not in j.name]
    return client + [neo, neo.with_name('neoforge-21.1.248-universal.jar')] + rest


def lc_jar():
    """The canonical Lightman's Currency jar (compile-time API), sha512-verified against manifest/stack.json."""
    stack = json.loads((ROOT / 'manifest/stack.json').read_text(encoding='utf-8-sig'))
    want = next(m for m in stack['mods'] if m['filename'].startswith('lightmanscurrency-'))
    for d in (ROOT / '.work/economy/runtime/mods', Path(os.environ.get('APPDATA', '')) / 'PrismLauncher/instances/prism-visual-test/minecraft/mods'):
        j = d / want['filename']
        if j.exists() and hashlib.sha512(j.read_bytes()).hexdigest() == want['sha512']:
            return j
    sys.exit('no hash-verified ' + want['filename'] + ' found (run economy-lab.py setup first)')


def compile_to(classes, sources, cp):
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    args = classes.parent / (classes.name + '.javac.args')
    q = lambda p: '"' + str(p).replace('\\', '/') + '"'
    args.write_text('-proc:none\n-encoding UTF-8\n--release 21\n-Xlint:-options\n-classpath\n'
                    + q(os.pathsep.join(str(j) for j in cp)) + '\n-d\n' + q(classes) + '\n'
                    + '\n'.join(q(s) for s in sources) + '\n')
    r = subprocess.run([javac(), '@' + str(args)], capture_output=True, text=True, timeout=600)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        sys.exit(f'javac failed for {classes.name}')
    if r.stderr.strip():
        print(r.stderr.strip())


def jar(path, classes, resources=None, toml=None, extra=()):
    """Reproducible jar: sorted entries, fixed timestamps and attributes, so the manifest can pin its hash."""
    entries = [(p.relative_to(classes).as_posix(), p.read_bytes()) for p in classes.rglob('*.class')]
    if resources:
        entries += [(p.relative_to(resources).as_posix(), p.read_bytes()) for p in resources.rglob('*') if p.is_file()]
    if toml:
        entries.append(('META-INF/neoforge.mods.toml', toml.encode('utf-8')))
    entries += list(extra)
    path.unlink(missing_ok=True)
    with zipfile.ZipFile(path, 'w') as z:
        for name, data in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, data, compresslevel=9)


def probe_toml(modid, name):
    return (f'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="All Rights Reserved"\n[[mods]]\nmodId="{modid}"\n'
            f'version="1.0"\ndisplayName="{name}"\ndescription="Disposable lab instrumentation. Never part of any pack."\n')


def build(a):
    OUT.mkdir(parents=True, exist_ok=True)
    cp = client_classpath() + [lc_jar()]
    srcs = sorted((SRC / 'src').rglob('*.java'))
    compile_to(OUT / 'mod-classes', srcs, cp)
    # the MIT licence text travels inside the jar (from 0.4.1), next to the mods.toml licence field
    jar(MOD_JAR, OUT / 'mod-classes', SRC / 'resources', extra=[('LICENSE', (SRC / 'LICENSE').read_bytes())])
    compile_to(OUT / "server-probe-classes", [PROBES / "EconomyLabProbe.java"], [OUT / "mod-classes"] + cp)
    jar(SERVER_PROBE_JAR, OUT / 'server-probe-classes', toml=probe_toml('indigo_economy_lab', 'Indigo economy lab probe (server)'))
    compile_to(OUT / 'client-probe-classes', [PROBES / 'EconomyClientProbe.java'], [OUT / 'mod-classes'] + cp)
    jar(CLIENT_PROBE_JAR, OUT / 'client-probe-classes', toml=probe_toml('indigo_economy_client_lab', 'Indigo economy lab probe (client)'))
    for j in (MOD_JAR, SERVER_PROBE_JAR, CLIENT_PROBE_JAR):
        print(f'{j.name}  {j.stat().st_size} bytes')


def core_test(a):
    out = ROOT / '.work/economy/core-test'
    scratch = out / 'scratch'
    if scratch.exists():
        shutil.rmtree(scratch)
    scratch.mkdir(parents=True)
    g = gson(a)
    srcs = sorted((SRC / 'src/online/indigomc/economy/core').glob('*.java')) + [SRC / 'test/CoreTest.java']
    compile_to(out / 'classes', srcs, [g])
    r = subprocess.run(['java', '-cp', os.pathsep.join([str(out / 'classes'), str(g)]), 'CoreTest', str(scratch)],
                       capture_output=True, text=True, timeout=900)
    (out / 'stderr.txt').write_text(r.stderr)
    body = r.stdout.rsplit('\n', 2)[0]
    result = json.loads(body)
    result['java'] = subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.splitlines()[0]
    result['gson'] = g.name
    if a.evidence:
        Path(a.evidence).write_text(json.dumps(result, indent=2) + '\n')
    for t in result['results']:
        print(('PASS ' if t['pass'] else 'FAIL ') + t['name'] + f" ({t['ms']} ms): " + str(t['detail'])[:160])
    print('CORE_TESTS_PASS' if r.returncode == 0 else 'CORE_TESTS_FAIL')
    sys.exit(r.returncode)


def company_test(a):
    out = ROOT / '.work/economy/company-test'
    scratch = out / 'scratch'
    if out.exists():
        shutil.rmtree(out)
    scratch.mkdir(parents=True)
    g = gson(a)
    cp = lambda d: os.pathsep.join([str(d), str(g)])
    # 1. the pre-company core, byte-for-byte from git, writes a legacy journal
    legacy_src = out / 'legacy-src'
    names = subprocess.run(['git', 'ls-tree', '--name-only', LEGACY_REF, 'extras/indigo-economy/src/online/indigomc/economy/core/'],
                           cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    if not names:
        sys.exit(f'no core sources at {LEGACY_REF} (fetch full history)')
    legacy_src.mkdir(parents=True)
    for n in names:
        (legacy_src / Path(n).name).write_bytes(subprocess.run(['git', 'show', f'{LEGACY_REF}:{n}'], cwd=ROOT, capture_output=True, check=True).stdout)
    compile_to(out / 'legacy-classes', sorted(legacy_src.glob('*.java')) + [SRC / 'test/LegacyJournalGen.java'], [g])
    legacy = scratch / 'legacy' / 'journal.jsonl'
    r = subprocess.run(['java', '-cp', cp(out / 'legacy-classes'), 'LegacyJournalGen', str(legacy)], capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        print(r.stdout + r.stderr)
        sys.exit('legacy journal generation failed')
    # 2. the company suite against the current core
    srcs = sorted((SRC / 'src/online/indigomc/economy/core').glob('*.java')) + [SRC / 'test/CompanyTest.java', SRC / 'test/LegacyJournalGen.java',
                                                                                 SRC / 'test/CompanyBridgeLab.java']
    compile_to(out / 'classes', srcs, [g])
    r = subprocess.run(['java', '-cp', cp(out / 'classes'), 'CompanyTest', str(scratch), str(legacy)], capture_output=True, text=True, timeout=1200)
    (out / 'stderr.txt').write_text(r.stderr)
    result = json.loads(r.stdout.rsplit('\n', 2)[0])
    # 3. the independent verifier on every journal the suite says must be valid (plus the untouched legacy journal)
    verifier = ROOT / 'scripts/economy-ledger-verify.py'
    journals = [legacy] + [Path(x) for x in (scratch / 'verify-list.txt').read_text().split('\n') if x]
    checks = []
    for j in journals:
        v = subprocess.run([sys.executable, str(verifier), str(j)], capture_output=True, text=True, timeout=300)
        report = json.loads(v.stdout.rsplit('\n', 2)[0])
        checks.append({'journal': j.relative_to(scratch).as_posix(), 'ok': v.returncode == 0, 'events': report.get('events'),
                       'companies': report.get('companies'), 'error': report.get('error')})
    result['independent_verifier'] = checks
    # 4. the verifier's company rules catch re-hashed (perfectly chained) rule-breaking journals
    st = subprocess.run([sys.executable, str(ROOT / 'scripts/economy-verify-selftest.py'), str(scratch), '--json', str(out / 'selftest.json')],
                        capture_output=True, text=True, timeout=300)
    result['verifier_selftest'] = json.loads((out / 'selftest.json').read_text()) if (out / 'selftest.json').exists() else st.stdout
    print(st.stdout.strip())
    # 5. rollback view: the OLD core (LEGACY_REF) opening copies of company journals
    rollback = []
    for name in ('restart', 'dissolution', 'shop-concurrency'):
        src = scratch / name / 'journal.jsonl'
        dst = out / 'rollback' / name / 'journal.jsonl'
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(src, dst)
        o = subprocess.run(['java', '-cp', cp(out / 'legacy-classes'), 'LegacyJournalGen', '--open', str(dst)], capture_output=True, text=True, timeout=120)
        line = [x for x in o.stdout.splitlines() if x.startswith('{')]
        rollback.append({'journal': name, 'opened_by_legacy_core': o.returncode == 0 and bool(line),
                         **(json.loads(line[-1]) if line else {'error': (o.stdout + o.stderr)[-400:]}),
                         'unchanged_by_open': src.read_bytes() == dst.read_bytes()})
    result['rollback_legacy_open'] = rollback
    for rb in rollback:
        print('ROLLBACK_VIEW ' + json.dumps(rb))
    # 6. optional: the Discord bot's /company handlers against the real Java bridge (needs tools/discord node_modules)
    e2e_ok = True
    if a.discord:
        env = dict(os.environ, INDIGO_COMPANY_LAB_CP=cp(out / 'classes'))
        npx = shutil.which('npx') or 'npx'
        e = subprocess.run([npx, 'tsx', '--test', 'test/company-bridge.e2e.test.ts'], cwd=ROOT / 'tools/discord', env=env,
                           capture_output=True, text=True, timeout=300)
        e2e_ok = e.returncode == 0 and '# skipped 0' in e.stdout
        result['discord_e2e'] = {'ok': e2e_ok, 'tail': e.stdout.strip().splitlines()[-9:]}
        print(('DISCORD_E2E_PASS' if e2e_ok else 'DISCORD_E2E_FAIL\n' + e.stdout + e.stderr))
    result['legacy_ref'] = LEGACY_REF
    result['java'] = subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.splitlines()[0]
    result['gson'] = g.name
    result['gson_sha256'] = hashlib.sha256(g.read_bytes()).hexdigest()
    if a.evidence:
        Path(a.evidence).write_text(json.dumps(result, indent=2) + '\n')
    for t in result['results']:
        print(('PASS ' if t['pass'] else 'FAIL ') + t['name'] + f" ({t['ms']} ms): " + str(t['detail'])[:200])
    for c in checks:
        print(('VERIFY_OK   ' if c['ok'] else 'VERIFY_FAIL ') + c['journal'] + f" events={c['events']} companies={c['companies']}"
              + (f" error={c['error']}" if c['error'] else ''))
    ok = r.returncode == 0 and all(c['ok'] for c in checks) and st.returncode == 0 and e2e_ok
    print('COMPANY_TESTS_PASS' if ok else 'COMPANY_TESTS_FAIL')
    sys.exit(0 if ok else 1)


def pin(a):
    """Records the freshly built (reproducible) jar's size and hashes in manifest/stack.json customMods."""
    mp = ROOT / 'manifest/stack.json'
    raw = mp.read_bytes().decode('utf-8-sig')
    crlf = chr(13) + chr(10) in raw
    stack = json.loads(raw)
    data = MOD_JAR.read_bytes()
    for m in stack.get('customMods', []):
        if m['filename'] == MOD_JAR.name:
            m.update(fileSize=len(data), sha1=hashlib.sha1(data).hexdigest(), sha256=hashlib.sha256(data).hexdigest(),
                     sha512=hashlib.sha512(data).hexdigest())
            break
    else:
        sys.exit(f'{MOD_JAR.name} is not in manifest customMods')
    text = json.dumps(stack, indent=2, ensure_ascii=False) + chr(10)
    if crlf:
        text = text.replace(chr(10), chr(13) + chr(10))
    mp.write_bytes(text.encode('utf-8'))
    print(f'pinned {MOD_JAR.name} sha256 {hashlib.sha256(data).hexdigest()}')


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    sub.add_parser('build')
    sub.add_parser('pin')
    s = sub.add_parser('core-test'); s.add_argument('--evidence'); s.add_argument('--gson')
    s = sub.add_parser('company-test'); s.add_argument('--evidence'); s.add_argument('--gson')
    s.add_argument('--discord', action='store_true', help='also run the bot /company handlers against the real bridge')
    a = p.parse_args()
    {'build': build, 'core-test': core_test, 'company-test': company_test, 'pin': pin}[a.cmd](a)


if __name__ == '__main__':
    main()
