package org.stypox.dicio.io.session

import org.stypox.dicio.skills.carfu.CarfuDialer
import org.stypox.dicio.skills.carfu.CarfuLaunchSpec
import org.stypox.dicio.skills.carfu.CarfuSkillPlatform
import org.stypox.dicio.skills.carfu.SkillExecutionResult

/**
 * Phase-3 executor: consumes [CanonicalCommand] only.
 *
 * Does not re-parse transcripts. Does not startListening / create VoiceSession / rearm SR.
 */
class CanonicalCommandExecutor(
    private val platform: CarfuSkillPlatform,
    private val appResolver: InstalledAppResolver = InstalledAppResolver(
        listLaunchable = { platform.listLaunchableApps() },
        isLaunchable = { platform.isPackageLaunchable(it) },
    ),
    private val youtubePlayAuto: YouTubePlayAutoPort? = null,
) {
    data class ExecutionTrace(
        val speechVi: String,
        val actionTaken: Boolean,
        val geoUri: String? = null,
        val packageName: String? = null,
        val mediaQuery: String? = null,
        val mediaProvider: String? = null,
        val mediaData: String? = null,
        val reason: String = "",
    )

    fun execute(command: CanonicalCommand): SkillExecutionResult {
        val trace = executeTraced(command)
        return SkillExecutionResult(
            speechVi = trace.speechVi,
            actionTaken = trace.actionTaken,
        )
    }

    fun executeTraced(command: CanonicalCommand): ExecutionTrace {
        VoiceToActionLatency.mark(
            VoiceToActionStage.ACTION_REQUEST,
            "cmd=${command::class.simpleName}",
        )
        val speech = VietnameseCommandUnderstanding.confirmationSpeechVi(command).orEmpty()
        val trace = when (command) {
            is CanonicalCommand.Navigate -> executeNavigate(command, speech)
            is CanonicalCommand.OpenApp -> executeOpenApp(command, speech)
            is CanonicalCommand.PlayMedia -> executePlayMedia(command, speech)
            is CanonicalCommand.Volume -> executeVolume(command, speech)
            CanonicalCommand.Time -> ExecutionTrace(
                speechVi = platform.currentTimeSpeech(),
                actionTaken = true,
                reason = "time",
            )
            is CanonicalCommand.CallContact -> ExecutionTrace(
                speechVi = "Đang gọi ${command.contactName}.",
                actionTaken = false,
                reason = "call_delegated_legacy",
            )
        }
        CarfuLog.i(
            VoiceLifecycleLog.TAG,
            "PHASE4_EXECUTOR cmd=$command actionTaken=${trace.actionTaken} " +
                "tts=\"${trace.speechVi}\" geo=${trace.geoUri.orEmpty()} " +
                "pkg=${trace.packageName.orEmpty()} mediaQ=${trace.mediaQuery.orEmpty()} " +
                "mediaP=${trace.mediaProvider.orEmpty()} mediaData=${trace.mediaData.orEmpty()} " +
                "reason=${trace.reason}",
        )
        return trace
    }

    private fun executeNavigate(
        command: CanonicalCommand.Navigate,
        speech: String,
    ): ExecutionTrace {
        val destination = command.destination.trim()
        if (destination.isEmpty()) {
            return ExecutionTrace(
                speechVi = "Hãy nói nơi bạn muốn đến.",
                actionTaken = false,
                reason = "empty_destination",
            )
        }
        val uri = NavigatePayload.geoUri(destination)
        val spec = CarfuLaunchSpec(action = CarfuDialer.ACTION_VIEW, data = uri)
        if (CarfuDialer.isBlockedPackage(platform.resolveLaunch(spec))) {
            return ExecutionTrace(
                speechVi = "Không tìm thấy ứng dụng bản đồ.",
                actionTaken = false,
                geoUri = uri,
                reason = "maps_blocked",
            )
        }
        val ok = platform.startLaunch(spec)
        return if (ok) {
            ExecutionTrace(
                speechVi = speech.ifBlank { "Đang chỉ đường đến $destination" },
                actionTaken = true,
                geoUri = uri,
                reason = "navigate_ok",
            )
        } else {
            ExecutionTrace(
                speechVi = "Không tìm thấy ứng dụng bản đồ.",
                actionTaken = false,
                geoUri = uri,
                reason = "maps_missing",
            )
        }
    }

    private fun executeOpenApp(
        command: CanonicalCommand.OpenApp,
        speech: String,
    ): ExecutionTrace {
        return when (val resolved = appResolver.resolve(command.appName)) {
            AppResolveResult.NotFound -> {
                CarfuLog.i(
                    VoiceLifecycleLog.TAG,
                    "PHASE4_OPEN_APP requested=${command.appName} result=not_found",
                )
                ExecutionTrace(
                    speechVi = "Không tìm thấy ứng dụng ${command.appName}.",
                    actionTaken = false,
                    reason = "app_not_found",
                )
            }
            is AppResolveResult.Match -> {
                CarfuLog.i(
                    VoiceLifecycleLog.TAG,
                    "PHASE4_OPEN_APP requested=${command.appName} " +
                        "resolved=${resolved.displayName} pkg=${resolved.packageName} " +
                        "via=${resolved.via} score=${resolved.score}",
                )
                val ok = platform.launchPackage(resolved.packageName)
                if (ok) {
                    ExecutionTrace(
                        speechVi = speech.ifBlank { "Đang mở ${resolved.displayName}" },
                        actionTaken = true,
                        packageName = resolved.packageName,
                        reason = "open_${resolved.via}",
                    )
                } else {
                    ExecutionTrace(
                        speechVi = "Không mở được ${resolved.displayName}.",
                        actionTaken = false,
                        packageName = resolved.packageName,
                        reason = "launch_failed",
                    )
                }
            }
        }
    }

    private fun executePlayMedia(
        command: CanonicalCommand.PlayMedia,
        speech: String,
    ): ExecutionTrace {
        return when (val built = MediaProviderExecutor.build(command.query, command.provider)) {
            is MediaProviderExecutor.Result.Unsupported -> ExecutionTrace(
                speechVi = "Chưa hỗ trợ phát nhạc trên ${command.provider ?: "nhà cung cấp này"}.",
                actionTaken = false,
                mediaQuery = command.query,
                mediaProvider = command.provider,
                reason = built.reason,
            )
            is MediaProviderExecutor.Result.YouTubePlayAuto ->
                executeYouTubePlayAuto(command, speech, built.query)
            is MediaProviderExecutor.Result.Ok -> {
                var launch = built.launch
                if (launch.spec.packageName != null &&
                    !platform.isPackageLaunchable(launch.spec.packageName!!)
                ) {
                    launch = MediaProviderExecutor.youtubeSearchGeneric(command.query)
                }
                val ok = platform.startLaunch(launch.spec)
                if (ok) {
                    ExecutionTrace(
                        speechVi = speech.ifBlank {
                            VietnameseCommandUnderstanding.confirmationSpeechVi(command).orEmpty()
                        },
                        actionTaken = true,
                        mediaQuery = launch.query,
                        mediaProvider = launch.provider,
                        mediaData = launch.spec.data,
                        packageName = launch.spec.packageName,
                        reason = if (launch.searchRoute) "youtube_search" else "media_ok",
                    )
                } else {
                    ExecutionTrace(
                        speechVi = "Không mở được ${launch.provider}.",
                        actionTaken = false,
                        mediaQuery = launch.query,
                        mediaProvider = launch.provider,
                        mediaData = launch.spec.data,
                        reason = "media_launch_failed",
                    )
                }
            }
        }
    }

    private fun executeYouTubePlayAuto(
        command: CanonicalCommand.PlayMedia,
        speech: String,
        query: String,
    ): ExecutionTrace {
        val port = youtubePlayAuto
        if (port == null) {
            return ExecutionTrace(
                speechVi = "Không phát được bài trên YouTube.",
                actionTaken = false,
                mediaQuery = query,
                mediaProvider = "YouTube",
                reason = "youtube_playauto_unconfigured",
            )
        }
        val result = port.play(query)
        val searchLeak = result.watchUrl?.contains("search_query=") == true ||
            result.searchOpened ||
            result.accessibilityFallbackUsed
        if (result.launched &&
            result.launchCount == 1 &&
            result.playAutoRequestCount == 1 &&
            !searchLeak &&
            !result.watchUrl.isNullOrBlank()
        ) {
            return ExecutionTrace(
                speechVi = speech.ifBlank {
                    VietnameseCommandUnderstanding.confirmationSpeechVi(command).orEmpty()
                },
                actionTaken = true,
                mediaQuery = result.query,
                mediaProvider = "YouTube",
                mediaData = result.watchUrl,
                packageName = "com.google.android.youtube",
                reason = "youtube_playauto_direct_target",
            )
        }
        return ExecutionTrace(
            speechVi = youtubeFailureSpeech(result.failure ?: result.resolverStatus),
            actionTaken = false,
            mediaQuery = result.query.ifBlank { query },
            mediaProvider = "YouTube",
            mediaData = null,
            reason = result.failure ?: result.resolverStatus ?: "youtube_playauto_failed",
        )
    }

    private fun youtubeFailureSpeech(status: String?): String = when (status) {
        "NO_RESULTS" -> "Không tìm thấy bài trên YouTube."
        "NETWORK_UNAVAILABLE", "TIMEOUT" -> "Không kết nối được YouTube."
        "QUOTA_EXCEEDED" -> "YouTube tạm thời quá tải."
        else -> "Không phát được bài trên YouTube."
    }

    private fun executeVolume(
        command: CanonicalCommand.Volume,
        speech: String,
    ): ExecutionTrace {
        val ok = platform.adjustVolume(command.up)
        return ExecutionTrace(
            speechVi = if (ok) {
                speech.ifBlank {
                    if (command.up) "Đang tăng âm lượng" else "Đang giảm âm lượng"
                }
            } else {
                "Không điều chỉnh được âm lượng."
            },
            actionTaken = ok,
            reason = "volume",
        )
    }
}
