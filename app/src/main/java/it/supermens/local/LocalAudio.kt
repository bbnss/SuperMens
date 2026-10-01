package it.supermens.local

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder

object LocalAudio {
    private const val outputRate = 16000
    private const val maxClipSamples = outputRate * 25
    private const val overlapSamples = outputRate
    data class AudioClip(val file:File,val startMs:Long)
    fun toWavClips(context: Context, source: File): List<AudioClip> {
        require(source.isFile) { context.uiString(R.string.audio_missing) }
        val extractor = MediaExtractor()
        val clips = mutableListOf<AudioClip>()
        var codec: MediaCodec? = null
        var writer: WavWriter? = null
        try {
            extractor.setDataSource(source.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: error(context.uiString(R.string.audio_no_track))
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME) ?: error(context.uiString(R.string.audio_format_unknown)))
            codec.configure(format, null, null, 0)
            codec.start()
            var inputDone = false
            var outputDone = false
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputFrame = 0L
            var nextOutput = 0.0
            var previous = 0f
            var hasPrevious = false
            val history=ShortArray(overlapSamples)
            var historyCount=0
            var totalSamples=0L
            fun writeSample(sample: Float) {
                val value=(sample.coerceIn(-1f, 1f) * 32767).toInt().toShort()
                if (writer == null || writer!!.samples >= maxClipSamples) {
                    writer?.close()
                    val path = File.createTempFile("supermens-audio-", ".wav", context.cacheDir)
                    clips += AudioClip(path,((totalSamples-historyCount)*1000/outputRate))
                    writer = WavWriter(path)
                    if(totalSamples>0) for(offset in historyCount downTo 1) writer!!.write(history[((totalSamples-offset)%overlapSamples).toInt()])
                }
                writer!!.write(value)
                history[(totalSamples%overlapSamples).toInt()]=value
                totalSamples++
                if(historyCount<overlapSamples) historyCount++
            }
            val info = MediaCodec.BufferInfo()
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: error(context.uiString(R.string.audio_buffer_missing))
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, count, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outputFormat = codec.outputFormat
                        channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (index >= 0) {
                        if (info.size > 0) {
                            val buffer = (codec.getOutputBuffer(index) ?: error(context.uiString(R.string.audio_output_missing))).duplicate().order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            require(encoding == AudioFormat.ENCODING_PCM_FLOAT || encoding == AudioFormat.ENCODING_PCM_16BIT) { context.uiString(R.string.audio_pcm_unsupported) }
                            while (buffer.remaining() >= channels * bytesPerSample) {
                                var sum = 0f
                                repeat(channels) { sum += if (bytesPerSample == 4) buffer.float else buffer.short / 32768f }
                                val current = sum / channels
                                if (!hasPrevious) { previous = current; hasPrevious = true }
                                while (nextOutput <= inputFrame.toDouble()) {
                                    val fraction = (nextOutput - (inputFrame - 1)).coerceIn(0.0, 1.0).toFloat()
                                    writeSample(previous + (current - previous) * fraction)
                                    nextOutput += sampleRate.toDouble() / outputRate
                                }
                                previous = current
                                inputFrame++
                            }
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
            writer?.close(); writer = null
            require(clips.isNotEmpty()) { context.uiString(R.string.audio_empty) }
            return clips
        } catch (error: Throwable) {
            writer?.close()
            clips.forEach { it.file.delete() }
            throw error
        } finally {
            runCatching { codec?.stop() }
            codec?.release()
            extractor.release()
        }
    }
    private class WavWriter(private val file: File) {
        private val output = RandomAccessFile(file, "rw")
        var samples = 0
            private set
        init { output.write(ByteArray(44)) }
        fun write(value: Short) {
            output.write(value.toInt() and 0xff)
            output.write((value.toInt() ushr 8) and 0xff)
            samples++
        }
        fun close() {
            val dataBytes = samples * 2
            output.seek(0)
            fun ascii(value: String) = output.write(value.toByteArray(Charsets.US_ASCII))
            fun intLE(value: Int) { repeat(4) { output.write((value ushr (it * 8)) and 0xff) } }
            fun shortLE(value: Int) { output.write(value and 0xff); output.write((value ushr 8) and 0xff) }
            ascii("RIFF"); intLE(36 + dataBytes); ascii("WAVEfmt "); intLE(16); shortLE(1); shortLE(1)
            intLE(outputRate); intLE(outputRate * 2); shortLE(2); shortLE(16); ascii("data"); intLE(dataBytes)
            output.close()
        }
    }
}
