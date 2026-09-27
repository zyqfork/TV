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
import org.jupnp.model.message.header.DeviceTypeHeader;
import org.jupnp.model.message.header.STAllHeader;
import org.jupnp.model.meta.RemoteDevice;
import org.jupnp.model.meta.RemoteService;
import org.jupnp.model.types.UDADeviceType;
import org.jupnp.model.types.UDAServiceType;
import org.jupnp.registry.DefaultRegistryListener;
import org.jupnp.registry.Registry;
import org.jupnp.support.contentdirectory.callback.Browse;
import org.jupnp.support.model.BrowseFlag;
import org.jupnp.support.model.BrowseResult;
import org.jupnp.support.model.DIDLContent;
import org.jupnp.support.model.ProtocolInfo;
import org.jupnp.support.model.Res;
import org.jupnp.support.model.container.Container;
import org.jupnp.support.model.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.stream.Collectors;

public class DlnaMediaManager extends DefaultRegistryListener implements ServiceConnection {

    private static final UDADeviceType SERVER_TYPE = new UDADeviceType("MediaServer", 1);
    private static final UDAServiceType CDS_TYPE = new UDAServiceType("ContentDirectory", 1);
    private static final long BROWSE_COUNT = 500L;
    /** Conservative page size some older ContentDirectory servers need; see browsePage(). */
    private static final long BROWSE_COUNT_FALLBACK = 200L;
    private static final int MAX_BROWSE_ENTRIES = 2000;
    private static final int MAX_TITLE_LENGTH = 512;
    private static final int MAX_URL_LENGTH = 8192;
    private static final int MAX_ATTACH_RETRIES = 10;
    private static final long ATTACH_RETRY_MS = 150L;
    // IPTV boxes (e.g. IPNP-iptv) often answer M-SEARCH late; retry early and often
    // instead of making the user stare at an empty list for 3–8s.
    private static final long RESCAN_DELAY_MS = 400L;
    private static final long RESCAN_EXTRA_DELAY_MS = 1_200L;
    private static final long RESCAN_LATE_DELAY_MS = 3_500L;

