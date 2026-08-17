package com.sa.aidesktop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.os.BatteryManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.sa.aidesktop.core.settings.AISettingsStore
import androidx.compose.ui.unit.*
import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.browser.AndroidBrowserService
import com.sa.aidesktop.core.browser.BrowserLimits
import com.sa.aidesktop.core.ai.tools.*
import com.sa.aidesktop.core.files.*
import com.sa.aidesktop.core.git.*
import com.sa.aidesktop.core.github.*
import com.sa.aidesktop.core.terminal.*
import com.sa.aidesktop.core.website.*
import com.sa.aidesktop.core.workspace.*
import com.sa.aidesktop.core.coding.*
import com.sa.aidesktop.core.window.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.DisposableEffect
import com.sa.aidesktop.core.voice.AndroidTextToSpeechEngine
import com.sa.aidesktop.core.voice.AndroidSpeechToTextEngine
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.painterResource
import com.sa.aidesktop.R

private val manager = DesktopWindowManager()

// BUG FIX: the taskbar height (and the "+10f" breathing room above it) used to be a 58f/68f
// magic number repeated in three places (workspace clamp, DesktopWindowView call, windowDefaults).
// Pulling it into one constant lets the taskbar shrink to a more compact dock height (rule 8)
// while keeping every downstream workspace/window calculation in sync automatically, in both
// portrait and landscape, instead of drifting out of sync if only one call site were edited.
private const val TASKBAR_HEIGHT = 50f

/**
 * Formats the current autonomous-agent state for the chat UI. Kept at file scope so the
 * send()/continuation handlers can resolve it reliably during Kotlin compilation.
 */
private fun formatAgentTaskStatus(r: AgentTaskRecord): String = buildString {
    append("Task ${r.taskId.take(8)}: ${r.state}. ")
    when {
        r.waitingReason != null -> append(r.waitingReason)
        r.finalResult.isNotBlank() -> append(r.finalResult)
        r.lastToolResult.isNotBlank() -> append(r.lastToolResult)
        r.currentOperation.isNotBlank() -> append(r.currentOperation)
        else -> append("Task state saved; next step will reconcile real state.")
    }
}



@Composable fun SADesktopApp() {
    val windows by manager.state.collectAsState()
    val context = LocalContext.current
    val settingsStore = remember(context) { AISettingsStore(context) }
    val offlineAi = remember(settingsStore) {
        LocalLlamaEngine(
            modelPathProvider = { settingsStore.getLocalModelPath() },
            configProvider = {
                LocalModelConfig(
                    contextSize = settingsStore.getLocalContextSize(),
                    threads = settingsStore.getLocalThreads(),
                    maxOutputTokens = settingsStore.getLocalMaxOutputTokens()
                )
            }
        )
    }
    val files = remember(context) { AndroidProjectFileService(context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    // Real-Linux terminal (Rule 8 counterpart to the restricted EmbeddedShellBackend): once the
    // user runs `bootstrap install` in Terminal, RoutingShellBackend switches every command from
    // the allowlist sandbox to the real downloaded bash/busybox/apt environment. Nothing about
    // EmbeddedShellBackend or EmbeddedTerminalService was removed — this only supplies a
    // different ShellBackend to the same, unmodified terminal service.
    // liveOutput carries progress/output lines WHILE a command is still running (bootstrap
    // install's download %, or each real line of a running command) — TerminalWindow shows it
    // live instead of the user staring at a blank screen until the whole thing finishes.
    val terminalLiveOutput = remember { MutableStateFlow("") }
    val terminal = remember(context) {
        val workspaceRoot = context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")
        EmbeddedTerminalService(
            workspaceRoot,
            RoutingShellBackend(workspaceRoot, LinuxBootstrapManager(context), onLiveOutput = { terminalLiveOutput.value = it })
        )
    }
    val browser = remember(context) { AndroidBrowserService(context, context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    val githubAccounts = remember(context) { GitHubAccountStore(context) }
    val githubApi = remember(githubAccounts) { GitHubApiClient(githubAccounts) }
    LaunchedEffect(Unit) { listOf(WindowType.DEVELOPER, WindowType.AI, WindowType.TERMINAL, WindowType.GIT, WindowType.FILES).forEach(manager::open); manager.focus("ai") }
    var startOpen by remember { mutableStateOf(false) }
    // BUG FIX (video timestamps 00:05-00:25): enableEdgeToEdge() in MainActivity draws the
    // Compose content underneath the Android status bar and navigation bar, but this Box
    // previously never consumed those insets. That let window title bars (with the close/
    // minimize/maximize controls) render partly under the status bar, where they overlapped
    // the system clock/Wi-Fi/battery icons and were difficult or impossible to tap because the
    // system status bar and gesture-navigation area sit on top of that space.
    // windowInsetsPadding(WindowInsets.systemBars) shrinks the measured workspace to the real
    // usable area (below the status bar, above the nav/gesture bar) so every downstream
    // calculation - clampToWorkspace, windowDefaults, resizeWithinWorkspace - already works
    // with correct bounds instead of the full physical screen. imePadding() additionally keeps
    // the workspace (and any focused text field, e.g. the AI chat box or terminal input) above
    // the on-screen keyboard instead of letting the keyboard cover it.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050611))
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
    ) {
        LaunchedEffect(maxWidth.value, maxHeight.value) { manager.clampToWorkspace(maxWidth.value, (maxHeight.value - TASKBAR_HEIGHT).coerceAtLeast(1f)) }
        DesktopBackdrop()
        DesktopIcons(onOpen = { if (it == WindowType.BROWSER) manager.openNew(it) else manager.open(it) })
        windows.filter { it.state != WindowState.MINIMIZED }.sortedBy { it.z }.forEach { w ->
            DesktopWindowView(w, maxWidth.value, maxHeight.value - TASKBAR_HEIGHT, files, terminal, terminalLiveOutput, browser, githubAccounts, githubApi, settingsStore, offlineAi)
        }
        Taskbar(windows, startOpen, { startOpen = !startOpen }, { manager.open(it); startOpen = false }, Modifier.align(Alignment.BottomCenter))
        if (startOpen) StartMenu(onOpen = { if (it == WindowType.BROWSER) manager.openNew(it) else manager.open(it); startOpen = false }, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable private fun DesktopBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF02030A), Color(0xFF0A102C), Color(0xFF321A45))))
        val w = size.width; val h = size.height
        val mountain = Path().apply { moveTo(0f,h*0.78f); lineTo(w*.16f,h*.56f); lineTo(w*.28f,h*.72f); lineTo(w*.43f,h*.5f); lineTo(w*.62f,h*.73f); lineTo(w*.8f,h*.55f); lineTo(w,h*.74f); lineTo(w,h); lineTo(0f,h); close() }
        drawPath(mountain, Brush.verticalGradient(listOf(Color(0xFF211D46), Color(0xFF08091B)), startY=h*.48f, endY=h))
        drawCircle(Color(0x55B57CFF), 120f, Offset(w*.72f,h*.16f))
    }
}

@Composable private fun DesktopIcons(onOpen: (WindowType)->Unit) {
    val items = listOf("My Computer" to Icons.Default.Computer, "Files" to Icons.Default.Folder, "Developer" to Icons.Default.Code, "AI Assistant" to Icons.Default.Face, "Settings" to Icons.Default.Settings, "Browser" to Icons.Default.Public, "Trash" to Icons.Default.Delete)
    Column(Modifier.fillMaxHeight().width(76.dp).padding(top=18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items.forEach { (name, icon) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onOpen(when(name){"Developer"->WindowType.DEVELOPER;"AI Assistant"->WindowType.AI;"Files"->WindowType.FILES;"Settings"->WindowType.SETTINGS;"Browser"->WindowType.BROWSER; else->WindowType.FILES}) }.padding(4.dp)) {
                Surface(Modifier.size(38.dp), RoundedCornerShape(9.dp), color = Color(0xAA0B0D1B)) { Icon(icon, null, Modifier.padding(8.dp), tint = Color(0xFF9BCBFF)) }
                Text(name, fontSize=9.sp, color=Color.White, maxLines=1)
            }
        }
    }
}

@Composable private fun Taskbar(windows: List<DesktopWindow>, start: Boolean, onStart:()->Unit, onOpen:(WindowType)->Unit, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(Date()) }
    val context=LocalContext.current
    var battery by remember { mutableIntStateOf(-1) }
    var online by remember { mutableStateOf(false) }
    // BUG FIX: AudioManager gives legitimate, permission-free access to adjust/show the real media
    // volume (see the Volume icon below) without leaving the app for Settings.
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    LaunchedEffect(Unit){ while(kotlinx.coroutines.currentCoroutineContext().isActive){ now=Date(); val bm=context.getSystemService(BatteryManager::class.java); battery=bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1; val cm=context.getSystemService(ConnectivityManager::class.java); online=cm?.activeNetwork?.let{cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true; kotlinx.coroutines.delay(1000) } }
    // BUG FIX (screenshot: taskbar too tall/too much empty middle space): height dropped from a
    // fixed 58.dp to the shared TASKBAR_HEIGHT (50.dp) compact-dock size, and the pinned icons plus
    // system indicators now sit inside a horizontalScroll row so nothing gets clipped on narrow
    // portrait widths while the row still only takes the space it actually needs.
    Row(modifier.fillMaxWidth().height(TASKBAR_HEIGHT.dp).background(Color(0xE80A0B14)).border(1.dp,Color(0x443D78FF)),verticalAlignment=Alignment.CenterVertically){
        Spacer(Modifier.width(10.dp)); Surface(Modifier.size(34.dp).clickable(onClick=onStart),RoundedCornerShape(9.dp),color=Color(0xFF3D20A7)){Box(contentAlignment=Alignment.Center){Text("SA",fontWeight=FontWeight.Bold,fontSize=12.sp,color=Color(0xFFF2F0FF))}};Spacer(Modifier.width(8.dp))
        val pinned=listOf(WindowType.DEVELOPER to Icons.Default.Code,WindowType.AI to Icons.Default.Face,WindowType.TERMINAL to Icons.Default.Terminal,WindowType.GIT to Icons.Default.AccountTree,WindowType.FILES to Icons.Default.Folder,WindowType.BROWSER to Icons.Default.Public)
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
            pinned.forEach{(type,icon)->val open=windows.lastOrNull{it.type==type};Surface(Modifier.padding(horizontal=2.dp).size(36.dp).clickable{if(type==WindowType.BROWSER) manager.openNew(type) else onOpen(type)},RoundedCornerShape(9.dp),color=if(open?.focused==true)Color(0x443D78FF)else Color.Transparent){Box(contentAlignment=Alignment.Center){Icon(icon,type.name,tint=if(open!=null)Color(0xFFE1E7FF)else Color(0xFF7E89A8),modifier=Modifier.size(18.dp));if(open!=null)Box(Modifier.align(Alignment.BottomCenter).size(14.dp,2.dp).background(Color(0xFF9C5CFF),RoundedCornerShape(2.dp)))}}}
        }
        // BUG FIX: Wi-Fi/volume used to unconditionally launch the full Settings app, which is
        // disruptive and (per the task) shouldn't be the only option when a legitimate in-app
        // control exists. Volume now uses the real AudioManager media stream (no fake slider, no
        // permission needed) to show Android's own volume UI in place. Wi-Fi cannot be toggled by
        // apps targeting API 29+ (WifiManager.setWifiEnabled is a no-op for non-system apps since
        // Android 10) - faking a toggle would misrepresent real device state - so this uses
        // Settings.Panel.ACTION_WIFI, the least-disruptive legitimate control (an inline quick
        // panel rather than leaving the app), falling back to the full Wi-Fi settings screen only
        // if the panel intent isn't resolvable on this device.
        Icon(if(online)Icons.Default.Wifi else Icons.Default.WifiOff,"Network",Modifier.size(17.dp).clickable{
            runCatching{context.startActivity(Intent(Settings.Panel.ACTION_WIFI))}
                .onFailure{runCatching{context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}}
        },tint=Color(0xFFB9C7E8));Spacer(Modifier.width(8.dp));
        Icon(Icons.Default.VolumeUp,"Volume",Modifier.size(17.dp).clickable{
            audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        },tint=Color(0xFFB9C7E8));Spacer(Modifier.width(8.dp));
        Icon(Icons.Default.BatteryFull,"Battery",Modifier.size(17.dp).clickable{runCatching{context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))}},tint=Color(0xFFB9C7E8));Spacer(Modifier.width(5.dp));if(battery>=0)Text("$battery%",fontSize=9.sp,color=Color(0xFFB9C7E8));Spacer(Modifier.width(8.dp))
        // BUG FIX (screenshot: clock text almost black/invisible): the HH:mm Text() had no
        // explicit color at all, so it inherited whatever low-contrast content color the theme
        // provided. Both the time and date now use explicit light colors, and the real
        // SimpleDateFormat(now) time source is unchanged (still updates every second above).
        Column(horizontalAlignment=Alignment.End){Text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(now),fontSize=12.sp,color=Color(0xFFF2F4FF),fontWeight=FontWeight.Bold);Text(SimpleDateFormat("dd/MM/yyyy",Locale.getDefault()).format(now),fontSize=8.sp,color=Color(0xFFB6C0E0))};Spacer(Modifier.width(10.dp))
    }
}

@Composable private fun StartMenu(onOpen:(WindowType)->Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(start=10.dp,bottom=64.dp).width(270.dp).height(360.dp), RoundedCornerShape(18.dp), color=Color(0xF20A0B15), shadowElevation=18.dp) {
        Column(Modifier.padding(16.dp)) { Text("SA Desktop",fontSize=20.sp,fontWeight=FontWeight.Bold); Text("AI developer workstation",fontSize=11.sp,color=Color(0xFF9CA7C8)); Spacer(Modifier.height(14.dp)); listOf("Developer Workspace" to WindowType.DEVELOPER,"AI Assistant - Sara" to WindowType.AI,"Terminal" to WindowType.TERMINAL,"Git" to WindowType.GIT,"File Manager" to WindowType.FILES,"Browser" to WindowType.BROWSER,"Settings" to WindowType.SETTINGS).forEach{(t,w)->Text(t,Modifier.fillMaxWidth().clickable{onOpen(w)}.padding(12.dp),color=Color.White)} }
    }
}

@Composable private fun DesktopWindowView(w: DesktopWindow, screenW:Float, screenH:Float, files: FileService, terminal: TerminalService, liveOutput: kotlinx.coroutines.flow.StateFlow<String>, browser: AndroidBrowserService, githubAccounts: GitHubAccountStore, githubApi: GitHubApiClient, settingsStore: AISettingsStore, offlineAi: LocalLlamaEngine) {
    val default = windowDefaults(w.type, screenW, screenH)
    LaunchedEffect(w.id, w.width, w.height) {
        if (w.width <= 0f || w.height <= 0f) manager.initializeBounds(w.id, default)
    }
    val width = if(w.state==WindowState.MAXIMIZED) screenW-16f else if(w.width>0) w.width else default.width
    val height = if(w.state==WindowState.MAXIMIZED) screenH-10f else if(w.height>0) w.height else default.height
    val x = if(w.state==WindowState.MAXIMIZED) 8f else w.x.coerceIn(4f, (screenW-width-4f).coerceAtLeast(4f))
    val y = if(w.state==WindowState.MAXIMIZED) 4f else w.y.coerceIn(4f, (screenH-height-4f).coerceAtLeast(4f))

    Surface(
        Modifier.offset(x.dp,y.dp).size(width.dp,height.dp),
        RoundedCornerShape(10.dp),
        color=Color(0xF10A0B12),
        border=BorderStroke(1.dp, if(w.focused) Color(0xFF435B9B) else Color(0xFF252A3B)),
        shadowElevation=if(w.focused) 18.dp else 8.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            WindowTitleBar(w, screenW, screenH, width, height)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                // BUG FIX (terminal/window pointer isolation, rules 2 & 15): the resize-handle
                // strips below used to sit in the exact same Box as the window content, fully
                // overlapping it along every edge. On a short "mini" window (very common right
                // after rotating to landscape, where available height shrinks a lot) the bottom
                // resize band could cover the terminal's input row / a window's last visible
                // pixels, so a tap meant for the input or a button, or a drag meant to scroll,
                // could be captured by the resize gesture instead. Insetting the content by the
                // handle's own thickness whenever the window is resizable keeps the true edge
                // pixels reserved for resizing while guaranteeing the window's real content -
                // including scrollable panes - is never drawn underneath a drag-only strip.
                val contentModifier = if (w.state == WindowState.NORMAL)
                    Modifier.fillMaxSize().padding(end = RESIZE_HANDLE_INSET, bottom = RESIZE_HANDLE_INSET)
                else Modifier.fillMaxSize()
                Box(contentModifier) {
                    when(w.type){
                        WindowType.DEVELOPER->DeveloperWindow(files)
                        WindowType.AI->AIWindow(browser, settingsStore, offlineAi)
                        WindowType.TERMINAL->TerminalWindow(terminal, liveOutput, files)
                        WindowType.GIT->GitWindow(files, githubAccounts, githubApi)
                        WindowType.FILES->FilesWindow(files)
                        WindowType.SETTINGS->SettingsWindow(settingsStore, offlineAi)
                        WindowType.BROWSER->BrowserWindow(w.id, browser)
                    }
                }
                if(w.state==WindowState.NORMAL) ResizeHandle(w, width, height, screenW, screenH)
            }
        }
    }
}

