package com.example.podscribe

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

object Gemini {
    private const val BASE = "https://generativelanguage.googleapis.com"
    private val JSON = "application/json".toMediaType()
    private val generationHttp = Repo.http.newBuilder().readTimeout(300, TimeUnit.SECONDS).build()
    private val generationMutex = Mutex()

    class ApiException(statusCode: Int, message: String) : ApiRequestException(statusCode, message)
    class IncompleteResponse(message: String, cause: Throwable? = null) : Exception(message, cause)

    private suspend fun awaitGenerationSlot() = generationMutex.withLock {
        while (true) {
            val now = System.currentTimeMillis()
            val saved = Repo.prefs.getString("gemini-generation-starts", "[]") ?: "[]"
            val starts = runCatching {
                val a = JSONArray(saved)
                (0 until a.length()).mapNotNull { a.optLong(it).takeIf { t -> t > 0 && t <= now && now - t < 60_000L } }
            }.getOrDefault(emptyList())
            if (starts.size < 3) {
                Repo.prefs.edit().putString("gemini-generation-starts", JSONArray((starts + now).map { it }).toString()).apply()
                return@withLock
            }
            delay((starts.minOrNull()!! + 60_000L - now + 250L).coerceAtLeast(250L))
        }
    }

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

    suspend fun upload(key: String, file: File, mime: String): String {
        val start = Request.Builder().url("$BASE/upload/v1beta/files?key=$key")
            .header("X-Goog-Upload-Protocol", "resumable").header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", mime)
            .post(JSONObject().put("file", JSONObject().put("display_name", file.name)).toString().toRequestBody(JSON)).build()
        val upUrl = Repo.http.newCall(start).execute().use {
            if (!it.isSuccessful) throw apiError("upload start", it.code, it.body?.string().orEmpty())
            it.header("x-goog-upload-url") ?: error("No upload URL")
        }
        val up = Request.Builder().url(upUrl).header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize").post(file.asRequestBody(mime.toMediaType())).build()
        val info = Repo.http.newCall(up).execute().use {
            val b = it.body?.string() ?: ""
            if (!it.isSuccessful) throw apiError("upload", it.code, b)
            JSONObject(b).getJSONObject("file")
        }
        val name = info.getString("name")
        val uri = info.getString("uri")
        var state = info.optString("state")
        var tries = 0
        while (state != "ACTIVE" && tries++ < 80) {
            delay(3000)
            val b = Repo.http.newCall(Request.Builder().url("$BASE/v1beta/$name?key=$key").build()).execute().use {
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

    suspend fun transcribe(key: String, model: String, uri: String, mime: String, lang: String, offsetSec: Int): List<Seg> {
        val id = model.trim().removePrefix("models/")
        val prompt = "Transcribe all speech in this audio clip without omitting any words. " +
            "Group nearby sentences into segments of about 15 to 25 seconds to keep the result concise, while retaining accurate timestamps. " +
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
        awaitGenerationSlot()
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
            JSONArray(txt).objs().map {
                Seg(((it.getDouble("start") + offsetSec) * 1000).toLong(), ((it.getDouble("end") + offsetSec) * 1000).toLong(), it.getString("text").trim(), it.optString("translation").trim())
            }.filter { it.text.isNotBlank() && it.endMs > it.startMs }
        } catch (e: Exception) {
            throw IncompleteResponse("Gemini returned invalid subtitle segments; splitting the audio chunk", e)
        }
    }
}

object Transcriber {
    val segs = MutableStateFlow<Map<String, List<Seg>>>(emptyMap())
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun st(id: String, s: String?) { status.value = if (s == null) status.value - id else status.value + (id to s) }
    fun setExternalStatus(id: String, text: String?) = st(id, text)

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
            st(ep.id, null)
        } catch (e: Exception) {
            st(ep.id, "Failed: ${e.message?.take(200)}")
        } finally { active.remove(ep.id) }
    }

    private data class ChunkOutcome(val from: Int, val to: Int, val file: File?, val result: Result<List<Seg>>)

