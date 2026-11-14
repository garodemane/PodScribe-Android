package com.example.podscribe

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppVM(app: Application) : AndroidViewModel(app) {
    var controller by mutableStateOf<MediaController?>(null)
    var current by mutableStateOf<Episode?>(null)
    var playing by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(normalizeSpeed(Repo.speed))
    var skipBack by mutableIntStateOf(Repo.skipBack)
    var skipFwd by mutableIntStateOf(Repo.skipFwd)
    var message by mutableStateOf<String?>(null)

    init {
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        f.addListener({
            val c = f.get(); controller = c
            c.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { syncCurrent() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) current?.let { Repo.updateEpisode(it.id) { e -> e.copy(played = true, positionMs = 0) } }
                }
            })
            syncCurrent()
        }, ContextCompat.getMainExecutor(app))

        viewModelScope.launch {
            var tick = 0
            while (true) {
                delay(250)
                controller?.let {
                    pos = it.currentPosition; dur = it.duration.coerceAtLeast(0); playing = it.isPlaying
                    if (it.isPlaying && ++tick % 20 == 0) current?.let { e ->
                        if (Repo.episodes.value.any { x -> x.id == e.id }) Repo.updateEpisode(e.id) { x -> x.copy(positionMs = pos) }
                    }
                }
            }
        }
    }

    private fun syncCurrent() {
        val id = controller?.currentMediaItem?.mediaId
        current = Repo.episodes.value.find { it.id == id }
        current?.let { Transcriber.load(it.id) }
    }

    private fun item(e: Episode) = MediaItem.Builder().setMediaId(e.id)
        .setUri(e.localPath?.takeIf(Repo::audioExists)?.let(Repo::audioUri) ?: Uri.parse(e.audioUrl))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(e.title)
            .setArtist(Repo.podcasts.value.find { it.id == e.podcastId }?.title)
            .setArtworkUri(e.image.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }).build()).build()

    fun play(ep: Episode, list: List<Episode>) {
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId == ep.id) { c.play(); return }
        val idx = list.indexOfFirst { it.id == ep.id }.coerceAtLeast(0)
        val start = if (ep.durationSec > 0 && ep.positionMs > ep.durationSec * 1000L - 5000) 0L else ep.positionMs
        c.setMediaItems(list.map(::item), idx, start)
        c.prepare(); c.setPlaybackSpeed(speed); c.play()
        current = ep; Transcriber.load(ep.id)
    }

    fun toggle() { controller?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun seekTo(ms: Long) { controller?.seekTo(ms); pos = ms }
    fun seekBy(ms: Long) { controller?.let { seekTo((it.currentPosition + ms).coerceIn(0, it.duration.coerceAtLeast(0))) } }
    fun prev() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    fun next() { controller?.seekToNextMediaItem() }
    fun restart() { seekTo(0); controller?.play() }
    fun setSpeedTo(s: Float) {
        speed = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).minBy { kotlin.math.abs(it - s) }
        Repo.speed = speed; controller?.setPlaybackSpeed(speed)
    }
    fun setBack(s: Int) { skipBack = s; Repo.skipBack = s }
    fun setFwd(s: Int) { skipFwd = s; Repo.skipFwd = s }

    fun addFeed(url: String) = viewModelScope.launch {
        message = try { val p = Repo.addFeed(url); "Added ${p.title}" } catch (e: Exception) { "Could not add feed: ${e.message}" }
    }
    fun addFeeds(urls: List<String>) = viewModelScope.launch {
        message = runCatching { val (added, failed) = Repo.addFeeds(urls); "Added $added feeds${if (failed > 0) "; $failed failed" else ""}" }
            .getOrElse { "Could not add feeds: ${it.message?.take(120)}" }
    }
    fun refresh() = viewModelScope.launch { Repo.refreshAll(); message = "Feeds refreshed" }
    fun download(e: Episode) = viewModelScope.launch {
        try { Repo.download(e); message = "Downloaded" } catch (x: Exception) { message = "Download failed: ${x.message}" }
    }
    fun openAudioInOtherApp(e: Episode) = viewModelScope.launch {
        try {
            val path = Repo.publishAudio(e)
            val uri = Repo.audioUri(path)
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Repo.audioMime(path))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(Intent.createChooser(intent, "Play audio with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (x: Exception) { message = "Could not open audio: ${x.message}" }
    }
    fun openDownloadFolder() {
        val context = getApplication<Application>()
        val folder = DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Music/PodScribe"
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            message = "Could not open Music/PodScribe: ${e.message?.take(120)}"
        }
    }
    fun transcribe(e: Episode) {
        viewModelScope.launch {
            if (Repo.transcriptionEngine == "local") Transcriber.runLocal(e, dur)
            else Transcriber.run(e)
        }
    }

    fun unsubscribe(podcastId: String) {
        val ids = Repo.episodes.value.filter { it.podcastId == podcastId }.map { it.id }.toSet()
        if (current?.id in ids) {
            runCatching { controller?.stop(); controller?.clearMediaItems() }
            current = null; playing = false; pos = 0; dur = 0
        }
        Transcriber.forget(ids)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Repo.removePodcast(podcastId) }
                message = "Unsubscribed"
            } catch (e: Exception) {
                message = "Could not unsubscribe: ${e.message}"
            }
        }
    }
}

private fun normalizeSpeed(speed: Float) = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    .minBy { kotlin.math.abs(it - speed) }
