package im.g.apps.core.bridge.diff

/**
 * Generic eyeball-im `VectorDiff`. The public [im.g.apps.core.bridge.ChatListDiff] and
 * [im.g.apps.core.bridge.TimelineDiff] keep gim-core's names and convert to this type so the list
 * semantics live in one place.
 */
internal sealed class VectorDiff<out T> {
    data class Append<T>(val values: List<T>) : VectorDiff<T>()

    data object Clear : VectorDiff<Nothing>()

    data class PushFront<T>(val value: T) : VectorDiff<T>()

    data class PushBack<T>(val value: T) : VectorDiff<T>()

    data object PopFront : VectorDiff<Nothing>()

    data object PopBack : VectorDiff<Nothing>()

    /** Inserts before [index]; `index == size` appends. */
    data class Insert<T>(val index: Int, val value: T) : VectorDiff<T>()

    data class Set<T>(val index: Int, val value: T) : VectorDiff<T>()

    data class Remove(val index: Int) : VectorDiff<Nothing>()

    /** Keeps only the first [length] items; a no-op when the list is not longer. */
    data class Truncate(val length: Int) : VectorDiff<Nothing>()

    data class Reset<T>(val values: List<T>) : VectorDiff<T>()
}

/** Applies [diffs] in order; each index refers to the list after the previous diff. */
internal fun <T> MutableList<T>.applyVectorDiffs(diffs: List<VectorDiff<T>>) {
    for (diff in diffs) {
        when (diff) {
            is VectorDiff.Append -> addAll(diff.values)
            VectorDiff.Clear -> clear()
            is VectorDiff.PushFront -> add(0, diff.value)
            is VectorDiff.PushBack -> add(diff.value)
            VectorDiff.PopFront -> {
                if (isEmpty()) throw IndexOutOfBoundsException("PopFront on an empty list")
                removeAt(0)
            }
            VectorDiff.PopBack -> {
                if (isEmpty()) throw IndexOutOfBoundsException("PopBack on an empty list")
                removeAt(lastIndex)
            }
            is VectorDiff.Insert -> add(diff.index, diff.value)
            is VectorDiff.Set -> set(diff.index, diff.value)
            is VectorDiff.Remove -> removeAt(diff.index)
            is VectorDiff.Truncate -> {
                if (diff.length < 0) throw IndexOutOfBoundsException("Truncate length ${diff.length}")
                if (diff.length < size) subList(diff.length, size).clear()
            }
            is VectorDiff.Reset -> {
                clear()
                addAll(diff.values)
            }
        }
    }
}
