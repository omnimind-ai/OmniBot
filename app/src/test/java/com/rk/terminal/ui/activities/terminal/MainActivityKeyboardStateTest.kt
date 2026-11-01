package com.rk.terminal.ui.activities.terminal

import android.app.Application
import android.os.Bundle
import android.widget.EditText
import com.rk.terminal.ui.screens.terminal.terminalView
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.lang.ref.WeakReference
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class MainActivityKeyboardStateTest {
    private var previousTerminalApplication: Application? = null

    @Before
    fun initializeTerminalApplication() {
        previousTerminalApplication = com.rk.libcommons.application
        // TerminalScreen's shared view state also initializes its preferences and font path.
        com.rk.libcommons.application = RuntimeEnvironment.getApplication()
    }

    @After
    fun clearTerminalView() {
        terminalView = WeakReference(null)
        com.rk.libcommons.application = previousTerminalApplication
    }

    @Test
    fun `recreation restores an open terminal keyboard only after its own view is ready`() {
        val original = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val oldView = attachTerminal(original.get())
        original.get().isKeyboardVisible = true
        val state = Bundle()
        original.saveInstanceState(state)
        original.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java).create(state).visible()
        try {
            val activity = recreated.get()
            assertTrue(activity.wasKeyboardOpen)
            // The shared weak reference can still point at the old activity until binding completes.
            activity.restoreTerminalKeyboard(oldView)
            assertTrue(activity.wasKeyboardOpen)

            val view = attachTerminal(activity)
            activity.restoreTerminalKeyboard(view)
            assertFalse(activity.wasKeyboardOpen)
        } finally {
            recreated.destroy()
        }
    }

    @Test
    fun `recreation does not open a keyboard that was hidden`() {
        val original = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        attachTerminal(original.get())
        val state = Bundle()
        original.saveInstanceState(state)
        original.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java).create(state).visible()
        try {
            val activity = recreated.get()
            activity.restoreTerminalKeyboard(attachTerminal(activity))
            assertFalse(activity.wasKeyboardOpen)
        } finally {
            recreated.destroy()
        }
    }

    @Test
    fun `keyboard from another screen is not saved as terminal keyboard state`() {
        val original = Robolectric.buildActivity(MainActivity::class.java).create().visible()
        val activity = original.get()
        attachTerminal(activity)
        val otherEditor = EditText(activity)
        activity.setContentView(otherEditor)
        otherEditor.requestFocus()
        activity.isKeyboardVisible = true
        val state = Bundle()
        original.saveInstanceState(state)
        original.destroy()

        val recreated = Robolectric.buildActivity(MainActivity::class.java).create(state)
        try {
            assertFalse(recreated.get().wasKeyboardOpen)
        } finally {
            recreated.destroy()
        }
    }

    private fun attachTerminal(activity: MainActivity): TerminalView {
        val view = TerminalView(activity, null).apply {
            setTerminalViewClient(mock(TerminalViewClient::class.java))
            setTextSize(14)
            isFocusableInTouchMode = true
        }
        activity.setContentView(view)
        terminalView = WeakReference(view)
        assertTrue(view.requestFocus())
        return view
    }
}
