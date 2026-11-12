package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.UserProfile

/** What the Account ID field holds. Users are found by Account ID only: hubs keep no user directory. */
@Immutable
sealed interface UserSearchState {
    /** The field is blank. */
    data object Idle : UserSearchState

    /** An Account ID, taken as is (the hub checks it when the chat is created or the member added). */
    data class Found(val user: UserProfile) : UserSearchState

    /** The text holds no Account ID. */
    data object NotAccountId : UserSearchState
}

/**
 * The Account ID field shared by the contacts ViewModels: every change of the text is parsed with [findAccountId]
 * (so a pasted ID with blanks, upper case or inside a link works) and the result goes to [onState].
 */
internal class UserSearch(private val onState: (UserSearchState) -> Unit) {
    fun onQueryChange(text: String) {
        onState(
            when {
                text.isBlank() -> UserSearchState.Idle
                else -> findAccountId(text)?.let { UserSearchState.Found(UserProfile(it, displayName = "")) }
                    ?: UserSearchState.NotAccountId
            },
        )
    }
}
