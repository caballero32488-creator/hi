package com.tinymacro

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Accent = Color(0xFF7C5CFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b); Cfg.init(this); Store.init(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Color(0xFF0E0F14), surface = Color(0xFF171923), onBackground = Color.White, onSurface = Color.White)) { App() } }
    }
}

@Composable fun Card2(mod: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(mod.fillMaxWidth().padding(vertical = 5.dp).background(Color(0xFF1B1E2B), RoundedCornerShape(20.dp)).padding(16.dp), content = content)

@Composable fun App() {
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("🏠 Home", "⏺ Recorder", "📁 Macros", "⚙ Settings")
    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        NavigationBar(containerColor = Color(0xFF12141C)) { tabs.forEachIndexed { i, t ->
            NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(t.take(2)) }, label = { Text(t.drop(3), maxLines = 1) }) } }
    }) { pad -> Box(Modifier.padding(pad).padding(16.dp)) { when (tab) { 0 -> Home(); 1 -> Recorder(); 2 -> Library { tab = 1 }; else -> SettingsScreen() } } }
}

@Composable fun Home() {
    val c = androidx.compose.ui.platform.LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val overlayOk = Settings.canDrawOverlays(c); val svcOk = MacroService.instance != null
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    Column {
        Text("TinyMacro", style = MaterialTheme.typography.headlineLarge); Text("Record taps and swipes. Replay them anywhere.", color = Color.Gray)
        Spacer(Modifier.height(12.dp))
        Card2 { Text(if (overlayOk) "✅ Overlay permission granted" else "❌ Overlay permission needed")
            if (!overlayOk) Button({ c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))) }) { Text("Grant") } }
        Card2 { Text(if (svcOk) "✅ Gesture service running" else "❌ Enable TinyMacro in Accessibility settings (needed for playback)")
            if (!svcOk) Button({ c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility") } }
        Card2 { Text("State: ${Hub.state}" + if (Hub.progress >= 0) "  (step ${Hub.progress + 1})" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ OverlayService.start(c) }, enabled = overlayOk) { Text("Show overlay") }
                OutlinedButton({ OverlayService.stop(c) }) { Text("Hide") } } }
        Text("Android restricts input injection on secure screens and some protected apps; macros can't run there.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun Recorder() {
    val c = androidx.compose.ui.platform.LocalContext.current
    var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var folder by remember { mutableStateOf("") }
    var edit by remember { mutableStateOf<Int?>(null) }
    Column {
        Text("Recorded actions (${Hub.actions.size})", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ Player.play(c, Hub.actions.toList(), Hub.recW, Hub.recH) }) { Text("▶ Play") }
            OutlinedButton({ Player.togglePause() }) { Text("⏸/▶") }; OutlinedButton({ Player.stop() }) { Text("■") }
            OutlinedButton({ Hub.actions.add(Action("wait", duration = 500)) }) { Text("+Wait") } }
        LazyColumn(Modifier.weight(1f)) { itemsIndexed(Hub.actions) { i, a ->
            Card2 { Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${a.label()}", Modifier.weight(1f), color = if (Hub.progress == i) Accent else Color.White)
                TextButton({ if (i > 0) { val t = Hub.actions.removeAt(i); Hub.actions.add(i - 1, t) } }) { Text("▲") }
                TextButton({ if (i < Hub.actions.lastIndex) { val t = Hub.actions.removeAt(i); Hub.actions.add(i + 1, t) } }) { Text("▼") }
                TextButton({ edit = i }) { Text("✎") }; TextButton({ Hub.actions.removeAt(i) }) { Text("✕") } } } } }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(desc, { desc = it }, label = { Text("Description") }, modifier = Modifier.weight(1f), singleLine = true)
            OutlinedTextField(folder, { folder = it }, label = { Text("Folder") }, modifier = Modifier.weight(1f), singleLine = true) }
        Button({ if (Hub.actions.isNotEmpty()) { Store.save(Macro(name = name.ifBlank { "Untitled" }, description = desc, folder = folder, width = Hub.recW, height = Hub.recH, actions = Hub.actions.map { it.copy() })); name = "" } },
            Modifier.fillMaxWidth()) { Text("Save macro") }
    }
    edit?.let { i -> val a = Hub.actions[i]
        var x by remember { mutableStateOf(a.x.toString()) }; var y by remember { mutableStateOf(a.y.toString()) }; var d by remember { mutableStateOf(a.duration.toString()) }
        AlertDialog(onDismissRequest = { edit = null }, title = { Text("Edit ${a.type}") },
            text = { Column { if (a.type != "wait") { OutlinedTextField(x, { x = it }, label = { Text("X") }); OutlinedTextField(y, { y = it }, label = { Text("Y") }) }
                OutlinedTextField(d, { d = it }, label = { Text("Duration ms") }) } },
            confirmButton = { TextButton({ Hub.actions[i] = a.copy(x = x.toIntOrNull() ?: a.x, y = y.toIntOrNull() ?: a.y, duration = d.toLongOrNull() ?: a.duration); edit = null }) { Text("Save") } }) }
}

