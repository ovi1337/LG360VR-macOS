package com.lg360vr.tester

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lg360vr.tester.display.DisplayInfo
import com.lg360vr.tester.display.DisplayMonitor
import com.lg360vr.tester.display.TestPattern
import com.lg360vr.tester.usb.Connection
import com.lg360vr.tester.usb.DeviceDescriptor
import com.lg360vr.tester.usb.LogLine
import com.lg360vr.tester.usb.LogSource
import com.lg360vr.tester.usb.Lg360Protocol
import com.lg360vr.tester.usb.UsbController

class MainActivity : ComponentActivity() {

    private lateinit var usb: UsbController
    private lateinit var displays: DisplayMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        usb = UsbController(applicationContext)
        displays = DisplayMonitor(applicationContext)
        usb.register()
        displays.register()

        setContent { App(usb, displays) }

        handleAttachIntent()
        // Try to connect on launch if the device is already present.
        usb.connect()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAttachIntent()
    }

    private fun handleAttachIntent() {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val dev: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            usb.connect(dev)
        }
    }

    override fun onDestroy() {
        displays.unregister()
        usb.unregister()
        super.onDestroy()
    }
}

private val Bg = Color(0xFF0B1021)
private val Surface = Color(0xFF161C33)
private val Accent = Color(0xFF5AC8FA)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(usb: UsbController, displays: DisplayMonitor) {
    val scheme = darkColorScheme(
        primary = Accent,
        background = Bg,
        surface = Surface,
        onBackground = Color(0xFFE6ECFF),
        onSurface = Color(0xFFE6ECFF),
    )
    MaterialTheme(colorScheme = scheme) {
        val tabs = listOf("Status", "Befehle", "Log", "Display", "USB")
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            containerColor = Bg,
            topBar = {
                TopAppBar(
                    title = { Text("LG 360 VR Tester", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Surface, titleContentColor = Color.White
                    )
                )
            }
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                TabRow(selectedTabIndex = tab, containerColor = Surface, contentColor = Accent) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = tab == i, onClick = { tab = i },
                            text = { Text(t, fontSize = 13.sp) })
                    }
                }
                Box(Modifier.fillMaxSize().padding(12.dp)) {
                    when (tab) {
                        0 -> StatusScreen(usb)
                        1 -> CommandsScreen(usb)
                        2 -> LogScreen(usb)
                        3 -> DisplayScreen(displays)
                        4 -> UsbInfoScreen(usb)
                    }
                }
            }
        }
    }
}

// ---- Status ---------------------------------------------------------------

@Composable
private fun StatusScreen(usb: UsbController) {
    val state by usb.state.collectAsStateWithLifecycle()
    val status by usb.status.collectAsStateWithLifecycle()
    val desc by usb.descriptor.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        val (dot, label) = when (state) {
            Connection.CONNECTED -> Color(0xFF35D07F) to "Verbunden"
            Connection.PERMISSION_PENDING -> Color(0xFFFFD23F) to "Berechtigung ausstehend"
            Connection.NO_DEVICE -> Color(0xFFFF6B6B) to "Kein Gerät"
            Connection.ERROR -> Color(0xFFFF6B6B) to "Fehler"
            Connection.DISCONNECTED -> Color(0xFF8892B0) to "Getrennt"
        }
        Card(Surface) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).background(dot, RoundedCornerShape(7.dp)))
                Spacer(Modifier.width(10.dp))
                Text(label, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(status, color = Color(0xFFAAB4D4), fontSize = 13.sp)
        }

        desc?.let { d ->
            Card(Surface) {
                Text(d.product ?: "LG 360 VR", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.height(4.dp))
                InfoRow("VID / PID", "${d.vidHex} / ${d.pidHex}")
                InfoRow("Hersteller", d.manufacturer ?: "–")
                InfoRow("Seriennr.", d.serial ?: "–")
                InfoRow("USB-Version", d.version)
                InfoRow("Interfaces", d.interfaces.size.toString())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { usb.connect() }, modifier = Modifier.weight(1f)) {
                Text(if (state == Connection.CONNECTED) "Neu verbinden" else "Verbinden")
            }
            OutlinedButton(onClick = { usb.close() }, modifier = Modifier.weight(1f)) {
                Text("Trennen")
            }
        }
        Button(
            onClick = { usb.activate() },
            enabled = state == Connection.CONNECTED,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.Black)
        ) { Text("Aktivieren  (Sleep Disable + VR App Start)", fontWeight = FontWeight.Bold) }

        Text(
            "Hinweis: Nach dem Aktivieren wartet die Brille auf ein DP-Alt-Mode-Videosignal. " +
            "Erscheint sie im Tab \"Display\" als Präsentations-Display, kann ein Testbild ausgegeben werden.",
            color = Color(0xFF8892B0), fontSize = 12.sp
        )
    }
}

// ---- Commands -------------------------------------------------------------

