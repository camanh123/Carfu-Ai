# CARFU resolver HTTPS deploy adapter (Phase 4.9.2b)

Public HTTPS front for the frozen `:carfu-resolver-service` contract.

The Kotlin ranking, cache, and REST sources under `src/main` are **not**
modified. This Worker is a deploy-time adapter so CARFU can reach the same
`GET /health`, `GET /v1/diagnostics`, and `GET /v1/youtube/resolve` contract
over the public Internet.

- Official YouTube Data API v3 `search.list` only
- `YOUTUBE_API_KEY` is a Worker secret, never sent to Android
- In-memory cache with the same TTL constants as the JVM service
