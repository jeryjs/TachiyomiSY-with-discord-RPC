package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.CrashLogUtil
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import eu.kanade.tachiyomi.util.system.toast
import exh.syDebugVersion
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LinkIcon
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.icons.CustomIcons
import tachiyomi.presentation.core.icons.Discord
import tachiyomi.presentation.core.icons.Facebook
import tachiyomi.presentation.core.icons.Github
import tachiyomi.presentation.core.icons.Reddit
import tachiyomi.presentation.core.icons.X
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

enum class UpdateCheckState {
    NotChecked,
    Checking,
    Checked,
    Downloading,
    Downloaded,
    Error,
}

object AboutScreen : Screen() {

    @Composable
    override fun Content() {
        val scope = rememberCoroutineScope()
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        var updateCheckState by remember { mutableStateOf(UpdateCheckState.NotChecked) }

        // SY -->
        var showWhatsNewDialog by remember { mutableStateOf(false) }
        // SY <--

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            // SY -->
            val workManager = WorkManager.getInstance(context)
            var appUpdateWorkInfo by remember { mutableStateOf<WorkInfo?>(null) }

            // Observe the work info for app update download job
            LaunchedEffect(Unit) {
                callbackFlow {
                    val liveData = workManager.getWorkInfosForUniqueWorkLiveData(AppUpdateDownloadJob.TAG)
                    val observer: (List<WorkInfo>) -> Unit = { list -> trySend(list) }
                    liveData.observeForever(observer)
                    awaitClose { liveData.removeObserver(observer) }
                }.collectLatest { appUpdateWorkInfo = it.firstOrNull() }
            }

            // Determine the update check state based on the appUpdateDownload Job state
            updateCheckState = when (appUpdateWorkInfo?.state) {
                WorkInfo.State.RUNNING -> UpdateCheckState.Downloading
                WorkInfo.State.FAILED -> UpdateCheckState.Error
                WorkInfo.State.CANCELLED -> UpdateCheckState.NotChecked
                WorkInfo.State.SUCCEEDED -> {
                    // Only show 'Downloaded' if user checked for updates this session
                    if (updateCheckState != UpdateCheckState.NotChecked) UpdateCheckState.Downloaded else updateCheckState
                }

                else -> updateCheckState
            }
            // SY <--

            ScrollbarLazyColumn(
                contentPadding = contentPadding,
            ) {
                item {
                    LogoHeader()
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.version),
                        subtitle = getVersionName(withBuildDate = true),
                        onPreferenceClick = {
                            val deviceInfo = CrashLogUtil(context).getDebugInfo()
                            context.copyToClipboard("Debug information", deviceInfo)
                        },
                    )
                }

