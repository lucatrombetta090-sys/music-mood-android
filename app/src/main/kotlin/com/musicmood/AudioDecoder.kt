package com.musicmood

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * Decodifica audio → PCM float mono.
 * Campiona 5 secondi a partire dal 50% della canzone (ritornello).
 */
object AudioDecoder {

    private const val SAMPLE_SECONDS = 5L
    private const val TIMEOUT_US     = 8_000L

    fun decode(path: String): Pair<FloatArray, Int>? {
        if (!File(path).exists()) return null

        val extractor = MediaExtractor()
        try { extractor.setDataSource(path) }
        catch (_: Exception) { return null }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) { extractor.release(); return null }

        extractor.selectTrack(trackIndex)

        val mime       = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channels   = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2

        // Salta al 50% della canzone — ritornello solitamente più rappresentativo
        val totalDuration = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L
        if (totalDuration > 10_000_000L) {
            extractor.seekTo(totalDuration / 2, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        }

        val codec: MediaCodec
        try {
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (_: Exception) { extractor.release(); return null }

        val maxSamples = sampleRate * SAMPLE_SECONDS
        val raw = ArrayList<Short>((maxSamples * channels).toInt())
        val info = MediaCodec.BufferInfo()
        var eos = false

        try {
            while (!eos && raw.size < maxSamples * channels) {
                val inIdx = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val buf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(buf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                var outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                while (outIdx >= 0) {
                    val out = codec.getOutputBuffer(outIdx)!!
                    out.order(ByteOrder.LITTLE_ENDIAN)
                    val sb = out.asShortBuffer()
                    while (sb.hasRemaining() && raw.size < maxSamples * channels)
                        raw.add(sb.get())
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { eos = true; break }
                    outIdx = codec.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {}
        finally {
            try { codec.stop(); codec.release() } catch (_: Exception) {}
            extractor.release()
        }

        if (raw.isEmpty()) return null

        val step = channels
        val mono = FloatArray(raw.size / step)
        for (i in mono.indices) {
            var sum = 0f
            for (ch in 0 until step) sum += raw[i * step + ch] / 32768f
            mono[i] = sum / step
        }
        return Pair(mono, sampleRate)
    }
}
