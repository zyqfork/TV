package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private var multicastLock: WifiManager.MulticastLock? = null
    private var raopRegistration: NsdManager.RegistrationListener? = null
    private var airplayRegistration: NsdManager.RegistrationListener? = null
    private var released = false

    private var raopName: String? = null
    private var raopPort: Int = 0
    private var raopTxt: Map<String, String> = emptyMap()
    private var raopAttempt = 0
    private var raopRetry: Runnable? = null

    private var airplayName: String? = null
    private var airplayPort: Int = 0
    private var airplayTxt: Map<String, String> = emptyMap()
    private var airplayAttempt = 0
    private var airplayRetry: Runnable? = null

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun registerRaop(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        raopName = serviceName
        raopPort = port
        raopTxt = txtRecords.toMap()
        raopAttempt = 0
        cancelRaopRetry()
        doRegisterRaop()
    }

    fun registerAirplay(serviceName: String, port: Int, txtRecords: Map<String, String>) {
        airplayName = serviceName
        airplayPort = port
        airplayTxt = txtRecords.toMap()
        airplayAttempt = 0
        cancelAirplayRetry()
        doRegisterAirplay()
    }

    private fun doRegisterRaop() {
        if (released) return
        val name = raopName ?: return
        unregisterRaopOnly()
        val info = NsdServiceInfo().apply {
            this.serviceName = name
            serviceType = "_raop._tcp"
            this.port = raopPort
            raopTxt.forEach { (k, v) -> setAttribute(k, v) }
        }
        raopRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP registered: ${info.serviceName}")
                raopAttempt = 0
                cancelRaopRetry()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP registration failed: $code")
                scheduleRaopRetry()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "RAOP unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "RAOP unregister failed: $code")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, raopRegistration)
        } catch (e: Exception) {
            Log.e(TAG, "RAOP register threw", e)
            scheduleRaopRetry()
        }
    }

    private fun doRegisterAirplay() {
        if (released) return
        val name = airplayName ?: return
        unregisterAirplayOnly()
        val info = NsdServiceInfo().apply {
            this.serviceName = name
            serviceType = "_airplay._tcp"
            this.port = airplayPort
            airplayTxt.forEach { (k, v) -> setAttribute(k, v) }
        }
        airplayRegistration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay registered: ${info.serviceName}")
                airplayAttempt = 0
                cancelAirplayRetry()
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay registration failed: $code")
                scheduleAirplayRetry()
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "AirPlay unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "AirPlay unregister failed: $code")
            }
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, airplayRegistration)
        } catch (e: Exception) {
            Log.e(TAG, "AirPlay register threw", e)
            scheduleAirplayRetry()
        }
    }

    private fun scheduleRaopRetry() {
        if (released || raopAttempt >= MAX_RETRY) return
        cancelRaopRetry()
        val delay = retryDelayMs(raopAttempt++)
        raopRetry = Runnable { doRegisterRaop() }
        handler.postDelayed(raopRetry!!, delay)
        Log.i(TAG, "RAOP retry in ${delay}ms (attempt $raopAttempt)")
    }

    private fun scheduleAirplayRetry() {
        if (released || airplayAttempt >= MAX_RETRY) return
        cancelAirplayRetry()
        val delay = retryDelayMs(airplayAttempt++)
        airplayRetry = Runnable { doRegisterAirplay() }
        handler.postDelayed(airplayRetry!!, delay)
        Log.i(TAG, "AirPlay retry in ${delay}ms (attempt $airplayAttempt)")
    }

    private fun retryDelayMs(attempt: Int): Long {
        val shift = attempt.coerceAtMost(5)
        return (BASE_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    private fun cancelRaopRetry() {
        raopRetry?.let { handler.removeCallbacks(it) }
        raopRetry = null
    }

    private fun cancelAirplayRetry() {
        airplayRetry?.let { handler.removeCallbacks(it) }
        airplayRetry = null
    }

    private fun unregisterRaopOnly() {
        raopRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            raopRegistration = null
        }
    }

    private fun unregisterAirplayOnly() {
        airplayRegistration?.let {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
            airplayRegistration = null
        }
    }

    fun unregisterAll() {
        cancelRaopRetry()
        cancelAirplayRetry()
        unregisterRaopOnly()
        unregisterAirplayOnly()
    }

    fun release() {
        released = true
        unregisterAll()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val MAX_RETRY = 8
        private const val BASE_RETRY_MS = 1_000L
        private const val MAX_RETRY_MS = 30_000L
    }
}
