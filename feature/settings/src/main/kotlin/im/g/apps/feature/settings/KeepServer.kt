package im.g.apps.feature.settings

import android.content.Context
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The user's keep server choice: the build default, off, or their own server. */
sealed interface KeepServerChoice {
    data object Default : KeepServerChoice

    data object Off : KeepServerChoice

    data class Custom(val url: String) : KeepServerChoice
}

/** The keep server URL for this choice; [buildDefault] is null when the build turns the keep server off. */
fun KeepServerChoice.resolve(buildDefault: String?): String? = when (this) {
    KeepServerChoice.Default -> buildDefault
    KeepServerChoice.Off -> null
    is KeepServerChoice.Custom -> url
}

/** Holds the [KeepServerChoice]. */
interface KeepServerStore {
    val choice: StateFlow<KeepServerChoice>

    fun set(choice: KeepServerChoice)
}

/** Persists the choice in the [PREFS] shared preferences, which sign-out does not wipe. */
class SharedPrefsKeepServerStore(context: Context) : KeepServerStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _choice = MutableStateFlow(read())
    override val choice: StateFlow<KeepServerChoice> = _choice.asStateFlow()

    override fun set(choice: KeepServerChoice) {
        val editor = prefs.edit()
        when (choice) {
            KeepServerChoice.Default -> editor.remove(KEY)
            KeepServerChoice.Off -> editor.putString(KEY, OFF)
            is KeepServerChoice.Custom -> editor.putString(KEY, choice.url)
        }
        editor.apply()
        _choice.value = choice
    }

    private fun read(): KeepServerChoice = when (val stored = prefs.getString(KEY, null)) {
        null -> KeepServerChoice.Default
        OFF -> KeepServerChoice.Off
        else -> KeepServerChoice.Custom(stored)
    }

    companion object {
        const val PREFS = SharedPrefsThemeModeStore.PREFS
        const val KEY = "keep_server"
        private const val OFF = "off"
    }
}

/** A [KeepServerStore] that keeps the choice in memory only (tests). */
class InMemoryKeepServerStore(initial: KeepServerChoice = KeepServerChoice.Default) : KeepServerStore {
    private val _choice = MutableStateFlow(initial)
    override val choice: StateFlow<KeepServerChoice> = _choice.asStateFlow()

    override fun set(choice: KeepServerChoice) {
        _choice.value = choice
    }
}

/** Why a keep server address was refused. */
enum class KeepServerUrlError {
    /** Not an `http`/`https` address with a host. */
    INVALID,

    /** Plain `http` outside the local network. */
    INSECURE,
}

/** [input] without surrounding spaces and a trailing slash. */
fun normalizeKeepServerUrl(input: String): String = input.trim().removeSuffix("/")

/** Null when [url] can be used: `https`, or `http` for localhost, private IPv4 addresses and `.local` names. */
fun validateKeepServerUrl(url: String): KeepServerUrlError? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return KeepServerUrlError.INVALID
    }
    val host = uri.host?.lowercase()
    if (host.isNullOrEmpty()) return KeepServerUrlError.INVALID
    return when (uri.scheme?.lowercase()) {
        "https" -> null
        "http" -> if (isLocalNetworkHost(host)) null else KeepServerUrlError.INSECURE
        else -> KeepServerUrlError.INVALID
    }
}

private fun isLocalNetworkHost(host: String): Boolean {
    if (host == "localhost" || host.endsWith(".local") || host == "[::1]") return true
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return when (octets[0]) {
        10, 127 -> true
        172 -> octets[1] in 16..31
        192 -> octets[1] == 168
        else -> false
    }
}
