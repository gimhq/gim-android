package im.g.apps.feature.contacts

import androidx.compose.runtime.Immutable
import im.g.apps.core.bridge.GimClient
import im.g.apps.core.bridge.GimException
import im.g.apps.core.bridge.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Result of a handle lookup, or a typed user id. */
@Immutable
sealed interface UserSearchState {
    /** The query is shorter than [MIN_HANDLE_LENGTH]. */
    data object Idle : UserSearchState

    /** Waiting for the debounce or for [GimClient.findUser]. */
    data object Searching : UserSearchState

    /** A user found by handle, or a typed user id taken as is (the hub checks it when the chat is created). */
    data class Found(val user: UserProfile) : UserSearchState

    data class NotFound(val handle: String) : UserSearchState

    data class Error(val error: ContactsError) : UserSearchState
}

internal const val SEARCH_DEBOUNCE_MS = 300L
internal const val MIN_HANDLE_LENGTH = 3

/** Lowercase handle without surrounding blanks or a leading '@'. */
internal fun normalizeHandle(input: String): String = input.trim().removePrefix("@").lowercase()

/** A gim user id: "u" + 55 base32 characters. Users are addressed by id everywhere; remote ones may lack a handle. */
private val USER_ID_PATTERN = Regex("^u[a-z2-7]{54}[aiqy]$")

internal fun isUserId(value: String): Boolean = USER_ID_PATTERN.matches(value)

/**
 * Debounced handle lookup shared by the contacts ViewModels: every distinct normalized query of at least
 * [MIN_HANDLE_LENGTH] characters is looked up [SEARCH_DEBOUNCE_MS] after the last change; a newer query
 * cancels the pending one. Results go to [onState]. Runs until [scope] is cancelled.
 */
internal class UserSearch(
    private val client: GimClient,
    scope: CoroutineScope,
    private val onState: (UserSearchState) -> Unit,
) {
    private val query = MutableStateFlow("")

    init {
        scope.launch {
            query.map(::normalizeHandle).distinctUntilChanged().collectLatest { handle ->
                if (handle.length < MIN_HANDLE_LENGTH) {
                    onState(UserSearchState.Idle)
                    return@collectLatest
                }
                onState(UserSearchState.Searching)
                delay(SEARCH_DEBOUNCE_MS)
                onState(lookUp(handle))
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text
    }

    private suspend fun lookUp(handle: String): UserSearchState = try {
        if (isUserId(handle)) {
            UserSearchState.Found(UserProfile(handle, handle = "", displayName = ""))
        } else {
            client.findUser(handle)?.let(UserSearchState::Found) ?: UserSearchState.NotFound(handle)
        }
    } catch (e: GimException) {
        UserSearchState.Error(e.toContactsError())
    }
}
