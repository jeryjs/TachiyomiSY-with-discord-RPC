package eu.kanade.tachiyomi.data.connections.discord

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.compose.ui.util.fastAny
import eu.kanade.domain.connections.service.ConnectionsPreferences
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.connections.ConnectionsManager
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.notificationBuilder
import kotlinx.coroutines.DelicateCoroutinesApi
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category.Companion.UNCATEGORIZED_ID
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class DiscordRPCService : Service() {

    private val connectionsManager: ConnectionsManager by injectLazy()

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        startOrResumeRpc()
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCORD_TOGGLE_PAUSE_RESUME -> {
                isPaused = !isPaused
                notification(this)
                if (isPaused) {
                    rpc?.closeRPC()
                } else {
                    startOrResumeRpc()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        NotificationReceiver.dismissNotification(this, Notifications.ID_DISCORD_RPC)
        rpc?.closeRPC()
        rpc = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun startOrResumeRpc() {
        val token = connectionsPreferences.connectionsToken(connectionsManager.discord).get()
        val status = when (connectionsPreferences.discordRPCStatus().get()) {
            -1 -> "dnd"
            0 -> "idle"
            else -> "online"
        }
        rpc = if (token.isNotBlank()) DiscordRPC(token, status) else null
        if (rpc != null) {
            launchIO {
                val cachedReaderData = lastReaderData
                if (cachedReaderData != null) {
                    setReaderActivity(this@DiscordRPCService, cachedReaderData)
                } else {
                    setScreen(this@DiscordRPCService, lastUsedScreen)
                }
            }
            notification(this)
        } else {
            connectionsPreferences.enableDiscordRPC().set(false)
        }
    }

    private fun notification(context: Context) {
        val toggleIcon = if (isPaused) R.drawable.ic_play_arrow_24dp else R.drawable.ic_pause_24dp
        val toggleText = if (isPaused) getString(R.string.action_resume) else getString(R.string.action_pause)
        val builder = context.notificationBuilder(Notifications.CHANNEL_DISCORD_RPC) {
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            setSmallIcon(R.drawable.ic_discord_24dp)
            setContentText(context.resources.getString(R.string.pref_discord_rpc))
            setAutoCancel(false)
            setOngoing(true)
            setWhen(since)
            setShowWhen(true)
            setUsesChronometer(true)
            addAction(toggleIcon, toggleText, togglePauseResumePendingIntent(context))
            addAction(R.drawable.ic_format_list_numbered_24dp, getString(R.string.status), openStatusDialogPendingIntent(context))
        }

        try {
            startForeground(Notifications.ID_DISCORD_RPC, builder.build())
        } catch (_: Exception) {
            rpc?.closeRPC()
            rpc = null
            stopSelf()
        }
    }

    private fun togglePauseResumePendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, DiscordRPCService::class.java).apply {
            this.action = ACTION_DISCORD_TOGGLE_PAUSE_RESUME
        }
        return PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openStatusDialogPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = ACTION_DISCORD_SET_STATUS
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {

        private val connectionsPreferences: ConnectionsPreferences by injectLazy()

        internal var rpc: DiscordRPC? = null

        internal var isPaused = false

        private val handler = Handler(Looper.getMainLooper())

        const val ACTION_DISCORD_TOGGLE_PAUSE_RESUME = "eu.kanade.tachiyomi.action.DISCORD_RPC_TOGGLE_PAUSE_RESUME"
        const val ACTION_DISCORD_SET_STATUS = "eu.kanade.tachiyomi.action.DISCORD_SET_STATUS"

        private var since = 0L

        private var lastReaderData: ReaderData? = null

        internal var lastUsedScreen = DiscordScreen.APP
            set(value) {
                field = if (value == DiscordScreen.MANGA || value == DiscordScreen.WEBVIEW) field else value
            }

        fun start(context: Context) {
            handler.removeCallbacksAndMessages(null)
            if (rpc == null && connectionsPreferences.enableDiscordRPC().get()) {
                since = System.currentTimeMillis()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(Intent(context, DiscordRPCService::class.java))
                } else {
                    context.startService(Intent(context, DiscordRPCService::class.java))
                }
            }
        }

        fun stop(context: Context, delay: Long = 30000L) {
            handler.postDelayed(
                { context.stopService(Intent(context, DiscordRPCService::class.java)) },
                delay,
            )
        }

        internal suspend fun setScreen(
            context: Context,
            discordScreen: DiscordScreen,
            readerData: ReaderData = ReaderData(),
        ) {
            lastUsedScreen = discordScreen

            if (discordScreen != DiscordScreen.MANGA && discordScreen != DiscordScreen.WEBVIEW) {
                lastReaderData = null
            }

            if (rpc == null || isPaused) return

            val name = context.resources.getString(discordScreen.text)

            val details = readerData.mangaTitle ?: context.resources.getString(discordScreen.details)

            val state = if (readerData.incognitoMode) {
                context.resources.getString(R.string.comic)
            } else {
                readerData.chapterTitle ?: context.resources.getString(discordScreen.state)
            }

            val imageUrl = readerData.thumbnailUrl ?: discordScreen.imageUrl

            rpc!!.updateRPC(
                activity = Activity(
                    name = name,
                    details = details,
                    state = state,
                    type = 0,
                    timestamps = Activity.Timestamps(start = since),
                    assets = Activity.Assets(
                        largeImage = "mp:$imageUrl",
                        smallImage = "mp:${DiscordScreen.APP.imageUrl}",
                        smallText = context.resources.getString(DiscordScreen.APP.text),
                    ),
                ),
                since = since,
            )
        }

        internal suspend fun setReaderActivity(context: Context, readerData: ReaderData = ReaderData()) {
            if (readerData.thumbnailUrl != null && readerData.mangaId != null) {
                lastReaderData = readerData
            }

            if (rpc == null || isPaused || readerData.thumbnailUrl == null || readerData.mangaId == null) return

            val categoryIds = Injekt.get<GetCategories>()
                .await(readerData.mangaId)
                .map { it.id.toString() }
                .run { ifEmpty { plus(UNCATEGORIZED_ID.toString()) } }

            val discordIncognitoMode = connectionsPreferences.discordRPCIncognito().get()
            val incognitoCategories = connectionsPreferences.discordRPCIncognitoCategories().get()

            val incognitoCategory = categoryIds.fastAny {
                it in incognitoCategories
            }

            val discordIncognito = discordIncognitoMode || readerData.incognitoMode || incognitoCategory

            val mangaTitle = readerData.mangaTitle.takeUnless { discordIncognito }

            val chapterTitle = readerData.chapterTitle?.let {
                when {
                    discordIncognito -> null
                    connectionsPreferences.useChapterTitles().get() -> it
                    else -> readerData.chapterNumber.let {
                        context.resources.getString(
                            R.string.display_mode_chapter,
                            formatChapterNumber(it.first.toDouble()),
                        ) + "/${it.second}"
                    }
                }
            }

            withIOContext {
                val networkService: NetworkHelper by injectLazy()
                val client = networkService.client
                val response = if (!discordIncognito) {
                    try {
                        client.newCall(GET("https://kizzy-api.cjjdxhdjd.workers.dev/image?url=${readerData.thumbnailUrl}")).execute()
                    } catch (e: Throwable) {
                        null
                    }
                } else {
                    null
                }

                val mangaThumbnail = response?.body?.string()
                    ?.takeIf { !it.contains("external/Not Found") }
                    ?.substringAfter("\"id\": \"")?.substringBefore("\"}")
                    ?.split("external/")?.getOrNull(1)?.let { "external/$it" }

                setScreen(
                    context = context,
                    discordScreen = DiscordScreen.MANGA,
                    readerData = ReaderData(
                        mangaTitle = mangaTitle,
                        chapterTitle = chapterTitle,
                        thumbnailUrl = mangaThumbnail,
                    ),
                )
            }
        }
    }
}
