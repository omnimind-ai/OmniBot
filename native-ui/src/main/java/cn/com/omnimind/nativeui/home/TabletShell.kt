package cn.com.omnimind.nativeui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import cn.com.omnimind.nativeui.R
import cn.com.omnimind.nativeui.theme.LocalOmniPalette

/** Which side panes the tablet shell shows; collapse is per session like the Flutter chat. */
class TabletPaneControls(
    val leftCollapsed: Boolean,
    val rightCollapsed: Boolean,
    val toggleLeft: () -> Unit,
    val toggleRight: () -> Unit,
)

/**
 * Tablet landscape shell (batch 5e-7d): a permanent drawer pane, the page
 * stack in the middle and, on chat pages, the workspace pane on the right
 * (Dart `_buildHdPadLandscapeShell`). Dragging a divider resizes its pane and
 * dragging it past the collapse threshold collapses it; a collapsed pane
 * keeps its divider as a rail that reopens it on tap. Widths are saved when
 * a drag ends.
 *
 * [center] stays the same call site whatever panes show (the side panes are
 * conditional siblings), so the page stack keeps its state when a pane opens
 * or the window stops being a tablet landscape (multi-window resize).
 */
@Composable
internal fun BoxWithConstraintsScope.TabletShell(
    /** False on phones and portrait: only [center] renders, in the same slot. */
    enabled: Boolean,
    widths: TabletPaneWidths,
    onWidthsChange: (TabletPaneWidths) -> Unit,
    controls: TabletPaneControls,
    showRight: Boolean,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
    center: @Composable () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val total = maxWidth.value
    val available = (total - TabletPanes.DIVIDER * 2).coerceAtLeast(0f)
    var dragging by remember { mutableStateOf(false) }
    var dragLeft by remember { mutableFloatStateOf(widths.left ?: TabletPanes.DEFAULT_LEFT) }
    var dragRight by remember { mutableFloatStateOf(widths.right ?: TabletPanes.DEFAULT_RIGHT) }
    val currentWidths by rememberUpdatedState(widths)
    val layout = TabletPanes.resolve(
        total,
        preferredLeft = if (dragging) dragLeft else widths.left,
        preferredRight = if (dragging) dragRight else widths.right,
        collapseLeft = controls.leftCollapsed,
        collapseRight = controls.rightCollapsed || !showRight,
    )
    // Pane content keeps its expanded width while the pane animates closed (Dart `expandedLayout`).
    val expanded = TabletPanes.resolve(
        total,
        preferredLeft = if (dragging) dragLeft else widths.left,
        preferredRight = if (dragging) dragRight else widths.right,
    )
    val spec = if (dragging) snap() else tween<Float>(280, easing = FastOutSlowInEasing)
    val leftWidth by animateFloatAsState(layout.left, spec, label = "tabletLeft")
    val rightWidth by animateFloatAsState(layout.right, spec, label = "tabletRight")

    Row(Modifier.fillMaxSize().background(palette.page)) {
        if (enabled) Box(Modifier.width(leftWidth.dp).fillMaxHeight().clipToBounds()) {
            // Laid out at full width and clipped, so the drawer never reflows while it animates.
            if (leftWidth > 0.5f) Box(Modifier.fillMaxHeight()
                .wrapContentWidth(Alignment.Start, unbounded = true).width(expanded.left.dp)) { left() }
        }
        if (enabled) PaneDivider(
            collapsed = controls.leftCollapsed,
            label = stringResource(R.string.omni_tablet_drawer_pane),
            onToggle = controls.toggleLeft,
            onDragStart = { dragLeft = layout.left; dragRight = layout.right; dragging = true },
            onDrag = { delta ->
                dragLeft += delta
                if (dragLeft <= TabletPanes.collapseThreshold(available, TabletPanes.MIN_LEFT)) {
                    dragging = false
                    controls.toggleLeft()
                }
            },
            onDragEnd = {
                if (dragging) {
                    dragging = false
                    onWidthsChange(currentWidths.copy(left = dragLeft.coerceIn(TabletPanes.MIN_LEFT, TabletPanes.MAX_LEFT)))
                }
            },
        )
        Box(Modifier.weight(1f).fillMaxHeight()) { center() }
        if (enabled && showRight) {
            PaneDivider(
                collapsed = controls.rightCollapsed,
                label = stringResource(R.string.omni_tablet_workspace_pane),
                onToggle = controls.toggleRight,
                onDragStart = { dragLeft = layout.left; dragRight = layout.right; dragging = true },
                onDrag = { delta ->
                    dragRight -= delta
                    if (dragRight <= TabletPanes.collapseThreshold(available, TabletPanes.MIN_RIGHT)) {
                        dragging = false
                        controls.toggleRight()
                    }
                },
                onDragEnd = {
                    if (dragging) {
                        dragging = false
                        onWidthsChange(currentWidths.copy(right = dragRight.coerceIn(TabletPanes.MIN_RIGHT, TabletPanes.MAX_RIGHT)))
                    }
                },
            )
        }
        if (enabled) Box(Modifier.width(rightWidth.dp).fillMaxHeight().clipToBounds()) {
            if (rightWidth > 0.5f) Box(Modifier.fillMaxHeight()
                .wrapContentWidth(Alignment.End, unbounded = true).width(expanded.right.dp)) { right() }
        }
    }
}

@Composable
private fun PaneDivider(
    collapsed: Boolean,
    label: String,
    onToggle: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val palette = LocalOmniPalette.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    val state = rememberDraggableState { delta -> currentOnDrag(delta) }
    val stateLabel = stringResource(if (collapsed) R.string.omni_tablet_pane_collapsed else R.string.omni_tablet_pane_expanded)
    val toggleLabel = stringResource(if (collapsed) R.string.omni_tablet_pane_expand else R.string.omni_tablet_pane_collapse)
    Box(
        Modifier.width(TabletPanes.DIVIDER.dp).fillMaxHeight()
            .then(if (collapsed) Modifier else Modifier.draggable(state, Orientation.Horizontal,
                onDragStarted = { onDragStart() }, onDragStopped = { onDragEnd() }))
            .clickable(role = Role.Button, onClickLabel = toggleLabel, onClick = onToggle)
            .semantics {
                contentDescription = label
                stateDescription = stateLabel
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { onToggle(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(4.dp).height(40.dp).background(palette.strongBorder, RoundedCornerShape(2.dp)))
    }
}
