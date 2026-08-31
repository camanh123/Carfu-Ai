package org.stypox.dicio.ui.driving

import org.stypox.dicio.R
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.CommandUiState
import org.stypox.dicio.ui.util.Progress

enum class DrivingVisualState {
    READY,
    ACKNOWLEDGING,
    LISTENING,
    PROCESSING,
    SUCCESS,
    ERROR,
}

data class DrivingPresentation(
    val visual: DrivingVisualState,
    val labelResHint: DrivingLabel,
    val showPartial: Boolean,
)

enum class DrivingLabel {
    READY,
    ACK,
    LISTENING,
    PROCESSING,
    UNCLEAR,
}

data class DrivingModelStatus(
    val textRes: Int,
    val percent: Int? = null,
)

object DrivingUiMapper {
    fun presentation(ui: CommandUiState): DrivingPresentation {
        return when (ui.phase) {
            CommandSessionPhase.IDLE_WAKE -> DrivingPresentation(
                visual = DrivingVisualState.READY,
                labelResHint = DrivingLabel.READY,
                showPartial = false,
            )
            CommandSessionPhase.WAKE_DETECTED,
            CommandSessionPhase.ACKNOWLEDGING -> DrivingPresentation(
                visual = DrivingVisualState.ACKNOWLEDGING,
                labelResHint = DrivingLabel.ACK,
                showPartial = false,
            )
            CommandSessionPhase.COMMAND_LISTENING -> DrivingPresentation(
                visual = DrivingVisualState.LISTENING,
                labelResHint = DrivingLabel.LISTENING,
                showPartial = true,
            )
            CommandSessionPhase.PROCESSING -> DrivingPresentation(
                visual = DrivingVisualState.PROCESSING,
                labelResHint = DrivingLabel.PROCESSING,
                showPartial = false,
            )
            CommandSessionPhase.RESPONDING -> {
                if (ui.unclear || ui.lastReply.isNullOrBlank()) {
                    DrivingPresentation(
                        visual = DrivingVisualState.ERROR,
                        labelResHint = DrivingLabel.UNCLEAR,
                        showPartial = false,
                    )
                } else {
                    DrivingPresentation(
                        visual = DrivingVisualState.SUCCESS,
                        labelResHint = DrivingLabel.READY,
                        showPartial = false,
                    )
                }
            }
            CommandSessionPhase.RETURNING_TO_WAKE -> DrivingPresentation(
                visual = DrivingVisualState.READY,
                labelResHint = DrivingLabel.READY,
                showPartial = false,
            )
        }
    }

    fun modelStatus(sttState: SttState?): DrivingModelStatus? {
        return when (sttState) {
            SttState.NotDownloaded -> DrivingModelStatus(R.string.carfu_vosk_missing)
            is SttState.Downloading -> DrivingModelStatus(
                textRes = R.string.carfu_vosk_downloading,
                percent = progressPercent(sttState.progress),
            )
            is SttState.Unzipping -> DrivingModelStatus(R.string.carfu_vosk_unpacking)
            is SttState.ErrorDownloading,
            is SttState.ErrorUnzipping,
            is SttState.ErrorLoading -> DrivingModelStatus(R.string.carfu_vosk_error)
            SttState.NotAvailable -> DrivingModelStatus(R.string.carfu_stt_android_unavailable)
            else -> null
        }
    }

    fun progressPercent(progress: Progress): Int {
        if (progress.totalBytes <= 0L) return 0
        return ((progress.currentBytes * 100L) / progress.totalBytes).toInt().coerceIn(0, 100)
    }
}
