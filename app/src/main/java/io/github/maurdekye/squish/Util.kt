package io.github.maurdekye.squish

import android.content.ClipData
import android.content.ClipDescription
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ext.SdkExtensions
import android.provider.MediaStore
import java.util.Locale

object Formats {
    fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }
}

object MediaPick {
    /** Discord takes at most 10 attachments per message. */
    const val DISCORD_MAX_FILES = 10

    fun photoPickerAvailable(): Boolean = Build.VERSION.SDK_INT >= 33 ||
        (Build.VERSION.SDK_INT >= 30 && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R) >= 2)

    fun maxPickable(): Int = if (Build.VERSION.SDK_INT >= 33) MediaStore.getPickImagesMaxLimit() else DISCORD_MAX_FILES

    // The system photo picker, or the Files screen where there is none.
    // filter is "image/*", "video/*", or null for both.
    fun mediaIntent(filter: String?, maxItems: Int): Intent =
        if (photoPickerAvailable()) {
            Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                if (filter != null) type = filter
                if (maxItems > 1) putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxItems.coerceAtMost(maxPickable()))
            }
        } else {
            documentIntent(filter ?: "*/*", if (filter == null) arrayOf("image/*", "video/*") else null, maxItems > 1)
        }

    fun documentIntent(type: String, mimeTypes: Array<String>?, multiple: Boolean): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            this.type = type
            if (mimeTypes != null) putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple)
        }

    fun urisOf(data: Intent): List<Uri> {
        val clip = data.clipData
        val uris = if (clip != null && clip.itemCount > 0) {
            (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        } else {
            listOfNotNull(data.data)
        }
        return uris.distinct()
    }

    fun clipOf(label: String, uris: List<Uri>, mimes: List<String>): ClipData {
        val clip = ClipData(ClipDescription(label, mimes.distinct().toTypedArray()), ClipData.Item(uris[0]))
        for (uri in uris.drop(1)) clip.addItem(ClipData.Item(uri))
        return clip
    }
}
