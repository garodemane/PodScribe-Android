package com.example.podscribe

import android.media.MediaCodec
import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

data class LocalAsrModel(
    val id: String,
    val title: String,
    val repository: String,
    val files: List<String>,
    val sizeLabel: String,
    val parakeet: Boolean = false,
) {
    val dir: File get() = File(Repo.app.noBackupFilesDir, "local-models/$id")
    val ready: Boolean get() = files.all { File(dir, it).isFile && File(dir, it).length() > 0 }
}

object LocalModels {
    fun transcriptId(episodeId: String) = transcriptId(selectedId, episodeId)
    fun transcriptId(modelId: String, episodeId: String) = "local-v3:$modelId:$episodeId"
    val catalog = listOf(
        LocalAsrModel("whisper-base", "Whisper Base", "csukuangfj/sherpa-onnx-whisper-base", listOf("base-encoder.int8.onnx", "base-decoder.int8.onnx", "base-tokens.txt"), "~162 MB"),
        LocalAsrModel("whisper-small", "Whisper Small", "csukuangfj/sherpa-onnx-whisper-small", listOf("small-encoder.int8.onnx", "small-decoder.int8.onnx", "small-tokens.txt"), "~375 MB"),
        LocalAsrModel("whisper-medium", "Whisper Medium", "csukuangfj/sherpa-onnx-whisper-medium", listOf("medium-encoder.int8.onnx", "medium-decoder.int8.onnx", "medium-tokens.txt"), "~1 GB"),
        LocalAsrModel("whisper-large-v3", "Whisper Large-v3", "csukuangfj/sherpa-onnx-whisper-large-v3", listOf("large-v3-encoder.int8.onnx", "large-v3-decoder.int8.onnx", "large-v3-tokens.txt"), "~1.8 GB"),
        LocalAsrModel("whisper-large-v3-turbo", "Whisper Large-v3 Turbo", "csukuangfj/sherpa-onnx-whisper-turbo", listOf("turbo-encoder.int8.onnx", "turbo-decoder.int8.onnx", "turbo-tokens.txt"), "~1.5 GB"),
        LocalAsrModel("parakeet-multilingual", "Parakeet Multilingual v3 · 25 European languages", "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), "~1.3 GB", true),
    )
    val downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloadStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    var selectedId: String
        get() = Repo.prefs.getString("localAsrModel", "whisper-small") ?: "whisper-small"
        set(value) { Repo.prefs.edit().putString("localAsrModel", value).apply() }

    suspend fun download(model: LocalAsrModel) = withContext(Dispatchers.IO) {
        model.dir.mkdirs()
        val totalBytes = model.files.sumOf { name ->
            runCatching {
                val request = Request.Builder().url("https://huggingface.co/${model.repository}/resolve/main/$name").head().build()
                Repo.http.newCall(request).execute().use { if (it.isSuccessful) it.header("Content-Length")?.toLongOrNull() ?: it.header("x-linked-size")?.toLongOrNull() ?: 0L else 0L }
            }.getOrDefault(0L)
        }
        var doneBytes = model.files.sumOf { File(model.dir, it).takeIf(File::isFile)?.length() ?: 0L }
        try {
            model.files.forEach { name ->
                val dest = File(model.dir, name)
                if (dest.isFile && dest.length() > 0) return@forEach
                val tmp = File(model.dir, "$name.part")
                val request = Request.Builder().url("https://huggingface.co/${model.repository}/resolve/main/$name?download=true").build()
                Repo.http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) error("Model download failed (HTTP ${response.code})")
                    val body = response.body ?: error("Empty model download")
                    body.byteStream().use { input -> tmp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); doneBytes += n
                            downloadProgress.value = downloadProgress.value + (model.id to if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f)
                        }
                    } }
                }
                if (!tmp.renameTo(dest)) error("Could not finish saving ${model.title}")
            }
            downloadProgress.value = downloadProgress.value - model.id
            downloadStatus.value = downloadStatus.value + (model.id to "Downloaded")
        } catch (e: Exception) {
            downloadProgress.value = downloadProgress.value - model.id
            downloadStatus.value = downloadStatus.value + (model.id to "Download failed: ${e.message?.take(120)}")
            throw e
        }
    }

    suspend fun transcribe(episode: Episode, durationHintMs: Long = 0L) = withContext(Dispatchers.IO) {
        val model = catalog.firstOrNull { it.id == selectedId } ?: error("Choose a local speech model in Settings")
        if (!model.ready) error("Download ${model.title} first in Settings")
        val audioPath = Repo.episodes.value.first { it.id == episode.id }.localPath ?: run { Repo.download(episode); Repo.episodes.value.first { it.id == episode.id }.localPath!! }
        val uri = Repo.audioUri(audioPath)
        val config = if (model.parakeet) {
            OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(File(model.dir, model.files[0]).path, File(model.dir, model.files[1]).path, File(model.dir, model.files[2]).path),
                tokens = File(model.dir, model.files[3]).path, modelType = "nemo_transducer", numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ))
        } else {
            OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(model.dir, model.files[0]).path, decoder = File(model.dir, model.files[1]).path,
                    language = "", task = "transcribe", enableTokenTimestamps = true, enableSegmentTimestamps = true,
                ), tokens = File(model.dir, model.files[2]).path, modelType = "whisper",
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            ))
        }
        val recognizer = OfflineRecognizer(config = config)
        val transcriptId = transcriptId(episode.id)
        try {
            val all = ArrayList(Repo.loadSegs(transcriptId))
            val duration = maxOf(
                metadataDurationSeconds(uri),
                extractorDurationSeconds(uri),
                episode.durationSec.toLong(),
                durationHintMs / 1000L,
            ).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (duration > episode.durationSec) Repo.updateEpisode(episode.id) { it.copy(durationSec = duration) }
            var start = Repo.transcriptionProgress(transcriptId).coerceAtLeast(0)
            // A second tap after a nominally complete run rechecks its last minute.
            // This recovers a short tail that a container's duration metadata may hide.
            if (start >= duration) start = (duration - 60).coerceAtLeast(0)
            start = start.coerceAtMost(duration)
            while (start < duration) {
                if (Transcriber.pauseAtSectionBoundary(episode.id)) return@withContext
                val end = minOf(start + 60, duration)
                val recheckingTail = Repo.transcriptionProgress(transcriptId) >= duration && start == (duration - 60).coerceAtLeast(0)
                Transcriber.setExternalStatus(episode.id, if (recheckingTail) "Checking the final minute with ${model.title}…" else "Local ${model.title}: ${start * 100 / duration}%…")
                // Keep context across minute boundaries. Whisper loses words at hard cuts,
                // so decode two seconds on either side and retain only the core interval.
                val contextSec = if (model.parakeet) 0 else 2
                val decodeStart = (start - contextSec).coerceAtLeast(0)
                val decodeEnd = (end + contextSec).coerceAtMost(duration)
                val wave = decode(uri, decodeStart, decodeEnd)
                val expectedSamples = (decodeEnd - decodeStart).toLong() * 16000L
                if (expectedSamples > 0 && wave.size * 100L < expectedSamples * 90L) {
                    throw IOException("Audio decode stopped early near ${start}s; progress was kept so you can retry this section")
                }
                val windowSamples = if (model.parakeet) wave.size.coerceAtLeast(1) else 25 * 16000
                val overlapSamples = if (model.parakeet) 0 else 3 * 16000
                val stepSamples = (windowSamples - overlapSamples).coerceAtLeast(1)
                val chunkSegments = ArrayList<Seg>()
                var audioOffset = 0
                while (audioOffset < wave.size) {
                    val audioEnd = minOf(audioOffset + windowSamples, wave.size)
                    val window = wave.copyOfRange(audioOffset, audioEnd)
                    val windowStartMs = decodeStart * 1000L + audioOffset * 1000L / 16000L
                    val windowEndMs = minOf(decodeEnd * 1000L, decodeStart * 1000L + audioEnd * 1000L / 16000L)
                    if (window.isNotEmpty()) {
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(window, 16000)
                            recognizer.decode(stream)
                            val result = recognizer.getResult(stream)
                            val words = tokenWords(result.tokens, result.timestamps, result.durations, windowStartMs, windowEndMs)
                            val coreStartMs = if (audioOffset == 0) start * 1000L
                                else windowStartMs + overlapSamples * 500L / 16000L
                            val hasNext = audioOffset + stepSamples < wave.size
                            val coreEndMs = if (hasNext) decodeStart * 1000L +
                                (audioOffset + stepSamples) * 1000L / 16000L + overlapSamples * 500L / 16000L
                                else end * 1000L
                            val keptWords = words.filter { (it.startMs + it.endMs) / 2 in coreStartMs until coreEndMs }
                            // Prefer the timestamped words when they overlap this window's core.
                            // sherpa can return valid text with timestamps shifted outside the core
                            // around overlap boundaries; never discard that recognized text.
                            val text = if (keptWords.isNotEmpty()) keptWords.joinToString(" ") { it.text }.trim()
                                else result.text.trim()
                            if (text.isNotBlank()) {
                                val segStart = keptWords.firstOrNull()?.startMs ?: coreStartMs
                                val segEnd = keptWords.lastOrNull()?.endMs ?: coreEndMs
                                chunkSegments.add(Seg(segStart, segEnd.coerceAtLeast(segStart + 1), text, "", keptWords))
                            }
                        } finally { stream.release() }
                    }
                    audioOffset += stepSamples
                }
                if (chunkSegments.isNotEmpty()) {
                    val rangeStartMs = start * 1000L
                    val rangeEndMs = end * 1000L
                    // A transcript segment may span a minute boundary. Replace only
                    // segments owned by this minute; deleting every overlapping segment
                    // was chopping the tail off the sentence from the previous minute.
                    all.removeAll { it.startMs >= rangeStartMs && it.startMs < rangeEndMs }
                    all.addAll(chunkSegments)
                    all.sortBy { it.startMs }
                }
                Repo.saveSegs(transcriptId, all)
                Transcriber.segs.value = Transcriber.segs.value + (transcriptId to all.toList())
                start = end
                Repo.setTranscriptionProgress(transcriptId, start)
            }
        } finally { recognizer.release() }
    }

    private fun metadataDurationSeconds(uri: Uri): Long = runCatching {
        val metadata = android.media.MediaMetadataRetriever()
        try {
            metadata.setDataSource(Repo.app, uri)
            metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.div(1000L) ?: 0L
        } finally { metadata.release() }
    }.getOrDefault(0L)

    private fun extractorDurationSeconds(uri: Uri): Long = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(Repo.app, uri, null)
            (0 until extractor.trackCount).mapNotNull { index ->
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") != true || !format.containsKey(MediaFormat.KEY_DURATION)) null
                else format.getLong(MediaFormat.KEY_DURATION) / 1_000_000L
            }.maxOrNull() ?: 0L
        } finally { extractor.release() }
    }.getOrDefault(0L)

    private fun tokenWords(tokens: Array<String>, times: FloatArray, durations: FloatArray, offset: Long, end: Long): List<WordStamp> {
        if (tokens.isEmpty()) return emptyList()
        val starts = tokens.indices.map { i -> offset + ((times.getOrNull(i) ?: (i.toFloat() / tokens.size * ((end - offset) / 1000f))) * 1000).toLong() }
        val result = ArrayList<WordStamp>()
        var value = StringBuilder(); var wordStart = offset; var wordEnd = offset
        fun flush() {
            val word = value.toString().trim()
            if (word.isNotEmpty() && end > offset) {
                val start = wordStart.coerceIn(offset, end - 1)
                val finish = wordEnd.coerceIn(start + 1, end)
                result.add(WordStamp(word, start, finish))
            }
            value = StringBuilder()
        }
        tokens.forEachIndexed { i, token ->
            val piece = token.replace("▁", " ").replace("Ġ", " ")
            if (piece.firstOrNull()?.isWhitespace() == true && value.isNotEmpty()) flush()
            if (value.isEmpty()) wordStart = starts[i]
            value.append(piece)
            val duration = durations.getOrNull(i)?.takeIf { it > 0f } ?: 0f
            wordEnd = if (duration > 0f) starts[i] + (duration * 1000).toLong() else starts.getOrNull(i + 1) ?: end
        }
        flush()
        return result
    }

    private fun decode(uri: Uri, fromSec: Int, toSec: Int): FloatArray {
        val extractor = MediaExtractor(); extractor.setDataSource(Repo.app, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: error("Audio track not found")
        val format = extractor.getTrackFormat(track); val mime = format.getString(MediaFormat.KEY_MIME)!!
        extractor.selectTrack(track); extractor.seekTo(fromSec * 1_000_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val decoder = MediaCodec.createDecoderByType(mime); decoder.configure(format, null, null, 0); decoder.start()
        val out = ArrayList<Float>(16000 * (toSec - fromSec)); val info = MediaCodec.BufferInfo(); var inputEnded = false; var outputEnded = false
        var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE); var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT); var emitted = 0L
        try {
            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!; buffer.clear()
                        val pts = extractor.sampleTime
                        if (pts < 0 || pts >= toSec * 1_000_000L) { decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { val size = extractor.readSampleData(buffer, 0); if (size < 0) { decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true } else { decoder.queueInputBuffer(inputIndex, 0, size, pts, 0); extractor.advance() } }
                    }
                }
                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outputIndex >= 0) {
                    val buffer = decoder.getOutputBuffer(outputIndex)!!
                    if (info.size > 0) {
                        val fmt = decoder.outputFormat; rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        buffer.position(info.offset); buffer.limit(info.offset + info.size); buffer.order(ByteOrder.LITTLE_ENDIAN)
                        val encoding = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        val bytesPerSample = when (encoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> 4
                            AudioFormat.ENCODING_PCM_16BIT -> 2
                            AudioFormat.ENCODING_PCM_8BIT -> 1
                            else -> error("Unsupported decoded PCM format: $encoding")
                        }
                        val frameCount = info.size / (bytesPerSample * channels); val source = FloatArray(frameCount)
                        for (i in 0 until frameCount) {
                            var sum = 0f
                            repeat(channels) {
                                sum += when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> buffer.float.coerceIn(-1f, 1f)
                                    AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                                    else -> ((buffer.get().toInt() and 0xff) - 128) / 128f
                                }
                            }
                            source[i] = sum / channels
                        }
                        for (i in 0 until frameCount) {
                            val absoluteUs = info.presentationTimeUs + i * 1_000_000L / rate
                            if (absoluteUs < fromSec * 1_000_000L || absoluteUs >= toSec * 1_000_000L) continue
                            val targetIndex = ((absoluteUs - fromSec * 1_000_000L) * 16000L / 1_000_000L)
                            if (targetIndex >= emitted) {
                                while (emitted < targetIndex) { out.add(0f); emitted++ }
                                out.add(source[i]); emitted++
                            }
                        }
                    }
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(outputIndex, false)
                }
            }
        } finally { runCatching { decoder.stop() }; decoder.release(); extractor.release() }
        return out.toFloatArray()
    }
}
