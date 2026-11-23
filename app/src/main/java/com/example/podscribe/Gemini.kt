package com.example.podscribe

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com"
    private val JSON = "application/json".toMediaType()
    private val generationHttp = Repo.http.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    private val uploadHttp = Repo.http.newBuilder()
        .callTimeout(150, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    class ApiException(statusCode: Int, message: String) : ApiRequestException(statusCode, message)
    class IncompleteResponse(message: String, cause: Throwable? = null) : Exception(message, cause)
    fun transcriptId(model: String, lang: String, episodeId: String) = "gemini-v1:${md5("$model|$lang")}:$episodeId"

    private fun apiError(operation: String, code: Int, body: String) = ApiException(
        code, "Gemini $operation HTTP $code: ${body.take(240)}"
    )

    fun mimeOf(path: String) = when (path.substringAfterLast('.').lowercase()) {
        "m4a", "mp4" -> "audio/mp4"; "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; "wav" -> "audio/wav"; else -> "audio/mpeg"
    }

    suspend fun listModels(key: String): List<String> = withContext(Dispatchers.IO) {
        val out = ArrayList<String>()
        var token = ""
        do {
            val url = "$BASE/v1beta/models?pageSize=200&key=$key" + if (token.isNotEmpty()) "&pageToken=$token" else ""
            val body = Repo.http.newCall(Request.Builder().url(url).build()).execute().use {
                val b = it.body?.string() ?: ""
                if (!it.isSuccessful) error("HTTP ${it.code}: ${b.take(200)}")
                JSONObject(b)
            }
            body.optJSONArray("models")?.objs()?.forEach { m ->
                val methods = m.optJSONArray("supportedGenerationMethods")
                val ok = methods != null && (0 until methods.length()).any { methods.getString(it) == "generateContent" }
                if (ok) out.add(m.getString("name").removePrefix("models/"))
            }
            token = body.optString("nextPageToken")
        } while (token.isNotEmpty())
        out.sorted()
    }

    suspend fun upload(key: String, file: File, mime: String, statusId: String): String {
        val start = Request.Builder().url("$BASE/upload/v1beta/files?key=$key")
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", file.name)).toString().toRequestBody(JSON)).build()
        val upUrl = uploadHttp.newCall(start).execute().use {
            if (!it.isSuccessful) throw apiError("upload start", it.code, it.body?.string().orEmpty())
            it.header("x-goog-upload-url") ?: error("No upload URL")
        }
        val up = Request.Builder().url(upUrl).header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize").post(file.asRequestBody(mime.toMediaType())).build()
        val info = uploadHttp.newCall(up).execute().use {
            val b = it.body?.string() ?: ""
            if (!it.isSuccessful) throw apiError("upload", it.code, b)
            JSONObject(b).getJSONObject("file")
        }
        val name = info.getString("name")
        val uri = info.getString("uri")
        var state = info.optString("state")
        var tries = 0
        while (state != "ACTIVE" && tries++ < 80) {
            if (tries % 5 == 1) Transcriber.setExternalStatus(statusId, "Gemini is processing uploaded audio…")
            delay(3000)
            val b = uploadHttp.newCall(Request.Builder().url("$BASE/v1beta/$name?key=$key").build()).execute().use {
                val body = it.body?.string().orEmpty()
                if (!it.isSuccessful) throw apiError("file status", it.code, body)
                body
            }
            state = JSONObject(b).optString("state")
            if (state == "FAILED") error("File processing failed")
        }
        if (state != "ACTIVE") error("File processing timeout")
        return uri
    }

    suspend fun transcribe(key: String, model: String, uri: String, mime: String, lang: String, statusId: String): List<Seg> {
        val id = model.trim().removePrefix("models/")
        val prompt = "Transcribe every spoken word in this audio clip verbatim, including speech at the beginning and end. Do not summarize or skip any sentence. " +
            "Group consecutive speech into segments of about 15 to 25 seconds, with accurate timestamps, to keep the response concise. " +
            "For each segment give: start and end in seconds from the beginning of THIS CLIP, " +
            "the exact transcript in the original language, and a natural, accurate translation into $lang. " +
            "If the speech is already in $lang, repeat the same text as the translation. Skip music and silence. Return JSON only."
        fun prop(t: String) = JSONObject().put("type", t)
        val schema = JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("start", prop("NUMBER")).put("end", prop("NUMBER")).put("text", prop("STRING")).put("translation", prop("STRING")))
            .put("required", JSONArray(listOf("start", "end", "text", "translation"))))
        val generationConfig = JSONObject().put("responseMimeType", "application/json")
            .put("responseSchema", schema).put("temperature", 0)
        if (id.startsWith("gemini-3")) {
            // Gemini 3.6 Flash supports `minimal`; it has lower latency for
            // transcription than `low`, while newer 3.x variants only accept low.
            val thinkingLevel = if (id.startsWith("gemini-3.6") || id.startsWith("gemini-3.5") || id == "gemini-3-flash-preview") "minimal" else "low"
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("fileData", JSONObject().put("mimeType", mime).put("fileUri", uri)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", generationConfig)
        val req = Request.Builder().url("$BASE/v1beta/models/$id:generateContent?key=$key").post(body.toString().toRequestBody(JSON)).build()
        Transcriber.setExternalStatus(statusId, "Waiting for Gemini response…")
        val payload = generationHttp.newCall(req).execute().use {
            val b = it.body?.string() ?: ""
            if (!it.isSuccessful) throw apiError("generateContent", it.code, b)
            JSONObject(b)
        }
        val candidate = payload.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw IncompleteResponse("Gemini returned no transcription candidate; splitting the audio chunk")
        val finishReason = candidate.optString("finishReason")
        if (finishReason == "MAX_TOKENS") throw IncompleteResponse("Gemini cut off the response; splitting the audio chunk")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IncompleteResponse("Gemini response did not include transcription text; splitting the audio chunk")
        val txt = (0 until parts.length()).mapNotNull { parts.optJSONObject(it) }
            .filterNot { it.optBoolean("thought", false) }
            .mapNotNull { it.optString("text").takeIf(String::isNotBlank) }
            .firstOrNull { runCatching { JSONArray(it); true }.getOrDefault(false) }
            ?: throw IncompleteResponse("Gemini returned incomplete transcript JSON; splitting the audio chunk")
        return try {
            val rows = JSONArray(txt).objs()
            val parsed = rows.map {
                Seg((it.getDouble("start") * 1000).toLong(), (it.getDouble("end") * 1000).toLong(), it.getString("text").trim(), it.optString("translation").trim())
            }
            if (parsed.any { it.text.isBlank() || it.endMs <= it.startMs })
                throw IncompleteResponse("Gemini returned invalid transcript segments; splitting the audio chunk")
            parsed
        } catch (e: Exception) {
            throw IncompleteResponse("Gemini returned invalid subtitle segments; splitting the audio chunk", e)
        }
    }
}

