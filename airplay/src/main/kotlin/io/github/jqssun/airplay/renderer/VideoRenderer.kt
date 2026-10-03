package io.github.jqssun.airplay.renderer

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import android.view.Surface

class VideoRenderer {

    private val lock = Object()
    private val pipeline = VideoPipeline()
    private var codec: MediaCodec? = null
    private var displaySurface: Surface? = null
    private var currentH265 = false
    private var videoWidth = 0
    private var videoHeight = 0
    private var firstFrameQueued = false
    /** Set once the current codec instance produced an output buffer. */
    private var firstOutputSeen = false
    /** When the current codec instance started, for the stalled-start watchdog. */
    private var decoderStartNs = 0L
    /** One software retry per resolution/session; a second stall must not restart forever. */
    private var softwareFallbackTried = false
    /** Latched until dimensions/codec type/session change, including later codec resets. */
    private var forceSoftwareStart = false
    private var usingSoftwareCodec = false
    private data class StartupFrame(val data: ByteArray, val ntpTimeNs: Long)
    private val startupFrames = ArrayList<StartupFrame>()
    private var startupBytes = 0
    private var startupNalFlags = 0
    private var startupCaptureStarted = false
    private var startupCaptureOverflow = false

    // stats
    @Volatile var fps = 0; private set
    @Volatile var bitrateBps = 0L; private set
    @Volatile var frameCount = 0L; private set
    @Volatile var codecName = ""; private set
    @Volatile var droppedFrames = 0L; private set
    @Volatile var framePacingJitterUs = 0L; private set

    var enforceSdr = true
    var keyAllowFrameDrop = true
    var realtimeDecoderPriority = true
    var lowLatency = true
    var operatingRateHint = false
    var scheduledOutputBufferRelease = true
    var benchmarkLog = false
    var benchmarkLogCallback: ((String) -> Unit)? = null
    private var _framesThisSec = 0
    private var _bytesThisSec = 0L
    private var _lastStatReset = 0L
    private val _frameIntervalsNs = LongArray(120)
    private var _frameIntervalIdx = 0
    private var _frameIntervalCount = 0
    private var _lastOutputFrameNs = 0L
    // anchors that map decoder PTS (us) to System.nanoTime() for scheduled rendering
    private var _ptsBaseUs = Long.MIN_VALUE
    private var _wallBaseNs = 0L

    fun setResolution(w: Int, h: Int) = synchronized(lock) {
        if (w != videoWidth || h != videoHeight) {
            _resetStartWatchdog()
            // An adaptive codec may remain alive across a size report. Re-arm its watchdog,
            // rather than leaving a running codec with a zero (disabled) start timestamp.
            if (codec != null) decoderStartNs = System.nanoTime()
            firstFrameQueued = false
        }
        videoWidth = w
        videoHeight = h
        pipeline.setVideoSize(w, h)
    }

    // doesn't restart codec; decoder renders into pipeline's own persistent surface
    fun setSurface(surface: Surface) = synchronized(lock) {
        displaySurface = surface
        pipeline.setDisplaySurface(surface)
    }

    fun clearSurface(surface: Surface) = synchronized(lock) {
        if (displaySurface !== surface) return@synchronized
        displaySurface = null
        pipeline.setDisplaySurface(null)
    }

    private fun _updateStats(size: Int) {
        val now = System.currentTimeMillis()
        if (now - _lastStatReset >= 1000) {
            fps = _framesThisSec
            bitrateBps = _bytesThisSec * 8
            framePacingJitterUs = _computeFramePacingJitterUs()
            _framesThisSec = 0
            _bytesThisSec = 0
            _lastStatReset = now
            if (benchmarkLog) _emitBenchmarkLine()
        }
        _framesThisSec++
        _bytesThisSec += size
        frameCount++
    }

    private fun _emitBenchmarkLine() {
        val msg = "fps=$fps bitrate=${bitrateBps / 1000}kbps " +
            "jitter=${framePacingJitterUs}us frames=$frameCount " +
            "dropped=$droppedFrames codec=$codecName " +
            "res=${videoWidth}x${videoHeight}"
        Log.i(BENCH_TAG, msg)
        benchmarkLogCallback?.invoke(msg)
    }