private fun windowDefaults(type: WindowType, screenW: Float, screenH: Float): WindowBounds {
    val usableH=(screenH-(TASKBAR_HEIGHT+10f)).coerceAtLeast(300f)
    val wide=screenW>=800f
    val developerW=(if(wide) 500f else screenW*0.64f).coerceIn(300f,620f)
    val developerH=(if(wide) 420f else usableH*0.64f).coerceIn(260f,500f)
    val aiW=(if(wide) 365f else screenW*0.56f).coerceIn(280f,420f)
    val aiH=(if(wide) 390f else usableH*0.62f).coerceIn(280f,470f)
    return when(type){
        WindowType.DEVELOPER->WindowBounds(if(wide)105f else 8f, if(wide)52f else 40f, developerW, developerH)
        WindowType.AI->WindowBounds(if(wide)610f else (screenW-aiW-8f).coerceAtLeast(8f), if(wide)54f else 40f, aiW, aiH)
        WindowType.TERMINAL->WindowBounds(18f, (usableH-305f).coerceAtLeast(100f), (if(wide)330f else screenW-16f).coerceAtLeast(280f), 300f.coerceAtMost(usableH))
        WindowType.GIT->WindowBounds(if(wide)355f else 8f, (usableH-305f).coerceAtLeast(100f), (if(wide)330f else screenW-16f).coerceAtLeast(280f), 300f.coerceAtMost(usableH))
        WindowType.FILES->WindowBounds(if(wide)680f else 8f, (usableH-305f).coerceAtLeast(100f), (if(wide)330f else screenW-16f).coerceAtLeast(280f), 300f.coerceAtMost(usableH))
        WindowType.SETTINGS->WindowBounds(120f,90f,(screenW*0.42f).coerceIn(300f,420f),(usableH*0.65f).coerceIn(300f,500f))
        WindowType.BROWSER->WindowBounds(150f,100f,(screenW*0.58f).coerceIn(320f,720f),(usableH*0.70f).coerceIn(300f,560f))
    }
}

// Shared with the content inset in DesktopWindowView so the resize-only strip and real window
// content never occupy the exact same pixels (see the BUG FIX comment there).
private val RESIZE_HANDLE_INSET = 12.dp

@Composable private fun ResizeHandle(w: DesktopWindow, width: Float, height: Float, screenW: Float, screenH: Float) {
    val handle = RESIZE_HANDLE_INSET
    fun drag(edge: ResizeEdge) = Modifier.pointerInput(w.id, edge) {
        detectDragGestures(
            onDragStart = { manager.focus(w.id) },
            onDrag = { _, delta -> manager.resizeWithinWorkspace(w.id, edge, delta.x, delta.y, screenW, screenH) }
        )
    }
    Box(Modifier.fillMaxSize()) {
        Box(drag(ResizeEdge.RIGHT).align(Alignment.CenterEnd).fillMaxHeight().width(handle))
        Box(drag(ResizeEdge.BOTTOM).align(Alignment.BottomCenter).fillMaxWidth().height(handle))
        Box(drag(ResizeEdge.BOTTOM_RIGHT).align(Alignment.BottomEnd).size(20.dp)) {
            Icon(Icons.Default.Expand, null, Modifier.align(Alignment.BottomEnd).padding(4.dp).size(13.dp), tint=Color(0xFF59627A))
        }
        Box(drag(ResizeEdge.LEFT).align(Alignment.CenterStart).fillMaxHeight().width(handle))
        Box(drag(ResizeEdge.TOP).align(Alignment.TopCenter).fillMaxWidth().height(handle))
        Box(drag(ResizeEdge.TOP_LEFT).align(Alignment.TopStart).size(14.dp))
        Box(drag(ResizeEdge.TOP_RIGHT).align(Alignment.TopEnd).size(14.dp))
        Box(drag(ResizeEdge.BOTTOM_LEFT).align(Alignment.BottomStart).size(14.dp))
    }
}

@Composable private fun WindowTitleBar(w:DesktopWindow, screenW:Float, screenH:Float, width:Float, height:Float){
    Row(Modifier.fillMaxWidth().height(34.dp).background(if(w.focused) Color(0xFF121522) else Color(0xFF0C0D13)).padding(horizontal=8.dp).pointerInput(w.id) {
        detectDragGestures(onDragStart={manager.focus(w.id)}, onDrag={ _, drag ->
            if (w.state == WindowState.NORMAL) {
                manager.move(w.id, drag.x, drag.y)
                manager.clampToWorkspace(screenW, screenH)
            }
        })
    },verticalAlignment=Alignment.CenterVertically){
        Icon(Icons.Default.DragHandle,null,Modifier.size(16.dp),tint=if(w.focused) Color(0xFF8F9FC8) else Color(0xFF59627A))
        Text(if (w.protectedByTaskId != null) "🔒 ${w.title}" else w.title,Modifier.weight(1f).padding(start=6.dp),fontSize=12.sp,color=Color(0xFFE5E9F7),maxLines=1)
        // BUG FIX (latest screenshot: min/max/close look like "strange different-color blocks"):
        // minimize and maximize previously used a translucent WHITE overlay (0x14FFFFFF) while
        // close used a translucent PINK overlay (0x26FF5C7A) - on top of title bars that already
        // swap between two background shades (focused/unfocused), those two different overlay
        // hues rendered as visibly mismatched blocks instead of one consistent control family.
        // All three buttons now share one flat, same-height, same-shape base surface color drawn
        // from the title bar's own dark palette (not a translucent tint), so they read as one
        // design regardless of focus state; close keeps a subtle same-family danger tint (a dark
        // red base instead of the neutral base) rather than a different color system. This is a
        // shared composable, so Developer/AI/every other window gets the fix at once.
        val controlBase = Color(0xFF1C2036)
        val closeBase = Color(0xFF3A1E29)
        val iconTint = Color(0xFFEDEFFA)
        val closeTint = Color(0xFFFF9FB2)
        IconButton({manager.minimize(w.id)},Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(controlBase)){Icon(Icons.Default.Remove,"Minimize",Modifier.size(16.dp),tint=iconTint)}
        IconButton({if(w.state==WindowState.MAXIMIZED)manager.restore(w.id)else manager.maximize(w.id)},Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(controlBase)){Icon(if(w.state==WindowState.MAXIMIZED)Icons.Default.FullscreenExit else Icons.Default.CropSquare,"Maximize",Modifier.size(14.dp),tint=iconTint)}
        IconButton({manager.close(w.id)},Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)).background(closeBase)){Icon(Icons.Default.Close,"Close",Modifier.size(16.dp),tint=closeTint)}
    }
}

@Composable private fun DeveloperWindow(files: FileService){
    var tree by remember { mutableStateOf(files.projectTree()) }
    val initialFile = remember(files) { files.listDirectory("src").value.orEmpty().firstOrNull { it.kind != FileKind.FOLDER }?.path }
    var tabs by remember(initialFile) { mutableStateOf(initialFile?.let { listOf(it) } ?: emptyList()) }
    var selectedTab by remember(initialFile) { mutableStateOf(initialFile.orEmpty()) }
    var buffers by remember(initialFile) { mutableStateOf<Map<String, String>>(initialFile?.let { mapOf(it to files.read(it).value.orEmpty()) } ?: emptyMap()) }
    var savedBuffers by remember { mutableStateOf<Map<String, String>>(buffers) }

    fun openFile(path: String) {
        if (!tabs.contains(path)) tabs = tabs + path
        if (!buffers.containsKey(path)) buffers = buffers + (path to files.read(path).value.orEmpty())
        if (!savedBuffers.containsKey(path)) savedBuffers = savedBuffers + (path to files.read(path).value.orEmpty())
        selectedTab = path
    }
    fun saveFile(path: String) {
        files.write(path, buffers[path].orEmpty())
        savedBuffers = savedBuffers + (path to buffers[path].orEmpty())
        tree = files.projectTree()
    }
    fun closeTab(path: String) {
        if (buffers[path] != savedBuffers[path]) return
        val nextTabs = tabs - path
        tabs = nextTabs
        if (selectedTab == path) selectedTab = tabs.firstOrNull().orEmpty()
    }

    // BUG FIX (screenshot 4A: code cut off in the mini window): the Explorer panel used a fixed
    // 185.dp width regardless of how narrow the window was. On a small window that left almost
    // no horizontal room for the editor, so the TextField wrapped every line (even mid-word, e.g.
    // "import" split into "impo"/"rt") instead of showing real code. The panel now measures the
    // real available width (BoxWithConstraints, not a hardcoded phone size) and collapses into a
    // toggleable icon rail below a width threshold, per rule 22 ("Explorer may collapse into a
    // real toggle" with "a real way to access collapsed panels"), so the editor always gets the
    // majority of the window's width instead of being starved by a fixed sidebar.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val narrow = maxWidth < 480.dp
        var explorerOpen by remember(narrow) { mutableStateOf(!narrow) }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (!narrow || explorerOpen) {
                    Column(Modifier.width(if (narrow) 220.dp else 185.dp).fillMaxHeight().background(Color(0xFF0B0C13)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("EXPLORER", fontSize=11.sp, color=Color(0xFF98A5C8), modifier=Modifier.weight(1f))
                            if (narrow) IconButton(onClick={ explorerOpen=false }, modifier=Modifier.size(26.dp)) { Icon(Icons.Default.ChevronLeft, "Collapse explorer", tint=Color(0xFF9BB8FF), modifier=Modifier.size(16.dp)) }
                            IconButton(onClick={ tree = files.projectTree() }, modifier=Modifier.size(26.dp)) { Icon(Icons.Default.Refresh, "Refresh", tint=Color(0xFF9BB8FF), modifier=Modifier.size(15.dp)) }
                        }
                        Text("MyProject", fontSize=10.sp, color=Color(0xFF6F7C9F), modifier=Modifier.padding(vertical=5.dp))
                        Tree(tree,0){ path -> openFile(path); if(narrow) explorerOpen=false }
                    }
                } else {
                    IconButton(onClick={ explorerOpen=true }, modifier=Modifier.fillMaxHeight().width(34.dp).background(Color(0xFF0B0C13))) { Icon(Icons.Default.ChevronRight, "Open explorer", tint=Color(0xFF9BB8FF)) }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    EditorTabs(tabs, selectedTab, buffers, savedBuffers, onSelect={selectedTab=it}, onClose={closeTab(it)})
                    val current = buffers[selectedTab].orEmpty()
                    CodeEditor(
                        code=current,
                        filePath=selectedTab,
                        onChange={ text -> buffers = buffers + (selectedTab to text) },
                        onSave={ saveFile(selectedTab) }
                    )
                }
            }
            val current = buffers[selectedTab].orEmpty()
            val dirty = current != savedBuffers[selectedTab]
            // BUG FIX (Rule 1/17 endpoint-correctness): EditorTabs only ever shows the bare
            // filename (path.substringAfterLast('/')), so a file opened from a sub-folder (e.g.
            // the default "src/main.py") looked identical to a tab for "main.py" at the workspace
            // root. The terminal's cwd is the workspace root, not "src/", so `python main.py`
            // failed with "No such file or directory" even though the file was genuinely saved —
            // the user had no way to see the real relative path to run it from. This status-bar
            // line now always shows the real relative path (never just the filename), plus the
            // exact runnable command for the file's own language, so what to type in Terminal is
            // never a guess.
            val runHint = when (selectedTab.substringAfterLast('.', "").lowercase()) {
                "py" -> "python $selectedTab"
                "js" -> "node $selectedTab"
                else -> null
            }
            Row(Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF0B0C13)).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
                Text("Ln 1, Col 1",fontSize=9.sp,color=Color(0xFFAAB4D4))
                Spacer(Modifier.width(12.dp))
                if (selectedTab.isNotBlank()) {
                    Text(selectedTab,fontSize=9.sp,color=Color(0xFF6F7C9F),maxLines=1)
                }
                if (runHint != null) {
                    Spacer(Modifier.width(12.dp))
                    Text("Run: $runHint",fontSize=9.sp,color=Color(0xFF7FA8E0),maxLines=1)
                }
                Spacer(Modifier.weight(1f))
                Text(if(dirty) "Unsaved changes" else "Saved",fontSize=9.sp,color=if(dirty) Color(0xFFFFB45B) else Color(0xFF7FE0A2))
                Spacer(Modifier.width(12.dp))
                Text("UTF-8   Kotlin/Compose editor core",fontSize=9.sp,color=Color(0xFF8F9DBA))
            }
        }
    }
}

@Composable private fun EditorTabs(
    tabs: List<String>, selected: String, buffers: Map<String,String>, saved: Map<String,String>,
    onSelect:(String)->Unit, onClose:(String)->Unit
){
    Row(Modifier.fillMaxWidth().height(36.dp).background(Color(0xFF0A0B11)).horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically){
        tabs.forEach { path ->
            val active = path == selected
            val dirty = buffers[path] != saved[path]
            Surface(Modifier.padding(start=2.dp,top=2.dp,bottom=2.dp).widthIn(min=115.dp).height(32.dp).clickable{onSelect(path)}, color=if(active)Color(0xFF15182A)else Color.Transparent, shape=RoundedCornerShape(topStart=7.dp,topEnd=7.dp)){
                Row(Modifier.fillMaxSize().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                    // BUG FIX (same root cause as the status-bar fix above): showing only the bare
                    // filename made a file inside a sub-folder (e.g. "src/main.py") visually
                    // identical to one at the workspace root ("main.py"). When two open tabs would
                    // otherwise collide on that bare name, show "parentFolder/file.ext" instead so
                    // the tab itself already disambiguates — no click/hover needed to find out.
                    val collidesWithAnotherTab = tabs.any { other -> other != path && other.substringAfterLast('/') == path.substringAfterLast('/') }
                    val label = if (collidesWithAnotherTab && path.contains('/')) {
                        val parent = path.substringBeforeLast('/').substringAfterLast('/')
                        "$parent/${path.substringAfterLast('/')}"
                    } else path.substringAfterLast('/')
                    Text(if(dirty) "● " else "",fontSize=9.sp,color=Color(0xFFFFB45B)); Text(label,Modifier.weight(1f),fontSize=10.sp,color=if(active)Color.White else Color(0xFF8E98B3),maxLines=1)
                    IconButton({onClose(path)},Modifier.size(22.dp)){Icon(Icons.Default.Close,"Close",Modifier.size(12.dp),tint=Color(0xFF7E89A8))}
                }
            }
        }
    }
}

@Composable private fun Tree(node:ProjectFile,depth:Int,onFile:(String)->Unit){
    if(node.path.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable{if(node.kind!=FileKind.FOLDER)onFile(node.path)}.padding(start=(depth*12).dp, top=5.dp, bottom=5.dp)){
        Icon(if(node.kind==FileKind.FOLDER)Icons.Default.Folder else Icons.Default.Description,null,Modifier.size(14.dp),tint=if(node.kind==FileKind.FOLDER)Color(0xFF70AFFF)else Color(0xFFB7C0D8));Text(node.name,Modifier.padding(start=6.dp),fontSize=11.sp,maxLines=1)
    }
    node.children.forEach{Tree(it,depth+1,onFile)}
}

private fun languageFor(path:String): String = when(path.substringAfterLast('.').lowercase()){
    "py"->"Python"; "kt"->"Kotlin"; "java"->"Java"; "js"->"JavaScript"; "cpp","cc","cxx"->"C++"; "c"->"C"; "json"->"JSON"; "md"->"Markdown"; "xml"->"XML"; else->"Text"
}

