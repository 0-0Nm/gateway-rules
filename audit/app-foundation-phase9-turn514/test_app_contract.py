#!/usr/bin/env python3
"""Source wiring regression only: no Android UI execution or device claims."""
from pathlib import Path
import importlib.util,re,unittest
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('lint',ROOT/'tools/check_kt.py');lint=importlib.util.module_from_spec(spec);spec.loader.exec_module(lint)
ACT=(ROOT/'app/src/main/java/com/aqi/ccb/MainActivity.kt').read_text();CLEAN=lint.strip_comments_and_strings(ACT)
def body(name):
 m=re.search(r'\bfun\s+'+re.escape(name)+r'\s*\(',CLEAN);assert m,name
 start=CLEAN.index('{',m.end());depth=0
 for i in range(start,len(CLEAN)):
  if CLEAN[i]=='{':depth+=1
  elif CLEAN[i]=='}':
   depth-=1
   if depth==0:return CLEAN[start+1:i]
 raise AssertionError('unbalanced')
class AppContract(unittest.TestCase):
 def test_settings_no_longer_contains_MCP_or_diagnostic_implementation(self):
  text=(ROOT/'app/src/main/java/com/aqi/ccb/data/AppSettings.kt').read_text();self.assertNotIn('class McpBridge',text);self.assertNotIn('object BackgroundDebug',text)
 def test_FQCN_compatibility_preserved_for_bridge_and_diagnostics(self):
  for name,decl in [('McpBridge.kt','class McpBridge'),('BackgroundDebug.kt','internal object BackgroundDebug')]:
   s=(ROOT/'app/src/main/java/com/aqi/ccb/data'/name).read_text();self.assertIn('package com.aqi.ccb.data',s);self.assertIn(decl,s)
 def test_discovery_UI_and_MCP_share_same_workflow(self):
  self.assertIn('ProviderWorkflows.discover',body('probeModels'));self.assertIn('ProviderWorkflows.discover',(ROOT/'app/src/main/java/com/aqi/ccb/data/McpBridge.kt').read_text())
 def test_connection_changes_invalidate_task(self):
  b=body('updateProviderDraft');self.assertIn('cancelProviderWork()',b)
  for f in ['baseUrl','apiKey','suffixStyle','configRevision','idOr()']:self.assertIn(f,b)
 def test_destroy_cancels_before_clearing_callbacks(self):
  b=body('onDestroy');self.assertLess(b.index('cancelProviderWork()'),b.index('ui.removeCallbacksAndMessages'))
 def test_switch_back_new_editor_and_save_delete_have_cancel_hooks(self):
  for f in ['openProvider','saveProvider','deleteProvider']:self.assertIn('cancelProviderWork()',body(f))
  self.assertIn('onBack = { cancelProviderWork(); route = "main"',ACT)
  self.assertRegex(ACT,r'onAddProvider = \{\s*cancelProviderWork\(\)')
 def test_every_network_editor_path_checks_ownership_before_UI_apply(self):
  for f in ['probeModels','testProvider','testAllModels']:self.assertIn('ownsProviderWork(ticket)',body(f))
 def test_batch_result_merges_current_fields_not_stale_model_object(self):self.assertIn('ProviderWorkflows.mergeTestFields(current,tested)',ACT)
 def test_success_does_not_enable_models(self):self.assertNotIn('enabled = if (res.nonStreaming)',ACT);self.assertIn('ProviderWorkflows.applyCapabilityResult',body('testProvider'))
 def test_UI_save_rule_uses_existing_shared_command(self):
  b=body('saveRule');self.assertIn('ConfigCommands.saveRule',b);self.assertNotIn('ConfigStore.transact',b);self.assertIn('expectedRevision',ACT)
 def test_billable_buttons_confirm_and_cancel_available(self):
  s=(ROOT/'app/src/main/java/com/aqi/ccb/ui/ProviderEditScreen.kt').read_text();self.assertIn('pendingTest',s);self.assertIn('AlertDialog',s);self.assertIn('onCancelTest?.invoke()',s);self.assertIn('draft.models.any{it.enabled}',s)
 def test_MCP_billable_batch_requires_true_confirmation(self):
  s=(ROOT/'app/src/main/java/com/aqi/ccb/gateway/McpServer.kt').read_text();self.assertIn('args.opt("confirm")==true',s);self.assertIn('JSONArray().put("providerId").put("confirm")',s)
 def test_preferences_protocol_and_build_identity_are_separate(self):
  b=(ROOT/'app/src/main/java/com/aqi/ccb/data/McpBridge.kt').read_text();self.assertIn('.put("build",BuildInfo.json())',b);self.assertIn('"persistedRevision"',b)
 def test_CI_passes_allocated_identity_and_verifies_apk_not_just_file_name(self):
  s=(ROOT/'.github/workflows/build.yml').read_text();self.assertIn('build_identity.py allocate',s);self.assertIn('-PgatewayBuildIdentity=',s);self.assertIn('build_identity.py verify-apk',s)
 def test_Gradle_requires_CI_identity_and_embeds_asset(self):
  s=(ROOT/'app/build.gradle.kts').read_text();self.assertIn('GITHUB_ACTIONS',s);self.assertIn('gatewayBuildIdentity',s);self.assertIn('build-identity.json',s);self.assertIn('buildConfig = true',s)
 def test_UI_configuration_feedback_preserves_commit_application_distinction(self):
  self.assertIn('commit.applied',body('showCommit'));self.assertIn('commit.applyError',body('showCommit'))
  for f in ['saveProvider','deleteProvider','saveRule','saveVirtualModel','deleteVirtualModel','deleteRule']:self.assertIn('showCommit(',body(f))
 def test_MCP_contract_change_bumps_server_implementation_version(self):
  s=(ROOT/'app/src/main/java/com/aqi/ccb/gateway/McpServer.kt').read_text();self.assertIn('put("version", "0.5.0")',s)
if __name__=='__main__':unittest.main(verbosity=2)