                if (BuildConfig.INCLUDE_UPDATER) {
                    item {
                        Column {
                            TextPreferenceWidget(
                                title = stringResource(
                                    when (updateCheckState) {
                                        UpdateCheckState.Downloading -> MR.strings.update_check_notification_download_in_progress
                                        UpdateCheckState.Downloaded -> MR.strings.update_check_notification_download_complete
                                        UpdateCheckState.Error -> MR.strings.update_check_notification_download_error
                                        else -> MR.strings.check_for_updates
                                    },
                                ),
                                widget = {
                                    AnimatedVisibility(visible = updateCheckState == UpdateCheckState.Checking) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            strokeWidth = 3.dp,
                                        )
                                    }
                                },
                                onPreferenceClick = {
                                    when (updateCheckState) {
                                        UpdateCheckState.Downloaded -> {
                                            // Launch APK installer directly
                                            val apkFileUri =
                                                File(context.externalCacheDir, "update.apk").getUriCompat(context)
                                            NotificationHandler.installApkPendingActivity(context, apkFileUri).send()
                                        }
                                        UpdateCheckState.Checking, UpdateCheckState.Downloading -> Unit // No action needed
                                        else -> {
                                            scope.launch {
                                                updateCheckState = UpdateCheckState.Checking
                                                checkVersion(
                                                    context = context,
                                                    onAvailableUpdate = { result ->
                                                        val updateScreen = NewUpdateScreen(
                                                            versionName = result.release.version,
                                                            changelogInfo = result.release.info,
                                                            releaseLink = result.release.releaseLink,
                                                            downloadLink = result.release.getDownloadLink(),
                                                        )
                                                        navigator.push(updateScreen)
                                                    },
                                                    onFinish = { hasUpdate ->
                                                        // reset state if no update is available
                                                        updateCheckState = if (hasUpdate) UpdateCheckState.Checked else UpdateCheckState.NotChecked
                                                    },
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                            AnimatedVisibility(visible = updateCheckState == UpdateCheckState.Downloading) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 36.dp),
                                    progress = {
                                        (appUpdateWorkInfo?.progress?.getInt("progress", 0))?.div(100f) ?: 0f
                                    },
                                )
                            }
                        }
                    }
                }

                if (!BuildConfig.DEBUG) {
                    item {
                        TextPreferenceWidget(
                            title = stringResource(MR.strings.whats_new),
                            // SY -->
                            onPreferenceClick = { showWhatsNewDialog = true },
                            // SY <--
                        )
                    }
                }

                // item {
                //     TextPreferenceWidget(
                //         title = stringResource(MR.strings.help_translate),
                //         onPreferenceClick = { uriHandler.openUri("https://mihon.app/docs/contribute#translation") },
                //     )
                // }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.licenses),
                        onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                    )
                }

                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.privacy_policy),
                        onPreferenceClick = { uriHandler.openUri("https://mihon.app/privacy/") },
                    )
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        LinkIcon(
                            label = stringResource(MR.strings.website),
                            icon = Icons.Outlined.Public,
                            url = "https://mihon.app",
                        )
                        LinkIcon(
                            label = "Discord",
                            icon = CustomIcons.Discord,
                            url = "https://discord.gg/mihon",
                        )
                        LinkIcon(
                            label = "X",
                            icon = CustomIcons.X,
                            url = "https://x.com/mihonapp",
                        )
                        LinkIcon(
                            label = "Facebook",
                            icon = CustomIcons.Facebook,
                            url = "https://facebook.com/mihonapp",
                        )
                        LinkIcon(
                            label = "Reddit",
                            icon = CustomIcons.Reddit,
                            url = "https://www.reddit.com/r/mihonapp",
                        )
                        LinkIcon(
                            label = "GitHub",
                            icon = CustomIcons.Github,
                            // SY -->
                            url = "https://github.com/jeryjs/TachiyomiSY-with-discord-RPC",
                            // SY <--
                        )
                    }
                }
            }
        }

        // SY -->
        if (showWhatsNewDialog) {
            WhatsNewDialog(onDismissRequest = { showWhatsNewDialog = false })
        }
        // SY <--
    }

    /**
     * Checks version and shows a user prompt if an update is available.
     */
    private suspend fun checkVersion(
        context: Context,
        onAvailableUpdate: (GetApplicationRelease.Result.NewUpdate) -> Unit,
        onFinish: (Boolean) -> Unit,
    ) {
        val hasUpdate = mutableStateOf(false)
        val updateChecker = AppUpdateChecker()
        withUIContext {
            try {
                when (val result = withIOContext { updateChecker.checkForUpdate(context, forceCheck = true  ) }) {
                    is GetApplicationRelease.Result.NewUpdate -> {
                        onAvailableUpdate(result)
                        hasUpdate.value = true
                    }
                    is GetApplicationRelease.Result.NoNewUpdate -> {
                        context.toast(MR.strings.update_check_no_new_updates)
                    }
                    is GetApplicationRelease.Result.OsTooOld -> {
                        context.toast(MR.strings.update_check_eol)
                    }
                }
            } catch (e: Exception) {
                context.toast(e.message)
                logcat(LogPriority.ERROR, e)
            } finally {
                onFinish(hasUpdate.value)
            }
        }
    }

    fun getVersionName(withBuildDate: Boolean): String {
        return when {
            BuildConfig.DEBUG -> {
                "Debug ${BuildConfig.COMMIT_SHA}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
            // SY -->
            isPreviewBuildType -> {
                "Preview r$syDebugVersion".let {
                    if (withBuildDate) {
                        "$it (${BuildConfig.COMMIT_SHA}, ${getFormattedBuildTime()})"
                    } else {
                        "$it (${BuildConfig.COMMIT_SHA})"
                    }
                }
            }
            // SY <--
            else -> {
                "Stable ${BuildConfig.VERSION_NAME}".let {
                    if (withBuildDate) {
                        "$it (${getFormattedBuildTime()})"
                    } else {
                        it
                    }
                }
            }
        }
    }

    internal fun getFormattedBuildTime(): String {
        return try {
            LocalDateTime.ofInstant(
                Instant.parse(BuildConfig.BUILD_TIME),
                ZoneId.systemDefault(),
            )
                .toDateTimestampString(
                    UiPreferences.dateFormat(
                        Injekt.get<UiPreferences>().dateFormat().get(),
                    ),
                )
        } catch (e: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
