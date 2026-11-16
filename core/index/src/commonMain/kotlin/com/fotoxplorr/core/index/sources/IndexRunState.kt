package com.fotoxplorr.core.index.sources

/** Where an `index_run` row stands. The resumable position itself is `core.index.walker.WalkCursor`. */
enum class IndexRunStatus { RUNNING, COMPLETED, FAILED, CANCELLED }