private fun highlightCode(code:String): AnnotatedString = buildAnnotatedString {
    val keywords = setOf("fun","val","var","class","object","if","else","for","while","return","import","package","when","true","false","null","def","from","in","and","or","not","print","const","let","function","public","private","void","int","new","extends")
    val regex = Regex("(\\\"[^\\\"]*\\\"|'[^']*'|//[^\\n]*|#[^\\n]*|\\b\\d+(?:\\.\\d+)?\\b|\\b[A-Za-z_][A-Za-z0-9_]*\\b)")
    var last=0
    regex.findAll(code).forEach { m ->
        append(code.substring(last,m.range.first))
        val token=m.value
        val style=when{
            token.startsWith("\"") || token.startsWith("'")->SpanStyle(color=Color(0xFFB9E7A7))
            token.startsWith("//") || token.startsWith("#")->SpanStyle(color=Color(0xFF65728F))
            token.firstOrNull()?.isDigit()==true->SpanStyle(color=Color(0xFFFFC978))
            token in keywords->SpanStyle(color=Color(0xFFC08CFF),fontWeight=FontWeight.Bold)
            else->null
        }
        if(style==null) append(token) else withStyle(style){append(token)}
        last=m.range.last+1
    }
    append(code.substring(last))
}

/** Rule 13 (cleaner-viewer helper) + Rule 15 (small single-purpose sub-helper): lightweight,
 *  dependency-free markdown for AI chat bubbles ONLY — fenced ```code``` blocks (reusing
 *  [highlightCode] so colors match the editor), **bold**, `inline code`, and "- "/"* " bullet
 *  lines. This is not a real markdown/HTML parser: anything it doesn't recognise is emitted as
 *  plain text unchanged, so no message content is ever lost, reordered, or altered — only
 *  presentation is added on top of the exact same text. */
private fun renderChatMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val codeFence = Regex("```[a-zA-Z0-9]*\\n?([\\s\\S]*?)```")
    var cursor = 0
    codeFence.findAll(text).forEach { block ->
        appendMarkdownLines(text.substring(cursor, block.range.first))
        val body = block.groupValues[1].trimEnd('\n')
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF1B1D2B), color = Color(0xFFE7EAF7))) {
            append(" ")
            append(highlightCode(body))
            append(" ")
        }
        cursor = block.range.last + 1
    }
    appendMarkdownLines(text.substring(cursor))
}

private fun AnnotatedString.Builder.appendMarkdownLines(segment: String) {
    if (segment.isEmpty()) return
    segment.lines().forEachIndexed { index, rawLine ->
        if (index > 0) append("\n")
        val trimmed = rawLine.trimStart()
        val bulleted = trimmed.startsWith("- ") || trimmed.startsWith("* ")
        if (bulleted) append("•  ")
        val body = if (bulleted) trimmed.removePrefix("- ").removePrefix("* ") else rawLine
        appendMarkdownInline(body)
    }
}

private fun AnnotatedString.Builder.appendMarkdownInline(line: String) {
    val inline = Regex("\\*\\*([^*]+)\\*\\*|`([^`]+)`")
    var last = 0
    inline.findAll(line).forEach { m ->
        append(line.substring(last, m.range.first))
        val bold = m.groupValues[1]
        if (bold.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF20222F), color = Color(0xFFFFC978))) {
                append(m.groupValues[2])
            }
        }
        last = m.range.last + 1
    }
    append(line.substring(last))
}

@Composable private fun CodeEditor(code:String,filePath:String,onChange:(String)->Unit,onSave:()->Unit){
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var showGoto by remember { mutableStateOf(false) }
    var gotoLine by remember { mutableStateOf("") }
    var history by remember(filePath) { mutableStateOf(listOf(code)) }
    var historyIndex by remember(filePath) { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val lines = code.lines()
    val matches = if(query.isBlank()) 0 else Regex(Regex.escape(query),RegexOption.IGNORE_CASE).findAll(code).count()
    val scope = rememberCoroutineScope()

    fun change(text:String){
        if(text==code) return
        val base=history.take(historyIndex+1)
        history=(base+text).takeLast(50)
        historyIndex=history.lastIndex
        onChange(text)
    }
    fun undo(){ if(historyIndex>0){historyIndex--;onChange(history[historyIndex])} }
    fun redo(){ if(historyIndex<history.lastIndex){historyIndex++;onChange(history[historyIndex])} }

    Column(Modifier.fillMaxSize().background(Color(0xFF090A10))){
        Row(Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF10121B)),verticalAlignment=Alignment.CenterVertically){
            Text(languageFor(filePath),fontSize=9.sp,color=Color(0xFF7F8CAB),modifier=Modifier.padding(horizontal=9.dp))
            Spacer(Modifier.weight(1f))
            IconButton(onClick={undo()}, enabled=historyIndex>0, modifier=Modifier.size(28.dp)){Icon(Icons.Default.Undo,"Undo",Modifier.size(15.dp))}
            IconButton(onClick={redo()}, enabled=historyIndex<history.lastIndex, modifier=Modifier.size(28.dp)){Icon(Icons.Default.Redo,"Redo",Modifier.size(15.dp))}
            IconButton(onClick={showSearch=!showSearch}, modifier=Modifier.size(28.dp)){Icon(Icons.Default.Search,"Search",Modifier.size(15.dp))}
            IconButton(onClick={showGoto=!showGoto}, modifier=Modifier.size(28.dp)){Icon(Icons.Default.FormatListNumbered,"Go to line",Modifier.size(15.dp))}
            IconButton(onClick={onSave()}, modifier=Modifier.size(28.dp)){Icon(Icons.Default.Save,"Save",Modifier.size(15.dp))}
        }
        if(showSearch) Row(Modifier.fillMaxWidth().padding(5.dp),verticalAlignment=Alignment.CenterVertically){
            TextField(query,{query=it},Modifier.weight(1f),singleLine=true,placeholder={Text("Find",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF151724),unfocusedContainerColor=Color(0xFF151724),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),textStyle=LocalTextStyle.current.copy(fontSize=10.sp))
            Spacer(Modifier.width(4.dp)); Text("$matches",fontSize=9.sp,color=Color(0xFF8F9DBA));
            TextField(replace,{replace=it},Modifier.width(110.dp),singleLine=true,placeholder={Text("Replace",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF151724),unfocusedContainerColor=Color(0xFF151724),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),textStyle=LocalTextStyle.current.copy(fontSize=10.sp))
            TextButton(onClick={if(query.isNotEmpty())change(code.replace(query,replace,ignoreCase=true))}){Text("Replace all",fontSize=9.sp)}
        }
        if(showGoto) Row(Modifier.fillMaxWidth().padding(5.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Go to line",fontSize=10.sp,color=Color(0xFF9AA7C8));Spacer(Modifier.width(8.dp));TextField(gotoLine,{gotoLine=it.filter(Char::isDigit)},Modifier.width(90.dp),singleLine=true,colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF151724),unfocusedContainerColor=Color(0xFF151724),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),textStyle=LocalTextStyle.current.copy(fontSize=10.sp));
            TextButton(onClick={val n=gotoLine.toIntOrNull()?.coerceIn(1,lines.size) ?: 1;scope.launch { scroll.animateScrollTo(((n-1)*18).coerceAtLeast(0)) };showGoto=false}){Text("Jump",fontSize=9.sp)}
        }
        // BUG FIX (screenshot 4A): the TextField had no horizontal scroll, so in a narrow window
        // it soft-wrapped every line - even mid-word ("import" rendered as "impo" / "rt" on two
        // lines) - which is exactly the "clipped/destroyed formatting" bug called out in the
        // report. Wrapping the field in its own horizontalScroll container and sizing it to its
        // intrinsic (unwrapped) content width means long lines now scroll sideways instead of
        // breaking, while the outer Row still scrolls vertically in sync with the line numbers.
        val hScroll = rememberScrollState()
        Row(Modifier.fillMaxSize().weight(1f)){
            Column(Modifier.width(48.dp).verticalScroll(scroll).padding(top=9.dp),horizontalAlignment=Alignment.End){lines.indices.forEach{Text("${it+1}",fontSize=10.sp,color=if(it+1==1)Color(0xFFB5C5FF)else Color(0xFF525A73),modifier=Modifier.padding(end=8.dp))}}
            Box(Modifier.fillMaxSize().horizontalScroll(hScroll).verticalScroll(scroll)) {
                TextField(value=code,onValueChange={change(it)},Modifier,visualTransformation=object:VisualTransformation{
                    override fun filter(text:AnnotatedString)=TransformedText(highlightCode(text.text),OffsetMapping.Identity)
                },textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp,color=Color(0xFFDDE5FF)),colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),singleLine=false)
            }
        }
    }
}

/** Color + icon for one real [WorkflowStepKind], matching the "MODERN COLOR PALETTE" /
 *  "ANIMATED THINKING EXAMPLES" reference design. Purely presentational — the step data itself
 *  (label/detail/elapsed time) always comes from ModelRouter's real, timestamped steps. */
private fun workflowStepVisual(kind: WorkflowStepKind): Pair<Color, androidx.compose.ui.graphics.vector.ImageVector> = when (kind) {
    WorkflowStepKind.PLANNING -> Color(0xFF8B5CF6) to Icons.Default.Lightbulb
    WorkflowStepKind.ANALYZING -> Color(0xFF00BFFF) to Icons.Default.Search
    WorkflowStepKind.INVESTIGATING -> Color(0xFF06D6A0) to Icons.Default.TravelExplore
    WorkflowStepKind.EDITING -> Color(0xFFF59E0B) to Icons.Default.Edit
    WorkflowStepKind.BUILDING -> Color(0xFF00BFFF) to Icons.Default.Build
    WorkflowStepKind.SUCCESS -> Color(0xFF22C55E) to Icons.Default.CheckCircle
    WorkflowStepKind.ERROR -> Color(0xFFEF4444) to Icons.Default.Warning
}

/** Single pulsing dot (scale in/out) — used for Planning ("Pulse" in the reference design) and
 *  Editing. Real Compose infinite-animation API, not a static icon standing in for motion. */
@Composable private fun PulseDotIndicator(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(9.dp).scale(scale).background(color, CircleShape))
    }
}

/** Three vertical bars bouncing out of phase — "Wave", used for Analyzing. */
@Composable private fun WaveBarsIndicator(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val bars = (0 until 3).map { index ->
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(450, delayMillis = index * 120, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "waveBar$index"
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { h -> Box(Modifier.width(2.5.dp).height((11 * h.value).dp).background(color, RoundedCornerShape(1.dp))) }
    }
}

/** Three dots fading in sequence — "Scan"/"Running" feel, used for Investigating and Building. */
@Composable private fun RunningDotsIndicator(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "running")
    val dots = (0 until 3).map { index ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(400, delayMillis = index * 130, easing = LinearEasing),
                RepeatMode.Reverse
            ),
            label = "runningDot$index"
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        dots.forEach { a -> Box(Modifier.size(4.5.dp).background(color.copy(alpha = a.value), CircleShape)) }
    }
}

/** Icon shaking side-to-side — "Shake" in the reference design's Animation Guide, used for
 *  Error. A real horizontal-offset infinite animation (not a re-skinned pulse), so a failing
 *  step is visually distinct from a normal in-progress step at a glance. */
@Composable private fun ShakeIndicator(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shake")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(90, easing = LinearEasing), RepeatMode.Reverse),
        label = "shakeOffset"
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(
            Icons.Default.Warning, null, tint = color,
            modifier = Modifier.size(12.dp).offset(x = (offset * 2).dp)
        )
    }
}

/** Dispatches the real animation style per real, currently-open WorkflowStepKind (Rule 1
 *  endpoint: every kind maps to something, nothing falls through to a generic default). Only
 *  reached for an OPEN step — a finished step already shows its static icon via
 *  [workflowStepVisual], unchanged. Mapping matches the reference design's Animation Guide:
 *  Pulse=Planning/Editing, Wave=Analyzing, Scan(running dots)=Investigating/Building,
 *  Shake=Error. Success is a completed/closed state in practice (it always carries an
 *  endedAtMs), so it renders via the static check-circle icon in [workflowStepVisual] rather
 *  than reaching this dispatcher — Pulse is kept here only as a safe, real fallback if a
 *  SUCCESS step is ever still open. */
@Composable private fun StepAnimationIndicator(kind: WorkflowStepKind, color: Color, modifier: Modifier = Modifier) {
    when (kind) {
        WorkflowStepKind.PLANNING, WorkflowStepKind.EDITING -> PulseDotIndicator(color, modifier)
        WorkflowStepKind.ANALYZING -> WaveBarsIndicator(color, modifier)
        WorkflowStepKind.INVESTIGATING, WorkflowStepKind.BUILDING -> RunningDotsIndicator(color, modifier)
        WorkflowStepKind.ERROR -> ShakeIndicator(color, modifier)
        WorkflowStepKind.SUCCESS -> PulseDotIndicator(color, modifier)
    }
}

/** mm:ss for a real elapsed-millis value — never a placeholder, always derived from a real
 *  start/end timestamp pair captured in ModelRouter. */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/** The "Smart Workflow Indicator" / "Thinking Details" panel from the reference design: one row
 *  per real WorkflowStep the router actually went through this turn, each with its real elapsed
 *  time. Ticks once a second (via [nowMs]) only while a step is still open, so an in-progress
 *  step's timer visibly runs instead of staying frozen. */
