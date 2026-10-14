package im.g.apps.core.bridge

import im.g.apps.core.bridge.diff.VectorDiff
import im.g.apps.core.bridge.diff.applyVectorDiffs

// Mirrors gim-core `ChatListDiff` (chat_list.rs) and `TimelineDiff` (timeline.rs), both eyeball-im
// `VectorDiff`s. Indices and lengths are `u32` in gim-core and Int here. Apply the diffs of one
// `onUpdate` call in order: each index refers to the list as it is after the previous diff.

/** Incremental change to the chat list. */
sealed class ChatListDiff {
    data class Append(val values: List<ChatSummary>) : ChatListDiff()

    data object Clear : ChatListDiff()

    data class PushFront(val value: ChatSummary) : ChatListDiff()

    data class PushBack(val value: ChatSummary) : ChatListDiff()

    data object PopFront : ChatListDiff()

    data object PopBack : ChatListDiff()

    data class Insert(val index: Int, val value: ChatSummary) : ChatListDiff()

    data class Set(val index: Int, val value: ChatSummary) : ChatListDiff()

    data class Remove(val index: Int) : ChatListDiff()

    data class Truncate(val length: Int) : ChatListDiff()

    data class Reset(val values: List<ChatSummary>) : ChatListDiff()
}

/** Incremental change to a timeline. */
sealed class TimelineDiff {
    data class Append(val values: List<TimelineItem>) : TimelineDiff()

    data object Clear : TimelineDiff()

    data class PushFront(val value: TimelineItem) : TimelineDiff()

    data class PushBack(val value: TimelineItem) : TimelineDiff()

    data object PopFront : TimelineDiff()

    data object PopBack : TimelineDiff()

    data class Insert(val index: Int, val value: TimelineItem) : TimelineDiff()

    data class Set(val index: Int, val value: TimelineItem) : TimelineDiff()

    data class Remove(val index: Int) : TimelineDiff()

    data class Truncate(val length: Int) : TimelineDiff()

    data class Reset(val values: List<TimelineItem>) : TimelineDiff()
}

/**
 * Applies [diffs] to this list in place, in order. Throws [IndexOutOfBoundsException] for an index outside
 * the list (or a pop on an empty list), which means the listener missed an update.
 */
fun MutableList<ChatSummary>.applyDiffs(diffs: List<ChatListDiff>) =
    applyVectorDiffs(diffs.map { it.toVectorDiff() })

/** Timeline counterpart of the chat list [applyDiffs]. */
@JvmName("applyTimelineDiffs")
fun MutableList<TimelineItem>.applyDiffs(diffs: List<TimelineDiff>) =
    applyVectorDiffs(diffs.map { it.toVectorDiff() })

internal fun ChatListDiff.toVectorDiff(): VectorDiff<ChatSummary> = when (this) {
    is ChatListDiff.Append -> VectorDiff.Append(values)
    ChatListDiff.Clear -> VectorDiff.Clear
    is ChatListDiff.PushFront -> VectorDiff.PushFront(value)
    is ChatListDiff.PushBack -> VectorDiff.PushBack(value)
    ChatListDiff.PopFront -> VectorDiff.PopFront
    ChatListDiff.PopBack -> VectorDiff.PopBack
    is ChatListDiff.Insert -> VectorDiff.Insert(index, value)
    is ChatListDiff.Set -> VectorDiff.Set(index, value)
    is ChatListDiff.Remove -> VectorDiff.Remove(index)
    is ChatListDiff.Truncate -> VectorDiff.Truncate(length)
    is ChatListDiff.Reset -> VectorDiff.Reset(values)
}

internal fun TimelineDiff.toVectorDiff(): VectorDiff<TimelineItem> = when (this) {
    is TimelineDiff.Append -> VectorDiff.Append(values)
    TimelineDiff.Clear -> VectorDiff.Clear
    is TimelineDiff.PushFront -> VectorDiff.PushFront(value)
    is TimelineDiff.PushBack -> VectorDiff.PushBack(value)
    TimelineDiff.PopFront -> VectorDiff.PopFront
    TimelineDiff.PopBack -> VectorDiff.PopBack
    is TimelineDiff.Insert -> VectorDiff.Insert(index, value)
    is TimelineDiff.Set -> VectorDiff.Set(index, value)
    is TimelineDiff.Remove -> VectorDiff.Remove(index)
    is TimelineDiff.Truncate -> VectorDiff.Truncate(length)
    is TimelineDiff.Reset -> VectorDiff.Reset(values)
}
