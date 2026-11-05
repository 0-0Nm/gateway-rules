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
coordinates=json.loads((ROOT/'audit/security-phase1/dependencies.lock.json').read_text())
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
names=['ConfigStore','ConfigValidation','ConfigCommands','VirtualModelStore','GatewayRuntime','HttpUtil','BaseUrlProbe','BatchTester','ModelProbe','Contracts','RequestVisualState','RateLimit','RuleEngine','SafeExpr','SocketIo','SwitchPolicy','GateLog','JsonUtil','GatewayServer','UsageLedger','McpServer','VirtualModel','RequestTransform','ServerAccessPolicy','LocalAccessTokens']
sources=[g/(n+'.kt') for n in names]+[ROOT/'audit/config-phase2/BridgeContext.kt',ROOT/'audit/security-phase1/AuditPlatformStubs.kt',HERE/'FoundationAudit.kt',ROOT/'audit/config-phase2/BridgePlatform.kt',ROOT/'audit/config-phase2/UiStyleAdapter.kt']+[g.parent/'data'/n for n in ['ModelInfo.kt','ProviderDraft.kt','ProviderRepo.kt','VirtualModelDraft.kt','VirtualModelRepo.kt','AppSettings.kt','McpBridge.kt','BackgroundDebug.kt','BuildInfo.kt']]
sources += sorted((g.parent/'application').glob('*.kt')) + [ROOT/'audit/app-foundation-phase9-turn514/BuildConfigFixture.kt']
sources += [ROOT/'audit/usage-ledger-turn558/UsageAudit.kt']
expectedCases=json.loads((HERE/'cases.json').read_text())
summary={'languageVersion':'1.9','apiVersion':'1.9','scope':'Shared application workflows, editor-task ownership and MCP budget regressions; isolated loopback only; Android UI and true APK build identity verified separately',
 'sources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
jar=out/'foundation-tests.jar'
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','1.9','-api-version','1.9','-no-stdlib','-no-reflect','-classpath',cp,*map(str,sources),'-d',str(jar)],capture_output=True,text=True,timeout=180)
(out/'compile.txt').write_text(r.stdout+r.stderr);summary['compileExit']=r.returncode
print('COMPILE_EXIT',r.returncode,flush=True)
if r.returncode:
 print(r.stderr);(out/'results.json').write_text(json.dumps(summary,indent=2));raise SystemExit(r.returncode)
with tempfile.TemporaryDirectory(dir=out) as tmp:
 r=subprocess.run(['java','-cp',str(jar)+os.pathsep+cp,'com.aqi.ccb.gateway.FoundationAuditKt',tmp],capture_output=True,text=True,timeout=60)
(out/'results.txt').write_text(r.stdout+r.stderr);summary['testExit']=r.returncode
summary['passed']=len([x for x in r.stdout.splitlines() if x.startswith('PASS|')]);summary['failed']=len([x for x in r.stdout.splitlines() if x.startswith('FAIL|')])
observed=[l.split('|')[1] for l in r.stdout.splitlines() if l.startswith('PASS|')]
summary['expectedCases']=len(expectedCases)
summary['caseManifestMatched']=sorted(observed)==sorted(expectedCases) and len(observed)==len(set(observed))
summary['verdict']='PASS' if r.returncode==0 and summary['failed']==0 and summary['caseManifestMatched'] else 'FAIL'
(out/'results.json').write_text(json.dumps(summary,indent=2))
print(r.stdout+r.stderr);print('VERDICT',summary['verdict'])
raise SystemExit(0 if summary['verdict']=='PASS' else 1)
