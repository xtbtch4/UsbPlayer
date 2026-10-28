package com.example.usbplayer

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import androidx.compose.ui.text.font.FontFamily
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private lateinit var playlists: Playlists

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracks = Library.load(this)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { playOne(it) } }
    private val importEq = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
            val name = it.lastPathSegment?.substringAfterLast(&#x27;/&#x27;)?.removeSuffix(".txt") ?: "AutoEQ"
            if (Eq.load(text, name)) AppState.reapply?.invoke() else AppState.message = "Не удалось разобрать профиль AutoEQ"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlists = Playlists(this)
        val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        perms.launch(arrayOf(audioPerm, Manifest.permission.POST_NOTIFICATIONS))

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        f.addListener({ controller = f.get() }, ContextCompat.getMainExecutor(this))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFD0BCFF), background = Color(0xFF0F0D13), surface = Color(0xFF0F0D13),
                surfaceContainer = Color(0xFF1A1721), onSurfaceVariant = Color(0xFFB8B2C4))
            ) { Surface(color = MaterialTheme.colorScheme.background) { Screen() } }
        }
    }

    @Composable
    fun Pill(text: String, ok: Boolean? = null, onClick: (() -> Unit)? = null) {
        val bg = when (ok) { true -> Color(0xFF1F4D36); false -> Color(0xFF4D2A2A); null -> Color(0xFF2A2633) }
        Text(text, Modifier.clip(RoundedCornerShape(50)).background(bg)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }

    private fun fmt(ms: Long): String { val t = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(t / 60, t % 60) }

    @Composable
    fun TrackRow(t: Track, action: String?, onAction: () -> Unit, onPlay: () -> Unit) {
        ListItem(
            leadingContent = { Cover(t, 48.dp) },
            headlineContent = { Text((if (t.trackNo > 0) "${t.trackNo % 1000}. " else "") + t.title, maxLines = 1) },
            supportingContent = {
                Text(listOfNotNull(t.artist, t.album.ifEmpty { null }, t.genre.ifEmpty { null },
                    t.year.takeIf { it > 0 }?.toString()).joinToString(" · "), maxLines = 1)
            },
            trailingContent = action?.let { a -> @Composable { TextButton(onAction) { Text(a) } } },
            modifier = Modifier.clickable(onClick = onPlay))
    }

    @Composable
    fun Screen() {
        val c = controller
        val scope = rememberCoroutineScope()
        var playing by remember { mutableStateOf(false) }
        var pos by remember { mutableLongStateOf(0L) }
        var dur by remember { mutableLongStateOf(1L) }
        var title by remember { mutableStateOf("") }
        var curId by remember { mutableStateOf<String?>(null) }
        var expanded by remember { mutableStateOf(false) }
        var usbReport by remember { mutableStateOf<String?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var filter by remember { mutableStateOf("") }
        var openAlbum by remember { mutableStateOf<String?>(null) }
        var openPl by remember { mutableStateOf<String?>(null) }
        var newPl by remember { mutableStateOf("") }
        var plVersion by remember { mutableIntStateOf(0) }
        var addTrack by remember { mutableStateOf<Track?>(null) }
        var loggedIn by remember { mutableStateOf(Providers.qobuz.token != null) }
        var email by remember { mutableStateOf("") }
        var pass by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var remote by remember { mutableStateOf<List<Track>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(c) {
            while (true) {
                c?.let { playing = it.isPlaying; pos = it.currentPosition
                    dur = it.duration.coerceAtLeast(1L); title = it.mediaMetadata.title?.toString() ?: ""
                    curId = it.currentMediaItem?.mediaId }
                delay(500)
            }
        }
        val plNames = remember(plVersion) { playlists.names() }
        val cur = (tracks + remote).find { "${it.provider}:${it.id}" == curId }
        val accent by produceState(Color(0xFF3B2F5C), cur?.uri) {
            value = cur?.let { t -> withContext(Dispatchers.IO) { Art.load(this@MainActivity, t, 96)?.let(::dominant) } }
                ?: Color(0xFF3B2F5C)
        }
        BackHandler(enabled = expanded) { expanded = false }
        val albums = remember(tracks) { tracks.groupBy { it.albumKey() }.toList().sortedBy { it.first } }

        addTrack?.let { t ->
            AlertDialog(onDismissRequest = { addTrack = null }, confirmButton = {},
                title = { Text("В плейлист") },
                text = {
                    Column {
                        if (plNames.isEmpty()) Text("Сначала создайте плейлист во вкладке «Плейлисты»")
                        plNames.forEach { n -> TextButton({ playlists.add(n, t.id); plVersion++; addTrack = null }) { Text(n) } }
                    }
                })
        }

        usbReport?.let { r ->
            AlertDialog(onDismissRequest = { usbReport = null },
                confirmButton = { TextButton({ copyText(r); usbReport = null }) { Text("Копировать") } },
                dismissButton = { TextButton({ usbReport = null }) { Text("Закрыть") } },
                title = { Text("USB-ЦАП: дескрипторы") },
                text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(r, fontFamily = FontFamily.Monospace, fontSize = 10.sp) } })
        }
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (title.isNotEmpty()) Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { expanded = true }) {
                        LinearProgressIndicator(progress = { pos.toFloat() / dur }, Modifier.fillMaxWidth().height(2.dp))
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(cur, 44.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                                Text(cur?.artist ?: AppState.formatInfo, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton({ if (playing) c?.pause() else c?.play() }) { Text(if (playing) "❚❚" else "▶", fontSize = 20.sp) }
                        }
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        listOf("♫" to "Треки", "▣" to "Альбомы", "☰" to "Плейлисты", "Q" to "Qobuz", "≋" to "EQ").forEachIndexed { i, (ic, n) ->
                            NavigationBarItem(tab == i, { tab = i; openAlbum = null; openPl = null },
                                icon = { Text(ic, fontSize = 20.sp) }, label = { Text(n, maxLines = 1, fontSize = 10.sp) })
                        }
                    }
                }
            }
        ) { pad ->
          Column(Modifier.padding(pad).fillMaxSize()) {
            // шапка: ЦАП и состояние тракта
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(AppState.dacName ?: "Нет USB DAC", AppState.dacName != null) { probeUsb { usbReport = it } }
                    if (AppState.formatInfo.isNotEmpty()) Pill(AppState.formatInfo)
                    Pill(if (AppState.bitPerfectActive) "Bit-perfect" else if (AppState.mixerMatched) "Микшер · без SRC" else "Системный микшер", AppState.bitPerfectActive)
                    if (Eq.active) Pill("EQ: ${Eq.name ?: "вкл"}")
                }
                if (!AppState.bitPerfectActive && AppState.bpInfo.isNotEmpty())
                    Text(AppState.bpInfo, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                AppState.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (Eq.active) "Bit-perfect (выкл. из-за EQ)" else "Bit-perfect", Modifier.weight(1f))
                    Switch(AppState.bitPerfectWanted, { AppState.bitPerfectWanted = it; AppState.reapply?.invoke() })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(filter, { filter = it }, label = { Text("Поиск по тегам") },
                                singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button({ openFile.launch(arrayOf("*/*")) }) { Text("Файл") }
                        }
                        val list = tracks.filter {
                            filter.isBlank() || listOf(it.title, it.artist, it.album, it.genre).any { s -> s.contains(filter, true) }
                        }
                        LazyColumn { itemsIndexed(list) { i, t -> TrackRow(t, "＋", { addTrack = t }) { play(list, i) } } }
                    }
                    1 -> if (openAlbum == null) {
                        LazyColumn {
                            items(albums) { (key, ts) ->
                                val (alb, art) = key.split(&#x27;\u0000&#x27;)
                                ListItem(
                                    leadingContent = { Cover(ts.first(), 56.dp) },
                                    headlineContent = { Text(alb, maxLines = 1) },
                                    supportingContent = { Text("$art · ${ts.size} тр." + (ts.first().year.takeIf { it > 0 }?.let { " · $it" } ?: ""), maxLines = 1) },
                                    modifier = Modifier.clickable { openAlbum = key })
                            }
                        }
                    } else Column {
                        TextButton({ openAlbum = null }) { Text("← Альбомы") }
                        val list = tracks.filter { it.albumKey() == openAlbum }.sortedBy { it.trackNo }
                        LazyColumn { itemsIndexed(list) { i, t -> TrackRow(t, "＋", { addTrack = t }) { play(list, i) } } }
                    }
                    2 -> if (openPl == null) Column {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(newPl, { newPl = it }, label = { Text("Новый плейлист") }, singleLine = true, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Button({ playlists.create(newPl); newPl = ""; plVersion++ }) { Text("Создать") }
                        }
                        LazyColumn {
                            items(plNames) { n ->
                                ListItem(
                                    headlineContent = { Text(n) },
                                    supportingContent = { Text("${playlists.get(n).size} тр.") },
                                    trailingContent = { TextButton({ playlists.delete(n); plVersion++ }) { Text("Удалить") } },
                                    modifier = Modifier.clickable { openPl = n })
                            }
                        }
                    } else Column {
                        TextButton({ openPl = null }) { Text("← Плейлисты") }
                        val name = openPl!!
                        val list = playlists.get(name).mapNotNull { id -> tracks.find { it.id == id } }
                        LazyColumn { itemsIndexed(list) { i, t -> TrackRow(t, "✕", { playlists.remove(name, t.id); plVersion++ }) { play(list, i) } } }
                    }
                    3 -> Column {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                !Providers.qobuz.configured -> Text("Впишите app_id и app_secret от Qobuz в Credentials.kt")
                                !loggedIn -> {
                                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                    OutlinedTextField(pass, { pass = it }, label = { Text("Пароль") }, singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                    Button({ scope.launch {
                                        error = null
                                        runCatching { withContext(Dispatchers.IO) { Providers.qobuz.login(email, pass) } }
                                            .onSuccess { loggedIn = it; if (!it) error = "Не удалось войти" }
                                            .onFailure { error = it.message }
                                    } }) { Text("Войти") }
                                }
                                else -> Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(query, { query = it }, label = { Text("Поиск треков") }, singleLine = true, modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    Button({ scope.launch {
                                        error = null
                                        runCatching { withContext(Dispatchers.IO) { Providers.qobuz.search(query) } }
                                            .onSuccess { remote = it }.onFailure { error = it.message }
                                    } }) { Text("Найти") }
                                }
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        LazyColumn { itemsIndexed(remote) { i, t -> TrackRow(t, null, {}) { play(remote, i) } } }
                    }
                    else -> Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Эквалайзер (AutoEQ)", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Switch(Eq.enabled, { Eq.enabled = it; AppState.reapply?.invoke() ?: Eq.apply() })
                        }
                        Text(Eq.name ?: "Профиль не загружен")
                        Text("Preamp ${Eq.preamp} dB · фильтров: ${Eq.filters.size}", style = MaterialTheme.typography.bodySmall)
                        Button({ importEq.launch(arrayOf("*/*")) }) { Text("Импорт ParametricEQ.txt") }
                        Text("EQ работает в обычном режиме: при его включении bit-perfect отключается. На DSD EQ не применяется.",
                            style = MaterialTheme.typography.bodySmall)
                        Eq.filters.forEach { Text("${it.type}  ${it.fc.toInt()} Hz  ${it.gain} dB  Q ${it.q}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

          }
        }
        AnimatedVisibility(expanded, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            val dark = Color(accent.red * 0.55f, accent.green * 0.55f, accent.blue * 0.55f)
            val side = LocalConfiguration.current.screenWidthDp.dp - 56.dp
            Column(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(dark, Color(0xFF0F0D13))))
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton({ expanded = false }, Modifier.align(Alignment.Start)) { Text("⌄  Свернуть") }
                Spacer(Modifier.height(8.dp))
                Cover(cur, side)
                Spacer(Modifier.height(24.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center)
                Text(listOfNotNull(cur?.artist, cur?.album?.ifEmpty { null }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xCCFFFFFF), maxLines = 1)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (AppState.formatInfo.isNotEmpty()) Pill(AppState.formatInfo)
                    Pill(if (AppState.bitPerfectActive) "Bit-perfect" else "Микшер", AppState.bitPerfectActive)
                }
                Spacer(Modifier.height(16.dp))
                Slider(pos.toFloat(), { c?.seekTo(it.toLong()) }, valueRange = 0f..dur.toFloat())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(pos), style = MaterialTheme.typography.labelMedium)
                    Text(fmt(dur), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ c?.seekToPreviousMediaItem() }) { Text("⏮", fontSize = 32.sp) }
                    Box(Modifier.size(76.dp).clip(CircleShape).background(Color.White)
                        .clickable { if (playing) c?.pause() else c?.play() }, contentAlignment = Alignment.Center) {
                        Text(if (playing) "❚❚" else "▶", color = Color.Black, fontSize = 26.sp)
                    }
                    TextButton({ c?.seekToNextMediaItem() }) { Text("⏭", fontSize = 32.sp) }
                }
            }
        }
        }
    }

    private fun copyText(t: String) =
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("usb", t))

    /** Запрашивает доступ к USB-ЦАПу и отдаёт отчёт по его дескрипторам. */
    private fun probeUsb(done: (String) -> Unit) {
        val um = getSystemService(UsbManager::class.java)
        val dev = um.deviceList.values.firstOrNull { d ->
            (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
        } ?: return done("USB-аудиоустройство не найдено")
        fun report() {
            val conn = um.openDevice(dev) ?: return done("Не удалось открыть устройство")
            val raw = conn.rawDescriptors
            conn.close()
            done(UsbReport.build(dev, raw))
        }
        if (um.hasPermission(dev)) return report()
        val action = "$packageName.USB_PERMISSION"
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                unregisterReceiver(this)
                if (um.hasPermission(dev)) report() else done("Нет разрешения на доступ к USB")
            }
        }
        ContextCompat.registerReceiver(this, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
    }

    private fun item(t: Track) = MediaItem.Builder().setMediaId("${t.provider}:${t.id}")
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(t.uri).build())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setArtworkUri(when {
                t.provider != "local" -> t.art.takeIf { it.isNotEmpty() }?.let(Uri::parse)
                t.albumId > 0 -> android.content.ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), t.albumId)
                else -> null
            }).build())
        .build()

    private fun play(list: List<Track>, index: Int) {
        val c = controller ?: return
        AppState.message = null
        c.setMediaItems(list.map(::item), index, 0L); c.prepare(); c.play()
    }

    /** Одиночный файл из проводника (в т.ч. DSF/DFF, которые MediaStore может не индексировать). */
    private fun playOne(uri: Uri) = play(listOf(Track(0, uri.lastPathSegment?.substringAfterLast(&#x27;/&#x27;) ?: "Файл", "", "", 0, uri)), 0)
}