@Composable private fun ThinkingDetailsPanel(steps: List<WorkflowStep>, nowMs: Long, onClose: () -> Unit) {
    if (steps.isEmpty()) return
    val totalMs = (steps.lastOrNull()?.endedAtMs ?: nowMs) - steps.first().startedAtMs
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF1E2938))
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Thinking Details", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0))
                IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = Color(0xFF8996B5), modifier = Modifier.size(14.dp))
                }
            }
            Spacer(Modifier.height(4.dp))
            steps.forEach { step ->
                val (color, icon) = workflowStepVisual(step.kind)
                val elapsed = (step.endedAtMs ?: nowMs) - step.startedAtMs
                val open = step.endedAtMs == null
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(Modifier.size(22.dp), RoundedCornerShape(11.dp), color = color.copy(alpha = 0.18f)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (open) {
                                StepAnimationIndicator(step.kind, color, Modifier.size(14.dp))
                            } else {
                                Icon(icon, step.label, tint = color, modifier = Modifier.size(13.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(step.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color)
                        Text(step.detail, fontSize = 9.sp, color = Color(0xFF9AA7C8), maxLines = 1)
                    }
                    Text(formatElapsed(elapsed), fontSize = 9.sp, color = Color(0xFF7C86A6))
                }
            }
            Spacer(Modifier.height(2.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Total Time", fontSize = 9.sp, color = Color(0xFF7C86A6))
                Text(formatElapsed(totalMs), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE2E8F0))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// AI chat card rendering (Rule 15 sub-helpers of AIWindow): a real file-write diff card and a
// real build-result card, parsed from the exact same message strings the chat already stores —
// no separate/fake data source. If a message doesn't match either shape it just falls through to
// the existing plain bubble (see the `msgs.forEach` loop in AIWindow), so no message is ever lost.
// ---------------------------------------------------------------------------------------------

private data class ParsedFileDiff(
    val path: String,
    val summaryLine: String,
    val diffLines: List<Pair<Char, String>>,
    val fullContent: String?
)

/** Parses the §§FILE_DIFF§§...§§END_DIFF§§ (+ optional §§FULL_CONTENT§§) block that
 *  [com.sa.aidesktop.core.ai.tools.WriteFileTool] now emits (via ChatDiffUtil) into real diff
 *  lines. Returns null for any message that isn't a write_file result — those keep rendering as
 *  a normal bubble. */
private fun parseFileDiffMessage(text: String): ParsedFileDiff? {
    val diffStart = text.indexOf("\u00A7\u00A7FILE_DIFF\u00A7\u00A7")
    if (diffStart < 0) return null
    val diffEnd = text.indexOf("\u00A7\u00A7END_DIFF\u00A7\u00A7", diffStart)
    if (diffEnd < 0) return null
    val summaryLine = text.substring(0, diffStart).removePrefix("Applied:").trim()
    val diffSection = text.substring(diffStart + "\u00A7\u00A7FILE_DIFF\u00A7\u00A7".length, diffEnd)
    val lines = diffSection.split('\n')
    val path = lines.firstOrNull()?.removePrefix("path=").orEmpty()
    val diffLines = lines.drop(1).filter { it.length >= 2 }.map { it[0] to it.substring(2) }
    val fullStart = text.indexOf("\u00A7\u00A7FULL_CONTENT\u00A7\u00A7")
    val fullEnd = text.indexOf("\u00A7\u00A7END_FULL\u00A7\u00A7")
    val fullContent = if (fullStart >= 0 && fullEnd > fullStart) {
        text.substring(fullStart + "\u00A7\u00A7FULL_CONTENT\u00A7\u00A7".length, fullEnd).trim('\n')
    } else null
    return ParsedFileDiff(path, summaryLine, diffLines, fullContent)
}

private data class ParsedBuildResult(val success: Boolean, val exitInfo: String, val command: String, val output: String)

/** Parses the real "Applied: BUILD/TEST SUCCESS..." / "Action failed: BUILD/TEST FAILED..."
 *  text that [com.sa.aidesktop.core.coding.BuildProjectTool] already produces — no new/fake
 *  success or failure signal is invented here, only the existing real text is split apart for a
 *  styled card. Returns null for any message that isn't a build/test tool result. */
private fun parseBuildResultMessage(text: String): ParsedBuildResult? {
    val success = text.startsWith("Applied: BUILD/TEST SUCCESS")
    val failed = text.startsWith("Action failed: BUILD/TEST FAILED")
    if (!success && !failed) return null
    var rest = if (success) text.removePrefix("Applied: BUILD/TEST SUCCESS") else text.removePrefix("Action failed: BUILD/TEST FAILED")
    rest = rest.trimStart()
    var exitInfo = ""
    if (!success) {
        val exitMatch = Regex("^\\(exit \\d+\\)").find(rest)
        if (exitMatch != null) { exitInfo = exitMatch.value; rest = rest.removePrefix(exitMatch.value) }
    }
    rest = rest.trimStart('\n')
    val lines = rest.split('\n')
    val command = lines.firstOrNull().orEmpty()
    val output = lines.drop(1).joinToString("\n").trim()
    return ParsedBuildResult(success, exitInfo, command, output)
}

// Rule 15 sub-helper of the chat bubble list (single job: recognise a message that is ONLY raw
// tool-trace lines — "TOOL x ✓", "TOOL x ✗", "APPROVAL REQUIRED: ...", "LOCAL TOOL ...", "NOTE: ..."
// — so it can get the same colored/iconed treatment as FileDiffCard/BuildResultCard instead of a
// flat text bubble. Doesn't change what text exists (Rule 1 endpoint unchanged, same real trace
// strings from ModelRouter) — only how an ALREADY-trace-only message is displayed (Rule 13
// cleaner-viewer). Any message with a single non-trace line (e.g. a normal assistant reply) still
// falls through to the plain bubble, so nothing that used to render is lost.
private val TOOL_TRACE_PREFIXES = listOf("TOOL ", "LOCAL TOOL ", "APPROVAL REQUIRED:", "NOTE:")
private fun isPureToolTrace(text: String): Boolean {
    val lines = text.split('\n').filter { it.isNotBlank() }
    if (lines.isEmpty()) return false
    return lines.all { line -> TOOL_TRACE_PREFIXES.any { line.trimStart().startsWith(it) } }
}

/** Renders one already-real trace line (see [isPureToolTrace]) with an icon/color that matches
 *  its real outcome — success (✓), failure (✗), or needs-approval — instead of plain white text.
 *  Parses only the fixed prefixes ModelRouter already emits; anything unrecognised still prints
 *  as plain text so no line is ever silently dropped. */
@Composable private fun ToolTraceLine(line: String) {
    val trimmed = line.trim()
    val (icon, tint) = when {
        trimmed.startsWith("APPROVAL REQUIRED:") -> Icons.Default.Warning to Color(0xFFF59E0B)
        trimmed.contains("✓") -> Icons.Default.CheckCircle to Color(0xFF22C55E)
        trimmed.contains("✗") -> Icons.Default.Error to Color(0xFFEF4444)
        trimmed.startsWith("NOTE:") -> Icons.Default.Info to Color(0xFF9BCBFF)
        else -> Icons.Default.Bolt to Color(0xFF9BCBFF)
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(trimmed, fontSize = 10.sp, color = Color(0xFFC7CEE4), fontFamily = FontFamily.Monospace)
    }
}

/** Card wrapper for a trace-only message — same shape/border language as FileDiffCard/
 *  BuildResultCard so the three "structured" card types feel consistent instead of one being a
 *  flat bubble. */
@Composable private fun ToolTraceCard(text: String) {
    Surface(
        Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
        RoundedCornerShape(12.dp),
        color = Color(0xFF14161F),
        border = BorderStroke(1.dp, Color(0xFF2A2E45))
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            text.split('\n').filter { it.isNotBlank() }.forEach { ToolTraceLine(it) }
        }
    }
}

@Composable private fun FileDiffCard(path: String, summaryLine: String, diffLines: List<Pair<Char, String>>, fullContent: String?) {
    var expanded by remember(path, fullContent) { mutableStateOf(false) }
    Surface(
        Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
        RoundedCornerShape(12.dp),
        color = Color(0xFF14161F),
        border = BorderStroke(1.dp, Color(0xFF2A2E45))
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1B1E2C)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, null, Modifier.size(13.dp), tint = Color(0xFF9BCBFF))
                Spacer(Modifier.width(6.dp))
                Text(path.substringAfterLast('/').ifBlank { path }, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE7EAF7), modifier = Modifier.weight(1f), maxLines = 1)
                if (fullContent != null) {
                    Text(
                        if (expanded) "Diff view" else "View full code",
                        fontSize = 9.sp, color = Color(0xFF9C5CFF),
                        modifier = Modifier.clickable { expanded = !expanded }
                    )
                }
            }
            Text(summaryLine, fontSize = 9.sp, color = Color(0xFF8996B5), modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                if (expanded && fullContent != null) {
                    Text(
                        highlightCode(fullContent),
                        fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFFE7EAF7),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                } else {
                    diffLines.forEach { (kind, lineText) ->
                        val bg = when (kind) { '+' -> Color(0xFF15391F); '-' -> Color(0xFF4A1B1F); else -> Color.Transparent }
                        val fg = when (kind) { '+' -> Color(0xFF86EFAC); '-' -> Color(0xFFFCA5A5); else -> Color(0xFF9AA4C4) }
                        Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 10.dp, vertical = 1.dp)) {
                            Text(kind.toString(), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = fg, modifier = Modifier.width(14.dp))
                            Text(lineText, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = fg, maxLines = 3)
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable private fun BuildResultCard(result: ParsedBuildResult, time: String) {
    var showOutput by remember(result.command, result.output) { mutableStateOf(false) }
    val accent = if (result.success) Color(0xFF22C55E) else Color(0xFFEF4444)
    Surface(
        Modifier.padding(vertical = 4.dp).widthIn(max = 300.dp),
        RoundedCornerShape(12.dp),
        color = if (result.success) Color(0xFF102416) else Color(0xFF2A1315),
        border = BorderStroke(1.dp, accent)
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (result.success) Icons.Default.CheckCircle else Icons.Default.Error, null, Modifier.size(15.dp), tint = accent)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (result.success) "Build successful" else "Build failed ${result.exitInfo}".trim(),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent
                )
            }
            if (result.command.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(result.command, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = Color(0xFF9AA4C4))
            }
            if (result.output.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (showOutput) "Hide output" else "View output",
                    fontSize = 9.sp, color = Color(0xFF9C5CFF),
                    modifier = Modifier.clickable { showOutput = !showOutput }
                )
                if (showOutput) {
                    Surface(Modifier.padding(top = 4.dp).fillMaxWidth(), RoundedCornerShape(8.dp), color = Color(0xFF0B0C13)) {
                        Text(
                            result.output,
                            Modifier.heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(6.dp),
                            fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = Color(0xFFC7CEE4)
                        )
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(time, fontSize = 8.sp, color = Color(0xFF6F7D9F))
        }
    }
}

/** Curated quick-action chip for the AI window's toolbar (replaces dumping the raw tool list by
 *  default — Rule 13 cleaner-viewer). Every chip does a real, functional thing: it either fills
 *  in a real prompt prefix that the same send()/tool pipeline below handles like any other typed
 *  message, or opens a real info panel — nothing here is a decorative no-op. */
@Composable private fun QuickActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        Modifier.padding(horizontal = 3.dp).clickable(onClick = onClick),
        RoundedCornerShape(8.dp),
        color = Color(0xFF171A2A)
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(13.dp), tint = Color(0xFF9BCBFF))
            Spacer(Modifier.width(5.dp))
            Text(label, fontSize = 10.sp, color = Color(0xFFDDE3F5))
        }
    }
}

