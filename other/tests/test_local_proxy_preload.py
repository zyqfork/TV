#!/usr/bin/env python3
"""Exact shared-proxy guard from VodPlaybackController. Does not prove a device playback session."""
from pathlib import Path
import tempfile, subprocess
root = Path(__file__).resolve().parents[2]
source = (root / "app/src/main/java/com/fongmi/android/tv/playback/vod/VodPlaybackController.java").read_text(encoding="utf-8")
start = source.index("    static boolean usesSharedLocalProxy(")
end = source.index("\n    private void applyPlayerResult", start)
method = source[start:end]
java = r'''
public class Probe {
METHOD
 static void check(boolean actual, boolean expected, String why) { if (actual != expected) throw new AssertionError(why); }
 public static void main(String[] args) {
  check(usesSharedLocalProxy("http://127.0.0.1:6677/proxy/play/quark/null/09.mkv"), true, "current quark proxy");
  check(usesSharedLocalProxy("http://127.0.0.1:6678/play/file"), true, "alternate plugin port");
  check(usesSharedLocalProxy("http://localhost/proxy/x"), true, "localhost proxy");
  check(usesSharedLocalProxy("http://[::1]:6677/proxy/a"), true, "ipv6 loopback");
  check(usesSharedLocalProxy("http://user@127.0.0.1:6677/proxy/a?x=1"), true, "userinfo and query");
  check(usesSharedLocalProxy("https://pan.quark.cn/s/abc"), false, "remote quark page");
  check(usesSharedLocalProxy("http://192.168.1.8:6677/proxy/a"), false, "non-loopback host");
  check(usesSharedLocalProxy("http://127.0.0.1:6677/other"), false, "loopback non-proxy path");
  check(usesSharedLocalProxy(""), false, "empty");
  check(usesSharedLocalProxy(null), false, "null");
  System.out.println("PASS shared local proxy guard");
 }
}
'''.replace("METHOD", method)
with tempfile.TemporaryDirectory() as directory:
    path = Path(directory) / "Probe.java"
    path.write_text(java, encoding="utf-8")
    subprocess.run(["javac", "-d", directory, str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "Probe"], check=True)
controller = source
assert "usesSharedLocalProxy(quality.getUrl().v())" in controller
assert "dataSource.preloadContent(request);" in controller
print("PASS production wiring still preloads only after the shared-proxy guard")