object Transcriber {
    val segs = MutableStateFlow<Map<String, List<Seg>>>(emptyMap())
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pauseRequested = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun st(id: String, s: String?) {
        val visible = if (s != null && id in pauseRequested && s != "Paused" && !s.startsWith("Failed"))
            "Pausing after the current audio section…" else s
        status.value = if (visible == null) status.value - id else status.value + (id to visible)
    }
    fun setExternalStatus(id: String, text: String?) = st(id, text)
    fun requestPause(id: String) {
        if (id in active) {
            pauseRequested.add(id)
            st(id, "Pausing after the current audio section…")
        }
    }
    fun pauseAtSectionBoundary(id: String): Boolean {
        if (!pauseRequested.remove(id)) return false
        st(id, "Paused")
        return true
    }
    fun isPaused(id: String) = status.value[id] == "Paused"

    fun load(id: String) { if (id !in segs.value) segs.value = segs.value + (id to Repo.loadSegs(id)) }
    fun forget(ids: Collection<String>) {
        fun keep(key: String) = key !in ids && ids.none { key.endsWith(":$it") }
        segs.value = segs.value.filterKeys(::keep)
        status.value = status.value.filterKeys(::keep)
    }

    suspend fun runLocal(ep: Episode, durationHintMs: Long = 0L) = withContext(Dispatchers.IO) {
        if (!active.add(ep.id)) return@withContext
        try {
            LocalModels.transcribe(ep, durationHintMs)
            if (!isPaused(ep.id)) st(ep.id, null)
        } catch (e: CancellationException) {
            st(ep.id, "Paused")
            throw e
        } catch (e: Exception) {
            st(ep.id, "Failed: ${e.message?.take(200)}")
        } finally { active.remove(ep.id); pauseRequested.remove(ep.id) }
    }

