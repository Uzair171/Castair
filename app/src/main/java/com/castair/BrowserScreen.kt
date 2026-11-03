package com.castair

import java.util.Locale
import android.graphics.Bitmap
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.castair.protocol.PlayStatus

data class Bookmark(val label: String, val url: String)

val SAMPLE_BOOKMARKS = listOf(
    Bookmark("Big Buck Bunny", "https://storage.googleapis.com/exoplayer-test-media-0/BigBuckBunny_320x180.mp4"),
    Bookmark("Mux HLS Stream", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
    Bookmark("Google", "https://www.google.com"),
    Bookmark("Vimeo", "https://vimeo.com"),
    Bookmark("Twitch", "https://www.twitch.tv")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    discoveryManager: TvDiscoveryManager,
    clientManager: CastClientManager
) {
    var currentUrl by remember { mutableStateOf("https://www.google.com") }
    var inputUrl by remember { mutableStateOf("https://www.google.com") }
    var pageTitle by remember { mutableStateOf("Castair Web Browser") }

    var detectedVideos by remember { mutableStateOf<List<DetectedVideo>>(emptyList()) }
    var selectedVideo by remember { mutableStateOf<DetectedVideo?>(null) }
    var showDeviceDialog by remember { mutableStateOf(false) }
    var showVideoListDialog by remember { mutableStateOf(false) }

    val discoveredDevices by discoveryManager.devices.collectAsState()
    val connectedDevice by clientManager.connectedDevice.collectAsState()
    val playbackState by clientManager.playbackState.collectAsState()
    val availableTracks by clientManager.availableTracks.collectAsState()
    val connectionError by clientManager.connectionError.collectAsState()

    var showQualityDialog by remember { mutableStateOf(false) }
    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }

    var manualIp by remember(discoveredDevices) {
        mutableStateOf(discoveredDevices.firstOrNull()?.host ?: "")
    }

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    fun isAudioPlaylist(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("mime=audio") || lower.contains("type=audio") || lower.contains("audio.m3u8")
    }

    fun clearDetectedVideos() {
        detectedVideos = emptyList()
        selectedVideo = null
    }

    fun formatTime(ms: Long): String {
        if (ms <= 0) return "00:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val hours = minutes / 60
        return if (hours > 0) {
            val remMinutes = minutes % 60
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, remMinutes, seconds)
        } else {
            String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
        }
    }

    fun addDetectedVideo(
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        subtitleUrl: String? = null
    ) {
        if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:") || isAudioPlaylist(url)) return
        if (!VideoSniffer.isVideoUrl(url, headers)) return

        val finalHeaders = headers.toMutableMap()
        if (!finalHeaders.containsKey("Referer") && currentUrl.isNotBlank() && !currentUrl.contains("google.com/search")) {
            finalHeaders["Referer"] = currentUrl
        }

        runCatching {
            val cookieMgr = android.webkit.CookieManager.getInstance()
            val cookiesUrl = cookieMgr.getCookie(url)
            val cookiesPage = if (currentUrl.isNotBlank()) cookieMgr.getCookie(currentUrl) else null
            val combined = listOfNotNull(cookiesUrl, cookiesPage)
                .flatMap { it.split(";") }
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("; ")
            if (combined.isNotBlank()) {
                finalHeaders["Cookie"] = combined
            }
        }

        val hostName = runCatching { android.net.Uri.parse(url).host }.getOrNull() ?: ""
        val displayTitle = when {
            title.isNotBlank() && title != "Google" && title != "Web Video" -> title
            pageTitle.isNotBlank() && pageTitle != "Castair Web Browser" -> pageTitle
            hostName.isNotBlank() -> "Video ($hostName)"
            else -> "Web Video"
        }

        val newVideo = DetectedVideo(
            url = url,
            title = displayTitle,
            headers = finalHeaders,
            subtitleUrl = subtitleUrl
        )

        val existingIndex = detectedVideos.indexOfFirst {
            VideoSniffer.isSameVideoUrl(it.url, url)
        }

        if (existingIndex != -1) {
            val existing = detectedVideos[existingIndex]
            val updatedVideo = if (subtitleUrl != null && existing.subtitleUrl == null) {
                existing.copy(subtitleUrl = subtitleUrl)
            } else newVideo
            val updatedList = detectedVideos.toMutableList()
            updatedList[existingIndex] = updatedVideo
            detectedVideos = updatedList
            if (selectedVideo == existing) {
                selectedVideo = updatedVideo
            }
            return
        }

        detectedVideos = listOf(newVideo) + detectedVideos.take(4)
        selectedVideo = newVideo
    }

    LaunchedEffect(Unit) {
        discoveryManager.startDiscovery()
    }

    DisposableEffect(Unit) {
        onDispose {
            discoveryManager.stopDiscovery()
        }
    }

    var canGoBack by remember { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = canGoBack) {
        if (webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (canGoBack) {
                        TextButton(onClick = {
                            if (webViewInstance?.canGoBack() == true) {
                                webViewInstance?.goBack()
                            }
                        }) {
                            Text("← Back", fontSize = 14.sp)
                        }
                    }
                },
                title = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Enter URL or search...") }
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(onClick = {
                            val target = if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                                "https://www.google.com/search?q=${inputUrl}"
                            } else inputUrl
                            clearDetectedVideos()
                            currentUrl = target
                            inputUrl = target
                        }) {
                            Text("Go")
                        }
                    }
                },
                actions = {
                    Button(
                        onClick = { showDeviceDialog = true },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(connectedDevice?.name ?: "Connect TV")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(SAMPLE_BOOKMARKS) { bookmark ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable {
                            clearDetectedVideos()
                            currentUrl = bookmark.url
                            inputUrl = bookmark.url
                        }
                    ) {
                        Text(
                            text = bookmark.label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.setSupportMultipleWindows(false)
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                            val ua = settings.userAgentString

                            addJavascriptInterface(CastairJSBridge { url, title, subUrl ->
                                val defaultHeaders = mapOf(
                                    "Referer" to currentUrl,
                                    "User-Agent" to ua
                                )
                                addDetectedVideo(url, title, defaultHeaders, subUrl)
                            }, "CastairBridge")

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    // Block intrusive pop-ups and ad redirects
                                    return false
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    url?.let {
                                        inputUrl = it
                                        if (it != currentUrl) {
                                            currentUrl = it
                                            clearDetectedVideos()
                                        }
                                    }
                                    view?.evaluateJavascript(VideoSniffer.JS_ADVANCED_SNIFFER, null)
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    canGoBack = view?.canGoBack() ?: false
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    pageTitle = view?.title ?: "Web Video"
                                    canGoBack = view?.canGoBack() ?: false
                                    view?.evaluateJavascript(VideoSniffer.JS_ADVANCED_SNIFFER, null)
                                }

                                override fun onLoadResource(view: WebView?, url: String?) {
                                    super.onLoadResource(view, url)
                                    if (url != null && VideoSniffer.isVideoUrl(url)) {
                                        addDetectedVideo(url, pageTitle, mapOf("User-Agent" to ua, "Referer" to currentUrl))
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): android.webkit.WebResourceResponse? {
                                    if (request != null) {
                                        val detected = VideoSniffer.inspectRequest(
                                            request = request,
                                            pageTitle = pageTitle,
                                            pageUrl = currentUrl,
                                            userAgent = ua
                                        )
                                        if (detected != null) {
                                            addDetectedVideo(detected.url, detected.title, detected.headers)
                                        }
                                    }
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }
                            webViewInstance = this
                            loadUrl(currentUrl)
                        }
                    },
                    update = { view ->
                        if (view.url != currentUrl) {
                            view.loadUrl(currentUrl)
                        }
                    }
                )

                selectedVideo?.let { video ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        if (detectedVideos.size > 1) {
                                            showVideoListDialog = true
                                        }
                                    }
                            ) {
                                Text(
                                    text = "🎬 ${detectedVideos.size} Video${if (detectedVideos.size > 1) "s" else ""} Detected",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = video.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            Button(onClick = {
                                webViewInstance?.evaluateJavascript("document.querySelectorAll('video').forEach(v => v.pause());", null)
                                if (connectedDevice == null) {
                                    showDeviceDialog = true
                                } else {
                                    clientManager.play(video.url, video.title, video.headers, video.subtitleUrl)
                                }
                            }) {
                                Text("Play on TV")
                            }
                        }
                    }
                }
            }

            playbackState?.let { state ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "TV: ${connectedDevice?.name ?: "Receiver"} (${state.status})",
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (state.title.isNotBlank()) {
                            Text(text = state.title, style = MaterialTheme.typography.bodyMedium)
                        }

                        if (state.status == PlayStatus.PLAYING || state.status == PlayStatus.PAUSED || state.status == PlayStatus.BUFFERING) {
                            var isUserSeeking by remember { mutableStateOf(false) }
                            var seekPositionMs by remember { mutableStateOf(0f) }

                            val currentPosMs = if (isUserSeeking) seekPositionMs else state.positionMs.toFloat()
                            val maxDurMs = state.durationMs.coerceAtLeast(1L).toFloat()

                            Spacer(modifier = Modifier.height(6.dp))
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = formatTime(currentPosMs.toLong()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatTime(state.durationMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                androidx.compose.material3.Slider(
                                    value = currentPosMs.coerceIn(0f, maxDurMs),
                                    onValueChange = { newValue ->
                                        isUserSeeking = true
                                        seekPositionMs = newValue
                                    },
                                    onValueChangeFinished = {
                                        clientManager.seek(seekPositionMs.toLong())
                                        isUserSeeking = false
                                    },
                                    valueRange = 0f..maxDurMs,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Button(onClick = {
                                if (state.status == PlayStatus.PLAYING) {
                                    clientManager.pause()
                                } else {
                                    clientManager.resume()
                                }
                            }) {
                                Text(if (state.status == PlayStatus.PLAYING) "Pause" else "Play")
                            }

                            Button(onClick = { clientManager.stop() }) {
                                Text("Stop")
                            }
                        }

                        if (state.status == PlayStatus.PLAYING || state.status == PlayStatus.PAUSED || state.status == PlayStatus.BUFFERING) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { showQualityDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("⚙️ ${state.qualityHeight?.let { "${it}p" } ?: "Quality"}", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { showAudioDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("🔊 Audio", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = { showSubtitleDialog = true },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("💬 Subtitles", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            connectionError?.let { err ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "⚠️ $err",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (showVideoListDialog) {
        AlertDialog(
            onDismissRequest = { showVideoListDialog = false },
            title = { Text("Select Video Stream") },
            text = {
                LazyColumn {
                    items(detectedVideos) { video ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedVideo = video
                                    showVideoListDialog = false
                                }
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(video.title, style = MaterialTheme.typography.bodyMedium)
                                Text(video.url, fontSize = 11.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVideoListDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    if (showDeviceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceDialog = false },
            title = { Text("Select Castair Receiver") },
            text = {
                Column {
                    Text("Discovered TVs:", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (discoveredDevices.isEmpty()) {
                        Text("Searching local Wi-Fi for TVs...", fontSize = 12.sp)
                    } else {
                        LazyColumn {
                            items(discoveredDevices) { device ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val videoToPlay = selectedVideo
                                            clientManager.connect(device) {
                                                videoToPlay?.let { v ->
                                                    clientManager.play(v.url, v.title, v.headers)
                                                }
                                            }
                                            showDeviceDialog = false
                                        }
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                    Text("${device.host}:${device.port}", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Or Connect via IP Address:", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("TV IP Address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val manualDevice = CastDevice("TV ($manualIp)", manualIp, 8765)
                    val videoToPlay = selectedVideo
                    clientManager.connect(manualDevice) {
                        videoToPlay?.let { v ->
                            clientManager.play(v.url, v.title, v.headers)
                        }
                    }
                    showDeviceDialog = false
                }) {
                    Text("Connect IP")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeviceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showQualityDialog) {
        val qualities = availableTracks?.qualities ?: emptyList()
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Select Video Quality") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clientManager.setQuality(null)
                                showQualityDialog = false
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Text("Auto (Adaptive Bitrate)", style = MaterialTheme.typography.bodyMedium)
                    }

                    LazyColumn {
                        items(qualities) { q ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clientManager.setQuality(q)
                                        showQualityDialog = false
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text("${q}p", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQualityDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAudioDialog) {
        val audioList = availableTracks?.audio ?: emptyList()
        AlertDialog(
            onDismissRequest = { showAudioDialog = false },
            title = { Text("Select Audio Track") },
            text = {
                if (audioList.isEmpty()) {
                    Text("No additional audio tracks found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn {
                        items(audioList) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clientManager.setAudioTrack(track.id)
                                        showAudioDialog = false
                                    }
                                    .padding(vertical = 10.dp)
                            ) {
                                Text(track.label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAudioDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showSubtitleDialog) {
        val subtitleList = availableTracks?.subtitles ?: emptyList()
        AlertDialog(
            onDismissRequest = { showSubtitleDialog = false },
            title = { Text("Select Subtitles") },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clientManager.setSubtitleTrack(null)
                                showSubtitleDialog = false
                            }
                            .padding(vertical = 12.dp)
                    ) {
                        Text("Subtitles Off", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (subtitleList.isEmpty()) {
                        Text("No internal subtitle tracks detected.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        LazyColumn {
                            items(subtitleList) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            clientManager.setSubtitleTrack(track.id)
                                            showSubtitleDialog = false
                                        }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Text(track.label, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSubtitleDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
