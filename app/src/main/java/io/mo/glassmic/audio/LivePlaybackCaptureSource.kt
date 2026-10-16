package io.mo.glassmic.audio

import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import io.mo.glassmic.core.model.SourceType
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental Android playback-capture source. The app must obtain explicit
 * MediaProjection consent and pass the resulting MediaProjection instance.
 * Captures only playback streams Android allows to be captured.
 */
class LivePlaybackCaptureSource(
    private val projection: MediaProjection
) : AudioSourceProvider {
    override val type: SourceType = SourceType.TTS

    private val running = AtomicBoolean(false)
    private val chunks = ArrayBlockingQueue<ByteArray>(12)
    @Volatile private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var pending: ByteArray? = null
    private var pendingOffset = 0

    fun start(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !running.compareAndSet(false, true)) return false
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val config = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build()
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                running.set(false)
                return false
            }
            recorder = record
            record.startRecording()
            captureThread = Thread({
                val buffer = ByteArray(4096)
                while (running.get()) {
                    val count = try { record.read(buffer, 0, buffer.size) } catch (_: Throwable) { -1 }
                    if (count > 0) {
                        val chunk = buffer.copyOf(count - (count % 4))
                        if (chunk.isNotEmpty() && !chunks.offer(chunk)) { chunks.poll(); chunks.offer(chunk) }
                    } else if (count < 0) break
                }
            }, "GlassMic-LivePlaybackCapture").apply { isDaemon = true; start() }
            true
        } catch (_: Throwable) {
            running.set(false)
            recorder?.release()
            recorder = null
            false
        }
    }

    override suspend fun read(out: ByteBuffer, sampleRate: Int, channels: Int): Int {
        var chunk = pending
        if (chunk == null || pendingOffset >= chunk.size) {
            chunk = chunks.poll() ?: return 0
            pending = chunk
            pendingOffset = 0
        }
        val availableSamples = (chunk.size - pendingOffset) / 4
        val takeSamples = minOf(availableSamples, out.remaining() / 2)
        repeat(takeSamples) {
            val i = pendingOffset
            val left = ((chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
            val right = ((chunk[i + 2].toInt() and 0xff) or (chunk[i + 3].toInt() shl 8)).toShort().toInt()
            val mono = ((left + right) / 2).toShort().toInt()
            out.put((mono and 0xff).toByte())
            out.put(((mono shr 8) and 0xff).toByte())
            pendingOffset += 4
        }
        if (pendingOffset >= chunk.size) { pending = null; pendingOffset = 0 }
        return takeSamples * 2
    }

    override fun release() {
        running.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        captureThread?.join(250)
        captureThread = null
        chunks.clear()
        pending = null
        pendingOffset = 0
        runCatching { projection.stop() }
    }

    companion object { const val SAMPLE_RATE = 48_000 }
}
