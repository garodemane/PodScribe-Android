package com.example.podscribe

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/** Creates independently playable audio files so Gemini never has to re-process the whole episode. */
object AudioChunker {
    fun extract(source: Uri, episodeId: String, startSec: Int, endSec: Int): File {
        require(endSec > startSec) { "Invalid audio segment range" }
        val dir = File(Repo.app.cacheDir, "transcription/${md5(episodeId)}").apply { mkdirs() }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(Repo.app, source, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio track in downloaded episode")
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Unknown audio format")
            extractor.selectTrack(track)

            val output = File(dir, "${startSec}_${endSec}.${if (mime == "audio/mpeg") "mp3" else "m4a"}")
            output.delete()
            if (mime == "audio/mpeg") writeMp3Range(extractor, output, startSec, endSec)
            else muxMp4Range(extractor, format, output, startSec, endSec)
            if (output.length() == 0L) error("Could not extract audio from ${startSec}s to ${endSec}s")
            return output
        } finally {
            extractor.release()
        }
    }

    private fun seek(extractor: MediaExtractor, startSec: Int) {
        extractor.seekTo(startSec * 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** MPEG audio samples are complete MP3 frames, so concatenating the selected frames stays MP3. */
    private fun writeMp3Range(extractor: MediaExtractor, output: File, startSec: Int, endSec: Int) {
        seek(extractor, startSec)
        val startUs = startSec * 1_000_000L
        val endUs = endSec * 1_000_000L
        output.outputStream().buffered().use { stream ->
            var capacity = 256 * 1024
            var buffer = ByteBuffer.allocate(capacity)
            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time >= endUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (time >= startUs && size > 0) stream.write(buffer.array(), 0, size)
                if (size > capacity / 2) {
                    capacity = size * 2
                    buffer = ByteBuffer.allocate(capacity)
                }
                if (!extractor.advance()) break
            }
        }
    }

    /** AAC and other MP4-compatible audio tracks can be copied into a small standalone M4A file. */
    private fun muxMp4Range(extractor: MediaExtractor, format: MediaFormat, output: File, startSec: Int, endSec: Int) {
        val startUs = startSec * 1_000_000L
        val endUs = endSec * 1_000_000L
        seek(extractor, startSec)
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val outTrack = muxer.addTrack(format)
            muxer.start()
            var startedAt: Long? = null
            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = android.media.MediaCodec.BufferInfo()
            while (true) {
                val time = extractor.sampleTime
                if (time < 0 || time >= endUs) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                if (time >= startUs && size > 0) {
                    if (startedAt == null) startedAt = time
                    info.set(0, size, (time - startedAt!!).coerceAtLeast(0), extractor.sampleFlags)
                    muxer.writeSampleData(outTrack, buffer, info)
                }
                if (!extractor.advance()) break
            }
            if (startedAt == null) error("No audio samples found in this segment")
        } finally {
            runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
