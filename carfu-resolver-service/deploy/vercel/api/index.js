import worker from "../worker.mjs";

export default async function handler(req, res) {
  const host = req.headers.host || "localhost";
  const proto = req.headers["x-forwarded-proto"] || "https";
  const url = `${proto}://${host}${req.url}`;
  const headers = new Headers();
  for (const [key, value] of Object.entries(req.headers)) {
    if (value == null) continue;
    headers.set(key, Array.isArray(value) ? value.join(", ") : String(value));
  }
  const request = new Request(url, { method: req.method, headers });
  const response = await worker.fetch(request, {
    YOUTUBE_API_KEY: process.env.YOUTUBE_API_KEY || "",
  });
  res.statusCode = response.status;
  response.headers.forEach((value, key) => {
    res.setHeader(key, value);
  });
  const body = Buffer.from(await response.arrayBuffer());
  res.end(body);
}
