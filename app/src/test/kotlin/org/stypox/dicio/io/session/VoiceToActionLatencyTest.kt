package org.stypox.dicio.io.session

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

class VoiceToActionLatencyTest : StringSpec({
    beforeTest {
        VoiceToActionLatency.resetForTests()
        CarfuVoiceTrace.resetForTests()
        CarfuLatencyLog.resetForTests()
    }

    afterTest {
        VoiceToActionLatency.resetForTests()
        CarfuLatencyLog.resetForTests()
        CarfuVoiceTrace.resetForTests()
    }

    "marks without begin are ignored" {
        VoiceToActionLatency.mark(VoiceToActionStage.FINAL_TRANSCRIPT, "orphan")
        VoiceToActionLatency.millisOf(VoiceToActionStage.FINAL_TRANSCRIPT).shouldBeNull()
        CarfuVoiceTrace.eventsForTests().none { it.contains("V2A") } shouldBe true
    }

    "first-occurrence stages stay first-only" {
        val clock = mutableListOf(1_000L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuVoiceTrace.bind(9L, VoiceTriggerManager.Origin.UI_MODE)
        VoiceToActionLatency.begin("9", "origin=UI_MODE")
        clock[0] = 1_200L
        VoiceToActionLatency.mark(VoiceToActionStage.COMPLETE_PARTIAL_HELD, "first")
        clock[0] = 1_400L
        VoiceToActionLatency.mark(VoiceToActionStage.COMPLETE_PARTIAL_HELD, "second")
        VoiceToActionLatency.millisOf(VoiceToActionStage.COMPLETE_PARTIAL_HELD)!! shouldBeExactly 1_200L
        CarfuVoiceTrace.eventsForTests().count { it.contains("COMPLETE_PARTIAL_HELD") } shouldBe 1
    }

    "V2A lines include monotonic deltas and log through CARFU_VOICE" {
        val clock = mutableListOf(0L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuVoiceTrace.bind(42L, VoiceTriggerManager.Origin.HARDWARE_MODE)
        VoiceToActionLatency.begin("42")
        clock[0] = 8_000L
        VoiceToActionLatency.mark(VoiceToActionStage.FINAL_TRANSCRIPT, "text=Mỹ Đình")
        val line = CarfuVoiceTrace.eventsForTests().first { it.contains("FINAL_TRANSCRIPT") }
        line.shouldStartWith("session=42 V2A stage=FINAL_TRANSCRIPT")
        line shouldContain "t_ms=8000"
        line shouldContain "dt_trigger=8000"
        line shouldContain "text=Mỹ Đình"
    }

    "simulated NAVIGATE path: EOS→Final dominates; action precedes confirmation TTS" {
        val clock = mutableListOf(0L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuVoiceTrace.bind(7L, VoiceTriggerManager.Origin.HARDWARE_MODE)

        VoiceToActionLatency.begin("7", "origin=HARDWARE_MODE")
        clock[0] = 100L
        VoiceToActionLatency.mark(VoiceToActionStage.PRODUCT_LISTENING)
        clock[0] = 200L
        VoiceToActionLatency.mark(VoiceToActionStage.LISTENING_READY)
        clock[0] = 500L
        VoiceToActionLatency.mark(VoiceToActionStage.FIRST_SPEECH)
        clock[0] = 800L
        VoiceToActionLatency.mark(VoiceToActionStage.PARTIAL_TRANSCRIPT, "text=chỉ đường đến Mỹ Đình")
        clock[0] = 900L
        VoiceToActionLatency.mark(VoiceToActionStage.SILENCE_WATCH_CANCELLED, "event=Partial")
        clock[0] = 1_200L
        VoiceToActionLatency.mark(
            VoiceToActionStage.COMPLETE_PARTIAL_HELD,
            "intent=NAVIGATE wait_for_android_final=true",
        )
        clock[0] = 1_500L
        VoiceToActionLatency.mark(VoiceToActionStage.LAST_SPEECH)

        // OEM-shaped gap: ~6s from end-of-speech to Android Final.
        clock[0] = 7_500L
        VoiceToActionLatency.mark(VoiceToActionStage.FINAL_TRANSCRIPT)
        clock[0] = 7_510L
        VoiceToActionLatency.mark(VoiceToActionStage.INPUT_EVENT_RECEIVED)
        clock[0] = 7_520L
        VoiceToActionLatency.mark(VoiceToActionStage.INPUT_EVENT_DISPATCHED)
        clock[0] = 7_530L
        VoiceToActionLatency.mark(VoiceToActionStage.CANONICAL_COMMAND_READY)
        clock[0] = 7_540L
        VoiceToActionLatency.mark(VoiceToActionStage.COMMAND_LOCKED)
        VoiceToActionLatency.hasLockedCommand() shouldBe true
        clock[0] = 7_550L
        VoiceToActionLatency.mark(VoiceToActionStage.ACTION_REQUEST)
        clock[0] = 7_560L
        VoiceToActionLatency.mark(VoiceToActionStage.INTENT_DISPATCH)
        clock[0] = 7_570L
        VoiceToActionLatency.mark(VoiceToActionStage.TTS_REQUEST, "after_action=true")
        clock[0] = 7_700L
        VoiceToActionLatency.mark(VoiceToActionStage.TTS_START)

        val summary = VoiceToActionLatency.summary()
        summary shouldContain "eos_to_final=6000"
        summary shouldContain "complete_partial_to_final=6300"
        summary shouldContain "final_to_input=10"
        summary shouldContain "lock_to_action=10"
        summary shouldContain "action_to_intent=10"
        summary shouldContain "action_to_tts_req=20"
        summary shouldContain "tts_req_to_start=130"

        val action = VoiceToActionLatency.millisOf(VoiceToActionStage.ACTION_REQUEST)!!
        val ttsReq = VoiceToActionLatency.millisOf(VoiceToActionStage.TTS_REQUEST)!!
        val intent = VoiceToActionLatency.millisOf(VoiceToActionStage.INTENT_DISPATCH)!!
        (ttsReq > action) shouldBe true
        (intent > action) shouldBe true
        (action - VoiceToActionLatency.millisOf(VoiceToActionStage.LAST_SPEECH)!!) shouldBeExactly 6_050L

        clock[0] = 10_000L
        VoiceToActionLatency.end("complete")
        val terminal = CarfuVoiceTrace.eventsForTests().first { it.contains("SESSION_TERMINAL") }
        terminal shouldContain "eos_to_final=6000"
        VoiceToActionLatency.hasLockedCommand() shouldBe false
    }

    "fast-path summary records complete_partial_to_action under 1s after stability" {
        val clock = mutableListOf(0L)
        CarfuLatencyLog.nowMs = { clock[0] }
        CarfuVoiceTrace.bind(8L, VoiceTriggerManager.Origin.UI_MODE)
        VoiceToActionLatency.begin("8")
        clock[0] = 500L
        VoiceToActionLatency.mark(VoiceToActionStage.FIRST_SPEECH)
        clock[0] = 800L
        VoiceToActionLatency.mark(VoiceToActionStage.PARTIAL_TRANSCRIPT)
        clock[0] = 1_000L
        VoiceToActionLatency.mark(VoiceToActionStage.COMPLETE_PARTIAL_HELD)
        clock[0] = 1_010L
        VoiceToActionLatency.mark(VoiceToActionStage.LAST_SPEECH)
        clock[0] = 1_020L
        VoiceToActionLatency.mark(VoiceToActionStage.STABLE_COMPLETE_COMMIT)
        VoiceToActionLatency.mark(VoiceToActionStage.STOP_REQUEST)
        clock[0] = 1_030L
        VoiceToActionLatency.mark(VoiceToActionStage.COMMAND_LOCKED)
        clock[0] = 1_040L
        VoiceToActionLatency.mark(VoiceToActionStage.ACTION_REQUEST)
        val summary = VoiceToActionLatency.summary()
        summary shouldContain "complete_partial_to_commit=20"
        summary shouldContain "complete_partial_to_action=40"
        VoiceToActionLatency.end("complete")
    }
})
