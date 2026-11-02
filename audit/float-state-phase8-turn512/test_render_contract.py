#!/usr/bin/env python3
"""Source-contract checks only, not an Android View/Handler execution or visual acceptance."""
from pathlib import Path
import importlib.util,unittest,re,hashlib,json
ROOT=Path(__file__).resolve().parents[2];HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('lint',ROOT/'tools/check_kt.py');lint=importlib.util.module_from_spec(spec);spec.loader.exec_module(lint)
FLOAT=ROOT/'app/src/main/java/com/aqi/ccb/gateway/FloatingBall.kt'
src=FLOAT.read_text();clean=lint.strip_comments_and_strings(src)
baseline=json.loads((HERE/'gesture-baseline.json').read_text())
def body(name,text=clean):
 m=re.search(r'\bfun\s+'+re.escape(name)+r'\s*\(',text);assert m,name
 start=text.index('{',m.end());depth=0
 for i in range(start,len(text)):
  if text[i]=='{':depth+=1
  elif text[i]=='}':
   depth-=1
   if depth==0:return text[start+1:i]
 raise AssertionError('unbalanced '+name)
class RenderContract(unittest.TestCase):
 def test_locked_mode_does_not_freeze_main_or_satellite_color(self):self.assertNotIn('locked',body('renderFromProviders'))
 def test_old_set_state_only_updates_fallback_and_requests_real_refresh(self):
  b=body('setState');self.assertIn('currentState = state',b);self.assertIn('refreshFromProviders()',b);self.assertNotIn('setTextColor',b)
 def test_mcp_refresh_uses_same_provider_pipeline(self):self.assertIn('refreshFromProviders()',body('setMcpAlive'));self.assertNotIn('setTextColor',body('setMcpAlive'))
 def test_main_handler_is_bound_to_main_looper(self):self.assertIn('Handler(Looper.getMainLooper())',clean)
 def test_refresh_is_coalesced(self):self.assertIn('refreshQueued.compareAndSet(false, true)',body('refreshFromProviders'));self.assertIn('main.post(providerRefreshRunnable)',body('refreshFromProviders'))
 def test_real_expiry_scheduled_only_for_attached_main_view(self):
  b=body('renderFromProviders');self.assertIn('nextTransitionMs',b);self.assertIn('mainView != null && delay > 0',b);self.assertIn('main.postDelayed(visualExpiryRunnable',b)
 def test_detach_cancels_both_named_render_callbacks(self):
  b=body('detach');self.assertIn('main.removeCallbacks(providerRefreshRunnable)',b);self.assertIn('main.removeCallbacks(visualExpiryRunnable)',b);self.assertIn('refreshQueued.set(false)',b)
 def test_attach_and_unlock_pull_current_state(self):
  self.assertIn('refreshFromProviders()',body('attach'));self.assertIn('refreshFromProviders()',body('performUnlock'));self.assertNotIn('colorFor(currentState)',body('performUnlock'))
 def test_generation_color_source_not_connection_or_failure_window(self):
  b=body('gatewayState');self.assertIn('requestVisualStatePublic().phase',b);self.assertNotIn('inflightPublic',b);self.assertNotIn('recentFailure',b)
 def test_existing_touch_gestures_unchanged(self):
  self.assertEqual(hashlib.sha256(body('makeTouchListener').encode()).hexdigest(),baseline['makeTouchListenerCleanBodySha256'])
  for name,value in baseline['gestureConstants'].items():self.assertEqual(re.search(r'private const val '+name+r'\s*=\s*([^\n]+)',clean).group(1).strip(),value)
 def test_service_injects_refresh_without_starting_activity(self):
  s=(ROOT/'app/src/main/java/com/aqi/ccb/gateway/GatewayService.kt').read_text();self.assertIn('onVisualStateChanged = { FloatingBall.refreshFromProviders() }',s)
 def test_settings_color_legend_matches_four_states(self):self.assertIn('待命蓝/生成橙/完成绿/中断红',(ROOT/'app/src/main/java/com/aqi/ccb/ui/SettingsScreen.kt').read_text())
if __name__=='__main__':unittest.main(verbosity=2)
