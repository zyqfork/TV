package com.fongmi.android.tv.ui.dialog;

import android.content.Context;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.NetIface;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses the interface whose address this device publishes to the LAN.
 *
 * Shared by both flavours so the TV and the phone cannot drift apart. Separate from the DLNA and
 * AirPlay interface settings: those decide where SSDP multicasts, this decides the address the
 * server advertises (QR code, /device, cast callback).
 */
public class NetIfaceDialog {

    public static void show(Context context, Runnable onChanged) {
        List<NetIface.Iface> list = NetIface.list();
        if (list.isEmpty()) {
            Notify.show(R.string.setting_net_iface_none);
            return;
        }
        if (list.size() == 1) {
            // Nothing to choose on a single-adapter device; make sure the value is recorded so the
            // row shows it, and stay silent otherwise.
            String only = list.get(0).name();
            if (!only.equals(NetIface.get())) NetIface.put(only);
            onChanged.run();
            return;
        }
        String current = NetIface.resolveName();
        List<String> labels = new ArrayList<>();
        int selected = 0;
        for (int i = 0; i < list.size(); i++) {
            labels.add(list.get(i).label());
            if (list.get(i).name().equals(current)) selected = i;
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.setting_net_iface)
                .setSingleChoiceItems(labels.toArray(new String[0]), selected, (dialog, which) -> {
                    dialog.dismiss();
                    NetIface.put(list.get(which).name());
                    onChanged.run();
                })
                .show();
    }
}
