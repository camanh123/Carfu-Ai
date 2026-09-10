package org.stypox.dicio.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.streams.asSequence

class ResolverSafetyTest : StringSpec({
    val moduleRoot = Path.of("").toAbsolutePath().let { cwd ->
        if (cwd.fileName.toString() == "carfu-resolver-service") cwd
        else cwd.resolve("carfu-resolver-service")
    }

    fun sourceFiles(): List<Path> =
        Files.walk(moduleRoot.resolve("src/main")).use { walk ->
            walk.asSequence()
                .filter { it.isRegularFile() && it.extension in setOf("kt", "kts") }
                .toList()
        }

    "no Cast / Accessibility / media-control dependencies in resolver sources" {
        val forbidden = listOf(
            "CastContext",
            "CastSession",
            "Chromecast",
            "MediaRouter",
            "RemotePlayback",
            "MediaController",
            "KEYCODE_MEDIA",
            "AccessibilityService",
            "android.intent",
            "androidx.mediarouter",
            "com.google.android.gms.cast",
        )
        val hits = sourceFiles().flatMap { path ->
            val text = path.readText()
            forbidden.filter { token -> text.contains(token) }.map { "$path: $it" }
        }
        hits.shouldBeEmpty()
        sourceFiles().size shouldBeGreaterThan 5
    }

    "no API key is committed in the resolver module" {
        val keyPattern = Regex("AIza[0-9A-Za-z_-]{20,}")
        val files = Files.walk(moduleRoot).use { walk ->
            walk.asSequence()
                .filter { it.isRegularFile() }
                .filter { it.toString().contains("/build/").not() }
                .toList()
        }
        val hits = files.flatMap { path ->
            val text = runCatching { path.readText() }.getOrNull() ?: return@flatMap emptyList()
            keyPattern.findAll(text).map { "${path.fileName}: ${it.value.take(8)}..." }.toList()
        }
        hits.shouldBeEmpty()
        moduleRoot.resolve(".env.example").readText().contains("YOUTUBE_API_KEY=") shouldBe true
        moduleRoot.resolve(".env.example").readText().contains("AIza") shouldBe false
    }

    "production sources do not hardcode the test song title" {
        val production = Files.walk(moduleRoot.resolve("src/main")).use { walk ->
            walk.asSequence().filter { it.isRegularFile() && it.extension == "kt" }.toList()
        }
        val hits = production.filter { it.readText().contains("Đừng Xa Em Đêm Nay") }
        hits.shouldBeEmpty()
    }
})
