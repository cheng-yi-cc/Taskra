package com.taskra.data

object Status {
    const val TODO = "TODO"
    const val DONE = "DONE"
}
object DeadlineType {
    const val NONE = "NONE"
    const val DATE = "DATE"
    const val DATETIME = "DATETIME"
}
object BlockRegion {
    const val QUESTION = "QUESTION"
    const val SOLUTION = "SOLUTION"
}
object BlockKind {
    const val TEXT = "TEXT"
    const val IMAGE = "IMAGE"
}
object ReviewResult {
    const val NONE = "NONE"
    const val MASTERED = "MASTERED"
    const val NEED_RETRY = "NEED_RETRY"
}
object SessionStatus {
    const val ONGOING = "ONGOING"
    const val FINISHED = "FINISHED"
}
object ThemeMode {
    const val SYSTEM = "SYSTEM"
    const val LIGHT = "LIGHT"
    const val DARK = "DARK"
}
object SortMode {
    const val DEADLINE = "DEADLINE"
    const val NEWEST = "NEWEST"
    const val OLDEST = "OLDEST"
}
