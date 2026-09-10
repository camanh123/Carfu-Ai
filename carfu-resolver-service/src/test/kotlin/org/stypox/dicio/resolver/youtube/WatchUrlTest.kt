package org.stypox.dicio.resolver.youtube

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class WatchUrlTest : StringSpec({
    "generates a standard watch URL" {
        WatchUrl.fromVideoId("aBcDeFgHiJk") shouldBe "https://www.youtube.com/watch?v=aBcDeFgHiJk"
    }

    "rejects malformed ids" {
        WatchUrl.isValidVideoId("short") shouldBe false
        WatchUrl.isValidVideoId("tooooooooolong") shouldBe false
        shouldThrow<IllegalArgumentException> { WatchUrl.fromVideoId("nope") }
    }
})
