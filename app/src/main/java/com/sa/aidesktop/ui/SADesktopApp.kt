package com.sa.aidesktop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.*
import com.sa.aidesktop.core.ai.*
import com.sa.aidesktop.core.ai.tools.*
import com.sa.aidesktop.core.files.*
import com.sa.aidesktop.core.git.*
import com.sa.aidesktop.core.terminal.*
import com.sa.aidesktop.core.window.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import androidx.compose.runtime.DisposableEffect
import com.sa.aidesktop.core.voice.AndroidTextToSpeechEngine
import com.sa.aidesktop.core.voice.AndroidSpeechToTextEngine
import java.text.SimpleDateFormat
import java.util.*
import androidx.compose.ui.res.painterResource
import com.sa.aidesktop.R

private val manager = DesktopWindowManager()
private val ai = OfflineDemoAI()



@Composable fun SADesktopApp() {
    val windows by manager.state.collectAsState()
    val context = LocalContext.current
    val files = remember(context) { AndroidProjectFileService(context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    val terminal = remember(context) { EmbeddedTerminalService(context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    LaunchedEffect(Unit) { listOf(WindowType.DEVELOPER, WindowType.AI, WindowType.TERMINAL, WindowType.GIT, WindowType.FILES).forEach(manager::open); manager.focus("ai") }
    var startOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF050611))) {
        LaunchedEffect(maxWidth.value, maxHeight.value) { manager.clampToWorkspace(maxWidth.value, (maxHeight.value - 58f).coerceAtLeast(1f)) }
        DesktopBackdrop()
        DesktopIcons(onOpen = { if (it == WindowType.BROWSER) manager.openNew(it) else manager.open(it) })
        windows.filter { it.state != WindowState.MINIMIZED }.sortedBy { it.z }.forEach { w ->
            DesktopWindowView(w, maxWidth.value, maxHeight.value - 58f, files, terminal)
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
    LaunchedEffect(Unit){ while(kotlinx.coroutines.currentCoroutineContext().isActive){ now=Date(); val bm=context.getSystemService(BatteryManager::class.java); battery=bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1; val cm=context.getSystemService(ConnectivityManager::class.java); online=cm?.activeNetwork?.let{cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}==true; kotlinx.coroutines.delay(1000) } }
    Row(modifier.fillMaxWidth().height(58.dp).background(Color(0xE80A0B14)).border(1.dp,Color(0x443D78FF)),verticalAlignment=Alignment.CenterVertically){
        Spacer(Modifier.width(12.dp)); Surface(Modifier.size(38.dp).clickable(onClick=onStart),RoundedCornerShape(9.dp),color=Color(0xFF3D20A7)){Box(contentAlignment=Alignment.Center){Text("SA",fontWeight=FontWeight.Bold)}};Spacer(Modifier.width(10.dp))
        val pinned=listOf(WindowType.DEVELOPER to Icons.Default.Code,WindowType.AI to Icons.Default.Face,WindowType.TERMINAL to Icons.Default.Terminal,WindowType.GIT to Icons.Default.AccountTree,WindowType.FILES to Icons.Default.Folder,WindowType.BROWSER to Icons.Default.Public)
        pinned.forEach{(type,icon)->val open=windows.lastOrNull{it.type==type};Surface(Modifier.padding(horizontal=2.dp).size(42.dp).clickable{if(type==WindowType.BROWSER) manager.openNew(type) else onOpen(type)},RoundedCornerShape(9.dp),color=if(open?.focused==true)Color(0x443D78FF)else Color.Transparent){Box(contentAlignment=Alignment.Center){Icon(icon,type.name,tint=if(open!=null)Color(0xFFE1E7FF)else Color(0xFF7E89A8),modifier=Modifier.size(20.dp));if(open!=null)Box(Modifier.align(Alignment.BottomCenter).size(16.dp,2.dp).background(Color(0xFF9C5CFF),RoundedCornerShape(2.dp)))}}}
        Spacer(Modifier.weight(1f));
        Icon(if(online)Icons.Default.Wifi else Icons.Default.WifiOff,"Network",Modifier.size(18.dp).clickable{runCatching{context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))}},tint=Color(0xFFB9C7E8));Spacer(Modifier.width(10.dp));
        Icon(Icons.Default.VolumeUp,"Volume",Modifier.size(18.dp).clickable{runCatching{context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))}},tint=Color(0xFFB9C7E8));Spacer(Modifier.width(10.dp));
        Icon(Icons.Default.BatteryFull,"Battery",Modifier.size(18.dp).clickable{runCatching{context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))}},tint=Color(0xFFB9C7E8));Spacer(Modifier.width(6.dp));if(battery>=0)Text("$battery%",fontSize=9.sp,color=Color(0xFFB9C7E8));Spacer(Modifier.width(10.dp));Column(horizontalAlignment=Alignment.End){Text(SimpleDateFormat("HH:mm",Locale.getDefault()).format(now),fontSize=12.sp);Text(SimpleDateFormat("dd/MM/yyyy",Locale.getDefault()).format(now),fontSize=9.sp,color=Color(0xFF9DA8C4))};Spacer(Modifier.width(14.dp))
    }
}

