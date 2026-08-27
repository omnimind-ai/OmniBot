package com.rk.terminal.ui.screens.terminal

import android.app.Activity
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import androidx.core.widget.doOnTextChanged
import androidx.navigation.NavController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import com.rk.components.compose.preferences.base.PreferenceGroup
import com.rk.libcommons.application
import com.rk.resources.drawables
import com.rk.resources.strings
import com.rk.libcommons.child
import com.rk.libcommons.dpToPx
import com.rk.libcommons.localDir
import com.rk.libcommons.pendingCommand
import com.rk.libcommons.isOmnibotSetupSessionId
import com.rk.settings.Settings
import com.rk.terminal.ui.activities.terminal.MainActivity
import com.rk.terminal.ui.routes.MainActivityRoutes
import com.rk.terminal.ui.screens.settings.SettingsCard
import com.rk.terminal.ui.screens.settings.WorkingMode
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysConstants
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysInfo
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysListener
import com.rk.terminal.ui.screens.terminal.virtualkeys.VirtualKeysView
import com.termux.terminal.TerminalColors
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.lang.ref.WeakReference
import java.util.Properties

var terminalView = WeakReference<TerminalView?>(null)
var virtualKeysView = WeakReference<VirtualKeysView?>(null)


var darkText = mutableStateOf(Settings.blackTextColor)
var bitmap = mutableStateOf<ImageBitmap?>(null)

private val file = application!!.filesDir.child("font.ttf")
private var font = (if (file.exists() && file.canRead()){
    Typeface.createFromFile(file)
}else{
    Typeface.MONOSPACE
})

suspend fun setFont(typeface: Typeface) = withContext(Dispatchers.Main){
    font = typeface
    terminalView.get()?.apply {
        setTypeface(typeface)
        onScreenUpdated()
    }
}

inline fun getViewColor(): Int{
    return if (darkText.value){
        Color.BLACK
    }else{
        Color.WHITE
    }
}

inline fun getComposeColor():androidx.compose.ui.graphics.Color{
    return if (darkText.value){
        androidx.compose.ui.graphics.Color.Black
    }else{
        androidx.compose.ui.graphics.Color.White
    }
}

private fun applyTerminalPalette(targetView: TerminalView) {
    val foregroundColor = getViewColor()
    // Terminal surface follows the app palette: light surface (#F4F7FB) when
    // darkText (light theme / light wallpaper), dark surface (#151617) otherwise.
    val backgroundColor = if (darkText.value) 0xFFF4F7FB.toInt() else 0xFF151617.toInt()
    targetView.mEmulator?.mColors?.mCurrentColors?.apply {
        set(256, foregroundColor)
        set(257, backgroundColor)
        set(258, foregroundColor)
    }
}

