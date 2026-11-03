package com.castair

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.castair.ui.theme.CastairTheme

class MainActivity : ComponentActivity() {

    private lateinit var discoveryManager: TvDiscoveryManager
    private lateinit var clientManager: CastClientManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        discoveryManager = TvDiscoveryManager(applicationContext)
        clientManager = CastClientManager()

        setContent {
            CastairTheme {
                BrowserScreen(
                    discoveryManager = discoveryManager,
                    clientManager = clientManager,
                )
            }
        }
    }

    override fun onDestroy() {
        clientManager.disconnect()
        discoveryManager.stopDiscovery()
        super.onDestroy()
    }
}
