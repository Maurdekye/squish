package io.github.maurdekye.squish

/** Who asked for the files decides whether they get squished. */
enum class Mode {
    /** Discord asked directly: squish whatever is over the limit. */
    DISCORD,

    /**
     * Android's Files screen relayed the request, so the app that will read the files is unknown.
     * Squish anyway; [SquishProvider] serves the squished copy only to Discord and the original to
     * everyone else.
     */
    UNKNOWN,

    /** Some other app asked directly: hand back the originals untouched. */
    OTHER,
}

object Callers {
    const val DISCORD = "com.discord"

    /** System pickers that start Squish on another app's behalf and pass our result back to it. */
    private val RELAYS = setOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.photopicker",
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
    )

    fun modeFor(callingPackage: String?): Mode = when {
        callingPackage == DISCORD -> Mode.DISCORD
        callingPackage == null || callingPackage in RELAYS -> Mode.UNKNOWN
        else -> Mode.OTHER
    }
}
