package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class CarfuVoiceTraceTest : StringSpec({
    beforeTest {
        CarfuVoiceTrace.resetForTests()
    }

    "one session emits the required chronological event names" {
        CarfuVoiceTrace.bind(42L, VoiceTriggerManager.Origin.UI_MODE)
        CarfuVoiceTrace.trigger("UI_MODE")
        CarfuVoiceTrace.sessionStart()
        CarfuVoiceTrace.srCreate("com.google.android.googlequicksearchbox", "GoogleRecognitionService")
        CarfuVoiceTrace.srStartListening()
        CarfuVoiceTrace.srReady()
        CarfuVoiceTrace.srBeginSpeech()
        CarfuVoiceTrace.srPartial("mấy giờ")
        CarfuVoiceTrace.srEndSpeech()
        CarfuVoiceTrace.srFinal("mấy giờ rồi", 1)
        CarfuVoiceTrace.stopRequest("final_transcript")
        CarfuVoiceTrace.terminal("complete")
        CarfuVoiceTrace.sessionEnd("complete")

        val events = CarfuVoiceTrace.eventsForTests()
        events.size shouldBe 12
        events[0] shouldBe "session=42 TRIGGER source=UI_MODE"
        events[1] shouldBe "session=42 SESSION_START origin=UI_MODE"
        events[2].shouldStartWith("session=42 SR_CREATE")
        events[3] shouldBe "session=42 SR_START_LISTENING"
        events[4] shouldBe "session=42 SR_READY"
        events[5] shouldBe "session=42 SR_BEGIN_SPEECH"
        events[6] shouldBe "session=42 SR_PARTIAL text=mấy giờ"
        events[7] shouldBe "session=42 SR_END_SPEECH"
        events[8].shouldStartWith("session=42 SR_FINAL text=mấy giờ rồi")
        events[9] shouldBe "session=42 STOP_REQUEST source=final_transcript"
        events[10] shouldBe "session=42 TERMINAL reason=complete"
        events[11] shouldBe "session=42 SESSION_END reason=complete"
        events.shouldContain("session=42 SR_START_LISTENING")
    }

    "early SR error is distinguishable from CARFU stop" {
        CarfuVoiceTrace.bind(7L, VoiceTriggerManager.Origin.HARDWARE_MODE)
        CarfuVoiceTrace.trigger("HARDWARE_MODE")
        CarfuVoiceTrace.srStartListening()
        CarfuVoiceTrace.srError(
            code = 7,
            name = "ERROR_NO_MATCH",
            action = "KEEP_PRODUCT_SESSION",
            generation = 3L,
            current = 3L,
        )
        CarfuVoiceTrace.srAbsorbed("sr_error_ERROR_NO_MATCH")
        val events = CarfuVoiceTrace.eventsForTests()
        events.shouldContain("session=7 TRIGGER source=HARDWARE_MODE")
        events.any { it.contains("SR_ERROR code=7 name=ERROR_NO_MATCH") }.shouldBe(true)
        events.shouldContain(
            "session=7 SR_ABSORBED reason=sr_error_ERROR_NO_MATCH keep_product_session=true rearm=false",
        )
        events.none { it.contains("STOP_REQUEST") }.shouldBe(true)
        events.none { it.contains("TERMINAL") }.shouldBe(true)
    }

    "stale callback is logged without a product terminal" {
        CarfuVoiceTrace.bind(3L, VoiceTriggerManager.Origin.UI_MODE)
        CarfuVoiceTrace.staleCallback("onError", generation = 1L, current = 4L)
        val events = CarfuVoiceTrace.eventsForTests()
        events.shouldContain("session=3 STALE_CALLBACK callback=onError gen=1 current=4")
    }

    "does not log audio buffers" {
        CarfuVoiceTrace.bind(1L, VoiceTriggerManager.Origin.UI_MODE)
        CarfuVoiceTrace.srPartial("xin chào")
        CarfuVoiceTrace.eventsForTests().none { it.contains("pcm") || it.contains("buffer") }
            .shouldBe(true)
    }
})
