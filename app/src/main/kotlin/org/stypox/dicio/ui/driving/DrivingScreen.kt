package org.stypox.dicio.ui.driving

import android.content.Context
import android.media.AudioManager
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.stypox.dicio.R
import org.stypox.dicio.io.input.SttState
import org.stypox.dicio.io.session.CarfuDiag
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
    var volumePanelVisible by remember { mutableStateOf(false) }
    var volumeIdleToken by remember { mutableLongStateOf(0L) }
    var banner by remember { mutableStateOf<String?>(null) }
    val volumeController = remember(context) { androidVolumeController(context) }

    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            online = isOnline(context)
            delay(15_000)
        }
    }
    LaunchedEffect(banner) {
        if (banner != null) {
            delay(3_000)
            banner = null
        }
    }
    LaunchedEffect(volumePanelVisible, volumeIdleToken) {
        if (!volumePanelVisible) return@LaunchedEffect
        delay(DrivingVolumePolicy.AUTO_DISMISS_IDLE_MS)
        volumePanelVisible = false
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
    val musicMissing = stringResource(R.string.carfu_music_not_found)
    val volumeFailed = stringResource(R.string.carfu_volume_failed)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
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
                    .height(DrivingQuickActions.TILE_HEIGHT_DP.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAction(
                    id = "nav",
                    icon = Icons.Default.Directions,
                    label = stringResource(R.string.carfu_action_nav),
                    onClick = { DrivingQuickActions.navigate(context) },
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    id = "music",
                    icon = Icons.Default.MusicNote,
                    label = stringResource(R.string.carfu_action_music),
                    onClick = {
                        when (DrivingQuickActions.music(context)) {
                            is MusicLaunchResult.Launched -> Unit
                            MusicLaunchResult.NotFound -> banner = musicMissing
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    id = "call",
                    icon = Icons.Default.Call,
                    label = stringResource(R.string.carfu_action_call),
                    onClick = { DrivingQuickActions.call(context) },
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    id = "apps",
                    icon = Icons.Default.Apps,
                    label = stringResource(R.string.carfu_action_apps),
                    onClick = { DrivingQuickActions.apps(context) },
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    id = "volume",
                    icon = Icons.Default.VolumeUp,
                    label = stringResource(R.string.carfu_action_volume),
                    onClick = {
                        check(DrivingQuickActions.volumeTileAction() ==
                            VolumeTileAction.SHOW_IN_APP_CONTROLLER)
                        CarfuDiag.quick("volume controller shown")
                        volumePanelVisible = true
                        volumeIdleToken += 1
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (!banner.isNullOrBlank()) {
            Text(
                text = banner!!,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(CharcoalElevated)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("quick_action_banner"),
            )
        }

        if (volumePanelVisible) {
            VolumeOverlay(
                controller = volumeController,
                failedMessage = volumeFailed,
                onInteract = { volumeIdleToken += 1 },
                onDismiss = { volumePanelVisible = false },
                onBanner = { banner = it },
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
                    colors = listOf(
                        color.copy(alpha = 0.95f),
                        color.copy(alpha = 0.25f),
                        Color.Transparent,
                    ),
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
    id: String,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .heightIn(min = DrivingQuickActions.MIN_TOUCH_DP.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(CharcoalElevated)
            .semantics(mergeDescendants = true) {
                this.role = Role.Button
                if (!enabled) disabled()
            }
            .combinedClickable(enabled = enabled, onClick = onClick, role = Role.Button)
            .testTag("quick_action_$id"),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VolumeOverlay(
    controller: DrivingVolumeController,
    failedMessage: String,
    onInteract: () -> Unit,
    onDismiss: () -> Unit,
    onBanner: (String) -> Unit,
) {
    var snapshot by remember {
        mutableStateOf(controller.current())
    }
    val ok = snapshot as? VolumeOpResult.Ok
    val volumeText = if (ok == null) {
        "--"
    } else {
        DrivingVolumePolicy.displayVolume(ok.volume, 15, ok.muted)
    }
    val muteLabel = if (ok?.muted == true || (ok?.volume ?: 1) <= 0) {
        stringResource(R.string.carfu_volume_unmute)
    } else {
        stringResource(R.string.carfu_volume_mute)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .combinedClickable(onClick = onDismiss)
            .testTag("volume_overlay"),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 1100.dp)
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .background(CharcoalElevated)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VolumeChip(
                label = stringResource(R.string.carfu_volume_down),
                tag = "volume_down",
                onClick = {
                    onInteract()
                    when (val result = controller.quieter()) {
                        is VolumeOpResult.Ok -> snapshot = result
                        VolumeOpResult.Failed -> onBanner(failedMessage)
                    }
                },
            )
            Text(
                text = volumeText,
                color = Lime,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 80.dp)
                    .testTag("volume_value"),
            )
            VolumeChip(
                label = stringResource(R.string.carfu_volume_up),
                tag = "volume_up",
                onClick = {
                    onInteract()
                    when (val result = controller.louder()) {
                        is VolumeOpResult.Ok -> snapshot = result
                        VolumeOpResult.Failed -> onBanner(failedMessage)
                    }
                },
            )
            VolumeChip(
                label = muteLabel,
                tag = "volume_mute",
                onClick = {
                    onInteract()
                    when (val result = controller.toggleMute()) {
                        is VolumeOpResult.Ok -> snapshot = result
                        VolumeOpResult.Failed -> onBanner(failedMessage)
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VolumeChip(
    label: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .heightIn(min = DrivingVolumePolicy.MIN_TOUCH_TARGET_DP.dp)
            .widthIn(min = DrivingVolumePolicy.MIN_TOUCH_TARGET_DP.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Charcoal)
            .combinedClickable(onClick = onClick, role = Role.Button)
            .padding(horizontal = 12.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

private fun androidVolumeController(context: Context): DrivingVolumeController {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return DrivingVolumeController(
        getVolume = { audio.getStreamVolume(AudioManager.STREAM_MUSIC) },
        getMax = { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) },
        isMuted = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audio.isStreamMute(AudioManager.STREAM_MUSIC)
            } else {
                audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
            }
        },
        setVolume = { value ->
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        },
        setMuted = { muted ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                    0,
                )
            } else if (muted) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            }
        },
    )
}

private fun isOnline(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
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
