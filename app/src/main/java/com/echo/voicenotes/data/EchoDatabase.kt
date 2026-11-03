package com.echo.voicenotes.data

import androidx.room.Database
import androidx.room.migration.Migration
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [Note::class], version = 2, exportSchema = false)
abstract class EchoDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE notes ADD COLUMN kind TEXT NOT NULL DEFAULT 'NOTE'")
            }
        }
    }
}