@Composable
private fun CommandsScreen(usb: UsbController) {
    val state by usb.state.collectAsStateWithLifecycle()
    val enabled = state == Connection.CONNECTED
    var custom by rememberSaveable { mutableStateOf("") }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Surface) {
                Text("Eigenes Kommando", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom, onValueChange = { custom = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("z.B. Get Swversion") }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { if (custom.isNotBlank()) usb.send(custom.trim()) },
                    enabled = enabled && custom.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Senden") }
            }
        }
        Lg360Protocol.COMMAND_GROUPS.forEach { (group, cmds) ->
            item {
                Card(Surface) {
                    Text(group, fontWeight = FontWeight.Bold, color = Accent)
                    Spacer(Modifier.height(8.dp))
                    FlowButtons(cmds, enabled) { usb.send(it) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowButtons(cmds: List<String>, enabled: Boolean, onClick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cmds.forEach { c ->
            ElevatedButton(onClick = { onClick(c) }, enabled = enabled) {
                Text(c, fontSize = 12.sp)
            }
        }
    }
}

// ---- Log ------------------------------------------------------------------

@Composable
private fun LogScreen(usb: UsbController) {
    val log by usb.log.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${log.size} Zeilen", color = Color(0xFF8892B0), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { usb.clearLog() }) { Text("Leeren") }
        }
        LazyColumn(
            Modifier.fillMaxSize().background(Color(0xFF090D1C), RoundedCornerShape(8.dp))
                .padding(8.dp),
            reverseLayout = true
        ) {
            items(log.asReversed()) { line -> LogRow(line) }
        }
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val color = when (line.source) {
        LogSource.SENT -> Color(0xFF5AC8FA)
        LogSource.RECV -> Color(0xFF9BE86B)
        LogSource.INFO -> Color(0xFFB0B8D8)
        LogSource.ERROR -> Color(0xFFFF6B6B)
    }
    val tag = when (line.source) {
        LogSource.SENT -> "TX"; LogSource.RECV -> "RX"
        LogSource.INFO -> "··"; LogSource.ERROR -> "!!"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(line.time, color = Color(0xFF5A6488), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Spacer(Modifier.width(6.dp))
        Text(tag, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(line.text, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    }
}

// ---- Display --------------------------------------------------------------

@Composable
private fun DisplayScreen(displays: DisplayMonitor) {
    val list by displays.displays.collectAsStateWithLifecycle()
    val active by displays.activeDisplayId.collectAsStateWithLifecycle()
    var pattern by rememberSaveable { mutableStateOf(TestPattern.BARS) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {

        Row {
            Text("Erkannte Displays", fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { displays.refresh() }) { Text("Aktualisieren") }
        }

        if (list.none { it.isPresentation }) {
            Card(Surface) {
                Text("Kein externes Display erkannt.", color = Color(0xFFFFD23F),
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Die Brille erscheint hier erst, wenn sie erfolgreich DP-Alt-Mode betritt. " +
                    "Falls sie nur bootet/blinkt, kommt kein Videopfad zustande.",
                    color = Color(0xFF8892B0), fontSize = 12.sp
                )
            }
        }

        list.forEach { d -> DisplayCard(d, d.id == active) }

        Card(Surface) {
            Text("Testbild", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            PatternPicker(pattern) { pattern = it }
            Spacer(Modifier.height(10.dp))
            val external = list.firstOrNull { it.isPresentation }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { external?.let { displays.showPattern(it.id, pattern) } },
                    enabled = external != null, modifier = Modifier.weight(1f)
                ) { Text("Anzeigen") }
                OutlinedButton(onClick = { displays.dismiss() }, modifier = Modifier.weight(1f)) {
                    Text("Ausblenden")
                }
            }
        }
    }
}

@Composable
private fun DisplayCard(d: DisplayInfo, isActive: Boolean) {
    Card(if (isActive) Color(0xFF1E3A2F) else Surface) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(d.name, fontWeight = FontWeight.Bold)
                Text("${d.width}×${d.height} @ ${"%.0f".format(d.refreshRate)}Hz  •  id=${d.id}",
                    color = Color(0xFFAAB4D4), fontSize = 12.sp)
            }
            if (d.isPresentation) {
                Text("EXTERN", color = Color(0xFF35D07F), fontWeight = FontWeight.Bold, fontSize = 11.sp)
            } else {
                Text("intern", color = Color(0xFF5A6488), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun PatternPicker(selected: TestPattern, onSelect: (TestPattern) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TestPattern.values().forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected == p, onClick = { onSelect(p) })
                Text(when (p) {
                    TestPattern.BARS -> "Farbbalken (SMPTE)"
                    TestPattern.GRID -> "Gitter"
                    TestPattern.CROSSHAIR -> "Fadenkreuz"
                    TestPattern.SOLID_WHITE -> "Weiß (Backlight-Test)"
                })
            }
        }
    }
}

// ---- USB Info -------------------------------------------------------------

@Composable
private fun UsbInfoScreen(usb: UsbController) {
    val desc by usb.descriptor.collectAsStateWithLifecycle()

    if (desc == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Nicht verbunden – keine Deskriptoren.", color = Color(0xFF8892B0))
        }
        return
    }
    val d = desc!!
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Surface) {
                Text("Gerät", fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.height(6.dp))
                InfoRow("Name", d.deviceName)
                InfoRow("Produkt", d.product ?: "–")
                InfoRow("Hersteller", d.manufacturer ?: "–")
                InfoRow("VID / PID", "${d.vidHex} / ${d.pidHex}")
                InfoRow("Klasse", "${d.deviceClassName} (0x%02X)".format(d.usbClass))
                InfoRow("USB-Version", d.version)
            }
        }
        items(d.interfaces) { itf ->
            Card(Surface) {
                Text("Interface ${itf.id} — ${itf.className}",
                    fontWeight = FontWeight.Bold, color = Accent)
                InfoRow("Alt-Setting", itf.alternateSetting.toString())
                Spacer(Modifier.height(6.dp))
                itf.endpoints.forEach { ep ->
                    Text("• EP ${ep.addrHex}  ${ep.direction}  ${ep.type}  " +
                        "max=${ep.maxPacketSize}B  intv=${ep.interval}",
                        fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                        color = Color(0xFFB0B8D8))
                }
            }
        }
    }
}

// ---- shared ---------------------------------------------------------------

@Composable
private fun Card(bg: Color, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = bg, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), content = content)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = Color(0xFF8892B0), fontSize = 13.sp, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
