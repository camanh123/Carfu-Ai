/**
 * HTTPS deploy adapter for the frozen CARFU YouTube resolver contract.
 * Ranking / cache / query rules mirror the Kotlin sources under
 * carfu-resolver-service/src/main and are not modified there.
 *
 * Official endpoint only: https://www.googleapis.com/youtube/v3/search
 * API key stays in Worker secrets. Never forwarded to Android.
 */

const SOURCE = "youtube_data_api_v3";
const SEARCH_ENDPOINT = "https://www.googleapis.com/youtube/v3/search";
const MIN_SCORE = 40.0;
const MIN_TITLE_OVERLAP = 0.4;
const POSITIVE_TTL_MILLIS = 21 * 24 * 60 * 60 * 1000;
const NEGATIVE_TTL_MILLIS = 60 * 60 * 1000;
const DEFAULT_MAX_ENTRIES = 2048;
const VIDEO_ID_RE = /^[A-Za-z0-9_-]{11}$/;
const WHITESPACE = /\s+/g;
const MARKS = /\p{M}+/gu;
const NON_ALNUM = /[^a-z0-9#]+/g;

const STRIP_FROM_CORE = new Set([
  "karaoke", "remix", "live", "cover", "official", "audio", "mv", "video",
  "sped", "spedup", "speed", "slowed", "nightcore", "reaction",
  "instrumental", "beat", "shorts", "4k", "hd", "lyric", "lyrics",
]);

const VariantKind = {
  KARAOKE: "KARAOKE",
  REMIX: "REMIX",
  LIVE: "LIVE",
  COVER: "COVER",
  OFFICIAL: "OFFICIAL",
  AUDIO: "AUDIO",
  MV: "MV",
  VIDEO: "VIDEO",
  SPED_UP: "SPED_UP",
  SLOWED: "SLOWED",
  NIGHTCORE: "NIGHTCORE",
  REACTION: "REACTION",
  INSTRUMENTAL: "INSTRUMENTAL",
  BEAT: "BEAT",
};

const cache = new Map();

export function fold(value) {
  const decomposed = value.normalize("NFD");
  const withoutMarks = decomposed.replace(MARKS, "");
  return withoutMarks
    .replaceAll("đ", "d")
    .replaceAll("Đ", "d")
    .toLowerCase()
    .replace(NON_ALNUM, " ")
    .trim()
    .replace(WHITESPACE, " ");
}

export function tokenize(folded) {
  return folded.split(/\s+/).filter((token) => token.length > 0);
}

function detectKinds(folded) {
  const padded = ` ${folded} `;
  const has = (phrase) => padded.includes(` ${phrase} `);
  const kinds = new Set();
  if (has("karaoke")) kinds.add(VariantKind.KARAOKE);
  if (has("remix")) kinds.add(VariantKind.REMIX);
  if (has("live")) kinds.add(VariantKind.LIVE);
  if (has("cover")) kinds.add(VariantKind.COVER);
  if (has("official")) kinds.add(VariantKind.OFFICIAL);
  if (has("audio")) kinds.add(VariantKind.AUDIO);
  if (has("mv")) kinds.add(VariantKind.MV);
  if (has("video")) kinds.add(VariantKind.VIDEO);
  if (has("sped up") || has("spedup") || has("speed up") || has("speedup")) {
    kinds.add(VariantKind.SPED_UP);
  }
  if (has("slowed")) kinds.add(VariantKind.SLOWED);
  if (has("nightcore")) kinds.add(VariantKind.NIGHTCORE);
  if (has("reaction")) kinds.add(VariantKind.REACTION);
  if (has("instrumental")) kinds.add(VariantKind.INSTRUMENTAL);
  if (has("beat")) kinds.add(VariantKind.BEAT);
  return kinds;
}

function requestedVariants(kinds) {
  return {
    karaoke: kinds.has(VariantKind.KARAOKE),
    remix: kinds.has(VariantKind.REMIX),
    live: kinds.has(VariantKind.LIVE),
    cover: kinds.has(VariantKind.COVER),
    official: kinds.has(VariantKind.OFFICIAL),
    audio: kinds.has(VariantKind.AUDIO),
    mv: kinds.has(VariantKind.MV),
    video: kinds.has(VariantKind.VIDEO),
    spedUp: kinds.has(VariantKind.SPED_UP),
    slowed: kinds.has(VariantKind.SLOWED),
    nightcore: kinds.has(VariantKind.NIGHTCORE),
  };
}

function cacheToken(variants) {
  const parts = [];
  if (variants.karaoke) parts.push("karaoke");
  if (variants.remix) parts.push("remix");
  if (variants.live) parts.push("live");
  if (variants.cover) parts.push("cover");
  if (variants.official) parts.push("official");
  if (variants.audio) parts.push("audio");
  if (variants.mv) parts.push("mv");
  if (variants.video) parts.push("video");
  if (variants.spedUp) parts.push("spedup");
  if (variants.slowed) parts.push("slowed");
  if (variants.nightcore) parts.push("nightcore");
  return parts.join(",");
}

function requestedKinds(variants) {
  const set = new Set();
  if (variants.karaoke) set.add(VariantKind.KARAOKE);
  if (variants.remix) set.add(VariantKind.REMIX);
  if (variants.live) set.add(VariantKind.LIVE);
  if (variants.cover) set.add(VariantKind.COVER);
  if (variants.official) set.add(VariantKind.OFFICIAL);
  if (variants.audio) set.add(VariantKind.AUDIO);
  if (variants.mv) set.add(VariantKind.MV);
  if (variants.video) set.add(VariantKind.VIDEO);
  if (variants.spedUp) set.add(VariantKind.SPED_UP);
  if (variants.slowed) set.add(VariantKind.SLOWED);
  if (variants.nightcore) set.add(VariantKind.NIGHTCORE);
  return set;
}

function detectTitle(folded) {
  const kinds = detectKinds(folded);
  const padded = ` ${folded} `;
  return {
    kinds,
    officialMarker: padded.includes(" official "),
    officialMv: padded.includes(" official ") && (padded.includes(" mv ") || padded.includes(" official mv ")),
    officialAudio: padded.includes(" official audio ") ||
      (padded.includes(" official ") && padded.includes(" audio ")),
    shorts: padded.includes(" shorts ") || folded.includes("#shorts"),
    has(kind) {
      return kinds.has(kind);
    },
  };
}

export function normalize(raw) {
  const original = raw.trim().replace(WHITESPACE, " ");
  const foldedValue = fold(original);
  const tokens = tokenize(foldedValue);
  const kinds = detectKinds(foldedValue);
  const variants = requestedVariants(kinds);
  const coreTokens = tokens.filter((token) => !STRIP_FROM_CORE.has(token));
  return {
    original,
    searchQuery: original,
    folded: foldedValue,
    tokens,
    coreTokens,
    variants,
  };
}

function overlapRatio(needle, haystack) {
  if (needle.length === 0) return 0;
  return needle.filter((token) => haystack.has(token)).length / needle.length;
}

function containsPhrase(haystack, needle) {
  if (needle.length === 0 || haystack.length < needle.length) return false;
  for (let i = 0; i <= haystack.length - needle.length; i++) {
    let ok = true;
    for (let j = 0; j < needle.length; j++) {
      if (haystack[i + j] !== needle[j]) {
        ok = false;
        break;
      }
    }
    if (ok) return true;
  }
  return false;
}

function unrequestedPenalties(requested, signals) {
  let penalty = 0;
  const penalize = (kind, amount) => {
    if (!requested.has(kind) && signals.has(kind)) penalty += amount;
  };
  penalize(VariantKind.KARAOKE, -42.0);
  penalize(VariantKind.REMIX, -36.0);
  penalize(VariantKind.COVER, -32.0);
  penalize(VariantKind.REACTION, -42.0);
  penalize(VariantKind.LIVE, -26.0);
  penalize(VariantKind.SPED_UP, -32.0);
  penalize(VariantKind.SLOWED, -32.0);
  penalize(VariantKind.NIGHTCORE, -32.0);
  penalize(VariantKind.INSTRUMENTAL, -26.0);
  penalize(VariantKind.BEAT, -20.0);
  return penalty;
}

function isShortDuration(candidate) {
  const duration = candidate.durationSeconds;
  if (duration == null) return false;
  return duration >= 1 && duration <= 60;
}

export function score(query, candidate) {
  const titleFolded = fold(candidate.title);
  const channelFolded = fold(candidate.channelTitle);
  const titleTokens = tokenize(titleFolded);
  const channelTokens = new Set(tokenize(channelFolded));
  const titleTokenSet = new Set(titleTokens);
  const signals = detectTitle(titleFolded);
  const coreQuery = query.coreTokens;
  if (coreQuery.length === 0) return null;

  const overlap = overlapRatio(coreQuery, titleTokenSet);
  const phrase = containsPhrase(titleTokens, coreQuery);
  const titleExact = tokenize(
    titleFolded.split(" ").filter((token) => !STRIP_FROM_CORE.has(token)).join(" "),
  ).length === coreQuery.length &&
    tokenize(
      titleFolded.split(" ").filter((token) => !STRIP_FROM_CORE.has(token)).join(" "),
    ).every((token, i) => token === coreQuery[i]);

  if (!phrase && overlap < MIN_TITLE_OVERLAP) {
    return null;
  }

  let value = 0.0;
  if (titleExact) value += 100.0;
  else if (phrase) value += 80.0;
  else if (overlap >= 0.99) value += 50.0;
  else value += overlap * 40.0;

  const artistHitsFirst = coreQuery.filter(
    (token) => channelTokens.has(token) && !titleTokenSet.has(token),
  ).length;
  const artistHitsSecond = Math.min(
    query.tokens.filter(
      (token) => channelTokens.has(token) && !STRIP_FROM_CORE.has(token),
    ).length,
    3,
  );
  const artistHits = artistHitsFirst + artistHitsSecond;
  value += Math.min(24.0, artistHits * 8.0);

  const requested = requestedKinds(query.variants);
  for (const kind of requested) {
    if (kind === VariantKind.VIDEO) {
      if (signals.has(kind)) value += 8.0;
      continue;
    }
    if (signals.has(kind) || (kind === VariantKind.OFFICIAL && signals.officialMarker)) {
      value += 40.0;
    } else {
      value -= 30.0;
    }
  }

  if (signals.officialMarker) value += 18.0;
  if (signals.officialMv) value += 8.0;
  if (signals.officialAudio) value += 8.0;

  if (channelFolded.includes("official") || channelFolded.includes("vevo")) {
    value += 6.0;
  }

  const views = candidate.viewCount;
  if (views != null && views > 0) {
    value += Math.min(5.0, Math.log(1.0 + views) / 4.0);
  }

  if (signals.shorts || isShortDuration(candidate)) {
    value -= 28.0;
  }

  value += unrequestedPenalties(requested, signals);

  if (value < MIN_SCORE) return null;
  return { candidate, score: value };
}

export function pick(query, candidates) {
  let best = null;
  for (const candidate of candidates) {
    const scored = score(query, candidate);
    if (!scored) continue;
    if (
      best == null ||
      scored.score > best.score ||
      (scored.score === best.score && scored.candidate.videoId < best.candidate.videoId)
    ) {
      best = scored;
    }
  }
  return best;
}

export function isValidVideoId(videoId) {
  return VIDEO_ID_RE.test(videoId);
}

function fromVideoId(videoId) {
  return `https://www.youtube.com/watch?v=${videoId}`;
}

function jsonStringBody(fields) {
  const parts = [];
  for (const [key, value] of fields) {
    if (value == null) continue;
    parts.push(`"${escapeJson(key)}":"${escapeJson(value)}"`);
  }
  return `{${parts.join(",")}}`;
}

function escapeJson(value) {
  let out = "";
  for (const ch of value) {
    switch (ch) {
      case "\\":
        out += "\\\\";
        break;
      case "\"":
        out += "\\\"";
        break;
      case "\n":
        out += "\\n";
        break;
      case "\r":
        out += "\\r";
        break;
      case "\t":
        out += "\\t";
        break;
      default:
        if (ch.codePointAt(0) < 0x20) {
          out += `\\u${ch.codePointAt(0).toString(16).padStart(4, "0")}`;
        } else {
          out += ch;
        }
    }
  }
  return out;
}

function cacheKeyOf(query, lang, region) {
  return `${query.folded}|${cacheToken(query.variants)}|${lang.toLowerCase()}|${region.toUpperCase()}`;
}

function cacheGet(key, now) {
  const entry = cache.get(key);
  if (!entry) return null;
  if (now - entry.storedAtMillis >= entry.ttlMillis) {
    cache.delete(key);
    return null;
  }
  cache.delete(key);
  cache.set(key, entry);
  return entry;
}

function cachePut(key, entry) {
  if (cache.has(key)) cache.delete(key);
  cache.set(key, entry);
  while (cache.size > DEFAULT_MAX_ENTRIES) {
    const oldest = cache.keys().next().value;
    cache.delete(oldest);
  }
}

function entryToResponse(entry, originalQuery, cacheFlag) {
  return jsonStringBody([
    ["status", entry.status],
    ["query", originalQuery],
    ["videoId", entry.videoId],
    ["title", entry.title],
    ["channelTitle", entry.channelTitle],
    ["watchUrl", entry.watchUrl],
    ["source", entry.status === "RESOLVED" ? SOURCE : null],
    ["cache", cacheFlag],
  ]);
}

function parseSearchList(body) {
  let root;
  try {
    root = JSON.parse(body);
  } catch {
    return { type: "failure", status: "INVALID_RESPONSE", detail: "malformed_json" };
  }
  const items = root.items;
  if (items == null) {
    return { type: "failure", status: "INVALID_RESPONSE", detail: "missing_items" };
  }
  if (!Array.isArray(items)) {
    return { type: "failure", status: "INVALID_RESPONSE", detail: "items_not_array" };
  }
  if (items.length === 0) {
    return { type: "success", candidates: [] };
  }
  const candidates = [];
  let sawItemWithoutId = false;
  for (const item of items) {
    const videoId = item?.id?.videoId;
    if (typeof videoId !== "string" || !isValidVideoId(videoId)) {
      sawItemWithoutId = true;
      continue;
    }
    const snippet = item.snippet ?? {};
    candidates.push({
      videoId,
      title: typeof snippet.title === "string" ? snippet.title : "",
      channelTitle: typeof snippet.channelTitle === "string" ? snippet.channelTitle : "",
      description: typeof snippet.description === "string" ? snippet.description : "",
    });
  }
  if (candidates.length === 0 && sawItemWithoutId) {
    return { type: "failure", status: "INVALID_RESPONSE", detail: "missing_video_id" };
  }
  return { type: "success", candidates };
}

function mapHttpError(httpCode, body) {
  let reason = null;
  try {
    const errors = JSON.parse(body)?.error?.errors;
    if (Array.isArray(errors) && errors[0]?.reason) {
      reason = errors[0].reason;
    }
  } catch {
    reason = null;
  }
  const quota = reason === "quotaExceeded" || reason === "dailyLimitExceeded";
  let status = "RESOLVER_UNAVAILABLE";
  if (httpCode === 403 && quota) status = "QUOTA_EXCEEDED";
  else if (httpCode >= 500 && httpCode <= 599) status = "RESOLVER_UNAVAILABLE";
  else if (httpCode === 408 || httpCode === 504) status = "TIMEOUT";
  else if (httpCode === 400) status = "INVALID_RESPONSE";
  return { type: "failure", status, detail: `http_${httpCode}` };
}

function buildSearchUrl(request, apiKey) {
  const params = new URLSearchParams();
  params.set("part", "snippet");
  params.set("type", "video");
  params.set("maxResults", String(Math.min(10, Math.max(1, request.maxResults ?? 8))));
  params.set("q", request.query);
  if (request.lang) params.set("relevanceLanguage", request.lang);
  if (request.region) params.set("regionCode", request.region);
  params.set("key", apiKey);
  return `${SEARCH_ENDPOINT}?${params.toString()}`;
}

async function youtubeSearch(request, apiKey) {
  if (!apiKey) {
    return { type: "failure", status: "RESOLVER_UNAVAILABLE", detail: "MISSING_API_KEY" };
  }
  const url = buildSearchUrl(request, apiKey);
  let response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers: {
        Accept: "application/json",
        "User-Agent": "carfu-resolver-service/1.0",
      },
      signal: AbortSignal.timeout(8000),
    });
  } catch (error) {
    const timeout = error?.name === "TimeoutError" || error?.name === "AbortError";
    return {
      type: "failure",
      status: timeout ? "TIMEOUT" : "RESOLVER_UNAVAILABLE",
      detail: timeout ? "timeout" : "network_failure",
    };
  }
  const body = await response.text();
  if (response.status !== 200) {
    return mapHttpError(response.status, body);
  }
  return parseSearchList(body);
}

