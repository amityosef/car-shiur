package com.shiur

import android.net.Uri

data class MediaEntry(
    val title: String, 
    val durationMs: Long, 
    val artResId: Int,
    val resId: Int? = null,
    val uri: Uri? = null
)
