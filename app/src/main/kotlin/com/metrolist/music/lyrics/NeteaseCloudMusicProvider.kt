package com.metrolist.music.lyrics

import android.content.Context
import com.metrolist.music.constants.EnableNeteaseCloudMusicKey
import com.metrolist.music.utils.dataStore
import com.metrolist.netease.NeteaseCloudMusicLyricsProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object NeteaseCloudMusicProvider : LyricsProvider {
    override val name = "NeteaseCloudMusic"

    override fun isEnabled(context: Context): Boolean =
        runBlocking { context.dataStore.data.first() }[EnableNeteaseCloudMusicKey] ?: false

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = NeteaseCloudMusicLyricsProvider.getLyrics(id, title, artist, duration, album)

    override suspend fun getAllLyrics(
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
        callback: (String) -> Unit,
    ) {
        NeteaseCloudMusicLyricsProvider.getAllLyrics(id, title, artist, duration, album, callback)
    }
}