@Composable private fun StartMenu(onOpen:(WindowType)->Unit, modifier: Modifier = Modifier) {
    Surface(modifier.padding(start=10.dp,bottom=64.dp).width(270.dp).height(360.dp), RoundedCornerShape(18.dp), color=Color(0xF20A0B15), shadowElevation=18.dp) {
        Column(Modifier.padding(16.dp)) { Text("SA Desktop",fontSize=20.sp,fontWeight=FontWeight.Bold); Text("AI developer workstation",fontSize=11.sp,color=Color(0xFF9CA7C8)); Spacer(Modifier.height(14.dp)); listOf("Developer Workspace" to WindowType.DEVELOPER,"AI Assistant - Sara" to WindowType.AI,"Terminal" to WindowType.TERMINAL,"Git" to WindowType.GIT,"File Manager" to WindowType.FILES,"Browser" to WindowType.BROWSER,"Settings" to WindowType.SETTINGS).forEach{(t,w)->Text(t,Modifier.fillMaxWidth().clickable{onOpen(w)}.padding(12.dp),color=Color.White)} }
    }
}

@Composable private fun DesktopWindowView(w: DesktopWindow, screenW:Float, screenH:Float, files: FileService, terminal: TerminalService) {
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
                when(w.type){
                    WindowType.DEVELOPER->DeveloperWindow(files)
                    WindowType.AI->AIWindow()
                    WindowType.TERMINAL->TerminalWindow(terminal)
                    WindowType.GIT->GitWindow(files)
                    WindowType.FILES->FilesWindow(files)
                    WindowType.SETTINGS->SettingsWindow()
                    WindowType.BROWSER->BrowserWindow(w.id)
                }
                if(w.state==WindowState.NORMAL) ResizeHandle(w, width, height, screenW, screenH)
            }
        }
    }
}

private fun windowDefaults(type: WindowType, screenW: Float, screenH: Float): WindowBounds {
    val usableH=(screenH-68f).coerceAtLeast(300f)
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

@Composable private fun ResizeHandle(w: DesktopWindow, width: Float, height: Float, screenW: Float, screenH: Float) {
    val handle = 12.dp
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
        Text(w.title,Modifier.weight(1f).padding(start=6.dp),fontSize=12.sp,color=Color(0xFFE5E9F7),maxLines=1)
        IconButton({manager.minimize(w.id)},Modifier.size(28.dp)){Icon(Icons.Default.Remove,null,Modifier.size(15.dp))}
        IconButton({if(w.state==WindowState.MAXIMIZED)manager.restore(w.id)else manager.maximize(w.id)},Modifier.size(28.dp)){Icon(if(w.state==WindowState.MAXIMIZED)Icons.Default.FullscreenExit else Icons.Default.CropSquare,null,Modifier.size(14.dp))}
        IconButton({manager.close(w.id)},Modifier.size(28.dp)){Icon(Icons.Default.Close,null,Modifier.size(15.dp))}
    }
}

