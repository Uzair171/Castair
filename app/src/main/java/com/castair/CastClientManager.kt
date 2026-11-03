package com.castair

import android.os.Build
import android.util.Log
import com.castair.protocol.CastItem
import com.castair.protocol.Command
import com.castair.protocol.Event
import com.castair.protocol.parseEvent
import com.castair.protocol.toJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class CastClientManager {

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingIntervalMillis = 15000
        }
    }

    private var session: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private var receiveJob: Job? = null

    private val _connectedDevice = MutableStateFlow<CastDevice?>(null)
    val connectedDevice: StateFlow<CastDevice?> = _connectedDevice.asStateFlow()

    private val _playbackState = MutableStateFlow<Event.State?>(null)
    val playbackState: StateFlow<Event.State?> = _playbackState.asStateFlow()

    private val _availableTracks = MutableStateFlow<Event.Tracks?>(null)
    val availableTracks: StateFlow<Event.Tracks?> = _availableTracks.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val sendMutex = Mutex()

    fun connect(device: CastDevice, onConnected: (() -> Unit)? = null) {
        disconnect()
        _connectionError.value = null

        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope

        newScope.launch {
            try {
                Log.i(TAG, "Connecting to ws://${device.host}:${device.port}/")
                val newSession = client.webSocketSession {
                    url("ws://${device.host}:${device.port}/")
                }

                session = newSession
                _connectedDevice.value = device
                _connectionError.value = null
                Log.i(TAG, "Connected successfully to ${device.name}")

                receiveJob = newScope.launch {
                    try {
                        for (frame in newSession.incoming) {
                            if (frame !is Frame.Text) continue
                            val text = frame.readText()
                            val event = parseEvent(text)
                            Log.d(TAG, "Received event: $event")

                            when (event) {
                                is Event.State -> {
                                    _playbackState.value = event
                                }
                                is Event.Tracks -> {
                                    _availableTracks.value = event
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WebSocket receive loop ended: ${e.message}")
                    } finally {
                        Log.i(TAG, "WebSocket closed or disconnected")
                        _connectedDevice.value = null
                        _playbackState.value = null
                        _availableTracks.value = null
                    }
                }

                val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
                send(Command.Pair(pairKey = "", deviceId = Build.MODEL, deviceName = deviceName))
                send(Command.GetState)
                onConnected?.invoke()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect to ${device.name}", e)
                _connectionError.value = "Unable to connect to ${device.name} (${device.host}). Ensure TV app is open and on same Wi-Fi."
                disconnect()
            }
        }
    }

    fun play(url: String, title: String, headers: Map<String, String> = emptyMap(), subtitleUrl: String? = null) {
        val item = CastItem(
            url = url,
            title = title,
            headers = headers,
            subtitleUrl = subtitleUrl
        )
        send(Command.Play(item))
    }

    fun pause() = send(Command.Pause)
    fun resume() = send(Command.Resume)
    fun stop() = send(Command.Stop)
    fun seek(positionMs: Long) = send(Command.Seek(positionMs))
    fun setVolume(percent: Int) = send(Command.SetVolume(percent))
    fun setQuality(height: Int?) = send(Command.SetQuality(height))
    fun setAudioTrack(id: String) = send(Command.SetAudioTrack(id))
    fun setSubtitleTrack(id: String?) = send(Command.SetSubtitleTrack(id))

    private fun send(command: Command) {
        val currentSession = session ?: run {
            Log.w(TAG, "Cannot send command; not connected.")
            return
        }
        scope?.launch {
            try {
                val json = command.toJson()
                Log.d(TAG, "Sending command: $json")
                sendMutex.withLock {
                    currentSession.send(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send command $command", e)
            }
        }
    }

    fun disconnect() {
        receiveJob?.cancel()
        receiveJob = null

        scope?.launch {
            try {
                session?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing session", e)
            }
        }
        session = null

        scope?.cancel()
        scope = null

        _connectedDevice.value = null
        _playbackState.value = null
        _availableTracks.value = null
    }

    companion object {
        private const val TAG = "CastClientManager"
    }
}
