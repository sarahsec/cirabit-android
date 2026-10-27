package com.cirabit.android.testhook

import com.cirabit.android.model.CirabitMessage

/**
 * Finds messages that arrived after a receive command started.
 *
 * "Arrived" is decided locally: a message is new when its ID was absent from the
 * snapshot taken at command start. The sender's timestamp is never compared with the
 * receiver's clock, so receive hooks keep working when the two devices' clocks disagree.
 */
internal class ArrivedMessageMatcher(
    alreadyPresent: Iterable<CirabitMessage>,
    private val accepts: (CirabitMessage) -> Boolean
) {
    private val seenIDs: Set<String> = alreadyPresent.mapTo(HashSet()) { it.id }

    fun firstArrived(messages: Iterable<CirabitMessage>): CirabitMessage? =
        messages.firstOrNull { it.id !in seenIDs && accepts(it) }
}
