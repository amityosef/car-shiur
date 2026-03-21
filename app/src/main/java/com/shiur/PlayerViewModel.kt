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
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

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
            refreshTracks()
            mutableMediaList.first { it.isNotEmpty() }
            val list = mutableMediaList.value
            val startIndex = if (savedIndex in list.indices) savedIndex else 0

            mutableCurrentIndex.value = startIndex
            player.seekTo(startIndex, savedPos.coerceAtLeast(0L))
            player.prepare()
            player.playWhenReady = true
            player.play()
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

    fun refreshTracks() {
        viewModelScope.launch {
            val rawEntries = loadRawTracks()
            val internalEntries = loadInternalTracks()
            val allEntries = (rawEntries + internalEntries).sortedBy { it.title }
            
            mutableMediaList.value = allEntries
            
            val mediaItems = allEntries.map { entry ->
                if (entry.resId != null) {
                    MediaItem.fromUri(Uri.parse("android.resource://${app.packageName}/${entry.resId}"))
                } else {
                    MediaItem.fromUri(entry.uri!!)
                }
            }
            player.setMediaItems(mediaItems)
            player.prepare()
        }
    }

    private fun loadRawTracks(): List<MediaEntry> {
        val rawFields = try {
            R.raw::class.java.fields
        } catch (e: Exception) {
            emptyArray()
        }

        return rawFields.mapNotNull { field ->
            try {
                val resId = field.getInt(null)
                val originalName = field.name
                val retriever = MediaMetadataRetriever()
                val resourceUri = Uri.parse("android.resource://${app.packageName}/$resId")
                
                retriever.setDataSource(app, resourceUri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                
                if (title.isNullOrBlank()) {
                    title = originalName.replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
                }
                
                val art = app.resources.getIdentifier("ic_launcher_foreground", "mipmap", app.packageName)
                retriever.release()
                MediaEntry(title = title, durationMs = dur, artResId = art, resId = resId)
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun loadInternalTracks(): List<MediaEntry> {
        val filesDir = app.filesDir
        val audioFiles = filesDir.listFiles { file ->
            file.extension.lowercase() in listOf("mp3", "m4a", "wav", "mp4", "ogg", "aac", "amr")
        } ?: emptyArray()

        return audioFiles.mapNotNull { file ->
            try {
                val retriever = MediaMetadataRetriever()
                val uri = Uri.fromFile(file)
                retriever.setDataSource(app, uri)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                var title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                
                if (title.isNullOrBlank()) {
                    title = file.nameWithoutExtension.replace("_", " ")
                        .split(" ")
                        .joinToString(" ") { it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() } }
                }
                
                val art = app.resources.getIdentifier("ic_launcher_foreground", "mipmap", app.packageName)
                retriever.release()
                MediaEntry(title = title, durationMs = dur, artResId = art, uri = uri)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            try {
                val fileName = getFileName(uri) ?: "imported_track_${System.currentTimeMillis()}"
                val destinationFile = File(app.filesDir, fileName)
                
                app.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
                refreshTracks()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteMediaEntry(mediaEntry: MediaEntry) {
        viewModelScope.launch {
            if (mediaEntry.uri != null) { // Only delete if it's an imported file
                try {
                    val file = File(mediaEntry.uri.path!!)
                    if (file.exists()) {
                        file.delete()
                    }
                    refreshTracks() // Refresh the list after deletion
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var name: String? = null
        val cursor = app.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        return name
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
