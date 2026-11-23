package com.example.podscribe

import android.content.Context
import android.content.ContentValues
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

object Repo {
    const val DEFAULT_MODEL = "gemini-3.6-flash"
    lateinit var app: Context
    lateinit var prefs: SharedPreferences
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).writeTimeout(300, TimeUnit.SECONDS).build()
    val podcasts = MutableStateFlow<List<Podcast>>(emptyList())
    val episodes = MutableStateFlow<List<Episode>>(emptyList())
    val dl = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val persistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    @Volatile private var pendingSave: Job? = null

    private val dbFile get() = File(app.filesDir, "db.json")

    fun init(c: Context) {
        app = c.applicationContext
        prefs = app.getSharedPreferences("podscribe", 0)
        // Remove credentials saved for custom non-Gemini providers and normalize
        // older installations to the supported Cloud/On-device selector.
        prefs.edit().remove("aiProviders").remove("activeAiProviderId").commit()
        runCatching {
            val o = JSONObject(dbFile.readText())
            podcasts.value = o.getJSONArray("podcasts").objs().map {
                Podcast(it.getString("id"), it.getString("title"), it.optString("author"), it.optString("image"), it.optString("desc"))
            }
            episodes.value = o.getJSONArray("episodes").objs().map {
                val lp = it.optString("local").ifBlank { null }?.takeIf(::audioExists)
                Episode(it.getString("id"), it.getString("pid"), it.getString("title"), it.getString("url"), it.optLong("date"),
                    it.optInt("dur"), it.optString("desc"), it.optString("image"), it.optLong("pos"), it.optBoolean("played"), lp, it.optBoolean("seen", true))
            }
        }
    }

    fun save() {
        synchronized(this) {
            pendingSave?.cancel()
            pendingSave = persistenceScope.launch { delay(250); writeSnapshot() }
        }
    }

    @Synchronized
    private fun writeSnapshot() {
        val o = JSONObject()
        val pa = JSONArray()
        podcasts.value.forEach {
            pa.put(JSONObject().put("id", it.id).put("title", it.title).put("author", it.author).put("image", it.image).put("desc", it.description))
        }
        val ea = JSONArray()
        episodes.value.forEach {
            ea.put(JSONObject().put("id", it.id).put("pid", it.podcastId).put("title", it.title).put("url", it.audioUrl)
                .put("date", it.pubDate).put("dur", it.durationSec).put("desc", it.description).put("image", it.image)
                .put("pos", it.positionMs).put("played", it.played).put("local", it.localPath ?: "").put("seen", it.seen))
        }
        o.put("podcasts", pa).put("episodes", ea)
        val tmp = File(app.filesDir, "db.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(dbFile)
    }

    fun updateEpisode(id: String, f: (Episode) -> Episode) {
        episodes.value = episodes.value.map { if (it.id == id) f(it) else it }
        save()
    }

    suspend fun addFeed(url: String) = withContext(Dispatchers.IO) {
        val (p, eps) = fetch(url.trim())
        upsert(p, eps)
        p
    }

    suspend fun addFeeds(urls: List<String>) = withContext(Dispatchers.IO) {
        val distinct = urls.map(String::trim).filter(String::isNotBlank).distinct()
        val results = coroutineScope { distinct.chunked(4).flatMap { group -> group.map { url -> async { runCatching { fetch(url) } } }.awaitAll() } }
        val good = results.mapNotNull { it.getOrNull() }
        if (good.isNotEmpty()) {
            val old = episodes.value.associateBy { it.id }
            val ids = good.map { it.first.id }.toSet()
            val newEpisodes = good.flatMap { (_, eps) -> eps.map { e -> old[e.id]?.let { e.copy(positionMs=it.positionMs, played=it.played, localPath=it.localPath, seen=it.seen) } ?: e } }
            podcasts.value = (podcasts.value.filterNot { it.id in ids } + good.map { it.first }).distinctBy { it.id }
            episodes.value = (episodes.value.filterNot { it.podcastId in ids } + newEpisodes).sortedByDescending { it.pubDate }
            save()
        }
        good.size to (distinct.size - good.size)
    }

    suspend fun refreshAll() = withContext(Dispatchers.IO) {
        val snapshot = podcasts.value
        // Fetch independently, but publish one state update and write the database once.
        // Per-feed upserts were forcing a full JSON rewrite and large-list recomposition N times.
        val results = coroutineScope {
            snapshot.chunked(4).flatMap { batch -> batch.map { old -> async { runCatching { fetch(old.id) } } }.awaitAll() }
        }.mapNotNull { it.getOrNull() }
        if (results.isEmpty()) return@withContext
        val oldEpisodes = episodes.value.associateBy { it.id }
        val refreshedIds = results.map { it.first.id }.toSet()
        val freshEpisodes = results.flatMap { (_, feedEpisodes) -> feedEpisodes.map { ep ->
            oldEpisodes[ep.id]?.let { ep.copy(positionMs = it.positionMs, played = it.played, localPath = it.localPath, seen = it.seen) } ?: ep
        } }
        podcasts.value = podcasts.value.map { current -> results.find { it.first.id == current.id }?.first ?: current }
        episodes.value = (episodes.value.filterNot { it.podcastId in refreshedIds } + freshEpisodes).sortedByDescending { it.pubDate }
        save()
        prefs.edit().putLong("lastFeedRefresh", System.currentTimeMillis()).apply()
    }

    suspend fun refreshIfStale(force: Boolean = false) {
        if (podcasts.value.isEmpty()) return
        val last = prefs.getLong("lastFeedRefresh", 0L)
        if (force || System.currentTimeMillis() - last >= TimeUnit.MINUTES.toMillis(30)) refreshAll()
    }

    suspend fun importOpml(input: InputStream) = withContext(Dispatchers.IO) {
        val parser = android.util.Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val urls = LinkedHashSet<String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name.equals("outline", true)) {
                val url = parser.getAttributeValue(null, "xmlUrl") ?: parser.getAttributeValue(null, "xmlurl")
                if (!url.isNullOrBlank()) urls.add(url.trim())
            }
            parser.next()
        }
        urls.forEach { url -> runCatching { val (p, eps) = fetch(url); upsert(p, eps) } }
        urls.size
    }

    fun exportOpml(out: java.io.OutputStream) {
        val serializer = android.util.Xml.newSerializer()
        serializer.setOutput(out, "UTF-8"); serializer.startDocument("UTF-8", true)
        serializer.startTag(null, "opml").attribute(null, "version", "2.0")
        serializer.startTag(null, "body")
        podcasts.value.forEach { p -> serializer.startTag(null, "outline").attribute(null, "text", p.title).attribute(null, "title", p.title).attribute(null, "type", "rss").attribute(null, "xmlUrl", p.id).endTag(null, "outline") }
        serializer.endTag(null, "body").endTag(null, "opml"); serializer.endDocument(); serializer.flush()
    }

    private fun fetch(url: String): Pair<Podcast, List<Episode>> {
        val req = Request.Builder().url(url).header("User-Agent", "PodScribe/1.0").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return parseFeed(r.body!!.byteStream(), url)
        }
    }

    private fun upsert(p: Podcast, eps: List<Episode>) {
        val old = episodes.value.associateBy { it.id }
        val merged = eps.map { e -> old[e.id]?.let { e.copy(positionMs = it.positionMs, played = it.played, localPath = it.localPath, seen = it.seen) } ?: e }
        podcasts.value = if (podcasts.value.any { it.id == p.id }) {
            podcasts.value.map { if (it.id == p.id) p else it }
        } else podcasts.value + p
        episodes.value = episodes.value.filter { it.podcastId != p.id } + merged
        save()
    }

    fun markSeen(id: String) = updateEpisode(id) { it.copy(seen = true) }
    fun clearTranscript(id: String) {
        deleteTranscript(id)
        geminiTranscriptProfiles(id).forEach { deleteTranscript(it.transcriptId) }
        prefs.edit().remove(geminiProfilesKey(id)).apply()
        LocalModels.catalog.forEach { deleteTranscript(LocalModels.transcriptId(it.id, id)) }
        Transcriber.segs.value = Transcriber.segs.value.filterKeys { !it.endsWith(":$id") && it != id }
    }

    private fun geminiProfilesKey(episodeId: String) = "gemini-transcript-profiles-${md5(episodeId)}"
    fun geminiTranscriptProfiles(episodeId: String): List<CloudTranscriptProfile> = runCatching {
        val json = JSONArray(prefs.getString(geminiProfilesKey(episodeId), "[]") ?: "[]")
        json.objs().map { CloudTranscriptProfile(it.getString("id"), it.getString("model"), it.getString("language")) }
    }.getOrDefault(emptyList())

    fun recordGeminiTranscriptProfile(episodeId: String, profile: CloudTranscriptProfile) {
        val profiles = geminiTranscriptProfiles(episodeId).filterNot { it.transcriptId == profile.transcriptId } + profile
        prefs.edit().putString(geminiProfilesKey(episodeId), JSONArray(profiles.map {
            JSONObject().put("id", it.transcriptId).put("model", it.model).put("language", it.language)
        }).toString()).apply()
    }

    fun removePodcast(id: String) {
        val removed = episodes.value.filter { it.podcastId == id }
        podcasts.value = podcasts.value.filter { it.id != id }
        episodes.value = episodes.value.filter { it.podcastId != id }
        save()
        removed.forEach { e ->
            runCatching { e.localPath?.let(::deleteAudio) }
            runCatching { deleteTranscript(e.id) }
            geminiTranscriptProfiles(e.id).forEach { runCatching { deleteTranscript(it.transcriptId) } }
            prefs.edit().remove(geminiProfilesKey(e.id)).apply()
            LocalModels.catalog.forEach { local -> runCatching { deleteTranscript(LocalModels.transcriptId(local.id, e.id)) } }
        }
    }

    private fun parseFeed(ins: InputStream, feedUrl: String): Pair<Podcast, List<Episode>> {
        val p = Xml.newPullParser()
        p.setInput(ins, null)
        var chTitle = ""; var chAuthor = ""; var chImage = ""; var chDesc = ""
        val list = ArrayList<Episode>()
        var inItem = false
        var t = ""; var url = ""; var date = 0L; var dur = 0; var desc = ""; var img = ""; var guid = ""
        val df = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val qualifiedName = p.name
                val podcastNamespace = p.prefix == "itunes" || qualifiedName.startsWith("itunes:") ||
                    p.namespace?.contains("podcast", ignoreCase = true) == true
                // XmlPullParser exposes the local name separately from its namespace prefix.
                // Matching the qualified spelling (for example "itunes:duration") is
                // parser-dependent and caused common iTunes fields to be silently skipped.
                when (p.name.substringAfter(':')) {
                    "item" -> { inItem = true; t = ""; url = ""; date = 0; dur = 0; desc = ""; img = ""; guid = "" }
                    "title" -> { val x = p.nextText().trim(); if (inItem) t = x else if (chTitle.isEmpty()) chTitle = x }
                    "enclosure" -> if (inItem) url = p.getAttributeValue(null, "url") ?: ""
                    "image" -> if (podcastNamespace) {
                        val h = p.getAttributeValue(null, "href") ?: p.getAttributeValue("http://www.itunes.com/dtds/podcast-1.0.dtd", "href") ?: ""
                        if (inItem) img = h else if (chImage.isEmpty()) chImage = h
                    }
                    "url" -> if (!inItem && chImage.isEmpty()) chImage = p.nextText().trim()
                    "pubDate" -> if (inItem) date = runCatching { df.parse(p.nextText().trim())!!.time }.getOrDefault(0L)
                    "duration" -> if (inItem) dur = parseDur(p.nextText().trim())
                    "guid" -> if (inItem) guid = p.nextText().trim()
                    "author" -> if (podcastNamespace) {
                        val x = p.nextText().trim()
                        if (!inItem && chAuthor.isEmpty()) chAuthor = x
                    }
                    "description" -> { val x = p.nextText().trim(); if (inItem) desc = x else if (chDesc.isEmpty()) chDesc = x }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "item") {
                inItem = false
                if (url.isNotBlank()) list.add(Episode(md5(feedUrl + (guid.ifBlank { url })), feedUrl, t.ifBlank { "Untitled" }, url, date, dur, desc, img.ifBlank { chImage }))
            }
            ev = p.next()
        }
        return Pair(Podcast(feedUrl, chTitle.ifBlank { feedUrl }, chAuthor, chImage, chDesc), list.distinctBy { it.id })
    }

    private fun parseDur(s: String): Int {
        val parts = s.split(":").mapNotNull { it.trim().toDoubleOrNull() }
        return when (parts.size) { 1 -> parts[0].toInt(); 2 -> (parts[0] * 60 + parts[1]).toInt(); 3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]).toInt(); else -> 0 }
    }

    suspend fun download(ep: Episode) = withContext(Dispatchers.IO) {
        val ext = ep.audioUrl.substringBefore('?').substringAfterLast('.', "mp3").lowercase().takeIf { it in setOf("mp3", "m4a", "aac", "ogg", "wav", "mp4") } ?: "mp3"
        val mime = mimeForExtension(ext)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, audioFileName(ep, ext))
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PodScribe")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PodScribe")
                if (!dir.exists() && !dir.mkdirs()) error("Could not create Music/PodScribe")
                put(MediaStore.MediaColumns.DATA, File(dir, audioFileName(ep, ext)).absolutePath)
            }
        }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create audio file in Music/PodScribe")
        try {
            dl.value = dl.value + (ep.id to 0f)
            http.newCall(Request.Builder().url(ep.audioUrl).header("User-Agent", "PodScribe/1.0").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                val total = r.body!!.contentLength().toFloat()
                var done = 0L; var last = 0L
                r.body!!.byteStream().use { i ->
                    (app.contentResolver.openOutputStream(uri, "w") ?: error("Could not open audio file for writing")).use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            o.write(buf, 0, n); done += n
                            if (done - last > 256 * 1024 && total > 0) { last = done; dl.value = dl.value + (ep.id to done / total) }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            updateEpisode(ep.id) { it.copy(localPath = uri.toString()) }
            uri.toString()
        } catch (e: Exception) {
            app.contentResolver.delete(uri, null, null)
            throw e
        } finally {
            dl.value = dl.value - ep.id
        }
    }

    fun deleteDownload(ep: Episode) {
        ep.localPath?.let(::deleteAudio)
        updateEpisode(ep.id) { it.copy(localPath = null) }
    }

    suspend fun publishAudio(ep: Episode): String = withContext(Dispatchers.IO) {
        val oldPath = ep.localPath ?: error("Audio is not downloaded")
        if (audioUri(oldPath).scheme == "content") return@withContext oldPath
        val source = File(audioUri(oldPath).path.orEmpty())
        if (!source.isFile) error("Downloaded audio file is missing")
        val ext = source.extension.ifBlank { "mp3" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, audioFileName(ep, ext))
            put(MediaStore.MediaColumns.MIME_TYPE, mimeForExtension(ext))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/PodScribe")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "PodScribe")
                if (!dir.exists() && !dir.mkdirs()) error("Could not create Music/PodScribe")
                put(MediaStore.MediaColumns.DATA, File(dir, audioFileName(ep, ext)).absolutePath)
            }
        }
        val uri = app.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create audio file in Music/PodScribe")
        try {
            source.inputStream().use { input ->
                (app.contentResolver.openOutputStream(uri, "w") ?: error("Could not open audio file for writing")).use(input::copyTo)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
            updateEpisode(ep.id) { it.copy(localPath = uri.toString()) }
            source.delete()
            uri.toString()
        } catch (e: Exception) {
            app.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    fun audioMime(path: String): String = runCatching { app.contentResolver.getType(audioUri(path)) }
        .getOrNull() ?: mimeForExtension(path.substringAfterLast('.').substringBefore('?').lowercase())

    fun audioUri(path: String): Uri {
        val parsed = Uri.parse(path)
        return if (parsed.scheme == null) Uri.fromFile(File(path)) else parsed
    }

    fun audioExists(path: String): Boolean = runCatching {
        val uri = audioUri(path)
        if (uri.scheme == "file") File(uri.path.orEmpty()).exists()
        else app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    fun deleteAudio(path: String) {
        runCatching {
            val uri = audioUri(path)
            if (uri.scheme == "content") app.contentResolver.delete(uri, null, null)
            else File(uri.path.orEmpty()).delete()
        }
    }

    private fun audioFileName(ep: Episode, extension: String): String {
        val podcast = podcasts.value.find { it.id == ep.podcastId }?.title.orEmpty()
        val base = listOf(podcast, ep.title).filter { it.isNotBlank() }.joinToString(" - ")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_")
            .replace(Regex("\\s+"), " ").trim().take(140).ifBlank { "PodScribe audio" }
        return "$base.$extension"
    }

    private fun mimeForExtension(ext: String) = when (ext) {
        "m4a", "mp4" -> "audio/mp4"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    fun segFile(id: String) = File(app.filesDir, "tr").also { it.mkdirs() }.resolve("$id.json")
    fun loadSegs(id: String): List<Seg> = runCatching {
        JSONArray(segFile(id).readText()).objs().map { o ->
            val words = o.optJSONArray("w")?.objs()?.map { WordStamp(it.getString("t"), it.getLong("s"), it.getLong("e")) }.orEmpty()
            Seg(o.getLong("s"), o.getLong("e"), o.getString("t"), o.optString("r"), words)
        }
    }.getOrDefault(emptyList())
    fun saveSegs(id: String, l: List<Seg>) {
        segFile(id).writeText(JSONArray(l.map { seg -> JSONObject().put("s", seg.startMs).put("e", seg.endMs).put("t", seg.text).put("r", seg.tr)
            .put("w", JSONArray(seg.words.map { JSONObject().put("t", it.text).put("s", it.startMs).put("e", it.endMs) })) }).toString())
    }
    fun deleteTranscript(id: String) {
        segFile(id).delete()
        prefs.edit().remove("transcription-progress-${md5(id)}").apply()
    }

    private fun s(k: String, d: String) = prefs.getString(k, d) ?: d
    var keysText: String get() = s("keys", ""); set(v) { prefs.edit().putString("keys", v).apply() }
    var model: String
        get() = s("model", DEFAULT_MODEL).trim().let {
            if (it.isEmpty() || it == "gemini-2.5-flash" || it == "gemini-flash-latest") DEFAULT_MODEL else it
        }
        set(v) { prefs.edit().putString("model", v.trim()).apply() }
    var transcriptionEngine: String get() = s("transcriptionEngine", "cloud"); set(v) { prefs.edit().putString("transcriptionEngine", v).apply() }
    var lang: String get() = s("lang", "English"); set(v) { prefs.edit().putString("lang", v).apply() }
    var skipBack: Int get() = prefs.getInt("sb", 10); set(v) { prefs.edit().putInt("sb", v).apply() }
    var skipFwd: Int get() = prefs.getInt("sf", 15); set(v) { prefs.edit().putInt("sf", v).apply() }
    var speed: Float get() = prefs.getFloat("speed", 1f); set(v) { prefs.edit().putFloat("speed", v).apply() }
    var librarySort: String get() = s("librarySort", "added"); set(v) { prefs.edit().putString("librarySort", v).apply() }
    var libraryOrder: List<String>
        get() = runCatching {
            val a = JSONArray(s("libraryOrder", "[]"))
            (0 until a.length()).map { a.getString(it) }
        }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("libraryOrder", JSONArray(v).toString()).apply() }
    fun transcriptionProgress(id: String) = prefs.getInt("transcription-progress-${md5(id)}", 0)
    fun setTranscriptionProgress(id: String, seconds: Int) {
        prefs.edit().putInt("transcription-progress-${md5(id)}", seconds.coerceAtLeast(0)).apply()
    }
    var tsSize: Int get() = prefs.getInt("tsize", 20); set(v) { prefs.edit().putInt("tsize", v).apply() }
    var tsMode: Int get() = prefs.getInt("tmode", 2); set(v) { prefs.edit().putInt("tmode", v).apply() }
    var transcriptAutoFollow: Boolean get() = prefs.getBoolean("transcriptAutoFollow", true); set(v) { prefs.edit().putBoolean("transcriptAutoFollow", v).apply() }
    fun keys() = keysText.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
