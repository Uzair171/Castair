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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.castair.protocol.PlayStatus

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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    if (canGoBack) {
                        TextButton(onClick = {
                            if (webViewInstance?.canGoBack() == true) {
                                webViewInstance?.goBack()
                            }
                        }) {
                            Text("←", fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                title = {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = { inputUrl = it },
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp),
                            singleLine = true,
                            shape = RoundedCornerShape(26.dp),
                            placeholder = { Text("Search or enter URL...", fontSize = 14.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val target = if (!inputUrl.startsWith("http://") && !inputUrl.startsWith("https://")) {
                                    "https://www.google.com/search?q=${inputUrl}"
                                } else inputUrl
                                clearDetectedVideos()
                                currentUrl = target
                                inputUrl = target
                            },
                            modifier = Modifier.height(48.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Go", fontSize = 14.sp)
                        }
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = { showDeviceDialog = true },
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = connectedDevice?.name ?: "Connect TV",
                            fontSize = 13.sp,
                            maxLines = 1
                        )
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

                // Floating Detected Video Banner at bottom
                selectedVideo?.let { video ->
                    Card(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                            .fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        shape = RoundedCornerShape(20.dp)
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
                                    text = "${detectedVideos.size} Video${if (detectedVideos.size > 1) "s" else ""} Ready",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = video.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = {
                                    webViewInstance?.evaluateJavascript("document.querySelectorAll('video').forEach(v => v.pause());", null)
                                    if (connectedDevice == null) {
                                        showDeviceDialog = true
                                    } else {
                                        clientManager.play(video.url, video.title, video.headers, video.subtitleUrl)
                                    }
                                },
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Text("Play on TV")
                            }
                        }
                    }
                }
            }

            // Playback Control Panel
            playbackState?.let { state ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp,
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Connected to ${connectedDevice?.name ?: "TV Receiver"}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (state.title.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = state.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Text(
                                    text = state.status.name,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        if (state.status == PlayStatus.PLAYING || state.status == PlayStatus.PAUSED || state.status == PlayStatus.BUFFERING) {
                            var isUserSeeking by remember { mutableStateOf(false) }
                            var seekPositionMs by remember { mutableStateOf(0f) }

                            val currentPosMs = if (isUserSeeking) seekPositionMs else state.positionMs.toFloat()
                            val maxDurMs = state.durationMs.coerceAtLeast(1L).toFloat()

                            Spacer(modifier = Modifier.height(12.dp))
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

                                Slider(
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
                                    colors = SliderDefaults.colors(
                                        thumbColor = MaterialTheme.colorScheme.primary,
                                        activeTrackColor = MaterialTheme.colorScheme.primary
                                    ),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Button(
                                onClick = {
                                    if (state.status == PlayStatus.PLAYING) {
                                        clientManager.pause()
                                    } else {
                                        clientManager.resume()
                                    }
                                },
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 6.dp)
                            ) {
                                Text(if (state.status == PlayStatus.PLAYING) "Pause" else "Play")
                            }

                            Button(
                                onClick = { clientManager.stop() },
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 6.dp)
                            ) {
                                Text("Stop")
                            }
                        }

                        if (state.status == PlayStatus.PLAYING || state.status == PlayStatus.PAUSED || state.status == PlayStatus.BUFFERING) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = { showQualityDialog = true },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val qText = state.qualityHeight?.let { "${it}p" } ?: "Quality"
                                    Text(qText, fontSize = 12.sp)
                                }

                                FilledTonalButton(
                                    onClick = { showAudioDialog = true },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Audio", fontSize = 12.sp)
                                }

                                FilledTonalButton(
                                    onClick = { showSubtitleDialog = true },
                                    shape = RoundedCornerShape(16.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Subtitles", fontSize = 12.sp)
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
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    if (showVideoListDialog) {
        AlertDialog(
            onDismissRequest = { showVideoListDialog = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text("Select Video Stream", style = MaterialTheme.typography.titleLarge) },
            text = {
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(detectedVideos) { video ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable {
                                    selectedVideo = video
                                    showVideoListDialog = false
                                },
                            color = if (selectedVideo == video) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(video.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                Spacer(modifier = Modifier.height(2.dp))
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Connect to TV Receiver", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Text("Discovered TVs:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (discoveredDevices.isEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                "Searching local Wi-Fi for TVs...",
                                modifier = Modifier.padding(16.dp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.height(150.dp)) {
                            items(discoveredDevices) { device ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable {
                                            val videoToPlay = selectedVideo
                                            clientManager.connect(device) {
                                                videoToPlay?.let { v ->
                                                    clientManager.play(v.url, v.title, v.headers, v.subtitleUrl)
                                                }
                                            }
                                            showDeviceDialog = false
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(device.name, style = MaterialTheme.typography.bodyLarge)
                                        Text("${device.host}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Or Connect via IP Address:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = manualIp,
                        onValueChange = { manualIp = it },
                        label = { Text("TV IP Address") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val manualDevice = CastDevice("TV ($manualIp)", manualIp, 8765)
                        val videoToPlay = selectedVideo
                        clientManager.connect(manualDevice) {
                            videoToPlay?.let { v ->
                                clientManager.play(v.url, v.title, v.headers, v.subtitleUrl)
                            }
                        }
                        showDeviceDialog = false
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Select Video Quality", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                clientManager.setQuality(null)
                                showQualityDialog = false
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text("Auto (Adaptive Bitrate)", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(qualities) { q ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        clientManager.setQuality(q)
                                        showQualityDialog = false
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Text("${q}p", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Select Audio Track", style = MaterialTheme.typography.titleLarge) },
            text = {
                if (audioList.isEmpty()) {
                    Text("No additional audio tracks found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.height(200.dp)) {
                        items(audioList) { track ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        clientManager.setAudioTrack(track.id)
                                        showAudioDialog = false
                                    },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                            ) {
                                Text(track.label, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
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
            shape = RoundedCornerShape(24.dp),
            title = { Text("Select Subtitles", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                clientManager.setSubtitleTrack(null)
                                showSubtitleDialog = false
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text("Subtitles Off", modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                    }

                    if (subtitleList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LazyColumn(modifier = Modifier.height(200.dp)) {
                            items(subtitleList) { track ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            clientManager.setSubtitleTrack(track.id)
                                            showSubtitleDialog = false
                                        },
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                                ) {
                                    Text(track.label, modifier = Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
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