    private data class ChunkOutcome(val from: Int, val to: Int, val file: File?, val result: Result<List<Seg>>)
    private class PauseRequestedException : Exception()

    private fun isMostlyCovered(old: Seg, fresh: List<Seg>): Boolean {
        val start = old.startMs
        val end = old.endMs
        if (end <= start) return false
        var covered = 0L
        var cursor = start
        fresh.asSequence().map { it.startMs.coerceAtLeast(start) to it.endMs.coerceAtMost(end) }
            .filter { it.second > it.first }.sortedBy { it.first }.forEach { (a, b) ->
                if (b > cursor) { covered += b - maxOf(a, cursor); cursor = maxOf(cursor, b) }
            }
        return covered.toFloat() / (end - start) >= 0.6f
    }

    private suspend fun processChunk(
        id: String, chunk: File, mime: String, clipFrom: Int, clipTo: Int, from: Int, to: Int, duration: Int,
        keys: List<String>, model: String, lang: String
    ): List<Seg> {
        var keyIndex = 0
        var uri: String? = null
        var retries = 0
        while (true) {
            try {
                if (uri == null) {
                    st(id, "Uploading ${from * 100 / duration}% audio…")
                    uri = Gemini.upload(keys[keyIndex], chunk, mime, id)
                }
                st(id, "Sending audio to Gemini (${from * 100 / duration}%)…")
                val rawSegments = Gemini.transcribe(keys[keyIndex], model, uri, mime, lang, id)
                val coreStart = from * 1000L
                val coreEnd = to * 1000L
                val clipStart = clipFrom * 1000L
                val clipEnd = clipTo * 1000L
                fun overlap(segments: List<Seg>, start: Long, end: Long) = segments.sumOf {
                    (minOf(it.endMs, end) - maxOf(it.startMs, start)).coerceAtLeast(0L)
                }
                val relative = rawSegments.map { it.copy(startMs = it.startMs + clipStart, endMs = it.endMs + clipStart) }
                val relativeCore = overlap(relative, coreStart, coreEnd)
                val absoluteCore = overlap(rawSegments, coreStart, coreEnd)
                val normalized = if (absoluteCore > relativeCore) rawSegments else relative
                val inClip = normalized.filter { it.endMs > clipStart && it.startMs < clipEnd }
                val owned = inClip.filter { (it.startMs + it.endMs) / 2 in coreStart until coreEnd }
                if (owned.isNotEmpty()) return owned
                // A model may return timestamps for speech heard in the overlap padding.
                // Such rows belong to a neighboring section; leave the core empty instead
                // of treating a valid response as a fatal timestamp error.
                val touching = inClip.filter { it.endMs > coreStart && it.startMs < coreEnd }
                return touching.map { segment ->
                    val start = segment.startMs.coerceIn(coreStart, coreEnd - 1)
                    segment.copy(startMs = start, endMs = segment.endMs.coerceIn(start + 1, coreEnd))
                }
            } catch (e: Gemini.ApiException) {
                val keySpecific = e.statusCode == 401 || e.statusCode == 403 || e.statusCode == 429
                if (keySpecific && keyIndex + 1 < keys.size) {
                    keyIndex++
                    uri = null
                    retries = 0
                    delay(2000)
                    continue
                }
                val temporary = e.statusCode == 429 || e.statusCode in 500..599
                // HTTP 503 is Gemini's transient capacity error. Keep the uploaded
                // audio URI and retry with backoff instead of ending the whole job
                // after the two short retries used for other transient errors.
                val maxRetries = if (e.statusCode == 503) 6 else 2
                if (temporary && retries < maxRetries) {
                    retries++
                    val waitMs = if (e.statusCode == 503)
                        (5_000L * (1L shl (retries - 1))).coerceAtMost(60_000L)
                    else 5_000L * retries
                    st(id, "Gemini is busy (HTTP ${e.statusCode}); retry $retries/$maxRetries in ${waitMs / 1000}s…")
                    delay(waitMs)
                    continue
                }
                throw e
        } catch (e: java.io.InterruptedIOException) {
                throw Gemini.IncompleteResponse("Gemini timed out on this audio chunk; splitting it", e)
            } catch (e: java.io.IOException) {
                if (retries >= 1) throw e
                retries++
                delay(5_000L * retries)
            }
        }
    }

