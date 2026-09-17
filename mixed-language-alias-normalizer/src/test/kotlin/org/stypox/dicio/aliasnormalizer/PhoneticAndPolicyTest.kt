package org.stypox.dicio.aliasnormalizer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class PhoneticNormalizerTest : StringSpec({
    "tube and stood collapse to the same coda-stop key after prosthetic S drop" {
        val tube = PhoneticNormalizer.encodeToken("tube")
        val stood = PhoneticNormalizer.encodeTokenDropLeadingS("stood")
        tube shouldBe stood
        tube shouldBe "TU$"
    }

    "SmartTube spaced and smart stood align" {
        val matcher = PhoneticMatcher()
        val smartTube = ProviderEntityCatalog().all().first { it.canonicalName == "SmartTube" }
        matcher.score("smart stood", smartTube) shouldBeGreaterThan 0.84
        matcher.score("smart toob", smartTube) shouldBeGreaterThan 0.84
    }

    "spotify is phonetically far from SmartTube and YouTube" {
        val catalog = ProviderEntityCatalog()
        val matcher = PhoneticMatcher()
        catalog.all().forEach { entity ->
            matcher.score("spotify", entity) shouldBeGreaterThan -1.0
            (matcher.score("spotify", entity) < 0.75).shouldBeTrue()
        }
    }

    "YouTube encode is distinct from SmartTube encode" {
        PhoneticNormalizer.encode("smart tube") shouldBe
            PhoneticNormalizer.encode(splitCamelName("SmartTube"))
        PhoneticNormalizer.encode("you tube") shouldBe
            PhoneticNormalizer.encode(splitCamelName("YouTube"))
        PhoneticNormalizer.encode("smart tube") shouldBe "SNULT TU$"
        PhoneticNormalizer.encode("you tube") shouldBe "YU TU$"
    }
})

class ProviderConfidencePolicyTest : StringSpec({
    "exact alias is HIGH even if we do not inspect scores" {
        ProviderConfidencePolicy.confidence(
            exactCanonical = false,
            exactAlias = true,
            lexical = 1.0,
            phonetic = 1.0,
            context = 1.0,
            finalScore = 1.0,
            ambiguous = false,
        ) shouldBe Confidence.HIGH
    }

    "lexical resemblance without phonetic is not HIGH" {
        ProviderConfidencePolicy.confidence(
            exactCanonical = false,
            exactAlias = false,
            lexical = 0.9,
            phonetic = 0.4,
            context = 1.0,
            finalScore = 0.7,
            ambiguous = false,
        ) shouldBe Confidence.LOW
    }

    "ambiguous ranked pair is not a rewrite" {
        val a = EntityScore("SmartTube", false, null, 0.8, 0.8, 1.0, 0.80)
        val b = EntityScore("YouTube", false, null, 0.8, 0.8, 1.0, 0.78)
        ProviderConfidencePolicy.isAmbiguous(listOf(a, b)).shouldBeTrue()
        ProviderConfidencePolicy.shouldRewrite(
            Confidence.HIGH, true, "tube", "smarttube",
        ).shouldBeFalse()
    }

    "already-canonical folded form does not rewrite" {
        ProviderConfidencePolicy.shouldRewrite(
            Confidence.HIGH, false, "smarttube", "smarttube",
        ).shouldBeFalse()
        ProviderConfidencePolicy.shouldRewrite(
            Confidence.HIGH, false, "smart stood", "smarttube",
        ).shouldBeTrue()
    }
})
