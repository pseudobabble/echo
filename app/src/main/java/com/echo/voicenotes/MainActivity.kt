package com.echo.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echo.voicenotes.data.Note
import com.echo.voicenotes.data.NoteRepository
import com.echo.voicenotes.data.SyncState
import com.echo.voicenotes.data.TranscriptionState
import com.echo.voicenotes.drive.DriveFolder
import com.echo.voicenotes.drive.DriveSyncScheduler
import com.echo.voicenotes.recording.RecordShortcutPublisher
import com.echo.voicenotes.recording.RecordingService
import com.echo.voicenotes.recording.RecordingStatus
import com.echo.voicenotes.transcription.WhisperModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) RecordShortcutPublisher.publish(this) }
    private val driveFolderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags)
        DriveFolder.set(this, uri)
        CoroutineScope(Dispatchers.IO).launch { NoteRepository.get(this@MainActivity).notes.completedIds().forEach { DriveSyncScheduler.enqueue(this@MainActivity, it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else RecordShortcutPublisher.publish(this)
        setContent { EchoApp(::requestRecording, ::stopRecording, ::chooseDriveFolder, ::installTranscriptionModel) }
    }

    private fun requestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() = ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
    private fun stopRecording() = startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    private fun chooseDriveFolder() = driveFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    private fun installTranscriptionModel() = WhisperModelManager.enqueueInstall(this)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EchoApp(onRecord: () -> Unit, onStop: () -> Unit, onConnectDrive: () -> Unit, onInstallModel: () -> Unit) {
    val context = LocalContext.current
    val repo = remember(context) { NoteRepository.get(context) }
    val modelInstalled = remember(context) { WhisperModelManager(context).installedModel() != null }
    val notes by repo.notes.observeAll().collectAsStateWithLifecycle(emptyList())
    val activeRecording by RecordingStatus.active.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(activeRecording?.noteId) {
        while (activeRecording != null) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Echo") }, actions = { TextButton(onClick = onConnectDrive) { Text("Drive") } }) },
            floatingActionButton = {
                if (activeRecording == null) FloatingActionButton(onClick = onRecord) { Icon(Icons.Default.Mic, "Record") }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                if (activeRecording != null) RecordingPanel(now - activeRecording!!.startedAt, onStop)
                if (!modelInstalled && notes.isEmpty()) ModelDownloadPrompt(onInstallModel)
                if (notes.isEmpty() && activeRecording == null && modelInstalled) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Tap the microphone to record a voice note") }
                } else if (notes.isNotEmpty()) {
                    PlaybackNoteList(notes)
                }
            }
        }
    }
}

@Composable
private fun RecordingPanel(elapsedMs: Long, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Recording", style = MaterialTheme.typography.titleMedium)
                Text(formatDuration(elapsedMs), style = MaterialTheme.typography.headlineSmall)
            }
            Button(onClick = onStop) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }
    }
}

@Composable
private fun ModelDownloadPrompt(onInstallModel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Download the on-device transcription model to get started")
            Spacer(Modifier.height(12.dp))
            Button(onClick = onInstallModel) { Text("Download model (Wi-Fi, 57 MB)") }
        }
    }
}

@Composable
private fun PlaybackNoteList(notes: List<Note>) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    fun releasePlayer() {
        player?.release()
        player = null
        playingId = null
        isPlaying = false
        positionMs = 0
        durationMs = 0
    }

    fun togglePlayback(note: Note) {
        if (playingId == note.id) {
            val current = player ?: return
            if (current.isPlaying) {
                current.pause()
                isPlaying = false
            } else {
                current.start()
                isPlaying = true
            }
            return
        }
        releasePlayer()
        runCatching {
            MediaPlayer().apply {
                setDataSource(note.audioPath)
                prepare()
                setOnCompletionListener { releasePlayer() }
                start()
            }
        }.onSuccess { created ->
            player = created
            playingId = note.id
            durationMs = created.duration.toLong().coerceAtLeast(0)
            isPlaying = true
        }
    }

    LaunchedEffect(playingId, isPlaying) {
        while (playingId != null && isPlaying) {
            positionMs = player?.currentPosition?.toLong()?.coerceAtLeast(0) ?: 0
            delay(250)
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            val isCurrent = note.id == playingId
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(note.createdAt)), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(noteStatus(note))
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { togglePlayback(note) }) {
                            Icon(if (isCurrent && isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isCurrent && isPlaying) "Pause recording" else "Play recording")
                        }
                        Text(if (isCurrent) "${formatDuration(positionMs)} / ${formatDuration(durationMs)}" else formatDuration(note.durationMs), style = MaterialTheme.typography.labelLarge)
                    }
                    if (isCurrent && durationMs > 0) {
                        Slider(
                            value = positionMs.coerceIn(0, durationMs).toFloat(),
                            onValueChange = { target ->
                                player?.seekTo(target.toInt())
                                positionMs = target.toLong()
                            },
                            valueRange = 0f..durationMs.toFloat(),
                        )
                    }
                    AssistChip(onClick = {}, label = { Text(driveStatus(note.driveState)) })
                }
            }
        }
    }
}

private fun noteStatus(note: Note): String = when (note.transcriptionState) {
    TranscriptionState.COMPLETE -> note.transcript.orEmpty().ifBlank { "No speech detected" }
    TranscriptionState.FAILED -> "Transcription needs attention: ${note.transcriptionError}"
    else -> "${note.transcriptionState.name.lowercase().replaceFirstChar { it.uppercase() }}…"
}

private fun driveStatus(state: SyncState): String = when (state) {
    SyncState.NOT_CONFIGURED -> "Audio stored on this device"
    SyncState.PENDING -> "Uploading to Drive…"
    SyncState.SYNCED -> "Saved to Drive"
    SyncState.FAILED -> "Drive upload will retry"
}

private fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0) / 1000)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
