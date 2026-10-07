"""Compile production snapshot store; real temporary gzip files, Android/atomic-writer doubles.
Checks large configs stay outside SQLite rows, source identity, corruption and write failure.
"""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
 'com/fongmi/android/tv/App.java': 'package com.fongmi.android.tv;public class App {public static App get(){return new App();}public java.io.File getFilesDir(){return new java.io.File(System.getProperty("snapshot.root"));}}',
 'com/fongmi/android/tv/bean/Config.java': 'package com.fongmi.android.tv.bean;public class Config {public final int type;public final String url;public String json;public Config(int t,String u){type=t;url=u;}public int getType(){return type;}public String getUrl(){return url;}public String getJson(){return json;}public void setJson(String s){json=s;}}',
 'com/fongmi/android/tv/utils/FileUtil.java': '''package com.fongmi.android.tv.utils;public class FileUtil {public static boolean fail;public static void writeAtomically(byte[] b,java.io.File f)throws java.io.IOException {if(fail)throw new java.io.IOException("injected disk failure");f.getParentFile().mkdirs();var tmp=java.nio.file.Files.createTempFile(f.getParentFile().toPath(),"snapshot-",".tmp");java.nio.file.Files.write(tmp,b);java.nio.file.Files.move(tmp,f.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}}''',
 'SnapshotProbe.java': '''import com.fongmi.android.tv.api.config.ConfigSnapshotStore;import com.fongmi.android.tv.bean.Config;import com.fongmi.android.tv.utils.FileUtil;import java.nio.file.*;
public class SnapshotProbe {
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  var c=new Config(0,"https://owned/source.json");String large="中文配置".repeat(300000);ConfigSnapshotStore.save(c,large);check(c.json.length()<128,"large snapshot stored in SQLite row");check(ConfigSnapshotStore.read(c).equals(large),"gzip UTF8/large body round trip failed");
  var live=new Config(1,c.url);ConfigSnapshotStore.save(live,"live text");check(!c.json.equals(live.json)&&ConfigSnapshotStore.read(c).equals(large),"VOD/live shared a file");
  var other=new Config(0,"https://owned/other.json");other.json=c.json;boolean rejected=false;try{ConfigSnapshotStore.read(other);}catch(java.io.IOException e){rejected=true;}check(rejected,"snapshot/source mismatch accepted");
  String marker=c.json;FileUtil.fail=true;ConfigSnapshotStore.save(c,"replacement");FileUtil.fail=false;check(c.json.equals(marker)&&ConfigSnapshotStore.read(c).equals(large),"failed write erased last good snapshot");
  Thread.currentThread().interrupt();ConfigSnapshotStore.save(c,"canceled");Thread.interrupted();check(ConfigSnapshotStore.read(c).equals(large),"canceled write changed snapshot");
  var inline=new Config(0,"https://owned/legacy");inline.json="{legacy-inline}";check(ConfigSnapshotStore.read(inline).equals(inline.json),"legacy inline snapshot incompatible");
  var folder=Path.of(System.getProperty("snapshot.root"),"source-snapshots");var file=folder.resolve(c.json.substring(c.json.indexOf('/')+1));Files.writeString(file,"corrupt");rejected=false;try{ConfigSnapshotStore.read(c);}catch(java.io.IOException e){rejected=true;}check(rejected,"corrupt gzip accepted");
  Files.delete(file);rejected=false;try{ConfigSnapshotStore.read(c);}catch(java.io.IOException e){rejected=true;}check(rejected,"missing snapshot not reported for fallback");
  System.out.println("PASS production snapshot store: gzip/UTF8/large body, tiny DB reference, per-source/type isolation, failed/canceled writes, legacy/corrupt/missing snapshots");
 }
}''',
}


def main():
    with TemporaryDirectory() as tmp:
        folder = Path(tmp)
        sources = [ROOT / 'app/src/main/java/com/fongmi/android/tv/api/config/ConfigSnapshotStore.java']
        for name, body in STUBS.items():
            p = folder / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(body, encoding='utf-8')
            sources.append(p)
        classes = folder / 'classes'
        subprocess.run(['javac','-encoding','UTF-8','-d',str(classes),*map(str,sources)],check=True)
        subprocess.run(['java',f'-Dsnapshot.root={folder / "private"}','-cp',str(classes),'SnapshotProbe'],check=True)


if __name__ == '__main__':
    main()
