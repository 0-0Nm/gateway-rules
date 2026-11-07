#!/usr/bin/env python3
"""Real JVM tests of production export logic; source UI contracts, not an Android UI test."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,tempfile,urllib.request,re
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
ap=argparse.ArgumentParser();ap.add_argument('--fetch-deps',action='store_true');ap.add_argument('--negative-control',action='store_true');ap.add_argument('--output',default=str(HERE/'run'));args=ap.parse_args()
out=Path(args.output).resolve();out.mkdir(parents=True,exist_ok=True)
lock=json.loads((ROOT/'audit/security-phase1/dependencies.lock.json').read_text());cache=Path(os.environ.get('AUDIT_DEP_CACHE',str(Path.home()/'.cache/gateway-security-deps')))
jars=[]
for row in lock:
 p=cache/row['name']
 if not p.exists() or hashlib.sha256(p.read_bytes()).hexdigest()!=row['sha256']:
  if not args.fetch_deps:raise RuntimeError('Missing verified dependency: '+row['name'])
  cache.mkdir(parents=True,exist_ok=True)
  with urllib.request.urlopen(row['url'],timeout=60) as r:b=r.read(100_000_000)
  assert hashlib.sha256(b).hexdigest()==row['sha256'];t=p.with_suffix('.tmp');t.write_bytes(b);t.replace(p)
 jars.append(p)
settings=ROOT/'app/src/main/java/com/aqi/ccb/ui/SettingsScreen.kt';act=ROOT/'app/src/main/java/com/aqi/ccb/MainActivity.kt';app=ROOT/'app/src/main/java/com/aqi/ccb/data/AppSettings.kt';workflow=ROOT/'.github/workflows/build.yml'
s=settings.read_text();a=act.read_text()
checks={
 'naming_button_before_json_copy':s.index('Text("MCP 命名")')<s.index('Text("复制配置 JSON")'),
 'dialog_validates_and_disables_invalid_save':'McpClientConfig.nameError(mcpNameDraft)' in s and 'enabled = validationError == null && onMcpNameSave != null' in s,
 'draft_saved_across_rotation':'rememberSaveable' in s and 'mcpNameDraft = state.mcpClientName' in s,
 'cancel_only_closes_dialog':'dismissButton = { TextButton(onClick = { showMcpName = false })' in s,
 'uses_selected_name_in_export':'McpClientConfig.json(\n                                settingsState.mcpClientName, actualPort,' in a and 'put("gateway-rules",' not in a,
 'name_loaded_from_preferences':'mcpClientName = AppSettings.mcpClientName(this)' in a,
 'save_callback_updates_ui_after_commit':'AppSettings.setMcpClientName(this, name)\n                                settingsState = settingsState.copy(mcpClientName = name)' in a,
 'sensitive_clipboard_preserved':'android.content.extra.IS_SENSITIVE' in a,
 'name_save_does_not_restart_listener':not any(x in a.split('onMcpNameSave = { name ->',1)[1].split('onMcpCopyRaw = {',1)[0] for x in ['setMcpPort(', 'GatewayService.', 'McpService.']),
 'real_ui_build_still_required':'gradle :app:signingReport assembleDebug' in workflow.read_text(),
}
assert all(checks.values()),[k for k,v in checks.items() if not v]
source=app
if args.negative_control:
 text=app.read_text();assert text.count('.put(checkedName,')==1
 source=out/'AppSettings.ignored-name.kt';source.write_text(text.replace('.put(checkedName,','.put(DEFAULT_NAME,'))
sources=[source,ROOT/'audit/config-phase2/BridgeContext.kt',HERE/'McpNameAudit.kt']
summary={'negativeControl':args.negative_control,'scope':'production export and preference logic, isolated JVM preferences; UI source wiring only',
 'sources':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'wiringSources':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [settings,act,workflow]},'sourceContracts':checks}
cp=os.pathsep.join(map(str,jars));jar=out/'name-tests.jar'
r=subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-language-version','1.9','-api-version','1.9','-no-stdlib','-no-reflect','-classpath',cp,*map(str,sources),'-d',str(jar)],capture_output=True,text=True,timeout=120)
(out/'compile.txt').write_text(r.stdout+r.stderr);summary['compileExit']=r.returncode
if r.returncode:
 (out/'results.json').write_text(json.dumps(summary,indent=2)+'\n');print(r.stdout+r.stderr);raise SystemExit(r.returncode)
with tempfile.TemporaryDirectory(dir=out) as tmp:r=subprocess.run(['java','-cp',str(jar)+os.pathsep+cp,'com.aqi.ccb.data.McpNameAuditKt',tmp],capture_output=True,text=True,timeout=30)
(out/'results.txt').write_text(r.stdout+r.stderr);summary['testExit']=r.returncode
summary['passed']=sum(x.startswith('PASS|') for x in r.stdout.splitlines());summary['failed']=sum(x.startswith('FAIL|') for x in r.stdout.splitlines())
summary['verdict']='PASS' if r.returncode==0 and summary['passed']==21 and summary['failed']==0 else 'FAIL'
if args.negative_control:
 summary['negativeControlEffective']=r.returncode!=0 and 'FAIL|custom_name_is_the_only_json_server_key|' in r.stdout and 'FAIL|saved_alias_is_used_in_export|' in r.stdout
else:summary['negativeControlEffective']=None
(out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print('COMPILE_EXIT',summary['compileExit']);print(r.stdout+r.stderr);print('SOURCE_CONTRACTS',len(checks),'VERDICT',summary['verdict'])
raise SystemExit(0 if (summary['negativeControlEffective'] if args.negative_control else summary['verdict']=='PASS') else 1)
