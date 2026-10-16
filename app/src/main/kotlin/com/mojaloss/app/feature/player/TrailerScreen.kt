package com.mojaloss.app.feature.player

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

internal sealed interface TrailerLoadState {
    data object Loading : TrailerLoadState
    data object Ready : TrailerLoadState
    data object TimedOut : TrailerLoadState
    data class Failed(val message: String) : TrailerLoadState
}

/** A late iframe readiness callback can recover a timeout, but not a known unavailable video. */
internal fun trailerReady(state: TrailerLoadState): TrailerLoadState =
    if (state is TrailerLoadState.Failed) state else TrailerLoadState.Ready

internal fun trailerTimeout(state: TrailerLoadState): TrailerLoadState =
    if (state == TrailerLoadState.Loading) TrailerLoadState.TimedOut else state

internal fun trailerFailure(code: Int): TrailerLoadState.Failed = TrailerLoadState.Failed(when (code) {
    100 -> "This trailer is no longer available."
    101, 150 -> "This trailer cannot play here. Open it on YouTube."
    else -> "The trailer could not play here. Open it on YouTube."
})

private class TrailerBridge(private val ready: () -> Unit, private val failed: (Int) -> Unit) {
    @JavascriptInterface fun onReady() = ready()
    @JavascriptInterface fun onError(code: Int) = failed(code)
}

