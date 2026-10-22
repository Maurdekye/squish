package io.github.maurdekye.squish

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.FileNotFoundException

/**
 * Serves squished files. Which bytes a reader gets depends on who is reading: Discord gets the
 * squished copy, any other app gets the original. That matters when Android's Files screen relays
 * the pick, because then Squish can't tell which app asked.
 */
class SquishProvider : ContentProvider() {

    override fun onCreate() = true

    private class Target(val item: Item, val squished: Boolean)

    private val logged: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun resolve(uri: Uri): Target? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val item = ItemStore.load(context ?: return null, segments[1]) ?: return null
        val reader = callingPackage
        val squished = when (segments[0]) {
            PATH_SQUISHED -> true
            PATH_ITEM -> reader == Callers.DISCORD
            else -> return null
        }
        // Readers query and open the same file many times; log each reader once.
        if (logged.add("${item.id} $reader")) Log.i(TAG, "${item.squishedName}: reader=$reader squished=$squished")
        return Target(item, squished)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val target = resolve(uri) ?: return null
        val item = target.item
        val name = if (target.squished) item.squishedName else item.originalName
        val size = if (target.squished) item.squishedFile.length() else item.originalSize
        val mime = if (target.squished) item.squishedMime else item.originalMime
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> name
                OpenableColumns.SIZE -> size.takeIf { it >= 0 }
                MediaStore.MediaColumns.MIME_TYPE -> mime
                else -> null
            }
        }
        return MatrixCursor(columns, 1).apply { addRow(row) }
    }

    override fun getType(uri: Uri): String? {
        val target = resolve(uri) ?: return null
        return if (target.squished) target.item.squishedMime else target.item.originalMime
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val target = resolve(uri) ?: throw FileNotFoundException(uri.toString())
        val item = target.item
        return when {
            target.squished -> ParcelFileDescriptor.open(item.squishedFile, ParcelFileDescriptor.MODE_READ_ONLY)
            item.originalCopy.isFile -> ParcelFileDescriptor.open(item.originalCopy, ParcelFileDescriptor.MODE_READ_ONLY)
            else -> context!!.contentResolver.openFileDescriptor(item.source, "r")
                ?: throw FileNotFoundException("original is gone: ${item.originalName}")
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        private const val TAG = "Squish"
        const val PATH_ITEM = "i"
        const val PATH_SQUISHED = "s"
    }
}
