"""Compile the production AirPlay VideoRenderer with minimal Android/pipeline test doubles.

Tests startup, feedFrame recovery, cancellation and UI control responsiveness with doubles, not real pixels/sessions.
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
 var display: Surface? = null
 fun start() {}
 fun setVideoSize(w: Int, h: Int) {}
 fun setDisplaySurface(surface: Surface?) { display = surface }
 fun release() { display = null }
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
 var slowSoftware = false
 var inputWaits = 0
 var inputEntered: java.util.concurrent.CountDownLatch? = null
 var holdInput: java.util.concurrent.CountDownLatch? = null
 val codecThreads = mutableSetOf<String>()
 val blockedInputs = mutableSetOf<String>()
 val outputCodecs = mutableSetOf<String>()
 data class Packet(val codec: String, val data: ByteArray, val pts: Long)
 val packets = mutableListOf<Packet>()
 fun reset() {
  attempts.clear(); releases.clear(); fail = ""; queries = 0
  queryFails = false; hasSoftware = true; softwareSupportsSize = true
  softwareFail = ""; inputError = ""; blockedInputs.clear(); outputCodecs.clear(); packets.clear()
  slowSoftware = false; inputWaits = 0; inputEntered = null; holdInput = null; codecThreads.clear()
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
  Fake.codecThreads.add(Thread.currentThread().name)
  if ((name == "hardware" && Fake.fail == "configure") ||
      (name != "hardware" && Fake.softwareFail == "configure")) error("configure failed")
 }
 fun start() {
  if ((name == "hardware" && Fake.fail == "start") ||
      (name != "hardware" && Fake.softwareFail == "start")) error("start failed")
 }
 fun stop() { Fake.codecThreads.add(Thread.currentThread().name) }
 fun release() { Fake.codecThreads.add(Thread.currentThread().name); Fake.releases.add(name) }
 fun dequeueInputBuffer(timeout: Long): Int {
  Fake.codecThreads.add(Thread.currentThread().name)
  if (name != "hardware") {
   Fake.inputEntered?.countDown()
   Fake.holdInput?.await(10, java.util.concurrent.TimeUnit.SECONDS)
   if (Fake.slowSoftware) {
    Thread.sleep(timeout / 1000)
    Fake.inputWaits++
    if (Fake.inputWaits % 5 != 0) return -1
   }
  }
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.view.Surface
fun <T> worker(r: VideoRenderer, block: () -> T): T {
 val executor = VideoRenderer::class.java.getDeclaredField("decoderExecutor")
  .apply { isAccessible = true }.get(r) as ExecutorService
 try { return executor.submit(Callable { block() }).get(10, TimeUnit.SECONDS) }
 catch (e: ExecutionException) { throw e.cause ?: e }
}
fun close(r: VideoRenderer) { r.release(); worker(r) {} }
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
 try { worker(renderer) { method.invoke(renderer, hevc) } }
 catch (e: InvocationTargetException) { failure = e.targetException }
 check((failure != null) == expectFailure) { "unexpected failure $height $fail: $failure" }
 val expected = if (expectFallback) listOf("hardware", "c2.android.test") else listOf("hardware")
 check(Fake.attempts == expected) { "decoder order ${Fake.attempts}, expected $expected" }
 check(if (fail.isEmpty()) Fake.queries == 0 else Fake.queries == 1) { "software query not lazy" }
 if (fail.isNotEmpty()) check(Fake.releases.contains("hardware")) { "failed hardware leaked" }
 if (expectFailure) check(failure?.message == "$fail failed") { "original failure lost" }
 close(renderer)
}
fun invoke(renderer: VideoRenderer, name: String, vararg args: Any?) {
 val types = args.map { if (it is Boolean) Boolean::class.javaPrimitiveType!! else it!!::class.java }.toTypedArray()
 val method = VideoRenderer::class.java.getDeclaredMethod(name, *types)
 method.isAccessible = true
 worker(renderer) { method.invoke(renderer, *args) }
}

fun field(r: VideoRenderer, name: String): Any? = worker(r) {
 VideoRenderer::class.java.getDeclaredField(name).apply { isAccessible = true }.get(r)
}
fun expire(r: VideoRenderer) = worker(r) {
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
 check(Fake.codecThreads == setOf("AirPlayVideoDecoder"))
 close(r)
}

fun runOutputCase() {
 Fake.reset(); Fake.outputCodecs.add("hardware")
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 check(field(r, "firstOutputSeen") == true && field(r, "startupBytes") == 0)
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(Fake.attempts == listOf("hardware") && Fake.queries == 0)
 close(r)
 Fake.reset(); Fake.outputCodecs.add("c2.android.test")
 val sw = VideoRenderer(); sw.setResolution(1080, 2340); bootstrap(sw, true)
 expire(sw); sw.feedFrame(inter(true), 5_000, true)
 check(field(sw, "firstOutputSeen") == true && field(sw, "startupBytes") == 0)
 close(sw)
}

fun runResolutionCase() {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 val old = field(r, "codec")
 r.setResolution(1080, 2400)
 check(old != null && field(r, "codec") == null)
 check(field(r, "decoderStartNs") == 0L)
 check(field(r, "startupBytes") == 0)
 val expected = bootstrap(r, true).toMutableList(); expected.add(inter(true))
 expire(r); r.feedFrame(inter(true), 5_000, true)
 check(Fake.attempts == listOf("hardware", "hardware", "c2.android.test"))
 val replay = Fake.packets.filter { it.codec == "c2.android.test" }
 check(replay.size == expected.size) { "old-resolution startup leaked into replay" }
 r.setResolution(1080, 2500)
 Fake.inputError = "c2.android.test"; r.feedFrame(inter(true), 6_000, true)
 Fake.inputError = ""; r.feedFrame(key(true), 7_000, true)
 check(Fake.attempts.last() == "hardware") { "dimensions did not re-open hardware choice" }
 close(r)
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
 close(r)
}

fun runIncompleteReplayCase() {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340)
 // A keyframe without parameter sets cannot bootstrap a new decoder without CSD.
 r.feedFrame(key(true), 1_000, true)
 expire(r); r.feedFrame(inter(true), 2_000, true)
 check(Fake.attempts == listOf("hardware") && Fake.releases.isEmpty())
 check(Fake.queries == 0 && field(r, "startupBytes") == 0)
 close(r)
}

fun runConfigureFallbackResetCase() {
 Fake.reset(); Fake.fail = "configure"
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test"))
 Fake.inputError = "c2.android.test"; r.feedFrame(inter(true), 5_000, true)
 Fake.inputError = ""; r.feedFrame(key(true), 6_000, true)
 check(Fake.attempts == listOf("hardware", "c2.android.test", "c2.android.test"))
 close(r)
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
 close(r)
}

fun runInputWaitCase(h265: Boolean) {
 Fake.reset(); Fake.blockedInputs.add("hardware"); Fake.outputCodecs.add("c2.android.test")
 val r = VideoRenderer(); r.setResolution(1080, 2340)
 invoke(r, "startCodec", h265)
 // No source input: neither elapsed wall time nor a codec start alone triggers fallback.
 check(field(r, "decoderStartNs") == 0L)
 invoke(r, "_checkStalledStart")
 check(Fake.attempts == listOf("hardware") && Fake.queries == 0)
 // Complete source input exists, but hardware never offers an input slot.
 val expected = bootstrap(r, h265).toMutableList(); expected.add(inter(h265))
 check(field(r, "firstFrameQueued") == false)
 expire(r); r.feedFrame(inter(h265), 5_000, h265)
 check(Fake.attempts == listOf("hardware", "c2.android.test")) { "no recovery for unavailable input" }
 val replay = Fake.packets.filter { it.codec == "c2.android.test" }
 check(replay.size == expected.size)
 replay.zip(expected).forEach { (packet, bytes) -> check(packet.data.contentEquals(bytes)) }
 check(field(r, "firstOutputSeen") == true)
 close(r)
}

fun runAsyncControlsCase(release: Boolean) {
 Fake.reset()
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 repeat(50) { r.feedFrame(inter(true), (it + 5) * 1000L, true) }
 expire(r)
 val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
 Fake.inputEntered = entered; Fake.holdInput = unblock
 val producer = Thread { r.feedFrame(inter(true), 60_000, true) }
 producer.start(); check(entered.await(2, TimeUnit.SECONDS))
 val waitingProducer = Thread { r.feedFrame(inter(true), 60_500, true) }
 waitingProducer.start()
 val gate = VideoRenderer::class.java.getDeclaredField("frameGate")
  .apply { isAccessible = true }.get(r) as java.util.concurrent.Semaphore
 val gateDeadline = System.nanoTime() + 1_000_000_000L
 while (gate.queueLength == 0 && System.nanoTime() < gateDeadline) Thread.sleep(1)
 check(gate.queueLength > 0) { "second receive callback bypassed one-frame backpressure" }
 try {
  val surface = Surface(); val stale = Surface()
  val begin = System.nanoTime()
  r.setResolution(1080, 2340) // repeated size reports also must not wait for decode
  r.setSurface(surface); r.clearSurface(stale)
  val desired = VideoRenderer::class.java.getDeclaredField("displaySurface")
   .apply { isAccessible = true }.get(r)
  check(desired === surface) { "stale surface clear detached replacement" }
  r.clearSurface(surface)
  if (release) r.release() else r.resetSession()
  val controlMs = (System.nanoTime() - begin) / 1_000_000
  check(controlMs < 200) { "UI controls waited for codec: ${controlMs}ms" }
  // The receive callback also leaves promptly on cancellation, even before the codec returns.
  producer.join(500); waitingProducer.join(500)
  check(!producer.isAlive && !waitingProducer.isAlive) { "native receive callback held through cancellation" }
  r.feedFrame(key(true), 61_000, true) // late input must not revive the ended session
  println("PASS blocked replay controls: release=$release UI=${controlMs}ms")
 } finally { unblock.countDown() }
 worker(r) {}
 check(field(r, "codec") == null && field(r, "startupBytes") == 0)
 check(field(r, "frameCount") == 0L)
 check(Fake.packets.none { it.codec == "c2.android.test" }) { "cancelled replay published input" }
 // release is reusable: cleanup must not erase a replacement surface or a fresh session.
 Fake.holdInput = null; Fake.inputEntered = null
 val replacement = Surface(); r.setSurface(replacement)
 r.setResolution(1080, 2340); bootstrap(r, true)
 check(Fake.attempts.last() == "hardware")
 val pipeline = field(r, "pipeline") as io.github.jqssun.airplay.renderer.VideoPipeline
 check(pipeline.display === replacement) { "old cleanup erased new display" }
 close(r)
}

fun runReplayBudgetCase() {
 Fake.reset(); Fake.outputCodecs.add("c2.android.test")
 val r = VideoRenderer(); r.setResolution(1080, 2340); bootstrap(r, true)
 repeat(40) { r.feedFrame(inter(true), (it + 5) * 1000L, true) }
 Fake.slowSoftware = true; expire(r)
 val begin = System.nanoTime(); r.feedFrame(inter(true), 50_000, true)
 val elapsedMs = (System.nanoTime() - begin) / 1_000_000
 check(elapsedMs in 2700..4000) { "replay time not bounded: ${elapsedMs}ms" }
 check(field(r, "codec") == null && field(r, "forceSoftwareStart") == true)
 check(Fake.releases.contains("c2.android.test") && field(r, "startupBytes") == 0)
 println("PASS replay time budget: ${elapsedMs}ms")
 close(r)
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
 android.os.Build.VERSION.SDK_INT = 32
 runStallCase(true); runStallCase(false)
 runOutputCase(); runResolutionCase(); runInputWaitCase(true); runInputWaitCase(false)
 runIncompleteReplayCase(); runConfigureFallbackResetCase()
 runUnavailableCase(queryFails = true)
 runUnavailableCase(hasSoftware = false)
 runUnavailableCase(supports = false)
 runUnavailableCase(overflow = true)
 runUnavailableCase(frameOverflow = true)
 runSoftwareFailureCase(fail = "configure")
 runSoftwareFailureCase(fail = "start")
 runSoftwareFailureCase(blocked = true)
 runAsyncControlsCase(release = false); runAsyncControlsCase(release = true)
 runReplayBudgetCase()
 println("PASS production AirPlay: decoder worker, nonblocking UI controls, cancellation/late-frame isolation, first-input stall recovery, bounded replay and hardware-first policy")
}
""",
}

# Service callbacks run on native threads; late callbacks must not revive a released worker.
service = (ROOT / "airplay/src/main/kotlin/io/github/jqssun/airplay/service/AirPlayService.kt").read_text(encoding="utf-8")
for flag in ("teardownRunning", "outputsReleased", "destroying"):
    assert f"@Volatile private var {flag}" in service, flag
for callback in ("onVideoData", "onVideoSize"):
    body = service.split(f"override fun {callback}(", 1)[1].split("{", 1)[1]
    assert body.lstrip().startswith("if (teardownRunning || outputsReleased || destroying) return"), callback

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