@Composable private fun AIWindow(browser: AndroidBrowserService, settingsStore: AISettingsStore, offlineAi: LocalLlamaEngine){
    val context = LocalContext.current
    val files = remember(context) { AndroidProjectFileService(context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    // Same real-Linux routing as the Terminal window above (Rule 4: one chain, not two divergent
    // ones) — Sara's `run_terminal` tool goes through the identical RoutingShellBackend, so a
    // command Sara runs and a command the user types behave identically.
    val terminal = remember(context) {
        val workspaceRoot = context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")
        EmbeddedTerminalService(workspaceRoot, RoutingShellBackend(workspaceRoot, LinuxBootstrapManager(context)))
    }
    val workspaceManager = remember(context) { ProjectWorkspaceManager(context.filesDir.resolve("SA-AIDesktop/workspaces")) }
    val aiWeb = remember(browser) { AIWebService(browser) }
    val gitRoot = context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")
    val gitService = remember(gitRoot.absolutePath) { CommandGitService(gitRoot) }
    val githubAccounts = remember(context) { GitHubAccountStore(context) }
    val githubApi = remember(githubAccounts) { GitHubApiClient(githubAccounts) }
    val tts = remember(context) { AndroidTextToSpeechEngine(context) }
    val stt = remember(context) { AndroidSpeechToTextEngine(context) }
    val voiceScope = rememberCoroutineScope()
    val voiceState by stt.state.collectAsState()
    val transcript by stt.transcript.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) stt.start() }
    DisposableEffect(tts, stt) { onDispose { tts.release(); stt.release() } }
    val tools = remember(files, terminal, browser, workspaceManager, gitRoot, githubAccounts, githubApi) {
        ToolRegistry(
            listOf(
                // Core workspace tools
                ReadFileTool(files), SearchFileTool(files), WriteFileTool(files), ListFilesTool(files),
                TerminalRunTool(terminal), WindowControlTool(manager),

                // Real device/read-only helpers
                CalculatorTool(), DeviceTimeTool(), DeviceDateTool(), DeviceBatteryTool(context),

                // Real browser tools
                com.sa.aidesktop.core.browser.BrowserOpenTool(browser, manager),
                com.sa.aidesktop.core.browser.BrowserNavigationTool("back", browser, manager),
                com.sa.aidesktop.core.browser.BrowserNavigationTool("forward", browser, manager),
                com.sa.aidesktop.core.browser.BrowserNavigationTool("reload", browser, manager),
                com.sa.aidesktop.core.browser.BrowserNavigationTool("stop", browser, manager),
                com.sa.aidesktop.core.browser.BrowserInspectTool(browser, manager),
                com.sa.aidesktop.core.browser.BrowserSearchTool(browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("click", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("type", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("clear", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("select", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("check", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("scroll", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("focus", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("download", browser, manager),
                com.sa.aidesktop.core.browser.BrowserElementTool("upload", browser, manager),

                // Real AI-website interaction through the current WebView
                AIWebDetectTool(aiWeb), AIWebInspectTool(aiWeb), AIWebTypeTool(aiWeb), AIWebSendTool(aiWeb),
                AIWebWaitTool(aiWeb), AIWebReadTool(aiWeb), AIWebUploadTool(aiWeb),

                // Project/coding tools
                ProjectInspectTreeTool(files),
                ProjectDiscoverTool(workspaceManager, context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")),
                ZipWorkspaceTool(workspaceManager), BuildProjectTool(terminal),

                // Real Git/GitHub tools
                GitStatusTool(gitService), GitDiffTool(gitService), GitLogTool(gitService), GitRemoteTool(gitService),
                GitInitTool(gitService), GitAddTool(gitService), GitCommitTool(gitService), GitFetchTool(gitService),
                GitPushTool(gitService), GitPullTool(gitService), GitCloneTool(gitService), GitBranchTool(gitService),
                GitCheckoutTool(gitService), GitMergeTool(gitService),
                GitHubAccountStatusTool(githubAccounts), GitHubListRepositoriesTool(githubApi),
                GitHubCreateRepositoryTool(githubApi)
            )
        )
    }
    val gate = remember { PermissionGate() }
    val gateway = remember(tools) { ToolExecutionGateway(tools, gate) }
    val taskStore = remember(context) {
        CodingTaskStore(context.filesDir.resolve("SA-AIDesktop/tasks/active.properties"))
    }

    // Groq is opt-in (Settings > Online Mode) and, when on, uses the same registry/gateway as the
    // offline path — there is no second hidden tool system. Offline is the real default tier.
    val groqClient = remember { GroqClient(apiKeyProvider = { settingsStore.getApiKey() }) }
    val router = remember(tools, offlineAi) {
        ModelRouter(
            groqClient,
            offlineAi,
            hasApiKey = { settingsStore.hasApiKey() },
            settingsProvider = { settingsStore.toGroqSettings() },
            toolRegistry = tools,
            // Offline-first, real toggle: false by default (see AISettingsStore), so every
            // request runs on the on-device model until the user turns this on in Settings.
            onlineModeEnabled = { settingsStore.isOnlineModeEnabled() }
        )
    }
    val service: AIService = router
    // Hoisted here (was previously collected a second time inside the header Row only) so the
    // same live activity label can also drive the in-chat status bubble below — one collection,
    // two places that read it (Rule 21: no duplicate/extra state collection).
    val activity by router.activityStatus.collectAsState()
    // Real, timestamped workflow steps for this turn (Smart Workflow Indicator / Thinking
    // Details panel) — see ModelRouter.workflowSteps. Additive: `activity`/the typing bubble
    // above are unchanged, this only adds the tappable detail view.
    val workflowSteps by router.workflowSteps.collectAsState()
    var showThinkingDetails by remember { mutableStateOf(false) }
    // Ticks once a second only while a step is genuinely still open, so in-progress step timers
    // in ThinkingDetailsPanel visibly run instead of a frozen number.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(workflowSteps) {
        while (workflowSteps.isNotEmpty() && workflowSteps.last().endedAtMs == null) {
            nowMs = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000L)
        }
        nowMs = System.currentTimeMillis()
    }
    val taskEngine = remember(router, gateway, taskStore, tools) {
        TaskEngine(router, gateway, taskStore).also { engine ->
            // Task controls are registered after the engine exists, but the router/gateway hold the
            // same mutable registry, so they immediately see these real tools.
            tools.register(TaskStatusTool(engine))
            tools.register(TaskCancelTool(engine))
            tools.register(TaskPauseTool(engine))
        }
    }

    LaunchedEffect(taskEngine) {
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val task = taskEngine.current()
            val protectedTypes = task?.let { TaskWindowProtection.protectedWindowTypes(it) }.orEmpty()
            val ids = manager.windows.filter { it.type in protectedTypes }.map { it.id }.toSet()
            if (task != null && ids.isNotEmpty()) {
                manager.setTaskProtection(ids, task.taskId, TaskWindowProtection.reason(task))
            } else {
                task?.let { manager.clearTaskProtection(it.taskId) }
            }
            kotlinx.coroutines.delay(250L)
        }
    }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<ToolRequest?>(null) }
    var pendingQueue by remember { mutableStateOf<List<ToolRequest>>(emptyList()) }
    var appliedBatchResults by remember { mutableStateOf<List<String>>(emptyList()) }
    var pendingTaskId by remember { mutableStateOf<String?>(null) }
    var pendingChatPrompt by remember { mutableStateOf<String?>(null) }
    var tier by remember { mutableStateOf<RouterTier?>(null) }
    var lastToolTrace by remember { mutableStateOf<List<String>>(emptyList()) }
    // Real, non-fabricated Groq usage for the most recent turn — see AIResponse.tokenUsage.
    // Stays null until a live Groq response actually reports a "usage" object; the status row
    // below the input shows "—" in that case rather than inventing a number.
    var lastUsage by remember { mutableStateOf<GroqUsage?>(null) }

    LaunchedEffect(transcript) { if (transcript.isNotBlank()) input = transcript }

    val profile = remember {
        AIProfile("sara", "Sara", "calm developer assistant", "default", "Hinglish", "sara")
    }
    val msgs = remember {
        mutableStateListOf(
            AIMessage(
                "Hello! I'm ${profile.name}. ${if (settingsStore.isOnlineModeEnabled()) (if (settingsStore.hasApiKey()) "Online Mode is on and Groq is configured." else "Online Mode is on but no Groq API key is set yet — add it in Settings.") else "Running fully offline on the on-device model. Turn on Online Mode in Settings if you want Groq instead."}",
                false,
                "Now"
            ),
            AIMessage(
                "Main sirf real tool results claim karungi. File/Git/browser/terminal changes approval ke bina apply nahi honge.",
                false,
                "Now"
            )
        )
    }

    fun conversationHistory(current: String): List<AIConversationMessage> {
        return msgs.takeLast(10).map {
            AIConversationMessage(if (it.fromUser) "user" else "assistant", it.text)
        }.filter { it.text.isNotBlank() } + AIConversationMessage("user", current)
    }

    fun isAgentTaskRequest(prompt: String): Boolean {
        val explicit = Regex(
            "(?i)\\b(" +
                "fix\\s+(this|the)?\\s*(project|bug|error)|" +
                "build\\s+(this|the)?\\s*(project|app)|" +
                "run\\s+(the\\s+)?(build|tests)|" +
                "modify\\s+file|change\\s+file|edit\\s+file|" +
                "commit\\s+(these|the|my)?\\s*changes|" +
                "push\\s+(these|the|my)?\\s*(changes|code)|" +
                "pull\\s+(the|latest)?\\s*changes|" +
                "clone\\s+(this|the)?\\s*(repo|repository)|" +
                "create\\s+(a\\s+)?github\\s+repo|" +
                "extract\\s+(this\\s+)?zip|" +
                "autonomous\\s+task|agent\\s+task" +
            ")\\b"
        ).containsMatchIn(prompt)
        if (explicit) return true

        // A request naming something to build (an app/tool/project/game/website/calculator/etc.)
        // together with a build verb — Hindi ("banao"/"bana do") or English ("make"/"create"/
        // "build"/"generate"/"develop") — is real coding work and belongs in the real
        // terminal-backed task engine, not a single ambiguous tool guess in plain chat.
        val buildVerb = Regex("(?i)\\b(banao|bana\\s*do|bnao|bnado|bna\\s*do|make|create|build|generate|develop)\\b")
        val buildNoun = Regex("(?i)\\b(app|application|project|website|web\\s*site|tool|program|game|script|calculator|website)\\b")
        return buildVerb.containsMatchIn(prompt) && buildNoun.containsMatchIn(prompt)
    }

    fun clearChat() {
        // BUG FIX (Rule 3/17 — "clear" must fully reset related state, not just the UI list):
        // clearing chat previously reset only local message/approval state. If an autonomous
        // agent task was mid-flight (e.g. WAITING_FOR_APPROVAL), TaskEngine's persisted record
        // survived untouched, so the *next* "banao/fix karo" request would silently resume that
        // stale old task via reconcileAndResume instead of starting the fresh one the user just
        // typed — the chat looked cleared but old task context wasn't. Cancel any active/resumable
        // task as part of clearing, so "clear chat" genuinely means a fresh start.
        if (taskEngine.current() != null) taskEngine.cancel()
        msgs.clear()
        msgs.add(
            AIMessage(
                "Hello! I'm ${profile.name}. ${if (settingsStore.isOnlineModeEnabled()) (if (settingsStore.hasApiKey()) "Online Mode is on and Groq is configured." else "Online Mode is on but no Groq API key is set yet — add it in Settings.") else "Running fully offline on the on-device model. Turn on Online Mode in Settings if you want Groq instead."}",
                false,
                "Now"
            )
        )
        msgs.add(
            AIMessage(
                "Main sirf real tool results claim karungi. File/Git/browser/terminal changes approval ke bina apply nahi honge.",
                false,
                "Now"
            )
        )
        pending = null
        pendingQueue = emptyList()
        appliedBatchResults = emptyList()
        pendingTaskId = null
        pendingChatPrompt = null
        lastToolTrace = emptyList()
        lastUsage = null
    }

    fun send() {
        val p = input.trim()
        if (p.isBlank() || busy) return

        val history = conversationHistory(p)
        msgs.add(AIMessage(p, true, "Now"))
        input = ""
        busy = true
        pending = null
        pendingQueue = emptyList()
        appliedBatchResults = emptyList()
        pendingTaskId = null
        pendingChatPrompt = null
        lastToolTrace = emptyList()

        scope.launch {
            if (isAgentTaskRequest(p)) {
                val run = taskEngine.start(
                    p,
                    ProjectContext(projectStructure = "MyProject workspace")
                )
                msgs.add(
                    AIMessage(
                        formatAgentTaskStatus(run.record),
                        false,
                        "Now"
                    )
                )
                pending = run.pendingTool
                pendingTaskId = run.pendingTool?.let { run.record.taskId }
            } else {
                when (
                    val result = service.chat(
                        AIRequest(
                            prompt = p,
                            context = ProjectContext(projectStructure = "MyProject workspace"),
                            history = history.dropLast(1)
                        )
                    )
                ) {
                    is AIResult.Success -> {
                        lastToolTrace = result.value.toolTrace
                        lastUsage = result.value.tokenUsage
                        if (result.value.toolTrace.isNotEmpty()) {
                            msgs.add(
                                AIMessage(
                                    result.value.toolTrace.joinToString("\n"),
                                    false,
                                    "Now"
                                )
                            )
                        }
                        if (result.value.text.isNotBlank()) {
                            msgs.add(AIMessage(result.value.text, false, "Now"))
                        }
                        pending = result.value.toolRequests.firstOrNull { gate.requiresApproval(it.risk) }
                        pendingQueue = result.value.toolRequests
                            .filter { gate.requiresApproval(it.risk) }
                            .drop(1)
                        pendingTaskId = null
                        pendingChatPrompt = if (pending != null) p else null
                    }
                    is AIResult.Failure -> {
                        lastToolTrace = emptyList()
                        msgs.add(AIMessage("AI error: ${result.error.toDisplayMessage()}", false, "Now"))
                    }
                }
            }
            tier = router.currentStatus().lastTier
            busy = false
        }
    }

    // Rule 12 (design-first) / Rule 21 (existing-behavior preserving): the window can be dragged
    // to any width down to its real minSizeFor(AI)=300.dp (DesktopWindowManager), but until now
    // the header always tried to draw all three text lines (name, provider status, tools/agent)
    // at full size regardless of how little width was actually available — on a real narrow/"mini"
    // window that means wrapped or clipped text instead of the clean compact header the design
    // calls for ("Mini Window: compact view with icons & key info only"). BoxWithConstraints reads
    // the REAL available width at composition time (no guessed/fixed breakpoint tied to a device),
    // so the header adapts live as the same window is dragged wider/narrower. Nothing here removes
    // a line of real data — the secondary status/tools text simply doesn't render below the
    // threshold where it would no longer fit cleanly; every value is still the same live state.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 340.dp
        Column(Modifier.fillMaxSize().background(Color(0xFF080911))){
        // Header status: real connection state only (Rule 10 — never fake "Online"). Declared at
        // this scope (not inside the header Row below) because the "Groq AI" quick-action info
        // panel further down reuses the exact same value — one real status, shown in two places.
        // BUG FIX: this used to show "Groq (online)" for RouterTier.OFFLINE_LOCAL too, which was
        // simply wrong — that tier means the on-device model just answered, not Groq. The status
        // is now derived honestly per real tier, and the "no request yet" (null) case reflects
        // the real active mode (offline-default vs. the user's own Online Mode toggle) instead of
        // always assuming Groq.
        val onlineOn = settingsStore.isOnlineModeEnabled()
        val statusText = when(tier){
            RouterTier.ONLINE_GROQ -> "Groq (online)"
            RouterTier.OFFLINE_LOCAL -> "Offline (on-device)"
            RouterTier.OFFLINE_LOCAL_UNAVAILABLE -> if (onlineOn) {
                settingsStore.getApiKey().let { if (it.isNullOrBlank()) "Groq API key missing" else "Groq error — see chat" }
            } else {
                if (settingsStore.getLocalModelPath().isNullOrBlank()) "Offline model not configured" else "Offline model error — see chat"
            }
            null -> if (onlineOn) {
                if (settingsStore.hasApiKey()) "Groq configured" else "Groq API key missing"
            } else {
                if (settingsStore.getLocalModelPath().isNullOrBlank()) "Offline mode — model not configured" else "Offline mode ready"
            }
        }
        val statusColor = when {
            statusText.contains("online") || statusText == "Offline (on-device)" || statusText == "Offline mode ready" -> Color(0xFF22C55E)
            statusText.contains("configured") -> Color(0xFFF59E0B)
            else -> Color(0xFFEF4444)
        }
        Row(Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF10121D)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(Modifier.size(34.dp),RoundedCornerShape(10.dp),color=Color(0xFF4D1A78)){androidx.compose.foundation.Image(painterResource(R.drawable.sara_avatar),contentDescription="Sara",modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)}
            // BUG FIX (latest screenshot: Sara's name still hard to see): this Text() had no
            // explicit color, so it inherited theme content color instead of a color chosen for
            // this dark header. Explicit light color added; the status line beneath it already
            // had an explicit (readable) color and is unchanged.
            Column(Modifier.padding(start=9.dp).weight(1f)){
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(statusColor, CircleShape))
                    Spacer(Modifier.width(5.dp))
                    Text(profile.name,fontSize=13.sp,fontWeight=FontWeight.Bold,color=Color(0xFFF2F0FF),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
                if (!compact) {
                    Text("$statusText • ${profile.language}",fontSize=9.sp,color=Color(0xFF8996B5),maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text("Tools: ${tools.all().size} • Agent: ${taskEngine.current()?.state ?: "IDLE"}",fontSize=7.sp,color=Color(0xFF6F7D9F),maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
            // BUG FIX (video: status text + delete icon looked cramped/cut off against the
            // window's right edge with almost no breathing room). A fixed Spacer now guarantees
            // real separation between the live status text and the delete button regardless of
            // status text length, and the row's own trailing padding (see Row Modifier below)
            // keeps the icon off the window edge instead of sitting flush against it. No data or
            // behavior changed - same busy/activity/Ready text, same clearChat() action.
            if (!compact) Text(if(busy) activity else "Ready",fontSize=9.sp,color=if(busy) Color(0xFFF59E0B) else Color(0xFF22C55E),maxLines=1,overflow=TextOverflow.Ellipsis)
            else Box(Modifier.size(8.dp).background(if(busy) Color(0xFFF59E0B) else Color(0xFF22C55E), CircleShape))
            Spacer(Modifier.width(10.dp))
            IconButton(onClick = { clearChat() }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Clear chat", tint = Color(0xFF8996B5), modifier = Modifier.size(16.dp))
            }
        }
        // Curated quick-action toolbar (Rule 13 cleaner-viewer): the full raw tool list (every
        // registered tool, unchanged, nothing deleted) now lives behind "More" instead of being
        // dumped by default. Each curated chip does a real thing — the text ones fill a real
        // prompt prefix into the same input the Send button already uses; "Groq AI" opens a real
        // provider/tool-count info panel using the same tier/tools state already collected above.
        var showAllTools by remember { mutableStateOf(false) }
        var showProviderInfo by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(40.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            QuickActionChip(Icons.Default.Bolt, "Groq AI") { showProviderInfo = true }
            QuickActionChip(Icons.Default.Public, "Web Search") { input = (if (input.isBlank()) "" else input + "\n") + "Search the web for: " }
            QuickActionChip(Icons.Default.FolderOpen, "File Search") { input = (if (input.isBlank()) "" else input + "\n") + "Search files for: " }
            QuickActionChip(Icons.Default.Terminal, "Run Command") { input = (if (input.isBlank()) "" else input + "\n") + "Run: " }
            QuickActionChip(Icons.Default.Code, "Code") { input = (if (input.isBlank()) "" else input + "\n") + "Write code: " }
            QuickActionChip(Icons.Default.MoreHoriz, if (showAllTools) "Less" else "More") { showAllTools = !showAllTools }
            // BUG FIX (video: last chip looked cut off flush against the window's right edge,
            // with no hint the row could still scroll). A real trailing spacer inside the same
            // scrollable Row gives the last chip breathing room at the end of the scroll range -
            // purely a spacing fix, the row was already horizontalScroll(); no chip added/removed.
            Spacer(Modifier.width(8.dp))
        }
        if (showProviderInfo) {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), RoundedCornerShape(10.dp), color = Color(0xFF14161F), border = BorderStroke(1.dp, Color(0xFF2A2E45))) {
                Column(Modifier.padding(10.dp)) {
                    Text("Provider: $statusText", fontSize = 10.sp, color = Color(0xFFE7EAF7))
                    Text("Model: ${settingsStore.getModel()}", fontSize = 9.sp, color = Color(0xFF9AA4C4))
                    Text("Tools registered: ${tools.all().size}", fontSize = 9.sp, color = Color(0xFF9AA4C4))
                    Text("Agent state: ${taskEngine.current()?.state ?: "IDLE"}", fontSize = 9.sp, color = Color(0xFF9AA4C4))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showProviderInfo = false }) { Text("Close", fontSize = 9.sp) }
                    }
                }
            }
        }
        if (showAllTools) {
            Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
                tools.all().forEach { tool ->
                    Surface(
                        Modifier.padding(horizontal=3.dp),
                        RoundedCornerShape(8.dp),
                        color=Color(0xFF111522)
                    ){
                        Text(
                            "${tool.id.replace('_',' ')} ${if(tool.risk == ToolRisk.READ_ONLY) "•R" else "•A"}",
                            Modifier.padding(horizontal=8.dp,vertical=5.dp),
                            fontSize=8.sp,
                            color=Color(0xFF9EACCC)
                        )
                    }
                }
            }
        }
        val chatScroll = rememberScrollState()
        // BUG FIX (Rule 1/17 endpoint-correctness: chat looked "cut off"/reply appeared to
        // vanish after a resize or a new turn): the message list scrolled, but nothing ever
        // drove it to the newest message, so a fresh reply — or the same scroll offset surviving
        // a window resize — could render below the visible viewport. The user had to notice and
        // manually drag to see it, which read as the chat being cut/broken. Auto-scroll to the
        // latest content whenever the message count or the busy/typing-bubble state changes,
        // exactly like a normal chat app; this only moves the scroll position, it never touches
        // AI/tool logic above.
        LaunchedEffect(msgs.size, busy) {
            if (chatScroll.maxValue > 0) chatScroll.animateScrollTo(chatScroll.maxValue)
        }
        Column(Modifier.weight(1f).verticalScroll(chatScroll).padding(10.dp)){
            // BUG FIX (screenshot: chat bubble text almost black/unreadable on dark background):
            // Text() previously had no explicit color, so it inherited LocalContentColor from the
            // surrounding theme/Surface instead of a color chosen for these specific bubble
            // backgrounds. Explicit light colors are set per bubble (user vs assistant) so both
            // remain readable, including multiline responses, without touching the AI logic above.
            // Rule 13 cleaner-viewer: a real write_file result or a real build/test result renders
            // as its own card (diff / success-failure) instead of a raw text dump; anything else
            // (including every plain assistant/user message) keeps the original bubble unchanged.
            msgs.forEach{m->
                val buildResult = if (!m.fromUser) parseBuildResultMessage(m.text) else null
                val fileDiff = if (!m.fromUser && buildResult == null) parseFileDiffMessage(m.text) else null
                val traceOnly = if (!m.fromUser && buildResult == null && fileDiff == null) isPureToolTrace(m.text) else false
                // Design match: every Sara message carries her small round avatar to its left
                // (see the mockup's chat feed) — user messages stay avatar-less on the right,
                // unchanged. Purely additive: same bubble/card content as before, just prefixed
                // with the same avatar image already used in the header (Rule 4: one avatar
                // asset, not a second copy).
                Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.fromUser)Arrangement.End else Arrangement.Start, verticalAlignment=Alignment.Top){
                    if (!m.fromUser) {
                        androidx.compose.foundation.Image(
                            painterResource(R.drawable.sara_avatar), contentDescription = null,
                            modifier = Modifier.padding(top = 4.dp).size(22.dp).clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    when {
                        buildResult != null -> BuildResultCard(buildResult, m.time)
                        fileDiff != null -> FileDiffCard(fileDiff.path, fileDiff.summaryLine, fileDiff.diffLines, fileDiff.fullContent)
                        traceOnly -> ToolTraceCard(m.text)
                        else -> Surface(Modifier.padding(vertical=4.dp).widthIn(max=300.dp),RoundedCornerShape(12.dp),color=if(m.fromUser)Color(0xFF4D1A78)else Color(0xFF171820)){Text(renderChatMarkdown(m.text),Modifier.padding(10.dp),fontSize=12.sp,color=if(m.fromUser)Color(0xFFF5EEFF)else Color(0xFFE7EAF7))}
                    }
                }
            }
            // Rule 13: a visible per-type "typing" bubble (not just the small header word) while a
            // turn is in flight — real activityStatus label (Thinking…/Coding…/Browsing…/Git…/
            // Running…) set from the actual tool ids about to run (ModelRouter.activityLabel),
            // never a guessed/generic word.
            if(busy) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Start, verticalAlignment=Alignment.Top){
                androidx.compose.foundation.Image(
                    painterResource(R.drawable.sara_avatar), contentDescription = null,
                    modifier = Modifier.padding(top = 4.dp).size(22.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
                Spacer(Modifier.width(6.dp))
                Surface(
                    Modifier.padding(vertical=4.dp).clickable { showThinkingDetails = !showThinkingDetails },
                    RoundedCornerShape(12.dp),color=Color(0xFF171820)
                ){
                    Row(Modifier.padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){
                        // Same real, currently-open workflow step drives this bubble's animation
                        // as the Thinking Details panel below (Rule 4: one chain, not two
                        // divergent visuals) — falls back to a plain pulse only before the
                        // router has pushed its first real step yet.
                        val openKind = workflowSteps.lastOrNull { it.endedAtMs == null }?.kind
                        StepAnimationIndicator(openKind ?: WorkflowStepKind.PLANNING, Color(0xFFF59E0B), Modifier.size(13.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(activity,fontSize=11.sp,color=Color(0xFFF59E0B))
                        if (workflowSteps.isNotEmpty()) {
                            Spacer(Modifier.width(7.dp))
                            Text("• Tap to view details",fontSize=9.sp,color=Color(0xFF7C86A6))
                        }
                    }
                }
            }
        }
        if (showThinkingDetails) {
            ThinkingDetailsPanel(workflowSteps, nowMs) { showThinkingDetails = false }
        }
        pending?.let { request ->
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                RoundedCornerShape(12.dp),
                color = Color(0xFF171322),
                border = BorderStroke(1.dp, Color(0xFF70458D))
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        if (pendingQueue.isEmpty()) "Approval required"
                        else "Approval required (1 of ${pendingQueue.size + 1})",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE4C7FF)
                    )
                    Text(
                        "Tool: ${request.toolId} • Risk: ${request.risk}",
                        fontSize = 9.sp,
                        color = Color(0xFFAFA7BE)
                    )
                    // Rule 13 cleaner-viewer / Rule 15 sub-helper: for a real write_file request,
                    // show the same red/green diff preview the applied result gets later, computed
                    // from the real on-disk "before" content via the same ChatDiffUtil the tool
                    // itself uses (Rule 4: one diff chain, not a second fake one) — instead of
                    // dumping the raw new-content string. Any other tool (or a write_file whose
                    // diff can't be computed, e.g. a brand-new file with a huge body) still falls
                    // back to the original plain "Input: ..." line, so nothing is hidden.
                    val previewDiff = remember(request) {
                        if (request.toolId != "write_file") return@remember null
                        val path = request.input["path"]?.trim().orEmpty()
                        val newContent = request.input["content"] ?: return@remember null
                        if (path.isBlank()) return@remember null
                        val oldContent = files.read(path).let { if (it.isSuccess) it.value.orEmpty() else "" }
                        val diff = ChatDiffUtil.lineDiff(oldContent.lines(), newContent.lines()) ?: return@remember null
                        path to ChatDiffUtil.collapseContext(diff).filter { it.length >= 2 }.map { it[0] to it.substring(2) }
                    }
                    if (previewDiff != null) {
                        Text(previewDiff.first, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = Color(0xFF9BCBFF), modifier = Modifier.padding(top = 4.dp))
                        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            previewDiff.second.forEach { (kind, lineText) ->
                                val bg = when (kind) { '+' -> Color(0xFF15391F); '-' -> Color(0xFF4A1B1F); else -> Color.Transparent }
                                val fg = when (kind) { '+' -> Color(0xFF86EFAC); '-' -> Color(0xFFFCA5A5); else -> Color(0xFF9AA4C4) }
                                Row(Modifier.fillMaxWidth().background(bg)) {
                                    Text(kind.toString(), fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = fg, modifier = Modifier.width(12.dp))
                                    Text(lineText, fontFamily = FontFamily.Monospace, fontSize = 9.sp, color = fg, maxLines = 3)
                                }
                            }
                        }
                    } else if (request.input.isNotEmpty()) {
                        Text(
                            "Input: ${request.input.entries.joinToString { "${it.key}=${it.value.take(160)}" }}",
                            fontSize = 8.sp,
                            color = Color(0xFF8E8AA0)
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = {
                                // Cancelling stops the whole queued batch, not just this one step —
                                // a partial batch silently continuing without the user's say would
                                // be more confusing than starting over.
                                pending = null
                                pendingQueue = emptyList()
                                appliedBatchResults = emptyList()
                                pendingTaskId = null
                                pendingChatPrompt = null
                            }
                        ) { Text("Cancel", fontSize = 9.sp) }

                        TextButton(
                            onClick = {
                                val requestToApply = request
                                val taskId = pendingTaskId
                                val originalChatPrompt = pendingChatPrompt
                                val queueSnapshot = pendingQueue
                                pending = null
                                pendingQueue = emptyList()
                                busy = true
                                scope.launch {
                                    when (
                                        val applied = gateway.execute(
                                            requestToApply,
                                            approved = true
                                        )
                                    ) {
                                        is AIResult.Success -> {
                                            msgs.add(
                                                AIMessage(
                                                    "Applied: ${applied.value.output}",
                                                    false,
                                                    "Now"
                                                )
                                            )
                                            if (taskId != null) {
                                                val continued = taskEngine.continueAfterApprovedTool(
                                                    taskId,
                                                    requestToApply,
                                                    applied,
                                                    ProjectContext(projectStructure = "MyProject workspace")
                                                )
                                                msgs.add(
                                                    AIMessage(
                                                        formatAgentTaskStatus(continued.record),
                                                        false,
                                                        "Now"
                                                    )
                                                )
                                                pending = continued.pendingTool
                                                pendingTaskId = continued.pendingTool?.let { continued.record.taskId }
                                                pendingChatPrompt = null
                                            } else if (originalChatPrompt != null) {
                                                val batchResults = appliedBatchResults +
                                                    "${requestToApply.toolId} REAL RESULT:\n${applied.value.output.take(12000)}"
                                                if (queueSnapshot.isNotEmpty()) {
                                                    // More approvals from the same AI turn are still
                                                    // queued — hold off asking the model to continue
                                                    // until every step in this batch has actually run,
                                                    // so it sees every real result together instead of
                                                    // reacting to one at a time.
                                                    appliedBatchResults = batchResults
                                                    pending = queueSnapshot.first()
                                                    pendingQueue = queueSnapshot.drop(1)
                                                    pendingChatPrompt = originalChatPrompt
                                                } else {
                                                    appliedBatchResults = emptyList()
                                                    // Normal chat approval needs to return to the model
                                                    // with the real tool result(s). Previously the UI stopped
                                                    // at "Applied", leaving the conversation half-wired.
                                                    when (
                                                        val followUp = service.chat(
                                                            AIRequest(
                                                                prompt = originalChatPrompt,
                                                                context = ProjectContext(projectStructure = "MyProject workspace"),
                                                                history = msgs.takeLast(8).map {
                                                                    AIConversationMessage(
                                                                        if (it.fromUser) "user" else "assistant",
                                                                        it.text
                                                                    )
                                                                } + listOf(
                                                                    AIConversationMessage(
                                                                        "tool",
                                                                        batchResults.joinToString("\n\n")
                                                                    )
                                                                )
                                                            )
                                                        )
                                                    ) {
                                                        is AIResult.Success -> {
                                                            lastToolTrace = followUp.value.toolTrace
                                                            lastUsage = followUp.value.tokenUsage
                                                            if (followUp.value.toolTrace.isNotEmpty()) {
                                                                msgs.add(
                                                                    AIMessage(
                                                                        followUp.value.toolTrace.joinToString("\n"),
                                                                        false,
                                                                        "Now"
                                                                    )
                                                                )
                                                            }
                                                            if (followUp.value.text.isNotBlank()) {
                                                                msgs.add(
                                                                    AIMessage(
                                                                        followUp.value.text,
                                                                        false,
                                                                        "Now"
                                                                    )
                                                                )
                                                            }
                                                            val nextWrites = followUp.value.toolRequests.filter {
                                                                gate.requiresApproval(it.risk)
                                                            }
                                                            pending = nextWrites.firstOrNull()
                                                            pendingQueue = nextWrites.drop(1)
                                                            pendingChatPrompt = if (pending != null) originalChatPrompt else null
                                                            pendingTaskId = null
                                                        }
                                                        is AIResult.Failure -> {
                                                            msgs.add(
                                                                AIMessage(
                                                                    "AI follow-up error: ${followUp.error.toDisplayMessage()}",
                                                                    false,
                                                                    "Now"
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        is AIResult.Failure -> {
                                            msgs.add(
                                                AIMessage(
                                                    "Action failed: ${applied.error.toDisplayMessage()}",
                                                    false,
                                                    "Now"
                                                )
                                            )
                                            // A failure stops the rest of the queued batch rather than
                                            // silently skipping ahead to the next queued action.
                                            pendingQueue = emptyList()
                                            appliedBatchResults = emptyList()
                                            if (taskId != null) {
                                                pending = null
                                                pendingTaskId = null
                                            }
                                        }
                                    }
                                    if (taskId == null && pending == null) {
                                        pendingChatPrompt = null
                                    }
                                    tier = router.currentStatus().lastTier
                                    busy = false
                                }
                            }
                        ) { Text("Apply", fontSize = 9.sp) }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            // BUG FIX (screenshot: AI prompt text/placeholder unreadable on dark background):
            // this TextField relied on inherited text/placeholder/cursor colors. Explicit colors
            // are now supplied for both focused and unfocused state, plus the typed text style,
            // cursor, and text-selection highlight, so entry and placeholder are always visible.
            // Single-line behavior, voice/mic/send buttons, and busy/send logic are unchanged.
            // Chat-window fix: this was singleLine=true, so any message longer than the box's
            // width was invisible/cramped (couldn't see what you typed, Claude/ChatGPT-style
            // inputs grow instead). minLines/maxLines lets it grow up to 6 lines as you type and
            // shrink back down; Enter still inserts a newline (send stays on the explicit Send
            // button below, unchanged) so nothing about submit behavior changes.
            // Terminal-style prompt marker (matches TerminalWindow's "$"), so the chat input reads
            // like a shell prompt even though it still sends a normal chat message underneath.
            Text("$",fontFamily=FontFamily.Monospace,color=Color(0xFF7AFF9B),modifier=Modifier.padding(end=4.dp))
            // BUG FIX (video: input box was several lines tall even when empty): the stock
            // Material3 TextField() carries its own ~56dp min-height chrome plus internal label/
            // supporting-text padding, and its long placeholder wrapped across 2-3 lines inside
            // the narrower mobile AI window width - since minLines/maxLines let the field grow to
            // fit its own placeholder, the box rendered as a tall multi-line slab before the user
            // typed a single character (the reference design's input is a slim single-line pill).
            // Swapping to BasicTextField with a manual compact decoration box removes that
            // built-in chrome; the shorter placeholder ("Ask Sara anything...", matching the
            // reference design's wording) fits on one line at this font size. Typed input still
            // grows up to 6 lines exactly as before - every value/color/cursor/selection/enabled
            // behavior below is preserved unchanged, only the layout chrome around it shrank.
            Box(
                Modifier.weight(1f)
                    .background(Color(0xFF050609), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (input.isEmpty()) {
                    Text(
                        "Ask Sara anything...",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = Color(0xFF8993B8), maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color(0xFFE0E6FF), fontSize = 12.sp),
                    cursorBrush = SolidColor(Color(0xFF7AFF9B)),
                    enabled = !busy,
                    minLines = 1, maxLines = 6
                )
            }
            IconButton({ voiceScope.launch { msgs.lastOrNull { !it.fromUser }?.let { tts.speak(it.text) } } }){Icon(Icons.Default.VolumeUp,"Speak",tint=Color(0xFF00BFFF))}
            IconButton({ if (voiceState.listening) stt.stop() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }){Icon(if(voiceState.listening) Icons.Default.Stop else Icons.Default.Mic,"Voice input",tint=if(voiceState.listening) Color(0xFFEF4444) else Color(0xFF00BFFF))}
            IconButton({send()},enabled=!busy){Icon(Icons.Default.Send,null,tint=Color(0xFF8B5CF6))}
        }
        // Real model/token status row. Model name always comes from the actual configured
        // setting (settingsStore.getModel()); token counts come only from lastUsage, which is
        // set exclusively from a real Groq "usage" object (see AIResponse.tokenUsage) — this
        // never estimates or invents a number, unlike a fixed "Context: 12K" label would.
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, top = 0.dp, end = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val usage = lastUsage
            val tokensLabel = if (usage == null) {
                "Tokens: —"
            } else {
                val prompt = usage.promptTokens?.toString() ?: "?"
                val completion = usage.completionTokens?.toString() ?: "?"
                val total = usage.totalTokens?.toString() ?: "?"
                "Tokens: $total (prompt $prompt / reply $completion)"
            }
            Text(
                if (compact) tokensLabel else "Model: ${settingsStore.getModel()} • $tokensLabel",
                fontSize = 9.sp,
                color = Color(0xFF6F7D9F),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        }
    }
}

/** Termux-style `nano <file>` state. `nano` is a full-screen interactive editor, not a one-shot
 *  TerminalResult, so it is intercepted here at the UI layer before terminal.execute() — exactly
 *  like a real terminal emulator special-cases a curses app — and reuses the existing sandboxed
 *  FileService read/write (Rule 8: same real file-access path as the rest of the app, nothing
 *  duplicated). filePath is workspace-relative (what FileService expects); displayPath is what
 *  the user actually typed, shown back to them like real nano's title bar. */
private data class NanoSession(
    val displayPath: String,
    val filePath: String,
    val original: String,
    val content: String,
    val confirmExit: Boolean = false
)

@Composable private fun TerminalWindow(terminal: TerminalService, liveOutput: kotlinx.coroutines.flow.StateFlow<String>, files: FileService){
    var input by remember{mutableStateOf("")}
    val initial=terminal.state()
    // Termux-style prompt: real terminals show the actual current directory, not a fixed string.
    // rootPath is the workspace root captured once (before any `cd`), so every later prompt can
    // be rendered relative to it the same way Termux shows "~" for home.
    val rootPath = remember { initial.workingDirectory }
    fun displayCwd(absolute: String): String {
        val suffix = absolute.removePrefix(rootPath)
        return if (suffix.isBlank()) "~" else "~" + suffix.replace(java.io.File.separatorChar, '/')
    }
    var cwdDisplay by remember { mutableStateOf(displayCwd(initial.workingDirectory)) }
    val out=remember{mutableStateListOf("SA Embedded Terminal — Workspace: ${initial.workingDirectory}","Type help for supported commands.")}
    // BUG FIX (Rule 14 weakness-check on RealLinuxShellBackend just added): this used to call
    // terminal.execute() directly on the composable's own (UI) thread. That was already
    // questionable for the old restricted sandbox, but `bootstrap install` (real network
    // download + extraction, can take well over a minute) or a real `apt install`/`git clone`
    // through the same blocking call would freeze the whole app and risk an Android ANR. Now
    // matches the pattern ProjectCodingTools.kt already uses for the same reason: run on
    // Dispatchers.IO, keep a `busy` flag so the UI stays responsive and a second command can't
    // be submitted mid-run, and echo the submitted line immediately so the wait is visible.
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Live progress/output (new): reflects RoutingShellBackend.onLiveOutput while busy — a real
    // `bootstrap install` download %, or each line of a long real command — instead of the
    // terminal showing nothing until the whole thing finishes.
    val live by liveOutput.collectAsState()
    var nanoState by remember { mutableStateOf<NanoSession?>(null) }

    /** Resolves a `nano` argument (relative to the terminal's CURRENT directory, which may be
     *  deep in a `cd`-ed subfolder) into a workspace-relative FileService path — same
     *  root-containment rule EmbeddedShellBackend/AndroidProjectFileService already enforce, so
     *  nano can never read/write outside the sandboxed workspace. Returns null when the target
     *  would escape the workspace. */
    fun resolveWorkspaceRelativePath(arg: String): String? {
        val current = terminal.state().workingDirectory
        val candidate = runCatching {
            java.io.File(if (arg.startsWith("/")) arg else "$current/$arg").canonicalFile
        }.getOrNull() ?: return null
        val root = java.io.File(rootPath).canonicalFile
        val inside = candidate.path == root.path || candidate.path.startsWith(root.path + java.io.File.separator)
        if (!inside) return null
        return root.toPath().relativize(candidate.toPath()).toString().replace(java.io.File.separatorChar, '/')
    }

    fun openNano(arg: String) {
        val relPath = resolveWorkspaceRelativePath(arg)
        if (relPath == null) { out.add("nano: $arg: Permission denied (outside workspace)"); return }
        val read = files.read(relPath)
        val loaded = when {
            read.isSuccess -> read.value.orEmpty()
            read.error == FileError.NotFound -> "" // real nano opens a new empty buffer for a new filename
            read.error == FileError.IsDirectory -> { out.add("nano: $arg: Is a directory"); return }
            else -> { out.add("nano: $arg: cannot open (${read.error})"); return }
        }
        nanoState = NanoSession(displayPath = arg, filePath = relPath, original = loaded, content = loaded)
    }

    fun runCommand(){
        if (busy || input.isBlank()) return
        val submitted = input; input=""
        out.add("$cwdDisplay $ $submitted")
        if (submitted == "nano" || submitted.startsWith("nano ")) {
            val arg = submitted.removePrefix("nano").trim()
            if (arg.isBlank()) out.add("nano: missing filename. Usage: nano <file>") else openNano(arg)
            return
        }
        busy = true
        scope.launch {
            val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { terminal.execute(submitted) }
            if(r.output=="__CLEAR__") out.clear() else if(r.output.isNotEmpty()) out.add(r.output)
            // Real terminals don't print "exit code: 0" after every successful command — only
            // surface it when something actually failed, so this stays honest without adding
            // clutter Termux itself never shows.
            if (r.exitCode != 0 && !r.cancelled) out.add("exit code: ${r.exitCode}")
            cwdDisplay = displayCwd(terminal.state().workingDirectory)
            busy = false
        }
    }
    fun stopCommand(){ if (busy) scope.launch { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { terminal.cancel() } } }

    if (nanoState != null) {
        val session = nanoState!!
        val dirty = session.content != session.original
        Column(Modifier.fillMaxSize().background(Color(0xFF050609))){
            Row(Modifier.fillMaxWidth().background(Color(0xFF1B2A4A)).padding(vertical=4.dp,horizontal=8.dp)){
                Text("GNU nano",fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFE0E6FF))
                Spacer(Modifier.weight(1f))
                Text(session.displayPath + if(dirty) " (modified)" else "",fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFE0E6FF))
            }
            TextField(
                value=session.content,onValueChange={nanoState=session.copy(content=it)},
                modifier=Modifier.weight(1f).fillMaxWidth(),
                textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Color(0xFFE0E6FF)),
                colors=TextFieldDefaults.colors(
                    focusedContainerColor=Color(0xFF050609),unfocusedContainerColor=Color(0xFF050609),
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,
                    focusedTextColor=Color(0xFFE0E6FF),unfocusedTextColor=Color(0xFFE0E6FF),
                    cursorColor=Color(0xFF7AFF9B)
                )
            )
            if (session.confirmExit) {
                // Real nano's "Save modified buffer? Y/N/^C" prompt on exit with unsaved changes.
                Row(Modifier.fillMaxWidth().background(Color(0xFF1B2A4A)).padding(6.dp),verticalAlignment=Alignment.CenterVertically){
                    Text("Save modified buffer? ",fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFE0E6FF))
                    TextButton(onClick={
                        val w=files.write(session.filePath,session.content)
                        out.add(if(w.isSuccess) "nano: wrote ${session.displayPath}" else "nano: write failed (${w.error})")
                        nanoState=null
                    }){Text("Y",color=Color(0xFF7AFF9B))}
                    TextButton(onClick={out.add("nano: exited ${session.displayPath} without saving");nanoState=null}){Text("N",color=Color(0xFFEF4444))}
                    TextButton(onClick={nanoState=session.copy(confirmExit=false)}){Text("^C Cancel",color=Color(0xFF7FA8E0))}
                }
            } else {
                Row(Modifier.fillMaxWidth().background(Color(0xFF11121A)).padding(6.dp),horizontalArrangement=Arrangement.SpaceEvenly){
                    TextButton(onClick={
                        val w=files.write(session.filePath,session.content)
                        out.add(if(w.isSuccess) "nano: wrote ${session.displayPath}" else "nano: write failed (${w.error})")
                        if(w.isSuccess) nanoState=session.copy(original=session.content)
                    }){Icon(Icons.Default.Save,null,tint=Color(0xFF7AFF9B));Spacer(Modifier.width(4.dp));Text("^O Write Out",fontSize=10.sp,color=Color(0xFFE0E6FF))}
                    TextButton(onClick={
                        if(dirty) nanoState=session.copy(confirmExit=true)
                        else {out.add("nano: exited ${session.displayPath}");nanoState=null}
                    }){Icon(Icons.Default.Close,null,tint=Color(0xFFEF4444));Spacer(Modifier.width(4.dp));Text("^X Exit",fontSize=10.sp,color=Color(0xFFE0E6FF))}
                }
            }
        }
        return
    }

    // BUG FIX (screenshot: taps around the terminal seemingly "sending" input): audited every
    // pointer handler in this window. The scrollable Column below has no clickable/pointerInput
    // of its own (only its own verticalScroll gesture), the input TextField has no click
    // listener attached (only onValueChange for typing), and runCommand() above is now the
    // single place that calls terminal.execute — wired to exactly the Enter/Go key and, while
    // busy, the Stop icon. Nothing in this composable can submit a command any other way.
    val termScroll = rememberScrollState()
    // BUG FIX (Rule 1/17: terminal output looked "cut off" after a command): same class of bug
    // as the AI chat window above — new output lines never drove the scroll position, so a
    // command's real result could land below the visible area. Auto-scroll to the latest line
    // (which is always the live prompt row now, Termux-style) whenever output grows or a run
    // starts/finishes; this only moves scroll, it never touches command execution.
    LaunchedEffect(out.size, busy) {
        termScroll.animateScrollTo(termScroll.maxValue)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF050609)).verticalScroll(termScroll).padding(10.dp)){
        out.forEach{Text(it,fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFE0E6FF))}
        if (busy && live.isNotBlank()) {
            Text(live,fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFF7FA8E0))
        }
        // Termux-style live prompt: part of the SAME continuous scroll as the output above (not
        // a separate fixed row), always the last line, no explicit "Run" button — Enter/Go on
        // the keyboard submits, exactly like a real terminal.
        Row(verticalAlignment=Alignment.CenterVertically){
            Text("$cwdDisplay $",fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Color(0xFF7AFF9B))
            Spacer(Modifier.width(4.dp))
            TextField(
                input,{input=it},Modifier.weight(1f),singleLine=true,enabled=!busy,
                colors=TextFieldDefaults.colors(
                    focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,
                    focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent,
                    focusedTextColor=Color(0xFFE0E6FF),unfocusedTextColor=Color(0xFFE0E6FF),
                    cursorColor=Color(0xFF7AFF9B),
                    selectionColors=TextSelectionColors(handleColor=Color(0xFF7AFF9B),backgroundColor=Color(0x557AFF9B))
                ),
                textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=11.sp,color=Color(0xFFE0E6FF)),
                keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(onGo={runCommand()})
            )
            // Stop button (unchanged): needed now that real commands (bootstrap install, apt
            // install, git clone) can genuinely run for a while — the only control besides
            // Enter/Go that this window responds to.
            if (busy) {
                IconButton(onClick={stopCommand()},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Close,"Stop command",tint=Color(0xFFEF4444))}
            }
        }
    }
}

@Composable private fun GitWindow(files: FileService, githubAccounts: GitHubAccountStore, githubApi: GitHubApiClient){
    val context=LocalContext.current
    val root=context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")
    val service=remember(root.absolutePath){CommandGitService(root)}
    val scope=rememberCoroutineScope()
    var message by remember{mutableStateOf("")}
    var confirmPush by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf<GitStatus?>(null)}
    var result by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var githubToken by remember{mutableStateOf("")}
    var githubStatus by remember{mutableStateOf(githubAccounts.active()?.login ?: "Not connected")}
    fun show(r:GitResult<String>){ result=when(r){
        is GitResult.Success->r.value.ifBlank{"Done"}
        is GitResult.Failure->when(val e=r.error){
            is GitError.Validation->e.message
            is GitError.NotAvailable->e.message
            is GitError.Command->"Git error (${e.code}): ${e.message}"
            is GitError.Permission->"Permission: ${e.message}"
            is GitError.AuthenticationRequired->"Authentication required: ${e.message}"
            is GitError.DirtyWorkspace->e.message
        }
    }}
    fun refresh(){scope.launch{busy=true;status=when(val r=service.status()){
        is GitResult.Success->r.value
        is GitResult.Failure->{result="Git status failed: ${r.error}";null}
    };busy=false}}
    LaunchedEffect(Unit){refresh()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("SOURCE CONTROL",fontSize=11.sp,color=Color(0xFF9FAAD0),modifier=Modifier.weight(1f))
            Text(status?.branch?:"No repository",fontSize=9.sp,color=Color(0xFF8FA0C5))
            IconButton({refresh()},enabled=!busy,modifier=Modifier.size(30.dp)){Icon(Icons.Default.Refresh,"Refresh")}
        }
        Text(
            "ahead ${status?.ahead ?: 0} · behind ${status?.behind ?: 0} · upstream ${status?.upstream ?: "none"}",
            fontSize=8.sp,color=Color(0xFF7F8AA8)
        )
        Spacer(Modifier.height(6.dp))
        Text("Changes",fontSize=13.sp,fontWeight=FontWeight.Bold)
        status?.changes?.forEach{Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){
            Text(it.path,Modifier.weight(1f),fontSize=11.sp)
            Text((if(it.staged)"STAGED " else "")+it.state,fontSize=9.sp,color=Color(0xFFFFB45B))
        }}
        if(status?.changes?.isEmpty()==true) Text("Working tree clean",fontSize=10.sp,color=Color(0xFF22C55E))
        Spacer(Modifier.height(8.dp))
        Text("GitHub account",fontSize=12.sp,fontWeight=FontWeight.Bold)
        Text(githubStatus,fontSize=9.sp,color=if(githubStatus=="Not connected")Color(0xFFF59E0B)else Color(0xFF22C55E))
        OutlinedTextField(
            value=githubToken,onValueChange={githubToken=it},singleLine=true,
            visualTransformation=PasswordVisualTransformation(),
            label={Text("GitHub token (never sent to Sara/Groq)",fontSize=9.sp)},
            modifier=Modifier.fillMaxWidth().padding(top=5.dp)
        )
        Row{
            TextButton(onClick={
                if(githubToken.isNotBlank()) scope.launch {
                    busy=true
                    when(val r=githubApi.verifyToken(githubToken)){
                        is GitHubResult.Success->{githubStatus=r.value.login;githubToken="";result="GitHub account verified and encrypted locally."}
                        is GitHubResult.Failure->{result="GitHub verification failed: ${r.message}"}
                    }
                    busy=false
                }
            },enabled=!busy&&githubToken.isNotBlank()){Text("Verify & Save",fontSize=9.sp)}
            TextButton(onClick={
                githubAccounts.active()?.let{githubAccounts.remove(it.id)}
                githubStatus="Not connected";result="GitHub account removed from secure storage."
            },enabled=!busy&&githubAccounts.active()!=null){Text("Remove",fontSize=9.sp,color=Color(0xFFEF4444))}
        }
        Text("Use a GitHub token created on GitHub. Sara never receives the token value and it is stored through Android Keystore-backed SecureStore.",fontSize=8.sp,color=Color(0xFF7C86A6))
        Spacer(Modifier.height(8.dp))
        TextField(message,{message=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Commit message",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF12131C),unfocusedContainerColor=Color(0xFF12131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
        Row(Modifier.fillMaxWidth()){
            Button({scope.launch{
                busy=true
                val paths=status?.changes?.map{it.path}.orEmpty()
                when(val a=service.add(paths)){
                    is GitResult.Failure->result="Add failed: ${a.error}"
                    is GitResult.Success->when(val c=service.commit(message)){
                        is GitResult.Success->{result=c.value;if(message.isNotBlank())message=""}
                        is GitResult.Failure->result="Commit failed: ${c.error}"
                    }
                }
                refresh()
                busy=false
            }},enabled=!busy&&status?.changes?.isNotEmpty()==true&&message.isNotBlank(),modifier=Modifier.weight(1f)){Text("Commit")}
            Spacer(Modifier.width(6.dp))
            Button({confirmPush=true},enabled=!busy&&status?.changes?.isNotEmpty()==true&&message.isNotBlank(),modifier=Modifier.weight(1f)){Text("Commit & Push")}
        }
        Row(Modifier.fillMaxWidth()){
            TextButton({scope.launch{busy=true;show(service.pull(confirmed=true));refresh();busy=false}},enabled=!busy){Text("Pull")}
            TextButton({scope.launch{busy=true;show(service.fetch());refresh();busy=false}},enabled=!busy){Text("Fetch")}
            TextButton({scope.launch{busy=true;show(service.diff());busy=false}},enabled=!busy){Text("Diff")}
            TextButton({scope.launch{busy=true;show(service.diff(true));busy=false}},enabled=!busy){Text("Staged Diff")}
        }
        if(result.isNotBlank()) Text(result,fontSize=9.sp,color=Color(0xFFB8C3E5),modifier=Modifier.padding(top=5.dp))
        if(confirmPush){AlertDialog(
            onDismissRequest={confirmPush=false},
            title={Text("Confirm Git Push")},
            text={Text("Push the current branch to its configured remote using a normal non-force push? The workspace will not be force-pushed.")},
            confirmButton={TextButton(onClick={
                confirmPush=false
                scope.launch{
                    busy=true
                    val paths=status?.changes?.map{it.path}.orEmpty()
                    when(val a=service.add(paths)){
                        is GitResult.Failure->result="Add failed: ${a.error}"
                        is GitResult.Success->when(val c=service.commit(message)){
                            is GitResult.Success->{show(service.push(confirmed=true));message=""}
                            is GitResult.Failure->result="Commit failed: ${c.error}"
                        }
                    }
                    refresh();busy=false
                }
            }){Text("Push")}},
            dismissButton={TextButton(onClick={confirmPush=false}){Text("Cancel")}}
        )}
    }
}

