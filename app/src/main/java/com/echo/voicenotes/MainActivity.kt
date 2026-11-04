package com.echo.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echo.voicenotes.data.Note
import com.echo.voicenotes.data.NoteKind
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
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.io.File

class MainActivity : ComponentActivity() {
    private var requestedKind = NoteKind.NOTE
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording(requestedKind) }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) RecordShortcutPublisher.publish(this) }
    private val driveFolderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags)
        DriveFolder.set(this, uri)
        CoroutineScope(Dispatchers.IO).launch { NoteRepository.get(this@MainActivity).notes.allIds().forEach { DriveSyncScheduler.enqueue(this@MainActivity, it) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else RecordShortcutPublisher.publish(this)
        setContent {
            EchoApp(
                onRecord = ::requestRecording,
                onPause = { sendRecordingAction(RecordingService.ACTION_PAUSE) },
                onResume = { sendRecordingAction(RecordingService.ACTION_RESUME) },
                onStop = { sendRecordingAction(RecordingService.ACTION_STOP) },
                onConnectDrive = ::chooseDriveFolder,
                onInstallModel = ::installTranscriptionModel,
            )
        }
    }

    private fun requestRecording(kind: NoteKind) {
        requestedKind = kind
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording(kind)
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording(kind: NoteKind) = ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java)
        .setAction(RecordingService.ACTION_START).putExtra(RecordingService.EXTRA_NOTE_KIND, kind.name))
    private fun sendRecordingAction(action: String) = startService(Intent(this, RecordingService::class.java).setAction(action))
    private fun chooseDriveFolder() = driveFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
    private fun installTranscriptionModel() = WhisperModelManager.enqueueInstall(this)
}

private enum class Destination(val label: String) { INBOX("Inbox"), TASKS("Tasks"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EchoApp(
    onRecord: (NoteKind) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onConnectDrive: () -> Unit,
    onInstallModel: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember(context) { NoteRepository.get(context) }
    val modelInstalled by WhisperModelManager.installed.collectAsStateWithLifecycle()
    val driveFolder by DriveFolder.folder.collectAsStateWithLifecycle()
    val notes by repo.notes.observeAll().collectAsStateWithLifecycle(emptyList())
    val activeRecording by RecordingStatus.active.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(Destination.INBOX) }
    var captureKind by remember { mutableStateOf(NoteKind.NOTE) }
    var selectedNote by remember { mutableStateOf<Note?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(context) {
        DriveFolder.load(context)
        withContext(Dispatchers.IO) { WhisperModelManager.refreshInstalled(context) }
    }

    LaunchedEffect(activeRecording?.noteId, activeRecording?.isPaused) {
        while (activeRecording != null && activeRecording?.isPaused == false) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }

    MaterialTheme {
        if (selectedNote != null) {
            NoteDetailScreen(note = selectedNote!!, onBack = { selectedNote = null })
            return@MaterialTheme
        }
        Scaffold(
            topBar = { TopAppBar(title = { Text("Echo") }) },
            bottomBar = { EchoNavigation(destination, onSelect = { destination = it }) },
            floatingActionButton = {
                if (activeRecording == null && destination != Destination.SETTINGS) {
                    FloatingActionButton(onClick = { onRecord(captureKind) }) { Icon(Icons.Default.Mic, "Start recording") }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                activeRecording?.let { recording ->
                    RecordingPanel(
                        recording = recording,
                        elapsedMs = recording.elapsedAt(now),
                        onPause = onPause,
                        onResume = onResume,
                        onStop = onStop,
                    )
                }
                when (destination) {
                    Destination.INBOX -> InboxScreen(notes.filter { it.kind == NoteKind.NOTE }, captureKind, { captureKind = it }, onRecord, modelInstalled, driveFolder != null, onInstallModel, { selectedNote = it })
                    Destination.TASKS -> TasksScreen(notes.filter { it.kind == NoteKind.TASK }, captureKind, { captureKind = it }, onRecord, modelInstalled, driveFolder != null, { selectedNote = it })
                    Destination.SETTINGS -> SettingsScreen(modelInstalled, onInstallModel, driveFolder != null, onConnectDrive)
                }
            }
        }
    }
}

@Composable
private fun EchoNavigation(selected: Destination, onSelect: (Destination) -> Unit) {
    NavigationBar {
        NavigationBarItem(selected == Destination.INBOX, { onSelect(Destination.INBOX) }, { Icon(Icons.Default.Mic, null) }, label = { Text("Inbox") })
        NavigationBarItem(selected == Destination.TASKS, { onSelect(Destination.TASKS) }, { Icon(Icons.Default.TaskAlt, null) }, label = { Text("Tasks") })
        NavigationBarItem(selected == Destination.SETTINGS, { onSelect(Destination.SETTINGS) }, { Icon(Icons.Default.Settings, null) }, label = { Text("Settings") })
    }
}

@Composable
private fun InboxScreen(
    notes: List<Note>,
    captureKind: NoteKind,
    onKindSelected: (NoteKind) -> Unit,
    onRecord: (NoteKind) -> Unit,
    modelInstalled: Boolean,
    driveConnected: Boolean,
    onInstallModel: () -> Unit,
    onOpenNote: (Note) -> Unit,
) {
    if (notes.isEmpty()) {
        CaptureLanding(captureKind, onKindSelected, onRecord, modelInstalled, onInstallModel)
    } else {
        NoteList(notes, modelInstalled, driveConnected, onOpenNote)
    }
}

@Composable
private fun TasksScreen(notes: List<Note>, captureKind: NoteKind, onKindSelected: (NoteKind) -> Unit, onRecord: (NoteKind) -> Unit, modelInstalled: Boolean, driveConnected: Boolean, onOpenNote: (Note) -> Unit) {
    if (notes.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.TaskAlt, null, modifier = Modifier.padding(bottom = 12.dp))
                Text("Capture a task by voice", style = MaterialTheme.typography.titleLarge)
                Text("Task recordings stay organised here while they are transcribed.", modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(16.dp))
                CaptureTypePicker(captureKind, onKindSelected)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { onRecord(NoteKind.TASK) }) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text("Record task") }
            }
        }
    } else {
        NoteList(notes, modelInstalled, driveConnected, onOpenNote)
    }
}