@Composable private fun DeveloperWindow(files: FileService){
    var tree by remember { mutableStateOf(files.projectTree()) }
    var tabs by remember { mutableStateOf(listOf("src/main.py")) }
    var selectedTab by remember { mutableStateOf("src/main.py") }
    var buffers by remember { mutableStateOf(mapOf("src/main.py" to files.read("src/main.py").value.orEmpty())) }
    var savedBuffers by remember { mutableStateOf(buffers) }

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
        tabs = if (nextTabs.isEmpty()) listOf("src/main.py") else nextTabs
        if (selectedTab == path) selectedTab = tabs.first()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.width(185.dp).fillMaxHeight().background(Color(0xFF0B0C13)).verticalScroll(rememberScrollState()).padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("EXPLORER", fontSize=11.sp, color=Color(0xFF98A5C8), modifier=Modifier.weight(1f))
                    IconButton(onClick={ tree = files.projectTree() }, modifier=Modifier.size(26.dp)) { Icon(Icons.Default.Refresh, "Refresh", tint=Color(0xFF9BB8FF), modifier=Modifier.size(15.dp)) }
                }
                Text("MyProject", fontSize=10.sp, color=Color(0xFF6F7C9F), modifier=Modifier.padding(vertical=5.dp))
                Tree(tree,0){ path -> openFile(path) }
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
        Row(Modifier.fillMaxWidth().height(30.dp).background(Color(0xFF0B0C13)).padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically){
            Text("Ln 1, Col 1",fontSize=9.sp)
            Spacer(Modifier.weight(1f))
            Text(if(dirty) "Unsaved changes" else "Saved",fontSize=9.sp,color=if(dirty) Color(0xFFFFB45B) else Color(0xFF7FE0A2))
            Spacer(Modifier.width(12.dp))
            Text("UTF-8   Kotlin/Compose editor core",fontSize=9.sp,color=Color(0xFF8F9DBA))
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
                    Text(if(dirty) "● " else "",fontSize=9.sp,color=Color(0xFFFFB45B)); Text(path.substringAfterLast('/'),Modifier.weight(1f),fontSize=10.sp,color=if(active)Color.White else Color(0xFF8E98B3),maxLines=1)
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
        Row(Modifier.fillMaxSize().weight(1f)){
            Column(Modifier.width(48.dp).verticalScroll(rememberScrollState()).padding(top=9.dp),horizontalAlignment=Alignment.End){lines.indices.forEach{Text("${it+1}",fontSize=10.sp,color=if(it+1==1)Color(0xFFB5C5FF)else Color(0xFF525A73),modifier=Modifier.padding(end=8.dp))}}
            TextField(value=code,onValueChange={change(it)},Modifier.fillMaxSize().verticalScroll(scroll),visualTransformation=object:VisualTransformation{
                override fun filter(text:AnnotatedString)=TransformedText(highlightCode(text.text),OffsetMapping.Identity)
            },textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=12.sp,color=Color(0xFFDDE5FF)),colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),singleLine=false)
        }
    }
}

