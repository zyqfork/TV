"""Production FTP + model/resolver/dispatch/Media3 source against owned loopback FTP servers.
Requires commons-net 3.12 jar and pyftpdlib. Android, SMB and WebDAV are doubles here.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import argparse, subprocess, sys, threading
ROOT=Path(__file__).resolve().parents[2]
STUBS={
'android/text/TextUtils.java':'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
'android/net/Uri.java':'''package android.net;public class Uri {final java.net.URI u;Uri(String s){u=java.net.URI.create(s);}public static Uri parse(String s){return new Uri(s);}public String getScheme(){return u.getScheme();}public String getHost(){return u.getHost();}public String getUserInfo(){return u.getUserInfo();}public String getQuery(){return u.getQuery();}public String getFragment(){return u.getFragment();}public String getPath(){return u.getPath();}public String toString(){return u.toString();}public static String encode(String s){return java.net.URLEncoder.encode(s,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}public static class Builder {String scheme,authority,path="";public Builder scheme(String s){scheme=s;return this;}public Builder encodedAuthority(String a){authority=a;return this;}public Builder path(String p){path=p;return this;}public Builder appendPath(String p){path+="/"+p;return this;}public Uri build(){try{return new Uri(new java.net.URI(scheme,authority,path,null,null).toASCIIString());}catch(Exception e){throw new IllegalArgumentException(e);}}}}''',
'android/util/Base64.java':'package android.util;public class Base64 {public static int NO_WRAP=2;public static String encodeToString(byte[] a,int flags){return java.util.Base64.getEncoder().encodeToString(a);}}',
'androidx/annotation/Nullable.java':'package androidx.annotation;public @interface Nullable {}',
'com/google/gson/annotations/SerializedName.java':'package com.google.gson.annotations;public @interface SerializedName {String value();}',
'com/fongmi/android/tv/R.java':'package com.fongmi.android.tv;public class R {public static class string {public static int network_storage_ftp_auth_disabled=1,network_storage_ftp_auth_failed=2,network_storage_ftp_path_missing=3,network_storage_ftp_failed=4;}}',
'com/fongmi/android/tv/utils/ResUtil.java':'package com.fongmi.android.tv.utils;public class ResUtil {public static String getString(int i){return "resource_"+i;}}',
'com/fongmi/android/tv/storage/NetworkStorageStore.java':'package com.fongmi.android.tv.storage;public class NetworkStorageStore {public static java.util.Map<String,NetworkStorage> entries=new java.util.HashMap<>();public static NetworkStorage find(String s){return entries.get(s);}public static void save(NetworkStorage s){entries.put(s.getId(),s);}}',
'com/fongmi/android/tv/storage/WebDavClientHelper.java':'package com.fongmi.android.tv.storage;public class WebDavClientHelper {public WebDavClientHelper(NetworkStorage s){}public java.util.List<NetworkEntry> list(String p){return java.util.List.of();}}',
'com/fongmi/android/tv/storage/SmbClientHelper.java':'''package com.fongmi.android.tv.storage;public class SmbClientHelper implements java.io.Closeable {public static int closed;final NetworkStorage s;public SmbClientHelper(NetworkStorage s){this.s=s;}public java.util.List<NetworkEntry> list(String p)throws java.io.IOException {if(s.getShare().equals("bad"))throw new java.io.IOException("missing-share");if(s.getShare().equals("auth"))throw new java.io.IOException("auth");return java.util.List.of(new NetworkEntry("share","share",true,0));}public static boolean isMissingShare(Throwable e){return "missing-share".equals(e.getMessage());}public com.hierynomus.smbj.share.File openRead(String p){return new com.hierynomus.smbj.share.File();}public void close(){closed++;}}''',
'com/hierynomus/smbj/share/File.java':'''package com.hierynomus.smbj.share;public class File {public static int closed;public Info getFileInformation(){return new Info();}public int read(byte[] b,long pos,int o,int n){if(pos>=4)return -1;int count=(int)Math.min(n,4-pos);for(int i=0;i<count;i++)b[o+i]=(byte)(pos+i);return count;}public void close(){closed++;}public static class Info {public Info getStandardInformation(){return this;}public long getEndOfFile(){return 4;}}}''',
'androidx/media3/common/C.java':'package androidx.media3.common;public class C {public static final int LENGTH_UNSET=-1,RESULT_END_OF_INPUT=-1;}',
'androidx/media3/datasource/DataSpec.java':'package androidx.media3.datasource;public class DataSpec {public android.net.Uri uri;public long position,length;public DataSpec(String u,long p,long l){uri=android.net.Uri.parse(u);position=p;length=l;}}',
'com/fongmi/android/tv/utils/UrlUtil.java':'package com.fongmi.android.tv.utils;public class UrlUtil {public static String scheme(android.net.Uri u){return u.getScheme();}public static String path(String s){return s.substring(s.lastIndexOf("/")+1);}}',
'com/github/catvod/utils/Path.java':'package com.github.catvod.utils;public class Path {public static java.io.File jpa(){return new java.io.File(".");}public static long size(java.io.File f){return 0;}public static long available(java.io.File f){return 0;}public static void clear(java.io.File f){}}',
'com/p2p/P2PClass.java':'package com.p2p;public class P2PClass {public static int starts;public int port=8089;public void P2Pdoxstart(byte[] b){starts++;}public void P2Pdoxpause(byte[] b){}}',
'com/fongmi/android/tv/utils/Task.java':'package com.fongmi.android.tv.utils;public class Task {public static void execute(Runnable r){r.run();}}',
'com/fongmi/android/tv/bean/Result.java':'package com.fongmi.android.tv.bean;public class Result {final String s;public Result(String s){this.s=s;}public Url getUrl(){return new Url();}public void setParse(int p){}public class Url {public android.net.Uri uri(){return android.net.Uri.parse(s);}public String v(){return s;}}}',
'com/fongmi/android/tv/bean/Episode.java':'package com.fongmi.android.tv.bean;public class Episode {public String getUrl(){return "";}}',
'com/fongmi/android/tv/bean/Flag.java':'package com.fongmi.android.tv.bean;public class Flag {public java.util.List<Episode> getEpisodes(){return new java.util.ArrayList<>();}}',
'com/fongmi/android/tv/bean/Vod.java':'package com.fongmi.android.tv.bean;public class Vod {public java.util.List<Flag> getFlags(){return java.util.List.of();}}',
'androidx/media3/datasource/TransferListener.java':'package androidx.media3.datasource;public interface TransferListener {}',
'androidx/media3/datasource/DataSource.java':'package androidx.media3.datasource;public interface DataSource {default void addTransferListener(TransferListener l){}default java.util.Map<String,java.util.List<String>> getResponseHeaders(){return java.util.Map.of();}interface Factory {DataSource createDataSource();}long open(DataSpec s)throws java.io.IOException;int read(byte[] b,int o,int l)throws java.io.IOException;void close()throws java.io.IOException;android.net.Uri getUri();}',
'androidx/media3/datasource/BaseDataSource.java':'package androidx.media3.datasource;public abstract class BaseDataSource implements DataSource {public int starts,ends,bytes;public BaseDataSource(boolean n){}protected void transferInitializing(DataSpec s){}protected void transferStarted(DataSpec s){starts++;}protected void transferEnded(){ends++;}protected void bytesTransferred(int n){bytes+=n;}}',
'com/fongmi/android/tv/player/exo/RouterProbe.java':'''package com.fongmi.android.tv.player.exo;import androidx.media3.datasource.*;public class RouterProbe {public static void run(String url)throws Exception {class Http implements DataSource {int opens,closes,listeners;android.net.Uri uri;public long open(DataSpec s){uri=s.uri;opens++;return 1;}public int read(byte[] b,int o,int n){return -1;}public void close(){closes++;uri=null;}public android.net.Uri getUri(){return uri;}public void addTransferListener(TransferListener l){listeners++;}}var http=new Http();var r=new NetworkRoutingDataSource(http);r.addTransferListener(new TransferListener(){});if(r.open(new DataSpec(url,5,4))!=4||http.opens!=0)throw new AssertionError("FTP routing");byte[] b=new byte[4];if(r.read(b,0,4)!=4||b[0]!=5)throw new AssertionError("FTP routed bytes");r.open(new DataSpec("https://owned.invalid/segment.ts",0,-1));if(http.opens!=1||http.listeners!=1||!r.getUri().getScheme().equals("https"))throw new AssertionError("HTTP segment routing");r.close();r.close();if(http.closes!=1)throw new AssertionError("router close");}}''',
'FtpProbe.java':'''import com.fongmi.android.tv.storage.*;import com.fongmi.android.tv.player.exo.NetworkFileDataSource;import androidx.media3.datasource.DataSpec;import java.util.*;
public class FtpProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static NetworkStorage config(int port){var s=NetworkStorage.create(NetworkStorage.TYPE_FTP);s.setHost("127.0.0.1");s.setPort(port);s.setPath("/root");s.setUsername("owned");s.setPassword("fixture");s.setAllowInsecureAuth(true);return s;}
 static void expectFail(NetworkStorage s,String path,long pos)throws Exception {boolean failed=false;try(var f=new FtpClientHelper(s)){f.open(path,pos);}catch(java.io.IOException e){failed=true;}check(failed,"failure accepted: "+path);}
 public static void main(String[] args)throws Exception {
  int port=Integer.parseInt(args[0]);var s=config(port);check(s.isValid()&&s.effectivePort()==port,"model/port invalid");check(s.displaySubtitle().startsWith("ftp://")&&!s.displaySubtitle().contains("fixture"),"credentials in display URL");
  if(args.length>1&&args[1].equals("no-rest")){expectFail(s,"中文 空格#.bin",11);System.out.println("PASS FTP rejects unsupported REST rather than silently reading from zero");return;}
  if(args.length>1&&(args[1].equals("unknown")||args[1].equals("no-size-fact"))){
   try(var f=new FtpClientHelper(s)){check(f.open("中文 空格#.bin",11)==-1,"unknown size falsely known");byte[] bytes=new byte[97];long offset=11;int n;while((n=f.read(bytes,0,bytes.length))!=-1){for(int i=0;i<n;i++)check((bytes[i]&255)==((offset+i)%251),"unknown-size restart corrupt");offset+=n;}check(offset==4096,"unknown-size EOF wrong");}
   System.out.println("PASS FTP unknown SIZE/MLST streaming and REST");return;
  }
  if(args.length>1&&args[1].equals("truncated")){
   NetworkStorageStore.save(s);var source=new NetworkFileDataSource();source.open(new DataSpec(s.toPlayUrl("中文 空格#.bin"),0,-1));boolean failed=false;try{byte[] bytes=new byte[512];while(source.read(bytes,0,512)!=-1){}}catch(java.io.EOFException e){failed=true;}finally{source.close();}check(failed,"premature EOF silently accepted");System.out.println("PASS Media3 rejects truncated FTP content");return;
  }
  var list=NetworkStorageAccess.list(s,"").entries();check(list.stream().anyMatch(e->e.getName().equals("中文 空格#.bin")),"UTF8/special-name listing lost");check(list.get(0).isDirectory(),"directory sorting");check(NetworkStorageAccess.list(s,"empty").entries().isEmpty(),"empty folder rejected");
  NetworkStorageStore.save(s);String url=s.toPlayUrl("中文 空格#.bin");check(NetworkPlayResolver.isNetworkPlayUrl(url)&&NetworkPlayResolver.relativePath(url).equals("中文 空格#.bin"),"encoded playback URL roundtrip");check(NetworkPlayResolver.protocolLabel(url).equals("FTP"),"FTP protocol label");var extraction=new com.fongmi.android.tv.player.extractor.Source();check(extraction.fetch(new com.fongmi.android.tv.bean.Result(url)).equals(url)&&com.p2p.P2PClass.starts==0,"stored FTP intercepted by JianPian");check(extraction.fetch(new com.fongmi.android.tv.bean.Result("ftp://public.example/legacy.mp4")).startsWith("http://127.0.0.1:8089/")&&com.p2p.P2PClass.starts==1,"legacy FTP extraction regressed");com.fongmi.android.tv.player.exo.RouterProbe.run(url);check(!NetworkPlayResolver.isNetworkPlayUrl("ftp://public.example/file.mp4")&&!NetworkPlayResolver.isNetworkPlayUrl("ftp://user:password@public.example/file.mp4"),"public FTP links intercepted as stored IDs");
  for(long position:new long[]{0,1,1024,4095,4096}){
   try(var f=new FtpClientHelper(s)){check(f.open("中文 空格#.bin",position)==4096,"size metadata");byte[] b=new byte[257];long offset=position;int n;while((n=f.read(b,0,b.length))!=-1){for(int i=0;i<n;i++)check((b[i]&255)==((offset+i)%251),"REST/binary byte mismatch");offset+=n;}check(offset==4096,"truncated read");}
  }
  var ds=new NetworkFileDataSource();check(ds.open(new DataSpec(url,101,17))==17,"bounded DataSpec");byte[] b=new byte[40];check(ds.read(b,0,40)==17&&ds.read(b,0,40)==-1&&ds.bytes==17,"bounded read/accounting");ds.close();ds.close();check(ds.starts==1&&ds.ends==1,"transfer close imbalance");check(ds.open(new DataSpec(url,4096,-1))==0&&ds.read(b,0,1)==-1,"EOF open");ds.close();
  try(var large=new FtpClientHelper(s)){check(large.open("large.bin",2147483657L)==2147483661L,"large file/64-bit REST metadata");byte[] end=new byte[4];check(large.read(end,0,4)==4&&new String(end,java.nio.charset.StandardCharsets.US_ASCII).equals("TAIL"),"large-file tail seek");}
  expectFail(s,"missing.bin",0);expectFail(s,"中文 空格#.bin",4097);var bad=config(port);bad.setPassword("wrong");expectFail(bad,"中文 空格#.bin",0);bad=config(port);bad.setAllowInsecureAuth(false);expectFail(bad,"中文 空格#.bin",0);
  boolean rejected=false;try{NetworkStorageAccess.list(s,"../outside");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"path traversal accepted");rejected=false;try{NetworkStorageAccess.list(s,"bad\\r\\nUSER injected");}catch(IllegalArgumentException e){rejected=true;}check(rejected,"control injection accepted");
  var anonymous=config(port);anonymous.setUsername("");anonymous.setPassword("");anonymous.setAllowInsecureAuth(false);check(!NetworkStorageAccess.list(anonymous,"").entries().isEmpty(),"anonymous access unavailable");
  var smb=NetworkStorage.create("smb");smb.setHost("127.0.0.1");smb.setShare("bad");check(NetworkStorageAccess.list(smb,"").shareRediscovered()&&smb.getShare().isEmpty(),"SMB classified root recovery lost");smb.setShare("auth");rejected=false;try{NetworkStorageAccess.list(smb,"");}catch(java.io.IOException e){rejected=true;}check(rejected&&smb.getShare().equals("auth"),"SMB auth cleared share");smb.setShare("share");try(var r=NetworkStorageAccess.open(smb,"file",2)){check(r.length()==4&&r.read(b,0,4)==2&&b[0]==2,"SMB shared reader broken");}
  var web=NetworkStorage.create("webdav");web.setHost("owned.invalid");web.setPath("/library");web.setUsername("fixture");web.setPassword("secret");NetworkStorageStore.save(web);check(!NetworkStorageAccess.list(web,"").shareRediscovered(),"WebDAV dispatch changed");var resolved=NetworkPlayResolver.resolveWebDav(web.toPlayUrl("file space.mp4"));check(resolved.url().equals("https://owned.invalid/library/file%20space.mp4")&&resolved.headers().containsKey("Authorization"),"WebDAV HTTP/auth resolution regressed");
  var unsupported=NetworkStorage.create("unknown");unsupported.setHost("127.0.0.1");check(!unsupported.isValid(),"unknown protocol accepted");
  System.out.println("PASS production FTP socket integration: auth/anonymous, UTF8/list/empty, binary/REST/EOF/ranges, missing/auth/plaintext errors, injection rejection, URI, shared SMB dispatch/reader and transfer lifecycle");
 }
}'''
}
def main():
 import xml.etree.ElementTree as ET
 for flavor in ['leanback','mobile']:
  layout=ET.parse(ROOT/f'app/src/{flavor}/res/layout/activity_network_storage.xml').getroot()
  android='{http://schemas.android.com/apk/res/android}'
  buttons={e.get(android+'id'):e for e in layout.iter() if e.get(android+'id') in ['@+id/addSmb','@+id/addWebdav','@+id/addFtp']}
  gap='16dp' if flavor=='leanback' else '12dp'
  assert buttons['@+id/addSmb'].get(android+'layout_marginEnd')==buttons['@+id/addWebdav'].get(android+'layout_marginEnd')==gap
  adapter=(ROOT/f'app/src/{flavor}/java/com/fongmi/android/tv/ui/adapter/NetworkStorageAdapter.java').read_text(encoding='utf-8')
  assert 'item.isFtp() ? R.string.network_storage_type_ftp' in adapter
  edit=(ROOT/f'app/src/{flavor}/java/com/fongmi/android/tv/ui/activity/NetworkStorageEditActivity.java').read_text(encoding='utf-8')
  assert 'network_storage_ftp_insecure_auth' in edit and 'mStorage.isWebDav() ? View.VISIBLE : View.GONE' in edit
 factory=(ROOT/'app/src/main/java/com/fongmi/android/tv/player/exo/MediaSourceFactory.java').read_text(encoding='utf-8')
 assert 'new NetworkRoutingDataSource(upstream.createDataSource())' in factory
 print('PASS FTP TV/mobile profile labels/security wiring and manifest-aware media factory')
 p=argparse.ArgumentParser();p.add_argument('--jianpian-source',type=Path,default=ROOT/'app/src/main/java/com/fongmi/android/tv/player/extractor/JianPian.java');p.add_argument('--commons-net-jar',type=Path,default=ROOT/'.pi/commons-net-3.12.0.jar');p.add_argument('--commons-io-jar',type=Path,default=ROOT/'.pi/commons-io-2.19.0.jar');p.add_argument('--pyftpdlib-dir',type=Path,default=ROOT/'.pi/ftp-test-libs');a=p.parse_args();sys.path.insert(0,str(a.pyftpdlib_dir))
 from pyftpdlib.authorizers import DummyAuthorizer
 from pyftpdlib.handlers import FTPHandler
 from pyftpdlib.servers import FTPServer
 with TemporaryDirectory() as tmp:
  folder=Path(tmp);data=folder/'server'/'root';data.mkdir(parents=True);(data/'empty').mkdir();(data/'中文 空格#.bin').write_bytes(bytes(i%251 for i in range(4096)))
  with (data/'large.bin').open('wb') as large:large.seek(2147483657);large.write(b'TAIL')
  authorizer=DummyAuthorizer();authorizer.add_user('owned','fixture',str(folder/'server'),perm='elr');authorizer.add_anonymous(str(folder/'server'),perm='elr')
  class Handler(FTPHandler):pass
  Handler.authorizer=authorizer;Handler.masquerade_address='192.0.2.200' # PASV address must not redirect the data socket.
  server=FTPServer(('127.0.0.1',0),Handler);port=server.socket.getsockname()[1];thread=threading.Thread(target=lambda:server.serve_forever(timeout=.05),daemon=True);thread.start()
  try:
   src=[ROOT/f'app/src/main/java/com/fongmi/android/tv/{x}.java' for x in ['storage/NetworkStorage','storage/NetworkPathPolicy','storage/NetworkEntry','storage/NetworkPlayResolver','storage/FtpClientHelper','storage/NetworkStorageAccess','player/exo/NetworkFileDataSource','player/exo/NetworkRoutingDataSource']]
   for name in ['Force','Push','Strm','Thunder','TVBus','Video','Youtube']:
    STUBS['com/fongmi/android/tv/player/extractor/'+name+'.java']='package com.fongmi.android.tv.player.extractor;public class '+name+' implements Source.Extractor {public boolean match(android.net.Uri u){return false;}public String fetch(String s){return s;}public void stop(){}public void exit(){}public static class Parser {public static boolean match(String s){return false;}public static java.util.concurrent.Callable<java.util.List<com.fongmi.android.tv.bean.Episode>> get(String s){return ()->java.util.List.of();}}}'
   src.extend([ROOT/'app/src/main/java/com/fongmi/android/tv/player/extractor/Source.java',a.jianpian_source])
   for name,body in STUBS.items():
    f=folder/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(body,encoding='utf-8');src.append(f)
   classes=folder/'classes';subprocess.run(['javac','-encoding','UTF-8','-cp',str(a.commons_net_jar),'-d',str(classes),*map(str,src)],check=True)
   import os
   cp=str(classes)+os.pathsep+str(a.commons_net_jar)+os.pathsep+str(a.commons_io_jar)
   subprocess.run(['java','-cp',cp,'FtpProbe',str(port)],check=True)
   server.close_all();thread.join(2)
   for mode in ['legacy','unknown','no-size-fact','no-rest','truncated']:
    class CompatibilityHandler(Handler):
     def ftp_MLSD(self,path):self.respond('502 MLSD unsupported')
     def ftp_MLST(self,path):
      if mode=='no-size-fact':
       self.respond('250-Listing');self.push(' type=file;perm=r; unnamed' + chr(13) + chr(10));self.respond('250 End')
      else:self.respond('502 MLST unsupported')
     def ftp_SIZE(self,path):
      if mode in ['unknown','no-size-fact']:self.respond('502 SIZE unsupported')
      elif mode=='truncated':self.respond('213 8192')
      else:super().ftp_SIZE(path)
     def ftp_REST(self,line):
      if mode=='no-rest':self.respond('502 REST unsupported')
      else:super().ftp_REST(line)
    server=FTPServer(('127.0.0.1',0),CompatibilityHandler);port=server.socket.getsockname()[1];thread=threading.Thread(target=lambda:server.serve_forever(timeout=.05),daemon=True);thread.start()
    try:subprocess.run(['java','-cp',cp,'FtpProbe',str(port),mode],check=True)
    finally:server.close_all();thread.join(2)
  finally:server.close_all();thread.join(2)
if __name__=='__main__':main()
