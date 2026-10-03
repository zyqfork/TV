"""Compile the production AirPlay VideoRenderer with minimal Android/pipeline test doubles.

Tests decoder startup decisions, not hardware pixels or an end-to-end AirPlay session.
Usage: python other/tests/test_airplay_decoder_start.py [--kotlin-lib-dir GRADLE_HOME/lib]
Without that flag a kotlinc/java installation is required. No Android SDK required.
"""
import argparse
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "airplay/src/main/kotlin/io/github/jqssun/airplay/renderer/VideoRenderer.kt"
STUBS = {
    "Surface.kt": "package android.view\nclass Surface",
    "Build.kt": "package android.os\nobject Build { object VERSION { var SDK_INT = 32 } }",
    "Log.kt": """package android.util
object Log {
 fun w(tag: String, msg: String, error: Throwable? = null) = 0
 fun i(tag: String, msg: String) = 0
}
""",
    "Pipeline.kt": """package io.github.jqssun.airplay.renderer
import android.view.Surface
class VideoPipeline {
 val inputSurface: Surface? = Surface()
 fun start() {}
 fun setVideoSize(w: Int, h: Int) {}
 fun setDisplaySurface(surface: Surface?) {}
 fun release() {}
}
""",
    "Media.kt": """package android.media
import android.view.Surface
import java.nio.ByteBuffer
object Fake {
 val attempts = mutableListOf<String>()
 val releases = mutableListOf<String>()
 var fail = ""
 var queries = 0
 var queryFails = false
 var hasSoftware = true
 var softwareSupportsSize = true
 fun reset() {
  attempts.clear(); releases.clear(); fail = ""; queries = 0
  queryFails = false; hasSoftware = true; softwareSupportsSize = true
 }
}
class MediaFormat {
 fun setInteger(key: String, value: Int) {}
 companion object {
  const val MIMETYPE_VIDEO_HEVC = "video/hevc"
  const val MIMETYPE_VIDEO_AVC = "video/avc"
  const val KEY_MAX_INPUT_SIZE = "max-input-size"
  const val KEY_COLOR_STANDARD = "color-standard"
  const val KEY_COLOR_RANGE = "color-range"
  const val KEY_COLOR_TRANSFER = "color-transfer"
  const val KEY_PRIORITY = "priority"
  const val KEY_OPERATING_RATE = "operating-rate"
  const val KEY_ALLOW_FRAME_DROP = "allow-frame-drop"
  const val KEY_LOW_LATENCY = "low-latency"
  const val COLOR_STANDARD_BT709 = 1
  const val COLOR_RANGE_LIMITED = 2
  const val COLOR_TRANSFER_SDR_VIDEO = 3
  fun createVideoFormat(mime: String, w: Int, h: Int) = MediaFormat()
 }
}
class MediaCodec(val name: String) {
 class BufferInfo { var presentationTimeUs = 0L }
 fun configure(format: MediaFormat, surface: Surface, crypto: Any?, flags: Int) {
  if (name == "hardware" && Fake.fail == "configure") error("configure failed")
 }
 fun start() { if (name == "hardware" && Fake.fail == "start") error("start failed") }
 fun stop() {}
 fun release() { Fake.releases.add(name) }
 fun dequeueInputBuffer(timeout: Long) = -1
 fun getInputBuffer(index: Int): ByteBuffer? = null
 fun queueInputBuffer(index: Int, offset: Int, size: Int, pts: Long, flags: Int) {}
 fun dequeueOutputBuffer(info: BufferInfo, timeout: Long) = -1
 fun releaseOutputBuffer(index: Int, render: Boolean) {}
 fun releaseOutputBuffer(index: Int, time: Long) {}
 companion object {
  fun createDecoderByType(mime: String): MediaCodec {
   Fake.attempts.add("hardware"); return MediaCodec("hardware")
  }
  fun createByCodecName(name: String): MediaCodec {
   Fake.attempts.add(name); return MediaCodec(name)
  }
 }
}
class Range(val upper: Int)
class VideoCapabilities {
 val supportedWidths = Range(4096)
 val supportedHeights = Range(4096)
 fun isSizeSupported(w: Int, h: Int) = Fake.softwareSupportsSize
}
class Capabilities { val videoCapabilities: VideoCapabilities? = VideoCapabilities() }
class Info(val name: String, val isSoftwareOnly: Boolean) {
 val isEncoder = false
 val supportedTypes = arrayOf("video/hevc", "video/avc")
 fun getCapabilitiesForType(mime: String) = Capabilities()
}
class MediaCodecList(kind: Int) {
 val codecInfos: Array<Info> get() {
  Fake.queries++
  if (Fake.queryFails) error("codec list failed")
  return if (Fake.hasSoftware) arrayOf(Info("c2.vendor.hardware", false), Info("c2.android.test", true))
   else arrayOf(Info("c2.vendor.hardware", false))
 }
 companion object { const val ALL_CODECS = 1; const val REGULAR_CODECS = 0 }
}
""",
    "Probe.kt": """import android.media.Fake
import io.github.jqssun.airplay.renderer.VideoRenderer
import java.lang.reflect.InvocationTargetException
fun runCase(height: Int, fail: String = "", queryFails: Boolean = false,
            hasSoftware: Boolean = true, sizeSupported: Boolean = true,
            expectFallback: Boolean = false, expectFailure: Boolean = false,
            hevc: Boolean = true) {
 Fake.reset(); Fake.fail = fail; Fake.queryFails = queryFails
 Fake.hasSoftware = hasSoftware; Fake.softwareSupportsSize = sizeSupported
 val renderer = VideoRenderer(); renderer.setResolution(1080, height)
 val method = VideoRenderer::class.java.getDeclaredMethod("startCodec", Boolean::class.javaPrimitiveType)
 method.isAccessible = true
 var failure: Throwable? = null
 try { method.invoke(renderer, hevc) } catch (e: InvocationTargetException) { failure = e.targetException }
 check((failure != null) == expectFailure) { "unexpected failure $height $fail: $failure" }
 val expected = if (expectFallback) listOf("hardware", "c2.android.test") else listOf("hardware")
 check(Fake.attempts == expected) { "decoder order ${Fake.attempts}, expected $expected" }
 check(if (fail.isEmpty()) Fake.queries == 0 else Fake.queries == 1) { "software query not lazy" }
 if (fail.isNotEmpty()) check(Fake.releases.contains("hardware")) { "failed hardware leaked" }
 if (expectFailure) check(failure?.message == "$fail failed") { "original failure lost" }
 renderer.release()
}
fun invoke(renderer: VideoRenderer, name: String, vararg args: Any?) {
 val types = args.map { if (it is Boolean) Boolean::class.javaPrimitiveType!! else it!!::class.java }.toTypedArray()
 val method = VideoRenderer::class.java.getDeclaredMethod(name, *types)
 method.isAccessible = true
 method.invoke(renderer, *args)
}

/** A codec that accepts configure/start and never emits a buffer must fall back once, then stop. */
fun runStallCase() {
 Fake.reset()
 val renderer = VideoRenderer(); renderer.setResolution(1080, 2340)
 invoke(renderer, "startCodec", true)
 check(Fake.attempts == listOf("hardware")) { "stall: first start ${Fake.attempts}" }
 Thread.sleep(3_100)
 invoke(renderer, "_checkStalledStart")
 check(Fake.releases.contains("hardware")) { "stall: stalled codec not released" }
 invoke(renderer, "startCodec", true)
 check(Fake.attempts == listOf("hardware", "c2.android.test")) { "stall: software retry ${Fake.attempts}" }
 Thread.sleep(3_100)
 invoke(renderer, "_checkStalledStart")
 val codec = VideoRenderer::class.java.getDeclaredField("codec").apply { isAccessible = true }.get(renderer)
 check(codec != null) { "stall: second stall must not restart the codec again" }
 renderer.release()
}

fun main() {
 // Tall input and even a broken software codec list must not preempt hardware.
 for (h in listOf(1080, 2160, 2340, 3840)) runCase(h, queryFails = true)
 runCase(2340, hevc = false)
 runCase(2340, fail = "configure", expectFallback = true)
 runCase(2340, fail = "start", expectFallback = true)
 runCase(2340, fail = "configure", queryFails = true, expectFailure = true)
 runCase(2340, fail = "configure", hasSoftware = false, expectFailure = true)
 runCase(2340, fail = "configure", sizeSupported = false, expectFailure = true)
 android.os.Build.VERSION.SDK_INT = 28
 runCase(2340, fail = "configure", expectFallback = true)
 runStallCase()
 println("PASS production AirPlay startup: hardware first at all heights, lazy software fallback, failed codec released, stalled start retried once")
}
""",
}

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--kotlin-lib-dir", type=Path)
args = parser.parse_args()
with TemporaryDirectory() as temp:
    path = Path(temp)
    sources = [str(SOURCE)]
    for name, content in STUBS.items():
        stub = path / name
        stub.write_text(content, encoding="utf-8")
        sources.append(str(stub))
    classes = path / "classes"
    if args.kotlin_lib_dir:
        lib = args.kotlin_lib_dir.resolve()
        jars = sorted(lib.glob("kotlin-*.jar")) + sorted(lib.glob("kotlinx-coroutines-core-jvm*.jar")) + sorted(lib.glob("annotations-*.jar"))
        compiler_cp = os.pathsep.join(map(str, jars))
        runtime_cp = os.pathsep.join(map(str, sorted(lib.glob("kotlin-stdlib-*.jar"))))
        subprocess.run(["java", "-cp", compiler_cp, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", runtime_cp,
                        "-nowarn", "-d", str(classes), *sources], check=True)
        subprocess.run(["java", "-cp", str(classes) + os.pathsep + runtime_cp, "ProbeKt"], check=True)
    else:
        jar = path / "probe.jar"
        subprocess.run(["kotlinc", "-nowarn", *sources, "-include-runtime", "-d", str(jar)], check=True)
        subprocess.run(["java", "-jar", str(jar)], check=True)