@Composable private fun AIWindow(){
    val context = LocalContext.current
    val files = remember(context) { AndroidProjectFileService(context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")) }
    val service = remember { OfflineDemoAI() }
    val tts = remember(context) { AndroidTextToSpeechEngine(context) }
    val stt = remember(context) { AndroidSpeechToTextEngine(context) }
    val voiceScope = rememberCoroutineScope()
    val voiceState by stt.state.collectAsState()
    val transcript by stt.transcript.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) stt.start() }
    DisposableEffect(tts, stt) { onDispose { tts.release(); stt.release() } }
    val tools = remember(files) { ToolRegistry(listOf(ReadFileTool(files), SearchFileTool(files), WriteFileTool(files), WindowControlTool(manager))) }
    val gate = remember { PermissionGate() }
    val gateway = remember(tools) { ToolExecutionGateway(tools, gate) }
    val scope = rememberCoroutineScope()
    var input by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf<ToolRequest?>(null)}
    LaunchedEffect(transcript) { if (transcript.isNotBlank()) input = transcript }
    val profile = remember { AIProfile("sara", "Sara", "calm developer assistant", "default", "Hinglish", "sara") }
    val msgs=remember{mutableStateListOf(
        AIMessage("Hello! I'm ${profile.name}\nOffline developer assistant ready.",false,"Now"),
        AIMessage("Main project context ko need ke hisaab se use karungi. File changes aur sensitive actions approval ke bina apply nahi honge.",false,"Now")
    )}
    fun send(){
        val p=input.trim(); if(p.isBlank() || busy) return
        msgs.add(AIMessage(p,true,"Now")); input=""; busy=true
        scope.launch {
            when(val result=service.chat(AIRequest(p, ProjectContext(projectStructure="MyProject workspace")))){
                is AIResult.Success -> {
                    msgs.add(AIMessage(result.value.text,false,"Now"))
                    pending=result.value.toolRequests.firstOrNull { gate.requiresApproval(it.risk) }
                }
                is AIResult.Failure -> msgs.add(AIMessage("AI error: ${result.error}",false,"Now"))
            }
            busy=false
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF080911))){
        Row(Modifier.fillMaxWidth().height(52.dp).background(Color(0xFF10121D)).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically){
            Surface(Modifier.size(34.dp),RoundedCornerShape(10.dp),color=Color(0xFF4D1A78)){androidx.compose.foundation.Image(painterResource(R.drawable.sara_avatar),contentDescription="Sara",modifier=Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),contentScale=androidx.compose.ui.layout.ContentScale.Crop)}
            Column(Modifier.padding(start=9.dp).weight(1f)){Text(profile.name,fontSize=13.sp,fontWeight=FontWeight.Bold);Text("Offline-ready • ${profile.language}",fontSize=9.sp,color=Color(0xFF8996B5))}
            Text(if(busy) "Thinking…" else "Ready",fontSize=9.sp,color=if(busy) Color(0xFFFFC36B) else Color(0xFF79DFA0))
        }
        Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            tools.all().forEach { tool ->
                Surface(Modifier.padding(horizontal=3.dp),RoundedCornerShape(8.dp),color=Color(0xFF111522)){Text(tool.id.replace('_',' '),Modifier.padding(horizontal=8.dp,vertical=5.dp),fontSize=8.sp,color=Color(0xFF9EACCC))}
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)){
            msgs.forEach{m->Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.fromUser)Arrangement.End else Arrangement.Start){Surface(Modifier.padding(vertical=4.dp).widthIn(max=300.dp),RoundedCornerShape(12.dp),color=if(m.fromUser)Color(0xFF4D1A78)else Color(0xFF171820)){Text(m.text,Modifier.padding(10.dp),fontSize=12.sp)}}}}
        pending?.let { request ->
            Surface(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),RoundedCornerShape(12.dp),color=Color(0xFF171322),border=BorderStroke(1.dp,Color(0xFF70458D))){
                Column(Modifier.padding(10.dp)){
                    Text("Approval required",fontSize=11.sp,fontWeight=FontWeight.Bold,color=Color(0xFFE4C7FF))
                    Text("Tool: ${request.toolId} • Risk: ${request.risk}",fontSize=9.sp,color=Color(0xFFAFA7BE))
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End){
                        TextButton(onClick={pending=null}){Text("Cancel",fontSize=9.sp)}
                        TextButton(onClick={
                            val requestToApply=request
                            scope.launch {
                                when(val applied=gateway.execute(requestToApply, approved=true)){
                                    is AIResult.Success -> msgs.add(AIMessage("Applied: ${applied.value.output}",false,"Now"))
                                    is AIResult.Failure -> msgs.add(AIMessage("Action failed: ${applied.error}",false,"Now"))
                                }
                                pending=null
                            }
                        }){Text("Apply",fontSize=9.sp)}
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            TextField(input,{input=it},Modifier.weight(1f),placeholder={Text("Ask Sara about your project...",fontSize=11.sp)},singleLine=true,enabled=!busy,colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF11121A),unfocusedContainerColor=Color(0xFF11121A),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            IconButton({ voiceScope.launch { msgs.lastOrNull { !it.fromUser }?.let { tts.speak(it.text) } } }){Icon(Icons.Default.VolumeUp,"Speak",tint=Color(0xFF7FC8FF))}
            IconButton({ if (voiceState.listening) stt.stop() else micPermission.launch(android.Manifest.permission.RECORD_AUDIO) }){Icon(if(voiceState.listening) Icons.Default.Stop else Icons.Default.Mic,"Voice input",tint=if(voiceState.listening) Color(0xFFFF7A9A) else Color(0xFF7FC8FF))}
            IconButton({send()},enabled=!busy){Icon(Icons.Default.Send,null,tint=Color(0xFFB55CFF))}
        }
    }
}

