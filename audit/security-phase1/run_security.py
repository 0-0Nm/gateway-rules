#!/usr/bin/env python3
"""Compile production transport code and run isolated security regressions. No real upstream calls."""
import argparse,hashlib,json,os,subprocess,tempfile,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
HERE=Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--fetch-deps',action='store_true')
parser.add_argument('--output',default=str(HERE/'run'))
args=parser.parse_args()
out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
# All versions pinned; repository checksums checked before use, artifacts cached outside source.
coordinates=json.loads((HERE/'dependencies.lock.json').read_text())
if args.fetch_deps:
 deps=Path(os.environ.get('AUDIT_DEP_CACHE',str(Path.home()/'.cache/gateway-security-deps')));deps.mkdir(parents=True,exist_ok=True)
 jars=[]
 for item in coordinates:
  name=item['name'];dest=deps/name;url=item['url'];checksum=item['sha256']
  if not dest.is_file() or hashlib.sha256(dest.read_bytes()).hexdigest()!=checksum:
   with urllib.request.urlopen(url,timeout=120) as r:data=r.read(100_000_000)
   if hashlib.sha256(data).hexdigest()!=checksum:raise RuntimeError('Dependency checksum mismatch: '+name)
   temp=dest.with_suffix('.tmp');temp.write_bytes(data);temp.replace(dest)
  jars.append(dest)
else:
 dirs=[Path(os.environ.get('AUDIT_KOTLIN_LIB','/root/toolbox/workspace/gw-rules/kotlin-lib')),
       Path(os.environ.get('AUDIT_SUPPORT_LIB','/storage/emulated/0/Download/GatewayRules-audit-20261102/toolchain')),
       ROOT/'audit/20261102/deps']
 jars=[p for d in dirs for p in d.glob('*.jar')]
cp=os.pathsep.join(map(str,jars));g=ROOT/'app/src/main/java/com/aqi/ccb/gateway'
names=['Contracts','RequestVisualState','RateLimit','RuleEngine','SafeExpr','SocketIo','SwitchPolicy','GateLog','JsonUtil','GatewayServer','UsageLedger','McpServer','VirtualModel','RequestTransform','ServerAccessPolicy','LocalAccessTokens']
sources=[g/(n+'.kt') for n in names]+[HERE/'TokenContext.kt',HERE/'AuditPlatformStubs.kt',HERE/'SecurityAudit.kt']
summary={'scope':'JVM security regression; Android lifecycle/UI requires Android build and separate acceptance',
 'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
jar=out/'security-tests.jar'
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',cp,*map(str,sources),'-d',str(jar)],capture_output=True,text=True,timeout=180)
(out/'compile.txt').write_text(r.stdout+r.stderr);summary['compileExit']=r.returncode
print('COMPILE_EXIT',r.returncode,flush=True)
if r.returncode:
 print(r.stderr);(out/'results.json').write_text(json.dumps(summary,indent=2));raise SystemExit(r.returncode)
with tempfile.TemporaryDirectory(dir=out) as tmp:
 r=subprocess.run(['java','-cp',str(jar)+os.pathsep+cp,'com.aqi.ccb.gateway.SecurityAuditKt',tmp],capture_output=True,text=True,timeout=60)
(out/'results.txt').write_text(r.stdout+r.stderr);summary['testExit']=r.returncode
summary['passed']=len([x for x in r.stdout.splitlines() if x.startswith('PASS|')]);summary['failed']=len([x for x in r.stdout.splitlines() if x.startswith('FAIL|')])
summary['verdict']='PASS' if r.returncode==0 and summary['passed']>=51 else 'FAIL'
(out/'results.json').write_text(json.dumps(summary,indent=2))
print(r.stdout+r.stderr);print('VERDICT',summary['verdict'])
raise SystemExit(0 if summary['verdict']=='PASS' else 1)
