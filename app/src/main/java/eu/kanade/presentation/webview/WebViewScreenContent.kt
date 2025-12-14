package eu.kanade.presentation.webview

import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import com.kevinnzou.web.AccompanistWebChromeClient
import com.kevinnzou.web.AccompanistWebViewClient
import com.kevinnzou.web.LoadingState
import com.kevinnzou.web.WebContent
import com.kevinnzou.web.WebView
import com.kevinnzou.web.WebViewNavigator
import com.kevinnzou.web.WebViewState
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getHtml
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.time.delay
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.time.Duration
import kotlin.math.max

class WebViewWindow(webContent: WebContent, val navigator: WebViewNavigator) {
    var state by mutableStateOf(WebViewState(webContent))
    var popupMessage: Message? = null
        private set
    var webView: WebView? = null

    constructor(popupMessage: Message, navigator: WebViewNavigator) : this(WebContent.NavigatorOnly, navigator) {
        this.popupMessage = popupMessage
    }
}

@Composable
fun WebViewScreenContent(
    onNavigateUp: () -> Unit,
    initialTitle: String?,
    url: String,
    onShare: (String) -> Unit,
    onOpenInBrowser: (String) -> Unit,
    onClearCookies: (String) -> Unit,
    headers: Map<String, String> = emptyMap(),
    onUrlChange: (String) -> Unit = {},
) {
    val coroutineScope = rememberCoroutineScope()

    val windowStack = remember {
        mutableStateStackOf(
            WebViewWindow(
                WebContent.Url(url = url, additionalHttpHeaders = headers),
                WebViewNavigator(coroutineScope),
            ),
        )
    }

    val currentWindow = windowStack.lastItemOrNull!!
    val navigator = currentWindow.navigator

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    var currentUrl by remember { mutableStateOf(url) }
    var showCloudflareHelp by remember { mutableStateOf(false) }
    var showScrollButtons by remember { mutableIntStateOf(0) }
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    val webClient = remember {
        object : AccompanistWebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                scope.launch {
                    val html = view.getHtml()
                    showCloudflareHelp = "window._cf_chl_opt" in html || "Ray ID is" in html
                }
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String?,
                isReload: Boolean,
            ) {
                super.doUpdateVisitedHistory(view, url, isReload)
                url?.let {
                    currentUrl = it
                    onUrlChange(it)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return false

                // Ignore intents urls
                if (url.startsWith("intent://")) return true

                // Only open valid web urls
                if (url.startsWith("http") || url.startsWith("https")) {
                    if (url != view?.url) {
                        view?.loadUrl(url, headers)
                        return true
                    }
                }

                return false
            }
        }
    }

    val webChromeClient = remember {
        object : AccompanistWebChromeClient() {
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                // if it wasn't initiated by a user gesture, we should ignore it like a normal browser would
                if (isUserGesture) {
                    windowStack.push(WebViewWindow(resultMsg, WebViewNavigator(coroutineScope)))
                    return true
                }
                return false
            }
        }
    }

    fun initializePopup(webView: WebView, message: Message): WebView {
        val transport = message.obj as WebView.WebViewTransport
        transport.webView = webView
        message.sendToTarget()
        return webView
    }

    val popState = remember<() -> Unit> {
        {
            if (windowStack.size == 1) {
                onNavigateUp()
            } else {
                windowStack.pop()
            }
        }
    }

    BackHandler(windowStack.size > 1, popState)

    Scaffold(
        topBar = {
            Box {
                Column {
                    AppBar(
                        title = currentWindow.state.pageTitle ?: initialTitle,
                        subtitle = currentUrl,
                        navigateUp = onNavigateUp,
                        navigationIcon = Icons.Outlined.Close,
                        actions = {
                            AppBarActions(
                                persistentListOf(
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_back),
                                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                                        onClick = {
                                            if (navigator.canGoBack) {
                                                navigator.navigateBack()
                                            }
                                        },
                                        enabled = navigator.canGoBack,
                                    ),
                                    AppBar.Action(
                                        title = stringResource(MR.strings.action_webview_forward),
                                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                                        onClick = {
                                            if (navigator.canGoForward) {
                                                navigator.navigateForward()
                                            }
                                        },
                                        enabled = navigator.canGoForward,
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_webview_refresh),
                                        onClick = { navigator.reload() },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_share),
                                        onClick = { onShare(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.action_open_in_browser),
                                        onClick = { onOpenInBrowser(currentUrl) },
                                    ),
                                    AppBar.OverflowAction(
                                        title = stringResource(MR.strings.pref_clear_cookies),
                                        onClick = { onClearCookies(currentUrl) },
                                    ),
                                ).builder().apply {
                                    if (windowStack.size > 1) {
                                        add(
                                            0,
                                            AppBar.Action(
                                                title = stringResource(MR.strings.action_webview_close_tab),
                                                icon = ImageVector.vectorResource(R.drawable.ic_tab_close_24px),
                                                onClick = popState,
                                            ),
                                        )
                                    }
                                }.build(),
                            )
                        },
                    )

                    if (showCloudflareHelp) {
                        Surface(
                            modifier = Modifier.padding(8.dp),
                        ) {
                            WarningBanner(
                                textRes = MR.strings.information_cloudflare_help,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable {
                                        uriHandler.openUri(
                                            "https://mihon.app/docs/guides/troubleshooting/#cloudflare",
                                        )
                                    },
                            )
                        }
                    }
                }
                when (val loadingState = currentWindow.state.loadingState) {
                    is LoadingState.Initializing -> LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )

                    is LoadingState.Loading -> LinearProgressIndicator(
                        progress = { (loadingState as? LoadingState.Loading)?.progress ?: 1f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter),
                    )

                    else -> {}
                }
            }
        },
    ) { contentPadding ->
        // We need to key the WebView composable to the window object since simply updating the WebView composable will
        // not cause it to re-invoke the WebView factory and render the new current window's WebView. This lets us
        // completely reset the WebView composable when the current window switches.
        key(currentWindow) {
            Box(Modifier.fillMaxSize()) {
                WebView(
                    state = currentWindow.state,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding),
                    navigator = navigator,
                    onCreated = { webView ->
                        webViewRef.value = webView
                        webView.setDefaultSettings()

                        // Debug mode (chrome://inspect/#devices)
                        if (BuildConfig.DEBUG &&
                            0 != webView.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE
                        ) {
                            WebView.setWebContentsDebuggingEnabled(true)
                        }

                        headers["user-agent"]?.let {
                            webView.settings.userAgentString = it
                        }

                        // Declare a Job to cancel/reset the button visibility after 300 ms
                        var scrollResetJob: Job? = null

                        webView.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
                            // Calculate scroll delta
                            val delta = scrollY - oldScrollY
                            // Calculate total content height in pixels.
                            val contentHeightPx = (webView.contentHeight * webView.scaleY).toInt()

                            // Only show the button if scrolling is applicable.
                            if (contentHeightPx <= webView.height) return@setOnScrollChangeListener

                            if (delta > 0 && scrollY > 0) {
                                showScrollButtons = 1
                            } else if (delta < 0 && scrollY < contentHeightPx - webView.height) {
                                showScrollButtons = 2
                            }

                            // Cancel any existing reset and hide the button after 300ms of inactivity.
                            scrollResetJob?.cancel()
                            scrollResetJob = scope.launch {
                                delay(Duration.ofMillis(300))
                                showScrollButtons = 0
                            }
                        }
                    },
                    onDispose = { webView ->
                        val window = windowStack.items.find { it.webView == webView }
                        if (window == null) {
                            // If we couldn't find any window on the stack that owns this WebView, it means that we can
                            // safely dispose of it because the window containing it has been closed.
                            webView.destroy()
                        } else {
                            // The composable is being disposed but the WebView object is not.
                            // When the WebView element is recomposed, we will want the WebView to resume from its state
                            // before it was unmounted, we won't want it to reset back to its original target.
                            window.state.content = WebContent.NavigatorOnly
                        }
                    },
                    client = webClient,
                    chromeClient = webChromeClient,
                    factory = { context ->
                        currentWindow.webView
                            ?: WebView(context).also { webView ->
                                currentWindow.webView = webView
                                currentWindow.popupMessage?.let {
                                    initializePopup(webView, it)
                                }
                            }
                    },
                )

                // Scroll-to-top/bottom button
                AnimatedVisibility(
                    visible = showScrollButtons > 0,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    SmallFloatingActionButton(
                        onClick = {
                            webViewRef.value?.let { webView ->
                                val contentHeightPx = (webView.contentHeight * webView.scaleY).toInt()
                                when (showScrollButtons) {
                                    1 -> { // ArrowDownward: scroll to bottom.
                                        val bottomY = max(0, contentHeightPx - webView.height)
                                        webView.scrollTo(0, bottomY)
                                    }

                                    2 -> { // ArrowUpward: scroll to top.
                                        webView.scrollTo(0, 0)
                                    }
                                }
                                // Hide the button immediately after clicking.
                                showScrollButtons = 0
                            }
                        },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.surfaceTint.copy(alpha = 0.5f),
                    ) {
                        Icon(
                            imageVector = if (showScrollButtons == 1)
                                Icons.Filled.ArrowDownward
                            else
                                Icons.Filled.ArrowUpward,
                            contentDescription = null,
                        )
                    }
                }
            }
        }
    }
}
