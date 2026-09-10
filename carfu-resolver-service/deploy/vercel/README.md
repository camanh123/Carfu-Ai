# Vercel HTTPS adapter

Serverless front for the frozen CARFU resolver contract. Ranking Kotlin
sources are not modified. `YOUTUBE_API_KEY` is a Vercel env var.

Production origin (SSO off, survives agent restart):

`https://carfu-ai-beryl.vercel.app`

```bash
cd carfu-resolver-service/deploy/vercel
vercel deploy --prod -e YOUTUBE_API_KEY
```

`GET /health` must return JSON without a browser/SSO challenge so Android
`HttpURLConnection` can call it. Deployment Protection / Vercel Authentication
must stay disabled on this project.
