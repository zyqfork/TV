"""Compile production CustomWebView + ParsePlaybackState with deterministic Android doubles.
Covers child WebView ownership, certificate cancellation and parsed header/intent contracts.
Not a real Android WebView, TLS handshake, or full Manager/MediaSession runtime test.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import argparse
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
 'android/annotation/SuppressLint.java': 'package android.annotation;public @interface SuppressLint {String[] value();}',
 'androidx/annotation/NonNull.java': 'package androidx.annotation;public @interface NonNull {}',
 'android/content/Context.java': 'package android.content;public class Context {}',
 'android/content/DialogInterface.java': 'package android.content;public interface DialogInterface {interface OnDismissListener {void onDismiss(DialogInterface d);}}',
 'android/text/TextUtils.java': 'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
 'android/net/Uri.java': 'package android.net;public class Uri {public static Uri parse(String s){return new Uri();}public String getHost(){return "owned";}}',
 'android/net/http/SslError.java': 'package android.net.http;public class SslError {}',
 'android/view/ViewGroup.java': 'package android.view;public class ViewGroup {public void removeView(Object o){}}',
 'android/webkit/SslErrorHandler.java': 'package android.webkit;public class SslErrorHandler {public int canceled,proceeded;public void cancel(){canceled++;}public void proceed(){proceeded++;}}',
 'android/webkit/WebResourceRequest.java': 'package android.webkit;public interface WebResourceRequest {android.net.Uri getUrl();java.util.Map<String,String> getRequestHeaders();}',
 'android/webkit/WebResourceResponse.java': 'package android.webkit;public class WebResourceResponse {public WebResourceResponse(String m,String e,java.io.InputStream b){}}',
 'android/webkit/WebViewClient.java': '''package android.webkit;public class WebViewClient {public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){return null;}public void onPageFinished(WebView v,String u){}public void onReceivedSslError(WebView v,SslErrorHandler h,android.net.http.SslError e){}public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){return false;}}''',
 'android/webkit/WebSettings.java': '''package android.webkit;public class WebSettings {public static final int MIXED_CONTENT_ALWAYS_ALLOW=0;public void setSupportZoom(boolean b){}public void setUseWideViewPort(boolean b){}public void setDatabaseEnabled(boolean b){}public void setDomStorageEnabled(boolean b){}public void setJavaScriptEnabled(boolean b){}public void setBuiltInZoomControls(boolean b){}public void setDisplayZoomControls(boolean b){}public void setLoadWithOverviewMode(boolean b){}public void setUserAgentString(String s){}public void setMediaPlaybackRequiresUserGesture(boolean b){}public void setJavaScriptCanOpenWindowsAutomatically(boolean b){}public void setMixedContentMode(int m){}}''',
 'android/webkit/CookieManager.java': '''package android.webkit;public class CookieManager {public static CookieManager getInstance(){return new CookieManager();}public void setAcceptThirdPartyCookies(WebView v,boolean b){}public void setCookie(String u,String c){}}''',
 'android/webkit/WebView.java': '''package android.webkit;import com.fongmi.android.tv.App;public class WebView {public static int created,destroyed;public boolean dead;public WebViewClient client;public WebView(android.content.Context c){created++;}public WebSettings getSettings(){return new WebSettings();}public void setWebViewClient(WebViewClient c){client=c;}public void loadUrl(String u){if(dead)throw new AssertionError("load after destroy");}public void loadUrl(String u,java.util.Map<String,String> h){loadUrl(u);}public void stopLoading(){}public void destroy(){if(dead)throw new AssertionError("double destroy");dead=true;destroyed++;}public boolean post(Runnable r){App.post(r);return true;}public Object getParent(){return null;}public void evaluateJavascript(String s,java.util.function.Consumer<String> c){c.accept("");}}''',
 'com/fongmi/android/tv/App.java': '''package com.fongmi.android.tv;public class App {public static final java.util.ArrayDeque<Runnable> tasks=new java.util.ArrayDeque<>();public static android.content.Context get(){return new android.content.Context();}public static Object activity(){return null;}public static void post(Runnable r){tasks.add(r);}public static void post(Runnable r,long t){}public static void removeCallbacks(Runnable r){}public static void drain(){while(!tasks.isEmpty())tasks.remove().run();}}''',
 'com/fongmi/android/tv/Constant.java': 'package com.fongmi.android.tv;public class Constant {public static final int TIMEOUT_PARSE_WEB=100;}',
 'com/fongmi/android/tv/api/config/RuleConfig.java': 'package com.fongmi.android.tv.api.config;public class RuleConfig {public static RuleConfig get(){return new RuleConfig();}public java.util.List<String> getAds(){return java.util.List.of();}}',
 'com/fongmi/android/tv/api/config/VodConfig.java': '''package com.fongmi.android.tv.api.config;public class VodConfig {public static VodConfig get(){return new VodConfig();}public Site getSite(String k){return new Site();}public static class Site {public com.github.catvod.crawler.Spider spider(){return new com.github.catvod.crawler.Spider();}}}''',
 'com/fongmi/android/tv/setting/Setting.java': 'package com.fongmi.android.tv.setting;public class Setting {public static boolean ignore;public static boolean isIgnoreParserSslErrors(){return ignore;}public static String getUa(){return "owned";}}',
 'com/fongmi/android/tv/ui/dialog/WebDialog.java': '''package com.fongmi.android.tv.ui.dialog;public class WebDialog {public static WebDialog create(Object v){return new WebDialog();}public WebDialog show(){return this;}public void dismiss(){}}''',
 'com/fongmi/android/tv/utils/Sniffer.java': '''package com.fongmi.android.tv.utils;public class Sniffer {public static java.util.List<String> getScript(android.net.Uri u){return java.util.List.of();}public static boolean isVideoFormat(String u){return u.endsWith(".mp4");}}''',
 'com/github/catvod/crawler/Spider.java': 'package com.github.catvod.crawler;public class Spider {public boolean manualVideoCheck(){return false;}public boolean isVideoFormat(String s){return false;}}',
 'com/github/catvod/crawler/SpiderDebug.java': 'package com.github.catvod.crawler;public class SpiderDebug {public static void log(String t,String f,Object...v){}}',
 'com/github/catvod/utils/Util.java': 'package com.github.catvod.utils;public class Util {public static boolean containOrMatch(String s,String p){return false;}}',
 'com/google/common/net/HttpHeaders.java': 'package com.google.common.net;public class HttpHeaders {public static final String USER_AGENT="User-Agent",COOKIE="Cookie";}',
 'ParserResourcesProbe.java': '''import com.fongmi.android.tv.ui.custom.CustomWebView;import com.fongmi.android.tv.player.parse.ParsePlaybackState;import com.fongmi.android.tv.impl.ParseCallback;import com.fongmi.android.tv.App;import android.webkit.*;import java.util.*;
public class ParserResourcesProbe {
 static int successes,errors;
 static ParseCallback cb=new ParseCallback(){public void onParseSuccess(Map<String,String> h,String u,String f){successes++;}public void onParseError(){errors++;}};
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static CustomWebView view(){return CustomWebView.create(App.get()).start("","",Map.of(),"http://owned/parser","",cb,true);}
 static void child(CustomWebView v)throws Exception {var m=CustomWebView.class.getDeclaredMethod("onParseAdd",Map.class,String.class);m.setAccessible(true);m.invoke(v,Map.of(),"http://owned/player/?url=http://owned/media");}
 static void success(CustomWebView v)throws Exception {var m=CustomWebView.class.getDeclaredMethod("onParseSuccess",Map.class,String.class);m.setAccessible(true);m.invoke(v,Map.of(),"http://owned/v.mp4");}
 public static void main(String[] args)throws Exception {
  if(args[0].equals("ownership")){
   var v=view();child(v);App.drain();check(WebView.created==2,"active child missing");v.stop(false);v.destroy();check(WebView.created==WebView.destroyed,"active child leaked after parent cleanup");
   System.out.println("PASS production WebView owns and destroys active child parsers");
  }else if(args[0].equals("web")){
   var v=view();child(v);v.stop(false);v.destroy();App.drain();check(WebView.created==1,"child created after parent stop");
   v=view();child(v);App.drain();check(WebView.created==3,"active child missing");v.stop(false);v.destroy();check(WebView.destroyed==3,"child leaked after parent stop");v.stop(false);check(WebView.destroyed==3,"child destroyed twice");
   v=view();child(v);success(v);App.drain();check(WebView.created==4,"child created after winner");check(successes==1&&errors==0,"winner/cleanup changed callback delivery");v.destroy();
   v=view();child(v);App.drain();v.stop(true);v.destroy();check(errors==1&&WebView.created==WebView.destroyed,"error cleanup leaked child or duplicate callback");
   System.out.println("PASS complete production WebView: queued-child rejection, child stop/destroy ownership, success/error cleanup");
  }else if(args[0].equals("tls")){
   var v=view();var h=new SslErrorHandler();v.client.onReceivedSslError(v,h,new android.net.http.SslError());check(h.canceled==1&&h.proceeded==0,"invalid certificate bypassed");
   com.fongmi.android.tv.setting.Setting.ignore=true;h=new SslErrorHandler();v.client.onReceivedSslError(v,h,new android.net.http.SslError());check(h.proceeded==1&&h.canceled==0,"explicit SSL compatibility option not applied");
   com.fongmi.android.tv.setting.Setting.ignore=false;h=new SslErrorHandler();v.client.onReceivedSslError(v,h,new android.net.http.SslError());check(h.canceled==1&&h.proceeded==0,"SSL option did not turn off");
   success(v);App.drain();check(successes==1,"TLS subresource rejection killed other successful parsing");v.destroy();
   System.out.println("PASS production SSL callback defaults to cancel; explicit opt-in/off works; parser still usable");
  }else{
   var state=new ParsePlaybackState();state.begin();check(state.isPending()&&state.complete(),"fresh parse lost autoplay");
   state.begin();state.setPlayWhenReady(false);check(!state.complete()&&!state.isPending(),"parse discarded pause");
   state.begin();state.setPlayWhenReady(false);state.setPlayWhenReady(true);check(state.complete(),"parse discarded resume");
   state.begin();state.cancel();state.setPlayWhenReady(true);check(!state.isPending()&&!state.complete(),"canceled parse intent revived");state.begin();check(state.complete(),"new parse inherited previous pause");
   var source=new HashMap<String,String>(Map.of("Range","bytes=5-", "rAnGe","bytes=9-","Authorization","token","Cookie","owned=1","Referer","https://owned"));var original=new HashMap<>(source);
   var copy=ParsePlaybackState.playbackHeaders(Collections.unmodifiableMap(source));check(source.equals(original),"shared resolver headers mutated");check(copy.keySet().stream().noneMatch(k->"range".equalsIgnoreCase(k)),"case-insensitive range leaked");check(copy.get("Authorization").equals("token")&&copy.get("Cookie").equals("owned=1")&&copy.get("Referer").equals("https://owned"),"required headers lost");copy.put("x","y");check(!source.containsKey("x"),"headers alias source");
   check(ParsePlaybackState.playbackHeaders(Map.of()).isEmpty()&&ParsePlaybackState.playbackHeaders(null).isEmpty(),"empty/null headers failed");
   System.out.println("PASS production pending parse intent + immutable/shared header copy, range removal and auth preservation");
  }
 }
}''',
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source-root', type=Path, default=ROOT)
    p.add_argument('--only', choices=('web', 'ownership', 'tls', 'state'))
    args = p.parse_args()
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        sources = [args.source_root / name for name in (
            'app/src/main/java/com/fongmi/android/tv/ui/custom/CustomWebView.java',
            'app/src/main/java/com/fongmi/android/tv/impl/ParseCallback.java',
        )]
        # Older snapshots have no state helper: use current helper for independent WebView negative controls.
        helper = args.source_root / 'app/src/main/java/com/fongmi/android/tv/player/parse/ParsePlaybackState.java'
        if not helper.exists():
            helper = ROOT / 'app/src/main/java/com/fongmi/android/tv/player/parse/ParsePlaybackState.java'
        sources.append(helper)
        for name, text in STUBS.items():
            path = folder / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding='utf-8')
            sources.append(path)
        classes = folder / 'classes'
        subprocess.run(['javac', '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], check=True)
        for case in (args.only,) if args.only else ('web', 'ownership', 'tls', 'state'):
            subprocess.run(['java', '-cp', str(classes), 'ParserResourcesProbe', case], check=True)


if __name__ == '__main__':
    main()
