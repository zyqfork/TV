"""Compile production BaseConfig + Setting with queue/network doubles; check settings UI wiring.
Does not replace Android UI, plugin loading, or real source/network validation.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
 'android/text/TextUtils.java': 'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
 'com/fongmi/android/tv/App.java': 'package com.fongmi.android.tv;public class App {public static java.util.ArrayDeque<Runnable> q=new java.util.ArrayDeque<>();public static void post(Runnable r){q.add(r);}public static void drain(){while(!q.isEmpty())q.remove().run();}}',
 'com/fongmi/android/tv/R.java': 'package com.fongmi.android.tv;public class R {public static class string {public static final int setting_on=1,setting_off=2,error_config_get=3;}}',
 'com/fongmi/android/tv/bean/Config.java': '''package com.fongmi.android.tv.bean;public class Config {public final String url;public String json;public int updates,saves;public Config(String u,String j){url=u;json=j;}public String getUrl(){return url;}public String getJson(){return json;}public String getNotice(){return "";}public Config update(){updates++;return this;}public Config save(){saves++;return this;}}''',
 'com/fongmi/android/tv/impl/Callback.java': 'package com.fongmi.android.tv.impl;public class Callback {public void start(){}public void success(){}public void error(String s){}}',
 'com/fongmi/android/tv/event/ConfigEvent.java': 'package com.fongmi.android.tv.event;public class ConfigEvent {public static void common(){}}',
 'com/fongmi/android/tv/server/Server.java': 'package com.fongmi.android.tv.server;public class Server {public static Server get(){return new Server();}public void start(){}}',
 'com/fongmi/android/tv/utils/ResUtil.java': 'package com.fongmi.android.tv.utils;public class ResUtil {public static String getString(int i){return ""+i;}}',
 'com/fongmi/android/tv/utils/Notify.java': 'package com.fongmi.android.tv.utils;public class Notify {public static void show(String s){}public static String getError(int r,Throwable e){return e.getMessage();}}',
 'com/fongmi/android/tv/utils/UrlUtil.java': 'package com.fongmi.android.tv.utils;public class UrlUtil {public static String convert(String s){return s;}}',
 'com/fongmi/android/tv/utils/Task.java': '''package com.fongmi.android.tv.utils;public class Task {public static java.util.ArrayDeque<java.util.concurrent.FutureTask<?>> jobs=new java.util.ArrayDeque<>();public static java.util.concurrent.Future<?> submit(Runnable r){var f=new java.util.concurrent.FutureTask<Void>(r,null);jobs.add(f);return f;}public static void drain(){while(!jobs.isEmpty())jobs.remove().run();com.fongmi.android.tv.App.drain();}}''',
 'com/github/catvod/utils/Prefers.java': '''package com.github.catvod.utils;public class Prefers {public static java.util.Map<String,Object> values=new java.util.HashMap<>();public static boolean getBoolean(String k){return getBoolean(k,false);}public static boolean getBoolean(String k,boolean d){return (boolean)values.getOrDefault(k,d);}public static int getInt(String k){return getInt(k,0);}public static int getInt(String k,int d){return (int)values.getOrDefault(k,d);}public static String getString(String k){return (String)values.getOrDefault(k,"");}public static void put(String k,Object v){values.put(k,v);}}''',
 'com/github/catvod/bean/Header.java': 'package com.github.catvod.bean;public class Header {}',
 'com/github/catvod/bean/Proxy.java': 'package com.github.catvod.bean;public class Proxy {}',
 'com/github/catvod/net/OkHttp.java': '''package com.github.catvod.net;public class OkHttp {public static int requests;public static void cancel(String t){}public static String string(String u){requests++;return "list";}public static Sink responseInterceptor(){return new Sink();}public static Sink selector(){return new Sink();}public static Sink dns(){return new Sink();}public static class Sink {public void addAll(java.util.List<?> l){}}}''',
 'com/google/gson/JsonElement.java': '''package com.google.gson;public class JsonElement {public String value;public JsonElement(){this("");}public JsonElement(String v){value=v;}public boolean isJsonObject(){return this instanceof JsonObject;}public boolean isJsonArray(){return this instanceof JsonArray;}public boolean isJsonPrimitive(){return !isJsonArray()&&!isJsonObject();}public String getAsString(){return value;}public JsonArray getAsJsonArray(){return (JsonArray)this;}}''',
 'com/google/gson/JsonArray.java': '''package com.google.gson;public class JsonArray extends JsonElement implements Iterable<JsonElement> {public java.util.List<JsonElement> items=new java.util.ArrayList<>();public void add(JsonElement e){items.add(e);}public void addAll(JsonArray a){items.addAll(a.items);}public java.util.Iterator<JsonElement> iterator(){return items.iterator();}}''',
 'com/google/gson/JsonObject.java': '''package com.google.gson;public class JsonObject extends JsonElement {public java.util.Map<String,JsonElement> values=new java.util.HashMap<>();public boolean has(String k){return values.containsKey(k);}public JsonElement get(String k){return values.get(k);}public void add(String k,JsonElement e){values.put(k,e);}}''',
 'com/github/catvod/utils/Json.java': '''package com.github.catvod.utils;public class Json {public static com.google.gson.JsonElement parse(String s){var a=new com.google.gson.JsonArray();a.add(new com.google.gson.JsonObject());return a;}}''',
 'com/fongmi/android/tv/api/config/StartupSettingsProbe.java': '''package com.fongmi.android.tv.api.config;
import com.fongmi.android.tv.bean.Config;import com.fongmi.android.tv.setting.Setting;import com.fongmi.android.tv.impl.Callback;import com.fongmi.android.tv.utils.Task;import com.google.gson.*;
public class StartupSettingsProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static class Fixture extends BaseConfig {int network,saved;boolean fail,loaded;Throwable failure;String seen;Fixture(Config c){config=c;}protected String getTag(){return "fixture";}protected Config defaultConfig(){return config;}protected boolean isLoaded(){return loaded;}protected void load(Config c)throws Throwable{network++;seen=c.url;if(failure!=null)throw failure;if(fail)throw new java.io.IOException("offline");c.json="valid";loaded=true;}protected boolean loadSaved(Config c)throws Throwable{saved++;seen=c.url;if(c.json.equals("cancel"))throw new java.io.InterruptedIOException("Canceled");if(!c.json.equals("valid"))throw new IllegalArgumentException("corrupt");loaded=true;return true;}JsonArray expand(JsonObject o){return fetchArray(o,"headers");}}
 static class Reply extends Callback {int success,error;public void success(){success++;}public void error(String s){error++;}}
 public static void main(String[] args){
  check(Setting.isAutoSourceRefresh()&&!Setting.isIgnoreParserSslErrors()&&!Setting.isParserSslWarningAccepted(),"unsafe/changed defaults");
  var c=new Config("owned-A","valid");var f=new Fixture(c);var r=new Reply();Setting.putAutoSourceRefresh(false);f.loadOnStartup(r);Task.drain();check(f.saved==1&&f.network==0&&r.success==1&&c.updates==0&&c.saves==1,"disabled startup refetched or faked freshness");
  f.load(r);Task.drain();check(f.network==1&&c.updates==1,"manual refresh suppressed");Setting.putAutoSourceRefresh(true);f.loadOnStartup(r);Task.drain();check(f.network==2,"enabled startup did not refresh");
  Setting.putAutoSourceRefresh(false);f=new Fixture(new Config("owned-empty",null));r=new Reply();f.loadOnStartup(r);Task.drain();check(f.network==1&&r.success==1,"first use cannot fetch");
  f=new Fixture(new Config("owned-bad","corrupt"));r=new Reply();f.loadOnStartup(r);Task.drain();check(f.saved==1&&f.network==1&&r.success==1,"corrupt cache cannot recover once");
  f=new Fixture(new Config("owned-cancel","cancel"));r=new Reply();f.loadOnStartup(r);Task.drain();check(f.network==0&&r.success==0&&r.error==0,"canceled saved startup refetched");
  c=new Config("owned-offline","valid");f=new Fixture(c);f.fail=true;r=new Reply();f.load(r);Task.drain();check(r.error==1&&c.json.equals("valid"),"network error erased saved source");
  f=new Fixture(new Config("owned-timeout","valid"));f.failure=new java.net.SocketTimeoutException("Read timed out");r=new Reply();f.load(r);Task.drain();check(r.error==1&&r.success==0,"timeout swallowed and loading UI left pending");
  f=new Fixture(new Config("owned-cold","valid"));f.ensureLoaded();check(f.saved==1&&f.network==0,"cold ensureLoaded ignored preference");
  f=new Fixture(new Config("owned-A","valid"));var old=new Reply();var fresh=new Reply();f.loadOnStartup(old);f.config=new Config("owned-B","valid");f.loadOnStartup(fresh);Task.drain();check(old.success==0&&fresh.success==1&&f.seen.equals("owned-B"),"obsolete startup published into new source");
  var object=new JsonObject();object.add("headers",new JsonElement("https://owned/headers"));int before=com.github.catvod.net.OkHttp.requests;f.expand(object);f.expand(object);check(com.github.catvod.net.OkHttp.requests==before+1&&object.get("headers").isJsonArray(),"expanded lists refetched on saved startup");
  Setting.putIgnoreParserSslErrors(true);check(Setting.isIgnoreParserSslErrors(),"SSL opt-in not persisted");Setting.putIgnoreParserSslErrors(false);check(!Setting.isIgnoreParserSslErrors(),"SSL cannot disable");Setting.putParserSslWarningAccepted(true);check(Setting.isParserSslWarningAccepted(),"SSL acknowledgment not persisted");
  System.out.println("PASS production startup preference/cache fallback/manual refresh/cancellation/header expansion + settings defaults/persistence");
 }
}''',
}


def wiring():
    ns = '{http://schemas.android.com/apk/res/android}'
    layout = ET.parse(ROOT / 'app/src/main/res/layout/activity_setting_other.xml').getroot()
    ids = {e.get(ns+'id', '').replace('@+id/', '') for e in layout.iter()}
    assert {'autoRefresh','ssl','doh','ua','refreshSources','incognito','update','cache','backup','restore'} <= ids
    texts = {e.get(ns+'text', '') for e in layout.iter()}
    assert not {'@string/setting_other_network','@string/setting_auto_source_refresh_desc','@string/setting_parser_ssl_desc'} & texts
    for flavor, name in [('leanback','activity_setting.xml'),('mobile','fragment_setting.xml')]:
        root = ET.parse(ROOT / f'app/src/{flavor}/res/layout/{name}').getroot()
        parent_ids = {e.get(ns+'id', '').replace('@+id/', '') for e in root.iter()}
        assert {'other','player','cast','networkStorage','version','size'} <= parent_ids
        assert not {'doh','incognito','cache','backup','restore'} & parent_ids
        home = (ROOT / f'app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/HomeActivity.java').read_text(encoding='utf-8')
        assert 'VodConfig.get().init().loadOnStartup(getCallback())' in home
        assert 'LiveConfig.get().init().loadOnStartup()' in home
    page = (ROOT / 'app/src/main/java/com/fongmi/android/tv/ui/activity/SettingOtherActivity.java').read_text(encoding='utf-8')
    assert 'setNegativeButton(R.string.dialog_negative, null)' in page and 'Setting.putParserSslWarningAccepted(true)' in page
    assert 'VodConfig.get().load(' in page and 'LiveConfig.get().load(new Callback()' in page
    assert 'TextUtils.isEmpty(VodConfig.getUrl())' in page and 'refreshDone(vodError)' in page
    assert 'implements UaListener' in page and 'UaDialog.show(this)' in page and 'Setting.putUa(ua)' in page
    for flavor,kind in [('leanback','activity'),('mobile','fragment')]:
        player_layout = ET.parse(ROOT/f'app/src/{flavor}/res/layout/{kind}_setting_player.xml').getroot()
        assert '@+id/ua' not in {e.get(ns+'id','') for e in player_layout.iter()}
    mobile_ua = (ROOT/'app/src/mobile/java/com/fongmi/android/tv/ui/dialog/UaDialog.java').read_text(encoding='utf-8')
    assert 'show(FragmentActivity activity)' in mobile_ua and 'getParentFragment() instanceof UaListener' in mobile_ua
    for kind in ('Vod','Live'):
        code = (ROOT / f'app/src/main/java/com/fongmi/android/tv/api/config/{kind}Config.java').read_text(encoding='utf-8')
        assert 'protected boolean loadSaved(Config config)' in code and 'ConfigSnapshotStore.save(' in code and 'preferCachedJar' in code
    jar = (ROOT / 'app/src/main/java/com/fongmi/android/tv/api/loader/JarLoader.java').read_text(encoding='utf-8')
    assert 'preferCached && texts[0].startsWith("http")' in jar
    print('PASS TV/phone other-page routing, moved controls, startup/manual wiring and VOD/live/JAR snapshots')


def main():
    wiring()
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        sources = [ROOT / p for p in ('app/src/main/java/com/fongmi/android/tv/api/config/BaseConfig.java','app/src/main/java/com/fongmi/android/tv/api/config/ConfigLoadCancellation.java','app/src/main/java/com/fongmi/android/tv/setting/Setting.java')]
        for name, body in STUBS.items():
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(body, encoding='utf-8')
            sources.append(path)
        classes = folder / 'classes'
        subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
        subprocess.run(['java','-cp',str(classes),'com.fongmi.android.tv.api.config.StartupSettingsProbe'],check=True)


if __name__ == '__main__':
    main()
