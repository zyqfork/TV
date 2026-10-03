"""Compile the production AirPlay VideoRenderer with minimal Android/pipeline test doubles.

Tests startup decisions and feedFrame recovery/replay, not hardware pixels or a real AirPlay session.
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
 var softwareFail = ""
 var inputError = ""
 val blockedInputs = mutableSetOf<String>()
 val outputCodecs = mutableSetOf<String>()
 data class Packet(val codec: String, val data: ByteArray, val pts: Long)
 val packets = mutableListOf<Packet>()
 fun reset() {
  attempts.clear(); releases.clear(); fail = ""; queries = 0
  queryFails = false; hasSoftware = true; softwareSupportsSize = true
  softwareFail = ""; inputError = ""; blockedInputs.clear(); outputCodecs.clear(); packets.clear()
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
 private val input = ByteBuffer.allocate(9 * 1024 * 1024)
 private var pendingOutput = 0
 private var lastPts = 0L
 class BufferInfo { var presentationTimeUs = 0L }
 fun configure(format: MediaFormat, surface: Surface, crypto: Any?, flags: Int) {
  if ((name == "hardware" && Fake.fail == "configure") ||
      (name != "hardware" && Fake.softwareFail == "configure")) error("configure failed")
 }
 fun start() {
  if ((name == "hardware" && Fake.fail == "start") ||
      (name != "hardware" && Fake.softwareFail == "start")) error("start failed")
 }
 fun stop() {}
 fun release() { Fake.releases.add(name) }
 fun dequeueInputBuffer(timeout: Long): Int {
  if (Fake.inputError == name) error("input failed")
  return if (name in Fake.blockedInputs) -1 else 0
 }
 fun getInputBuffer(index: Int): ByteBuffer? = input
 fun queueInputBuffer(index: Int, offset: Int, size: Int, pts: Long, flags: Int) {
  val data = ByteArray(size); input.flip(); input.get(data)
  Fake.packets.add(Fake.Packet(name, data, pts)); lastPts = pts
  if (name in Fake.outputCodecs) pendingOutput++
 }
 fun dequeueOutputBuffer(info: BufferInfo, timeout: Long): Int {
  if (pendingOutput == 0) return -1
  pendingOutput--; info.presentationTimeUs = lastPts; return 0
 }
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

fun field(r: VideoRenderer, name: String): Any? =
 VideoRenderer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(r)
fun expire(r: VideoRenderer) {
 VideoRenderer::class.java.getDeclaredField("decoderStartNs").apply { isAccessible = true }
  .setLong(r, System.nanoTime() - 4_000_000_000L)
}
fun nal(h265: Boolean, type: Int): ByteArray =
 byteArrayOf(0, 0, 0, 1, (if (h265) type shl 1 else type).toByte(), 1, 42)
fun inter(h265: Boolean) = nal(h265, 1)
fun key(h265: Boolean) = nal(h265, if (h265) 19 else 5)
fun bootstrap(r: VideoRenderer, h265: Boolean): List<ByteArray> {
 val types = if (h265) listOf(32, 33, 34, 19) else listOf(7, 8, 5)
 val data = types.map { nal(h265, it) }
 data.forEachIndexed { i, packet -> r.feedFrame(packet, (i + 1) * 1000L, h265) }
 return data
}

/** Recover through the actual receive gate even when the sender never sends a second keyframe. */
fun runStallCase(h265: Boolean) {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340)
 val expected = bootstrap(r, h265).toMutableList()
 val next = inter(h265); expected.add(next)
 expire(r); r.feedFrame(next, 5_000, h265)
 check(Fake.attempts == listOf("hardware", "c2.android.test")) { "no feedFrame software retry: ${Fake.attempts}" }
 check(Fake.releases.contains("hardware"))
 val replay = Fake.packets.filter { it.codec == "c2.android.test" }
 check(replay.size == expected.size)
 replay.zip(expected).forEach { (packet, bytes) -> check(packet.data.contentEquals(bytes)) }
 check(replay.last().pts == 5L) { "replay changed timestamps" }
 // A stalled software decoder must not create an unbounded restart loop.
 expire(r); r.feedFrame(next, 6_000, h265)
 check(Fake.attempts.size == 2 && field(r, "codec") != null)
 // A later runtime codec error must not undo the software decision.
 Fake.inputError = "c2.android.test"; r.feedFrame(next, 7_000, h265)
 check(field(r, "codec") == null)
 Fake.inputError = ""; r.feedFrame(key(h265), 8_000, h265)
 check(Fake.attempts == listOf("hardware", "c2.android.test", "c2.android.test"))
 // Same dimensions do not re-open the decision; a new session does.
 r.setResolution(1080, 2340)
 check(field(r, "forceSoftwareStart") == true)
 r.resetSession(); check(field(r, "startupBytes") == 0)
 r.setResolution(1080, 2340); r.feedFrame(key(h265), 9_000, h265)
 check(Fake.attempts.last() == "hardware")
 r.release()
}

fun runOutputCase() {
 Fake.reset(); Fake.outputCodecs.add("hardware")
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 check(field(r, "firstOutputSeen") == true && field(r, "startupBytes") == 0)
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(Fake.attempts == listOf("hardware") && Fake.queries == 0)
 r.release()
 Fake.reset(); Fake.outputCodecs.add("c2.android.test")
 val sw = VideoRenderer(); sw.setResolution(1080, 2340); bootstrap(sw, true)
 expire(sw); sw.feedFrame(inter(true), 5_000, true)
 check(field(sw, "firstOutputSeen") == true && field(sw, "startupBytes") == 0)
 sw.release()
}

fun runResolutionCase() {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 val old = field(r, "codec")
 r.setResolution(1080, 2400)
 check(field(r, "codec") === old && (field(r, "decoderStartNs") as Long) > 0L)
 check(field(r, "startupBytes") == 0)
 val expected = bootstrap(r, true).toMutableList(); expected.add(inter(true))
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test"))
 val replay = Fake.packets.filter { it.codec == "c2.android.test" }
 check(replay.size == expected.size) { "old-resolution startup leaked into replay" }
 r.setResolution(1080, 2500)
 Fake.inputError = "c2.android.test"; r.feedFrame(inter(true), 6_000, true)
 Fake.inputError = ""; r.feedFrame(key(true), 7_000, true)
 check(Fake.attempts.last() == "hardware") { "dimensions did not re-open hardware choice" }
 r.release()
}

fun runUnavailableCase(queryFails: Boolean = false, hasSoftware: Boolean = true,
                       supports: Boolean = true, overflow: Boolean = false, frameOverflow: Boolean = false) {
 Fake.reset(); Fake.queryFails = queryFails; Fake.hasSoftware = hasSoftware
 Fake.softwareSupportsSize = supports
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 if (overflow) r.feedFrame(ByteArray(8 * 1024 * 1024 + 1), 5_000, true)
 if (frameOverflow) repeat(257) { r.feedFrame(inter(true), 5_000, true) }
 expire(r); r.feedFrame(inter(true), 6_000, true)
 check(Fake.attempts == listOf("hardware") && field(r, "codec") != null)
 check(Fake.releases.isEmpty()) { "watchdog destroyed decoder without a viable replay" }
 check(field(r, "startupBytes") == 0 && field(r, "softwareFallbackTried") == true)
 val queries = Fake.queries
 repeat(10) { r.feedFrame(inter(true), 7_000, true) }
 check(Fake.queries == queries) { "unavailable recovery retried forever" }
 r.release()
}

fun runIncompleteReplayCase() {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340)
 // A keyframe without parameter sets cannot bootstrap a new decoder without CSD.
 r.feedFrame(key(true), 1_000, true)
 expire(r); r.feedFrame(inter(true), 2_000, true)
 check(Fake.attempts == listOf("hardware") && Fake.releases.isEmpty())
 check(Fake.queries == 0 && field(r, "startupBytes") == 0)
 r.release()
}

fun runConfigureFallbackResetCase() {
 Fake.reset(); Fake.fail = "configure"
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test"))
 Fake.inputError = "c2.android.test"; r.feedFrame(inter(true), 5_000, true)
 Fake.inputError = ""; r.feedFrame(key(true), 6_000, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test", "c2.android.test"))
 r.release()
}

fun runSoftwareFailureCase(fail: String = "", blocked: Boolean = false) {
 Fake.reset(); Fake.softwareFail = fail
 if (blocked) Fake.blockedInputs.add("c2.android.test")
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(field(r, "codec") == null && field(r, "forceSoftwareStart") == true)
 check(Fake.releases.contains("c2.android.test")) { "failed software leaked" }
 Fake.softwareFail = ""; Fake.blockedInputs.clear()
 r.feedFrame(key(true), 6_000, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test", "c2.android.test"))
 // A codec-type change opens a new default-decoder decision.
 r.feedFrame(key(false), 7_000, false)
 check(Fake.attempts.last() == "hardware")
 r.release()
}

fun runInputWaitCase() {
 Fake.reset(); Fake.blockedInputs.add("hardware")
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(Fake.attempts == listOf("hardware")) { "watchdog classified an unfed decoder as stalled" }
 Fake.blockedInputs.clear(); r.feedFrame(inter(true), 6_000, true)
 check((field(r, "decoderStartNs") as Long) > System.nanoTime() - 1_000_000_000)
 expire(r); r.feedFrame(inter(true), 7_000, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test"))
 r.release()
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
 runStallCase(true); runStallCase(false)
 runOutputCase(); runResolutionCase(); runInputWaitCase()
 runIncompleteReplayCase(); runConfigureFallbackResetCase()
 runUnavailableCase(queryFails = true)
 runUnavailableCase(hasSoftware = false)
 runUnavailableCase(supports = false)
 runUnavailableCase(overflow = true)
 runUnavailableCase(frameOverflow = true)
 runSoftwareFailureCase(fail = "configure")
 runSoftwareFailureCase(fail = "start")
 runSoftwareFailureCase(blocked = true)
 println("PASS production AirPlay: hardware first, bounded feedFrame startup replay, persistent software choice, resolution/session reset, output and failure guards")
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
