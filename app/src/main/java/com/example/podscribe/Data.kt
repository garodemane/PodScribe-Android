package com.example.podscribe

data class Podcast(val id: String, val title: String, val author: String, val image: String, val description: String)

data class Episode(
    val id: String, val podcastId: String, val title: String, val audioUrl: String,
    val pubDate: Long, val durationSec: Int, val description: String, val image: String,
    val positionMs: Long = 0, val played: Boolean = false, val localPath: String? = null, val seen: Boolean = false
)

data class WordStamp(val text: String, val startMs: Long, val endMs: Long)
data class Seg(val startMs: Long, val endMs: Long, val text: String, val tr: String, val words: List<WordStamp> = emptyList())

open class ApiRequestException(val statusCode: Int, message: String) : Exception(message)
