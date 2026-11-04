package com.echo.voicenotes.drive

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted Android Storage Access Framework grant for the user-selected Drive folder. */
object DriveFolder {
    private const val PREFS = "drive_folder"; private const val KEY_URI = "tree_uri"
    private val mutableFolder = MutableStateFlow<Uri?>(null)
    val folder: StateFlow<Uri?> = mutableFolder.asStateFlow()

    fun get(context: Context): Uri? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URI, null)?.let(Uri::parse)
    fun load(context: Context) { mutableFolder.value = get(context) }
    fun set(context: Context, uri: Uri) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply()
        mutableFolder.value = uri
    }
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_URI).apply()
        mutableFolder.value = null
    }
}
