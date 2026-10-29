package com.parallax.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.zIndex
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch

private val TileShape = RoundedCornerShape(12.dp)

private val SelectKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** Which way focus travelled to reach a tile: x/y in -1..1. Used to "nudge" the tile on arrival. */
data class FocusDirection(val dx: Float, val dy: Float)

@Composable
fun AppTile(
    app: AppInfo,
    index: Int,
    moving: Boolean,
    entryDirection: () -> FocusDirection,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // Set once a held OK button has turned into a long press, so letting go doesn't also open the app.
    var longPressed by remember { mutableStateOf(false) }

    // tvOS-style lift: springy scale up on focus, a quick squeeze on click.
    val scale by animateFloatAsState(
        targetValue = when {
            moving -> 1.15f
            pressed -> 1.06f
            focused -> 1.15f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 320f),
        label = "scale",
    )

    // There's no touch surface to drive parallax, so the tile tilts toward where focus came from and wobbles back.
    val tiltX = remember { Animatable(0f) }
    val tiltY = remember { Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            val dir = entryDirection()
            launch {
                tiltY.snapTo(dir.dx * 9f)
                tiltY.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 110f))
            }
            launch {
                tiltX.snapTo(-dir.dy * 7f)
                tiltX.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 110f))
            }
        } else {
            tiltX.snapTo(0f)
            tiltY.snapTo(0f)
        }
    }

    // The jiggle that tells you an app is picked up and can be moved.
    val wiggle = remember { Animatable(0f) }
    LaunchedEffect(moving) {
        if (moving) {
            while (true) {
                wiggle.animateTo(1.6f, tween(120))
                wiggle.animateTo(-1.6f, tween(120))
            }
        } else {
            wiggle.animateTo(0f, tween(120))
        }
    }

    // A tile being moved isn't moved by focus, so keep it on screen ourselves.
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(moving, index) {
        if (moving) bringIntoView.bringIntoView()
    }

    Column(
        modifier
            // Draw above the neighbours so the enlarged tile and its name are never covered.
            .zIndex(if (focused || moving) 1f else 0f)
            .bringIntoViewRequester(bringIntoView),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocused()
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationX = tiltX.value
                    rotationY = tiltY.value
                    rotationZ = wiggle.value
                    cameraDistance = 12f * density
                    shadowElevation = if (focused) 28.dp.toPx() else 0f
                    shape = TileShape
                    clip = true
                }
                .onKeyEvent { event ->
                    if (event.key !in SelectKeys) return@onKeyEvent false
                    val repeat = event.nativeKeyEvent.repeatCount
                    when {
                        // Holding a remote button sends repeated key-downs; the first repeat means "long press".
                        event.type == KeyEventType.KeyDown && repeat == 0 -> {
                            pressed = true
                            longPressed = false
                        }
                        event.type == KeyEventType.KeyDown && !longPressed -> {
                            longPressed = true
                            pressed = false
                            onLongClick()
                        }
                        event.type == KeyEventType.KeyUp -> {
                            if (pressed && !longPressed) onClick()
                            pressed = false
                        }
                    }
                    true
                }
                .focusable()
                .background(Color(0xFF2C2C34))
                .drawWithContent {
                    drawContent()
                    if (focused) {
                        // Specular sheen that slides with the tilt, like light catching glass.
                        val shift = 0.5f + tiltY.value / 18f
                        val cx = size.width * shift
                        drawRect(
                            brush = Brush.linearGradient(
                                colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                                start = Offset(cx - size.width * 0.6f, 0f),
                                end = Offset(cx + size.width * 0.6f, size.height),
                            ),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.White.copy(alpha = 0.12f),
                                0.4f to Color.Transparent,
                            ),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            when {
                app.banner != null -> Image(
                    bitmap = app.banner,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                app.icon != null -> Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier.fillMaxHeight(0.55f).aspectRatio(1f),
                )
            }
        }

        // Like tvOS, only the focused app shows its name.
        val labelAlpha by animateFloatAsState(if (focused || moving) 1f else 0f, label = "label")
        Spacer(Modifier.height(16.dp))
        BasicText(
            text = app.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = labelAlpha },
        )
    }
}
