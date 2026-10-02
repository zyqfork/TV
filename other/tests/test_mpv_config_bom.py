#!/usr/bin/env python3
"""Compile the production mpv.conf parser with minimal Android I/O stubs (no device data)."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
    'android/content/Context.java': 'package android.content; public class Context {}',
    'android/net/Uri.java': 'package android.net; public class Uri {}',
    'com/fongmi/android/tv/utils/FileUtil.java': '''package com.fongmi.android.tv.utils;
import android.content.Context; import android.net.Uri; import java.io.*;
public class FileUtil {
 public static void writeAtomically(byte[] data, File dest) throws IOException { throw new IOException(); }
 public static void copyAtomically(Uri uri, File dest) throws IOException { throw new IOException(); }
}''',
    'com/github/catvod/utils/Path.java': '''package com.github.catvod.utils;
import java.io.*; import java.nio.file.*;
public class Path {
 public static File mpv(String name) { return new File(System.getProperty("mpv.test.dir"), name); }
 public static File font() { return new File(System.getProperty("mpv.test.dir")); }
 public static String read(File file) { try { return Files.readString(file.toPath()); } catch (IOException e) { return null; } }
 public static void write(File file, byte[] bytes) { throw new AssertionError("no disk changes"); }
}''',
    'com/fongmi/android/tv/player/mpv/MpvUtil.java': '''package com.fongmi.android.tv.player.mpv;
import java.util.*;
public class MpvUtil { public static List<String> getManagedOptionNames() { return List.of("vo", "hwdec"); } }''',
    'ConfigProbe.java': r'''import com.fongmi.android.tv.player.mpv.MpvConfigFiles;
import androidx.media3.mpvplayer.MpvAutomaticOutputPolicy;
import java.nio.file.*; import java.util.*;
public class ConfigProbe {
 static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
 public static void main(String[] args) throws Exception {
  String content = "\uFEFF--vo=gpu # comment\n[cinema]\nhwdec=mediacodec-copy\n[default]\nno-hwdec\nuser-agent=\"Foo#Bar\" # trailing\n";
  Files.writeString(MpvConfigFiles.file().toPath(), content);
  Map<String,String> actual = MpvConfigFiles.readGlobalOptions();
  check("gpu".equals(actual.get("vo")), "UTF-8 BOM before first vo ignored");
  check(!MpvAutomaticOutputPolicy.acceptsUserOptions(actual, false, false),
      "BOM-prefixed vo=gpu silently bypassed automatic-output veto");
  check("no".equals(actual.get("hwdec")), "[default] no-hwdec normalization");
  check("Foo#Bar".equals(actual.get("user-agent")), "quoted # consumed as comment");
  check(actual.size() == 3, "profile leaked into global options: " + actual);
  check(MpvConfigFiles.findInterfaceManagedOptions(content).equals(List.of("vo", "hwdec")),
      "warning disagrees with applied options");
  Files.writeString(MpvConfigFiles.file().toPath(), "vo=mediacodec_embed\nhwdec=mediacodec\n");
  check("mediacodec_embed".equals(MpvConfigFiles.readGlobalOptions().get("vo")), "plain config");
  System.out.println("PASS production mpv.conf BOM / quoted comments / profiles / no- and warning parity");
 }
}''',
}
with tempfile.TemporaryDirectory() as directory:
    out = Path(directory)
    files = []
    for name, content in STUBS.items():
        path = out / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding='utf8')
        files.append(str(path))
    classes = out / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '--release', '21', '-d', str(classes),
                    str(ROOT / 'app/src/main/java/com/fongmi/android/tv/player/mpv/MpvConfigFiles.java'),
                    str(ROOT / 'media3compat/src/main/java/androidx/media3/mpvplayer/MpvAutomaticOutputPolicy.java'),
                    *files], check=True)
    subprocess.run(['java', '-Dmpv.test.dir=' + str(out), '-cp', str(classes), 'ConfigProbe'], check=True)
