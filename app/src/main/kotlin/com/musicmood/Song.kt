package com.musicmood

data class Song(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: String,
    val duration: Float,
    val albumId: Long = 0L,
    var tempo: Float = 0f,
    var energy: Float = 0f,
    var mood: String = "",
    var genreResolved: String = "",
    var analyzed: Boolean = false
)