@Composable private fun FilesWindow(files: FileService){
    var currentPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(files.listDirectory("").value.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ProjectFile?>(null) }
    // Rename UI state + last-operation error. FileService.rename()/delete() already existed and
    // already worked for both files and folders (AndroidProjectFileService/
    // InMemoryProjectFileService) — Rename just had no button wired to it in this window (Rule 4:
    // dead-end at the UI layer), and delete()'s FileResult was previously discarded, so a failed
    // delete (e.g. a non-empty folder) silently did nothing with no explanation (Rule 4:
    // silent-fail). Both are fixed here without touching the file-service logic itself.
    var renaming by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    var opError by remember { mutableStateOf<String?>(null) }
    fun refresh(path:String=currentPath){ items = files.listDirectory(path).value.orEmpty(); selected=null; renaming=false }
    fun describeFileError(e: FileError): String = when(e){
        FileError.InvalidPath -> "Invalid path."
        FileError.NotFound -> "Not found."
        FileError.AlreadyExists -> "A file or folder with that name already exists."
        FileError.NotDirectory -> "Not a folder."
        FileError.DirectoryNotEmpty -> "Folder is not empty — delete its contents first."
        FileError.IsDirectory -> "That is a folder."
        is FileError.Access -> e.message
        is FileError.InvalidName -> e.message
    }
    val shown = if(query.isBlank()) items else files.search(query).value.orEmpty()
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={currentPath=currentPath.substringBeforeLast('/',"");refresh() },enabled=currentPath.isNotEmpty(),modifier=Modifier.size(30.dp)){Icon(Icons.Default.ArrowBack,"Back")}
            Text("MyProject/${currentPath}".trimEnd('/'),Modifier.weight(1f),fontSize=11.sp)
            IconButton(onClick={refresh()},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Refresh,"Refresh")}
        }
        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            TextField(query,{query=it},Modifier.weight(1f),singleLine=true,placeholder={Text("Search files...",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF11131C),unfocusedContainerColor=Color(0xFF11131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            Spacer(Modifier.width(6.dp))
            TextButton(onClick={val name="new_file.txt";files.createFile(if(currentPath.isEmpty())name else "$currentPath/$name");refresh()}){Text("+ File",fontSize=10.sp)}
            TextButton(onClick={val name="new_folder";files.createFolder(if(currentPath.isEmpty())name else "$currentPath/$name");refresh()}){Text("+ Folder",fontSize=10.sp)}
        }
        if(selected!=null && !renaming) Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Selected: ${selected!!.name}",Modifier.weight(1f),fontSize=10.sp,color=Color(0xFF9CA8C5))
            TextButton(onClick={renameText=selected!!.name;opError=null;renaming=true}){Text("Rename",fontSize=10.sp)}
            TextButton(onClick={
                val r=files.delete(selected!!.path)
                opError = r.error?.let{ "Delete failed: ${describeFileError(it)}" }
                refresh()
            }){Text("Delete",fontSize=10.sp,color=Color(0xFFEF4444))}
        }
        if(selected!=null && renaming) Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){
            TextField(renameText,{renameText=it},Modifier.weight(1f),singleLine=true,colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF11131C),unfocusedContainerColor=Color(0xFF11131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            TextButton(onClick={
                val r=files.rename(selected!!.path,renameText.trim())
                opError = r.error?.let{ "Rename failed: ${describeFileError(it)}" }
                refresh()
            },enabled=renameText.isNotBlank()){Text("Save",fontSize=10.sp)}
            TextButton(onClick={renaming=false;opError=null}){Text("Cancel",fontSize=10.sp)}
        }
        if(opError!=null) Text(opError!!,fontSize=9.sp,color=Color(0xFFEF4444),modifier=Modifier.padding(bottom=3.dp))
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            shown.forEach { f ->
                Surface(Modifier.padding(6.dp).size(92.dp,86.dp).clickable{
                    selected=f
                    if(f.kind==FileKind.FOLDER && query.isBlank()){currentPath=f.path;refresh()}
                },RoundedCornerShape(10.dp),color=if(selected?.path==f.path)Color(0xFF1B2450) else Color(0xFF11131C)){
                    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center){Icon(if(f.kind==FileKind.FOLDER)Icons.Default.Folder else Icons.Default.Description,null,tint=Color(0xFF80B6FF));Text(f.name,fontSize=10.sp,maxLines=1);Text(if(f.kind==FileKind.FOLDER)"folder" else "${f.sizeBytes} B",fontSize=8.sp,color=Color.Gray)}
                }
            }
        }
    }
}


