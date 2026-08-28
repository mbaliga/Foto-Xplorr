package com.fotoxplorr.app.lens

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation as MlKitTranslation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The connect flavor's translator: real ML Kit Translate, downloading whatever language model it
 * needs the first time a given language pair is used.
 *
 * COST, so a reviewer never has to go find ML Kit's own numbers: an ML Kit translate model is
 * roughly 30 MB, downloaded once per source/target pair and cached under this app's private
 * storage after that -- the second translation between the same two languages is instant and
 * needs no network at all. This IS the network-capable behaviour [TextTranslator]'s own KDoc
 * explains is why this class exists only here, at this exact fully-qualified name, in the
 * `connect` source set: `offline` compiles against the identical interface with none of this,
 * and `com.google.mlkit:translate` is declared `connectImplementation`-only in
 * `app/build.gradle.kts` specifically so it can never reach `offline`'s resolved classpath.
 */
class AppTextTranslator : TextTranslator {

    override val available: Boolean = true

    override suspend fun translate(
        text: String,
        targetLanguageTag: String,
        sourceLanguageTag: String,
    ): Result<Translation> {
        val targetCode = TranslateLanguage.fromLanguageTag(targetLanguageTag)
        val sourceCode = TranslateLanguage.fromLanguageTag(sourceLanguageTag)
        if (targetCode == null || sourceCode == null) {
            val badTag = if (targetCode == null) targetLanguageTag else sourceLanguageTag
            return Result.failure(
                TranslatorUnavailableException(
                    "Foto Xplorr's on-device translator has no model for \"$badTag\".",
                ),
            )
        }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceCode)
            .setTargetLanguage(targetCode)
            .build()
        val translator = MlKitTranslation.getClient(options)
        return try {
            withTimeout(TRANSLATE_TIMEOUT_MILLIS) {
                // Deliberately NO requireWifi()/requireCharging() on these DownloadConditions:
                // an unmet condition does not fail the returned Task fast, it leaves the Task
                // simply never completing until the condition IS met -- which, left unbounded,
                // would hang this suspend function (and the pill's "Translating…" state) forever
                // on a metered connection with WiFi off. withTimeout is what turns "condition
                // never met" into an honest, bounded failure instead of a silent, permanent
                // spinner -- see this function's timeout branch below.
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                val translated = translator.translate(text).await()
                Result.success(Translation(translated, sourceLanguageTag, targetLanguageTag))
            }
        } catch (timeout: TimeoutCancellationException) {
            Result.failure(
                TranslatorUnavailableException(
                    "Translation timed out -- this needs a network connection the first time " +
                        "a language is used, to download its model.",
                ),
            )
        } catch (cancellation: CancellationException) {
            // NOT a translation failure -- the CALLER's own coroutine scope was cancelled (the
            // photo was most likely closed mid-translation). Rethrown rather than turned into a
            // Result.failure, which is the well-known trap of a broad `catch (e: Exception)`
            // around suspending code: CancellationException IS an Exception, and swallowing it
            // here would make structured concurrency think this coroutine completed normally
            // instead of being cancelled, leaking the cancellation to whatever awaited it.
            throw cancellation
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            translator.close()
        }
    }
}

/**
 * Bridges a Play Services [Task] into a suspend call without adding a
 * `kotlinx-coroutines-play-services` dependency this project would otherwise have no other use
 * for -- ML Kit's Task-based API is the only Play Services surface this feature touches.
 *
 * No `invokeOnCancellation`: [Task] has no `cancel()` method to call into even if one were
 * registered here. A [withTimeout]-triggered cancellation of the coroutine calling this
 * therefore stops US from waiting on the [Task], not the underlying download itself, which keeps
 * running in the background and simply finishes into the model cache that the NEXT [translate]
 * call benefits from -- see [translate]'s own timeout-branch comment.
 */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result -> continuation.resume(result) }
    addOnFailureListener { error -> continuation.resumeWithException(error) }
    addOnCanceledListener { continuation.cancel() }
}

/**
 * Generous enough to cover a real model download on a slow connection; finite enough that a
 * connect-flavor device with no connectivity at all (airplane mode, say) gets an honest answer
 * within one sitting rather than an eternal "Translating…".
 */
private const val TRANSLATE_TIMEOUT_MILLIS = 45_000L
