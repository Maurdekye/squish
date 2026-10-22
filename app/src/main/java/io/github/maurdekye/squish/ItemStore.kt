package io.github.maurdekye.squish

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.Properties
import java.util.UUID

/** A squished file and the original it came from, kept in the cache for [SquishProvider] to serve. */
class Item(
    val id: String,
    val dir: File,
    val source: Uri,
    val originalName: String,
    val originalMime: String,
    val originalSize: Long,
    val squishedName: String,
    val squishedMime: String,
) {
    val squishedFile: File get() = File(dir, SQUISHED)

    /** A byte-for-byte copy of the original, made only when access to the source couldn't be kept. */
    val originalCopy: File get() = File(dir, ORIGINAL)

    companion object {
        const val SQUISHED = "squished"
        const val ORIGINAL = "original"
    }
}

object ItemStore {
    private const val TAG = "Squish"
    private const val META = "meta"
    private const val MAX_AGE_MS = 2L * 24 * 60 * 60 * 1000
    private val ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    private fun root(context: Context) = File(context.cacheDir, "items")

    fun authority(context: Context) = "${context.packageName}.files"

    fun newDir(context: Context): Pair<String, File> {
        val id = UUID.randomUUID().toString()
        val dir = File(root(context), id)
        check(dir.mkdirs()) { "couldn't create $dir" }
        return id to dir
    }

    fun save(item: Item) {
        val p = Properties()
        p["source"] = item.source.toString()
        p["originalName"] = item.originalName
        p["originalMime"] = item.originalMime
        p["originalSize"] = item.originalSize.toString()
        p["squishedName"] = item.squishedName
        p["squishedMime"] = item.squishedMime
        val tmp = File(item.dir, "$META.tmp")
        tmp.outputStream().use { p.store(it, null) }
        check(tmp.renameTo(File(item.dir, META))) { "couldn't save ${item.id}" }
    }

    fun load(context: Context, id: String): Item? {
        if (!ID.matches(id)) return null
        val dir = File(root(context), id)
        val meta = File(dir, META)
        if (!meta.isFile) return null
        return runCatching {
            val p = Properties().apply { meta.inputStream().use { load(it) } }
            Item(
                id = id,
                dir = dir,
                source = Uri.parse(p.getProperty("source")),
                originalName = p.getProperty("originalName"),
                originalMime = p.getProperty("originalMime"),
                originalSize = p.getProperty("originalSize").toLong(),
                squishedName = p.getProperty("squishedName"),
                squishedMime = p.getProperty("squishedMime"),
            )
        }.onFailure { Log.w(TAG, "bad item $id", it) }.getOrNull()
    }

    /**
     * The URI handed to other apps. Plain URIs serve the squished file to Discord and the original
     * to everyone else; [alwaysSquished] URIs are for our own previews.
     */
    fun uri(context: Context, item: Item, alwaysSquished: Boolean = false): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority(context))
        .appendPath(if (alwaysSquished) SquishProvider.PATH_SQUISHED else SquishProvider.PATH_ITEM)
        .appendPath(item.id)
        .appendPath(item.squishedName)
        .build()

    /** Deletes items older than two days and lets go of source grants nothing needs anymore. */
    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val stillNeeded = mutableSetOf<String>()
        root(context).listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > MAX_AGE_MS) {
                dir.deleteRecursively()
            } else {
                load(context, dir.name)?.let { stillNeeded += it.source.toString() }
            }
        }
        val resolver = context.contentResolver
        for (permission in resolver.persistedUriPermissions) {
            if (permission.uri.toString() !in stillNeeded) {
                runCatching { resolver.releasePersistableUriPermission(permission.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
    }
}
