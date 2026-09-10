# CARFU YouTube Resolver Service (Phase 4.9.1)

Standalone metadata resolver. It is **not** wired into the Android app,
Phase 4.9 launcher, PlayAuto Core, voice, or NLU.

```
query → YouTube Data API v3 search.list → rank → {videoId, title, watchUrl}
```

This service is metadata only. It has no Cast, Chromecast, MediaRouter,
RemotePlayback, Accessibility, media keys, or Android intents.

## Official quota model (verified 2026-09-04 UTC)

Do not treat older “10,000 units includes search at 100 units/call” notes as current.

| Item | Current official model |
| --- | --- |
| Default daily allocation | **100 `search.list` calls**, **100 `videos.insert` calls**, and **10,000 units** for all other endpoints |
| `search.list` cost | **1 quota unit per call**, in its **own Search Queries bucket** (default **100 calls/day**) |
| `videos.list` cost | **1 unit**, from the **10,000/day combined** pool |
| Extra `search.list` pages | Each page is another call / another unit in the search bucket |
| Separate search cap | **Yes** — `search.list` is not paid from the 10,000 combined pool |
| Daily reset | Midnight Pacific Time (PT) |
| Quota extension | Compliance audit + [Audit and Quota Extension Form](https://developers.google.com/youtube/v3/guides/quota_and_compliance_audits). Not a paid add-on. |
| Billing | API usage is **quota-limited and not a billed per-search product**. Google Cloud project billing is not required to use the default allocation. |

Official sources (all last updated **2026-09-04 UTC** unless noted):

- https://developers.google.com/youtube/v3/determine_quota_cost
- https://developers.google.com/youtube/v3/docs/search/list
- https://developers.google.com/youtube/v3/docs/videos/list
- https://developers.google.com/youtube/v3/getting-started
- https://developers.google.com/youtube/v3/guides/quota_and_compliance_audits

This service therefore uses **one `search.list` call per cache miss**
(`type=video`, `maxResults=8`) and does **not** call `videos.list` by default.

## Run locally

```bash
cd /path/to/repo
export YOUTUBE_API_KEY=your_key_here   # optional; service still starts without it
export PORT=8787
./gradlew :carfu-resolver-service:run
```

Or copy `.env.example` to `.env` in this directory and export variables yourself.
The process reads **environment variables only**. It never prints the key.

```bash
curl "http://localhost:8787/health"
curl "http://localhost:8787/v1/diagnostics"
curl "http://localhost:8787/v1/youtube/resolve?q=%C4%90%E1%BB%ABng%20Xa%20Em%20%C4%90%C3%AAm%20Nay&lang=vi&region=VN"
```

Without `YOUTUBE_API_KEY`, resolve returns `RESOLVER_UNAVAILABLE` with
`error=MISSING_API_KEY`. Unit tests never call Google.

## Secret storage

| Environment | How to supply the key |
| --- | --- |
| Local | `export YOUTUBE_API_KEY=...` or a gitignored `.env` that you export |
| systemd | `EnvironmentFile=` pointing at a root-only file, or `Environment=YOUTUBE_API_KEY=` |
| GitHub Actions | repository/environment secret `YOUTUBE_API_KEY` |
| Cloud Run / GCE | Secret Manager → mount or inject as env `YOUTUBE_API_KEY` |

Never put a real key in source, Gradle files, or logs.

## Tests

```bash
./gradlew :carfu-resolver-service:test
```

## API contract

`GET /v1/youtube/resolve?q=<query>&lang=vi&region=VN`

See `src/main/kotlin/org/stypox/dicio/resolver/api/ResolveResponse.kt`.
