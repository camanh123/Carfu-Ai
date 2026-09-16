package org.stypox.dicio.smarttubeplayauto

/**
 * Candidate Android Intent forms for opening a resolved YouTube video in SmartTube.
 *
 * SOURCE-PROVEN for SmartTube: none. These are diagnostic candidates only.
 * YouTube's proven form is ACTION_VIEW + https://www.youtube.com/watch?v=<id>;
 * whether SmartTube handles that (or youtu.be / vnd.youtube / MAIN) is unknown
 * until a CARFU device probe reports resolveActivity + launch outcome.
 */
enum class SmartTubeLaunchForm {
    VIEW_WATCH_URL_PINNED,
    VIEW_WATCH_URL_UNPINNED,
    VIEW_YOUTU_BE_PINNED,
    VIEW_VND_YOUTUBE_PINNED,
    MAIN_LAUNCHER,
}

object SmartTubeLaunchAudit {
    const val SOURCE_PROVEN = false
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val WATCH_HOST_PATH = "https://www.youtube.com/watch?v="
    const val YOUTU_BE_HOST_PATH = "https://youtu.be/"
    const val VND_YOUTUBE_SCHEME = "vnd.youtube"
    const val UNPROVEN = "smarttube_intent_unproven"
    const val EVIDENCE =
        "no_source_proof_that_smarttube_handles_youtube_watch_url_youtu_be_or_vnd_youtube"
}

data class SmartTubeLaunchSpec(
    val action: String,
    val uri: String?,
    val packageName: String?,
    val categories: List<String> = emptyList(),
    val form: SmartTubeLaunchForm,
    val exactVideoTargetRequested: Boolean,
    val videoId: String? = null,
    val resolvedTitle: String? = null,
)

sealed class SmartTubeIntentBuild {
    data class Ok(val spec: SmartTubeLaunchSpec) : SmartTubeIntentBuild()
    data class Failed(val reason: String) : SmartTubeIntentBuild()
}

object SmartTubeIntentBuilder {
    fun youtuBeUri(videoId: String): String = "${SmartTubeLaunchAudit.YOUTU_BE_HOST_PATH}$videoId"

    fun vndYoutubeUri(videoId: String): String = "${SmartTubeLaunchAudit.VND_YOUTUBE_SCHEME}:$videoId"

    fun build(
        form: SmartTubeLaunchForm,
        target: ResolvedMediaTarget,
        pinPackage: String?,
    ): SmartTubeIntentBuild {
        val id = org.stypox.dicio.youtubeplayauto.YouTubeVideoIdParser.parse(target.videoId)
            ?: return SmartTubeIntentBuild.Failed("invalid_video_id")
        val watch = org.stypox.dicio.youtubeplayauto.YouTubeVideoIdParser.canonicalWatchUri(id)
        val title = target.resolvedTitle
        return when (form) {
            SmartTubeLaunchForm.VIEW_WATCH_URL_PINNED -> ok(
                action = SmartTubeLaunchAudit.ACTION_VIEW,
                uri = watch,
                pkg = pinPackage,
                form = form,
                exact = true,
                videoId = id,
                title = title,
            )
            SmartTubeLaunchForm.VIEW_WATCH_URL_UNPINNED -> ok(
                action = SmartTubeLaunchAudit.ACTION_VIEW,
                uri = watch,
                pkg = null,
                form = form,
                exact = true,
                videoId = id,
                title = title,
            )
            SmartTubeLaunchForm.VIEW_YOUTU_BE_PINNED -> ok(
                action = SmartTubeLaunchAudit.ACTION_VIEW,
                uri = youtuBeUri(id),
                pkg = pinPackage,
                form = form,
                exact = true,
                videoId = id,
                title = title,
            )
            SmartTubeLaunchForm.VIEW_VND_YOUTUBE_PINNED -> ok(
                action = SmartTubeLaunchAudit.ACTION_VIEW,
                uri = vndYoutubeUri(id),
                pkg = pinPackage,
                form = form,
                exact = true,
                videoId = id,
                title = title,
            )
            SmartTubeLaunchForm.MAIN_LAUNCHER -> ok(
                action = SmartTubeLaunchAudit.ACTION_MAIN,
                uri = null,
                pkg = pinPackage,
                form = form,
                exact = false,
                videoId = id,
                title = title,
                categories = listOf(SmartTubeLaunchAudit.CATEGORY_LAUNCHER),
            )
        }
    }

    private fun ok(
        action: String,
        uri: String?,
        pkg: String?,
        form: SmartTubeLaunchForm,
        exact: Boolean,
        videoId: String,
        title: String?,
        categories: List<String> = emptyList(),
    ): SmartTubeIntentBuild.Ok = SmartTubeIntentBuild.Ok(
        SmartTubeLaunchSpec(
            action = action,
            uri = uri,
            packageName = pkg,
            categories = categories,
            form = form,
            exactVideoTargetRequested = exact,
            videoId = videoId,
            resolvedTitle = title,
        ),
    )
}
