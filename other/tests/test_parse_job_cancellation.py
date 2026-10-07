"""Deterministic cancellation probes against the complete production ParseJob.
WebView/HTTP/JSON doubles are not real network or Android WebView validation.
Requires a JDK. Run: python other/tests/test_parse_job_cancellation.py
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import argparse
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
 'android/text/TextUtils.java': 'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
 'com/fongmi/android/tv/App.java': '''package com.fongmi.android.tv;public class App {public static final java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();public static Object get(){return null;}public static void post(Runnable r){tasks.add(r);}public static void drain(){while(!tasks.isEmpty())tasks.remove().run();}}''',
 'com/fongmi/android/tv/Constant.java': 'package com.fongmi.android.tv;public class Constant {public static final int TIMEOUT_PARSE_DEF=100;}',
 'com/fongmi/android/tv/utils/Task.java': 'package com.fongmi.android.tv.utils;public class Task {public static void schedule(Runnable r,long t,java.util.concurrent.TimeUnit u){}}',
 'com/fongmi/android/tv/utils/WebViewUtil.java': 'package com.fongmi.android.tv.utils;public class WebViewUtil {public static boolean support(){return true;}}',
 'com/fongmi/android/tv/utils/UrlUtil.java': 'package com.fongmi.android.tv.utils;public class UrlUtil {public static String scheme(String s){if(s==null)return "";s=s.trim();if(s.startsWith("/"))return "file";int i=s.indexOf(58);return i>0&&s.substring(0,i).matches("[A-Za-z][A-Za-z0-9+.-]*")?s.substring(0,i).toLowerCase():"";}public static String convert(String s){return s;}public static String fixHeader(String s){return s;}}',
 'com/fongmi/android/tv/server/Server.java': 'package com.fongmi.android.tv.server;public class Server {public static Server get(){return new Server();}public String getAddress(String s){return "http://localhost"+s;}}',
 'com/fongmi/android/tv/bean/Parse.java': '''package com.fongmi.android.tv.bean;public class Parse {public static Parse get(int t,String s){return new Parse();}public int getType(){return 0;}public String getUrl(){return "";}public String getName(){return "";}public String getClick(){return "";}public java.util.Map<String,String> getHeader(){return java.util.Map.of();}public boolean isEmpty(){return false;}public void setClick(String s){}public void setHeader(java.util.Map<String,String> h){}public String extUrl(){return "";}public java.util.HashMap<String,String> mixMap(){return new java.util.HashMap<>();}}''',
 'com/fongmi/android/tv/bean/Result.java': '''package com.fongmi.android.tv.bean;public class Result {public static Result fromObject(Object o){return new Result();}public String getPlayUrl(){return "";}public String getKey(){return "";}public String getClick(){return "";}public String getFlag(){return "";}public String getJxFrom(){return "";}public java.util.Map<String,String> getHeader(){return java.util.Map.of();}public void setHeader(java.util.Map<String,String> h){}public boolean needParse(){return false;}public Url getUrl(){return new Url();}public static class Url {public String v(){return "";}public boolean isEmpty(){return true;}}}''',
 'com/fongmi/android/tv/api/config/VodConfig.java': '''package com.fongmi.android.tv.api.config;import com.fongmi.android.tv.bean.Parse;public class VodConfig {public static VodConfig get(){return new VodConfig();}public Parse getParse(){return new Parse();}public Parse getParse(String s){return new Parse();}public java.util.List<Parse> getParses(){return java.util.List.of();}public java.util.List<Parse> getParses(int t,String f){return java.util.List.of();}public Site getSite(String k){return new Site();}public static class Site {public String getClick(){return "";}}}''',
 'com/fongmi/android/tv/api/loader/BaseLoader.java': '''package com.fongmi.android.tv.api.loader;public class BaseLoader {public static BaseLoader get(){return new BaseLoader();}public Object jsonExt(String a,Object b,String c){return null;}public Object jsonExtMix(String a,String b,String c,Object d,String e){return null;}}''',
 'com/fongmi/android/tv/ui/custom/CustomWebView.java': '''package com.fongmi.android.tv.ui.custom;import com.fongmi.android.tv.impl.ParseCallback;import java.util.Map;public class CustomWebView {public static int created,destroyed;public static CustomWebView create(Object c){created++;return new CustomWebView();}public CustomWebView start(String k,String f,Map<String,String> h,String u,String c,ParseCallback cb,boolean d){return this;}public void stop(boolean notify){}public void destroy(){destroyed++;}}''',
 'com/google/common/net/HttpHeaders.java': '''package com.google.common.net;public class HttpHeaders {public static final String USER_AGENT="User-Agent",REFERER="Referer",COOKIE="Cookie";}''',
 'com/google/gson/JsonElement.java': '''package com.google.gson;public class JsonElement {public boolean isJsonNull(){return false;}public String getAsString(){return "";}public JsonObject getAsJsonObject(){return new JsonObject();}}''',
 'com/google/gson/JsonObject.java': '''package com.google.gson;public class JsonObject extends JsonElement {public JsonObject getAsJsonObject(String s){return new JsonObject();}public java.util.Set<java.util.Map.Entry<String,JsonElement>> entrySet(){return java.util.Set.of();}}''',
 'com/github/catvod/utils/Json.java': '''package com.github.catvod.utils;public class Json {public static com.google.gson.JsonElement parse(String s){return new com.google.gson.JsonElement();}public static String safeString(Object o,String s){return "";}}''',
 'com/github/catvod/utils/Util.java': 'package com.github.catvod.utils;public class Util {public static String substring(String s){return s;}}',
 'okhttp3/ResponseBody.java': 'package okhttp3;public class ResponseBody {public String string(){return "";}}',
 'okhttp3/Response.java': 'package okhttp3;public class Response implements AutoCloseable {public ResponseBody body(){return new ResponseBody();}public void close(){}}',
 'com/github/catvod/net/OkHttp.java': '''package com.github.catvod.net;public class OkHttp {public static Call newCall(String s,Object h){return new Call();}public static class Call {public okhttp3.Response execute(){return new okhttp3.Response();}}}''',
 'ParseCancellationProbe.java': '''import com.fongmi.android.tv.player.parse.ParseJob;import com.fongmi.android.tv.impl.ParseCallback;import com.fongmi.android.tv.App;import com.fongmi.android.tv.ui.custom.CustomWebView;import java.util.Map;
public class ParseCancellationProbe {
 static int replies, successes, errors;
 static ParseJob job(){return ParseJob.create(new ParseCallback(){public void onParseSuccess(Map<String,String> h,String u,String f){replies++;successes++;}public void onParseError(){replies++;errors++;}});}
 static void web(ParseJob j)throws Exception {var m=ParseJob.class.getDeclaredMethod("startWeb",String.class,String.class,Map.class,String.class,String.class);m.setAccessible(true);m.invoke(j,"","",Map.of(),"http://owned/parser","");}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] a)throws Exception {
  var stopped=job();try {web(stopped);stopped.stop();App.drain();check(CustomWebView.created==0,"WebView created after ParseJob.stop");}finally{stopped.stop();}
  var won=job();try {web(won);won.onParseSuccess(Map.of(),"http://owned/video.mp4","");App.drain();check(CustomWebView.created==0,"WebView created after another parser won");check(replies==1,"winner reply not delivered once");}finally{won.stop();}
  var active=job();try {web(active);App.drain();check(CustomWebView.created==1,"active parser cannot create WebView");active.stop();check(CustomWebView.destroyed==1,"active parser WebView leaked");}finally{active.stop();}
  var late=job();try{late.onParseSuccess(Map.of(),"http://owned/video.mp4","");late.stop();App.drain();check(replies==1,"stopped parser delivered late callback");}finally{late.stop();}
  var result=ParseJob.class.getDeclaredMethod("checkResult",Map.class,String.class,String.class,boolean.class);result.setAccessible(true);
  for(String url:new String[]{"https://a/v","http://127.0.0.1/v.mp4","rtmp://a/live","file:///v.mp4","content://a/v","proxy://do=play","https://owned/a-very-long-but-valid-source-path/video.mp4"}){
   var j=job();int before=successes;try{result.invoke(j,Map.of(),url,"owned",true);App.drain();check(successes==before+1,"valid parsed URL rejected: "+url);}finally{j.stop();}
  }
  for(String url:new String[]{"","parser failed despite a sufficiently lengthy textual error message",null}){
   var j=job();int before=errors;try{result.invoke(j,Map.of(),url,"",true);App.drain();check(errors==before+1,"invalid parser result accepted");}finally{j.stop();}
  }
  var optional=job();int before=errors, beforeSuccess=successes;try{result.invoke(optional,Map.of(),"","",false);App.drain();check(errors==before,"optional parser failure became fatal");result.invoke(optional,Map.of(),"https://a/v","",false);App.drain();check(successes==beforeSuccess+1,"later parser success lost");}finally{optional.stop();}
  System.out.println("PASS parser cancellation/cleanup and short media URL acceptance, invalid/optional-result handling");
 }
}''',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-root', type=Path, default=ROOT)
    args = parser.parse_args()
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        sources = [args.source_root / p for p in (
            'app/src/main/java/com/fongmi/android/tv/player/parse/ParseJob.java',
            'app/src/main/java/com/fongmi/android/tv/impl/ParseCallback.java',
        )]
        for name, body in STUBS.items():
            p = folder / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body, encoding='utf-8')
            sources.append(p)
        classes = folder / 'classes'
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
        subprocess.run(['java', '-cp', str(classes), 'ParseCancellationProbe'], check=True)


if __name__ == '__main__':
    main()
