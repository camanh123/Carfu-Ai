package org.stypox.dicio.io.assist

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldStartWith
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.stypox.dicio.io.session.CarfuActivationSource
import org.stypox.dicio.io.session.CarfuCommandRouter
import org.stypox.dicio.io.session.CarfuIntent

class CarfuAssistIntentsTest : StringSpec({
    "MODE and official assistant actions start the hardware CommandSession" {
        CarfuAssistIntents.isHardwareAssistAction("android.intent.action.ASSIST").shouldBeTrue()
        CarfuAssistIntents.isHardwareAssistAction("android.intent.action.VOICE_COMMAND").shouldBeTrue()
        CarfuAssistIntents.isHardwareAssistAction("android.intent.action.VOICE_ASSIST").shouldBeTrue()
        CarfuAssistIntents.isHardwareAssistAction("android.speech.action.VOICE_SEARCH_HANDS_FREE")
            .shouldBeTrue()
        CarfuAssistIntents.isHardwareAssistAction("android.speech.action.WEB_SEARCH").shouldBeTrue()
    }

    "launcher and wake-word actions are not MODE presses" {
        CarfuAssistIntents.isHardwareAssistAction("android.intent.action.MAIN").shouldBeFalse()
        CarfuAssistIntents.isHardwareAssistAction(
            "org.stypox.dicio.MainActivity.ACTION_WAKE_WORD",
        ).shouldBeFalse()
        CarfuAssistIntents.isHardwareAssistAction(null).shouldBeFalse()
    }

    "generic browser WEB_SEARCH is not claimed as a MODE intent" {
        CarfuAssistIntents.isGenericWebSearch("android.intent.action.WEB_SEARCH").shouldBeTrue()
        CarfuAssistIntents.isHardwareAssistAction("android.intent.action.WEB_SEARCH").shouldBeFalse()
    }

    "assistant settings open the system picker and never write a default" {
        CarfuAssistIntents.assistantSettingsActions() shouldStartWith listOf(
            CarfuAssistIntents.SETTINGS_VOICE_INPUT,
        )
        CarfuAssistIntents.assistantSettingsActions().first() shouldBe
            "android.settings.VOICE_INPUT_SETTINGS"
    }

    "sensitive extras are logged as keys and types only" {
        CarfuAssistIntents.isSensitiveExtraKey("query").shouldBeTrue()
        CarfuAssistIntents.isSensitiveExtraKey("android.speech.extra.RESULTS").shouldBeTrue()
        CarfuAssistIntents.isSensitiveExtraKey("android.intent.extra.TEXT").shouldBeTrue()
        CarfuAssistIntents.isSensitiveExtraKey("from_voice_interaction").shouldBeFalse()
        val summary = CarfuAssistIntents.summarizeSafeExtras(
            mapOf(
                "query" to "mở youtube",
                "from_voice_interaction" to true,
            ),
        )
        summary.shouldContain("query:type=String:redacted")
        summary.shouldContain("from_voice_interaction:type=Boolean")
        summary.shouldNotContain("mở youtube")
    }

    "MODE does not execute the ranker Search skill; explicit Vietnamese search still routes" {
        CarfuAssistIntents.shouldExecuteRankerSkill(
            "search",
            CarfuActivationSource.Kind.HARDWARE_BUTTON,
        ).shouldBeFalse()
        CarfuAssistIntents.shouldExecuteRankerSkill(
            "open",
            CarfuActivationSource.Kind.HARDWARE_BUTTON,
        ).shouldBeTrue()
        CarfuAssistIntents.shouldExecuteRankerSkill(
            "search",
            CarfuActivationSource.Kind.AUTOMATIC_WAKE,
        ).shouldBeTrue()
        CarfuCommandRouter.match("tìm kiếm youtube")!!.intent shouldBe CarfuIntent.SEARCH
        CarfuCommandRouter.match("một câu không phải lệnh").shouldBe(null)
    }
})