export function resolveLocal(rawQuery, lang, region, now, apiKeyConfigured, searchOutcome) {
  const query = normalize(rawQuery);
  if (!query.original || query.coreTokens.length === 0) {
    return jsonStringBody([
      ["status", "INVALID_RESPONSE"],
      ["query", query.original || rawQuery],
      ["error", "EMPTY_QUERY"],
    ]);
  }
  const key = cacheKeyOf(query, lang, region);
  const cached = cacheGet(key, now);
  if (cached) {
    return entryToResponse(cached, query.original, "HIT");
  }
  if (!apiKeyConfigured) {
    return jsonStringBody([
      ["status", "RESOLVER_UNAVAILABLE"],
      ["query", query.original],
      ["cache", "MISS"],
      ["error", "MISSING_API_KEY"],
    ]);
  }
  if (searchOutcome.type === "failure") {
    return jsonStringBody([
      ["status", searchOutcome.status],
      ["query", query.original],
      ["cache", "MISS"],
      ["error", searchOutcome.detail === "MISSING_API_KEY" ? "MISSING_API_KEY" : null],
    ]);
  }
  if (searchOutcome.candidates.length === 0) {
    cachePut(key, {
      status: "NO_RESULTS",
      storedAtMillis: now,
      ttlMillis: NEGATIVE_TTL_MILLIS,
    });
    return jsonStringBody([
      ["status", "NO_RESULTS"],
      ["query", query.original],
      ["cache", "MISS"],
    ]);
  }
  const picked = pick(query, searchOutcome.candidates);
  if (!picked) {
    cachePut(key, {
      status: "NO_RESULTS",
      storedAtMillis: now,
      ttlMillis: NEGATIVE_TTL_MILLIS,
    });
    return jsonStringBody([
      ["status", "NO_RESULTS"],
      ["query", query.original],
      ["cache", "MISS"],
    ]);
  }
  const videoId = picked.candidate.videoId;
  if (!isValidVideoId(videoId)) {
    return jsonStringBody([
      ["status", "INVALID_RESPONSE"],
      ["query", query.original],
      ["cache", "MISS"],
    ]);
  }
  const watchUrl = fromVideoId(videoId);
  cachePut(key, {
    status: "RESOLVED",
    videoId,
    title: picked.candidate.title,
    channelTitle: picked.candidate.channelTitle,
    watchUrl,
    storedAtMillis: now,
    ttlMillis: POSITIVE_TTL_MILLIS,
  });
  return jsonStringBody([
    ["status", "RESOLVED"],
    ["query", query.original],
    ["videoId", videoId],
    ["title", picked.candidate.title],
    ["channelTitle", picked.candidate.channelTitle],
    ["watchUrl", watchUrl],
    ["source", SOURCE],
    ["cache", "MISS"],
  ]);
}

