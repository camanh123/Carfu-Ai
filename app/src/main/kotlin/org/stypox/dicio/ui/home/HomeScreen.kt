package org.stypox.dicio.ui.home

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import dev.shreyaspatil.permissionflow.compose.rememberPermissionFlowRequestLauncher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.dicio.skill.skill.Permission
import org.stypox.dicio.io.input.InputEvent
import org.stypox.dicio.io.session.CommandUiState
import org.stypox.dicio.probe.CarfuProbeActivity
import org.stypox.dicio.ui.driving.DrivingScreen
import org.stypox.dicio.util.checkPermissions
import org.stypox.dicio.util.getNonGrantedSecurePermissions

@Composable
fun HomeScreen(
    onSettingsClick: () -> Unit,
) {
    val channel = remember { Channel<Boolean>() }
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberPermissionFlowRequestLauncher { isGranted ->
        coroutineScope.launch {
            channel.send(isGranted.values.all { it })
        }
    }
    val context = LocalContext.current

    suspend fun requestPermissions(permissions: List<Permission>): Boolean {
        val nonGrantedSecurePermissions = getNonGrantedSecurePermissions(
                context,
                permissions.filterIsInstance<Permission.SecurePermission>()
        )
        if (nonGrantedSecurePermissions.isNotEmpty()) {
            return false
        }

        val normalPermissions = permissions.filterIsInstance<Permission.NormalPermission>()
            .map { it.id }.toTypedArray()
        if (checkPermissions(context, *normalPermissions)) {
            return true
        }

        launcher.launch(normalPermissions)
        return channel.receive()
    }

    val viewModel: HomeScreenViewModel = hiltViewModel()
    viewModel.skillEvaluator.permissionRequester = ::requestPermissions

    val interactionsState = viewModel.skillEvaluator.state.collectAsState()
    val sttState = viewModel.sttInputDevice.uiState.collectAsState()
    val commandUi = viewModel.commandSession.ui.collectAsState()

    val lastQa = interactionsState.value.interactions.lastOrNull()
        ?.questionsAnswers?.lastOrNull()
    val lastCommand = commandUi.value.lastHeard
        ?: interactionsState.value.pendingQuestion?.userInput
        ?: lastQa?.question
    val lastReply = commandUi.value.lastReply

    DrivingScreen(
        commandUi = commandUi.value,
        sttState = sttState.value,
        lastCommand = lastCommand,
        lastReply = lastReply,
        onMicClick = {
            viewModel.sttInputDevice.onClick(viewModel.skillEvaluator::processInputEvent)
        },
        onSettingsClick = onSettingsClick,
        onDiagnosticsClick = {
            context.startActivity(Intent(context, CarfuProbeActivity::class.java))
        },
    )
}

@Composable
fun HomeScreenPreviewHost(
    commandUi: CommandUiState = CommandUiState(),
    lastCommand: String? = null,
    lastReply: String? = null,
) {
    DrivingScreen(
        commandUi = commandUi,
        sttState = null,
        lastCommand = lastCommand,
        lastReply = lastReply,
        onMicClick = {},
        onSettingsClick = {},
        onDiagnosticsClick = {},
    )
}
