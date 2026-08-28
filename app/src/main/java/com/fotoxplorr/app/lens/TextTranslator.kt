package com.fotoxplorr.app.lens

/**
 * On-device translation, behind the same kind of seam as
 * [com.fotoxplorr.app.ai.RemoteAiBridge]: one interface here, in `src/main`, and a
 * same-fully-qualified-name implementation class per build flavor (`AppTextTranslator`, in
 * `src/connect/` and `src/offline/`) that the compiler picks by whichever source set the
 * variant actually includes -- no factory, no reflection, no runtime flag that could fall out of
 * sync with what got compiled in.
 *
 * The reason this needs a seam at all, unlike [android.speech.tts.TextToSpeech] or the
 * clipboard, is what backs the real implementation: ML Kit's Translate API downloads a language
 * model over the network the first time a language pair is used -- tens of megabytes; see the
 * connect implementation's own KDoc for the figure -- which makes `com.google.mlkit:translate` a
 * network-capable library exactly like OkHttp or MapLibre are. The `offline` flavor's Gradle
 * gates fail the build if any such library reaches its RESOLVED classpath at all (see
 * `verifyOfflineRuntimeClasspath` in `app/build.gradle.kts`), so the dependency is declared
 * `connectImplementation`-only, and this interface -- which mentions no ML Kit type anywhere --
 * is what lets [LensCard] be written once against a type that compiles cleanly in a flavor that
 * cannot see the artifact at all.
 */
interface TextTranslator {

    /**
     * False in the offline flavor, always. The card asks this rather than reading a
     * `BuildConfig` flag directly, so the answer and the actual behaviour cannot disagree --
     * the same reasoning [com.fotoxplorr.app.ai.RemoteAiBridge.available] documents for the
     * BYOK network seam this one is modelled on.
     */
    val available: Boolean

    /**
     * Translate [text] from [sourceLanguageTag] to [targetLanguageTag] (both
     * [BCP 47](https://www.rfc-editor.org/rfc/bcp/bcp47.txt) tags, e.g. `"en"`, `"fr-CA"`).
     *
     * [sourceLanguageTag] defaults to [DEFAULT_SOURCE_LANGUAGE_TAG] rather than being detected,
     * because language DETECTION is a separate ML Kit artifact (`com.google.mlkit:language-id`)
     * that this feature deliberately does not add -- the negotiated network-capable surface for
     * this feature is `com.google.mlkit:translate` alone, not a second library that would need
     * its own offline-classpath justification. Text photographed in a language other than the
     * default will therefore often translate wrong, or not at all; that is a real, KNOWN
     * limitation of the default, not a silently wrong guess -- a caller with a better idea of the
     * source language (a future language-tagged OCR result, say) should simply pass one.
     */
    suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String = DEFAULT_SOURCE_LANGUAGE_TAG,
    ): Result<Translation>

    companion object {
        const val DEFAULT_SOURCE_LANGUAGE_TAG: String = "en"
    }
}

/** One successful translation. */
data class Translation(
    val text: String,
    val sourceLanguageTag: String,
    val targetLanguageTag: String,
)

/**
 * The typed "this build/device/language cannot translate" failure -- the [TextTranslator]
 * counterpart to [com.fotoxplorr.app.ai.RemoteAiUnavailableException]. A distinct type so
 * [LensCard] can tell "this is an expected, explainable unavailability" (offline build, no model
 * for this language pair, no network to fetch one) from some other, genuinely unexpected
 * failure, and word the card's message accordingly rather than showing a raw exception message
 * for both alike.
 */
class TranslatorUnavailableException(message: String) : Exception(message)