function textResponse(status, body, contentType = "application/json; charset=utf-8") {
  return new Response(body, {
    status,
    headers: {
      "content-type": contentType,
      "cache-control": "no-store",
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const apiKey = (env?.YOUTUBE_API_KEY ?? "").trim();
    const fetchStats = String(env?.YOUTUBE_FETCH_STATS ?? "").toLowerCase() === "true";

    if (path === "/health") {
      return textResponse(200, `{"status":"ok"}`);
    }
    if (path === "/v1/diagnostics") {
      return textResponse(
        200,
        `{"service":"carfu-resolver-service","youtubeApiKeyConfigured":${apiKey.length > 0},"source":"${SOURCE}","fetchStats":${fetchStats}}`,
      );
    }
    if (path === "/v1/youtube/resolve") {
      if (request.method !== "GET") {
        return textResponse(405, jsonStringBody([
          ["status", "INVALID_RESPONSE"],
          ["error", "METHOD_NOT_ALLOWED"],
        ]));
      }
      const q = url.searchParams.get("q") ?? "";
      const lang = url.searchParams.get("lang") ?? "";
      const region = url.searchParams.get("region") ?? "";
      const query = normalize(q);
      if (!query.original || query.coreTokens.length === 0) {
        return textResponse(200, jsonStringBody([
          ["status", "INVALID_RESPONSE"],
          ["query", query.original || q],
          ["error", "EMPTY_QUERY"],
        ]));
      }
      const key = cacheKeyOf(query, lang, region);
      const now = Date.now();
      const cached = cacheGet(key, now);
      if (cached) {
        return textResponse(200, entryToResponse(cached, query.original, "HIT"));
      }
      const outcome = await youtubeSearch(
        { query: query.searchQuery, lang, region, maxResults: 8 },
        apiKey,
      );
      return textResponse(
        200,
        resolveLocal(q, lang, region, now, apiKey.length > 0, outcome),
      );
    }
    return textResponse(404, jsonStringBody([
      ["status", "INVALID_RESPONSE"],
      ["error", "NOT_FOUND"],
    ]));
  },
};
