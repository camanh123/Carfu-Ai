package org.stypox.dicio.youtubeplayauto

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

class YouTubeDirectTargetAndSafetyTest : StringSpec({
    val song = "Đừng Xa Em Đêm Nay"

    "public HTML parser extracts videoRenderer id and title" {
        val html = """
            {"videoRenderer":{"videoId":"AbCdeFghi_K","title":{"runs":[{"text":"Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]"}]}}}
            {"videoRenderer":{"videoId":"RemixVideo1","title":{"runs":[{"text":"Đừng Xa Em Đêm Nay remix"}]}}}
        """.trimIndent()
        val hits = YouTubePublicSearchHtmlParser.parse(html)
        hits.size shouldBe 2
        hits[0].videoId shouldBe "AbCdeFghi_K"
        hits[0].title shouldBe "Đừng Xa Em Đêm Nay - Hồ Hoàng Yến [Official 4K MV]"
    }

    "empty HTML yields no hits" {
        YouTubePublicSearchHtmlParser.parse("").shouldBeEmpty()
        YouTubePublicSearchHtmlParser.parse("<html>consent</html>").shouldBeEmpty()
    }

    "parsed resolver accepts id and watch URL and rejects titles" {
        ParsedYouTubeContentResolver.resolveQuery("abcdefghijk")
            .shouldBeInstanceOf<YouTubeContentResolution.Resolved>()
            .target.source shouldBe "parsed_id_or_watch_url"
        ParsedYouTubeContentResolver.resolveQuery("https://www.youtube.com/watch?v=abcdefghijk")
            .shouldBeInstanceOf<YouTubeContentResolution.Resolved>()
        ParsedYouTubeContentResolver.resolveQuery(song)
            .shouldBeInstanceOf<YouTubeContentResolution.Unresolved>()
    }

    "chained resolver does not hardcode the test song" {
        val unresolved = ChainedYouTubeContentResolver(ParsedYouTubeContentResolver)
            .resolveQuery(song)
        unresolved.shouldBeInstanceOf<YouTubeContentResolution.Unresolved>()
        InjectedYouTubeContentResolver().resolveQuery(song)
            .shouldBeInstanceOf<YouTubeContentResolution.Unresolved>()
    }

    "primary driver source files do not introduce Cast or media-key APIs" {
        val root = File("src/main/kotlin")
        val files = if (root.exists()) {
            root.walkTopDown().filter { it.extension == "kt" }.toList()
        } else {
            File("youtube-playauto-android/src/main/kotlin")
                .walkTopDown().filter { it.extension == "kt" }.toList()
        }
        files.isEmpty() shouldBe false
        val banned = listOf(
            "CastContext",
            "MediaRouter",
            "MediaRouteButton",
            "RemotePlaybackClient",
            "RemoteMediaClient",
            "CastOptions",
            "SessionManagerListener",
            "CastMediaControlIntent",
            "KEYCODE_MEDIA_PLAY",
            "KEYCODE_MEDIA_PAUSE",
            "dispatchMediaKeyEvent",
            "android.media.session.MediaController",
            "TransportControls",
        )
        files.forEach { file ->
            val text = file.readText()
            banned.forEach { token ->
                text.shouldNotContain(token)
            }
        }
    }

    "YouTube public launch audit does not claim autoplay extras" {
        YouTubePublicLaunchAudit.NO_OFFICIAL_AUTOPLAY_EXTRA shouldBe
            "no_public_youtube_app_intent_extra_for_autoplay"
        YouTubePublicLaunchAudit.WATCH_HOST_PATH shouldBe "https://www.youtube.com/watch?v="
    }
})
