package com.fotoxplorr.core.organize.fileops

/** What the caller asks for. Files only; folders are reported as a warning and fail their step. */
sealed interface FileOp {
    val source: String

    data class Copy(override val source: String, val destDir: String, val newName: String? = null) : FileOp

    /** Native rename inside one source; copy, verify, then trash the original across sources. */
    data class Move(override val source: String, val destDir: String, val newName: String? = null) : FileOp

    data class Rename(override val source: String, val newName: String) : FileOp

    data class Trash(override val source: String) : FileOp
}

enum class StepKind { COPY, MOVE_NATIVE, MOVE_CROSS, RENAME, TRASH }

/** [target] is the DESIRED path; the conflict policy may turn it into a numbered name at run time. */
data class PlanStep(
    val id: String,
    val kind: StepKind,
    val source: String,
    val target: String?,
    /** Bytes that will be streamed (0 for renames, native moves and trashing). */
    val bytes: Long,
    val sourceSize: Long?,
    val isFolder: Boolean = false,
)

sealed interface PlanWarning {
    data class SourceMissing(val stepId: String) : PlanWarning
    data class IsFolder(val stepId: String) : PlanWarning
    data class InsufficientSpace(val sourceId: String, val neededBytes: Long, val freeBytes: Long) : PlanWarning
    /** REPLACE chosen but not consented: every clash will fail its step untouched. */
    data object ReplaceWithoutConsent : PlanWarning
}

data class FileOpsPlan(
    /** Hash of the steps, so a journal can only ever resume the plan that wrote it. */
    val id: String,
    val steps: List<PlanStep>,
    val conflicts: ConflictSettings,
    val estimatedBytes: Long,
    val warnings: List<PlanWarning>,
)

class FileOpsPlanner(private val fs: FileSystem) {

    fun plan(ops: List<FileOp>, conflicts: ConflictSettings = ConflictSettings()): FileOpsPlan {
        val warnings = ArrayList<PlanWarning>()
        val steps = ops.mapIndexed { index, op ->
            val id = index.toString()
            val stat = fs.stat(op.source)
            if (stat == null) warnings += PlanWarning.SourceMissing(id)
            if (stat?.isDirectory == true) warnings += PlanWarning.IsFolder(id)
            val size = stat?.size
            when (op) {
                is FileOp.Copy -> PlanStep(
                    id, StepKind.COPY, op.source, targetIn(op.destDir, op.source, op.newName),
                    size ?: 0L, size, stat?.isDirectory == true,
                )
                is FileOp.Move -> {
                    val target = targetIn(op.destDir, op.source, op.newName)
                    if (fs.sourceId(op.source) == fs.sourceId(target)) {
                        PlanStep(id, StepKind.MOVE_NATIVE, op.source, target, 0L, size, stat?.isDirectory == true)
                    } else {
                        PlanStep(id, StepKind.MOVE_CROSS, op.source, target, size ?: 0L, size, stat?.isDirectory == true)
                    }
                }
                is FileOp.Rename -> PlanStep(
                    id, StepKind.RENAME, op.source,
                    targetIn(FilePaths.parent(op.source), op.source, op.newName), 0L, size, stat?.isDirectory == true,
                )
                is FileOp.Trash -> PlanStep(id, StepKind.TRASH, op.source, null, 0L, size, stat?.isDirectory == true)
            }
        }
        // Space check per destination volume: everything that will be streamed there.
        val needed = LinkedHashMap<String, Long>()
        val probe = HashMap<String, String>()
        for (s in steps) {
            val t = s.target ?: continue
            if (s.bytes <= 0L) continue
            val sid = fs.sourceId(t)
            needed[sid] = (needed[sid] ?: 0L) + s.bytes
            probe.getOrPut(sid) { t }
        }
        for ((sid, bytes) in needed) {
            val free = fs.freeBytes(probe.getValue(sid)) ?: continue
            if (free < bytes) warnings += PlanWarning.InsufficientSpace(sid, bytes, free)
        }
        if (conflicts.policy == ConflictPolicy.REPLACE && !conflicts.replaceConsent) {
            warnings += PlanWarning.ReplaceWithoutConsent
        }
        return FileOpsPlan(planId(steps), steps, conflicts, steps.sumOf { it.bytes }, warnings)
    }

    private fun targetIn(dir: String, source: String, newName: String?): String =
        FilePaths.join(dir, newName?.let { sanitizeName(it) }?.takeIf { it.isNotEmpty() } ?: FilePaths.name(source))

    private fun planId(steps: List<PlanStep>): String {
        val h = Sha256()
        for (s in steps) {
            val line = "${s.kind}\t${s.source}\t${s.target}\t${s.bytes}\n".encodeToByteArray()
            h.update(line)
        }
        return h.digestHex().substring(0, 16)
    }

    companion object {
        private const val ILLEGAL = "/\\:*?\"<>|"

        /** Illegal characters become `_`; blank and dot-only names become empty (the caller keeps the old name). */
        fun sanitizeName(name: String): String {
            val cleaned = name.map { if (it in ILLEGAL || it.code < 0x20) '_' else it }.joinToString("").trim()
            return if (cleaned.all { it == '.' }) "" else cleaned
        }
    }
}
