package com.castair

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import com.castair.protocol.MDNS_SERVICE_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CastDevice(
    val name: String,
    val host: String,
    val port: Int
)

class TvDiscoveryManager(private val context: Context) {

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var isResolving = false

    fun startDiscovery() {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        try {
            multicastLock = wifiManager?.createMulticastLock("CastairClientMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock", e)
        }

        nsdManager = (context.getSystemService(Context.NSD_SERVICE) as? NsdManager)

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "mDNS Service discovery started for $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.i(TAG, "mDNS Service found: ${service.serviceName}")
                if (service.serviceType.contains("_castair")) {
                    enqueueResolve(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.i(TAG, "mDNS Service lost: ${service.serviceName}")
                _devices.value = _devices.value.filterNot { it.name == service.serviceName }
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "mDNS Discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Start discovery failed: $errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "mDNS Stop discovery failed: $errorCode")
            }
        }

        try {
            nsdManager?.discoverServices(
                MDNS_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                discoveryListener
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service discovery", e)
        }
    }

    private fun enqueueResolve(serviceInfo: NsdServiceInfo) {
        synchronized(resolveQueue) {
            if (resolveQueue.none { it.serviceName == serviceInfo.serviceName }) {
                resolveQueue.addLast(serviceInfo)
            }
            processResolveQueue()
        }
    }

    private fun processResolveQueue() {
        synchronized(resolveQueue) {
            if (isResolving || resolveQueue.isEmpty()) return
            val nextService = resolveQueue.removeFirst()
            isResolving = true
            resolveNext(nextService)
        }
    }

    private fun resolveNext(serviceInfo: NsdServiceInfo) {
        nsdManager?.resolveService(serviceInfo, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${serviceInfo.serviceName}: error $errorCode")
                synchronized(resolveQueue) {
                    isResolving = false
                    processResolveQueue()
                }
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val inetAddr = serviceInfo.host
                var host = inetAddr?.hostAddress ?: return
                if (host.contains("%")) {
                    host = host.substringBefore("%")
                }
                val port = serviceInfo.port
                val deviceName = serviceInfo.serviceName.removePrefix("Castair-")

                Log.i(TAG, "Resolved Castair TV: $deviceName at $host:$port")
                val newDevice = CastDevice(name = deviceName, host = host, port = port)
                _devices.value = (_devices.value.filterNot { it.host == host } + newDevice)

                synchronized(resolveQueue) {
                    isResolving = false
                    processResolveQueue()
                }
            }
        })
    }

    fun stopDiscovery() {
        synchronized(resolveQueue) {
            resolveQueue.clear()
            isResolving = false
        }
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping NSD discovery", e)
        } finally {
            discoveryListener = null
        }

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing multicast lock", e)
        }
    }

    companion object {
        private const val TAG = "TvDiscoveryManager"
    }
}