var showToolbar = mutableStateOf(Settings.toolbar)
var showVirtualKeys = mutableStateOf(Settings.virtualKeys)
var showHorizontalToolbar = mutableStateOf(Settings.toolbar)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    modifier: Modifier = Modifier,
    mainActivityActivity: MainActivity,
    navController: NavController
) {
    val context = LocalContext.current
    val isDarkMode = isSystemInDarkTheme()
    val scope = rememberCoroutineScope()


    LaunchedEffect(Unit){
        withContext(Dispatchers.IO){
            if (context.filesDir.child("background").exists().not()){
                darkText.value = !isDarkMode
            }else if (bitmap.value == null){
                val fullBitmap = BitmapFactory.decodeFile(context.filesDir.child("background").absolutePath)?.asImageBitmap()
                if (fullBitmap != null) bitmap.value = fullBitmap
            }
        }


        scope.launch(Dispatchers.Main){
            virtualKeysView.get()?.apply {
                virtualKeysViewClient =
                    terminalView.get()?.mTermSession?.let {
                        VirtualKeysListener(
                            it
                        )
                    }

                buttonTextColor = getViewColor()


                reload(
                    VirtualKeysInfo(
                        VIRTUAL_KEYS,
                        "",
                        VirtualKeysConstants.CONTROL_CHARS_ALIASES
                    )
                )
            }

            terminalView.get()?.apply {
                onScreenUpdated()

                applyTerminalPalette(this)
            }
        }


    }

    Box {
        val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
        val scope = rememberCoroutineScope()
        val configuration = LocalConfiguration.current
        val screenWidthDp = configuration.screenWidthDp
        val drawerWidth = (screenWidthDp * 0.84).dp
        var showAddDialog by remember { mutableStateOf(false) }

        BackHandler(enabled = drawerState.isOpen) {
            scope.launch {
                drawerState.close()
            }
        }

        if (drawerState.isClosed){
            SetStatusBarTextColor(isDarkIcons = darkText.value)
        }else{
            SetStatusBarTextColor(isDarkIcons = !isDarkMode)
        }

        if (showAddDialog){
            BasicAlertDialog(
                onDismissRequest = {
                    showAddDialog = false
                }
            ) {

                fun createSession(workingMode:Int){
                    fun generateUniqueString(existingStrings: List<String>): String {
                        var index = 1
                        var newString: String

                        do {
                            newString = "main$index"
                            index++
                        } while (newString in existingStrings)

                        return newString
                    }

                    val sessionId = generateUniqueString(mainActivityActivity.sessionBinder!!.getService().sessionList.keys.toList())
                    mainActivityActivity.sessionBinder!!.createSession(
                        sessionId,
                        mainActivityActivity,
                        workingMode = workingMode
                    )

                    changeSession(mainActivityActivity, session_id = sessionId)
                }


                PreferenceGroup {
                    SettingsCard(
                        title = { Text("Alpine") },
                        description = {Text(stringResource(strings.alpine_desc))},
                        onClick = {
                           createSession(workingMode = WorkingMode.ALPINE)
                            showAddDialog = false
                        })

                    SettingsCard(
                        title = { Text("Ubuntu") },
                        description = { Text(stringResource(strings.ubuntu_desc)) },
                        onClick = {
                            createSession(workingMode = WorkingMode.UBUNTU)
                            showAddDialog = false
                        })

                    SettingsCard(
                        title = { Text("Android") },
                        description = {Text(stringResource(strings.android_desc))},
                        onClick = {
                            createSession(workingMode = WorkingMode.ANDROID)
                            showAddDialog = false
                        })
                }
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            // Keep edge-swipe opening disabled while still allowing the open
            // drawer's scrim (and close swipe) to dismiss the session panel.
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(strings.session),
                                style = MaterialTheme.typography.titleLarge
                            )

                            Row {
                                val keyboardController = LocalSoftwareKeyboardController.current
                                IconButton(onClick = {
                                    navController.navigate(MainActivityRoutes.Settings.route)
                                    keyboardController?.hide()
                                }) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(drawables.ic_lucide_settings),
                                        contentDescription = null
                                    )
                                }

                                IconButton(onClick = {
                                    showAddDialog = true
                                }) {
                                    Icon(
                                        imageVector = ImageVector.vectorResource(drawables.ic_lucide_plus),
                                        contentDescription = null
                                    )
                                }

                            }


                        }

                        mainActivityActivity.sessionBinder?.getService()?.sessionList?.keys?.toList()?.let {
                            LazyColumn {
                                items(it) { session_id ->
                                    SelectableCard(
                                        selected = session_id == mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first,
                                        onSelect = {
                                            changeSession(
                                                mainActivityActivity,
                                                session_id
                                            )
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = session_id,
                                                style = MaterialTheme.typography.bodyLarge
                                            )

                                            if (session_id != mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first) {
                                                Spacer(modifier = Modifier.weight(1f))

                                                IconButton(
                                                    onClick = {
                                                        println(session_id)
                                                        mainActivityActivity.sessionBinder?.terminateSession(
                                                            session_id
                                                        )
                                                    },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    
                                                    Icon(
                                                        imageVector = ImageVector.vectorResource(drawables.ic_lucide_trash_2),
                                                        contentDescription = null,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                }
                                            }

                                        }
                                    }
                                }
                            }
                        }

                    }
                }

            },
            content = {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BackgroundImage()
                        val color = getComposeColor()
                        Column {

                            fun getNameOfWorkingMode(workingMode:Int?):String{
                                return when(workingMode){
                                    0 -> "ALPINE".lowercase()
                                    1 -> "ANDROID".lowercase()
                                    2 -> "UBUNTU".lowercase()
                                    null -> "null"
                                    else -> "unknown"
                                }
                            }


                            if (showToolbar.value && (LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE || showHorizontalToolbar.value)){
                                TopAppBar(
                                    colors = TopAppBarDefaults.topAppBarColors(
                                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        scrolledContainerColor = androidx.compose.ui.graphics.Color.Transparent
                                    ),
                                    title = {
                                        Column {
                                            Text(text = "Terminal", color = color)
                                            Text(style = MaterialTheme.typography.bodySmall,text = mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.first + " (${getNameOfWorkingMode(mainActivityActivity.sessionBinder?.getService()?.currentSession?.value?.second)})",color = color)
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = {
                                            mainActivityActivity.onBackPressedDispatcher.onBackPressed()
                                        }) {
                                            Icon(ImageVector.vectorResource(drawables.ic_lucide_arrow_left), null, tint = color)
                                        }
                                    },
                                    actions = {
                                        IconButton(onClick = {
                                            scope.launch { drawerState.open() }
                                        }) {
                                            Icon(ImageVector.vectorResource(drawables.ic_lucide_menu), null, tint = color)
                                        }
                                        IconButton(onClick = {
                                            showAddDialog = true
                                        }) {
                                            Icon(ImageVector.vectorResource(drawables.ic_lucide_plus),null, tint = color)
                                        }
                                    }
                                )
                            }

                            val density = LocalDensity.current
                            Column(modifier = Modifier.imePadding().navigationBarsPadding().padding(top = if (showToolbar.value){0.dp}else{
                                with(density){
                                    TopAppBarDefaults.windowInsets.getTop(density).toDp()
                                }
                            })) {
                                AndroidView(
                                    factory = { context ->
                                        TerminalView(context, null).apply {
                                            terminalView = WeakReference(this)
                                            setTextSize(
                                                dpToPx(
                                                    Settings.terminal_font_size.toFloat(),
                                                    context
                                                )
                                            )
                                            val client = TerminalBackEnd(this, mainActivityActivity)

                                            val launchCommand = pendingCommand
                                            if (pendingCommand === launchCommand) {
                                                pendingCommand = null
                                            }
                                            val session = if (launchCommand != null) {
                                                if (
                                                    launchCommand.terminatePreviousSession &&
                                                    isOmnibotSetupSessionId(launchCommand.id)
                                                ) {
                                                    mainActivityActivity.sessionBinder!!.terminateSession(
                                                        launchCommand.id
                                                    )
                                                }
                                                mainActivityActivity.sessionBinder!!.getService().currentSession.value = Pair(
                                                    launchCommand.id,
                                                    launchCommand.workingMode
                                                )
                                                val resolvedSession =
                                                    mainActivityActivity.sessionBinder!!.getSession(
                                                        launchCommand.id
                                                    )
                                                        ?: mainActivityActivity.sessionBinder!!.createSession(
                                                            launchCommand.id,
                                                            mainActivityActivity,
                                                            workingMode = launchCommand.workingMode,
                                                            launchCommand = launchCommand
                                                        )
                                                resolvedSession
                                            } else {
                                                mainActivityActivity.sessionBinder!!.getSession(
                                                    mainActivityActivity.sessionBinder!!.getService().currentSession.value.first
                                                )
                                                    ?: mainActivityActivity.sessionBinder!!.createSession(
                                                        mainActivityActivity.sessionBinder!!.getService().currentSession.value.first,
                                                        mainActivityActivity,
                                                        workingMode = mainActivityActivity.sessionBinder!!.getService().currentSession.value.second
                                                    )
                                            }

                                            attachSessionToTerminalView(
                                                mainActivity = mainActivityActivity,
                                                sessionId = session.mSessionName ?: mainActivityActivity.sessionBinder!!.getService().currentSession.value.first,
                                                targetView = this
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    update = { terminalView ->
                                        terminalView.onScreenUpdated()
                                        applyTerminalPalette(terminalView)
                                    },
                                )

                                if (showVirtualKeys.value){
                                    val pagerState = rememberPagerState(pageCount = { 2 })
                                    val onSurfaceColor = MaterialTheme.colorScheme.onSurface.toArgb()
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(75.dp)
                                    ) { page ->
                                        when (page) {
                                            0 -> {
                                                terminalView.get()?.requestFocus()
                                                //terminalView.get()?.requestFocusFromTouch()
                                                AndroidView(
                                                    factory = { context ->
                                                        VirtualKeysView(context, null).apply {
                                                            virtualKeysView = WeakReference(this)
                                                            virtualKeysViewClient =
                                                                terminalView.get()?.mTermSession?.let {
                                                                    VirtualKeysListener(
                                                                        it
                                                                    )
                                                                }

                                                            buttonTextColor = onSurfaceColor!!

                                                            reload(
                                                                VirtualKeysInfo(
                                                                    VIRTUAL_KEYS,
                                                                    "",
                                                                    VirtualKeysConstants.CONTROL_CHARS_ALIASES
                                                                )
                                                            )
                                                        }
                                                    },
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(75.dp)
                                                )
                                            }

                                            1 -> {
                                                var text by rememberSaveable { mutableStateOf("") }

                                                AndroidView(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .height(75.dp),
                                                    factory = { ctx ->
                                                        EditText(ctx).apply {
                                                            maxLines = 1
                                                            isSingleLine = true
                                                            imeOptions = EditorInfo.IME_ACTION_DONE

                                                            // Listen for text changes to update Compose state
                                                            doOnTextChanged { textInput, _, _, _ ->
                                                                text = textInput.toString()
                                                            }

                                                            setOnEditorActionListener { v, actionId, event ->
                                                                if (actionId == EditorInfo.IME_ACTION_DONE) {
                                                                    if (text.isEmpty()) {
                                                                        // Dispatch enter key events if text is empty
                                                                        val eventDown = KeyEvent(
                                                                            KeyEvent.ACTION_DOWN,
                                                                            KeyEvent.KEYCODE_ENTER
                                                                        )
                                                                        val eventUp = KeyEvent(
                                                                            KeyEvent.ACTION_UP,
                                                                            KeyEvent.KEYCODE_ENTER
                                                                        )
                                                                        terminalView.get()
                                                                            ?.dispatchKeyEvent(eventDown)
                                                                        terminalView.get()
                                                                            ?.dispatchKeyEvent(eventUp)
                                                                    } else {
                                                                        terminalView.get()?.currentSession?.write(
                                                                            text
                                                                        )
                                                                        setText("")
                                                                    }
                                                                    true
                                                                } else {
                                                                    false
                                                                }
                                                            }
                                                        }
                                                    },
                                                    update = { editText ->
                                                        // Keep EditText's text in sync with Compose state, avoid infinite loop by only updating if different
                                                        if (editText.text.toString() != text) {
                                                            editText.setText(text)
                                                            editText.setSelection(text.length)
                                                        }
                                                    }
                                                )
                                            }
                                        }

                                    }
                                }else{
                                    virtualKeysView = WeakReference(null)
                                }

                            }
                        }



                }

            })
    }
}

