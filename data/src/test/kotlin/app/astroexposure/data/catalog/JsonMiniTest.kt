package app.astroexposure.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Unit tests for the in-house JSON parser (JsonMini). */
class JsonMiniTest {

    @Test
    fun `parses nested objects arrays and scalars`() {
        val root = JsonMini.parse(
            """{"name":"M31","tags":["galaxy","bright"],"mag":3.4,"count":110,"visible":true,"note":null}""",
        ) as JsonValue.Obj
        assertEquals("M31", (root.entries.getValue("name") as JsonValue.Str).value)
        val tags = root.entries.getValue("tags") as JsonValue.Arr
        assertEquals(2, tags.items.size)
        assertEquals("bright", (tags.items[1] as JsonValue.Str).value)
        assertEquals(3.4, (root.entries.getValue("mag") as JsonValue.Num).value, 1e-9)
        assertEquals(110.0, (root.entries.getValue("count") as JsonValue.Num).value, 1e-9)
        assertEquals(true, (root.entries.getValue("visible") as JsonValue.Bool).value)
        assertEquals(JsonValue.Null, root.entries.getValue("note"))
    }

    @Test
    fun `parses string escapes including unicode`() {
        val root = JsonMini.parse("""{"s":"a\"b\\c\nd\u0041\u00e9"}""") as JsonValue.Obj
        assertEquals("a\"b\\c\ndAé", (root.entries.getValue("s") as JsonValue.Str).value)
    }

    @Test
    fun `parses negative fractional and exponent numbers`() {
        val arr = JsonMini.parse("[-74.006, 1.4e2, -0.5E-2, 0]") as JsonValue.Arr
        assertEquals(-74.006, (arr.items[0] as JsonValue.Num).value, 1e-9)
        assertEquals(140.0, (arr.items[1] as JsonValue.Num).value, 1e-9)
        assertEquals(-0.005, (arr.items[2] as JsonValue.Num).value, 1e-9)
        assertEquals(0.0, (arr.items[3] as JsonValue.Num).value, 1e-9)
    }

    @Test
    fun `rejects unterminated string with position`() {
        try {
            JsonMini.parse("""{"s":"abc}""")
            fail("expected JsonParseException")
        } catch (e: JsonParseException) {
            assertTrue("message should carry a position: ${e.message}", e.message!!.contains("position"))
            assertTrue(e.position > 0)
        }
    }

    @Test
    fun `rejects trailing content after the top level value`() {
        try {
            JsonMini.parse("{} {}")
            fail("expected JsonParseException")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("trailing"))
        }
    }

    @Test
    fun `rejects trailing commas and bad literals`() {
        for (bad in listOf("[1,]", "{\"a\":tru}", "[01]", "\"bad\\x\"")) {
            try {
                JsonMini.parse(bad)
                fail("expected JsonParseException for $bad")
            } catch (e: JsonParseException) {
                // expected
            }
        }
    }

    @Test
    fun `rejects duplicate object keys`() {
        try {
            JsonMini.parse("""{"a":1,"b":2,"a":3}""")
            fail("expected JsonParseException for a duplicate key")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("duplicate"))
        }
    }

    @Test
    fun `intField rejects out of range integers instead of saturating`() {
        val big = mapOf("v" to JsonValue.Num(1e20))
        try {
            big.intField("v", "test")
            fail("expected IllegalStateException for an out-of-range integer")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("out of Int range"))
        }
        val ok = mapOf("v" to JsonValue.Num(147.0))
        assertEquals(147, ok.intField("v", "test"))
    }

    @Test
    fun `rejects nesting beyond the depth limit`() {
        val deep = "[".repeat(JsonMini.MAX_DEPTH + 10) + "]".repeat(JsonMini.MAX_DEPTH + 10)
        try {
            JsonMini.parse(deep)
            fail("expected JsonParseException for excessive nesting")
        } catch (e: JsonParseException) {
            assertTrue(e.message!!.contains("nesting"))
        }
        // Nesting within the limit still parses.
        val fine = "[".repeat(16) + "1" + "]".repeat(16)
        JsonMini.parse(fine)
    }
}
