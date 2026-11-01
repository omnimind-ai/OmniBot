package com.rk.terminal.ui.activities.terminal

import android.app.Activity
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rk.terminal.service.SessionService
import com.rk.libcommons.OMNIBOT_SETUP_SESSION_ID
import com.rk.terminal.ui.navHosts.MainActivityNavHost
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.terminal.TerminalScreen
import com.rk.terminal.ui.screens.terminal.terminalView
import com.rk.terminal.ui.theme.KarbonTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private companion object {
        const val STATE_TERMINAL_KEYBOARD_OPEN = "terminal_keyboard_open"
    }

    var sessionBinder:SessionService.SessionBinder? = null
    var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SessionService.SessionBinder
            sessionBinder = binder
            isBound = true

            lifecycleScope.launch(Dispatchers.Main){
                setContent {
                    KarbonTheme {
                        Surface {
                            val navController = rememberNavController()
                            MainActivityNavHost(navController = navController, mainActivity = this@MainActivity)

                            val currentEntry by navController.currentBackStackEntryAsState()
                            val currentDestination = currentEntry?.destination

                            val focusManager = LocalFocusManager.current
                            val keyboardController = LocalSoftwareKeyboardController.current

                            LaunchedEffect(currentDestination) {
                                if (currentDestination != null &&
                                    !currentDestination.hasRoute<MainActivityRoutes.MainScreen>()
                                ) {
                                    wasKeyboardOpen = false
                                    // 1️⃣ Clear Compose focus
                                    focusManager.clearFocus(force = true)

                                    // 2️⃣ Clear Android View focus
                                    terminalView.get()?.clearFocus()

                                    // 3️⃣ Hide IME explicitly
                                    keyboardController?.hide()
                                }
                            }

                        }
                    }
                }
            }

        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            sessionBinder = null
        }
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, SessionService::class.java))
        }else{
            startService(Intent(this, SessionService::class.java))
        }
        Intent(this, SessionService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (isFinishing && !isChangingConfigurations) {
            cleanupSetupSessionIfNeeded()
        }
        super.onStop()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private var denied = 1
    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (!isGranted && denied <= 2) {
                denied++
                requestPermission()
            }
        }

    fun requestPermission(){
        // Only request on Android 13+ (API 33+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    var isKeyboardVisible = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wasKeyboardOpen = savedInstanceState?.getBoolean(STATE_TERMINAL_KEYBOARD_OPEN) == true
        enableEdgeToEdge()
        requestPermission()

        val rootView = findViewById<View>(android.R.id.content)
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            isKeyboardVisible = ViewCompat.getRootWindowInsets(rootView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }

        if (intent.hasExtra("awake_intent")){
            moveTaskToBack(true)
        }

    }

    var wasKeyboardOpen = false
    override fun onPause() {
        super.onPause()
        wasKeyboardOpen = isKeyboardVisible && hasFocusedTerminal()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(
            STATE_TERMINAL_KEYBOARD_OPEN,
            wasKeyboardOpen || (isKeyboardVisible && hasFocusedTerminal())
        )
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()

        if (wasKeyboardOpen && !isKeyboardVisible){
            terminalView.get()?.let(::restoreTerminalKeyboard)
        }
    }

    private fun hasFocusedTerminal(): Boolean = terminalView.get()?.let {
        it.rootView === window.decorView && it.hasFocus()
    } == true

    fun restoreTerminalKeyboard(view: View) {
        if (!wasKeyboardOpen || view !== terminalView.get() ||
            view.rootView !== window.decorView || !view.hasFocus()
        ) return
        // Android 17 no longer restores IME visibility after activity recreation.
        // Wait for this activity's terminal view; service binding can finish after onResume.
        wasKeyboardOpen = false
        WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.ime())
    }

    private fun cleanupSetupSessionIfNeeded() {
        val binder = sessionBinder ?: return
        if (binder.getSession(OMNIBOT_SETUP_SESSION_ID) == null) {
            return
        }
        runCatching {
            binder.terminateSession(OMNIBOT_SETUP_SESSION_ID)
        }
    }
}
