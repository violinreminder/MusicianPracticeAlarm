package com.violinnagger.app

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Saves the last crash so the app can show it on screen the next time it opens. */
object CrashLog {
    private var installed = false

    private fun file(c: Context) = File(c.applicationContext.filesDir, "last_crash.txt")

    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(app, e)
            old?.uncaughtException(t, e)
        }
    }

    fun text(e: Throwable): String {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val head = "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), " +
            Build.MANUFACTURER + " " + Build.MODEL + "\n"
        return head + sw.toString().take(3500)
    }

    fun save(c: Context, e: Throwable) {
        try {
            file(c).writeText(text(e))
        } catch (x: Exception) {
        }
    }

    fun read(c: Context): String? {
        return try {
            val f = file(c)
            if (f.exists()) f.readText() else null
        } catch (x: Exception) {
            null
        }
    }

    fun clear(c: Context) {
        try {
            file(c).delete()
        } catch (x: Exception) {
        }
    }
}
