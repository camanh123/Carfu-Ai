import { strict as assert } from "node:assert";
import { pick, normalize, score } from "./worker.mjs";

const QUERY = "Đừng Xa Em Đêm Nay";

const officialMv = {
  videoId: "aBcDeFgHiJk",
  title: "Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]",
  channelTitle: "Hồ Hoàng Yến Official",
  viewCount: 12_000_000,
};
const karaoke = {
  videoId: "kArAoKeFixt",
  title: "Đừng Xa Em Đêm Nay | KARAOKE",
  channelTitle: "Karaoke Hits",
};
const remix = {
  videoId: "rEmIxFixt12",
  title: "Đừng Xa Em Đêm Nay Remix",
  channelTitle: "DJ Mix Channel",
};
const cover = {
  videoId: "cOvErFixt12",
  title: "Đừng Xa Em Đêm Nay Cover",
  channelTitle: "Cover Studio",
};
const live = {
  videoId: "lIvEfIxt123",
  title: "Đừng Xa Em Đêm Nay Live",
  channelTitle: "Live Stage",
};
const unrelated = {
  videoId: "uNrElAtEd12",
  title: "How to cook pho at home",
  channelTitle: "Cooking Daily",
};

function pickId(query, candidates) {
  return pick(normalize(query), candidates)?.candidate.videoId ?? null;
}

const a = normalize("Đừng Xa Em Đêm Nay");
const b = normalize("đừng xa em đêm nay");
const c = normalize("ĐỪNG XA EM ĐÊM NAY");
const d = normalize("  Đừng Xa Em Đêm Nay  ");
assert.equal(a.folded, "dung xa em dem nay");
assert.equal(b.folded, a.folded);
assert.equal(c.folded, a.folded);
assert.equal(d.folded, a.folded);
assert.equal(d.original, QUERY);

const karaokeQ = normalize("Đừng Xa Em Đêm Nay karaoke");
assert.equal(karaokeQ.variants.karaoke, true);
assert.deepEqual(karaokeQ.coreTokens, ["dung", "xa", "em", "dem", "nay"]);

assert.equal(normalize("hello sped up").variants.spedUp, true);
assert.equal(normalize("hello nightcore").variants.nightcore, true);

const exact = {
  videoId: "eXaCtItLe12",
  title: QUERY,
  channelTitle: "Unknown",
};
assert.equal(pickId(QUERY, [unrelated, exact, cover]), exact.videoId);
assert.equal(pickId(QUERY, [remix, officialMv]), officialMv.videoId);
assert.equal(pickId(`${QUERY} karaoke`, [officialMv, karaoke, remix, cover, live]), karaoke.videoId);
assert.equal(pickId(`${QUERY} remix`, [officialMv, karaoke, remix, cover, live]), remix.videoId);

const genericChannel = { ...officialMv, videoId: "gEnErIcCh12", channelTitle: "Random Uploads" };
const artistChannel = { ...officialMv, videoId: "aRtIsTcH123", channelTitle: "Hồ Hoàng Yến Official" };
assert.equal(
  pickId(`Hồ Hoàng Yến ${QUERY}`, [genericChannel, artistChannel]),
  artistChannel.videoId,
);

assert.equal(pickId(QUERY, [unrelated]), null);
assert.equal(pick(normalize(QUERY), [{
  videoId: "wEaKiTeM123",
  title: "totally different topic remix karaoke",
  channelTitle: "Spam",
}]), null);

const shorts = { ...officialMv, videoId: "sHoRtSvId12", title: "Đừng Xa Em Đêm Nay #shorts" };
assert.equal(pickId(QUERY, [shorts, officialMv]), officialMv.videoId);

assert.equal(score(normalize(QUERY), unrelated), null);
assert.ok(score(normalize(QUERY), officialMv));

console.log("ranker adapter tests passed");
