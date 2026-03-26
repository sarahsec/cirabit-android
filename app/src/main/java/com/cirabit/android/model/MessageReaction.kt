package com.cirabit.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Emoji reaction update for a message.
 */
@Parcelize
data class MessageReaction(
    val messageID: String,
    val emoji: String,
    val reactorPeerID: String,
    val isRemoval: Boolean = false
) : Parcelable
