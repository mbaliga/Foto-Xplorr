package com.fotoxplorr.app.lens

/**
 * The offline flavor's translator: there is no model to run, on this device or any other,
 * because this build never downloads one.
 *
 * [TextTranslator]'s own KDoc explains WHY the interface has to exist at all for this to compile
 * cleanly with no `com.google.mlkit:translate` on this flavor's classpath; this class is the
 * other half of that seam, at the same fully-qualified name as the connect implementation
 * (`app/src/connect/java/com/fotoxplorr/app/lens/AppTextTranslator.kt`). ML Kit Translate
 * downloads its language models over the network on first use -- there is no bundled,
 * no-network variant of it the way `com.google.mlkit:text-recognition` (which THIS index itself
 * runs) has, unlike the face/label/text models `app/build.gradle.kts` already documents as
 * bundled specifically so they need no network -- so a translator genuinely cannot exist in a
 * build with no network capability at all. That is not a gap to work around; it is what
 * "offline" means, honestly reported.
 *
 * [LensCard] turns [available] = false into a "Translate with…" hand-off
 * ([LensHandoff.translateIntent], `ACTION_PROCESS_TEXT`/`ACTION_SEND`) to whatever translator app
 * the person has installed -- which needs no `INTERNET` permission of THIS app's own, because
 * firing an implicit intent is not a network call this process makes; whatever app the chooser
 * resolves to does its own thing under its own permissions. [LensHandoff]'s own KDoc has the
 * full reasoning, and the offline manifest already relies on the identical distinction for the
 * app's existing share sheet.
 */
class AppTextTranslator : TextTranslator {

    override val available: Boolean = false

    override suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String,
    ): Result<Translation> = Result.failure(
        TranslatorUnavailableException(
            "This is the offline build of Foto Xplorr — translation models are fetched over " +
                "the network the first time a language is used, and this build has no network " +
                "capability at all to fetch one. Use \"Translate with…\" instead.",
        ),
    )
}
