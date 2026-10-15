package com.cirabit.android.ui.media

import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.cirabit.android.ui.theme.CirabitFontFamily
import com.cirabit.android.mesh.MeshService
import com.cirabit.android.model.CirabitMessage
import com.cirabit.android.model.CirabitMessageType
import androidx.compose.material3.ColorScheme
import com.cirabit.android.core.ui.component.text.AnnotatedClickableText
import com.cirabit.android.ui.theme.LocalCirabitPalette
import java.text.SimpleDateFormat

@Composable
fun ImageMessageItem(
    message: CirabitMessage,
    messages: List<CirabitMessage>,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((CirabitMessage) -> Unit)?,
    onCancelTransfer: ((CirabitMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bubbles: Boolean = false
) {
    val palette = LocalCirabitPalette.current
    val path = message.content.trim()
    // Bubble mode wraps the image in the same tinted shell as text bubbles; Matrix mode keeps
    // the flat header-plus-card layout.
    val bubblesMode = bubbles
    val imageShape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)

    val context = LocalContext.current
    // Cirabit: bounded, hardened decode (SecureImageDecoder) instead of an unbounded decodeFile.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val targetWidthPx = with(density) { 300.dp.roundToPx().coerceAtLeast(1) }
    val targetHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx().coerceAtLeast(1) }
    val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path, targetWidthPx, targetHeightPx) {
        value = SecureImageDecoder.decodeForDisplay(path, targetWidthPx, targetHeightPx)
    }

    // Collect all image paths from messages for swipe navigation
    val imagePaths = remember(messages) {
        messages.filter { it.type == CirabitMessageType.Image }
            .map { it.content.trim() }
    }
    val haptic = LocalHapticFeedback.current

    if (bubblesMode) {
        MediaBubbleShell(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = meshService.myPeerID,
            showSender = showSender,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onLongPress = { onMessageLongPress?.invoke(message) },
            modifier = modifier,
        ) {
            ImageMessageCard(
                message = message,
                currentUserNickname = currentUserNickname,
                path = path,
                bmp = bmp,
                imagePaths = imagePaths,
                imageShape = imageShape,
                onImageClick = onImageClick,
                onCancelTransfer = onCancelTransfer,
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onMessageLongPress?.invoke(message)
                },
            )
        }
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        val headerText = com.cirabit.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = meshService.myPeerID,
            palette = palette,
            contentColor = colorScheme.onSurface,
            timeFormatter = timeFormatter,
            includeSender = showSender
        )
        AnnotatedClickableText(
            text = headerText,
            annotationTags = listOf("nickname_click"),
            onAnnotationClick = { tag, item ->
                if (tag == "nickname_click" && onNicknameClick != null) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onNicknameClick.invoke(item)
                    true
                } else {
                    false
                }
            },
            onLongPress = { onMessageLongPress?.invoke(message) },
            fontFamily = CirabitFontFamily,
            color = colorScheme.onSurface,
        )

        if (bmp != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                ImageMessageCard(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    path = path,
                    bmp = bmp,
                    imagePaths = imagePaths,
                    imageShape = imageShape,
                    onImageClick = onImageClick,
                    onCancelTransfer = onCancelTransfer,
                    onLongPress = null,
                )
            }
        } else {
            Text(text = stringResource(com.cirabit.android.R.string.image_unavailable), fontFamily = CirabitFontFamily, color = Color.Gray)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageMessageCard(
    message: CirabitMessage,
    currentUserNickname: String,
    path: String,
    bmp: android.graphics.Bitmap?,
    imagePaths: List<String>,
    imageShape: androidx.compose.foundation.shape.RoundedCornerShape,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    onCancelTransfer: ((CirabitMessage) -> Unit)?,
    onLongPress: (() -> Unit)?,
) {
    if (bmp == null) {
        Text(text = stringResource(com.cirabit.android.R.string.image_unavailable), fontFamily = CirabitFontFamily, color = Color.Gray)
        return
    }
    val img = bmp.asImageBitmap()
    val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
    val progressFraction: Float? = when (val st = message.deliveryStatus) {
        is com.cirabit.android.model.DeliveryStatus.PartiallyDelivered -> if (st.total > 0) st.reached.toFloat() / st.total.toFloat() else 0f
        else -> null
    }
    Box {
        val imageModifier = Modifier
            .widthIn(max = 300.dp)
            .aspectRatio(aspect)
            .clip(imageShape)
            .combinedClickable(
                onClick = {
                    val currentIndex = imagePaths.indexOf(path)
                    onImageClick?.invoke(path, imagePaths, currentIndex)
                },
                onLongClick = { onLongPress?.invoke() },
            )
        if (progressFraction != null && progressFraction < 1f && message.sender == currentUserNickname) {
            // Cyberpunk block-reveal while sending
            BlockRevealImage(
                bitmap = img,
                progress = progressFraction,
                blocksX = 24,
                blocksY = 16,
                modifier = imageModifier
            )
        } else {
            // Fully revealed image
            Image(
                bitmap = img,
                contentDescription = stringResource(com.cirabit.android.R.string.cd_image),
                modifier = imageModifier,
                contentScale = ContentScale.Fit
            )
        }
        // Cancel button overlay during sending
        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is com.cirabit.android.model.DeliveryStatus.PartiallyDelivered)
        if (showCancel) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                    .clickable { onCancelTransfer?.invoke(message) },
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(com.cirabit.android.R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}
