package app.astroexposure.ui

import app.astroexposure.domain.lens.Lens
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The all-lenses panel's facet derivation (ui-design §7.10,
 * 0.13.0): brand comes from the built-in id prefixes only, custom
 * lenses land in the custom bucket, mount facets list what the
 * library actually holds.
 */
class LensFacetsTest {

    private fun lens(id: String, mount: String, min: Double, max: Double = min) =
        Lens(id, id, mount, min, max, 2.8)

    private val library = listOf(
        lens("sony-fe-135mm", "Sony E", 135.0),
        lens("canon-rf-85mm", "Canon RF", 85.0),
        lens("nikon-z-50mm", "Nikon Z", 50.0),
        lens("sigma-85mm", "Sony E", 85.0),
        lens("tamron-35-150mm", "Sony E", 35.0, 150.0),
        lens("custom-uuid-1", "Sony E", 100.0, 400.0),
        lens("custom-uuid-2", "Pentax K", 31.0),
    )

    @Test
    fun `brand derives from the built-in id prefixes`() {
        assertEquals(LensBrand.SONY, lensBrand(lens("sony-x", "Sony E", 50.0)))
        assertEquals(LensBrand.CANON, lensBrand(lens("canon-x", "Canon RF", 50.0)))
        assertEquals(LensBrand.NIKON, lensBrand(lens("nikon-x", "Nikon Z", 50.0)))
        assertEquals(LensBrand.SIGMA, lensBrand(lens("sigma-x", "Sony E", 50.0)))
        assertEquals(LensBrand.TAMRON, lensBrand(lens("tamron-x", "Sony E", 50.0)))
    }

    @Test
    fun `custom and unknown ids land in the custom bucket`() {
        assertEquals(LensBrand.CUSTOM, lensBrand(lens("custom-uuid", "Sony E", 50.0)))
        assertEquals(LensBrand.CUSTOM, lensBrand(lens("some-other-id", "Sony E", 50.0)))
    }

    @Test
    fun `mount facets are the library's mounts, canonical three first`() {
        assertEquals(
            listOf("Sony E", "Canon RF", "Nikon Z", "Pentax K"),
            lensMountFacets(library),
        )
    }

    @Test
    fun `brand facets list only brands present, in standing order`() {
        assertEquals(
            listOf(
                LensBrand.SONY, LensBrand.CANON, LensBrand.NIKON,
                LensBrand.SIGMA, LensBrand.TAMRON, LensBrand.CUSTOM,
            ),
            lensBrandFacets(library),
        )
        assertEquals(
            listOf(LensBrand.SONY),
            lensBrandFacets(listOf(lens("sony-x", "Sony E", 50.0))),
        )
    }

    @Test
    fun `panel filter applies both facets and keeps library order`() {
        assertEquals(
            listOf("tamron-35-150mm", "sigma-85mm", "custom-uuid-1", "sony-fe-135mm"),
            filterLensPanel(library, mount = "Sony E", brand = null).map { it.id },
        )
        assertEquals(
            listOf("sigma-85mm"),
            filterLensPanel(library, mount = "Sony E", brand = LensBrand.SIGMA).map { it.id },
        )
        assertEquals(
            listOf("custom-uuid-2", "custom-uuid-1"),
            filterLensPanel(library, mount = null, brand = LensBrand.CUSTOM).map { it.id },
        )
        assertEquals(
            emptyList<String>(),
            filterLensPanel(library, mount = "Nikon Z", brand = LensBrand.TAMRON).map { it.id },
        )
    }
}