    private suspend fun processChunk(
        id: String, chunk: File, mime: String, from: Int, to: Int, duration: Int,
        keys: List<String>, model: String, lang: String
    ): List<Seg> {
        var keyIndex = 0
        var uri: String? = null
        var retries = 0
        while (true) {
            try {
                if (uri == null) {
                    st(id, "Uploading ${from * 100 / duration}% audio…")
                    uri = Gemini.upload(keys[keyIndex], chunk, mime)
                }
                st(id, "Transcribing ${from * 100 / duration}% ($model)…")
                return Gemini.transcribe(keys[keyIndex], model, uri, mime, lang, from)
                    .filter { it.startMs >= from * 1000L - 500 && it.startMs < to * 1000L }
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
                if (temporary && retries < 2) {
                    retries++
                    delay(5_000L * retries)
                    continue
                }
                throw e
            } catch (e: SocketTimeoutException) {
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
        keys: List<String>, model: String, lang: String
    ): List<Seg> {
        val chunk = AudioChunker.extract(source, id, from, to)
        try {
            return try {
                processChunk(id, chunk, Gemini.mimeOf(chunk.path), from, to, duration, keys, model, lang)
            } catch (e: Gemini.IncompleteResponse) {
                if (to - from <= 60) throw java.io.IOException("Gemini could not finish the ${to - from}-second audio section: ${e.message}", e)
                val mid = from + (to - from) / 2
                processRange(id, source, from, mid, duration, keys, model, lang) +
                    processRange(id, source, mid, to, duration, keys, model, lang)
            }
        } finally { chunk.delete() }
    }

    suspend fun run(ep0: Episode) = withContext(Dispatchers.IO) {
        val id = ep0.id
        if (!active.add(id)) return@withContext
        try {
            val keys = Repo.keys()
            if (keys.isEmpty()) error("Add a Gemini API key in Settings first")
            var ep = Repo.episodes.value.first { it.id == id }
            if (ep.localPath == null) { st(id, "Downloading episode…"); Repo.download(ep); ep = Repo.episodes.value.first { it.id == id } }
            val sourceUri = Repo.audioUri(ep.localPath!!)
            val mmr = MediaMetadataRetriever()
            val dur = try {
                mmr.setDataSource(Repo.app, sourceUri)
                ((mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: ep.durationSec * 1000L) / 1000).toInt()
            } finally { mmr.release() }
            if (dur <= 0) error("Unknown duration")
            val model = Repo.model; val lang = Repo.lang
            val versionKey = "gemini-transcript-config-${md5(id)}"
            val configStamp = "v2|$model|$lang|300s"
            if (Repo.prefs.getString(versionKey, null) != configStamp) {
                // Keep existing transcript segments when transcription settings change.
                // New work resumes from the saved progress and only updates its later chunks.
                Repo.prefs.edit().putString(versionKey, configStamp).apply()
            }
            val all = ArrayList<Seg>(segs.value[id] ?: emptyList())
            val chunkSeconds = 300
            var from = Repo.transcriptionProgress(id)
            if (from == 0 && all.isNotEmpty()) {
                val lastEndSec = (all.maxOf { it.endMs } / 1000).toInt()
                from = ((lastEndSec + chunkSeconds - 1) / chunkSeconds * chunkSeconds).coerceAtMost(dur)
                Repo.setTranscriptionProgress(id, from)
            }
            from = from.coerceIn(0, dur)
            while (from < dur) {
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
                st(id, "Preparing audio ${from * 100 / dur}%…")
                val outcomes = coroutineScope {
                    batch.map { (start, end) -> async(Dispatchers.IO) {
                        try {
                            ChunkOutcome(start, end, null, Result.success(processRange(id, sourceUri, start, end, dur, keys, model, lang)))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            ChunkOutcome(start, end, null, Result.failure(e))
                        }
                    } }.awaitAll()
                }
                var contiguous = from
                var batchFailure: Throwable? = null
                outcomes.sortedBy { it.from }.forEach { outcome ->
                    val part = outcome.result.getOrElse { error ->
                        if (batchFailure == null) batchFailure = error
                        return@forEach
                    }
                    all.removeAll { it.startMs >= outcome.from * 1000L && it.startMs < outcome.to * 1000L }
                    all.addAll(part.filter { it.startMs >= outcome.from * 1000L - 500 && it.startMs < outcome.to * 1000L })
                    val snap = all.sortedBy { it.startMs }
                    Repo.saveSegs(id, snap)
                    segs.value = segs.value + (id to snap)
                    outcome.file?.delete()
                    if (outcome.from == contiguous) contiguous = outcome.to
                }
                Repo.setTranscriptionProgress(id, contiguous)
                if (batchFailure != null) throw batchFailure!!
                from = contiguous
                st(id, "Saved ${from * 100 / dur}%")
            }
            st(id, null)
        } catch (e: Exception) {
            st(id, "Failed: ${e.message?.take(200)}")
        } finally {
            active.remove(id)
        }
    }
}