    fun feedFrame(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        synchronized(lock) {
            _updateStats(data.size)
            if (videoWidth == 0 || videoHeight == 0) return
            if (isH265 != currentH265) {
                stopCodec()
                _resetStartWatchdog()
                currentH265 = isH265
            }

            if (codec == null) {
                // a stale reference frame decodes to corruption, so wait for a keyframe to (re)start
                if (!_isKeyframe(data, isH265)) return
                stopCodec()
            }

            try {
                if (codec == null) startCodec(isH265)
                _cacheStartupFrame(data, ntpTimeNs, isH265)
                _feedToCodec(data, ntpTimeNs)
                drainOutput()
                _checkStalledStart()
            } catch (e: Exception) {
                Log.w(TAG, "Codec error, resetting", e)
                stopCodec()
            }
        }
    }

    private fun _feedToCodec(data: ByteArray, ntpTimeNs: Long): Boolean {
        val c = codec ?: return false
        // dropping a frame desyncs decoder until the next keyframe, but source would only send one on (re)connect
        val retries = if (firstFrameQueued) FEED_RETRIES else FIRST_FEED_RETRIES
        repeat(retries) {
            val idx = c.dequeueInputBuffer(FEED_WAIT_US)
            if (idx >= 0) {
                val buf = c.getInputBuffer(idx) ?: return false
                buf.clear()
                buf.put(data)
                c.queueInputBuffer(idx, 0, data.size, ntpTimeNs / 1000, 0)
                // Measure actual fed startup, not time spent waiting for the sender's first input.
                if (!firstFrameQueued) decoderStartNs = System.nanoTime()
                firstFrameQueued = true
                return true
            }
            drainOutput()
        }
        droppedFrames++
        Log.w(TAG, "Decoder input queue full; dropping frame. drops=$droppedFrames")
        return false
    }

    private fun _cacheStartupFrame(data: ByteArray, ntpTimeNs: Long, h265: Boolean) {
        if (firstOutputSeen || softwareFallbackTried || usingSoftwareCodec || startupCaptureOverflow) return
        if (!startupCaptureStarted) {
            if (!_isKeyframe(data, h265)) return
            startupCaptureStarted = true
        }
        // Preserve parameter sets and every following frame, including inputs the stalled codec
        // could not accept. Replaying only an IDR would lose subsequent reference frames.
        if (startupFrames.size >= MAX_STARTUP_FRAMES || data.size > MAX_STARTUP_BYTES - startupBytes) {
            _clearStartupFrames()
            startupCaptureOverflow = true
            return
        }
        startupFrames.add(StartupFrame(data.copyOf(), ntpTimeNs))
        startupBytes += data.size
        startupNalFlags = startupNalFlags or _nalFlags(data, h265)
    }

    private fun _clearStartupFrames() {
        startupFrames.clear()
        startupBytes = 0
        startupNalFlags = 0
        startupCaptureStarted = false
        startupCaptureOverflow = false
    }

    private fun _softwareDecoderForSize(mime: String) = try {
        _softwareDecoder(mime)?.takeIf {
            it.getCapabilitiesForType(mime).videoCapabilities
                ?.isSizeSupported(videoWidth, videoHeight) == true
        }
    } catch (e: Exception) {
        Log.w(TAG, "Software decoder capabilities unavailable", e)
        null
    }

