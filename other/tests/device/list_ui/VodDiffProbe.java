import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/** Shell-only test of the installed APK's kept Vod bean. Add base.apk to CLASSPATH. */
public final class VodDiffProbe {
    static final Class<?> VOD;
    static final Method CONTENT, SAME;
    static {
        try {
            VOD = Class.forName("com.fongmi.android.tv.bean.Vod");
            CONTENT = VOD.getMethod("cardContent"); SAME = VOD.getMethod("isSameContent", VOD);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    static Object vod() throws Exception {
        Object v = VOD.getConstructor().newInstance();
        set(v, "vodId", "refresh"); set(v, "vodName", "Refresh"); set(v, "vodPic", "http://localhost/poster");
        return v;
    }
    static void set(Object value, String name, Object data) throws Exception {
        Field field = value.getClass().getDeclaredField(name); field.setAccessible(true); field.set(value, data);
    }
    static void check(boolean pass, String message) { if (!pass) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Object a = vod(), b = vod();
        check((Boolean) SAME.invoke(a, b), "identical cards should not rebind");
        set(a, "action", "first"); set(b, "action", "second");
        check(!(Boolean) SAME.invoke(a, b), "action payload left stale click model");
        set(a, "action", ""); set(b, "action", ""); set(b, "vodTag", "folder");
        check(!(Boolean) SAME.invoke(a, b), "folder change ignored");
        set(b, "vodTag", ""); set(b, "vodYear", "2026");
        check(!(Boolean) SAME.invoke(a, b), "year change ignored");
        List<?> old = (List<?>) CONTENT.invoke(a); set(a, "vodName", "Changed");
        check(!old.equals(CONTENT.invoke(a)), "shared mutable bean corrupted old snapshot");
        Class<?> siteClass = Class.forName("com.fongmi.android.tv.bean.Site");
        Object site = siteClass.getConstructor().newInstance(); set(site, "key", "site"); set(site, "name", "First");
        set(a, "site", site); old = (List<?>) CONTENT.invoke(a); set(site, "name", "Second");
        check(!old.equals(CONTENT.invoke(a)), "shared mutable site corrupted old snapshot");
        System.out.println("PASS installed Vod: unchanged / action / folder / year / mutable Vod / mutable Site");
    }
}
