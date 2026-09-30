import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Xml;
import android.view.accessibility.AccessibilityNodeInfo;

import org.xmlpull.v1.XmlSerializer;

import java.io.FileOutputStream;

/** Shell-only hierarchy snapshot without uiautomator's idle wait (the home clock ticks each second). */
public final class HomeHierarchyProbe {
    private static String text(CharSequence value) { return value == null ? "" : value.toString(); }

    private static void node(XmlSerializer xml, AccessibilityNodeInfo info, int index) throws Exception {
        if (info == null) return;
        try {
            if (!info.isVisibleToUser()) return;
            Rect bounds = new Rect();
            info.getBoundsInScreen(bounds);
            xml.startTag(null, "node");
            xml.attribute(null, "index", Integer.toString(index));
            xml.attribute(null, "text", text(info.getText()));
            xml.attribute(null, "resource-id", text(info.getViewIdResourceName()));
            xml.attribute(null, "class", text(info.getClassName()));
            xml.attribute(null, "package", text(info.getPackageName()));
            xml.attribute(null, "content-desc", text(info.getContentDescription()));
            xml.attribute(null, "focused", Boolean.toString(info.isFocused()));
            xml.attribute(null, "focusable", Boolean.toString(info.isFocusable()));
            xml.attribute(null, "clickable", Boolean.toString(info.isClickable()));
            xml.attribute(null, "long-clickable", Boolean.toString(info.isLongClickable()));
            xml.attribute(null, "selected", Boolean.toString(info.isSelected()));
            xml.attribute(null, "scrollable", Boolean.toString(info.isScrollable()));
            AccessibilityNodeInfo.CollectionInfo collection = info.getCollectionInfo();
            if (collection != null) {
                xml.attribute(null, "row-count", Integer.toString(collection.getRowCount()));
                xml.attribute(null, "column-count", Integer.toString(collection.getColumnCount()));
            }
            xml.attribute(null, "bounds", "[" + bounds.left + "," + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]");
            for (int i = 0; i < info.getChildCount(); i++) node(xml, info.getChild(i), i);
            xml.endTag(null, "node");
        } finally {
            info.recycle();
        }
    }

    public static void main(String[] args) throws Exception {
        // Android 16's AccessibilityInteractionClient requires a main looper even for shell tools.
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper();
        HandlerThread thread = new HandlerThread("home-hierarchy");
        thread.start();
        UiAutomation automation = null;
        try {
            Object connection = Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            automation = (UiAutomation) UiAutomation.class.getConstructor(Looper.class,
                    Class.forName("android.app.IUiAutomationConnection")).newInstance(thread.getLooper(), connection);
            UiAutomation.class.getMethod("connect", int.class).invoke(automation,
                    UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            automation.setServiceInfo(withViewIds(automation));
            AccessibilityNodeInfo root = null;
            // Service registration / window cache population is asynchronous, particularly
            // after setServiceInfo(). This is a bounded readiness wait, not an idle wait.
            for (int attempt = 0; root == null && attempt < 20; attempt++) {
                android.os.SystemClock.sleep(100);
                root = automation.getRootInActiveWindow();
                if (root == null) {
                    for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                        if (window.isActive() || window.isFocused()) root = window.getRoot();
                        window.recycle();
                        if (root != null) break;
                    }
                }
            }
            if (root == null) throw new IllegalStateException("No active window after readiness wait");
            try (FileOutputStream output = new FileOutputStream(args[0])) {
                XmlSerializer xml = Xml.newSerializer();
                xml.setOutput(output, "UTF-8");
                xml.startDocument("UTF-8", true);
                xml.startTag(null, "hierarchy");
                node(xml, root, 0);
                xml.endTag(null, "hierarchy");
                xml.endDocument();
            }
            System.out.println("Snapshot written: " + args[0]);
        } finally {
            if (automation != null) UiAutomation.class.getMethod("disconnect").invoke(automation);
            thread.quitSafely();
        }
    }

    private static android.accessibilityservice.AccessibilityServiceInfo withViewIds(UiAutomation automation) {
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        return info;
    }
}
