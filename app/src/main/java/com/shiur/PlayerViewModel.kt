package com.shiur

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    private val mutableMediaList = MutableStateFlow<List<MediaEntry>>(emptyList())
    val mediaList: StateFlow<List<MediaEntry>> = mutableMediaList
    private val mutableCurrentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = mutableCurrentIndex

    private val mutableIsPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = mutableIsPlaying

    private val mutablePositionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = mutablePositionMs

    private val mutableDurationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = mutableDurationMs

    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    private val prefIndex = "current_index"
    private val prefPosition = "last_position"

    init {
        val savedIndex = prefs.getInt(prefIndex, 0)
        val savedPos = prefs.getLong(prefPosition, 0L)

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    mutableDurationMs.value = player.duration.coerceAtLeast(0)
                }
                mutableIsPlaying.value = player.isPlaying
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                mutableIsPlaying.value = isPlaying
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = player.currentMediaItemIndex
                mutableCurrentIndex.value = newIndex
                mutableDurationMs.value = player.duration.coerceAtLeast(0)
                
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
                    prefs.edit().putInt(prefIndex, newIndex).apply()
                }
            }
        })

        viewModelScope.launch {
            loadRawTracks()
            mutableMediaList.first { it.isNotEmpty() }
            val list = mutableMediaList.value
            if (savedIndex in list.indices) {
                mutableCurrentIndex.value = savedIndex
                player.seekTo(savedIndex, savedPos)
                player.prepare()
            }
        }

        viewModelScope.launch {
            while (isActive) {
                if (player.playbackState != Player.STATE_IDLE) {
                    mutablePositionMs.value = player.currentPosition
                    mutableDurationMs.value = player.duration.coerceAtLeast(0)
                    mutableCurrentIndex.value = player.currentMediaItemIndex
                    
                    if (player.isPlaying) {
                        prefs.edit()
                            .putLong(prefPosition, player.currentPosition)
                            .putInt(prefIndex, player.currentMediaItemIndex)
                            .apply()
                    }
                }
                delay(500L)
            }
        }
    }

    private fun loadRawTracks() {
        val names = listOf("track1", "track2", "emuna_bitahon1", "emuna_bitahon2")
        val ids = names.mapNotNull { name ->
            val id = app.resources.getIdentifier(name, "raw", app.packageName)
            if (id != 0) id else null
        }
        val entries = ids.map { resId ->
            val retriever = MediaMetadataRetriever()
            val resourceUri = Uri.parse("android.resource://${app.packageName}/$resId")
            try {
                retriever.setDataSource(app, resourceUri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: names[ids.indexOf(resId)]
                val art = app.resources.getIdentifier("ic_launcher_foreground", "mipmap", app.packageName)
                MediaEntry(resId, title, dur, art)
            } catch (e: Exception) {
                val art = app.resources.getIdentifier("ic_launcher_foreground", "mipmap", app.packageName)
                MediaEntry(resId, names[ids.indexOf(resId)], 0L, art)
            } finally {
                retriever.release()
            }
        }
        mutableMediaList.value = entries
        if (entries.isNotEmpty()) {
            val mediaItems = entries.map { entry ->
                MediaItem.fromUri(Uri.parse("android.resource://${app.packageName}/${entry.resId}"))
            }
            player.setMediaItems(mediaItems)
            player.prepare()
        }
    }

    fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun playAt(index: Int) {
        val list = mutableMediaList.value
        if (index in list.indices) {
            mutableCurrentIndex.value = index
            player.seekTo(index, 0)
            prefs.edit().putInt(prefIndex, index).putLong(prefPosition, 0L).apply()
            player.play()
        }
    }

    fun next() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            mutableCurrentIndex.value = player.currentMediaItemIndex
            prefs.edit().putInt(prefIndex, player.currentMediaItemIndex).putLong(prefPosition, 0L).apply()
            player.play()
        }
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) {
            player.seekToPreviousMediaItem()
            mutableCurrentIndex.value = player.currentMediaItemIndex
            prefs.edit().putInt(prefIndex, player.currentMediaItemIndex).putLong(prefPosition, 0L).apply()
            player.play()
        }
    }

    override fun onCleared() {
        super.onCleared()
        prefs.edit()
            .putInt(prefIndex, player.currentMediaItemIndex)
            .putLong(prefPosition, player.currentPosition)
            .apply()
        player.release()
    }
}