@Composable private fun TerminalWindow(terminal: TerminalService){
    var input by remember{mutableStateOf("")}
    val initial=terminal.state()
    val out=remember{mutableStateListOf("SA Embedded Terminal","Workspace: ${initial.workingDirectory}","Type help for supported commands.","user@sa-desktop:~/MyProject$")}
    Column(Modifier.fillMaxSize().background(Color(0xFF050609))){
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(10.dp)){out.forEach{Text(it,fontFamily=FontFamily.Monospace,fontSize=10.sp,color=Color(0xFFE0E6FF))}}
        Row(Modifier.padding(8.dp),verticalAlignment=Alignment.CenterVertically){
            Text("$",fontFamily=FontFamily.Monospace,color=Color(0xFF7AFF9B))
            TextField(input,{input=it},Modifier.weight(1f),singleLine=true,colors=TextFieldDefaults.colors(focusedContainerColor=Color.Transparent,unfocusedContainerColor=Color.Transparent,focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent),textStyle=LocalTextStyle.current.copy(fontFamily=FontFamily.Monospace,fontSize=11.sp))
            IconButton({
                val r=terminal.execute(input); input=""
                if(r.output=="__CLEAR__") out.clear() else { if(r.output.isNotEmpty()) out.add(r.output); out.add("exit code: ${r.exitCode}"); out.add("user@sa-desktop:~/MyProject$") }
            }){Icon(Icons.Default.PlayArrow,"Run")}
        }
    }
}

