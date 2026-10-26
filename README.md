# gateway-rules

Local OpenAI-compatible API gateway for Android.

## Features
- multi-provider import (baseUrl + apiKey), auto model discovery
- declarative rule engine: select (pure ops) + execute (one op)
- execute ops: race / hedge / first / shadow / retry_backoff / circuit_breaker / quorum / escalate
- select ops: require / breaker_filter / quota_filter / tier_first / fastest_first / round_robin / sticky_session / sticky_prefix / ab_split / canary / error_rate / budget_cap / least_errors / lru / weighted_rr / random / take / where
- rate limiting to avoid upstream anti-fraud triggers (ratePerMin / burst / minIntervalMs / jitterMs / maxInflight)
- dual-track logging: call log (JSONL, per request) + system log

## Build
GitHub Actions -> artifact `app-debug`.
