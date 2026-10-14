package im.g.apps.core.bridge.diff

/** Incremental list update, modelled on eyeball-im's `VectorDiff` as delivered by gim-core listeners. */
sealed class ListDiff<out T> {
    data class Reset<T>(val items: List<T>) : ListDiff<T>()

    data class Insert<T>(val index: Int, val item: T) : ListDiff<T>()

    data class Set<T>(val index: Int, val item: T) : ListDiff<T>()

    data class Remove(val index: Int) : ListDiff<Nothing>()

    data class PushBack<T>(val item: T) : ListDiff<T>()

    data class PushFront<T>(val item: T) : ListDiff<T>()

    data object Clear : ListDiff<Nothing>()

    /** Keeps the first [length] items. */
    data class Truncate(val length: Int) : ListDiff<Nothing>()
}

/**
 * Returns a new list with [diffs] applied in order. Throws [IndexOutOfBoundsException] for indices outside
 * the list, which signals a listener that missed an update.
 */
fun <T> List<T>.applyDiffs(diffs: List<ListDiff<T>>): List<T> {
    val result = ArrayList(this)
    for (diff in diffs) {
        when (diff) {
            is ListDiff.Reset -> {
                result.clear()
                result.addAll(diff.items)
            }
            is ListDiff.Insert -> result.add(diff.index, diff.item)
            is ListDiff.Set -> result[diff.index] = diff.item
            is ListDiff.Remove -> result.removeAt(diff.index)
            is ListDiff.PushBack -> result.add(diff.item)
            is ListDiff.PushFront -> result.add(0, diff.item)
            ListDiff.Clear -> result.clear()
            is ListDiff.Truncate -> {
                if (diff.length < 0) throw IndexOutOfBoundsException("truncate length ${diff.length}")
                if (diff.length < result.size) result.subList(diff.length, result.size).clear()
            }
        }
    }
    return result
}
