package com.cirabit.android.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import com.cirabit.android.mesh.BluetoothMeshService
import com.cirabit.android.model.CirabitMessage
import com.cirabit.android.model.CirabitMessageType
import androidx.compose.material3.ColorScheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ImageMessageItem(
    message: CirabitMessage,
    messages: List<CirabitMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((CirabitMessage) -> Unit)?,
    onCancelTransfer: ((CirabitMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val path = message.content.trim()
    Column(modifier = modifier.fillMaxWidth()) {
        val headerText = com.cirabit.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        val haptic = LocalHapticFeedback.current
        var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = headerText,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onSurface,
            modifier = Modifier.pointerInput(message.id) {
                detectTapGestures(onTap = { pos ->
                    val layout = headerLayout ?: return@detectTapGestures
                    val offset = layout.getOffsetForPosition(pos)
                    val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                    if (ann.isNotEmpty() && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(ann.first().item)
                    }
                }, onLongPress = { onMessageLongPress?.invoke(message) })
            },
            onTextLayout = { headerLayout = it }
        )

        // Collect all image paths from messages for swipe navigation
        val imagePaths = remember(messages) {
            messages.filter { it.type == CirabitMessageType.Image }
                .map { it.content.trim() }
        }

        BoxWithConstraints {
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current
            val targetWidthPx = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
            val targetHeightPx = with(density) { configuration.screenHeightDp.dp.roundToPx().coerceAtLeast(1) }
            val bmp by produceState<android.graphics.Bitmap?>(initialValue = null, path, targetWidthPx, targetHeightPx) {
                value = SecureImageDecoder.decodeForDisplay(path, targetWidthPx, targetHeightPx)
            }

            val decodedBitmap = bmp
            if (decodedBitmap != null) {
                val img = decodedBitmap.asImageBitmap()
                val aspect = (decodedBitmap.width.toFloat() / decodedBitmap.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
                val progressFraction: Float? = when (val st = message.deliveryStatus) {
                    is com.cirabit.android.model.DeliveryStatus.PartiallyDelivered -> if (st.total > 0) st.reached.toFloat() / st.total.toFloat() else 0f
                    else -> null
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Box {
                        if (progressFraction != null && progressFraction < 1f && message.sender == currentUserNickname) {
                            // Cyberpunk block-reveal while sending
                            BlockRevealImage(
                                bitmap = img,
                                progress = progressFraction,
                                blocksX = 24,
                                blocksY = 16,
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .aspectRatio(aspect)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .clickable {
                                        val currentIndex = imagePaths.indexOf(path)
                                        onImageClick?.invoke(path, imagePaths, currentIndex)
                                    }
                            )
                        } else {
                            // Fully revealed image
                            Image(
                                bitmap = img,
                                contentDescription = stringResource(com.cirabit.android.R.string.cd_image),
                                modifier = Modifier
                                    .widthIn(max = 300.dp)
                                    .aspectRatio(aspect)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                    .clickable {
                                        val currentIndex = imagePaths.indexOf(path)
                                        onImageClick?.invoke(path, imagePaths, currentIndex)
                                    },
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
            } else {
                Text(text = stringResource(com.cirabit.android.R.string.image_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
            }
        }
    }
}
