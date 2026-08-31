package org.stypox.dicio.ui.driving

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.CommandUiState

class DrivingUiMapperTest : StringSpec({
    "idle maps to ready" {
        val p = DrivingUiMapper.presentation(CommandUiState())
        p.visual shouldBe DrivingVisualState.READY
        p.labelResHint shouldBe DrivingLabel.READY
    }

    "acknowledging maps to the wake TTS label" {
        val p = DrivingUiMapper.presentation(
            CommandUiState(phase = CommandSessionPhase.ACKNOWLEDGING)
        )
        p.visual shouldBe DrivingVisualState.ACKNOWLEDGING
        p.labelResHint shouldBe DrivingLabel.ACK
    }

    "command listening maps to listening" {
        val p = DrivingUiMapper.presentation(
            CommandUiState(phase = CommandSessionPhase.COMMAND_LISTENING)
        )
        p.visual shouldBe DrivingVisualState.LISTENING
        p.labelResHint shouldBe DrivingLabel.LISTENING
        p.showPartial shouldBe true
    }

    "processing maps to processing" {
        val p = DrivingUiMapper.presentation(
            CommandUiState(phase = CommandSessionPhase.PROCESSING)
        )
        p.visual shouldBe DrivingVisualState.PROCESSING
        p.labelResHint shouldBe DrivingLabel.PROCESSING
    }

    "unclear responding maps to error" {
        val p = DrivingUiMapper.presentation(
            CommandUiState(phase = CommandSessionPhase.RESPONDING, unclear = true)
        )
        p.visual shouldBe DrivingVisualState.ERROR
        p.labelResHint shouldBe DrivingLabel.UNCLEAR
    }

    "successful responding maps to success" {
        val p = DrivingUiMapper.presentation(
            CommandUiState(
                phase = CommandSessionPhase.RESPONDING,
                lastReply = "Đang mở YouTube…",
                unclear = false,
            )
        )
        p.visual shouldBe DrivingVisualState.SUCCESS
    }

    "downloading model surfaces a progress banner" {
        val status = DrivingUiMapper.modelStatus(
            org.stypox.dicio.io.input.SttState.Downloading(
                org.stypox.dicio.ui.util.Progress(0, 1, 25_000_000, 50_000_000)
            )
        )
        status!!.percent shouldBe 50
        DrivingUiMapper.modelStatus(org.stypox.dicio.io.input.SttState.Loaded) shouldBe null
        DrivingUiMapper.modelStatus(org.stypox.dicio.io.input.SttState.NotDownloaded) shouldBe
            DrivingModelStatus(org.stypox.dicio.R.string.carfu_vosk_missing)
        DrivingUiMapper.modelStatus(org.stypox.dicio.io.input.SttState.NotAvailable) shouldBe
            DrivingModelStatus(org.stypox.dicio.R.string.carfu_stt_android_unavailable)
    }
})
