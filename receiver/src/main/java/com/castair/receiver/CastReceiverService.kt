package com.castair.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.castair.protocol.Command
import com.castair.protocol.DEFAULT_PORT
import com.castair.protocol.Event
import com.castair.protocol.parseCommand
import com.castair.protocol.toJson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CastReceiverService : Service() {

    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        acquireLocks()

        if (server == null) {
            startServer()
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("CastairMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            val powerManager = getSystemService(POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Castair:WakeLock")?.apply {
                acquire(10 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire locks", e)
        }
    }

    private fun startServer() {
        Thread {
            try {
                System.setProperty("java.net.preferIPv4Stack", "true")

                val serverInstance = embeddedServer(
                    factory = CIO,
                    port = DEFAULT_PORT,
                    host = "0.0.0.0",
                ) {
                    castReceiverModule(this@CastReceiverService)
                }

                server = serverInstance
                serverInstance.start(wait = false)

                Log.i(TAG, "Server listening on port $DEFAULT_PORT")
                registerNsdService()
            } catch (e: Exception) {
                Log.e(TAG, "Server failed to start", e)
            }
        }.start()
    }

    private fun registerNsdService() {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Castair-${Build.MODEL}"
            serviceType = "_castair._tcp."
            port = DEFAULT_PORT
        }

        nsdManager = (getSystemService(NSD_SERVICE) as? NsdManager)?.apply {
            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS Service successfully registered: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS Registration failed: error code $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS Service unregistered")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS Unregistration failed: error code $errorCode")
                }
            }
            try {
                registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register NSD service", e)
            }
        }
    }

    override fun onDestroy() {
        val s = server
        server = null
        Thread { s?.stop(500, 1000) }.start()

        try {
            registrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering NSD listener", e)
        }

        try {
            if (multicastLock?.isHeld == true) multicastLock?.release()
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake/multicast locks", e)
        }

        PlayerManager.release()

        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Castair Receiver", NotificationManager.IMPORTANCE_LOW),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Castair receiver is running")
            .setContentText("Listening on port $DEFAULT_PORT")
            .setOngoing(true)
            .build()
    }

    companion object {
        internal const val TAG = "CastairReceiver"
        private const val CHANNEL_ID = "receiver_channel"
        private const val NOTIFICATION_ID = 101
    }
}

private fun Application.castReceiverModule(serviceContext: Context) {
    install(WebSockets)
    routing {
        webSocket("/") {
            Log.i(CastReceiverService.TAG, "Client connected")
            val sendMutex = Mutex()

            suspend fun safeSend(text: String) {
                sendMutex.withLock {
                    runCatching {
                        kotlinx.coroutines.withTimeoutOrNull(2000) {
                            send(text)
                        }
                    }
                }
            }

            PlayerManager.setConnectedDevice("Phone")
            safeSend(Event.Welcome(deviceName = Build.MODEL).toJson())

            val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val stateJob = sessionScope.launch {
                PlayerManager.playbackState.collect { state ->
                    safeSend(state.toJson())
                }
            }

            val tracksJob = sessionScope.launch {
                PlayerManager.availableTracks.collect { tracks ->
                    safeSend(tracks.toJson())
                }
            }

            try {
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val rawText = frame.readText()
                    Log.i(CastReceiverService.TAG, "Received raw message: $rawText")

                    val command = parseCommand(rawText)
                    Log.i(CastReceiverService.TAG, "Parsed command: $command")

                    when (command) {
                        is Command.Pair -> {
                            PlayerManager.setConnectedDevice(command.deviceName)
                        }
                        is Command.Hello -> {
                            PlayerManager.setConnectedDevice("Phone")
                        }
                        is Command.GetState -> {
                            safeSend(PlayerManager.getCurrentState().toJson())
                        }
                        is Command.Play -> {
                            PlayerManager.play(serviceContext, command.item)

                            val launchIntent = Intent(serviceContext, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            serviceContext.startActivity(launchIntent)
                        }
                        is Command.Pause -> {
                            PlayerManager.pause()
                        }
                        is Command.Resume -> {
                            PlayerManager.resume()
                        }
                        is Command.Stop -> {
                            PlayerManager.stop()
                        }
                        is Command.Seek -> {
                            PlayerManager.seekTo(command.positionMs)
                        }
                        is Command.SetVolume -> {
                            PlayerManager.setVolume(command.percent)
                        }
                        is Command.SetQuality -> {
                            PlayerManager.setQuality(command.height)
                        }
                        is Command.SetAudioTrack -> {
                            PlayerManager.setAudioTrack(command.id)
                        }
                        is Command.SetSubtitleTrack -> {
                            PlayerManager.setSubtitleTrack(command.id)
                        }
                        else -> {
                            Log.d(CastReceiverService.TAG, "Unhandled command: $command")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(CastReceiverService.TAG, "WebSocket error: ${e.message}", e)
            } finally {
                tracksJob.cancel()
                stateJob.cancel()
                sessionScope.cancel()
                PlayerManager.setConnectedDevice(null)
                Log.i(CastReceiverService.TAG, "Client disconnected")
            }
        }
    }
}