/** Verified trailer ID only. The embed never starts automatically or reports movie progress. */
@Composable
fun TrailerScreen(videoId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val valid = remember(videoId) { videoId.matches(Regex("[A-Za-z0-9_-]{11}")) }
    var state by remember(videoId) { mutableStateOf<TrailerLoadState>(if (valid) TrailerLoadState.Loading
        else TrailerLoadState.Failed("This trailer is unavailable.")) }
    var attempt by remember(videoId) { mutableIntStateOf(0) }
    var fullScreenView by remember { mutableStateOf<View?>(null) }
    var fullScreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun closeFullScreen() {
        fullScreenView = null
        fullScreenCallback?.onCustomViewHidden()
        fullScreenCallback = null
    }
    val openExternal = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")))
        } catch (_: ActivityNotFoundException) {
            state = TrailerLoadState.Failed("No browser is available to open this trailer.")
        }
    }
    BackHandler { if (fullScreenView != null) closeFullScreen() else onBack() }
    TrailerContent(
        valid = valid, state = state, onBack = onBack, onOpenExternal = openExternal,
        onRetry = { closeFullScreen(); state = TrailerLoadState.Loading; attempt++ },
        renderer = { modifier ->
            YouTubeTrailerRenderer(videoId, attempt, modifier,
                onLoading = { state = TrailerLoadState.Loading },
                onReady = { state = trailerReady(state) },
                onFailure = { state = TrailerLoadState.Failed(it) },
                onTimeout = { state = trailerTimeout(state) },
                onOpenExternal = openExternal,
                onShowFullScreen = { view, callback ->
                    if (fullScreenView != null) callback.onCustomViewHidden()
                    else { fullScreenView = view; fullScreenCallback = callback }
                },
                onCloseFullScreen = ::closeFullScreen)
        }
    )
    fullScreenView?.let { videoView ->
        Dialog(onDismissRequest = ::closeFullScreen,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { videoView }, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Presentation seam keeps native UX tests local: supplied test renderers never create WebView. */
@Composable
internal fun TrailerContent(
    valid: Boolean,
    state: TrailerLoadState,
    onBack: () -> Unit,
    onOpenExternal: () -> Unit,
    onRetry: () -> Unit,
    renderer: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().background(MaterialTheme.colorScheme.background)) {
        // The iframe requires a 200x200 CSS-pixel viewport. Short panes use its external fallback.
        val canEmbed = valid && maxWidth >= 232.dp && maxHeight >= 380.dp
        val embedHeight = if (canEmbed) (maxWidth.minus(32.dp) * (9f / 16f))
            .coerceIn(200.dp, (maxHeight * .55f).coerceAtMost(360.dp)) else 200.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
            .testTag("trailer-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back to title") }
            Text("Trailer", style = MaterialTheme.typography.headlineSmall)
            if (valid) {
                // Recovery precedes the embed, so even large text never traps it below a video.
                OutlinedButton(onClick = onOpenExternal, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open on YouTube") }
                if (!canEmbed) Text("Open on YouTube to watch with more room.", style = MaterialTheme.typography.bodyMedium)
                else Text("Tap YouTube’s Play button to start.", style = MaterialTheme.typography.bodyMedium)
            }
            when (state) {
                TrailerLoadState.Loading -> if (canEmbed) {
                    Text("Loading trailer…", style = MaterialTheme.typography.bodyMedium)
                }
                TrailerLoadState.Ready -> Unit
                TrailerLoadState.TimedOut -> {
                    Text("The trailer is taking longer to load. You can open it on YouTube.", color = MaterialTheme.colorScheme.error)
                    if (canEmbed) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry trailer") }
                }
                is TrailerLoadState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    if (canEmbed) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry trailer") }
                }
            }
            if (canEmbed) renderer(Modifier.fillMaxWidth().height(embedHeight).testTag("trailer-embed"))
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeTrailerRenderer(
    videoId: String,
    attempt: Int,
    modifier: Modifier,
    onLoading: () -> Unit,
    onReady: () -> Unit,
    onFailure: (String) -> Unit,
    onTimeout: () -> Unit,
    onOpenExternal: () -> Unit,
    onShowFullScreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onCloseFullScreen: () -> Unit,
) {
    val owner = LocalLifecycleOwner.current
    var webView by remember(videoId, attempt) { mutableStateOf<WebView?>(null) }
    var rendererActive by remember(videoId, attempt) { mutableStateOf(true) }
    val currentReady by rememberUpdatedState(onReady)
    val currentFailure by rememberUpdatedState(onFailure)
    val currentOpen by rememberUpdatedState(onOpenExternal)
    val currentShowFullScreen by rememberUpdatedState(onShowFullScreen)
    val currentCloseFullScreen by rememberUpdatedState(onCloseFullScreen)
    LaunchedEffect(videoId, attempt) {
        onLoading()
        delay(20_000)
        onTimeout()
    }
    DisposableEffect(owner, webView) {
        val current = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> current?.onResume()
                Lifecycle.Event.ON_STOP -> {
                    current?.evaluateJavascript("if(window.player){window.player.pauseVideo();}", null)
                    current?.onPause()
                }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            current?.evaluateJavascript("if(window.player){window.player.pauseVideo();}", null)
            current?.onPause()
        }
    }
    androidx.compose.runtime.key(videoId, attempt) {
        AndroidView(modifier = modifier,
            factory = { context -> WebView(context).apply {
                webView = this
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                addJavascriptInterface(TrailerBridge(
                    ready = { post { if (rendererActive) currentReady() } },
                    failed = { code -> post { if (rendererActive) currentFailure(trailerFailure(code).message) } }), "MojaTrailer")
                webChromeClient = object : WebChromeClient() {
                    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                        if (rendererActive) currentShowFullScreen(view, callback) else callback.onCustomViewHidden()
                    }
                    override fun onHideCustomView() = currentCloseFullScreen()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "https" && uri.host in setOf("www.youtube.com", "www.youtube-nocookie.com") &&
                            uri.path?.startsWith("/embed/") == true) return false
                        if (request.isForMainFrame && request.hasGesture()) currentOpen()
                        return request.isForMainFrame
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, failure: WebResourceError) {
                        if (rendererActive && request.isForMainFrame) currentFailure("The trailer could not load. Open it on YouTube.")
                    }
                    override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                        if (rendererActive && request.isForMainFrame) currentFailure("The trailer could not load. Open it on YouTube.")
                    }
                }
                val origin = "https://${context.packageName.lowercase()}"
                loadDataWithBaseURL(origin, trailerHtml(videoId, origin), "text/html", "UTF-8", null)
            } },
            onRelease = { view ->
                rendererActive = false
                webView = null
                currentCloseFullScreen()
                view.stopLoading()
                view.removeJavascriptInterface("MojaTrailer")
                view.loadUrl("about:blank")
                view.destroy()
            }
        )
    }
}

internal fun trailerHtml(videoId: String, origin: String) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="strict-origin-when-cross-origin">
<style>html,body{margin:0;width:100%;height:100%;background:#000}#player{width:100%;height:100%;min-width:200px;min-height:200px}</style>
</head><body><div id="player"></div><script>
var player;var tag=document.createElement('script');tag.src='https://www.youtube.com/iframe_api';document.head.appendChild(tag);
function onYouTubeIframeAPIReady(){player=new YT.Player('player',{videoId:'$videoId',
playerVars:{autoplay:0,controls:1,playsinline:1,origin:'$origin'},
events:{onReady:function(){MojaTrailer.onReady();},onError:function(e){MojaTrailer.onError(e.data);}}});}
</script></body></html>
""".trimIndent()