    private suspend fun processRange(
        id: String, source: android.net.Uri, from: Int, to: Int, duration: Int,
        keys: List<String>, model: String, lang: String, recoverGaps: Boolean = true
    ): List<Seg> {
        if (id in pauseRequested) throw PauseRequestedException()
        val clipFrom = (from - 12).coerceAtLeast(0)
        val clipTo = (to + 12).coerceAtMost(duration)
        val chunk = AudioChunker.extract(source, id, clipFrom, clipTo)
        try {
            return try {
                val part = processChunk(id, chunk, Gemini.mimeOf(chunk.path), clipFrom, clipTo, from, to, duration, keys, model, lang)
                // Gemini sometimes returns a successful but empty response for a long
                // audio range. Retry that range as smaller pieces so one empty response
                // cannot silently skip an entire minute.
                if (part.isEmpty() && to - from > 20) {
                    val mid = from + (to - from) / 2
                    val left = processRange(id, source, from, mid, duration, keys, model, lang, recoverGaps = false)
                    if (id in pauseRequested) throw PauseRequestedException()
                    return left + processRange(id, source, mid, to, duration, keys, model, lang, recoverGaps = false)
                }
                if (!recoverGaps || to - from <= 20) return part
                val gaps = ArrayList<Pair<Int, Int>>()
                var cursor = from * 1000L
                part.sortedBy { it.startMs }.forEach { seg ->
                    val start = seg.startMs.coerceIn(from * 1000L, to * 1000L)
                    val end = seg.endMs.coerceIn(from * 1000L, to * 1000L)
                    if (start - cursor >= 15_000L) gaps.add((cursor / 1000L).toInt() to (start / 1000L).toInt())
                    cursor = maxOf(cursor, end)
                }
                if (to * 1000L - cursor >= 15_000L) gaps.add((cursor / 1000L).toInt() to to)
                if (gaps.isEmpty()) part else {
                    // One follow-up per core keeps gap recovery bounded. Reprocessing
                    // every quiet interval multiplied Gemini calls and looked like a hang.
                    val largestGap = gaps.filter { it.second - it.first >= 12 }
                        .maxByOrNull { it.second - it.first }
                    if (largestGap == null) return part
                    val recovered = ArrayList<Seg>()
                    listOf(largestGap).forEach { (gapStart, gapEnd) ->
                        if (id in pauseRequested) throw PauseRequestedException()
                        recovered.addAll(processRange(id, source, gapStart, gapEnd, duration, keys, model, lang, recoverGaps = false))
                    }
                    (part + recovered).distinctBy { "${it.startMs}:${it.endMs}:${it.text}" }.sortedBy { it.startMs }
                }
            } catch (e: Gemini.IncompleteResponse) {
                if (to - from <= 20) throw java.io.IOException("Gemini could not finish the ${to - from}-second audio section: ${e.message}", e)
                val mid = from + (to - from) / 2
                val left = processRange(id, source, from, mid, duration, keys, model, lang)
                if (id in pauseRequested) throw PauseRequestedException()
                left + processRange(id, source, mid, to, duration, keys, model, lang)
            }
        } finally { chunk.delete() }
    }

