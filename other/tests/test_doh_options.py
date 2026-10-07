"""Compile production Doh; verify endpoint de-duplication without losing source overrides.
Android/Gson symbols are compilation doubles; merge uses actual production Doh objects.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
ROOT=Path(__file__).resolve().parents[2]
STUBS={
 'android/content/Context.java':'package android.content;public class Context {public Resources getResources(){return new Resources();}public static class Resources {public String[] getStringArray(int i){return new String[0];}}}',
 'android/text/TextUtils.java':'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
 'androidx/annotation/NonNull.java':'package androidx.annotation;public @interface NonNull {}',
 'androidx/annotation/Nullable.java':'package androidx.annotation;public @interface Nullable {}',
 'com/github/catvod/crawler/R.java':'package com.github.catvod.crawler;public class R {public static class array {public static int doh_url=1,doh_name=2;}}',
 'com/google/gson/Gson.java':'package com.google.gson;public class Gson {public <T>T fromJson(String s,Class<T> c){return null;}public <T>T fromJson(JsonElement e,java.lang.reflect.Type t){return null;}public String toJson(Object o){return "";}}',
 'com/google/gson/JsonElement.java':'package com.google.gson;public class JsonElement {}',
 'com/google/gson/annotations/SerializedName.java':'package com.google.gson.annotations;public @interface SerializedName {String value();}',
 'com/google/gson/reflect/TypeToken.java':'package com.google.gson.reflect;public class TypeToken {public static TypeToken getParameterized(java.lang.reflect.Type t,java.lang.reflect.Type... a){return new TypeToken();}public java.lang.reflect.Type getType(){return java.util.List.class;}}',
 'com/github/catvod/utils/Util.java':'package com.github.catvod.utils;public class Util {public static boolean containOrMatch(String s,String pattern){return s.equals(pattern);}}',
 'okhttp3/Dns.java':'package okhttp3;public interface Dns {java.util.List<java.net.InetAddress> lookup(String s)throws java.net.UnknownHostException;java.util.concurrent.atomic.AtomicInteger SYSTEM_CALLS=new java.util.concurrent.atomic.AtomicInteger();Dns SYSTEM=s->{SYSTEM_CALLS.incrementAndGet();return java.util.List.of(java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}));};}',
 'okhttp3/HttpUrl.java':'package okhttp3;public class HttpUrl {public static HttpUrl parse(String s){return s==null||s.isEmpty()?null:new HttpUrl();}}',
 'okhttp3/OkHttpClient.java':'package okhttp3;public class OkHttpClient {}',
 'okhttp3/dnsoverhttps/DnsOverHttps.java':'package okhttp3.dnsoverhttps;public class DnsOverHttps implements okhttp3.Dns {public static java.util.concurrent.atomic.AtomicInteger CALLS=new java.util.concurrent.atomic.AtomicInteger();public java.util.List<java.net.InetAddress> lookup(String s){CALLS.incrementAndGet();return java.util.List.of();}public static class Builder {public Builder client(okhttp3.OkHttpClient c){return this;}public Builder url(okhttp3.HttpUrl u){return this;}public Builder bootstrapDnsHosts(java.util.List<java.net.InetAddress> a){return this;}public DnsOverHttps build(){return new DnsOverHttps();}}}',
 'DohProbe.java':'''import com.github.catvod.bean.Doh;import java.util.*;
public class DohProbe {
 static Doh item(String name,String url){return new Doh().name(name).url(url);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  var system=item("system","");var tencent=item("tencent","https://doh.pub/dns-query");var ali=item("ali","https://dns.alidns.com/dns-query");var other=item("360","https://doh.360.cn/dns-query");
  var built=new ArrayList<>(List.of(system,tencent,ali,other));
  var override=item("source-ali",ali.getUrl());var ips=Doh.class.getDeclaredField("ips");ips.setAccessible(true);ips.set(override,List.of("192.0.2.1"));
  var first=item("custom-first","https://owned/dns-query");var second=item("custom-second",first.getUrl());var distinct=item("custom-first","https://owned/another-query");
  var configured=new ArrayList<>(Arrays.asList(override,first,second,override,null,distinct));
  var merged=Doh.merge(built,configured);
  check(merged.size()==6,"wrong unique endpoint count");
  check(merged.get(0)==system&&merged.get(1)==tencent&&merged.get(2)==other,"built-in order changed");
  check(merged.get(3)==override&&merged.get(3).getIps().equals(List.of("192.0.2.1")),"source override/bootstrap IP lost");
  check(merged.get(4)==first&&merged.get(5)==distinct,"first source entry/order or different endpoints lost");
  check(merged.indexOf(item("saved selection",first.getUrl()))==4,"saved URL selection no longer resolves");
  check(built.size()==4&&configured.size()==6,"input/source lists mutated");
  check(Doh.merge(built,null).equals(built)&&Doh.merge(built,List.of()).equals(built),"defaults changed");
  var sourceSystem=item("source system","");var missing=item("missing URL",null);var blank=item("blank URL","   ");
  var protectedSystem=Doh.merge(built,Arrays.asList(sourceSystem,sourceSystem,missing,blank,null));
  check(protectedSystem.size()==4&&protectedSystem.get(0)==system,"source hid/renamed/reordered system DNS");
  check(protectedSystem.get(0).getUrl().isEmpty(),"system DNS became a DoH URL");
  var dns=new com.github.catvod.net.OkDns();dns.setDoh(tencent);dns.lookup("owned.invalid");check(okhttp3.dnsoverhttps.DnsOverHttps.CALLS.get()==1,"DoH selection inactive");
  dns.setDoh(protectedSystem.get(0));dns.lookup("owned.invalid");check(okhttp3.Dns.SYSTEM_CALLS.get()==1&&okhttp3.dnsoverhttps.DnsOverHttps.CALLS.get()==1,"system selection did not clear DoH/delegate to system DNS");
  dns.setDoh(()->protectedSystem.get(0));dns.lookup("owned.invalid");check(okhttp3.Dns.SYSTEM_CALLS.get()==2,"startup supplier did not preserve system DNS");
  System.out.println("PASS production DoH deduplication, protected first system entry, SYSTEM delegation/reset/supplier, source override/IP preservation, saved selection, null/default handling");
 }
}'''
}
def main():
 code=(ROOT/'app/src/main/java/com/fongmi/android/tv/api/config/VodConfig.java').read_text(encoding='utf-8')
 assert 'return Doh.merge(Doh.get(App.get()), doh);' in code
 for directory,label in [("values","System DNS"),("values-zh-rCN","系统 DNS"),("values-zh-rTW","系統 DNS")]:
  assert f"<item>{label}</item>" in (ROOT/f"catvod/src/main/res/{directory}/strings.xml").read_text(encoding="utf-8")
 with TemporaryDirectory() as tmp:
  folder=Path(tmp);src=[ROOT/'catvod/src/main/java/com/github/catvod/bean/Doh.java',ROOT/'catvod/src/main/java/com/github/catvod/net/OkDns.java']
  for name,body in STUBS.items():
   p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');src.append(p)
  subprocess.run(['javac','-encoding','UTF-8','-d',str(folder/'classes'),*map(str,src)],check=True)
  subprocess.run(['java','-cp',str(folder/'classes'),'DohProbe'],check=True)
if __name__=='__main__':main()
