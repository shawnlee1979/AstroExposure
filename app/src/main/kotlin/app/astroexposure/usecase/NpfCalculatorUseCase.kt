package app.astroexposure.usecase

import app.astroexposure.domain.camera.Camera
import app.astroexposure.domain.model.NpfModel
import app.astroexposure.domain.strictness.Strictness
import app.astroexposure.domain.units.Degrees

/**
 * One NPF calculation, end to end: UI input -> this use case -> [NpfModel].
 * The UI displays the returned numbers and composes its explanation line from
 * them; it contains no formula of its own (DEVELOPMENT-RULES.md D.4).
 */
class NpfCalculatorUseCase {

    data class Input(
        val camera: Camera,
        val focalLengthMm: Double,
        val aperture: Double,
        val declinationDeg: Double,
        val strictness: Strictness,
    )

    data class Card(
        val maxShutterSeconds: Double,
        val ruleOf500Seconds: Double,
        val kUsed: Int,
        val pixelPitchUm: Double,
        val declinationCapped: Boolean,
    )

    fun execute(input: Input): Card {
        val result = NpfModel.evaluate(
            NpfModel.Input(
                camera = input.camera,
                focalLengthMm = input.focalLengthMm,
                aperture = input.aperture,
                declination = Degrees(input.declinationDeg),
                strictness = input.strictness,
            ),
        )
        return Card(
            maxShutterSeconds = result.maxShutter.value,
            ruleOf500Seconds = result.ruleOf500.value,
            kUsed = result.kUsed,
            pixelPitchUm = input.camera.pixelPitch.value,
            declinationCapped = result.declinationCapped,
        )
    }
}