@Composable
private fun CaptureLanding(kind: NoteKind, onKindSelected: (NoteKind) -> Unit, onRecord: (NoteKind) -> Unit, modelInstalled: Boolean, onInstallModel: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Mic, null, modifier = Modifier.padding(bottom = 12.dp))
            Text("Capture a thought", style = MaterialTheme.typography.headlineSmall)
            Text("Record locally. Echo transcribes on this device.", modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(20.dp))
            CaptureTypePicker(kind, onKindSelected)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { onRecord(kind) }) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(if (kind == NoteKind.NOTE) "Record note" else "Record task") }
            if (!modelInstalled) {
                Spacer(Modifier.height(24.dp))
                Text("Transcription model not installed", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = onInstallModel) { Text("Download model (Wi-Fi, 57 MB)") }
            }
        }
    }
}

@Composable
private fun CaptureTypePicker(selected: NoteKind, onSelected: (NoteKind) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected == NoteKind.NOTE, { onSelected(NoteKind.NOTE) }, label = { Text("Note") }, leadingIcon = if (selected == NoteKind.NOTE) ({ Icon(Icons.Default.CheckCircle, null) }) else null)
        FilterChip(selected == NoteKind.TASK, { onSelected(NoteKind.TASK) }, label = { Text("Task") }, leadingIcon = if (selected == NoteKind.TASK) ({ Icon(Icons.Default.CheckCircle, null) }) else null)
    }
}

@Composable
private fun RecordingPanel(recording: com.echo.voicenotes.recording.ActiveRecording, elapsedMs: Long, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Mic, null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (recording.isPaused) "Recording paused" else "Recording ${recording.kind.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
                    Text(formatDuration(elapsedMs), style = MaterialTheme.typography.headlineSmall)
                }
                AssistChip(onClick = {}, label = { Text("Saved locally") })
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(if (recording.isPaused) 0f else 0.7f, Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = if (recording.isPaused) onResume else onPause) {
                    Icon(if (recording.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (recording.isPaused) "Resume" else "Pause")
                }
                Button(onClick = onStop) { Icon(Icons.Default.Stop, null); Spacer(Modifier.width(8.dp)); Text("Stop") }
            }
        }
    }
}

@Composable
private fun SettingsScreen(modelInstalled: Boolean, onInstallModel: () -> Unit, driveConnected: Boolean, onConnectDrive: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("On-device transcription", style = MaterialTheme.typography.titleMedium)
            Text(if (modelInstalled) "Model installed" else "Model not installed", modifier = Modifier.padding(top = 4.dp))
            if (!modelInstalled) TextButton(onClick = onInstallModel) { Text("Download model (Wi-Fi, 57 MB)") }
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Google Drive", style = MaterialTheme.typography.titleMedium)
            Text(if (driveConnected) "Folder connected — audio uploads after Stop" else "No folder connected", modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = onConnectDrive) { Text(if (driveConnected) "Change Drive folder" else "Connect Drive folder") }
        } }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text("Google Tasks", style = MaterialTheme.typography.titleMedium)
            Text("Task capture is ready. Connecting it to your Google Tasks account needs a Google OAuth client.", modifier = Modifier.padding(top = 4.dp))
        } }
    }
}

