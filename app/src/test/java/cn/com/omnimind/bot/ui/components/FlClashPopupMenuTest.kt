package cn.com.omnimind.bot.ui.components

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 34], application = Application::class, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class FlClashPopupMenuTest {
    private fun withMenu(test: (FlClashPopupMenu, Button, PopupWindow, () -> Int) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val anchor = Button(activity).apply { text = "More" }
        val root = FrameLayout(activity).apply {
            addView(anchor, FrameLayout.LayoutParams(88, 88, Gravity.TOP or Gravity.RIGHT))
        }
        activity.setContentView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, 800, 1600)
        shadowOf(Looper.getMainLooper()).idle()
        var selected = 0
        val menu = FlClashPopupMenu(
            anchor,
            false,
            listOf(
                FlClashPopupMenu.Item("Reload") { selected++ },
                FlClashPopupMenu.Item("Share") { selected += 10 },
            ),
        )
        try {
            menu.show()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            val popup = shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow
            test(menu, anchor, popup) { selected }
        } finally {
            menu.dispose()
            controller.pause().stop().destroy()
        }
    }

    private fun findLabel(view: View, label: String): TextView? {
        if (view is TextView && view.text.toString() == label) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findLabel(view.getChildAt(i), label)?.let { return it }
        }
        return null
    }

    @Test
    fun `selection restores source before invoking action once`() =
        withMenu { _, anchor, popup, count ->
            assertTrue(popup.isShowing)
            assertEquals(0f, anchor.alpha)
            val reload = requireNotNull(findLabel(popup.contentView, "Reload"))
            reload.performClick()
            reload.performClick()
            assertEquals(0, count())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertEquals(1, count())
            assertEquals(1f, anchor.alpha)
            assertFalse(popup.isShowing)
        }

    @Test
    fun `system dismissal restores source without running actions`() =
        withMenu { _, anchor, popup, count ->
            popup.dismiss()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertFalse(popup.isShowing)
            assertEquals(1f, anchor.alpha)
            assertEquals(0, count())
        }

    @Test
    fun `activity cleanup cancels a selected action while it is returning`() =
        withMenu { menu, anchor, popup, count ->
            requireNotNull(findLabel(popup.contentView, "Share")).performClick()
            menu.dispose()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
            assertEquals(0, count())
            assertEquals(1f, anchor.alpha)
            assertFalse(popup.isShowing)
        }
}
