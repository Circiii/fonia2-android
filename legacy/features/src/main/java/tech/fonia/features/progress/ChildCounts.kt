package tech.fonia.features.progress

/**
 * The counts on the today card, the medals and the lessons cards. A child sees the bar fill or the ring close, never
 * how many are left: a number out of a total reads as a score. An adult keeps the numbers.
 */
object ChildCounts {
    fun todayCount(done: Int, total: Int, isChild: Boolean): String? = if (isChild) null else "$done din $total"

    fun todayLabel(done: Int, total: Int, isChild: Boolean): String? = if (isChild) null else "Ai terminat $done din $total lecții"

    fun medalCaption(done: Int, total: Int, isChild: Boolean): String? = if (isChild) null else "$done/$total"

    fun medalValue(done: Int, total: Int, isChild: Boolean): String? = if (isChild) null else "$done din $total"
}
