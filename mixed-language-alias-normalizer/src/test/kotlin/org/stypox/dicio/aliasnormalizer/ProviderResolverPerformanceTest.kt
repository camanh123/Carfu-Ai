package org.stypox.dicio.aliasnormalizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureNanoTime

/**
 * Desktop JVM timing only. Not an Android / CARFU latency claim.
 */
class ProviderResolverPerformanceTest : StringSpec({
    val resolver = ContextualMixedLanguageProviderResolver()
    val corpus = listOf(
        "Mở bài Đừng Xa Em Đêm Nay trên smart stood",
        "Mở bài Đừng Xa Em Đêm Nay trên SmartTube",
        "Mở bài Đừng Xa Em Đêm Nay trên YouTube",
        "Mở bài Đừng Xa Em Đêm Nay trên spotify",
        "Mở bài Đừng Xa Em Đêm Nay trên smartphone",
        "Tôi đang dùng smartphone",
        "Mở YouTube trên smartphone",
        "Mở sea games trên smart toob",
        "Phát Nơi Này Có Anh bằng smart tube",
        "Cho âm lượng nhỏ xuống cùng một chút",
    )

    "representative corpus resolves well under 5ms median on this JVM" {
        repeat(50) { corpus.forEach { resolver.resolve(it) } }
        val times = LongArray(200)
        for (i in times.indices) {
            times[i] = measureNanoTime {
                corpus.forEach { resolver.resolve(it) }
            }
        }
        times.sort()
        val medianNs = times[times.size / 2]
        val medianPerTranscriptNs = medianNs / corpus.size
        println(
            "PERF corpus=${corpus.size} iterations=${times.size} " +
                "median_batch_ns=$medianNs median_per_transcript_ns=$medianPerTranscriptNs " +
                "median_per_transcript_us=${medianPerTranscriptNs / 1000.0}",
        )
        medianPerTranscriptNs.shouldBeLessThan(5_000_000L)
        resolver.resolve(corpus[0]).canonicalProvider shouldBe "SmartTube"
    }
})
