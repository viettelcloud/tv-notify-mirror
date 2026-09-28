package com.example.ui.tv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.NotificationPayload
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bottom-Right Live Overlay Stack for TV screen preview.
 * Displays up to 4 notifications stacked vertically at the bottom right of the screen,
 * with each notification having its own 5-second countdown progress bar.
 */
@Composable
fun BottomRightOverlayStack(
    stackedItems: List<NotificationPayload>,
    durationSeconds: Int = 5,
    onDismissItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(bottom = 28.dp, end = 28.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        stackedItems.forEach { payload ->
            key(payload.id) {
                var progress by remember(payload.id) { mutableFloatStateOf(1f) }

                LaunchedEffect(payload.id) {
                    val startTime = System.currentTimeMillis()
                    val totalMs = durationSeconds * 1000L
                    while (System.currentTimeMillis() - startTime < totalMs) {
                        val elapsed = System.currentTimeMillis() - startTime
                        progress = (1f - (elapsed.toFloat() / totalMs)).coerceIn(0f, 1f)
                        delay(50)
                    }
                    progress = 0f
                    onDismissItem(payload.id)
                }

                AnimatedVisibility(
                    visible = true,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                ) {
                    NotificationBannerCard(
                        payload = payload,
                        progress = progress,
                        onDismiss = { onDismissItem(payload.id) }
                    )
                }
            }
        }
    }
}

// Backward-compatible wrapper for single notification callers
@Composable
fun TopRightOverlayBanner(
    payload: NotificationPayload?,
    durationSeconds: Int = 5,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (payload != null) {
        BottomRightOverlayStack(
            stackedItems = listOf(payload),
            durationSeconds = durationSeconds,
            onDismissItem = { onDismiss() },
            modifier = modifier
        )
    }
}

@Composable
fun NotificationBannerCard(
    payload: NotificationPayload,
    progress: Float,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val timeStr = remember(payload.timestamp) {
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(payload.timestamp))
    }

    Surface(
        modifier = modifier
            .widthIn(min = 340.dp, max = 400.dp)
            .shadow(elevation = 16.dp, shape = RoundedCornerShape(18.dp))
            .border(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF38BDF8), Color(0xFF6366F1), Color(0x33FFFFFF))
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .clip(RoundedCornerShape(18.dp))
            .testTag("notification_overlay_card"),
        color = Color(0xF20B132B), // Deep TV glass
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            // Header Row: App Name Badge + Timestamp + Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color(0xFF0284C7), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notification",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = payload.appName.uppercase(),
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )

                    if (payload.privacyMode) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = "Privacy Shield",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeStr,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(26.dp)
                            .padding(start = 4.dp)
                            .testTag("dismiss_banner_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Title
            Text(
                text = if (payload.title.isNotBlank()) payload.title else payload.appName,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF8FAFC),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            // Message Snippet
            Text(
                text = payload.message.ifBlank { "Notification mirrored" },
                fontSize = 13.sp,
                color = Color(0xFFCBD5E1),
                lineHeight = 18.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Auto-dismiss countdown bar (5 seconds)
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Color(0xFF38BDF8),
                trackColor = Color(0xFF1E293B),
            )
        }
    }
}
