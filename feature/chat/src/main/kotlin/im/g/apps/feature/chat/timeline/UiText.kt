package im.g.apps.feature.chat.timeline

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Text chosen by the ViewModel and resolved against resources at display time. */
@Immutable
sealed interface UiText {
    data class Raw(val value: String) : UiText

    data class Res(@StringRes val id: Int, val args: ImmutableList<String> = persistentListOf()) : UiText

    /** Plural whose only format argument is [count]. */
    data class Plural(@PluralsRes val id: Int, val count: Int) : UiText
}

@Composable
fun UiText.asString(): String = when (this) {
    is UiText.Raw -> value
    is UiText.Res -> stringResource(id, *args.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, count)
}
