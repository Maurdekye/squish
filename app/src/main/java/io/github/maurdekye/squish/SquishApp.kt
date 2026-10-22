package io.github.maurdekye.squish

import android.app.Application
import android.util.Log

class SquishApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread {
            runCatching { ItemStore.cleanup(this) }.onFailure { Log.w("Squish", "cleanup failed", it) }
        }.start()
    }
}
