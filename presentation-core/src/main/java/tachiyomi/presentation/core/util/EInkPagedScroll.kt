package tachiyomi.presentation.core.util

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Whether vertically scrolling lists should turn a full page per swipe instead of
 * continuously scrolling. On by default in this e-ink build; toggled by the
 * "paged scrolling" preference, provided from the app theme.
 */
val LocalEInkPagedScrolling = compositionLocalOf { true }

/**
 * Observes vertical swipes and turns them into single, instant full-viewport page
 * turns on [state]. The owning list must also be created with
 * `userScrollEnabled = false` (when paged scrolling is on) so the list itself never
 * scrolls from touch. No-op when [LocalEInkPagedScrolling] is off.
 */
fun Modifier.eInkPagedListScroll(state: LazyListState): Modifier = eInkPagedScroll(
    viewportHeight = { state.layoutInfo.viewportSize.height },
    scrollBy = { delta -> state.scroll { scrollBy(delta) } },
)

/**
 * Grid equivalent of [eInkPagedListScroll].
 */
fun Modifier.eInkPagedGridScroll(state: LazyGridState): Modifier = eInkPagedScroll(
    viewportHeight = { state.layoutInfo.viewportSize.height },
    scrollBy = { delta -> state.scroll { scrollBy(delta) } },
)

private fun Modifier.eInkPagedScroll(
    viewportHeight: () -> Int,
    scrollBy: suspend (Float) -> Unit,
): Modifier = composed {
    if (!LocalEInkPagedScrolling.current) return@composed Modifier

    val scope = rememberCoroutineScope()
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var accumulatedX = 0f
            var accumulatedY = 0f
            var fired = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                accumulatedX += delta.x
                accumulatedY += delta.y
                val viewport = viewportHeight().toFloat()
                if (viewport <= 0f) continue
                // One page turn per gesture: swipe up moves to the next page
                if (!fired &&
                    abs(accumulatedY) > viewport / 8f &&
                    abs(accumulatedY) > abs(accumulatedX) * 2
                ) {
                    fired = true
                    scope.launch { scrollBy(if (accumulatedY < 0) viewport else -viewport) }
                }
            }
        }
    }
}