@Composable private fun GitWindow(files: FileService){
    val context=LocalContext.current
    val root=context.filesDir.resolve("SA-AIDesktop/workspace/MyProject")
    val service=remember(root.absolutePath){CommandGitService(root)}
    val scope=rememberCoroutineScope()
    var message by remember{mutableStateOf("")}; var confirmPush by remember{mutableStateOf(false)}; var status by remember{mutableStateOf<GitStatus?>(null)}; var result by remember{mutableStateOf("")}; var busy by remember{mutableStateOf(false)}
    fun show(r:GitResult<String>){ result=when(r){is GitResult.Success->r.value.ifBlank{"Done"};is GitResult.Failure->when(val e=r.error){is GitError.Validation->e.message;is GitError.NotAvailable->e.message;is GitError.Command->"Git error (${e.code}): ${e.message}";is GitError.Permission->"Permission: ${e.message}"}} }
    fun refresh(){scope.launch{busy=true;status=when(val r=service.status()){is GitResult.Success->r.value;is GitResult.Failure->{result="Git status failed: ${r.error}";null}};busy=false}}
    LaunchedEffect(Unit){refresh()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("SOURCE CONTROL",fontSize=11.sp,color=Color(0xFF9FAAD0),modifier=Modifier.weight(1f));Text(status?.branch?:"No repository",fontSize=9.sp,color=Color(0xFF8FA0C5));IconButton({refresh()},enabled=!busy,modifier=Modifier.size(30.dp)){Icon(Icons.Default.Refresh,"Refresh")}}
        Spacer(Modifier.height(6.dp)); Text("Changes",fontSize=13.sp,fontWeight=FontWeight.Bold); status?.changes?.forEach{Row(Modifier.fillMaxWidth().padding(vertical=5.dp)){Text(it.path,Modifier.weight(1f),fontSize=11.sp);Text(it.state,fontSize=10.sp,color=Color(0xFFFFB45B))}}
        if(status?.changes?.isEmpty()==true) Text("Working tree clean",fontSize=10.sp,color=Color(0xFF79DFA0))
        Spacer(Modifier.weight(1f)); TextField(message,{message=it},Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Commit message",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF12131C),unfocusedContainerColor=Color(0xFF12131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
        Row(Modifier.fillMaxWidth()){Button({scope.launch{busy=true;val paths=status?.changes?.map{it.path}.orEmpty();val a=service.add(paths);if(a is GitResult.Failure)result="Add failed: ${a.error}" else show(service.commit(message));message="";refresh()}},enabled=!busy&&status?.changes?.isNotEmpty()==true,modifier=Modifier.weight(1f)){Text("Commit")};Spacer(Modifier.width(6.dp));Button({confirmPush=true},enabled=!busy&&status?.changes?.isNotEmpty()==true,modifier=Modifier.weight(1f)){Text("Commit & Push")} }
        Row(Modifier.fillMaxWidth()){TextButton({scope.launch{show(service.pull());refresh()}},enabled=!busy){Text("Pull")};TextButton({scope.launch{show(service.fetch());refresh()}},enabled=!busy){Text("Fetch")};TextButton({scope.launch{show(service.diff())}},enabled=!busy){Text("Diff")}}
        if(confirmPush){AlertDialog(onDismissRequest={confirmPush=false},title={Text("Confirm Git Push")},text={Text("Push the current commit to the configured remote? This is a network-sensitive action.")},confirmButton={TextButton(onClick={confirmPush=false;scope.launch{busy=true;val paths=status?.changes?.map{it.path}.orEmpty();val a=service.add(paths);if(a is GitResult.Failure){result="Add failed: ${a.error}"}else{when(val c=service.commit(message)){is GitResult.Success->show(service.push(true));is GitResult.Failure->result="Commit failed: ${c.error}"};message=""};refresh();busy=false}}){Text("Push")}},dismissButton={TextButton(onClick={confirmPush=false}){Text("Cancel")}})}
    }
}

