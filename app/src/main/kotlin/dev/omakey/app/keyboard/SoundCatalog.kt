package dev.omakey.app.keyboard

import dev.omakey.app.R
import dev.omakey.core.feedback.SoundChoices

/** Maps a persisted sound-choice id to the actual R.raw resource. Lives in the app module (not
 * core) since it needs R.raw resource ids — same split as `FontCatalog`/`FontChoices`. Unknown
 * ids fall back to the default choice's resource rather than crashing, in case a future release
 * ever removes a choice a user had previously selected. */
object SoundCatalog {
    fun resolve(soundChoice: String): Int = when (soundChoice) {
        SoundChoices.CRISP -> R.raw.click_crisp
        SoundChoices.DEEP -> R.raw.click_deep
        SoundChoices.SOFT -> R.raw.click_soft
        SoundChoices.CLASSIC -> R.raw.key_click
        else -> R.raw.click_crisp
    }

    val displayNames = listOf(
        SoundChoices.CRISP to "Crisp",
        SoundChoices.DEEP to "Deep",
        SoundChoices.SOFT to "Soft",
        SoundChoices.CLASSIC to "Classic",
    )
}