    // SPS=1, PPS=2, VPS=4, random-access picture=8. Parameter sets can arrive separately
    // before the first picture; retain all of them before rebuilding a decoder without CSD.
    private fun _nalFlags(data: ByteArray, isH265: Boolean): Int {
        var flags = 0
        var i = 0
        while (i <= data.size - 5) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                val flag = if (isH265) {
                    when ((data[i + 4].toInt() shr 1) and 0x3F) {
                        32 -> 4
                        33 -> 1
                        34 -> 2
                        19, 20, 21 -> 8
                        else -> 0
                    }
                } else {
                    when (data[i + 4].toInt() and 0x1F) {
                        7 -> 1
                        8 -> 2
                        5 -> 8
                        else -> 0
                    }
                }
                flags = flags or flag
            }
            i++
        }
        return flags
    }

    private fun _isKeyframe(data: ByteArray, isH265: Boolean) = _nalFlags(data, isH265) and 13 != 0

    private fun startCodec(h265: Boolean) {
        pipeline.start()
        pipeline.setVideoSize(videoWidth, videoHeight)
        val s = pipeline.inputSurface ?: return
        currentH265 = h265
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC

        val format = MediaFormat.createVideoFormat(mime, videoWidth, videoHeight)
        val maxInput = maxOf(videoWidth * videoHeight * 3 / 4, 1024 * 1024)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxInput)
        if (enforceSdr) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        if (realtimeDecoderPriority) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        if (operatingRateHint && android.os.Build.VERSION.SDK_INT >= 23) {
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            format.setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, if (keyAllowFrameDrop) 1 else 0)
        }
        if (lowLatency && android.os.Build.VERSION.SDK_INT >= 30) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }

        firstFrameQueued = false
        // Only inspect software decoders when needed; some vendor codec lists throw while querying
        // capabilities, and a normal hardware stream should never depend on that query succeeding.
        // Let Android try its default decoder (normally hardware) at the requested dimensions.
        // Height alone does not establish a hardware limit; query software only after an actual
        // configure/start failure, not preemptively for portrait or >2160-high streams.
        if (forceSoftwareStart) {
            val sw = _softwareDecoderForSize(mime) ?: throw IllegalStateException("no software decoder for $mime")
            _startDecoder(MediaCodec.createByCodecName(sw.name), format, s, h265, software = true)
        } else try {
            _startDecoder(MediaCodec.createDecoderByType(mime), format, s, h265)
        } catch (e: Exception) {
            // Strict hardware decoders reject configs beyond their real limits.
            val sw = _softwareDecoderForSize(mime) ?: throw e
            forceSoftwareStart = true
            softwareFallbackTried = true
            Log.w(TAG, "Hardware decoder failed, trying software fallback", e)
            _startDecoder(MediaCodec.createByCodecName(sw.name), format, s, h265, software = true)
        }
        Log.i(TAG, "Video codec started: $mime ${videoWidth}x${videoHeight} ($codecName)")
    }

    private fun _startDecoder(c: MediaCodec, format: MediaFormat, surface: Surface, h265: Boolean,
                              software: Boolean = false) {
        try {
            c.configure(format, surface, null, 0)
            c.start()
        } catch (e: Exception) {
            try { c.release() } catch (_: Exception) {}
            throw e
        }
        codec = c
        usingSoftwareCodec = software
        codecName = (if (h265) "H.265" else "H.264") + " (${c.name})"
        firstOutputSeen = false
        decoderStartNs = System.nanoTime()
    }

    private fun stopCodec() {
        _frameIntervalIdx = 0
        _frameIntervalCount = 0
        _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE
        _wallBaseNs = 0L
        codec?.let {
            try {
                it.stop()
            } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        codec = null
        firstFrameQueued = false
        firstOutputSeen = false
        decoderStartNs = 0L
        usingSoftwareCodec = false
        _clearStartupFrames()
    }

    private fun drainOutput() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            if (idx < 0) break
            firstOutputSeen = true
            _clearStartupFrames()
            _recordOutputFrameTime()
            if (scheduledOutputBufferRelease) {
                // schedule frame at VSYNC matching its NTP presentation time
                val ptsUs = info.presentationTimeUs
                if (_ptsBaseUs == Long.MIN_VALUE) {
                    _ptsBaseUs = ptsUs
                    _wallBaseNs = System.nanoTime()
                }
                c.releaseOutputBuffer(idx, _wallBaseNs + (ptsUs - _ptsBaseUs) * 1000L)
            } else {
                c.releaseOutputBuffer(idx, true)
            }
        }
    }

    /** End a cast session but keep the EGL pipeline warm for the next connect. */
    fun resetSession() = synchronized(lock) {
        stopCodec()
        displaySurface = null
        pipeline.setDisplaySurface(null)
        firstFrameQueued = false
        _resetStartWatchdog()
        currentH265 = false
        videoWidth = 0
        videoHeight = 0
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
    }

    fun release() = synchronized(lock) {
        stopCodec()
        pipeline.release()
        displaySurface = null
        firstFrameQueued = false
        _resetStartWatchdog()
        currentH265 = false
        videoWidth = 0
        videoHeight = 0
        fps = 0; bitrateBps = 0; frameCount = 0; codecName = ""
        droppedFrames = 0; framePacingJitterUs = 0
        _framesThisSec = 0; _bytesThisSec = 0
        _frameIntervalIdx = 0; _frameIntervalCount = 0; _lastOutputFrameNs = 0L
        _ptsBaseUs = Long.MIN_VALUE; _wallBaseNs = 0L
    }

    /**
     * Some vendor decoders accept configure/start for frames beyond their real limits and then
     * never emit a buffer: the input queue fills forever and the mirror stays black. A
     * configure/start exception cannot catch that, so give the codec a bounded window to produce
     * its first frame and retry once with a software decoder when it does not.
     */
    private fun _checkStalledStart() {
        if (codec == null || !firstFrameQueued || firstOutputSeen || decoderStartNs == 0L
            || softwareFallbackTried || usingSoftwareCodec) return
        if (System.nanoTime() - decoderStartNs < OUTPUT_STALL_TIMEOUT_NS) return
        softwareFallbackTried = true
        val mime = if (currentH265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Never destroy the live decoder unless software startup can be fed a complete cached
        // sequence. Some senders will not send another keyframe until they reconnect.
        val requiredFlags = if (currentH265) 15 else 11
        if (startupFrames.isEmpty() || startupCaptureOverflow
            || startupNalFlags and requiredFlags != requiredFlags || _softwareDecoderForSize(mime) == null) {
            _clearStartupFrames()
            Log.w(TAG, "Decoder $codecName stalled; no bounded startup replay/software decoder available. Reconnect required")
            return
        }
        val replay = startupFrames.toList()
        Log.w(TAG, "Decoder $codecName stalled; retrying software with ${replay.size} cached startup packets")
        forceSoftwareStart = true
        val h265 = currentH265
        stopCodec()
        // Rebuild now, rather than waiting at feedFrame's keyframe gate. Retain the software
        // decision even if startup or a subsequent codec reset fails.
        startCodec(h265)
        for (frame in replay) {
            if (!_feedToCodec(frame.data, frame.ntpTimeNs)) {
                throw IllegalStateException("Software startup replay input queue full")
            }
            drainOutput()
        }
    }

    private fun _resetStartWatchdog() {
        firstOutputSeen = false
        decoderStartNs = 0L
        softwareFallbackTried = false
        forceSoftwareStart = false
        _clearStartupFrames()
    }

    private fun _recordOutputFrameTime() {
        val now = System.nanoTime()
        if (_lastOutputFrameNs > 0) {
            _frameIntervalsNs[_frameIntervalIdx % _frameIntervalsNs.size] = now - _lastOutputFrameNs
            _frameIntervalIdx++
            _frameIntervalCount++
        }
        _lastOutputFrameNs = now
    }

    private fun _computeFramePacingJitterUs(): Long {
        val count = _frameIntervalCount.coerceAtMost(_frameIntervalsNs.size)
        if (count < 2) return 0

        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until count) {
            val interval = _frameIntervalsNs[i].toDouble()
            sum += interval
            sumSq += interval * interval
        }
        val mean = sum / count
        val variance = (sumSq / count) - (mean * mean)
        return (kotlin.math.sqrt(variance.coerceAtLeast(0.0)) / 1000.0).toLong()
    }

    companion object {
        private const val TAG = "VideoRenderer"
        private const val BENCH_TAG = "BENCHMARK"
        private const val FEED_WAIT_US = 20_000L
        /** How long a started decoder may produce no output before the software retry. */
        private const val OUTPUT_STALL_TIMEOUT_NS = 3_000_000_000L
        private const val MAX_STARTUP_BYTES = 8 * 1024 * 1024
        private const val MAX_STARTUP_FRAMES = 256
        private const val FEED_RETRIES = 10
        private const val FIRST_FEED_RETRIES = 50

        fun supportsH265(): Boolean {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            return list.codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        }

        // smallest limits across codecs the sender may pick; unknown limits assume 1080p, which any device decodes
        fun maxSupportedResolution(h265: Boolean): Pair<Int, Int> {
            val mimes = listOfNotNull(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                MediaFormat.MIMETYPE_VIDEO_HEVC.takeIf { h265 },
            )
            return mimes
                .map { mime ->
                    _decoderCaps(mime)?.let { it.supportedWidths.upper to it.supportedHeights.upper }
                        ?: (1920 to 1080)
                }
                .reduce { (w1, h1), (w2, h2) -> minOf(w1, w2) to minOf(h1, h2) }
        }

        // caps of the decoder createDecoderByType would pick
        private fun _decoderCaps(mime: String) = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .firstOrNull { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
                }?.getCapabilitiesForType(mime)?.videoCapabilities
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get decoder capabilities", e)
            null
        }

        private fun _softwareDecoder(mime: String) =
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (if (android.os.Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
                    else info.name.lowercase().let {
                        it.startsWith("omx.google.") || it.startsWith("c2.android.") ||
                            (!it.startsWith("omx.") && !it.startsWith("c2."))
                    })
            }
    }
}
