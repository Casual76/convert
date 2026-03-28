package com.p2r3.convert.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun AnimatedScreenBlock(
    index: Int,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var visible by remember(index, reduceMotion) { mutableStateOf(reduceMotion) }

    LaunchedEffect(index, reduceMotion) {
        if (reduceMotion) {
            visible = true
        } else {
            visible = false
            delay((index * 10L).coerceAtMost(36L))
            visible = true
        }
    }

    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (reduceMotion) 70 else 120),
        label = "screen-block-progress"
    )

    Box(
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 6f
        }
    ) {
        content()
    }
}

@Composable
fun PremiumHeroSurface(
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val highlightBrush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.10f),
            Color(0xFFFFD7A3).copy(alpha = if (reduceMotion) 0.08f else 0.10f),
            Color.White.copy(alpha = 0.04f)
        ),
        start = Offset.Zero,
        end = Offset(800f, 1_000f)
    )

    Box(
        modifier = modifier
            .clip(androidx.compose.material3.MaterialTheme.shapes.large)
            .background(highlightBrush),
        content = content
    )
}

@Composable
fun rememberPremiumPulseScale(
    enabled: Boolean,
    reduceMotion: Boolean
): Float = 1f

@Composable
fun rememberFloatingTranslation(
    reduceMotion: Boolean,
    amplitude: Dp = 6.dp
): Float = 0f

fun Modifier.premiumPulse(scale: Float): Modifier = graphicsLayer {
    scaleX = scale
    scaleY = scale
}
