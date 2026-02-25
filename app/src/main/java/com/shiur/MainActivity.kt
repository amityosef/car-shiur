package com.shiur

import android.os.Bundle
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {
    private val viewModel: PlayerViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(color = MaterialTheme.colors.background) {
                AppRoot(viewModel)
            }
        }
    }
}

@Composable
fun AppRoot(viewModel: PlayerViewModel) {
    val isPlaying by viewModel.isPlaying.collectAsState()
    val position by viewModel.positionMs.collectAsState()
    val duration by viewModel.durationMs.collectAsState()
    val list by viewModel.mediaList.collectAsState()
    val current by viewModel.currentIndex.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
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
                IconButton(onClick = { viewModel.player.seekTo((viewModel.player.currentPosition - 10_000).coerceAtLeast(0L)) }) {
                    Icon(Icons.Filled.FastRewind, contentDescription = "Back 10s", modifier = Modifier.size(36.dp))
                }
                IconButton(onClick = { if (isPlaying) viewModel.pause() else viewModel.play() }) {
                    if (isPlaying) Icon(Icons.Filled.Pause, contentDescription = "Pause", modifier = Modifier.size(48.dp)) else Icon(Icons.Default.PlayArrow, contentDescription = "Play", modifier = Modifier.size(48.dp))
                }
                IconButton(onClick = { viewModel.player.seekTo(viewModel.player.currentPosition + 10_000) }) {
                    Icon(Icons.Filled.FastForward, contentDescription = "Forward 10s", modifier = Modifier.size(36.dp))
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
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Button(onClick = { viewModel.previous() }) { Text("Previous") }
                Spacer(modifier = Modifier.width(12.dp))
                Button(onClick = { viewModel.next() }) { Text("Next") }
            }
        }
        itemsIndexed(list) { index, item ->
            val highlighted = index == current
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { viewModel.playAt(index) }
                    .height(72.dp),
                elevation = if (highlighted) 8.dp else 2.dp,
                backgroundColor = if (highlighted) MaterialTheme.colors.primary.copy(alpha = 0.12f) else Color.Transparent
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatMs(item.durationMs), modifier = Modifier.padding(end = 8.dp))
                        if (item.artResId != 0) {
                            Image(painter = painterResource(id = item.artResId), contentDescription = null, modifier = Modifier.size(48.dp))
                        } else {
                            Box(modifier = Modifier.size(48.dp))
                        }
                    }
                }
            }
        }
    }
}