@Composable
private fun NoteList(notes: List<Note>, modelInstalled: Boolean, driveConnected: Boolean, onOpenNote: (Note) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(notes, key = { it.id }) { note ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(noteTitle(note), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        AssistChip(onClick = { onOpenNote(note) }, label = { Text(note.kind.name.lowercase().replaceFirstChar { it.uppercase() }) })
                    }
                    Text(DateFormat.getDateTimeInstance().format(Date(note.createdAt)), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(noteStatus(note, modelInstalled), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (note.driveState == SyncState.SYNCED) Icons.Default.CloudDone else Icons.Default.CloudUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("${formatDuration(note.durationMs)} · ${driveStatus(note.driveState, driveConnected)}", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { onOpenNote(note) }) { Text("Open") }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NoteDetailScreen(note: Note, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var player by remember(note.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember(note.id) { mutableStateOf(false) }
    var positionMs by remember(note.id) { mutableLongStateOf(0L) }
    var durationMs by remember(note.id) { mutableLongStateOf(note.durationMs) }

    fun releasePlayer() { player?.release(); player = null; isPlaying = false; positionMs = 0 }
    fun togglePlayback() {
        val existing = player
        if (existing != null) {
            if (existing.isPlaying) { existing.pause(); isPlaying = false } else { existing.start(); isPlaying = true }
        } else {
            runCatching { MediaPlayer().apply { setDataSource(note.audioPath); prepare(); setOnCompletionListener { releasePlayer() }; start() } }
                .onSuccess { created -> player = created; durationMs = created.duration.toLong().coerceAtLeast(0); isPlaying = true }
        }
    }
    LaunchedEffect(isPlaying) { while (isPlaying) { positionMs = player?.currentPosition?.toLong()?.coerceAtLeast(0) ?: 0; delay(250) } }
    DisposableEffect(note.id) { onDispose { player?.release() } }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text(if (note.kind == NoteKind.TASK) "Task recording" else "Voice note") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }) }) { padding ->
            Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
                Text(noteTitle(note), style = MaterialTheme.typography.headlineSmall)
                Text(DateFormat.getDateTimeInstance().format(Date(note.createdAt)), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(20.dp))
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = ::togglePlayback) { Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (isPlaying) "Pause" else "Play") }
                        Text("${formatDuration(positionMs)} / ${formatDuration(durationMs)}")
                    }
                    Slider(value = positionMs.coerceIn(0, durationMs).toFloat(), onValueChange = { target -> player?.seekTo(target.toInt()); positionMs = target.toLong() }, valueRange = 0f..durationMs.coerceAtLeast(1).toFloat())
                    Text(driveStatus(note.driveState), style = MaterialTheme.typography.labelLarge)
                    if (note.driveState == SyncState.FAILED && note.driveError != null) Text(note.driveError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                } }
                Spacer(Modifier.height(20.dp))
                Text("Transcript", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SelectionContainer { Text(noteStatus(note)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(note.transcript.orEmpty())) }, enabled = !note.transcript.isNullOrBlank()) { Text("Copy transcript") }
                    TextButton(onClick = { shareTranscript(context, note) }, enabled = !note.transcript.isNullOrBlank()) { Text("Share text") }
                    TextButton(onClick = { shareAudio(context, note) }, enabled = File(note.audioPath).isFile) { Text("Share audio") }
                }
            }
        }
    }
}

private fun shareTranscript(context: android.content.Context, note: Note) {
    val share = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TITLE, noteTitle(note))
        .putExtra(Intent.EXTRA_TEXT, note.transcript.orEmpty())
    context.startActivity(Intent.createChooser(share, "Share transcript"))
}

private fun shareAudio(context: android.content.Context, note: Note) {
    val audio = File(note.audioPath)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", audio)
    val share = Intent(Intent.ACTION_SEND)
        .setType("audio/mp4")
        .putExtra(Intent.EXTRA_TITLE, noteTitle(note))
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(share, "Share recording"))
}

private fun noteTitle(note: Note): String = note.transcript.orEmpty().lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotBlank() } ?: if (note.kind == NoteKind.TASK) "Untitled task" else "Untitled voice note"
private fun noteStatus(note: Note, modelInstalled: Boolean = true): String = when (note.transcriptionState) {
    TranscriptionState.COMPLETE -> note.transcript.orEmpty().ifBlank { "No speech detected" }
    TranscriptionState.FAILED -> "Transcription needs attention: ${note.transcriptionError}"
    TranscriptionState.QUEUED -> if (modelInstalled) "Queued for transcription…" else "Waiting for transcription model"
    else -> "${note.transcriptionState.name.lowercase().replaceFirstChar { it.uppercase() }}…"
}
private fun driveStatus(state: SyncState, driveConnected: Boolean = false): String = when (state) {
    SyncState.NOT_CONFIGURED -> if (driveConnected) "Ready to upload to Drive" else "Stored on this device"
    SyncState.PENDING -> "Uploading to Drive…"
    SyncState.SYNCED -> "Saved to Drive"
    SyncState.FAILED -> "Drive upload will retry"
}
private fun formatDuration(durationMs: Long): String {
    val totalSeconds = durationMs.coerceAtLeast(0) / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
