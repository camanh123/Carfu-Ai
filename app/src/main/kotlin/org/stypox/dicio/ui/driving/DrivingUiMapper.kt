package org.stypox.dicio.ui.driving

import org.stypox.dicio.io.session.CommandSessionPhase
import org.stypox.dicio.io.session.CommandUiState

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
}
