#!/usr/bin/env python3
import copy,importlib.util,json,tempfile,unittest,zipfile,subprocess
from pathlib import Path
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('identity',ROOT/'tools/build_identity.py');v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
class BuildIdentityTests(unittest.TestCase):
 def setUp(self):
  self.p=v.policy_read(ROOT/'version-policy.json');self.sha='a'*40
  self.e={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'0-0Nm/gateway-rules','GITHUB_REF':'refs/heads/main','GITHUB_WORKFLOW_REF':'0-0Nm/gateway-rules/.github/workflows/build.yml@refs/heads/main','GITHUB_RUN_NUMBER':'251','GITHUB_RUN_ID':'41400000000','GITHUB_RUN_ATTEMPT':'1','GITHUB_SHA':self.sha}
 def allocate(self):return v.allocate(self.p,self.e,self.sha,self.sha)
 def rejected(self,code):
  with self.assertRaisesRegex(v.IdentityError,code):self.allocate()
 def test_preserves_four_semantic_fields(self):self.assertEqual(self.allocate()['versionName'],'0.3.0.1')
 def test_android_code_independent_and_above_current(self):self.assertEqual(self.allocate()['versionCode'],1000251)
 def test_distinct_builds_distinct_identity_and_monotonic_codes(self):
  a=self.allocate();self.e['GITHUB_RUN_NUMBER']='252';b=self.allocate();self.assertNotEqual(a['versionName'],b['versionName']);self.assertLess(a['versionCode'],b['versionCode'])
 def test_more_than99_builds_no_packing_collision(self):
  self.e['GITHUB_RUN_NUMBER']='350';a=self.allocate();self.p['validationRound']=1;self.p['buildSequenceOrigin']=350;self.e['GITHUB_RUN_NUMBER']='351';b=self.allocate();self.assertNotEqual(a['versionCode'],b['versionCode'])
 def test_user_validation_round_changes_display_not_counter_lineage(self):self.p['validationRound']=7;self.assertEqual(self.allocate()['versionName'],'0.3.7.1')
 def test_no_automatic_major_bump(self):self.assertEqual(self.allocate()['major'],0)
 def test_rerun_fails_before_identity_reuse(self):self.e['GITHUB_RUN_ATTEMPT']='2';self.rejected('NEW_WORKFLOW_DISPATCH')
 def test_non_actions_context_rejected(self):self.e['GITHUB_ACTIONS']='false';self.rejected('GITHUB_ACTIONS')
 def test_other_repo_rejected(self):self.e['GITHUB_REPOSITORY']='other/repo';self.rejected('WRONG_REPOSITORY')
 def test_non_main_ref_rejected(self):self.e['GITHUB_REF']='refs/heads/test';self.rejected('MAIN')
 def test_changed_workflow_lineage_rejected(self):self.e['GITHUB_WORKFLOW_REF']='0-0Nm/gateway-rules/.github/workflows/other.yml@refs/heads/main';self.rejected('LINEAGE')
 def test_invalid_sha_rejected(self):self.e['GITHUB_SHA']='not-sha';self.rejected('COMMIT')
 def test_checkout_mismatch_rejected(self):
  with self.assertRaisesRegex(v.IdentityError,'CHECKOUT'):v.allocate(self.p,self.e,'b'*40,self.sha)
 def test_stale_main_rejected(self):
  with self.assertRaisesRegex(v.IdentityError,'STALE'):v.allocate(self.p,self.e,self.sha,'b'*40)
 def test_negative_counter_rejected(self):self.e['GITHUB_RUN_NUMBER']='-1';self.rejected('SEQUENCE')
 def test_zero_counter_rejected(self):self.e['GITHUB_RUN_NUMBER']='0';self.rejected('SEQUENCE')
 def test_decimal_counter_rejected(self):self.e['GITHUB_RUN_NUMBER']='1.5';self.rejected('SEQUENCE')
 def test_code_limit_enforced(self):self.e['GITHUB_RUN_NUMBER']='2100000000';self.rejected('VERSION_CODE')
 def test_boolean_policy_number_rejected(self):self.p['major']=True;self.rejected('MAJOR')
 def test_negative_policy_round_rejected(self):self.p['validationRound']=-1;self.rejected('VALIDATIONROUND')
 def test_identity_json_idempotent_for_same_build(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'identity.json';a=self.allocate();v.publish_new_json(p,a);before=p.read_bytes();v.publish_new_json(p,a);self.assertEqual(p.read_bytes(),before)
 def test_identity_existing_different_never_overwritten(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'identity.json';a=self.allocate();v.publish_new_json(p,a);before=p.read_bytes()
   with self.assertRaisesRegex(v.IdentityError,'DIFFERENT'):v.publish_new_json(p,dict(a,versionCode=2))
   self.assertEqual(p.read_bytes(),before)
 def make_apk(self,p,identity):
  with zipfile.ZipFile(p,'w') as z:z.writestr('assets/build-identity.json',json.dumps(identity))
 def aapt(self,identity,package='com.aqi.ccb'):return subprocess.CompletedProcess([],0,"package: name='"+package+"' versionCode='"+str(identity['versionCode'])+"' versionName='"+identity['versionName']+"'\n",'')
 def test_manifest_and_asset_must_match(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'app.apk';a=self.allocate();self.make_apk(p,a)
   with patch.object(v.subprocess,'run',return_value=self.aapt(a)):self.assertTrue(v.verify_apk(p,a,'fake-aapt')['assetAndManifestMatched'])
 def test_wrong_asset_commit_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'app.apk';a=self.allocate();self.make_apk(p,dict(a,sourceCommit='b'*40))
   with self.assertRaisesRegex(v.IdentityError,'ASSET_MISMATCH'):v.verify_apk(p,a,'fake')
 def test_manifest_wrong_version_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'app.apk';a=self.allocate();self.make_apk(p,a)
   with patch.object(v.subprocess,'run',return_value=self.aapt(dict(a,versionCode=1))):
    with self.assertRaisesRegex(v.IdentityError,'MANIFEST'):v.verify_apk(p,a,'fake')
 def test_manifest_wrong_package_rejected(self):
  with tempfile.TemporaryDirectory() as t:
   p=Path(t)/'app.apk';a=self.allocate();self.make_apk(p,a)
   with patch.object(v.subprocess,'run',return_value=self.aapt(a,'another.app')):
    with self.assertRaisesRegex(v.IdentityError,'MANIFEST'):v.verify_apk(p,a,'fake')
 def test_mcp_protocol_and_config_revisions_not_used_as_app_version(self):
  a=self.allocate();self.assertNotIn('configRevision',a);self.assertNotEqual(a['versionName'],'0.4.0')
 def test_secrets_not_in_identity(self):self.assertNotIn('apiKey',str(self.allocate()));self.assertNotIn('GITHUB_TOKEN',str(self.allocate()))
 def test_higher_field_bump_can_reset_display_build_without_code_rollback(self):
  a=self.allocate();self.p['validationRound']=1;self.p['buildSequenceOrigin']=251;self.e['GITHUB_RUN_NUMBER']='252';b=self.allocate()
  self.assertEqual(b['versionName'],'0.3.1.1');self.assertGreater(b['versionCode'],a['versionCode'])
 def test_origin_cannot_reuse_or_preallocate_same_display_identity(self):
  self.p['buildSequenceOrigin']=251;self.rejected('ORIGIN')
 def test_code_epoch_cannot_silently_reset(self):
  self.p['versionCodeOffset']=100;self.rejected('EPOCH_MIGRATION')
 def test_CI_read_token_is_ephemeral_not_source_credential(self):
  text=(ROOT/'.github/workflows/build.yml').read_text();self.assertIn('GITHUB_TOKEN: ${{ github.token }}',text)
if __name__=='__main__':unittest.main(verbosity=2)
