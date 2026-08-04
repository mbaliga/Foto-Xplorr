package com.fotoxplorr.app

import android.app.Application
import dev.aarso.crashrecovery.CrashRecovery

/**
 * Installs Hyle's optional dev.aarso:crash-recovery utility (own Gradle module, zero
 * dependency on :hyle, plain android.widget views) -- captures a device-only launch/runtime
 * crash and shows a recovery screen on the next launch instead of the app's real content.
 * See FotoXplorrActivity.onCreate() for the matching maybeShowRecovery() call.
 */
class FotoXplorrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashRecovery.install(this, appLabel = "Foto Xplorr")
    }
}
