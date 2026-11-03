package com.castair.receiver

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.ui.PlayerView
import com.castair.protocol.DEFAULT_PORT
import com.castair.protocol.PlayStatus
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusTextView: TextView
    private lateinit var playerView: PlayerView
    private lateinit var idleOverlay: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusTextView = findViewById(R.id.status)
        playerView = findViewById(R.id.player_view)
        idleOverlay = findViewById(R.id.idle_overlay)

        findViewById<View>(R.id.main)?.let { mainView ->
            ViewCompat.setOnApplyWindowInsetsListener(mainView) { v, insets ->
                val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
                insets
            }
        }

        ContextCompat.startForegroundService(
            this,
            Intent(this, CastReceiverService::class.java),
        )

        playerView.player = PlayerManager.getPlayer(this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PlayerManager.playbackState.collect { state ->
                    when (state.status) {
                        PlayStatus.PLAYING, PlayStatus.BUFFERING, PlayStatus.PAUSED, PlayStatus.LOADING -> {
                            idleOverlay.visibility = View.GONE
                            playerView.visibility = View.VISIBLE
                            playerView.requestFocus()
                        }
                        PlayStatus.IDLE -> {
                            idleOverlay.visibility = View.VISIBLE
                            updateDisplayAddress()
                        }
                        PlayStatus.CONNECTED -> {
                            idleOverlay.visibility = View.VISIBLE
                            val devName = state.connectedDeviceName ?: "Phone"
                            statusTextView.text = "Connected to $devName\nReady to cast videos"
                        }
                        PlayStatus.ENDED -> {
                            idleOverlay.visibility = View.VISIBLE
                            val devName = state.connectedDeviceName
                            val address = localIpv4() ?: ""
                            val connInfo = if (devName != null) "Connected to $devName\n" else ""
                            statusTextView.text = "Playback Finished: ${state.title}\n${connInfo}$address:$DEFAULT_PORT"
                        }
                        PlayStatus.ERROR -> {
                            idleOverlay.visibility = View.VISIBLE
                            val address = localIpv4() ?: ""
                            statusTextView.text = "Playback Error\n$address:$DEFAULT_PORT"
                        }
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            if (PlayerManager.playbackState.value.status != PlayStatus.IDLE) {
                PlayerManager.stop()
            } else {
                finish()
            }
        }

        updateDisplayAddress()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        playerView.player = PlayerManager.getPlayer(this)
    }

    override fun onResume() {
        super.onResume()
        updateDisplayAddress()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                PlayerManager.togglePlayPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                PlayerManager.seekBy(-10_000L)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                PlayerManager.seekBy(10_000L)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun updateDisplayAddress() {
        val address = localIpv4() ?: "Not connected to Wi-Fi or Ethernet"
        val state = PlayerManager.playbackState.value
        when (state.status) {
            PlayStatus.CONNECTED -> {
                val devName = state.connectedDeviceName ?: "Phone"
                statusTextView.text = "Connected to $devName\nReady to cast videos"
            }
            PlayStatus.ENDED -> {
                statusTextView.text = "Playback Finished: ${state.title}\n$address:$DEFAULT_PORT"
            }
            PlayStatus.ERROR -> {
                statusTextView.text = "Playback Error\n$address:$DEFAULT_PORT"
            }
            else -> {
                statusTextView.text = "Waiting for your phone\n$address:$DEFAULT_PORT"
            }
        }
    }

    private fun localIpv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()
}