@Composable fun Library(toRecorder: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    var rename by remember { mutableStateOf<Macro?>(null) }; var newName by remember { mutableStateOf("") }
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("My Macros", style = MaterialTheme.typography.titleLarge)
            OutlinedButton({ val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty(); android.widget.Toast.makeText(c, if (Store.importJson(t)) "Imported" else "Clipboard has no valid macro JSON", android.widget.Toast.LENGTH_SHORT).show() }) { Text("Import (clipboard)") } }
        LazyColumn { Store.macros.groupBy { it.folder.ifBlank { "Unsorted" } }.forEach { (folder, list) ->
            item { Text("📂 $folder", Modifier.padding(top = 12.dp), color = Color.Gray) }
            list.forEach { m -> item(m.id) { Card2 {
                Text(m.name, style = MaterialTheme.typography.titleMedium); Text("${m.actions.size} actions · ${m.description}", color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton({ Player.play(c, m.actions, m.width, m.height) }) { Text("▶") }
                    TextButton({ Hub.actions.clear(); Hub.actions.addAll(m.actions.map { it.copy() }); Hub.recW = m.width; Hub.recH = m.height; toRecorder() }) { Text("Edit") }
                    TextButton({ rename = m; newName = m.name }) { Text("Rename") }
                    TextButton({ Store.duplicate(m) }) { Text("Copy") }
                    TextButton({ c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_TEXT, m.toJson().toString(2)), "Share macro")) }) { Text("Share") }
                    TextButton({ Store.delete(m) }) { Text("🗑") } } } } } } }
    }
    rename?.let { m -> AlertDialog(onDismissRequest = { rename = null }, title = { Text("Rename") },
        text = { OutlinedTextField(newName, { newName = it }) },
        confirmButton = { TextButton({ Store.save(m.copy(name = newName)); rename = null }) { Text("OK") } }) }
}

@Composable fun SettingsScreen() {
        @Composable fun s(label: String, v: Float, r: ClosedFloatingPointRange<Float>, set: (Float) -> Unit, fmt: String = "%.2f") {
        Text("$label: ${fmt.format(v)}"); Slider(v, { set(it); Cfg.save() }, valueRange = r) }
        @Composable fun sw(label: String, v: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(v, { set(it); Cfg.save() }) }
    LazyColumn { item { Text("Settings", style = MaterialTheme.typography.titleLarge)
        Card2 { Text("Recording", color = Accent)
            s("Min. gap kept as Wait (ms, lower = higher fidelity)", Cfg.minGap, 0f..500f, { Cfg.minGap = it }, "%.0f")
            sw("Touch visualization", Cfg.touchViz) { Cfg.touchViz = it }; sw("Auto-save recordings", Cfg.autoSave) { Cfg.autoSave = it } }
        Card2 { Text("Playback", color = Accent)
            s("Speed ×", Cfg.speed, 0.25f..4f, { Cfg.speed = it }); s("Default action delay (ms)", Cfg.defaultDelay, 0f..2000f, { Cfg.defaultDelay = it }, "%.0f")
            s("Loop count", Cfg.loops, 1f..100f, { Cfg.loops = it.toInt().toFloat() }, "%.0f"); sw("Infinite loop", Cfg.infinite) { Cfg.infinite = it }
            sw("Scale coordinates to this screen (handles resolution/orientation changes)", Cfg.scaling) { Cfg.scaling = it } }
        Card2 { Text("Overlay (restart overlay to apply)", color = Accent)
            s("Opacity", Cfg.opacity, 0.3f..1f, { Cfg.opacity = it }); s("Size ×", Cfg.overlaySize, 0.7f..1.6f, { Cfg.overlaySize = it }) } } }
}
