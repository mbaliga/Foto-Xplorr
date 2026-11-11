package com.fotoxplorr.core.organize.fileops

/**
 * What to do when the target name is taken. The dialog is not the engine's: it implements
 * [ConflictPrompt] and the engine remembers an "apply to all" answer for the rest of the run.
 */
enum class ConflictPolicy { ASK, SKIP, KEEP_BOTH, REPLACE }

enum class ConflictChoice { SKIP, KEEP_BOTH, REPLACE }

/**
 * [replaceConsent] is the explicit flag. Without it a REPLACE answer fails the step with
 * [FileOpsError.CONSENT_REQUIRED] and touches nothing. Even with it, replace never overwrites in place:
 * the existing file goes to the trash first (ADR-022 point 3), so it can be restored.
 */
data class ConflictSettings(
    val policy: ConflictPolicy = ConflictPolicy.ASK,
    val replaceConsent: Boolean = false,
)

data class Conflict(val stepId: String, val sourcePath: String, val targetPath: String)

data class ConflictAnswer(val choice: ConflictChoice, val applyToAll: Boolean = false)

fun interface ConflictPrompt {
    fun ask(conflict: Conflict): ConflictAnswer
}

/** Per-run policy engine: fixed policies answer directly, ASK consults the prompt and remembers apply-to-all. */
class ConflictResolver(private val settings: ConflictSettings, private val prompt: ConflictPrompt?) {
    private var sticky: ConflictChoice? = null

    fun resolve(conflict: Conflict): ConflictChoice {
        val choice = when (settings.policy) {
            ConflictPolicy.SKIP -> ConflictChoice.SKIP
            ConflictPolicy.KEEP_BOTH -> ConflictChoice.KEEP_BOTH
            ConflictPolicy.REPLACE -> ConflictChoice.REPLACE
            // No prompt wired means no way to ask: the safe answer is to leave both files alone.
            ConflictPolicy.ASK -> sticky ?: prompt?.ask(conflict)?.also { if (it.applyToAll) sticky = it.choice }?.choice
                ?: ConflictChoice.SKIP
        }
        if (choice == ConflictChoice.REPLACE && !settings.replaceConsent) {
            throw FileOpsException(FileOpsError.CONSENT_REQUIRED, "Replace needs explicit consent.")
        }
        return choice
    }
}

object ConflictNaming {
    /** `IMG.jpg` -> `IMG (2).jpg`, `IMG (3).jpg` ... the first name for which [taken] is false. */
    fun keepBoth(path: String, taken: (String) -> Boolean): String {
        val dir = FilePaths.parent(path)
        val (stem, ext) = FilePaths.splitExtension(FilePaths.name(path))
        var n = 2
        while (true) {
            val candidate = FilePaths.join(dir, "$stem ($n)$ext")
            if (!taken(candidate)) return candidate
            n++
        }
    }
}
