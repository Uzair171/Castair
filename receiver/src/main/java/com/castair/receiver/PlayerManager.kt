package com.castair.receiver

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.castair.protocol.CastItem
import com.castair.protocol.Event
import com.castair.protocol.PlayStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
object PlayerManager {

    private const val TAG = "PlayerManager"

    private var exoPlayer: ExoPlayer? = null

    private val _playbackState = MutableStateFlow(
        Event.State(status = PlayStatus.IDLE)
    )
    val playbackState: StateFlow<Event.State> = _playbackState.asStateFlow()

    private val _availableTracks = MutableStateFlow(
        Event.Tracks()
    )
    val availableTracks: StateFlow<Event.Tracks> = _availableTracks.asStateFlow()

    private var currentItem: CastItem? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            updateState()
            if (exoPlayer?.isPlaying == true) {
                mainHandler.postDelayed(this, 1000)
            }
        }
    }

    fun getPlayer(context: Context): ExoPlayer {
        return exoPlayer ?: synchronized(this) {
            exoPlayer ?: buildPlayer(context.applicationContext).also {
                exoPlayer = it
            }
        }
    }

    private fun buildPlayer(context: Context): ExoPlayer {
        val player = ExoPlayer.Builder(context).build()

        val trackParams = player.trackSelectionParameters.buildUpon()
            .setSelectUndeterminedTextLanguage(true)
            .setPreferredTextLanguage(Locale.getDefault().language)
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
            .build()
        player.trackSelectionParameters = trackParams

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateState()
                if (playbackState == Player.STATE_READY && player.isPlaying) {
                    startProgressTicker()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateState()
                if (isPlaying) {
                    startProgressTicker()
                } else {
                    stopProgressTicker()
                }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                updateTracks(tracks)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "ExoPlayer Error [${error.errorCodeName} / ${error.errorCode}]: ${error.message}", error)
                error.cause?.let { cause ->
                    Log.e(TAG, "ExoPlayer Root Cause: ${cause.javaClass.name}: ${cause.message}", cause)
                }
                _playbackState.value = _playbackState.value.copy(
                    status = PlayStatus.ERROR
                )
                stopProgressTicker()
            }
        })
        return player
    }

    private fun createTrustAllOkHttpClient(): okhttp3.OkHttpClient {
        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                }
            )

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, java.security.SecureRandom())

            okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        } catch (e: Exception) {
            okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }

    fun play(context: Context, item: CastItem) {
        mainHandler.post {
            currentItem = item
            val player = getPlayer(context)

            _playbackState.value = Event.State(status = PlayStatus.LOADING, title = item.title)

            player.stop()
            player.clearMediaItems()

            val okHttpClient = createTrustAllOkHttpClient()

            val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okHttpClient).apply {
                val customHeaders = item.headers.toMutableMap()

                val customUa = customHeaders.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.value
                if (!customUa.isNullOrBlank()) {
                    setUserAgent(customUa)
                } else {
                    setUserAgent("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                }
                customHeaders.keys.removeAll { it.equals("User-Agent", ignoreCase = true) }

                val refererEntry = customHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }
                val originEntry = customHeaders.entries.firstOrNull { it.key.equals("Origin", ignoreCase = true) }

                val referer = refererEntry?.value
                val origin = originEntry?.value

                if (referer.isNullOrBlank() || referer.contains("google.com/search") || referer.contains("bing.com/search")) {
                    if (!origin.isNullOrBlank()) {
                        customHeaders["Referer"] = if (origin.endsWith("/")) origin else "$origin/"
                    } else {
                        runCatching {
                            val uri = android.net.Uri.parse(item.url)
                            val host = uri.host
                            val scheme = uri.scheme ?: "https"
                            if (host != null) {
                                customHeaders["Referer"] = "$scheme://$host/"
                            }
                        }
                    }
                }

                val itemHost = runCatching { android.net.Uri.parse(item.url).host }.getOrNull()
                val refererHost = customHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value?.let {
                    runCatching { android.net.Uri.parse(it).host }.getOrNull()
                }

                val isSameSite = itemHost != null && refererHost != null &&
                    (itemHost.equals(refererHost, ignoreCase = true) || itemHost.endsWith(".$refererHost") || refererHost.endsWith(".$itemHost"))

                if (!customHeaders.containsKey("Accept")) customHeaders["Accept"] = "*/*"
                if (!customHeaders.containsKey("Accept-Language")) customHeaders["Accept-Language"] = "en-US,en;q=0.9"
                if (!customHeaders.containsKey("Sec-Fetch-Dest")) customHeaders["Sec-Fetch-Dest"] = "empty"
                if (!customHeaders.containsKey("Sec-Fetch-Mode")) customHeaders["Sec-Fetch-Mode"] = "cors"
                if (!customHeaders.containsKey("Sec-Fetch-Site")) {
                    customHeaders["Sec-Fetch-Site"] = if (isSameSite) "same-origin" else "cross-site"
                }

                setDefaultRequestProperties(customHeaders)
            }

            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(
                context.applicationContext,
                httpDataSourceFactory
            )

            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

            val mediaItemBuilder = MediaItem.Builder().setUri(item.url)
            val lowerUrl = item.url.lowercase(Locale.ROOT)

            when {
                lowerUrl.contains(".m3u8") || lowerUrl.contains("/hls/") || lowerUrl.contains("playlist") || lowerUrl.contains("manifest") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                }
                lowerUrl.contains(".mpd") || lowerUrl.contains("/dash/") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                }
                lowerUrl.contains(".mp4") || lowerUrl.contains("mime=video/mp4") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_MP4)
                }
                lowerUrl.contains(".webm") || lowerUrl.contains("mime=video/webm") -> {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.VIDEO_WEBM)
                }
            }

            item.subtitleUrl?.let { subUrl ->
                if (subUrl.isNotBlank()) {
                    val mime = if (subUrl.contains(".vtt")) {
                        androidx.media3.common.MimeTypes.TEXT_VTT
                    } else {
                        androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                    }
                    val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subUrl))
                        .setMimeType(mime)
                        .setLanguage("en")
                        .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT or androidx.media3.common.C.SELECTION_FLAG_AUTOSELECT)
                        .build()
                    mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
                }
            }

            val mediaItem = mediaItemBuilder.build()
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

            player.setMediaSource(mediaSource)
            player.prepare()

            if (item.startPositionMs > 0) {
                player.seekTo(item.startPositionMs)
            }

            player.playWhenReady = true
        }
    }

    fun pause() {
        mainHandler.post {
            exoPlayer?.pause()
            updateState()
        }
    }

    fun resume() {
        mainHandler.post {
            exoPlayer?.play()
            updateState()
        }
    }

    fun togglePlayPause() {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            updateState()
        }
    }

    fun stop() {
        mainHandler.post {
            stopProgressTicker()
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            currentItem = null
            _playbackState.value = Event.State(status = PlayStatus.IDLE)
        }
    }

    fun seekTo(positionMs: Long) {
        mainHandler.post {
            exoPlayer?.seekTo(positionMs)
            updateState()
        }
    }

    fun seekBy(offsetMs: Long) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            val newPos = (player.currentPosition + offsetMs).coerceIn(0, player.duration.coerceAtLeast(0))
            player.seekTo(newPos)
            updateState()
        }
    }

    fun setVolume(percent: Int) {
        mainHandler.post {
            val clamped = percent.coerceIn(0, 100)
            exoPlayer?.volume = clamped / 100f
            updateState()
        }
    }

    private fun updateTracks(tracks: androidx.media3.common.Tracks) {
        val qualities = mutableSetOf<Int>()
        val audioTracks = mutableListOf<com.castair.protocol.Track>()
        val subtitleTracks = mutableListOf<com.castair.protocol.Track>()

        for (group in tracks.groups) {
            val trackGroup = group.mediaTrackGroup
            val trackType = group.type

            for (i in 0 until trackGroup.length) {
                val format = trackGroup.getFormat(i)
                when (trackType) {
                    androidx.media3.common.C.TRACK_TYPE_VIDEO -> {
                        if (format.height > 0) {
                            qualities.add(format.height)
                        }
                    }
                    androidx.media3.common.C.TRACK_TYPE_AUDIO -> {
                        val id = "${trackGroup.id}:$i"
                        val label = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}"
                        val lang = format.language
                        audioTracks.add(com.castair.protocol.Track(id = id, label = label, language = lang))
                    }
                    androidx.media3.common.C.TRACK_TYPE_TEXT -> {
                        val id = "${trackGroup.id}:$i"
                        val label = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}"
                        val lang = format.language
                        subtitleTracks.add(com.castair.protocol.Track(id = id, label = label, language = lang))
                    }
                }
            }
        }

        _availableTracks.value = Event.Tracks(
            qualities = qualities.sortedDescending(),
            audio = audioTracks,
            subtitles = subtitleTracks
        )
    }

    fun setQuality(height: Int?) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            val builder = player.trackSelectionParameters.buildUpon()
            if (height != null && height > 0) {
                builder.setMaxVideoSize(10000, height)
                builder.setMinVideoSize(0, height)
            } else {
                builder.clearVideoSizeConstraints()
            }
            player.trackSelectionParameters = builder.build()
            _playbackState.value = _playbackState.value.copy(qualityHeight = height)
        }
    }

    fun setAudioTrack(id: String) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            val tracks = player.currentTracks
            for (group in tracks.groups) {
                if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    val trackGroup = group.mediaTrackGroup
                    for (i in 0 until trackGroup.length) {
                        val trackId = "${trackGroup.id}:$i"
                        if (trackId == id) {
                            val override = androidx.media3.common.TrackSelectionOverride(trackGroup, i)
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(override)
                                .build()
                            return@post
                        }
                    }
                }
            }
        }
    }

    fun setSubtitleTrack(id: String?) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            val builder = player.trackSelectionParameters.buildUpon()
            if (id == null) {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            } else {
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                val tracks = player.currentTracks
                for (group in tracks.groups) {
                    if (group.type == androidx.media3.common.C.TRACK_TYPE_TEXT) {
                        val trackGroup = group.mediaTrackGroup
                        for (i in 0 until trackGroup.length) {
                            val trackId = "${trackGroup.id}:$i"
                            if (trackId == id) {
                                val override = androidx.media3.common.TrackSelectionOverride(trackGroup, i)
                                builder.setOverrideForType(override)
                                break
                            }
                        }
                    }
                }
            }
            player.trackSelectionParameters = builder.build()
        }
    }

    fun setConnectedDevice(deviceName: String?) {
        mainHandler.post {
            _playbackState.value = _playbackState.value.copy(
                connectedDeviceName = deviceName
            )
            updateState()
        }
    }

    suspend fun getCurrentState(): Event.State = withContext(Dispatchers.Main) {
        updateState()
        _playbackState.value
    }

    private fun updateState() {
        val player = exoPlayer
        val deviceName = _playbackState.value.connectedDeviceName
        val status = when {
            player?.playerError != null -> PlayStatus.ERROR
            player?.isPlaying == true -> PlayStatus.PLAYING
            player?.playbackState == Player.STATE_BUFFERING -> PlayStatus.BUFFERING
            player?.playbackState == Player.STATE_READY -> if (player.playWhenReady) PlayStatus.PLAYING else PlayStatus.PAUSED
            player?.playbackState == Player.STATE_ENDED -> PlayStatus.ENDED
            player?.playbackState == Player.STATE_IDLE && currentItem != null -> PlayStatus.LOADING
            deviceName != null -> PlayStatus.CONNECTED
            else -> PlayStatus.IDLE
        }

        val pos = player?.currentPosition?.coerceAtLeast(0) ?: 0
        val dur = player?.duration?.coerceAtLeast(0) ?: 0
        val vol = ((player?.volume ?: 1f) * 100).toInt()

        _playbackState.value = Event.State(
            status = status,
            title = currentItem?.title ?: "",
            positionMs = pos,
            durationMs = if (dur < 0) 0 else dur,
            volumePercent = vol,
            connectedDeviceName = deviceName
        )
    }

    private fun startProgressTicker() {
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.post(updateProgressRunnable)
    }

    private fun stopProgressTicker() {
        mainHandler.removeCallbacks(updateProgressRunnable)
    }

    fun release() {
        mainHandler.post {
            stopProgressTicker()
            exoPlayer?.release()
            exoPlayer = null
            _playbackState.value = Event.State(status = PlayStatus.IDLE)
        }
    }
}
