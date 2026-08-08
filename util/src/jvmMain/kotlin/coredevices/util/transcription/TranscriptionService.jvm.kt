package coredevices.util.transcription

import java.util.Locale

actual val SpokenLanguageOptions: List<Pair<String, String>> by lazy {
    Locale.getISOLanguages().mapNotNull { code ->
        val locale = Locale.forLanguageTag(code)
        locale.displayLanguage.takeIf { it.isNotBlank() }?.let { code to it }
    }.sortedBy { it.second }
}
