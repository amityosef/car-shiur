package com.shiur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.Button
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Slider
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.res.painterResource
import androidx.compose.material.Card
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.text.layoutDirection
import java.util.Locale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.os.StatFs
import android.os.Environment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request storage permissions if needed
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 100)
            }
        }

        // Handle shared files from other apps (like WhatsApp)
        handleIncomingIntent(intent)

        setContent {
            Surface(color = MaterialTheme.colors.background) {
                AppRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                // Handle single file shared from another app
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                }
                uri?.let { handleSharedFile(it) }
            }
            Intent.ACTION_VIEW -> {
                // Handle "Open with" from file managers
                intent.data?.let { uri ->
                    handleSharedFile(uri)
                }
            }
        }
    }

    private fun handleSharedFile(uri: android.net.Uri) {
        try {
            // Try to take persistable permission
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // If we can't get persistent permission, we'll still try to import
            // (it might work for the current session)
        }

        // Import the file to the player
        viewModel.importFile(uri)

        // Show confirmation to user
        Toast.makeText(
            this,
            "Media file received and added to playlist",
            Toast.LENGTH_SHORT
        ).show()
    }
}

@Composable
fun AppRoot(viewModel: PlayerViewModel) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.positionMs.collectAsState()
    val duration by viewModel.durationMs.collectAsState()
    val list by viewModel.mediaList.collectAsState()
    val current by viewModel.currentIndex.collectAsState()
    val context = LocalContext.current

    // Storage information state
    var appSize by remember { mutableStateOf("...") }
    var freeSpace by remember { mutableStateOf("...") }
    val scope = rememberCoroutineScope()

    // Calculate storage info
    LaunchedEffect(Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Calculate app size
                    val dataDir = context.dataDir
                    val cacheDir = context.cacheDir
                    var totalSize = 0L

                    fun getDirSize(dir: File): Long {
                        var size = 0L
                        dir.listFiles()?.forEach { file ->
                            size += if (file.isDirectory) getDirSize(file) else file.length()
                        }
                        return size
                    }

                    totalSize = getDirSize(dataDir) + getDirSize(cacheDir)

                    appSize = when {
                        totalSize >= 1024 * 1024 * 1024 -> String.format("%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0))
                        totalSize >= 1024 * 1024 -> String.format("%.2f MB", totalSize / (1024.0 * 1024.0))
                        totalSize >= 1024 -> String.format("%.2f KB", totalSize / 1024.0)
                        else -> "$totalSize B"
                    }

                    // Calculate free space
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

                    freeSpace = when {
                        availableBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", availableBytes / (1024.0 * 1024.0 * 1024.0))
                        availableBytes >= 1024 * 1024 -> String.format("%.2f MB", availableBytes / (1024.0 * 1024.0))
                        availableBytes >= 1024 -> String.format("%.2f KB", availableBytes / 1024.0)
                        else -> "$availableBytes B"
                    }
                } catch (e: Exception) {
                    appSize = "N/A"
                    freeSpace = "N/A"
                }
            }
        }
    }

    // Recalculate when media list changes
    LaunchedEffect(list.size) {
        scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    // Calculate app size
                    val dataDir = context.dataDir
                    val cacheDir = context.cacheDir
                    var totalSize = 0L

                    fun getDirSize(dir: File): Long {
                        var size = 0L
                        dir.listFiles()?.forEach { file ->
                            size += if (file.isDirectory) getDirSize(file) else file.length()
                        }
                        return size
                    }

                    totalSize = getDirSize(dataDir) + getDirSize(cacheDir)

                    appSize = when {
                        totalSize >= 1024 * 1024 * 1024 -> String.format("%.2f GB", totalSize / (1024.0 * 1024.0 * 1024.0))
                        totalSize >= 1024 * 1024 -> String.format("%.2f MB", totalSize / (1024.0 * 1024.0))
                        totalSize >= 1024 -> String.format("%.2f KB", totalSize / 1024.0)
                        else -> "$totalSize B"
                    }

                    // Calculate free space
                    val stat = StatFs(Environment.getDataDirectory().path)
                    val availableBytes = stat.availableBlocksLong * stat.blockSizeLong

                    freeSpace = when {
                        availableBytes >= 1024 * 1024 * 1024 -> String.format("%.2f GB", availableBytes / (1024.0 * 1024.0 * 1024.0))
                        availableBytes >= 1024 * 1024 -> String.format("%.2f MB", availableBytes / (1024.0 * 1024.0))
                        availableBytes >= 1024 -> String.format("%.2f KB", availableBytes / 1024.0)
                        else -> "$availableBytes B"
                    }
                } catch (e: Exception) {
                    appSize = "N/A"
                    freeSpace = "N/A"
                }
            }
        }
    }

    // Permission launcher for storage access
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(context, "Storage permission is needed to access files", Toast.LENGTH_LONG).show()
        }
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                // Take persistable permission so we can access the file
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: Exception) {
                    // If we can't get persistent permission, just try to import it anyway
                }
                viewModel.importFile(uri)
            }
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides if (Locale.getDefault().layoutDirection == android.util.LayoutDirection.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colors.background)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Album,
                                contentDescription = "Record",
                                modifier = Modifier
                                    .size(48.dp)
                                    .padding(end = 8.dp),
                                tint = MaterialTheme.colors.primary
                            )
                            Column {
                                Text(
                                    "Shiur Player",
                                    style = MaterialTheme.typography.h6,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "App: $appSize | Free: $freeSpace",
                                    style = MaterialTheme.typography.caption,
                                    color = Color.Gray
                                )
                            }
                        }
                        // System file picker button (Plus button)
                        IconButton(onClick = {
                        // Check permission first for Android 12 and below
                        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                                != PackageManager.PERMISSION_GRANTED) {
                                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                return@IconButton
                            }
                        }

                        try {
                            // Use ACTION_PICK which QuickPic handles well
                            val intent = Intent(Intent.ACTION_PICK)
                            // Use wildcard to let QuickPic show all media
                            intent.type = "*/*"

                            // Specify supported MIME types including audio
                            val mimeTypes = arrayOf(
                                "audio/*",
                                "video/*",
                                "image/*"
                            )
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)

                            launcher.launch(intent)
                        } catch (e: ActivityNotFoundException) {
                            // Fallback to GET_CONTENT if PICK doesn't work
                            try {
                                val intent = Intent(Intent.ACTION_GET_CONTENT)
                                intent.addCategory(Intent.CATEGORY_OPENABLE)
                                intent.type = "*/*"
                                val mimeTypes = arrayOf("audio/*", "video/*", "image/*")
                                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                                launcher.launch(intent)
                            } catch (e2: Exception) {
                                Toast.makeText(context, "No file picker found: ${e2.message}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add Track",
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colors.primary
                        )
                    }
                    }
                    Spacer(modifier = Modifier.height(16.dp)) // Spacer after the title row
                }
            item {
                 AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            player = viewModel.player
                            useController = false
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(Color.Black)
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

                    // In RTL: Forward should be on left, Play in middle, Rewind on right
                    // In LTR: Rewind should be on left, Play in middle, Forward on right
                    IconButton(onClick = {
                        if (isRtl) {
                            viewModel.player.seekTo(viewModel.player.currentPosition + 10_000)
                        } else {
                            viewModel.player.seekTo((viewModel.player.currentPosition - 10_000).coerceAtLeast(0L))
                        }
                    }) {
                        if (isRtl) {
                            Icon(Icons.Filled.FastForward, contentDescription = "Forward 10s", modifier = Modifier.size(36.dp))
                        } else {
                            Icon(Icons.Filled.FastRewind, contentDescription = "Back 10s", modifier = Modifier.size(36.dp))
                        }
                    }
                    IconButton(onClick = { if (isPlaying) viewModel.pause() else viewModel.play() }) {
                        if (isPlaying) Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(48.dp)) else Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(48.dp))
                    }
                    IconButton(onClick = {
                        if (isRtl) {
                            viewModel.player.seekTo((viewModel.player.currentPosition - 10_000).coerceAtLeast(0L))
                        } else {
                            viewModel.player.seekTo(viewModel.player.currentPosition + 10_000)
                        }
                    }) {
                        if (isRtl) {
                            Icon(Icons.Filled.FastRewind, contentDescription = "Back 10s", modifier = Modifier.size(36.dp))
                        } else {
                            Icon(Icons.Filled.FastForward, contentDescription = "Forward 10s", modifier = Modifier.size(36.dp))
                        }
                    }
                }
            }
            item {
                Slider(
                    value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { frac ->
                        val pos = (frac * duration).toLong()
                        viewModel.player.seekTo(pos)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatMs(position))
                    Text(formatMs(duration))
                }
            }
            item {
                val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    // In RTL: Next should be on left, Previous on right
                    // In LTR: Previous should be on left, Next on right
                    Button(onClick = { if (isRtl) viewModel.next() else viewModel.previous() }) {
                        Text(if (isRtl) "Next" else "Previous")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(onClick = { if (isRtl) viewModel.previous() else viewModel.next() }) {
                        Text(if (isRtl) "Previous" else "Next")
                    }
                }
            }
            itemsIndexed(list) { index, item ->
                val highlighted = index == current
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .clickable { viewModel.playAt(index) }
                        .height(80.dp)
                        .then(if (highlighted) Modifier.border(1.dp, MaterialTheme.colors.primary, RoundedCornerShape(8.dp)) else Modifier),
                    shape = RoundedCornerShape(8.dp),
                    elevation = if (highlighted) 4.dp else 4.dp,
                    backgroundColor = if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.05f) else MaterialTheme.colors.surface
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Album,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = if (highlighted) MaterialTheme.colors.primary else Color.Gray
                        )
                        
                        Spacer(modifier = Modifier.width(12.dp))
                        
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.subtitle1
                            )
                            Text(
                                formatMs(item.durationMs),
                                style = MaterialTheme.typography.caption
                            )
                        }

                        if (highlighted && isPlaying) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Playing",
                                tint = MaterialTheme.colors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (item.uri != null) {
                                    viewModel.deleteMediaEntry(item)
                                } else {
                                    Toast.makeText(context, "Cannot delete built-in tracks", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = item.uri != null
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = "Delete Track",
                                tint = if (item.uri != null) MaterialTheme.colors.error else Color.Gray
                            )
                        }
                    }
                }
            }
        }
        }
    }
}