@Composable private fun FilesWindow(files: FileService){
    var currentPath by remember { mutableStateOf("") }
    var items by remember { mutableStateOf(files.listDirectory("").value.orEmpty()) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<ProjectFile?>(null) }
    fun refresh(path:String=currentPath){ items = files.listDirectory(path).value.orEmpty(); selected=null }
    val shown = if(query.isBlank()) items else files.search(query).value.orEmpty()
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth().height(34.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={currentPath=currentPath.substringBeforeLast('/',"");refresh() },enabled=currentPath.isNotEmpty(),modifier=Modifier.size(30.dp)){Icon(Icons.Default.ArrowBack,"Back")}
            Text("MyProject/${currentPath}".trimEnd('/'),Modifier.weight(1f),fontSize=11.sp)
            IconButton(onClick={refresh},modifier=Modifier.size(30.dp)){Icon(Icons.Default.Refresh,"Refresh")}
        }
        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),verticalAlignment=Alignment.CenterVertically){
            TextField(query,{query=it},Modifier.weight(1f),singleLine=true,placeholder={Text("Search files...",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF11131C),unfocusedContainerColor=Color(0xFF11131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            Spacer(Modifier.width(6.dp))
            TextButton(onClick={val name="new_file.txt";files.createFile(if(currentPath.isEmpty())name else "$currentPath/$name");refresh()}){Text("+ File",fontSize=10.sp)}
            TextButton(onClick={val name="new_folder";files.createFolder(if(currentPath.isEmpty())name else "$currentPath/$name");refresh()}){Text("+ Folder",fontSize=10.sp)}
        }
        if(selected!=null) Row(Modifier.fillMaxWidth().padding(vertical=3.dp),verticalAlignment=Alignment.CenterVertically){Text("Selected: ${selected!!.name}",Modifier.weight(1f),fontSize=10.sp,color=Color(0xFF9CA8C5));TextButton(onClick={files.delete(selected!!.path);refresh()}){Text("Delete",fontSize=10.sp)}}
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

@Composable private fun SettingsWindow(){Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)){Text("Appearance",fontSize=13.sp,fontWeight=FontWeight.Bold);Text("Dark / deep purple / neon blue",fontSize=11.sp,color=Color(0xFF9AA7C8),modifier=Modifier.padding(vertical=8.dp));Divider();Text("AI Profile",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("Name: Sara\nMode: Offline-ready\nVoice: replaceable adapter",fontSize=11.sp,color=Color(0xFF9AA7C8));Divider();Text("Security",fontSize=13.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=14.dp));Text("Sensitive actions require confirmation. Secrets are not stored in source code.",fontSize=11.sp,color=Color(0xFF9AA7C8))}}
@Composable private fun BrowserWindow(windowId: String){
    var address by remember(windowId){ mutableStateOf("https://www.google.com") }
    val context = LocalContext.current
    val webView = remember(windowId) { WebView(context).apply {
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { address = url }
        }
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadsImagesAutomatically = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        loadUrl(address)
    }}
    DisposableEffect(webView) { onDispose { webView.stopLoading(); webView.destroy() } }
    Column(Modifier.fillMaxSize().background(Color(0xFF080910))) {
        Row(Modifier.fillMaxWidth().height(42.dp).padding(horizontal=6.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton(onClick={if(webView.canGoBack())webView.goBack()},modifier=Modifier.size(32.dp)){Icon(Icons.Default.ArrowBack,"Back",Modifier.size(16.dp))}
            IconButton(onClick={if(webView.canGoForward())webView.goForward()},modifier=Modifier.size(32.dp)){Icon(Icons.Default.ArrowForward,"Forward",Modifier.size(16.dp))}
            IconButton(onClick={webView.reload()},modifier=Modifier.size(32.dp)){Icon(Icons.Default.Refresh,"Refresh",Modifier.size(16.dp))}
            TextField(address,{address=it},Modifier.weight(1f).height(38.dp),singleLine=true,placeholder={Text("Search or enter address",fontSize=10.sp)},colors=TextFieldDefaults.colors(focusedContainerColor=Color(0xFF11131C),unfocusedContainerColor=Color(0xFF11131C),focusedIndicatorColor=Color.Transparent,unfocusedIndicatorColor=Color.Transparent))
            TextButton(onClick={ manager.openNew(WindowType.BROWSER) }){Text("New",fontSize=10.sp)}
            TextButton(onClick={
                val target=address.trim()
                if(target.isNotBlank()){
                    val url=when {
                        target.startsWith("http://")||target.startsWith("https://") -> target
                        target.contains(" ") -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(target,"UTF-8")
                        else -> "https://" + target
                    }
                    webView.loadUrl(url)
                }
            }){Text("Go",fontSize=10.sp)}
        }
        AndroidView(factory={webView},modifier=Modifier.weight(1f).fillMaxWidth())
    }
}
