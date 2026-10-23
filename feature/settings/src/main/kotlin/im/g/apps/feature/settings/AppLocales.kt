package im.g.apps.feature.settings

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/** The per-app language: a BCP 47 language tag, or null to follow the phone language. */
interface AppLocales {
    fun current(): String?

    fun set(tag: String?)
}

/**
 * Per-app language through AppCompat: the platform setting on API 33+, AppCompat's own storage below (the
 * manifest's `AppLocalesMetadataHolderService` with `autoStoreLocales`). Setting it recreates the activities.
 */
class AppCompatAppLocales : AppLocales {
    override fun current(): String? = AppCompatDelegate.getApplicationLocales()[0]?.toLanguageTag()

    override fun set(tag: String?) {
        AppCompatDelegate.setApplicationLocales(
            if (tag == null) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }
}

/** An [AppLocales] that only remembers the tag (tests). */
class InMemoryAppLocales(private var tag: String? = null) : AppLocales {
    override fun current(): String? = tag

    override fun set(tag: String?) {
        this.tag = tag
    }
}

/** The selectable language tags, from `R.array.settings_language_tags`. */
fun supportedLanguageTags(context: Context): List<String> =
    context.resources.getStringArray(R.array.settings_language_tags).toList()

/** [tag]'s language named in that language, e.g. "English", "Español". */
fun languageDisplayName(tag: String): String {
    val locale = Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
