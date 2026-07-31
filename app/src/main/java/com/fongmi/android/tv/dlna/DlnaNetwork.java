package com.fongmi.android.tv.dlna;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.text.TextUtils;

import com.github.catvod.Init;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

public class DlnaNetwork {

    public record Iface(String name, String address) {

        public String label() {
            return TextUtils.isEmpty(address) ? name : name + " (" + address + ")";
        }
    }

    public static List<Iface> listUsable() {
        List<Iface> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            if (en == null) return list;
            for (NetworkInterface nif : Collections.list(en)) {
                if (!isCandidate(nif)) continue;
                String ip = firstIpv4(nif);
                if (TextUtils.isEmpty(ip)) continue;
                list.add(new Iface(nif.getName(), ip));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** Empty preferred = follow system default / active network. */
    public static String resolveInterfaceName(String preferred) {
        List<Iface> ifaces = listUsable();
        if (ifaces.isEmpty()) return "";
        if (!TextUtils.isEmpty(preferred)) {
            for (Iface item : ifaces) if (item.name().equals(preferred)) return preferred;
        }
        String active = getActiveIfaceName();
        if (!TextUtils.isEmpty(active)) {
            for (Iface item : ifaces) if (item.name().equals(active)) return active;
        }
        for (Iface item : ifaces) if (item.name().toLowerCase(Locale.US).startsWith("eth")) return item.name();
        for (Iface item : ifaces) if (item.name().toLowerCase(Locale.US).startsWith("wlan")) return item.name();
        return ifaces.get(0).name();
    }

    public static Iface find(String name) {
        if (TextUtils.isEmpty(name)) return null;
        for (Iface item : listUsable()) if (item.name().equals(name)) return item;
        return null;
    }

    public static Iface resolve(String preferred) {
        return find(resolveInterfaceName(preferred));
    }

    public static Iface defaultIface() {
        return resolve("");
    }

    public static String getActiveIfaceName() {
        try {
            Context context = Init.context();
            if (context == null) return "";
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "";
            Network network = cm.getActiveNetwork();
            if (network == null) return "";
            LinkProperties props = cm.getLinkProperties(network);
            return props == null || props.getInterfaceName() == null ? "" : props.getInterfaceName();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isCandidate(NetworkInterface nif) throws Exception {
        if (nif == null || !nif.isUp() || nif.isLoopback() || nif.isPointToPoint() || nif.isVirtual()) return false;
        String name = nif.getName().toLowerCase(Locale.US);
        String display = nif.getDisplayName() == null ? "" : nif.getDisplayName().toLowerCase(Locale.US);
        if (name.startsWith("lo") || name.startsWith("dummy") || name.startsWith("docker") || name.startsWith("br-")) return false;
        if (name.startsWith("veth") || name.startsWith("tun") || name.startsWith("tap") || name.startsWith("p2p")) return false;
        if (name.startsWith("rmnet") || name.startsWith("tailscale") || name.startsWith("wg") || name.startsWith("vpn")) return false;
        if (name.startsWith("vmnet") || name.startsWith("vnic") || display.contains("vmnet")) return false;
        return true;
    }

    private static String firstIpv4(NetworkInterface nif) {
        try {
            for (InetAddress address : Collections.list(nif.getInetAddresses())) {
                if (address instanceof Inet4Address && !address.isLoopbackAddress()) return address.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