var wallAlpha by mutableFloatStateOf(Settings.wallTransparency)

@Composable
fun BackgroundImage() {
    bitmap.value?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(wallAlpha)
                .zIndex(-1f)
        )
    }
}

@Composable
fun SetStatusBarTextColor(isDarkIcons: Boolean) {
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return

    SideEffect {
        WindowCompat.getInsetsController(window, view)?.isAppearanceLightStatusBars = isDarkIcons
    }
}



@Composable
fun SelectableCard(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        },
        label = "containerColor"
    )

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (selected) 8.dp else 2.dp
        ),
        enabled = enabled,
        onClick = onSelect
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            content()
        }
    }
}


fun changeSession(mainActivityActivity: MainActivity, session_id: String) {
    terminalView.get()?.let { activeView ->
        attachSessionToTerminalView(
            mainActivity = mainActivityActivity,
            sessionId = session_id,
            targetView = activeView
        )
    }
}

private fun attachSessionToTerminalView(
    mainActivity: MainActivity,
    sessionId: String,
    targetView: TerminalView
) {
    val binder = mainActivity.sessionBinder ?: return
    val service = binder.getService()
    val requestedWorkingMode = service.sessionList[sessionId]
        ?: service.currentSession.value.takeIf { it.first == sessionId }?.second
        ?: Settings.working_Mode

    val currentAttachedSessionId = service.currentSession.value.first
    if (currentAttachedSessionId != sessionId) {
        binder.getSession(currentAttachedSessionId)?.updateTerminalSessionClient(HeadlessTerminalSessionClient)
    }

    val session = binder.getSession(sessionId)
        ?: binder.createSession(
            sessionId,
            mainActivity,
            workingMode = requestedWorkingMode
        )
    val client = TerminalBackEnd(targetView, mainActivity)
    session.updateTerminalSessionClient(client)
    targetView.attachSession(session)
    targetView.setTerminalViewClient(client)
    targetView.setTypeface(font)
    targetView.post {
        targetView.keepScreenOn = true
        targetView.requestFocus()
        targetView.isFocusableInTouchMode = true

        applyTerminalPalette(targetView)

        val colorsFile = localDir().child("colors.properties")
        if (colorsFile.exists() && colorsFile.isFile){
            val props = Properties()
            FileInputStream(colorsFile).use { input ->
                props.load(input)
            }
            TerminalColors.COLOR_SCHEME.updateWith(props)
        }
        targetView.onScreenUpdated()
    }

    virtualKeysView.get()?.apply {
        virtualKeysViewClient = targetView.mTermSession?.let { VirtualKeysListener(it) }
    }
    service.currentSession.value = Pair(sessionId, requestedWorkingMode)
}


const val VIRTUAL_KEYS =
    ("[" + "\n  [" + "\n    \"ESC\"," + "\n    {" + "\n      \"key\": \"/\"," + "\n      \"popup\": \"\\\\\"" + "\n    }," + "\n    {" + "\n      \"key\": \"-\"," + "\n      \"popup\": \"|\"" + "\n    }," + "\n    \"HOME\"," + "\n    \"UP\"," + "\n    \"END\"," + "\n    \"PGUP\"" + "\n  ]," + "\n  [" + "\n    \"TAB\"," + "\n    \"CTRL\"," + "\n    \"ALT\"," + "\n    \"LEFT\"," + "\n    \"DOWN\"," + "\n    \"RIGHT\"," + "\n    \"PGDN\"" + "\n  ]" + "\n]")
