package com.fongmi.android.tv.setting;

import android.text.TextUtils;

import com.fongmi.android.tv.dlna.DlnaNetwork;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.utils.Prefers;

public class DlnaSetting {

    public static boolean isEnabled() {
        return Prefers.getBoolean("dlna_enabled", true);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("dlna_enabled", enabled);
    }

    public static String getName() {
        return Prefers.getString("dlna_name");
    }

    public static void putName(String name) {
        Prefers.put("dlna_name", name == null ? "" : name.trim());
    }

    public static String getDisplayName() {
        String name = getName();
        return TextUtils.isEmpty(name) ? Util.getDeviceName() : name;
    }

    public static String getInterface() {
        return Prefers.getString("dlna_iface");
    }

    public static void putInterface(String name) {
        Prefers.put("dlna_iface", name == null ? "" : name.trim());
    }

    /** If user never chose an interface, pick system default once at startup. */
    public static void ensureDefaultInterface() {
        if (!TextUtils.isEmpty(getInterface())) return;
        String name = DlnaNetwork.resolveInterfaceName("");
        if (!TextUtils.isEmpty(name)) putInterface(name);
    }

    public static String resolveInterfaceName() {
        ensureDefaultInterface();
        return DlnaNetwork.resolveInterfaceName(getInterface());
    }

    public static DlnaNetwork.Iface resolveIface() {
        return DlnaNetwork.resolve(resolveInterfaceName());
    }

    public static int getHttpPort() {
        return Math.clamp(Prefers.getInt("dlna_http_port", 0), 0, 65535);
    }

    public static void putHttpPort(int port) {
        if (port < 0 || port > 65535) port = 0;
        Prefers.put("dlna_http_port", port);
    }
}
