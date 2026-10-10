#!/usr/bin/env python3
from pathlib import Path
import tempfile,subprocess
ROOT=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as d:
 p=Path(d)/'Probe.java';p.write_text('''import com.fongmi.android.tv.api.config.ConfigLoadCancellation;import java.net.SocketTimeoutException;import java.io.InterruptedIOException;
public class Probe {static void check(boolean b){if(!b)throw new AssertionError();} public static void main(String[] args){
 check(!ConfigLoadCancellation.isCanceled(new SocketTimeoutException("Read timed out")));
 check(!ConfigLoadCancellation.isCanceled(new RuntimeException(new SocketTimeoutException("connect timeout"))));
 check(!ConfigLoadCancellation.isCanceled(new InterruptedIOException("timeout")));
 check(ConfigLoadCancellation.isCanceled(new RuntimeException(new InterruptedException())));
 check(ConfigLoadCancellation.isCanceled(new java.io.IOException("Canceled")));
 Thread.currentThread().interrupt();check(ConfigLoadCancellation.isCanceled(new InterruptedIOException()));Thread.interrupted();
 check(!ConfigLoadCancellation.isCanceled(new java.io.IOException("HTTP 500")));
 System.out.println("PASS production config cancellation: read/connect/wrapped timeout is failure; explicit cancellation/interrupted worker is cancellation");}}
''',encoding='utf-8')
 subprocess.run(['javac','-d',d,str(ROOT/'app/src/main/java/com/fongmi/android/tv/api/config/ConfigLoadCancellation.java'),str(p)],check=True)
 subprocess.run(['java','-cp',d,'Probe'],check=True)
s=(ROOT/'app/src/main/java/com/fongmi/android/tv/api/config/BaseConfig.java').read_text(encoding='utf-8')
assert 'App.post(() -> { if (taskId.get() == id) callback.error(error); });' in s
assert 'if (taskId.get() != id) return;\n                Notify.show' in s
print('PASS STATIC load UI callbacks recheck generation on dispatch, not only before posting')
