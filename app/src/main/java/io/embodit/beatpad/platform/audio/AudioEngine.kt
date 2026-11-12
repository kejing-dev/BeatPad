package io.embodit.beatpad.platform.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import io.embodit.beatpad.platform.AppLog
import java.io.File
import java.nio.ByteOrder

/**
 * One AudioTrack output stream fed by [Mixer] on its own URGENT_AUDIO thread (audioEngine.decision: no SoundPool).
 *
 * Latency (needsCheck「混音器在头显上的真实延迟」, probe 2026-11-12 on Swan B3110 via `dumpsys media.audio_flinger`):
 * with spatial audio on, media streams go to the SPATIALIZER output (512-frame HAL buffer, ~58 ms thread write latency);
 * the primary output has a FastMixer (4 ms period, 21 ms). So this track asks for PERFORMANCE_MODE_LOW_LATENCY and
 * SPATIALIZATION_BEHAVIOR_NEVER (the pan is ours, design audioEngine.pan), writes one burst at a time and keeps two bursts
 * queued (grows by a burst on every underrun, up to 8). Every 5 s it logs `LATENCY out=… ms` measured from
 * AudioTrack.getTimestamp: frames written − frames presented at the speaker.
 */
class AudioEngine(private val context: Context) {
    val mixer = Mixer(SR)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    @Volatile var burst = 192
        private set
    @Volatile var outLatencyMs = -1f
        private set
    @Volatile var underruns = 0
        private set
    @Volatile var fastTrack = false
        private set
    // (written frames, presented frame, nanoTime) of the last good timestamp
    @Volatile private var tsFrame = -1L
    @Volatile private var tsNano = 0L

    fun start() {
        if (running) return
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it in 32..2048 } ?: 192
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .apply { if (Build.VERSION.SDK_INT >= 32) setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER) }.build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(SR).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
        val minBytes = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = runCatching {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setBufferSizeInBytes(maxOf(minBytes, burst * 8 * 4 * 2)).build()
        }.getOrElse { AppLog.e("AUDIO track failed", it); return }
        fastTrack = t.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        runCatching { t.setBufferSizeInFrames(burst * 2) }
        track = t
        running = true
        AppLog.i("AUDIO start sr=$SR burst=$burst minBuf=${minBytes / 8}f cap=${t.bufferCapacityInFrames}f size=${t.bufferSizeInFrames}f lowLatency=$fastTrack spatializer=never")
        thread = Thread({ loop(t) }, "BeatPadAudio").apply { start() }
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(burst * 2)
        val ts = AudioTimestamp()
        t.play()
        var lastLog = 0L
        var lastUnder = 0
        while (running) {
            mixer.render(buf, burst)
            val w = t.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            if (w < 0) { AppLog.w("AUDIO write error $w"); break }
            val now = SystemClock.elapsedRealtime()
            if (t.getTimestamp(ts)) {
                tsFrame = ts.framePosition; tsNano = ts.nanoTime
                val presentedNow = ts.framePosition + (System.nanoTime() - ts.nanoTime) * SR / 1e9
                outLatencyMs = ((mixer.rendered - presentedNow) * 1000.0 / SR).toFloat()
            }
            val u = t.underrunCount
            if (u > lastUnder) {
                lastUnder = u; underruns = u
                val size = t.bufferSizeInFrames
                if (size < burst * 8) runCatching { t.setBufferSizeInFrames(size + burst) }
                AppLog.w("AUDIO underrun count=$u buffer=${t.bufferSizeInFrames}f")
            }
            if (now - lastLog > 5000) {
                lastLog = now
                AppLog.i("LATENCY out=${"%.1f".format(outLatencyMs)}ms buffer=${t.bufferSizeInFrames}f burst=$burst underruns=$u lowLatency=$fastTrack")
            }
        }
        runCatching { t.pause(); t.flush() }
    }

    /**
     * The audio frame (mixer clock) the player heard at [nanoTime] (System.nanoTime): from the last output timestamp.
     * Before the first timestamp: rendered − 2 bursts.
     */
    fun playedFrameAt(nanoTime: Long): Double {
        val f = tsFrame
        return if (f < 0) (mixer.rendered - burst * 2).toDouble() else f + (nanoTime - tsNano) * SR / 1e9
    }

    fun stop() {
        running = false
        runCatching { thread?.join(300) }
        runCatching { track?.release() }
        track = null; thread = null
    }

    // ------------------------------------------------------------------ samples

    /** Decodes every asset under sounds/ into the mixer (background thread). */
    fun loadAssets(): Int {
        val names = context.assets.list("sounds").orEmpty().filter { it.endsWith(".wav") }
        val t0 = SystemClock.elapsedRealtime()
        var bytes = 0L
        for (n in names) {
            runCatching { context.assets.open("sounds/$n").use { Pcm.readWav(it, SR) } }
                .onSuccess { mixer.putSample(n.removeSuffix(".wav"), it); bytes += it.size * 4L }
                .onFailure { AppLog.e("AUDIO decode $n", it) }
        }
        AppLog.i("AUDIO loaded ${names.size} samples ${bytes / 1024} KB in ${SystemClock.elapsedRealtime() - t0} ms")
        return names.size
    }

    fun loadFile(path: String, key: String): Boolean = runCatching {
        File(path).inputStream().use { mixer.putSample(key, Pcm.readWav(it, SR)) }
    }.onFailure { AppLog.w("AUDIO file $path: $it") }.isSuccess

    /** Decodes any audio the platform can read (wav / mp3 / ogg / m4a) to mono float at 48 kHz. */
    fun decode(uri: Uri): FloatArray? = runCatching {
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        decodeWith(ex)
    }.onFailure { AppLog.w("IMPORT decode failed: $it") }.getOrNull()

    fun decode(file: File): FloatArray? = runCatching {
        val ex = MediaExtractor(); ex.setDataSource(file.absolutePath); decodeWith(ex)
    }.onFailure { AppLog.w("IMPORT decode failed: $it") }.getOrNull()

    private fun decodeWith(ex: MediaExtractor): FloatArray {
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val out = ArrayList<Float>(rate * 4)
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        val maxFrames = rate * 10
        while (!outDone && out.size < maxFrames) {
            if (!inDone) {
                val i = codec.dequeueInputBuffer(5000)
                if (i >= 0) {
                    val b = codec.getInputBuffer(i)!!
                    val n = ex.readSampleData(b, 0)
                    if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                    else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val o = codec.dequeueOutputBuffer(info, 5000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f = codec.outputFormat; rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                val shorts = b.asShortBuffer()
                val frames = info.size / 2 / ch
                for (f in 0 until frames) { var acc = 0f; for (c in 0 until ch) acc += shorts.get(f * ch + c) / 32768f; out += acc / ch }
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val mono = out.toFloatArray()
        return if (rate == SR) mono else Pcm.resample(mono, rate, SR)
    }

    companion object { const val SR = 48000 }
}
