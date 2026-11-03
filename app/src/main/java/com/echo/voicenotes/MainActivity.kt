package com.echo.voicenotes

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echo.voicenotes.data.NoteRepository
import com.echo.voicenotes.data.TranscriptionState
import com.echo.voicenotes.recording.RecordingService
import com.echo.voicenotes.recording.RecordShortcutPublisher
import com.echo.voicenotes.drive.DriveFolder
import com.echo.voicenotes.drive.DriveSyncScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) startRecording() }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) RecordShortcutPublisher.publish(this) }
    private val driveFolderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data ?: return@registerForActivityResult
        val flags = result.data!!.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        contentResolver.takePersistableUriPermission(uri, flags); DriveFolder.set(this, uri)
        CoroutineScope(Dispatchers.IO).launch { NoteRepository.get(this@MainActivity).notes.completedIds().forEach { DriveSyncScheduler.enqueue(this@MainActivity, it) } }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else RecordShortcutPublisher.publish(this)
        setContent { EchoApp(::requestRecording, ::chooseDriveFolder) }
    }
    private fun requestRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    private fun startRecording() = ContextCompat.startForegroundService(this, Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START))
    private fun chooseDriveFolder() = driveFolderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
}

@Composable private fun EchoApp(onRecord: () -> Unit, onConnectDrive: () -> Unit) {
    val repo = remember { NoteRepository.get(androidx.compose.ui.platform.LocalContext.current) }
    val notes by repo.notes.observeAll().collectAsStateWithLifecycle(emptyList())
    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("Echo") }, actions = { TextButton(onClick = onConnectDrive) { Text("Drive") } }) }, floatingActionButton = { FloatingActionButton(onClick = onRecord) { Text("Record") } }) { padding ->
            if (notes.isEmpty()) Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text("Record a voice note to begin") }
            else LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(notes, key = { it.id }) { note -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                    Text(DateFormat.getDateTimeInstance().format(Date(note.createdAt)), style = MaterialTheme.typography.titleMedium)
                    Text(when (note.transcriptionState) { TranscriptionState.COMPLETE -> note.transcript.orEmpty(); TranscriptionState.FAILED -> "Transcription needs attention: ${note.transcriptionError}"; else -> "${note.transcriptionState.name.lowercase().replaceFirstChar { it.uppercase() }}…" })
                    Text("Audio retained on this device", style = MaterialTheme.typography.labelSmall)
                } } }
            }
        }
    }
}
