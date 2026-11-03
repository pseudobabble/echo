package com.echo.voicenotes.data

import android.content.Context
import androidx.room.Room
import java.io.File
import java.util.UUID

class NoteRepository private constructor(private val context: Context) {
    private val db = Room.databaseBuilder(context.applicationContext, EchoDatabase::class.java, "echo.db")
        .addMigrations(EchoDatabase.MIGRATION_1_2)
        .build()
    val notes = db.notes()
    fun newAudioFile(id: String = UUID.randomUUID().toString()): Pair<String, File> {
        val folder = File(context.filesDir, "audio").also { it.mkdirs() }
        return id to File(folder, "$id.m4a")
    }
    companion object {
        @Volatile private var instance: NoteRepository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: NoteRepository(context.applicationContext).also { instance = it }
        }
    }
}
