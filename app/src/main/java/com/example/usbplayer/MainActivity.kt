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
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.ui.text.font.FontFamily
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.LazyRow
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var tracks by mutableStateOf<List<Track>>(emptyList())
    private var openPlayer by mutableStateOf(false)
    /** Очередь, запущенная пользователем: по ней ищем играющий трек (обложка, исполнитель), даже если открыт другой альбом. */
    private var queue by mutableStateOf<List<Track>>(emptyList())
    private lateinit var playlists: Playlists

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tracks = Library.load(this)
    }
    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { playOne(it) } }
    private val importEq = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = contentResolver.openInputStream(it)?.bufferedReader()?.use { r -> r.readText() }.orEmpty()
            val name = it.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".txt") ?: "AutoEQ"
            if (Eq.load(text, name)) AppState.reapply?.invoke() else AppState.message = "Не удалось разобрать профиль AutoEQ"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("open_player", false)) openPlayer = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        playlists = Playlists(this)
        Providers.qobuz.load(this)
        AppState.load(this)
        openPlayer = intent?.getBooleanExtra("open_player", false) == true
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

    private val homeSections = listOf(
        "Новинки" to "new-releases", "Самые прослушиваемые" to "most-streamed", "Награды прессы" to "press-awards",
        "Qobuzissime" to "qobuzissims", "Альбом недели" to "album-of-the-week",
        "Идеальная дискография" to "ideal-discography", "Бестселлеры" to "best-sellers")

    @Composable
    fun AlbumRow(title: String, albums: List<QAlbum>, onOpen: (QAlbum) -> Unit) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(title, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albums) { a ->
                    Column(Modifier.width(140.dp).clickable { onOpen(a) }) {
                        Cover(a.asTrack(), 140.dp)
                        Text(a.title, Modifier.padding(top = 6.dp), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(a.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (a.quality.isNotEmpty()) Text(a.quality, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
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
        var directOn by remember { mutableStateOf(UsbDirect.ready) }
        var directInfo by remember { mutableStateOf("") }
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
        var qAppId by remember { mutableStateOf(Providers.qobuz.appId) }
        var qSecret by remember { mutableStateOf(Providers.qobuz.appSecret) }
        var qToken by remember { mutableStateOf("") }
        var remote by remember { mutableStateOf<List<Track>>(emptyList()) }
        var error by remember { mutableStateOf<String?>(null) }
        var remoteAlbums by remember { mutableStateOf<List<QAlbum>>(emptyList()) }
        var home by remember { mutableStateOf<List<Pair<String, List<QAlbum>>>>(emptyList()) }
        var homeLoading by remember { mutableStateOf(false) }
        var qAlbum by remember { mutableStateOf<QAlbum?>(null) }
        var qTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
        var qArtistName by remember { mutableStateOf<String?>(null) }
        var qArtistAlbums by remember { mutableStateOf<List<QAlbum>>(emptyList()) }
        var qArtistLoading by remember { mutableStateOf(false) }
        var artistFilter by remember { mutableStateOf<String?>(null) }
        val loadHome: () -> Unit = {
            scope.launch {
                homeLoading = true; error = null
                val r = withContext(Dispatchers.IO) {
                    coroutineScope {
                        homeSections.map { (t, type) -> async { t to runCatching { Providers.qobuz.featured(type) } } }.awaitAll()
                    }
                }
                home = r.mapNotNull { (t, res) -> res.getOrNull()?.takeIf { it.isNotEmpty() }?.let { t to it } }
                if (home.isEmpty()) error = r.firstNotNullOfOrNull { it.second.exceptionOrNull()?.message } ?: "Главная пуста"
                homeLoading = false
            }
        }
        val openQ: (QAlbum) -> Unit = { a ->
            qAlbum = a; qTracks = emptyList()
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { Providers.qobuz.albumTracks(a.id) } }
                    .onSuccess { qTracks = it }.onFailure { error = it.message }
            }
        }
        /** Нажатие на исполнителя: у Qobuz открывается его страница с релизами, у локальных треков — его альбомы в библиотеке. */
        val openArtist: (Track) -> Unit = { t ->
            expanded = false
            if (t.provider == "qobuz" && t.artistId > 0) {
                tab = 3; qAlbum = null; qArtistName = t.artist; qArtistAlbums = emptyList(); qArtistLoading = true; error = null
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { Providers.qobuz.artistAlbums(t.artistId, t.artist) } }
                        .onSuccess { qArtistAlbums = it }.onFailure { error = it.message }
                    qArtistLoading = false
                }
            } else { tab = 1; openAlbum = null; artistFilter = t.artist }
        }
        LaunchedEffect(loggedIn) { if (loggedIn && home.isEmpty()) loadHome() }
        BackHandler(enabled = qAlbum != null) { qAlbum = null }
        BackHandler(enabled = qAlbum == null && qArtistName != null) { qArtistName = null }
        LaunchedEffect(openPlayer) { if (openPlayer) { expanded = true; openPlayer = false } }

        LaunchedEffect(c) {
            while (true) {
                c?.let { playing = it.isPlaying; pos = it.currentPosition
                    dur = it.duration.coerceAtLeast(1L); title = it.mediaMetadata.title?.toString() ?: ""
                    curId = it.currentMediaItem?.mediaId }
                directInfo = UsbDirect.info()
                delay(500)
            }
        }
        val plNames = remember(plVersion) { playlists.names() }
        val cur = (queue + tracks + remote + qTracks).find { "${it.provider}:${it.id}" == curId }
        val accent by produceState(Color(0xFF3B2F5C), cur?.uri) {
            value = cur?.let { t -> withContext(Dispatchers.IO) { Art.load(this@MainActivity, t, 96)?.let(::dominant) } }
                ?: Color(0xFF3B2F5C)
        }
        BackHandler(enabled = expanded) { expanded = false }
        val albums = remember(tracks) { tracks.groupBy { it.albumKey() }.toList().sortedBy { it.first } }
        val shownAlbums = if (artistFilter == null) albums else albums.filter { (_, ts) ->
            ts.any { t -> t.artist.equals(artistFilter, true) || t.albumArtist.equals(artistFilter, true) }
        }

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
                            NavigationBarItem(tab == i, { tab = i; openAlbum = null; openPl = null; artistFilter = null },
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
                    Switch(AppState.bitPerfectWanted, { AppState.bitPerfectWanted = it; AppState.saveBitPerfect(this@MainActivity); AppState.reapply?.invoke() })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Прямой USB (свой драйвер, эксперимент)", Modifier.weight(1f))
                    Switch(directOn, { directOn = it; setDirect(it) })
                }
                if (UsbDirect.hasVolume) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Громкость ЦАП", style = MaterialTheme.typography.bodySmall)
                    Slider(UsbDirect.volume, { UsbDirect.setVolume(it) }, Modifier.weight(1f).padding(horizontal = 8.dp))
                    Text("${(UsbDirect.volume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
                if (UsbDirect.status.isNotEmpty() || directInfo.isNotEmpty())
                    Text(UsbDirect.status + if (directInfo.isNotEmpty()) "\n$directInfo" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            if (artistFilter != null) item { TextButton({ artistFilter = null }) { Text("✕ Исполнитель: $artistFilter") } }
                            items(shownAlbums) { (key, ts) ->
                                val (alb, art) = key.split('\u0000')
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
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!loggedIn) {
                                OutlinedTextField(qAppId, { qAppId = it }, label = { Text("app_id") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(qSecret, { qSecret = it }, label = { Text("app_secret") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(qToken, { qToken = it }, label = { Text("user_auth_token") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                                Button({ scope.launch {
                                    error = null
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            Providers.qobuz.signInWithToken(this@MainActivity, qAppId.trim(), qSecret.trim(), qToken.trim())
                                        }
                                    }.onSuccess { loggedIn = true; qToken = "" }.onFailure { error = it.message }
                                } }) { Text("Войти по токену") }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(query, { query = it }, label = { Text("Поиск альбомов и треков") }, singleLine = true, modifier = Modifier.weight(1f))
                                    Spacer(Modifier.width(8.dp))
                                    Button({ scope.launch {
                                        error = null; qAlbum = null; qArtistName = null
                                        runCatching { withContext(Dispatchers.IO) { Providers.qobuz.searchAll(query) } }
                                            .onSuccess { remoteAlbums = it.first; remote = it.second }.onFailure { error = it.message }
                                    } }) { Text("Найти") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({ query = ""; remote = emptyList(); remoteAlbums = emptyList(); qAlbum = null; qArtistName = null }) { Text("Главная") }
                                    TextButton({
                                        Providers.qobuz.logout(this@MainActivity)
                                        loggedIn = false; remote = emptyList(); remoteAlbums = emptyList(); home = emptyList(); qAlbum = null
                                    }) { Text("Выйти из Qobuz") }
                                }
                            }
                            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (loggedIn) {
                            val al = qAlbum
                            when {
                                al != null -> Column {
                                    TextButton({ qAlbum = null }) { Text("← ${al.title}", maxLines = 1) }
                                    if (qTracks.isEmpty()) Text("Загрузка…", Modifier.padding(16.dp))
                                    LazyColumn { itemsIndexed(qTracks) { i, t -> TrackRow(t, null, {}) { play(qTracks, i) } } }
                                }
                                qArtistName != null -> Column {
                                    TextButton({ qArtistName = null }) { Text("← ${qArtistName}", maxLines = 1) }
                                    if (qArtistAlbums.isEmpty())
                                        Text(if (qArtistLoading) "Загрузка…" else "Релизов не найдено", Modifier.padding(16.dp))
                                    LazyColumn {
                                        items(qArtistAlbums) { a ->
                                            ListItem(
                                                leadingContent = { Cover(a.asTrack(), 56.dp) },
                                                headlineContent = { Text(a.title, maxLines = 1) },
                                                supportingContent = { Text(listOf(a.year, a.quality).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1) },
                                                modifier = Modifier.clickable { openQ(a) })
                                        }
                                    }
                                }
                                remote.isNotEmpty() || remoteAlbums.isNotEmpty() -> LazyColumn {
                                    if (remoteAlbums.isNotEmpty()) item { AlbumRow("Альбомы", remoteAlbums, openQ) }
                                    itemsIndexed(remote) { i, t -> TrackRow(t, null, {}) { play(remote, i) } }
                                }
                                else -> LazyColumn {
                                    items(home) { (title, albums) -> AlbumRow(title, albums, openQ) }
                                    if (home.isEmpty()) item {
                                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(if (homeLoading) "Загрузка главной…" else "Главная не загрузилась", Modifier.weight(1f))
                                            if (!homeLoading) Button({ loadHome() }) { Text("Повторить") }
                                        }
                                    }
                                }
                            }
                        }
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
                Modifier.fillMaxSize().background(Color(0xFF0F0D13))
                    .background(Brush.verticalGradient(listOf(dark, Color(0xFF0F0D13))))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                TextButton({ expanded = false }, Modifier.align(Alignment.Start)) { Text("⌄  Свернуть") }
                Spacer(Modifier.height(8.dp))
                Cover(cur, side)
                Spacer(Modifier.height(24.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, textAlign = TextAlign.Center)
                cur?.let { t ->
                    Text(t.artist, Modifier.clickable { openArtist(t) }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    if (t.album.isNotEmpty())
                        Text(t.album, style = MaterialTheme.typography.bodyMedium, color = Color(0xCCFFFFFF), maxLines = 1)
                }
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

    private fun usbAudioDevice(): UsbDevice? = getSystemService(UsbManager::class.java).deviceList.values.firstOrNull { d ->
        (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }
    }

    /** Включает/выключает прямой драйвер (после выключения ЦАП может вернуться в систему только после переподключения). */
    private fun setDirect(on: Boolean) {
        if (!on) { Thread { UsbDirect.close(); runOnUiThread { AppState.reapply?.invoke() } }.start(); return }
        val dev = usbAudioDevice() ?: return run { UsbDirect.close(); AppState.message = "USB-аудиоустройство не найдено" }
        val um = getSystemService(UsbManager::class.java)
        fun go() = Thread { UsbDirect.open(this, dev); runOnUiThread { AppState.reapply?.invoke() } }.start()
        if (um.hasPermission(dev)) return go()
        val action = "$packageName.USB_PERMISSION_DIRECT"
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                unregisterReceiver(this)
                if (um.hasPermission(dev)) go() else AppState.message = "Нет разрешения на доступ к USB"
            }
        }
        ContextCompat.registerReceiver(this, rcv, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        um.requestPermission(dev, PendingIntent.getBroadcast(this, 0, Intent(action).setPackage(packageName), PendingIntent.FLAG_MUTABLE))
    }

    /** Кнопки громкости внутри приложения управляют громкостью самого ЦАП, когда включён прямой режим. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (UsbDirect.ready && UsbDirect.hasVolume && (up || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN) UsbDirect.setVolume(UsbDirect.volume + if (up) 0.03f else -0.03f)
            return true
        }
        return super.dispatchKeyEvent(event)
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
        queue = list
        c.setMediaItems(list.map(::item), index, 0L); c.prepare(); c.play()
    }

    /** Одиночный файл из проводника (в т.ч. DSF/DFF, которые MediaStore может не индексировать). */
    private fun playOne(uri: Uri) {
        val title = uri.lastPathSegment?.substringAfterLast('/') ?: "Файл"
        val track = Track(id = 0L, title = title, artist = "", album = "", durationMs = 0L, uri = uri)
        play(listOf(track), 0)
    }
}
