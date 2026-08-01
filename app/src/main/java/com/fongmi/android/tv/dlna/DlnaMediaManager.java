package com.fongmi.android.tv.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.DlnaEntry;
import com.fongmi.android.tv.service.DlnaBrowserService;

import org.jupnp.android.AndroidUpnpService;
import org.jupnp.controlpoint.ControlPoint;
import org.jupnp.model.action.ActionInvocation;
import org.jupnp.model.message.UpnpResponse;
import org.jupnp.model.message.header.STAllHeader;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.support.contentdirectory.callback.Browse;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class DlnaMediaManager extends DefaultRegistryListener implements ServiceConnection {

    private static final UDADeviceType SERVER_TYPE = new UDADeviceType("MediaServer", 1);
    private static final UDAServiceType CDS_TYPE = new UDAServiceType("ContentDirectory", 1);
    private static final long BROWSE_COUNT = 200L;

    private AndroidUpnpService upnpService;
    private DeviceListener deviceListener;
    private boolean bound;

    public static DlnaMediaManager get() {
        return Loader.INSTANCE;
    }

    @Override
    public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
        if (device.getType().implementsVersion(SERVER_TYPE)) notifyAdded(Device.get(device));
    }

    @Override
    public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
        if (device.getType().implementsVersion(SERVER_TYPE)) notifyRemoved(Device.get(device));
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        if (!bound) return;
        attach((AndroidUpnpService) binder);
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        detach();
    }

    public void setDeviceListener(DeviceListener listener) {
        deviceListener = listener;
    }

    private void notifyAdded(Device bean) {
        if (deviceListener != null) App.post(() -> deviceListener.onDeviceAdded(bean));
    }

    private void notifyRemoved(Device bean) {
        if (deviceListener != null) App.post(() -> deviceListener.onDeviceRemoved(bean));
    }

    public void init(Context context) {
        if (bound) {
            search();
        } else {
            bind(context.getApplicationContext());
        }
    }

    public void search() {
        if (upnpService != null) upnpService.getControlPoint().search(new STAllHeader());
    }

    public List<Device> getRegistered() {
        if (upnpService == null) return List.of();
        return upnpService.getRegistry().getDevices(SERVER_TYPE).stream().map(d -> Device.get((RemoteDevice) d)).collect(Collectors.toList());
    }

    public RemoteDevice findDevice(String uuid) {
        if (upnpService == null || TextUtils.isEmpty(uuid)) return null;
        return upnpService.getRegistry().getDevices(SERVER_TYPE).stream()
                .filter(d -> d.getIdentity().getUdn().getIdentifierString().equals(uuid))
                .map(d -> (RemoteDevice) d)
                .findFirst()
                .orElse(null);
    }

    public RemoteService findContentDirectory(String uuid) {
        RemoteDevice device = findDevice(uuid);
        return device != null ? device.findService(CDS_TYPE) : null;
    }

    public ControlPoint getControlPoint() {
        return upnpService != null ? upnpService.getControlPoint() : null;
    }

    public void browse(String uuid, String objectId, BrowseCallback callback) {
        ControlPoint control = getControlPoint();
        RemoteService service = findContentDirectory(uuid);
        if (control == null || service == null) {
            if (callback != null) App.post(() -> callback.onError(null));
            return;
        }
        String id = TextUtils.isEmpty(objectId) ? "0" : objectId;
        control.execute(new Browse(service, id, BrowseFlag.DIRECT_CHILDREN, Browse.CAPS_WILDCARD, 0, BROWSE_COUNT) {
            @Override
            public void received(ActionInvocation invocation, DIDLContent didl) {
                List<DlnaEntry> entries = toEntries(didl);
                if (callback != null) App.post(() -> callback.onSuccess(entries));
            }

            @Override
            public void updateStatus(Status status) {
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                if (callback != null) App.post(() -> callback.onError(defaultMsg));
            }
        });
    }

    private List<DlnaEntry> toEntries(DIDLContent didl) {
        List<DlnaEntry> items = new ArrayList<>();
        if (didl == null) return items;
        for (Container container : didl.getContainers()) {
            items.add(new DlnaEntry(container.getId(), container.getTitle(), true, pickUrl(container.getResources()), mimeOf(container.getFirstResource())));
        }
        for (Item item : didl.getItems()) {
            items.add(new DlnaEntry(item.getId(), item.getTitle(), false, pickUrl(item.getResources()), mimeOf(item.getFirstResource())));
        }
        return items;
    }

    private static String pickUrl(List<Res> resources) {
        if (resources == null || resources.isEmpty()) return "";
        String fallback = "";
        for (Res res : resources) {
            String value = res == null ? null : res.getValue();
            if (TextUtils.isEmpty(value)) continue;
            String lower = value.toLowerCase(Locale.US);
            if (!lower.startsWith("http://") && !lower.startsWith("https://")) continue;
            String mime = mimeOf(res);
            if (mime.startsWith("video/") || mime.startsWith("audio/") || mime.contains("mpegurl") || mime.contains("mpegts")) {
                return value;
            }
            if (fallback.isEmpty()) fallback = value;
        }
        return fallback;
    }

    private static String mimeOf(Res res) {
        if (res == null) return "";
        ProtocolInfo info = res.getProtocolInfo();
        if (info == null) return "";
        String format = info.getContentFormat();
        return format == null ? "" : format.toLowerCase(Locale.US);
    }

    public void release(Context context) {
        detach();
        unbind(context.getApplicationContext());
    }

    private void bind(Context context) {
        bound = context.bindService(new Intent(context, DlnaBrowserService.class), this, Context.BIND_AUTO_CREATE);
    }

    private void unbind(Context context) {
        if (!bound) return;
        context.unbindService(this);
        bound = false;
    }

    private void attach(AndroidUpnpService service) {
        detach();
        upnpService = service;
        upnpService.getRegistry().addListener(this);
        search();
        if (deviceListener != null) {
            List<Device> devices = getRegistered();
            App.post(() -> {
                if (deviceListener == null) return;
                for (Device device : devices) deviceListener.onDeviceAdded(device);
            });
        }
    }

    private void detach() {
        if (upnpService != null) upnpService.getRegistry().removeListener(this);
        upnpService = null;
    }

    public interface DeviceListener {

        void onDeviceAdded(Device device);

        void onDeviceRemoved(Device device);
    }

    public interface BrowseCallback {

        void onSuccess(List<DlnaEntry> entries);

        void onError(String msg);
    }

    private static class Loader {
        static final DlnaMediaManager INSTANCE = new DlnaMediaManager();
    }
}
