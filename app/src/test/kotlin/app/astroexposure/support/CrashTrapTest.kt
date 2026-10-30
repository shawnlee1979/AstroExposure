package app.astroexposure.support

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The crash record's text format (0.11.1), assembled by the pure
 * [CrashTrap.recordText] since the pre-release revision of
 * 0.11.2: header, instant, thread, blank line, then the trace
 * verbatim — byte for byte what the handler has always written.
 */
class CrashTrapTest {

    @Test
    fun recordTextAssemblesHeaderTimeThreadAndTrace() {
        val trace = "java.lang.IllegalArgumentException: maxWidth(-55) must be >= than minWidth(0)\n" +
            "\tat app.astroexposure.ui.FramingChartSectionKt.FramingChartCanvas(FramingChartSection.kt:879)\n"
        assertEquals(
            "AstroExposure 0.11.1 (versionCode 35)\n" +
                "time: 2026-10-30T12:55:14.133321Z\n" +
                "thread: main\n" +
                "\n" +
                trace,
            CrashTrap.recordText(
                versionLabel = "0.11.1 (versionCode 35)",
                time = Instant.parse("2026-10-30T12:55:14.133321Z"),
                threadName = "main",
                trace = trace,
            ),
        )
    }

    @Test
    fun recordTextKeepsAnEmptyTraceVerbatim() {
        assertEquals(
            "AstroExposure unknown version\n" +
                "time: 1970-01-01T00:00:00Z\n" +
                "thread: bg\n" +
                "\n",
            CrashTrap.recordText("unknown version", Instant.EPOCH, "bg", ""),
        )
    }
}
