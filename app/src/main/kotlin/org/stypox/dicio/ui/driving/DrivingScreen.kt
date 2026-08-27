package org.stypox.dicio.ui.driving

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.stypox.dicio.R
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.session.CommandUiState
import java.util.Date

private val Charcoal = Color(0xFF0B0D0C)
private val CharcoalElevated = Color(0xFF141816)
private val Lime = Color(0xFFC6F54A)
private val Amber = Color(0xFFE8B84A)
private val ErrorRed = Color(0xFFE07A7A)
private val TextPrimary = Color(0xFFF4F7F2)
private val TextMuted = Color(0xFFA8B3A4)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DrivingScreen(
    commandUi: CommandUiState,
    sttState: SttState?,
    lastCommand: String?,
    lastReply: String?,
    onMicClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onDiagnosticsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = remember(commandUi) { DrivingUiMapper.presentation(commandUi) }
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    var online by remember { mutableStateOf(isOnline(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            online = isOnline(context)
            delay(15_000)
        }
    }

    val label = when (presentation.labelResHint) {
        DrivingLabel.READY -> stringResource(R.string.carfu_state_ready)
        DrivingLabel.ACK -> stringResource(R.string.carfu_state_ack)
        DrivingLabel.LISTENING -> stringResource(R.string.carfu_state_listening)
        DrivingLabel.PROCESSING -> stringResource(R.string.carfu_state_processing)
        DrivingLabel.UNCLEAR -> stringResource(R.string.carfu_state_unclear)
    }
    val heard = if (presentation.showPartial) {
        commandUi.partial?.takeIf { it.isNotBlank() } ?: lastCommand
    } else {
        lastCommand
    }
    val reply = if (commandUi.unclear && lastReply.isNullOrBlank()) {
        stringResource(R.string.carfu_state_unclear)
    } else {
        lastReply
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Charcoal)
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        DrivingTopBar(
            online = online,
            micListening = presentation.visual == DrivingVisualState.LISTENING ||
                sttState == SttState.Listening,
            timeText = DateFormat.getTimeFormat(context).format(now),
            onSettingsClick = onSettingsClick,
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                AssistantOrb(
                    visual = presentation.visual,
                    onClick = onMicClick,
                    onLongClick = onDiagnosticsClick,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1.15f)
                    .fillMaxHeight()
                    .padding(start = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    color = TextPrimary,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(16.dp))
                if (!heard.isNullOrBlank()) {
                    Text(
                        text = heard,
                        color = Lime,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!reply.isNullOrBlank() && reply != heard) {
                    Text(
                        text = reply,
                        color = TextMuted,
                        fontSize = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuickAction(
                icon = Icons.Default.Directions,
                label = stringResource(R.string.carfu_action_nav),
                onClick = { DrivingQuickActions.navigate(context) },
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Default.MusicNote,
                label = stringResource(R.string.carfu_action_music),
                onClick = { DrivingQuickActions.music(context) },
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Default.Call,
                label = stringResource(R.string.carfu_action_call),
                onClick = { DrivingQuickActions.call(context) },
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Default.Apps,
                label = stringResource(R.string.carfu_action_apps),
                onClick = { DrivingQuickActions.apps(context) },
                modifier = Modifier.weight(1f),
            )
            QuickAction(
                icon = Icons.Default.VolumeUp,
                label = stringResource(R.string.carfu_action_volume),
                onClick = { DrivingQuickActions.volume(context) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrivingTopBar(
    online: Boolean,
    micListening: Boolean,
    timeText: String,
    onSettingsClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            color = TextPrimary,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(16.dp))
        StatusChip(
            text = stringResource(
                if (online) R.string.carfu_status_online else R.string.carfu_status_offline
            ),
            color = if (online) Lime else TextMuted,
        )
        Spacer(Modifier.width(8.dp))
        StatusChip(
            text = stringResource(
                if (micListening) R.string.carfu_mic_listening else R.string.carfu_mic_ready
            ),
            color = if (micListening) Lime else TextMuted,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = timeText,
            color = TextPrimary,
            fontSize = 22.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(CharcoalElevated)
                .combinedClickable(onClick = onSettingsClick)
                .testTag("settings_button"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = stringResource(R.string.settings),
                tint = TextPrimary,
                modifier = Modifier.size(32.dp),
            )
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(CharcoalElevated)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantOrb(
    visual: DrivingVisualState,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val target = when (visual) {
        DrivingVisualState.READY -> Color(0xFF3A4038)
        DrivingVisualState.ACKNOWLEDGING,
        DrivingVisualState.LISTENING -> Lime
        DrivingVisualState.PROCESSING -> Amber
        DrivingVisualState.SUCCESS -> Lime
        DrivingVisualState.ERROR -> ErrorRed
    }
    val color by animateColorAsState(target, label = "orb")
    val pulse = if (visual == DrivingVisualState.LISTENING ||
        visual == DrivingVisualState.ACKNOWLEDGING
    ) {
        val t = rememberInfiniteTransition(label = "orbPulse")
        t.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(900),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "orbScale",
        ).value
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(220.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.25f), Color.Transparent),
                )
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                role = Role.Button,
            )
            .testTag("assistant_orb"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.9f)),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(CharcoalElevated)
            .combinedClickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Lime,
            modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun isOnline(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } else {
        @Suppress("DEPRECATION")
        cm.activeNetworkInfo?.isConnected == true
    }
}
