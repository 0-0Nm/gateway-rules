#!/usr/bin/env python3
"""Read-only business-source regression runner. Never contacts real upstreams."""
import argparse, hashlib, json, os, re, subprocess, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent
parser=argparse.ArgumentParser()
parser.add_argument('--group',choices=['all','core','integration','probe'],default='all')
parser.add_argument('--output',default=str(HERE))
opts=parser.parse_args()
OUT=Path(opts.output).resolve();OUT.mkdir(parents=True,exist_ok=True)
compiler=Path(os.environ.get('AUDIT_KOTLIN_LIB','/root/toolbox/workspace/gw-rules/kotlin-lib'))
support=Path(os.environ.get('AUDIT_SUPPORT_LIB','/storage/emulated/0/Download/GatewayRules-audit-20261102/toolchain'))
cp=':'.join(str(p) for base in [compiler,support,ROOT/'audit/20261102/deps'] for p in sorted(base.glob('*.jar')))
g=ROOT/'app/src/main/java/com/aqi/ccb/gateway';d=g.parent/'data'
core=['Contracts','RequestVisualState','RateLimit','RuleEngine','SafeExpr','SocketIo','SwitchPolicy','GateLog','JsonUtil']
more=['GatewayServer','UsageLedger','McpServer','VirtualModel','RequestTransform','ConfigStore','VirtualModelStore','ConfigValidation','ConfigCommands','ServerAccessPolicy']
summary={'scope':'phase4 production source; original audit assertions byte-identical; explicit constructor tokens inherited; JVM subset',
 'baselineRemote':'ab79dfc208ac01b206f2f081b2c4359caecf9ee3',
 'compiler':'Kotlin 2.0.21 compiler, language/API 1.9; project Android build uses Kotlin 1.9.24',
 'groups':[],'sourceSha256':{}}
for group,entry in [('core','CoreAudit'),('integration','IntegrationAudit'),('probe','ProbeAudit')]:
 if opts.group not in ('all',group):continue
 src=[g/(n+'.kt') for n in core]
 if group!='core':src += [g/(n+'.kt') for n in more]+[d/(n+'.kt') for n in ['ProviderDraft','ProviderRepo','ModelInfo']]+[HERE/'AuditAndroidContext.kt',HERE/'AuditPlatformStubs.kt']
 if group=='probe':src += [g/'HttpUtil.kt',g/'ModelProbe.kt']
 src += [HERE/(entry+'.kt')]
 for f in src:
  summary['sourceSha256'][str(f.relative_to(ROOT))]=hashlib.sha256(f.read_bytes()).hexdigest()
 jar=OUT/(group+'-audit.jar')
 compileResult=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','1.9','-api-version','1.9','-no-stdlib','-no-reflect','-classpath',cp,*map(str,src),'-d',str(jar)],capture_output=True,text=True,timeout=90)
 (OUT/(group+'-compile.txt')).write_text(compileResult.stdout+compileResult.stderr)
 record={'name':group,'compileExit':compileResult.returncode};summary['groups'].append(record)
 print('GROUP',group,'COMPILE_EXIT',compileResult.returncode,flush=True)
 if compileResult.returncode:
  print(compileResult.stderr[-3000:]);continue
 with tempfile.TemporaryDirectory(prefix='data-',dir=OUT) as tmp:
  cmd=['java','--add-modules','jdk.httpserver','-cp',str(jar)+':'+cp,'com.aqi.ccb.gateway.'+entry+'Kt']
  if group=='integration':cmd.append(tmp)
  r=subprocess.run(cmd,capture_output=True,text=True,timeout=40)
 (OUT/(group+'-results.txt')).write_text(r.stdout+r.stderr)
 record['testExit']=r.returncode
 for line in r.stdout.splitlines():
  if line.startswith(('PASS|','FAIL|','DETAIL|','SUMMARY|')):print(line,flush=True)
 match=re.search(r'SUMMARY\|cases=(\d+)\|passed=(\d+)\|failed=(\d+)',r.stdout)
 if match:record.update(zip(['cases','passed','failed'],map(int,match.groups())))
 record['failingCases']=[l.split('|')[1] for l in r.stdout.splitlines() if l.startswith('FAIL|')]
summary['totals']={k:sum(g.get(k,0) for g in summary['groups']) for k in ['cases','passed','failed']}
summary['verdict']='NO_GO' if any(g.get('testExit',1)!=0 for g in summary['groups']) else 'PASS'
path=OUT/('results.json' if opts.group=='all' else opts.group+'-results.json')
path.write_text(json.dumps(summary,ensure_ascii=False,indent=2))
print('RESULT',path,'TOTAL',summary['totals'],'VERDICT',summary['verdict'])
raise SystemExit(1 if summary['verdict']=='NO_GO' else 0)
