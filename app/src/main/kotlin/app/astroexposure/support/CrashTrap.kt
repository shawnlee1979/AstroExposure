package app.astroexposure.support

import android.content.Context
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
            "${info.versionName} (versionCode ${info.longVersionCode})"
        }.getOrDefault("unknown version")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = StringWriter().also { writer ->
                    throwable.printStackTrace(PrintWriter(writer))
                }.toString()
                recordFile.writeText(
                    "AstroExposure $versionLabel\n" +
                        "time: ${Instant.now()}\n" +
                        "thread: ${thread.name}\n\n" +
                        trace,
                )
            } catch (ignored: Throwable) {
                // Deliberately swallowed: the trap must never take
                // the app down with it — a failed write simply
                // leaves no record behind.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

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
