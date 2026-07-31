package io.github.jqssun.airplay

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.text.TextUtils
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/** Resolve preferred network interface for AirPlay identity / MAC selection. */
object NetworkPrefs {

    data class Iface(val name: String, val address: String)

    fun listUsable(): List<Iface> {
        val list = ArrayList<Iface>()
        try {
            val en = NetworkInterface.getNetworkInterfaces() ?: return list
            for (nif in Collections.list(en)) {
                if (!isCandidate(nif)) continue
                val ip = firstIpv4(nif) ?: continue
                list.add(Iface(nif.name, ip))
            }
        } catch (_: Exception) {
        }
        return list
    }

    fun resolveName(context: Context, prefs: SharedPreferences): String {
        val ifaces = listUsable()
        if (ifaces.isEmpty()) return ""
        val preferred = prefs.getString(Prefs.IFACE_NAME, Prefs.DEF_IFACE_NAME).orEmpty()
        if (preferred.isNotEmpty()) {
            ifaces.firstOrNull { it.name == preferred }?.let { return it.name }
        }
        val active = activeIfaceName(context)
        if (active.isNotEmpty()) ifaces.firstOrNull { it.name == active }?.let { return it.name }
        ifaces.firstOrNull { it.name.lowercase(Locale.US).startsWith("eth") }?.let { return it.name }
        ifaces.firstOrNull { it.name.lowercase(Locale.US).startsWith("wlan") }?.let { return it.name }
        return ifaces[0].name
    }

    fun macFor(context: Context, prefs: SharedPreferences): ByteArray? {
        val name = resolveName(context, prefs)
        if (name.isEmpty()) return null
        return try {
            NetworkInterface.getByName(name)?.hardwareAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun activeIfaceName(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ""
            val network = cm.activeNetwork ?: return ""
            cm.getLinkProperties(network)?.interfaceName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun isCandidate(nif: NetworkInterface): Boolean {
        return try {
            if (!nif.isUp || nif.isLoopback || nif.isPointToPoint || nif.isVirtual) return false
            val name = nif.name.lowercase(Locale.US)
            val display = nif.displayName?.lowercase(Locale.US).orEmpty()
            if (name.startsWith("lo") || name.startsWith("dummy") || name.startsWith("docker") || name.startsWith("br-")) return false
            if (name.startsWith("veth") || name.startsWith("tun") || name.startsWith("tap") || name.startsWith("p2p")) return false
            if (name.startsWith("rmnet") || name.startsWith("tailscale") || name.startsWith("wg") || name.startsWith("vpn")) return false
            if (name.startsWith("vmnet") || name.startsWith("vnic") || display.contains("vmnet")) return false
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun firstIpv4(nif: NetworkInterface): String? {
        return try {
            Collections.list(nif.inetAddresses)
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
