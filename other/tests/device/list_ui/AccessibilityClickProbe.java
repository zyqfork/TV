import android.app.UiAutomation;
import android.os.HandlerThread;
import android.os.Looper;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityNodeInfo;

/** Explicit accessibility action; not a physical mouse/touch feel test. */
public final class AccessibilityClickProbe {
    public static void main(String[] args) throws Exception {
        if(args.length<1 || args.length>2) throw new IllegalArgumentException("fully-qualified view id [exact text]");
        if(Looper.getMainLooper()==null) Looper.prepareMainLooper();
        new Thread(() -> { try { click(args[0],args.length==2?args[1]:null); System.exit(0); } catch(Throwable e) { e.printStackTrace(); System.exit(1); } }, "click-worker").start();
        Looper.loop();
    }
    private static void click(String id,String text) throws Exception {
        HandlerThread t=new HandlerThread("view-click");t.start();UiAutomation u=null;
        try {
            Object c=Class.forName("android.app.UiAutomationConnection").getConstructor().newInstance();
            u=(UiAutomation)UiAutomation.class.getConstructor(Looper.class,Class.forName("android.app.IUiAutomationConnection")).newInstance(t.getLooper(),c);
            UiAutomation.class.getMethod("connect",int.class).invoke(u,UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
            AccessibilityServiceInfo info=u.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;u.setServiceInfo(info);
            AccessibilityNodeInfo root=null;
            for(int i=0;i<20 && root==null;i++){android.os.SystemClock.sleep(100);root=u.getRootInActiveWindow();}
            if(root==null) throw new IllegalStateException("No active app window");
            boolean clicked=false;
            for(AccessibilityNodeInfo n:root.findAccessibilityNodeInfosByViewId(id)){
                if(n.isVisibleToUser() && (text==null || text.contentEquals(n.getText()==null?"":n.getText()))) {
                    AccessibilityNodeInfo target=AccessibilityNodeInfo.obtain(n);
                    while(target!=null && !target.isClickable()) {
                        AccessibilityNodeInfo parent=target.getParent();target.recycle();target=parent;
                    }
                    if(target!=null){clicked=target.performAction(AccessibilityNodeInfo.ACTION_CLICK);target.recycle();}
                }
                n.recycle();if(clicked)break;
            }
            root.recycle();if(!clicked)throw new IllegalStateException("No clickable view: "+id);
            System.out.println("ACTION_CLICK accepted: "+id);
        } finally{if(u!=null)UiAutomation.class.getMethod("disconnect").invoke(u);t.quitSafely();}
    }
}
