package com.craftworks.music.ui.elements

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

// Taken from this Medium article:
// https://blog.canopas.com/jetpack-compose-cool-button-click-effects-c6bbecec7bcb
// Rewritten: pointerInput keyed on Unit so the gesture coroutine is not torn down
// and restarted on every press, and state writes happen outside composition.

enum class ButtonState { Pressed, Idle }

fun Modifier.bounceClick(enabled: Boolean = true) = composed {
    var buttonState by remember { mutableStateOf(ButtonState.Idle) }
    val scale by animateFloatAsState(
        if (buttonState == ButtonState.Pressed && enabled) 0.9f else 1f,
        label = "Animated Button Scale",
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                buttonState = ButtonState.Pressed
                waitForUpOrCancellation()
                buttonState = ButtonState.Idle
            }
        }
}

fun Modifier.moveClick(right: Boolean, enabled: Boolean = true) = composed {
    var buttonState by remember { mutableStateOf(ButtonState.Idle) }
    val position by animateDpAsState(
        if (buttonState == ButtonState.Pressed && enabled) 12.dp else 0.dp,
        label = "Animated Button Position",
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    this
        .offset {
            IntOffset(x = if (right) position.toPx().toInt() else -position.toPx().toInt(), y = 0)
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                buttonState = ButtonState.Pressed
                waitForUpOrCancellation()
                buttonState = ButtonState.Idle
            }
        }
}

/**
 * Press-slide for a button's *content*, without moving the touch target.
 *
 * [moveClick] is the same effect but built on `Modifier.offset`, which is a
 * LAYOUT modifier: Compose hit-tests against the offset position, so the moment
 * the button slid 12 dp on press, a finger resting near the trailing edge fell
 * outside the shifted bounds and the click was silently cancelled. On the 48 dp
 * transport keys that made the outer ~12 dp of prev/next dead.
 *
 * Applying this to the Icon instead keeps the press feel while the IconButton's
 * bounds stay perfectly still — `graphicsLayer` does not participate in hit
 * testing.
 */
fun Modifier.pressSlide(right: Boolean, enabled: Boolean = true) = composed {
    var buttonState by remember { mutableStateOf(ButtonState.Idle) }
    val position by animateDpAsState(
        if (buttonState == ButtonState.Pressed && enabled) 10.dp else 0.dp,
        label = "Press Slide",
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )
    this
        .graphicsLayer { translationX = if (right) position.toPx() else -position.toPx() }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                buttonState = ButtonState.Pressed
                waitForUpOrCancellation()
                buttonState = ButtonState.Idle
            }
        }
}
