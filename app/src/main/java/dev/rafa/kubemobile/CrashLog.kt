package dev.rafa.kubemobile

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Keeps the stack trace of the last uncaught exception on disk so it can be shown and copied from
 * Settings after the next launch. The app ships outside Play, so there is no crash console; this is
 * the only way a user can hand over what actually went wrong. Nothing leaves the device unless the
 * user copies it.
 */
object CrashLog {

    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(appContext, previous))
    }

    fun read(context: Context): String? =
        runCatching { file(context).takeIf { it.isFile }?.readText() }.getOrNull()?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private class Handler(
        private val context: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                file(context).writeText(
                    buildString {
                        appendLine("KubeDeck ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Time ${Instant.now()}, thread ${thread.name}")
                        appendLine()
                        append(trace.toString())
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
