package org.stypox.dicio.resolver.query

data class RequestedVariants(
    val karaoke: Boolean = false,
    val remix: Boolean = false,
    val live: Boolean = false,
    val cover: Boolean = false,
    val official: Boolean = false,
    val audio: Boolean = false,
    val mv: Boolean = false,
    val video: Boolean = false,
    val spedUp: Boolean = false,
    val slowed: Boolean = false,
    val nightcore: Boolean = false,
) {
    fun cacheToken(): String = buildList {
        if (karaoke) add("karaoke")
        if (remix) add("remix")
        if (live) add("live")
        if (cover) add("cover")
        if (official) add("official")
        if (audio) add("audio")
        if (mv) add("mv")
        if (video) add("video")
        if (spedUp) add("spedup")
        if (slowed) add("slowed")
        if (nightcore) add("nightcore")
    }.joinToString(",")

    fun requestedKinds(): Set<VariantKind> = buildSet {
        if (karaoke) add(VariantKind.KARAOKE)
        if (remix) add(VariantKind.REMIX)
        if (live) add(VariantKind.LIVE)
        if (cover) add(VariantKind.COVER)
        if (official) add(VariantKind.OFFICIAL)
        if (audio) add(VariantKind.AUDIO)
        if (mv) add(VariantKind.MV)
        if (video) add(VariantKind.VIDEO)
        if (spedUp) add(VariantKind.SPED_UP)
        if (slowed) add(VariantKind.SLOWED)
        if (nightcore) add(VariantKind.NIGHTCORE)
    }
}

enum class VariantKind {
    KARAOKE,
    REMIX,
    LIVE,
    COVER,
    OFFICIAL,
    AUDIO,
    MV,
    VIDEO,
    SPED_UP,
    SLOWED,
    NIGHTCORE,
    REACTION,
    INSTRUMENTAL,
    BEAT,
}

data class TitleSignals(
    val kinds: Set<VariantKind>,
    val officialMarker: Boolean,
    val officialMv: Boolean,
    val officialAudio: Boolean,
    val shorts: Boolean,
) {
    fun has(kind: VariantKind): Boolean = kind in kinds
}

object VariantDetector {
    val STRIP_FROM_CORE: Set<String> = setOf(
        "karaoke", "remix", "live", "cover", "official", "audio", "mv", "video",
        "sped", "spedup", "speed", "slowed", "nightcore", "reaction",
        "instrumental", "beat", "shorts", "4k", "hd", "lyric", "lyrics",
    )

    fun detectRequested(folded: String): RequestedVariants {
        val kinds = detectKinds(folded)
        return RequestedVariants(
            karaoke = VariantKind.KARAOKE in kinds,
            remix = VariantKind.REMIX in kinds,
            live = VariantKind.LIVE in kinds,
            cover = VariantKind.COVER in kinds,
            official = VariantKind.OFFICIAL in kinds,
            audio = VariantKind.AUDIO in kinds,
            mv = VariantKind.MV in kinds,
            video = VariantKind.VIDEO in kinds,
            spedUp = VariantKind.SPED_UP in kinds,
            slowed = VariantKind.SLOWED in kinds,
            nightcore = VariantKind.NIGHTCORE in kinds,
        )
    }

    fun detectTitle(folded: String): TitleSignals {
        val kinds = detectKinds(folded)
        val padded = " $folded "
        return TitleSignals(
            kinds = kinds,
            officialMarker = " official " in padded,
            officialMv = padded.contains(" official ") && (" mv " in padded || " official mv " in padded),
            officialAudio = padded.contains(" official audio ") ||
                (padded.contains(" official ") && padded.contains(" audio ")),
            shorts = " shorts " in padded || "#shorts" in folded,
        )
    }

    fun detectKinds(folded: String): Set<VariantKind> {
        val padded = " $folded "
        fun has(phrase: String): Boolean = padded.contains(" $phrase ")
        return buildSet {
            if (has("karaoke")) add(VariantKind.KARAOKE)
            if (has("remix")) add(VariantKind.REMIX)
            if (has("live")) add(VariantKind.LIVE)
            if (has("cover")) add(VariantKind.COVER)
            if (has("official")) add(VariantKind.OFFICIAL)
            if (has("audio")) add(VariantKind.AUDIO)
            if (has("mv")) add(VariantKind.MV)
            if (has("video")) add(VariantKind.VIDEO)
            if (has("sped up") || has("spedup") || has("speed up") || has("speedup")) {
                add(VariantKind.SPED_UP)
            }
            if (has("slowed")) add(VariantKind.SLOWED)
            if (has("nightcore")) add(VariantKind.NIGHTCORE)
            if (has("reaction")) add(VariantKind.REACTION)
            if (has("instrumental")) add(VariantKind.INSTRUMENTAL)
            if (has("beat")) add(VariantKind.BEAT)
        }
    }
}