@Composable private fun SettingsWindow(settingsStore: AISettingsStore, offlineAi: LocalLlamaEngine){
    val context=LocalContext.current
    val store=settingsStore
    val localModelManager = remember(context) { LocalModelManager(context) }
    var onlineModeOn by remember { mutableStateOf(store.isOnlineModeEnabled()) }
    var apiKeyInput by remember{ mutableStateOf("") }
    var apiKeyConfigured by remember{ mutableStateOf(store.hasApiKey()) }
    var model by remember{ mutableStateOf(store.getModel()) }
    var timeoutSeconds by remember{ mutableStateOf((store.getTimeoutMs()/1000).toString()) }
    var retryLimit by remember{ mutableStateOf(store.getRetryLimit().toString()) }
    var maxTokens by remember{ mutableStateOf(store.getMaxOutputTokens()?.toString() ?: "") }
    var savedNotice by remember{ mutableStateOf<String?>(null) }
    var localPath by remember { mutableStateOf(store.getLocalModelPath().orEmpty()) }
    var localContext by remember { mutableStateOf(store.getLocalContextSize().toString()) }
    var localThreads by remember { mutableStateOf(store.getLocalThreads().toString()) }
    var localMaxTokens by remember { mutableStateOf(store.getLocalMaxOutputTokens().toString()) }
    var localBusy by remember { mutableStateOf(false) }
    var localStatus by remember { mutableStateOf<String?>(null) }
    val localScope = rememberCoroutineScope()
    val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            localBusy = true
            localStatus = "Preparing local model storage…"
            localScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                offlineAi.unload()
                val result = localModelManager.installFromUri(context.contentResolver, uri)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    localBusy = false
                    result.onSuccess {
                        store.setLocalModelPath(it.absolutePath)
                        localPath = it.absolutePath
                        localStatus = "Valid GGUF model installed. It will load on first local request."
                    }.onFailure { localStatus = "Model import failed: ${it.message ?: it.javaClass.simpleName}" }
                }
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)){
        Text("Appearance",fontSize=13.sp,fontWeight=FontWeight.Bold);Text("Dark / deep purple / neon blue",fontSize=11.sp,color=Color(0xFF9AA7C8),modifier=Modifier.padding(vertical=8.dp));Divider()
        Text("AI Profile",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("Name: Sara\nVoice: replaceable adapter",fontSize=11.sp,color=Color(0xFF9AA7C8));Divider()

        // Groq is opt-in now (Rule: real toggle, default off — see AISettingsStore). The key is
        // stored only through SecureStore (AndroidKeyStore-backed AES/GCM) and is never shown back
        // in full once saved.
        Text("AI Provider — Groq (online, optional)",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
        Row(Modifier.fillMaxWidth().padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
            Switch(checked=onlineModeOn,onCheckedChange={ onlineModeOn=it; store.setOnlineModeEnabled(it) })
            Spacer(Modifier.width(8.dp))
            Text(if(onlineModeOn) "Online Mode is ON — Sara uses Groq" else "Online Mode is OFF — Sara runs fully offline",fontSize=11.sp,color=if(onlineModeOn) Color(0xFF22C55E) else Color(0xFF9AA7C8))
        }
        Text(if(apiKeyConfigured) "API key: configured (hidden)" else "API key: not configured",fontSize=11.sp,color=if(apiKeyConfigured) Color(0xFF22C55E) else Color(0xFF9AA7C8),modifier=Modifier.padding(top=6.dp))
        OutlinedTextField(
            value=apiKeyInput,onValueChange={apiKeyInput=it},
            label={Text("Groq API key",fontSize=10.sp)},
            singleLine=true,visualTransformation=PasswordVisualTransformation(),
            modifier=Modifier.fillMaxWidth().padding(top=8.dp)
        )
        Row(Modifier.padding(top=6.dp)){
            TextButton(onClick={ if(apiKeyInput.isNotBlank()){ store.setApiKey(apiKeyInput); apiKeyInput=""; apiKeyConfigured=true; savedNotice="Saved." } }){Text("Save key",fontSize=10.sp)}
            TextButton(onClick={ store.clearApiKey(); apiKeyConfigured=false; savedNotice="API key removed." }){Text("Remove key",fontSize=10.sp,color=Color(0xFFEF4444))}
        }
        OutlinedTextField(value=model,onValueChange={model=it},label={Text("Groq model",fontSize=10.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=10.dp))
        Row(Modifier.fillMaxWidth().padding(top=8.dp)){
            OutlinedTextField(value=timeoutSeconds,onValueChange={timeoutSeconds=it.filter(Char::isDigit)},label={Text("Timeout (sec)",fontSize=10.sp)},singleLine=true,modifier=Modifier.weight(1f).padding(end=6.dp))
            OutlinedTextField(value=retryLimit,onValueChange={retryLimit=it.filter(Char::isDigit)},label={Text("Retry limit",fontSize=10.sp)},singleLine=true,modifier=Modifier.weight(1f))
        }
        OutlinedTextField(value=maxTokens,onValueChange={maxTokens=it.filter(Char::isDigit)},label={Text("Max output tokens (blank = provider default)",fontSize=10.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
        TextButton(onClick={
            store.setModel(model)
            timeoutSeconds.toIntOrNull()?.let{ store.setTimeoutMs(it*1000) }
            retryLimit.toIntOrNull()?.let{ store.setRetryLimit(it) }
            store.setMaxOutputTokens(maxTokens.toIntOrNull())
            model=store.getModel();timeoutSeconds=(store.getTimeoutMs()/1000).toString();retryLimit=store.getRetryLimit().toString();maxTokens=store.getMaxOutputTokens()?.toString()?:""
            savedNotice="Settings saved."
        },modifier=Modifier.padding(top=6.dp)){Text("Save provider settings",fontSize=10.sp)}
        savedNotice?.let{ Text(it,fontSize=9.sp,color=Color(0xFF22C55E),modifier=Modifier.padding(top=4.dp)) }
        Text("Values are clamped to safe ranges automatically. These settings only take effect while Online Mode above is ON — with it off, Sara never contacts Groq.",fontSize=9.sp,color=Color(0xFF7C86A6),modifier=Modifier.padding(top=6.dp))
        Divider(Modifier.padding(top=14.dp))

        Text("AI Provider — Offline Local LLM (default)",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp))
        Text("This is what Sara uses by default (Online Mode above is off). Real on-device inference runs through the imported GGUF model below — deterministic commands (calculator, device time/date, etc.) still resolve instantly without the model; everything else is answered by it directly.",fontSize=10.sp,color=Color(0xFF9AA7C8),modifier=Modifier.padding(top=6.dp))
        Text(if(localPath.isBlank()) "Model: not configured" else "Model: configured (${java.io.File(localPath).length() / (1024*1024)} MiB)",fontSize=10.sp,color=if(localPath.isBlank()) Color(0xFFF59E0B) else Color(0xFF22C55E),modifier=Modifier.padding(top=6.dp))
        Row(Modifier.padding(top=6.dp),verticalAlignment=Alignment.CenterVertically){
            TextButton(onClick={ if(!localBusy) modelPicker.launch(arrayOf("application/octet-stream","application/*","*/*")) },enabled=!localBusy){Text(if(localBusy) "Importing…" else "Select GGUF model",fontSize=10.sp)}
            TextButton(onClick={ localScope.launch { offlineAi.unload(); localStatus="Offline model state reset." } }){Text("Unload",fontSize=10.sp)}
            TextButton(onClick={
                if (!localBusy) localScope.launch {
                    offlineAi.unload()
                    if (localModelManager.removeModel()) {
                        store.setLocalModelPath(null)
                        localPath = ""
                        localStatus = "Local model removed from app storage."
                    } else {
                        localStatus = "Local model could not be removed."
                    }
                }
            }){Text("Remove",fontSize=10.sp,color=Color(0xFFEF4444))}
        }
        OutlinedTextField(value=localContext,onValueChange={localContext=it.filter(Char::isDigit)},label={Text("Context size",fontSize=10.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=4.dp))
        Row(Modifier.fillMaxWidth().padding(top=6.dp)){
            OutlinedTextField(value=localThreads,onValueChange={localThreads=it.filter(Char::isDigit)},label={Text("CPU threads",fontSize=10.sp)},singleLine=true,modifier=Modifier.weight(1f).padding(end=6.dp))
            OutlinedTextField(value=localMaxTokens,onValueChange={localMaxTokens=it.filter(Char::isDigit)},label={Text("Max output",fontSize=10.sp)},singleLine=true,modifier=Modifier.weight(1f))
        }
        TextButton(onClick={ store.setLocalContextSize(localContext.toIntOrNull() ?: 2048); store.setLocalThreads(localThreads.toIntOrNull() ?: 4); store.setLocalMaxOutputTokens(localMaxTokens.toIntOrNull() ?: 256); localContext=store.getLocalContextSize().toString(); localThreads=store.getLocalThreads().toString(); localMaxTokens=store.getLocalMaxOutputTokens().toString(); localStatus="Offline model settings saved." },modifier=Modifier.padding(top=4.dp)){Text("Save offline settings",fontSize=10.sp)}
        localStatus?.let { Text(it,fontSize=9.sp,color=Color(0xFF9AA7C8),modifier=Modifier.padding(top=4.dp)) }
        Text("Recommended starting point: a small Q4 GGUF model such as Qwen2.5 0.5B (~400 MiB). The model is not bundled or downloaded by Sara; import the GGUF yourself. It loads on first offline request after import and stays loaded until you Unload or Remove it here.",fontSize=9.sp,color=Color(0xFF7C86A6),modifier=Modifier.padding(top=6.dp))

        Text("Security",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("Sensitive actions require confirmation. Secrets are not stored in source code, logs, or plaintext preferences — the Groq key lives only in the Android-Keystore-backed secure store.",fontSize=11.sp,color=Color(0xFF9AA7C8))
    }
}
@Composable private fun BrowserWindow(windowId: String, browser: AndroidBrowserService){
    val context = LocalContext.current
    val stateMap by browser.states.collectAsState()
    val state = stateMap[windowId]
    var address by remember(windowId){ mutableStateOf(state?.url ?: "https://www.google.com") }
    val webView = remember(windowId) { WebView(context) }
    val scope = rememberCoroutineScope()

    DisposableEffect(webView, windowId) {
        browser.register(windowId, webView)
        onDispose { browser.unregister(windowId) }
    }

    LaunchedEffect(state?.url) {
        if (!state?.url.isNullOrBlank()) address = state.url
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF080910))) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={ scope.launch { browser.back(windowId) } },modifier=Modifier.size(32.dp)){
                Icon(Icons.Default.ArrowBack,"Back",Modifier.size(16.dp))
            }
            IconButton(onClick={ scope.launch { browser.forward(windowId) } },modifier=Modifier.size(32.dp)){
                Icon(Icons.Default.ArrowForward,"Forward",Modifier.size(16.dp))
            }
            IconButton(onClick={ scope.launch { browser.reload(windowId) } },modifier=Modifier.size(32.dp)){
                Icon(Icons.Default.Refresh,"Reload",Modifier.size(16.dp))
            }
            IconButton(onClick={ scope.launch { browser.stop(windowId) } },modifier=Modifier.size(32.dp)){
                Icon(Icons.Default.Close,"Stop",Modifier.size(15.dp))
            }
            TextField(
                value=address,
                onValueChange={address=it},
                Modifier.weight(1f).height(38.dp),
                singleLine=true,
                placeholder={Text("Search or enter address",fontSize=10.sp)},
                colors=TextFieldDefaults.colors(
                    focusedContainerColor=Color(0xFF11131C),
                    unfocusedContainerColor=Color(0xFF11131C),
                    focusedIndicatorColor=Color.Transparent,
                    unfocusedIndicatorColor=Color.Transparent
                ),
                keyboardActions=androidx.compose.foundation.text.KeyboardActions(
                    onDone={ scope.launch { browser.open(windowId,address) } }
                )
            )
            TextButton(onClick={ manager.openNew(WindowType.BROWSER) }){Text("New",fontSize=10.sp)}
            TextButton(onClick={ scope.launch { browser.open(windowId,address) } }){Text("Go",fontSize=10.sp)}
        }
        if (state?.loading == true) {
            LinearProgressIndicator(
                progress = { state.progress.coerceIn(0,100) / 100f },
                modifier=Modifier.fillMaxWidth().height(2.dp)
            )
        }
        state?.lastError?.let {
            Text("Browser error: $it",fontSize=9.sp,color=Color(0xFFFF9AAE),modifier=Modifier.padding(horizontal=8.dp,vertical=3.dp))
        }
        Text(
            if (state?.title.isNullOrBlank()) "No page title" else state.title,
            fontSize=8.sp,color=Color(0xFF7F8AA8),
            maxLines=1,modifier=Modifier.padding(horizontal=8.dp,vertical=2.dp)
        )
        AndroidView(factory={webView},modifier=Modifier.weight(1f).fillMaxWidth())
    }
}
