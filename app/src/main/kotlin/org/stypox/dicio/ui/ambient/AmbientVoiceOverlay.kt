package org.stypox.dicio.ui.ambient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Minimal Ambient Voice HUD: centered ice-cyan wave + optional one-line raw transcript.
 * Transparent — no card, capsule, blur panel, or background fill.
 */
@Composable
fun AmbientVoiceOverlay(
    state: AmbientVoiceUiState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state.visible,
        enter = fadeIn(animationSpec = tween(AmbientVoicePresentation.FADE_MS)),
        exit = fadeOut(animationSpec = tween(AmbientVoicePresentation.FADE_MS)),
        modifier = modifier.testTag("ambient_voice_overlay"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val transcript = state.rawTranscript
            if (!transcript.isNullOrBlank()) {
                Text(
                    text = transcript,
                    color = AmbientVoiceColors.Transcript,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .width(320.dp)
                        .padding(bottom = 4.dp)
                        .testTag("ambient_voice_transcript"),
                    style = androidx.compose.ui.text.TextStyle(
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = AmbientVoiceColors.TranscriptShadow,
                            offset = Offset(0f, 1f),
                            blurRadius = 6f,
                        ),
                    ),
                )
            }
            AmbientWaveform(
                modifier = Modifier
                    .width(220.dp)
                    .height(24.dp)
                    .testTag("ambient_voice_wave"),
            )
        }
    }
}

@Composable
private fun AmbientWaveform(modifier: Modifier = Modifier) {
    // Slow, low-cost phase drift — not a high-frequency amplitude visualizer.
    val transition = rememberInfiniteTransition(label = "ambient_wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ambient_wave_phase",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val midY = h * 0.55f
        val amp = h * 0.28f
        val path = Path()
        val steps = 48
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val x = t * w
            // Fade envelope at both ends into transparency.
            val envelope = sin(t * PI).toFloat().coerceIn(0f, 1f)
            val y = midY + sin(t * 3.2f * PI + phase).toFloat() * amp * envelope
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        // Soft glow under the main stroke.
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    AmbientVoiceColors.Glow.copy(alpha = 0f),
                    AmbientVoiceColors.Glow.copy(alpha = 0.35f),
                    AmbientVoiceColors.Glow.copy(alpha = 0.35f),
                    AmbientVoiceColors.Glow.copy(alpha = 0f),
                ),
            ),
            style = Stroke(width = 6f, cap = StrokeCap.Round),
        )
        drawPath(
            path = path,
            brush = Brush.horizontalGradient(
                colors = listOf(
                    AmbientVoiceColors.WaveMain.copy(alpha = 0f),
                    AmbientVoiceColors.CenterBright.copy(alpha = 0.95f),
                    AmbientVoiceColors.WaveMain.copy(alpha = 0.9f),
                    AmbientVoiceColors.WaveMain.copy(alpha = 0f),
                ),
            ),
            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
        )
    }
}

/** Convenience wrapper for CommandSession-driven UI. */
@Composable
fun AmbientVoiceOverlayFromSession(
    phase: org.stypox.dicio.io.session.CommandSessionPhase,
    rawPartialTranscript: String?,
    modifier: Modifier = Modifier,
) {
    val state = AmbientVoicePresentation.fromSession(phase, rawPartialTranscript)
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        AmbientVoiceOverlay(state = state)
    }
}
