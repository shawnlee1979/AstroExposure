package app.astroexposure.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.astroexposure.R
import app.astroexposure.domain.catalog.ConstellationNames
import app.astroexposure.usecase.TonightOverviewUseCase

/**
 * Presentation of tonight subjects (ui-design §7.8): the ONE place
 * lane labels, board rows, popup rows and detail dialogs resolve a
 * subject's localised name and category wording from, so the four
 * surfaces can never name the same entry differently.
 */

/** The subject's display name in the UI language. */
@Composable
fun TonightOverviewUseCase.TonightSubject.displayName(): String {
    val english = isEnglishUiLanguage()
    return when (this) {
        is TonightOverviewUseCase.TonightSubject.DeepSky ->
            obj.nameZh ?: obj.name
        is TonightOverviewUseCase.TonightSubject.Constellation ->
            ConstellationNames.displayName(entry.code, english)
        is TonightOverviewUseCase.TonightSubject.Viewpoint ->
            if (english) viewpoint.nameEn else viewpoint.nameZh
        is TonightOverviewUseCase.TonightSubject.Radiant ->
            if (english) shower.nameEn else shower.nameZh
    }
}

/**
 * The subject's lane label: the display name, with the radiant
 * suffix for a shower (a lane shows the radiant, not the shower
 * alone). Category tags are NOT part of the label — callers that
 * draw one compose it from [categoryTag].
 */
@Composable
fun TonightOverviewUseCase.TonightSubject.laneLabel(): String {
    val name = displayName()
    return if (this is TonightOverviewUseCase.TonightSubject.Radiant) {
        stringResource(R.string.lane_radiant_label, name)
    } else {
        name
    }
}

/** The short category tag drawn on lanes and board rows (text, never colour alone). */
@Composable
fun TonightOverviewUseCase.SubjectCategory.categoryTag(): String = stringResource(
    when (this) {
        TonightOverviewUseCase.SubjectCategory.DEEP_SKY -> R.string.category_deep_sky
        TonightOverviewUseCase.SubjectCategory.CONSTELLATION -> R.string.category_constellation
        TonightOverviewUseCase.SubjectCategory.WIDE_FIELD -> R.string.category_wide_field
        TonightOverviewUseCase.SubjectCategory.METEOR -> R.string.category_meteor_tag
    },
)

/** The category's full name, for the filter chips and the filter fact lines. */
@Composable
fun TonightOverviewUseCase.SubjectCategory.categoryName(): String = stringResource(
    when (this) {
        TonightOverviewUseCase.SubjectCategory.DEEP_SKY -> R.string.category_deep_sky
        TonightOverviewUseCase.SubjectCategory.CONSTELLATION -> R.string.category_constellation
        TonightOverviewUseCase.SubjectCategory.WIDE_FIELD -> R.string.category_wide_field
        TonightOverviewUseCase.SubjectCategory.METEOR -> R.string.category_meteor
    },
)