    private final Set<DeviceListener> deviceListeners = new CopyOnWriteArraySet<>();
    private final Runnable firstRescan = this::searchIfBound;
    private final Runnable secondRescan = this::searchIfBound;
    private final Runnable lateRescan = this::lateRescanOrFallback;
    private AndroidUpnpService upnpService;
    private Context appContext;
    private int bindCount;
    private int attachGeneration;
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
        bound = false;
        if (bindCount > 0 && appContext != null) App.post(() -> {
            synchronized (DlnaMediaManager.this) {
                if (!bound && bindCount > 0) bind(appContext);
            }
        }, 1000);
    }

    public void addDeviceListener(DeviceListener listener) {
        if (listener != null) deviceListeners.add(listener);
    }

    public void removeDeviceListener(DeviceListener listener) {
        if (listener != null) deviceListeners.remove(listener);
    }

    private void notifyAdded(Device bean) {
        for (DeviceListener listener : deviceListeners) App.post(() -> listener.onDeviceAdded(bean));
    }

    private void notifyRemoved(Device bean) {
        for (DeviceListener listener : deviceListeners) App.post(() -> listener.onDeviceRemoved(bean));
    }

    public synchronized void init(Context context) {
        appContext = context.getApplicationContext();
        bindCount++;
        // Search immediately; attach completion will search again once registry is ready.
        searchWithRescan();
        if (!bound) bind(appContext);
    }

    public void search() {
        ControlPoint control = getControlPoint();
        // Target MediaServer:1 instead of ssdp:all — far fewer description fetches on
        // a busy LAN, so the IPTV box can show up sooner.
        if (control != null) control.search(new DeviceTypeHeader(SERVER_TYPE));
    }

    /**
     * Broad safety net for devices that only answer {@code ssdp:all}, which cheap IPTV boxes
     * frequently do even though UPnP requires a response to their implemented device type.
     */
    private void searchAll() {
        ControlPoint control = getControlPoint();
        if (control != null) control.search(new STAllHeader());
    }

    /** Immediate search plus early retries for devices that answer M-SEARCH late. */
    public void searchWithRescan() {
        search();
        scheduleRescan();
    }

    /** Bounded follow-up searches catch slow UPnP devices (IPTV boxes) that answer late. */
    private synchronized void scheduleRescan() {
        App.removeCallbacks(firstRescan);
        App.removeCallbacks(secondRescan);
        App.removeCallbacks(lateRescan);
        App.post(firstRescan, RESCAN_DELAY_MS);
        App.post(secondRescan, RESCAN_EXTRA_DELAY_MS);
        App.post(lateRescan, RESCAN_LATE_DELAY_MS);
    }

    private synchronized void searchIfBound() {
        if (bindCount > 0) search();
    }

    /**
     * Last scheduled attempt. If the type-targeted searches found nothing, widen once to
     * {@code ssdp:all} so a non-compliant box is still discovered; an empty result here is also
     * indistinguishable from a LAN that genuinely has no media server, so this stays bounded.
     */
    private synchronized void lateRescanOrFallback() {
        if (bindCount <= 0) return;
        if (getRegistered().isEmpty()) searchAll();
        else search();
    }

    public List<Device> getRegistered() {
        Registry registry = getRegistry();
        if (registry == null) return List.of();
        return registry.getDevices(SERVER_TYPE).stream().map(d -> Device.get((RemoteDevice) d)).collect(Collectors.toList());
    }

    public RemoteDevice findDevice(String uuid) {
        Registry registry = getRegistry();
        if (registry == null || TextUtils.isEmpty(uuid)) return null;
        return registry.getDevices(SERVER_TYPE).stream()
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
        try {
            return upnpService != null ? upnpService.getControlPoint() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Registry getRegistry() {
        try {
            return upnpService != null ? upnpService.getRegistry() : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public void browse(String uuid, String objectId, BrowseCallback callback) {
        ControlPoint control = getControlPoint();
        RemoteService service = findContentDirectory(uuid);
        if (control == null || service == null) {
            if (callback != null) App.post(() -> callback.onError(null));
            return;
        }
        String id = TextUtils.isEmpty(objectId) ? "0" : objectId;
        if (id.length() > 1024 || containsControl(id)) {
            if (callback != null) App.post(() -> callback.onError("Invalid ContentDirectory object ID"));
            return;
        }
        browsePage(control, service, id, 0, new ArrayList<>(), BROWSE_COUNT, callback);
    }

    private void browsePage(ControlPoint control, RemoteService service, String id, long start,
                            List<DlnaEntry> accumulated, long count, BrowseCallback callback) {
        control.execute(new Browse(service, id, BrowseFlag.DIRECT_CHILDREN, Browse.CAPS_WILDCARD, start, count) {
            private long numberReturned = -1;
            private long totalMatches = -1;

            @Override
            public boolean receivedRaw(ActionInvocation<?> invocation, BrowseResult result) {
                if (result != null) {
                    numberReturned = result.getCountLong();
                    totalMatches = result.getTotalMatchesLong();
                }
                return super.receivedRaw(invocation, result);
            }

            @Override
            public void received(ActionInvocation invocation, DIDLContent didl) {
                List<DlnaEntry> page = toEntries(didl);
                int room = MAX_BROWSE_ENTRIES - accumulated.size();
                if (page.size() > room) accumulated.addAll(page.subList(0, Math.max(0, room)));
                else accumulated.addAll(page);

                // DIDL conversion intentionally drops unsafe/unsupported resources, so page.size()
                // is not NumberReturned. Advance with the server's raw count or pagination can stop
                // early (or repeatedly request the same page).
                long returned = numberReturned >= 0 ? numberReturned
                        : didl == null ? 0 : didl.getContainers().size() + didl.getItems().size();
                long next = start + returned;
                boolean serverHasMore = totalMatches > 0 ? next < totalMatches : returned >= count;
                if (callback != null) {
                    // Emit only this page so the UI can append without duplicating
                    // the accumulated list.
                    int cap = Math.min(page.size(), Math.max(0, room));
                    List<DlnaEntry> emit = new ArrayList<>(page.subList(0, cap));
                    boolean firstPage = start == 0;
                    boolean more = returned > 0 && serverHasMore && next < MAX_BROWSE_ENTRIES
                            && accumulated.size() < MAX_BROWSE_ENTRIES;
                    App.post(() -> callback.onPage(emit, firstPage, !more));
                }
                if (returned > 0 && serverHasMore && next < MAX_BROWSE_ENTRIES
                        && accumulated.size() < MAX_BROWSE_ENTRIES) {
                    browsePage(control, service, id, next, accumulated, count, callback);
                }
            }

            @Override
            public void updateStatus(Status status) {
            }

            @Override
            public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                // Some ContentDirectory servers fail the whole browse when RequestedCount is larger
                // than they expect. Retry the first page once with a conservative size instead of
                // reporting an empty library.
                if (count > BROWSE_COUNT_FALLBACK && start == 0) {
                    browsePage(control, service, id, start, accumulated, BROWSE_COUNT_FALLBACK, callback);
                    return;
                }
                if (callback != null) App.post(() -> callback.onError(safeError(defaultMsg)));
            }
        });
    }

    private List<DlnaEntry> toEntries(DIDLContent didl) {
        List<DlnaEntry> items = new ArrayList<>();
        if (didl == null) return items;
        for (Container container : didl.getContainers()) {
            items.add(new DlnaEntry(container.getId(), safeTitle(container.getTitle()), true, pickUrl(container.getResources()), mimeOf(container.getFirstResource())));
        }
        for (Item item : didl.getItems()) {
            items.add(new DlnaEntry(item.getId(), safeTitle(item.getTitle()), false, pickUrl(item.getResources()), mimeOf(item.getFirstResource())));
        }
        return items;
    }

    private static String pickUrl(List<Res> resources) {
        if (resources == null || resources.isEmpty()) return "";
        String fallback = "";
        for (Res res : resources) {
            String value = res == null ? null : res.getValue();
            if (TextUtils.isEmpty(value) || value.length() > MAX_URL_LENGTH || containsControl(value)) continue;
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

    public synchronized void release(Context context) {
        if (bindCount > 0) bindCount--;
        if (bindCount > 0) return;
        App.removeCallbacks(firstRescan);
        App.removeCallbacks(secondRescan);
        App.removeCallbacks(lateRescan);
        detach();
        unbind(context.getApplicationContext());
        appContext = null;
    }

    private void bind(Context context) {
        if (bound) return;
        bound = context.bindService(new Intent(context, DlnaBrowserService.class), this, Context.BIND_AUTO_CREATE);
    }

    private void unbind(Context context) {
        if (!bound) return;
        try {
            context.unbindService(this);
        } catch (IllegalArgumentException ignored) {
        }
        bound = false;
    }

    private synchronized void attach(AndroidUpnpService service) {
        int generation = ++attachGeneration;
        attach(service, generation, 0);
    }

    private void attach(AndroidUpnpService service, int generation, int attempt) {
        synchronized (this) {
            if (!bound || generation != attachGeneration) return;
            Registry registry;
            ControlPoint control;
            try {
                registry = service == null ? null : service.getRegistry();
                control = service == null ? null : service.getControlPoint();
            } catch (RuntimeException ignored) {
                registry = null;
                control = null;
            }
            if (registry == null || control == null) {
                if (attempt < MAX_ATTACH_RETRIES) App.post(() -> attach(service, generation, attempt + 1), ATTACH_RETRY_MS);
                return;
            }
            Registry oldRegistry = getRegistry();
            if (oldRegistry != null) oldRegistry.removeListener(this);
            upnpService = service;
            registry.addListener(this);
        }
        search();
        if (!deviceListeners.isEmpty()) {
            List<Device> devices = getRegistered();
            App.post(() -> {
                for (DeviceListener listener : deviceListeners) {
                    for (Device device : devices) listener.onDeviceAdded(device);
                }
            });
        }
    }

    private synchronized void detach() {
        attachGeneration++;
        Registry registry = getRegistry();
        if (registry != null) registry.removeListener(this);
        upnpService = null;
    }

    private static String safeTitle(String value) {
        if (TextUtils.isEmpty(value)) return "";
        String clean = value.replaceAll("[\\x00-\\x1F\\x7F]", " ").trim();
        return clean.length() <= MAX_TITLE_LENGTH ? clean : clean.substring(0, MAX_TITLE_LENGTH);
    }

    private static String safeError(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String clean = value.replaceAll("(?i)(https?://)[^\\s]+", "$1…");
        return clean.length() <= MAX_TITLE_LENGTH ? clean : clean.substring(0, MAX_TITLE_LENGTH);
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1f || c == 0x7f) return true;
        }
        return false;
    }

    public interface DeviceListener {

        void onDeviceAdded(Device device);

        void onDeviceRemoved(Device device);
    }

    public interface BrowseCallback {

        /** firstPage=true replace list; later pages append. done marks the last page. */
        void onPage(List<DlnaEntry> entries, boolean firstPage, boolean done);

        void onSuccess(List<DlnaEntry> entries);

        void onError(String msg);
    }

    private static class Loader {
        static final DlnaMediaManager INSTANCE = new DlnaMediaManager();
    }
}