    suspend fun run(ep0: Episode) = withContext(Dispatchers.IO) {
        val episodeId = ep0.id
        val id = Gemini.transcriptId(Repo.model, Repo.lang, episodeId)
        if (!active.add(episodeId)) return@withContext
        try {
            val keys = Repo.keys()
            if (keys.isEmpty()) error("Add a Gemini API key in Settings first")
            var ep = Repo.episodes.value.first { it.id == episodeId }
            if (ep.localPath == null) { st(episodeId, "Downloading episode…"); Repo.download(ep); ep = Repo.episodes.value.first { it.id == episodeId } }
            val sourceUri = Repo.audioUri(ep.localPath!!)
            val mmr = MediaMetadataRetriever()
            val dur = try {
                mmr.setDataSource(Repo.app, sourceUri)
                (((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: ep.durationSec * 1000L) + 999L) / 1000L).toInt()
            } finally { mmr.release() }
            if (dur <= 0) error("Unknown duration")
            val model = Repo.model; val lang = Repo.lang
            Repo.recordGeminiTranscriptProfile(episodeId, CloudTranscriptProfile(id, model, lang))
            val all = ArrayList<Seg>(segs.value[id] ?: Repo.loadSegs(id))
            val legacyConfig = Repo.prefs.getString("gemini-transcript-config-${md5(episodeId)}", null).orEmpty()
            val legacySegments = if (all.isEmpty() && legacyConfig.startsWith("v3|$model|$lang|")) Repo.loadSegs(episodeId) else emptyList()
            if (legacySegments.isNotEmpty()) {
                all.addAll(legacySegments)
                Repo.saveSegs(id, all)
                segs.value = segs.value + (id to all.toList())
            }
            val versionKey = "gemini-transcript-config-${md5(id)}"
            val configStamp = "v7|$model|$lang|1200s+12s"
            val configChanged = Repo.prefs.getString(versionKey, null) != configStamp
            if (configChanged) {
                // Replay saved audio once after transcript handling changes so old
                // completed ranges can recover words lost by earlier responses.
                Repo.setTranscriptionProgress(id, 0)
                Repo.prefs.edit().putString(versionKey, configStamp).apply()
            }
            val chunkSeconds = 1200
            var from = Repo.transcriptionProgress(id)
            if (!configChanged && from == 0 && all.isNotEmpty()) {
                val lastEndSec = (all.maxOf { it.endMs } / 1000).toInt()
                from = ((lastEndSec + chunkSeconds - 1) / chunkSeconds * chunkSeconds).coerceAtMost(dur)
                Repo.setTranscriptionProgress(id, from)
            }
            from = from.coerceIn(0, dur)
            if (from >= dur && all.isEmpty()) {
                // A prior run may have advanced progress despite Gemini returning no
                // segments. Allow the user to retry instead of presenting a silent no-op.
                from = 0
                Repo.setTranscriptionProgress(id, 0)
            }
            while (from < dur) {
                if (pauseAtSectionBoundary(episodeId)) return@withContext
                // Keep Gemini inference sequential: this model returns 503 under
                // concurrent audio jobs, even when the RPM ceiling is respected.
                val batch = ArrayList<Pair<Int, Int>>(1)
                var next = from
                repeat(1) {
                    if (next < dur) {
                        val end = minOf(next + chunkSeconds, dur)
                        batch.add(next to end)
                        next = end
                    }
                }
                st(episodeId, "Preparing audio ${from * 100 / dur}%…")
                val outcomes = coroutineScope {
                    batch.map { (start, end) -> async(Dispatchers.IO) {
                        try {
                            ChunkOutcome(start, end, null, Result.success(processRange(episodeId, sourceUri, start, end, dur, keys, model, lang)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChunkOutcome(start, end, null, Result.failure(e))
                        }
                    } }.awaitAll()
                }
                if (outcomes.any { it.result.exceptionOrNull() is PauseRequestedException }) {
                    pauseAtSectionBoundary(episodeId)
                    return@withContext
                }
                var contiguous = from
                var batchFailure: Throwable? = null
                outcomes.sortedBy { it.from }.forEach { outcome ->
                    val part = outcome.result.getOrElse { error ->
                        if (batchFailure == null) batchFailure = error
                        return@forEach
                    }
                    if (part.isNotEmpty()) {
                        val coreStart = outcome.from * 1000L
                        val coreEnd = outcome.to * 1000L
                        val old = all.filter { (it.startMs + it.endMs) / 2 in coreStart until coreEnd }
                        val keep = old.filterNot { isMostlyCovered(it, part) }
                        all.removeAll { (it.startMs + it.endMs) / 2 in coreStart until coreEnd }
                        all.addAll(keep)
                        all.addAll(part)
                    }
                    val snap = all.sortedBy { it.startMs }
                    Repo.saveSegs(id, snap)
                    segs.value = segs.value + (id to snap)
                    outcome.file?.delete()
                    if (outcome.from == contiguous) contiguous = outcome.to
                }
                Repo.setTranscriptionProgress(id, contiguous)
                if (batchFailure != null) throw batchFailure!!
                from = contiguous
                st(episodeId, "Saved ${from * 100 / dur}%")
            }
            if (!isPaused(episodeId)) st(episodeId, null)
        } catch (e: PauseRequestedException) {
            pauseRequested.remove(episodeId)
            st(episodeId, "Paused")
        } catch (e: CancellationException) {
            st(episodeId, "Paused")
            throw e
        } catch (e: Exception) {
            st(episodeId, "Failed: ${e.message?.take(200)}")
        } finally {
            active.remove(episodeId)
            pauseRequested.remove(episodeId)
        }
    }
}
