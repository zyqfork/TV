package com.github.catvod.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import com.github.catvod.Init;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Which network adapter this device publishes to the LAN.
 *
 * A host can have several: the real LAN plus Docker, VM and tunnel adapters. Every one of them is
 * up with a site-local address, so "the first address we find" advertises something no LAN peer can
 * reach — the server QR code, /device discovery and cast callbacks all break while looking healthy.
 * Only real LAN interfaces are candidates, unless the user picks one explicitly.
 */
public class NetIface {

    public static final String KEY = "net_iface";

    public record Iface(String name, String address) {

        public String label() {
            return address == null || address.isEmpty() ? name : name + " (" + address + ")";
        }
    }

    /** User's choice, or "" to choose automatically. */
    public static String get() {
        return Prefers.getString(KEY).trim();
    }

    public static void put(String name) {
        Prefers.put(KEY, name == null ? "" : name.trim());
    }

    /** Interfaces a LAN peer can reach, in system order. */
    public static List<Iface> list() {
        List<Iface> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return list;
            for (NetworkInterface nif : Collections.list(en)) {
                if (!nif.isUp() || !isLanReachable(nif.getName())) continue;
                String ip = firstIpv4(nif);
                if (!ip.isEmpty()) list.add(new Iface(nif.getName(), ip));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /**
     * Name of the interface to publish on, or "" when the device has none.
     *
     * User choice wins. Otherwise a dedicated local link is preferred over the interface carrying
     * the default route: that one is the uplink (Wi-Fi, or a container/NAT bridge), while the
     * address peers must reach belongs to the LAN-facing interface. Single-adapter devices have
     * only one candidate, so they are unaffected. Falls back to Ethernet, then Wi-Fi.
     */
    public static String resolveName() {
        List<Iface> list = list();
        if (list.isEmpty()) return "";
        String chosen = get();
        if (!chosen.isEmpty()) {
            for (Iface item : list) if (item.name().equals(chosen)) return chosen;
        }
        String active = getActiveName();
        List<Iface> local = new ArrayList<>();
        for (Iface item : list) if (!item.name().equals(active)) local.add(item);
        String name = prefer(local);
        if (!name.isEmpty()) return name;
        if (!active.isEmpty()) {
            for (Iface item : list) if (item.name().equals(active)) return active;
        }
        return prefer(list);
    }

    /** Ethernet first, then Wi-Fi, then whatever the system listed first. */
    private static String prefer(List<Iface> list) {
        for (Iface item : list) if (item.name().toLowerCase(Locale.US).startsWith("eth")) return item.name();
        for (Iface item : list) if (item.name().toLowerCase(Locale.US).startsWith("wlan")) return item.name();
        return list.isEmpty() ? "" : list.get(0).name();
    }

    /** Address to publish, or "" when nothing usable was found. */
    public static String resolve() {
        String name = resolveName();
        for (Iface item : list()) if (item.name().equals(name)) return item.address();
        return "";
    }

    /**
     * Whether a LAN peer could reach an address on this interface.
     *
     * Docker/VM bridges, tunnels and VPNs are up with a routable-looking address, so they pass every
     * other check while being unreachable from the LAN. Before an address is bound, the interface
     * name is the only signal available.
     */
    public static boolean isLanReachable(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith("lo") || lower.startsWith("dummy") || lower.startsWith("docker")) return false;
        if (lower.startsWith("br-") || lower.startsWith("veth") || lower.startsWith("virbr")) return false;
        if (lower.startsWith("tun") || lower.startsWith("tap") || lower.startsWith("p2p")) return false;
        if (lower.startsWith("rmnet") || lower.startsWith("tailscale") || lower.startsWith("wg")) return false;
        if (lower.startsWith("vpn") || lower.startsWith("vmnet") || lower.startsWith("vnic")) return false;
        return true;
    }

    public static String firstIpv4(NetworkInterface nif) {
        try {
            for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String getActiveName() {
        try {
            Context context = Init.context();
            if (context == null) return "";
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return "";
            Network network = manager.getActiveNetwork();
            if (network == null) return "";
            LinkProperties properties = manager.getLinkProperties(network);
            String name = properties == null ? null : properties.getInterfaceName();
            return name == null ? "" : name;
        } catch (Exception e) {
            return "";
        }
    }
}
