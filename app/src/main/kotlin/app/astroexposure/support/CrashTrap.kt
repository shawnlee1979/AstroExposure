package app.astroexposure.support

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * An offline crash record (0.11.1): the app's last uncaught
 * exception, kept in the private files directory so the NEXT
 * launch can show it on the Tonight page (see CrashRecordCard)
 * and the user can read or copy the stack trace out. Nothing is
 * uploaded anywhere; the record exists only on the device until
 * the user dismisses it. The trap exists because the 0.11.0
 * framing-chart crash reproduced on the user's device but on no
 * development surface — the trace is the missing evidence.
 *
 * The writer runs inside the default uncaught-exception handler:
 * it writes synchronously, is armoured so a failure of the trap
 * itself can never crash the app a second time, and always hands
 * the exception on to the handler that was registered before it
 * (the system's), so platform crash behaviour is unchanged.
 */
object CrashTrap {

    /** The record's file name inside the app's private files directory. */
    const val FILE_NAME = "last-crash.txt"

    @Volatile
    private var installed = false

    /** The record file (whether or not a record currently exists). */
    fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    /**
     * Installs the recording handler; a second call in the same
     * process (activity recreation) is a no-op, so the handler
     * never chains onto itself. Called once, first thing in
     * MainActivity.onCreate.
     */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val recordFile = file(context)
        val versionLabel = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            // longVersionCode exists only from API 28; the plain
            // versionCode serves below that (minSdk is 26).
            val code = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            "${info.versionName} (versionCode $code)"
        }.getOrDefault("unknown version")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                recordFile.writeText(
                    recordText(versionLabel, Instant.now(), thread.name, trace),
                )
            } catch (ignored: Throwable) {
                // Deliberately swallowed: the trap must never take
                // the app down with it — a failed write simply
                // leaves no record behind.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * The record's text for one crash: header (app name and
     * version label), the crash instant, the thread name, a blank
     * line, then the stack trace verbatim. A pure function of its
     * inputs so the format is unit-testable without a Context; the
     * handler above passes the live values and writes the result
     * unchanged.
     */
    internal fun recordText(
        versionLabel: String,
        time: Instant,
        threadName: String,
        trace: String,
    ): String =
        "AstroExposure $versionLabel\n" +
            "time: $time\n" +
            "thread: $threadName\n\n" +
            trace

    /** The recorded crash text, or null when no record exists. */
    fun read(context: Context): String? =
        file(context).takeIf { it.isFile }?.let { record ->
            // A record that cannot be read is treated as no record:
            // the card is a courtesy surface, never a new failure.
            runCatching { record.readText() }.getOrNull()
        }

    /** Deletes the record (the card's dismiss action). */
    fun clear(context: Context) {
        file(context).delete()
    }
}
