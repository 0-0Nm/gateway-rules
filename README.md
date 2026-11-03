# gateway-rules

Local OpenAI-compatible API gateway for Android.

## Features
- multi-provider import (baseUrl + apiKey), auto model discovery
- declarative rule engine: select (pure ops) + execute (one op)
- execute ops: `first` only. `race`, `hedge`, `shadow`, `retry_backoff`, `quorum` and `escalate` are NOT implemented; invalid execution operators are rejected, never silently downgraded.
- select ops: require / breaker_filter / quota_filter / tier_first / fastest_first / round_robin / sticky_session / sticky_prefix / ab_split / canary / error_rate / budget_cap / least_errors / lru / weighted_rr / random / take / where
- rate limiting to avoid upstream anti-fraud triggers (ratePerMin / burst / minIntervalMs / jitterMs / maxInflight)
- dual-track logging: call log (JSONL, per request) + system log

## Build
GitHub Actions -> artifact `app-debug`.

## Development version and shared application workflows
- Project four-part version: `主.次.本.末` = user-controlled major / functional iteration / user validation round / project CI build sequence. This functional iteration preserves major0 and starts `0.3.0.N` from the existing0.2 baseline.
- `version-policy.json` is project-specific. Official main builds allocate display `N=GITHUB_RUN_NUMBER-buildSequenceOrigin` and independent `versionCode=1000000+GITHUB_RUN_NUMBER`; no global toolbox counter or decimal four-field packing is used.
- Workflow re-runs cannot reuse the same APK identity: use a fresh workflow_dispatch after failure, not Re-run old jobs. Older checkout not equal to currentmain is refused. Counter lineage changes require explicit review, not automatic reset. Local unallocated builds are marked `local-unallocated`, not claimed as official artifacts.
- CI verifies app Manifest AND embedded `assets/build-identity.json`; UI settings and MCPstatus expose the immutable build identity, separate from protocol/config versions. Fixeddebug certificate is unchanged; release signing remains separate.
- UI and MCP share provider discovery/merge semantics. New discovered models remain disabled; test success does not auto-enable. Test result merges preserve current user name/prices/selection/modalities.
- Explicitly selected models only, max20 per batch. UI asks before potentially billable generation; MCP `gw_test_models` now also requires `confirm=true` (same tool name; refresh tools/list). HTTP directory success is not proof of chat/tool ability. Cancellation is cooperative and does not revoke already dispatched upstream billing.
- Provider editor owns generation-scoped task tickets. Switching provider/editor, changing URL/key/suffix/config revision, new operation, back/save/delete or Activity destroy invalidates old results. Runtime request executor, config format, package and floating status contracts are not replaced.

On functional/validation-round advancement, reset the display-build origin explicitly while preserving the project CI lineage and increasing Android code. For this0.3.0 batch, origin250 was read from the last successful project run; next first-attempt CI251 displays0.3.0.1, not0.3.0.251. Never increment a semantic higher field just to hide numeric overflow.

MCP server implementation0.5.0 makes the selected-model batch confirmation requirement explicit; protocol2024-11-05 and application four-part version are separate. Reconnect/refresh tools/list after updating the app. UI configuration feedback uses the same typed Commit persisted/revision/applied distinction as MCP.
