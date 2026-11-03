package com.echo.voicenotes.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Note::class], version = 1, exportSchema = false)
abstract class EchoDatabase : RoomDatabase() { abstract fun notes(): NoteDao }
