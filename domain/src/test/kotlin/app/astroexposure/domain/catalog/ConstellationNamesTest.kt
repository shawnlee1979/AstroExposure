package app.astroexposure.domain.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The constellation code → name table (ui-design §7.5): the catalogue
 * stores IAU three-letter codes, the UI shows localized names. The
 * canonical 88 codes below are the IAU list itself; the shipped
 * catalogue's codes are a subset of it, so full coverage here covers
 * every code the catalogue can present.
 */
class ConstellationNamesTest {

    /** The 88 IAU constellation codes, in the IAU's alphabetical order. */
    private val iauCodes = listOf(
        "And", "Ant", "Aps", "Aql", "Aqr", "Ara", "Ari", "Aur", "Boo", "Cae",
        "Cam", "Cap", "Car", "Cas", "Cen", "Cep", "Cet", "Cha", "Cir", "CMa",
        "CMi", "Cnc", "Col", "Com", "CrA", "CrB", "Crt", "Cru", "Crv", "CVn",
        "Cyg", "Del", "Dor", "Dra", "Equ", "Eri", "For", "Gem", "Gru", "Her",
        "Hor", "Hya", "Hyi", "Ind", "Lac", "Leo", "Lep", "Lib", "LMi", "Lup",
        "Lyn", "Lyr", "Men", "Mic", "Mon", "Mus", "Nor", "Oct", "Oph", "Ori",
        "Pav", "Peg", "Per", "Phe", "Pic", "PsA", "Psc", "Pup", "Pyx", "Ret",
        "Scl", "Sco", "Sct", "Ser", "Sex", "Sge", "Sgr", "Tau", "Tel", "TrA",
        "Tri", "Tuc", "UMa", "UMi", "Vel", "Vir", "Vol", "Vul",
    )

    @Test
    fun canonicalListReallyHas88Codes() {
        assertEquals(88, iauCodes.size)
        assertEquals(88, iauCodes.toSet().size)
    }

    @Test
    fun everyIauCodeHasANameInBothLanguages() {
        assertEquals(iauCodes.toSet(), ConstellationNames.codes)
        for (code in iauCodes) {
            val zh = ConstellationNames.nameOrNull(code, english = false)
            val en = ConstellationNames.nameOrNull(code, english = true)
            assertNotNull("no Chinese name for $code", zh)
            assertNotNull("no English name for $code", en)
            assertTrue("blank Chinese name for $code", zh!!.isNotBlank())
            assertTrue("blank English name for $code", en!!.isNotBlank())
        }
    }

    @Test
    fun andromedaMapsToBothNames() {
        assertEquals("仙女座", ConstellationNames.displayName("And", english = false))
        assertEquals("Andromeda", ConstellationNames.displayName("And", english = true))
    }

    @Test
    fun unknownCodeFallsBackToTheCodeItself() {
        assertNull(ConstellationNames.nameOrNull("Xyz", english = false))
        assertEquals("Xyz", ConstellationNames.displayName("Xyz", english = false))
        assertEquals("Xyz", ConstellationNames.displayName("Xyz", english = true))
        // A full name stored where a code was expected is not a code:
        // it passes through unchanged instead of being blanked.
        assertEquals(
            "Andromeda",
            ConstellationNames.displayName("Andromeda", english = false),
        )
    }
}
