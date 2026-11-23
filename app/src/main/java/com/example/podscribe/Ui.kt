@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.podscribe

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.provider.Settings
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val LANGS = listOf("English", "German", "Persian", "Arabic", "French", "Spanish", "Italian", "Turkish", "Russian", "Chinese", "Japanese", "Korean", "Portuguese", "Hindi")
val SKIPS = listOf(3, 5, 7, 10, 15)

fun fmt(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%d:%02d".format(m, x)
}

@Composable
fun Art(url: String?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (!url.isNullOrBlank()) AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.Mic, null, Modifier.align(Alignment.Center), tint = MaterialTheme.colorScheme.primary)
    }
}

private class ShortApiKeyMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        if (source.isEmpty()) return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
        val suffixLength = minOf(4, source.length)
        val prefixLength = source.length - suffixLength
        val visibleSuffix = if (source.length <= 4) "•".repeat(source.length) else " ${source.takeLast(4)}"
        val transformed = "••••$visibleSuffix"
        return TransformedText(AnnotatedString(transformed), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int =
                if (source.isEmpty()) 0 else if (offset <= prefixLength) 4 + if (suffixLength > 0) 1 else 0
                else (5 + offset - prefixLength).coerceAtMost(transformed.length)
            override fun transformedToOriginal(offset: Int): Int =
                if (source.isEmpty()) 0 else if (offset <= 5) prefixLength else (prefixLength + offset - 5).coerceIn(0, source.length)
        })
    }
}

@Composable
private fun SettingsGroupLabel(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, bottom = 1.dp)) {
        Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, null, Modifier.padding(8.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
fun PodApp(vm: AppVM, darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val episodeState by Repo.episodes.collectAsState()
    var tab by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var showNow by rememberSaveable { mutableStateOf(false) }
    val snack = remember { SnackbarHostState() }
    val reduceMotion = remember {
        runCatching { Settings.Global.getFloat(vm.getApplication<android.app.Application>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    LaunchedEffect(vm.message) { vm.message?.let { snack.showSnackbar(it); vm.message = null } }
    BackHandler(showNow) { showNow = false }
    BackHandler(open != null && !showNow) { open = null }

    val nav = listOf("Library" to Icons.Filled.LibraryMusic, "New" to Icons.Filled.FiberNew, "Downloads" to Icons.Filled.Download, "Settings" to Icons.Filled.Settings)
    Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snack) },
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                Column {
                    if (vm.current != null) MiniPlayer(vm) { showNow = true }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
                        windowInsets = WindowInsets.navigationBars) {
                        nav.forEachIndexed { i, (n, ic) ->
                            NavigationBarItem(tab == i, { tab = i; open = null },
                                icon = { if (i == 1 && episodeState.any { !it.seen }) BadgedBox({ Badge { Text(episodeState.count { !it.seen }.coerceAtMost(99).toString()) } }) { Icon(ic, n) } else Icon(ic, n) }, label = { Text(n, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                    indicatorColor = if (darkTheme) MaterialTheme.colorScheme.primary else Color(0xFFFFE7E1),
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                ))
                        }
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> if (open != null) PodcastScreen(vm, open!!, { open = null }) { showNow = true } else LibraryScreen(vm) { open = it }
                    1 -> NewEpisodesScreen(vm, darkTheme) { showNow = true }
                    2 -> DownloadsScreen(vm) { showNow = true }
                    else -> SettingsScreen(darkTheme, onThemeChange)
                }
            }
        }
        AnimatedVisibility(showNow && vm.current != null,
            enter = if (reduceMotion) EnterTransition.None else slideInVertically(initialOffsetY = { it }, animationSpec = androidx.compose.animation.core.tween(200)),
            exit = if (reduceMotion) ExitTransition.None else slideOutVertically(targetOffsetY = { it }, animationSpec = androidx.compose.animation.core.tween(200))) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                NowPlaying(vm) { showNow = false }
            }
        }
    }
}

@Composable
fun NewEpisodesScreen(vm: AppVM, darkTheme: Boolean, onPlay: () -> Unit) {
    val all by Repo.episodes.collectAsState()
    val fresh = remember(all) { all.filter { !it.seen }.sortedByDescending { it.pubDate } }
    val heroForeground = if (darkTheme) MaterialTheme.colorScheme.onPrimary else Color.White
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(if (darkTheme) listOf(Color(0xFFB95D1F), Color(0xFFE78235)) else listOf(Color(0xFF653990), Color(0xFF8750BB))))
        ) {
            Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("YOUR DAILY LISTEN", style = MaterialTheme.typography.labelMedium, color = heroForeground.copy(alpha = 0.82f))
                    Text("New episodes", style = MaterialTheme.typography.headlineMedium, color = heroForeground)
                    Text("${fresh.size} ready to explore", style = MaterialTheme.typography.bodyMedium, color = heroForeground.copy(alpha = 0.9f))
                }
                Icon(Icons.Filled.GraphicEq, null, Modifier.size(42.dp), tint = heroForeground.copy(alpha = 0.9f))
            }
        }
        if (fresh.isEmpty()) Box(Modifier.fillMaxSize().padding(20.dp), Alignment.Center) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DoneAll, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("You're all caught up", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
                }
            }
        }
        else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) { items(fresh, key = { it.id }) { ep ->
            val ordered = remember(all, ep.podcastId) { all.filter { it.podcastId == ep.podcastId }.sortedByDescending { it.pubDate } }
            EpisodeRow(vm, ep, ordered, onPlayEpisode = { Repo.markSeen(ep.id); onPlay() })
        } }
    }
}

@Composable
fun MiniPlayer(vm: AppVM, onExpand: () -> Unit) {
    val e = vm.current ?: return
    var acc by remember { mutableFloatStateOf(0f) }
    Surface(
        Modifier.fillMaxWidth().clickable { onExpand() }.pointerInput(Unit) {
            detectVerticalDragGestures(
                onDragStart = { acc = 0f },
                onDragEnd = { if (acc < -40f) onExpand() },
                onVerticalDrag = { c, d -> c.consume(); acc += d }
            )
        },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
            LinearProgressIndicator(progress = { if (vm.dur > 0) vm.pos.toFloat() / vm.dur else 0f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Art(e.image, 44.dp)
                Spacer(Modifier.width(10.dp))
                Text(e.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton({ vm.seekBy(-vm.skipBack * 1000L) }) { Icon(Icons.Filled.Replay, "Skip back ${vm.skipBack} seconds", tint = MaterialTheme.colorScheme.secondary) }
                IconButton({ vm.toggle() }) { Icon(if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (vm.playing) "Pause playback" else "Play playback", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }
}

@Composable
fun LibraryScreen(vm: AppVM, onOpen: (String) -> Unit) {
    val pods by Repo.podcasts.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(Repo.librarySort) }
    var customOrder by remember { mutableStateOf(Repo.libraryOrder) }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragPosition by remember { mutableStateOf(Offset.Zero) }
    val gridState = rememberLazyGridState()
    LaunchedEffect(pods) {
        val ids = pods.map { it.id }
        customOrder = customOrder.filter { it in ids } + ids.filterNot { it in customOrder }
        Repo.libraryOrder = customOrder
    }
    val orderedPods = remember(pods, sort, customOrder) {
        when (sort) {
            "title_az" -> pods.sortedBy { it.title.lowercase() }
            "title_za" -> pods.sortedByDescending { it.title.lowercase() }
            "custom" -> customOrder.mapNotNull { id -> pods.find { it.id == id } } + pods.filter { it.id !in customOrder }
            else -> pods
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).clip(MaterialTheme.shapes.large)
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("PODSCRIBE  /  LIBRARY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        Text("Your library", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimary)
                        Text("${pods.size} shows · ${Repo.episodes.value.count { !it.seen }} new episodes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f))
                    }
                    Icon(Icons.Filled.Headphones, null, Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.92f))
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your shows", Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.titleMedium)
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort podcasts") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        listOf("custom" to "Custom order (drag to arrange)", "added" to "Recently added", "title_az" to "Title: A–Z", "title_za" to "Title: Z–A").forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                sort = value; Repo.librarySort = value; sortMenu = false
                            })
                        }
                    }
                }
                IconButton({ vm.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh podcasts", tint = MaterialTheme.colorScheme.secondary) }
            }
            if (pods.isEmpty()) {
                Card(
                    Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = MaterialTheme.shapes.large,
                ) {
                    Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(Icons.Filled.Podcasts, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.height(16.dp))
                        Text("Your listening room starts here", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("Add a podcast by pasting its RSS feed URL. New episodes will appear in your library.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { dialog = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add podcast")
                        }
                    }
                }
            } else LazyVerticalGrid(GridCells.Adaptive(150.dp), state = gridState, contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(orderedPods, key = { it.id }) { p ->
                    val cardModifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(p.id) }
                        .then(if (sort == "custom") Modifier.pointerInput(p.id, sort) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { start ->
                                    draggingId = p.id
                                    val info = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == p.id }
                                    dragPosition = if (info != null) Offset(info.offset.x + start.x, info.offset.y + start.y) else start
                                },
                                onDragEnd = { draggingId = null },
                                onDragCancel = { draggingId = null },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragPosition += amount
                                    val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                        val x = dragPosition.x; val y = dragPosition.y
                                        x >= info.offset.x && x <= info.offset.x + info.size.width &&
                                            y >= info.offset.y && y <= info.offset.y + info.size.height
                                    }?.key as? String
                                    val from = customOrder.indexOf(p.id)
                                    val to = customOrder.indexOf(target)
                                    if (from >= 0 && to >= 0 && from != to) {
                                        val updated = customOrder.toMutableList().apply { add(to, removeAt(from)) }
                                        customOrder = updated
                                        Repo.libraryOrder = updated
                                    }
                                }
                            )
                        } else Modifier)
                    Card(cardModifier.graphicsLayer { alpha = if (draggingId == p.id) 0.65f else 1f },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = MaterialTheme.shapes.medium, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                        Column {
                            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                                Art(p.image, 150.dp, Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)))
                            }
                            Text(p.title, Modifier.padding(horizontal = 12.dp, vertical = 10.dp), maxLines = 2,
                                minLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        if (pods.isNotEmpty()) FloatingActionButton({ dialog = true }, Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primary) { Icon(Icons.Filled.Add, "Add feed") }
    }
    if (dialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { dialog = false }, title = { Text("Add podcast") },
            text = { OutlinedTextField(url, { url = it }, label = { Text("RSS feed URLs") }, placeholder = { Text("One URL per line") }, minLines = 3, maxLines = 6) },
            confirmButton = { TextButton({ val urls = url.lines().flatMap { it.split(',', ';', '\t', ' ') }.filter { it.startsWith("http://") || it.startsWith("https://") }; if (urls.isNotEmpty()) vm.addFeeds(urls); dialog = false }) { Text("Add feeds") } },
            dismissButton = { TextButton({ dialog = false }) { Text("Cancel") } })
    }
}

@Composable
fun PodcastScreen(vm: AppVM, id: String, onBack: () -> Unit, onPlayEpisode: () -> Unit) {
    val pods by Repo.podcasts.collectAsState()
    val all by Repo.episodes.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val p = pods.find { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val eps = remember(all, id) { all.filter { it.podcastId == id }.sortedByDescending { it.pubDate } }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)))) {
                    Column(Modifier.padding(16.dp)) {
                        IconButton(onBack) { Icon(Icons.Filled.KeyboardArrowDown, "Back", Modifier.graphicsLayer { rotationZ = if (isRtl) -90f else 90f }, tint = MaterialTheme.colorScheme.onPrimary) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Art(p.image, 104.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, style = MaterialTheme.typography.titleLarge, maxLines = 3, color = MaterialTheme.colorScheme.onPrimary)
                                Text(p.author, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ vm.refresh() }) { Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("Refresh", color = MaterialTheme.colorScheme.onPrimary) }
                            TextButton({ confirm = true }) { Icon(Icons.Filled.PersonRemove, null, tint = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(6.dp)); Text("Unsubscribe", color = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }
                }
                Text("Episodes · ${eps.size}", Modifier.padding(start = 4.dp, top = 18.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
        items(eps, key = { it.id }) { EpisodeRow(vm, it, eps, onPlayEpisode = onPlayEpisode) }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
            title = { Text("Unsubscribe?") },
        text = { Text("This removes \"${p.title}\", its episodes, downloaded files and transcripts from this device.") },
        confirmButton = { TextButton({ confirm = false; onBack(); vm.unsubscribe(id) }) { Text("Unsubscribe", color = MaterialTheme.colorScheme.primary) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } }
    )
}

@Composable
fun EpisodeRow(vm: AppVM, e: Episode, list: List<Episode>, showExternalOpen: Boolean = false, onPlayEpisode: (() -> Unit)? = null) {
    val dl by Repo.dl.collectAsState()
    val prog = dl[e.id]
    val context = LocalContext.current
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.download(e) else vm.message = "Storage permission is needed to save audio in Music/PodScribe"
    }
    fun download() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            writePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else vm.download(e)
    }
    val df = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }
    var actions by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
        .clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
        .clickable { Repo.markSeen(e.id); vm.play(e, list); onPlayEpisode?.invoke() }
        .padding(horizontal = 14.dp, vertical = if (showExternalOpen) 14.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (showExternalOpen) { Art(e.image, 64.dp, Modifier.clip(MaterialTheme.shapes.small)); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (e.played) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = if (showExternalOpen) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge)
            Text(buildString {
                if (e.pubDate > 0) append(df.format(Date(e.pubDate)))
                if (e.durationSec > 0) append("  ·  ${e.durationSec / 60} min")
                if (e.positionMs > 0 && !e.played) append("  ·  ${fmt(e.positionMs)} played")
                if (e.localPath != null) append(" · offline")
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showExternalOpen && e.durationSec > 0) {
                val fraction = (e.positionMs.toFloat() / (e.durationSec * 1000f)).coerceIn(0f, 1f)
                LinearProgressIndicator({ fraction }, Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
            }
        }
        when {
            prog != null -> CircularProgressIndicator(progress = { prog }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            showExternalOpen -> Box { IconButton({ actions = true }) { Icon(Icons.Filled.MoreVert, "Download actions") }
                DropdownMenu(actions, { actions = false }) {
                    DropdownMenuItem(text = { Text("Remove download") }, leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) }, onClick = { actions = false; Repo.deleteDownload(e) })
                    DropdownMenuItem(text = { Text("Show in folder") }, leadingIcon = { Icon(Icons.Filled.FolderOpen, null) }, onClick = { actions = false; vm.openDownloadFolder() })
                    DropdownMenuItem(text = { Text("Open with another app") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) }, onClick = { actions = false; vm.openAudioInOtherApp(e) })
                }
            }
            e.localPath != null -> IconButton({ Repo.deleteDownload(e) }) { Icon(Icons.Filled.DownloadDone, "Delete download", tint = MaterialTheme.colorScheme.primary) }
            else -> IconButton(::download) { Icon(Icons.Filled.Download, "Download") }
        }
        if (e.localPath != null && !showExternalOpen) {
            IconButton({ vm.openDownloadFolder() }) { Icon(Icons.Filled.FolderOpen, "Show download location") }
        }
    }
}

@Composable
fun DownloadsScreen(vm: AppVM, onPlayEpisode: () -> Unit) {
    val all by Repo.episodes.collectAsState()
    val list = all.filter { it.localPath != null }.sortedByDescending { it.pubDate }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text("YOUR AUDIO", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Downloads", style = MaterialTheme.typography.headlineMedium)
            Surface(Modifier.fillMaxWidth().padding(top = 12.dp), color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(10.dp)); Text("Saved to Music/PodScribe", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
        }
        if (list.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f).padding(20.dp), Alignment.Center) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = MaterialTheme.shapes.large) {
                Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DownloadForOffline, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("Your offline library is ready", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
                    Text("Download an episode from any podcast to listen here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                }
            }
        } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(list, key = { it.id }) { EpisodeRow(vm, it, list, showExternalOpen = true, onPlayEpisode = onPlayEpisode) }
        }
    }
}

@Composable
fun SettingsScreen(darkTheme: Boolean, onThemeChange: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var opmlMessage by remember { mutableStateOf<String?>(null) }
    val importOpml = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { scope.launch {
            runCatching { context.contentResolver.openInputStream(it)!!.use { stream -> Repo.importOpml(stream) } }
                .onSuccess { count -> opmlMessage = "$count subscriptions imported"; Repo.refreshAll() }
                .onFailure { opmlMessage = "Could not import OPML: ${it.message}" }
        } }
    }
    val exportOpml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/xml")) { uri ->
        uri?.let {
            runCatching { context.contentResolver.openOutputStream(it)!!.use(Repo::exportOpml) }
                .onSuccess { opmlMessage = "OPML export complete" }
                .onFailure { opmlMessage = "Could not export OPML: ${it.message}" }
        }
    }
    var keys by remember { mutableStateOf(Repo.keysText) }
    var revealKeys by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(Repo.model) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelInfo by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var lang by remember { mutableStateOf(Repo.lang) }
    var size by remember { mutableFloatStateOf(Repo.tsSize.toFloat()) }
    var langMenu by remember { mutableStateOf(false) }
    var localEngine by remember { mutableStateOf(Repo.transcriptionEngine == "local") }
    var selectedLocalModel by remember { mutableStateOf(LocalModels.selectedId) }
    val downloadProgress by LocalModels.downloadProgress.collectAsState()
    val downloadStates by LocalModels.downloadStatus.collectAsState()
    val keyValues = if (keys.isBlank()) listOf("") else keys.split('\n')
    val keyLimited = modelInfo.contains("429") || modelInfo.contains("rate", ignoreCase = true) || modelInfo.contains("quota", ignoreCase = true)
    val keyStatus = when { Repo.keys().isEmpty() -> "Not configured"; keyLimited -> "Limited"; else -> "Active" }

    LazyColumn(contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Column(Modifier.padding(bottom = 2.dp)) {
            Text("YOUR PODSCRIBE", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
        } }
        item { SettingsGroupLabel("Library", Icons.Filled.LibraryMusic) }
        item { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Podcast library", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("Import or back up your subscriptions as an OPML file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ importOpml.launch(arrayOf("application/xml", "text/xml", "text/*")) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Import OPML") }
                OutlinedButton({ exportOpml.launch("PodScribe-subscriptions.opml") }, contentPadding = PaddingValues(horizontal = 14.dp)) { Icon(Icons.Filled.FileDownload, null, tint = MaterialTheme.colorScheme.secondary); Spacer(Modifier.width(6.dp)); Text("Export OPML") }
            }
            opmlMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (it.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
        } } }
        item { SettingsGroupLabel("Transcription", Icons.Filled.Subtitles) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Choose where transcripts are created", style = MaterialTheme.typography.titleSmall)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !localEngine, onClick = { localEngine = false; Repo.transcriptionEngine = "cloud" }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) { Text("Gemini") }
                        SegmentedButton(selected = localEngine, onClick = { localEngine = true; Repo.transcriptionEngine = "local" }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) { Text("On-device") }
                    }
                    Text(if (localEngine) "Run transcription on this device. Download a model once to use it offline." else "Use Gemini to transcribe and translate episode audio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (localEngine) {
            item { SettingsGroupLabel("Local models", Icons.Filled.CloudDownload) }
            items(LocalModels.catalog, key = { it.id }) { localModel ->
                val fraction = downloadProgress[localModel.id]
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selectedLocalModel == localModel.id, {
                                selectedLocalModel = localModel.id; LocalModels.selectedId = localModel.id
                            })
                            Column(Modifier.weight(1f).clickable {
                                selectedLocalModel = localModel.id; LocalModels.selectedId = localModel.id
                            }) {
                                Text(localModel.title, style = MaterialTheme.typography.titleSmall)
                                Text("${localModel.sizeLabel} · download once, then offline", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            when {
                                localModel.ready -> AssistChip(onClick = {}, label = { Text("Ready") }, leadingIcon = { Icon(Icons.Filled.CheckCircle, null) })
                                fraction != null -> CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                                else -> OutlinedButton(onClick = {
                                    selectedLocalModel = localModel.id; LocalModels.selectedId = localModel.id
                                    localEngine = true; Repo.transcriptionEngine = "local"
                                    scope.launch { runCatching { LocalModels.download(localModel) } }
                                }) { Text("Download") }
                            }
                        }
                        downloadStates[localModel.id]?.let { Text(it, color = if (it.startsWith("Download failed")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                        if (fraction != null) Text("${(fraction * 100).toInt()}% downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { Text("Whisper models support multilingual audio. Parakeet v3 covers 25 European languages. Large models require substantial free storage and memory.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            item { SettingsGroupLabel("Keys", Icons.Filled.Key) }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Gemini API key", style = MaterialTheme.typography.titleMedium)
                                Text("${Repo.keys().size} saved · ending ${Repo.keys().firstOrNull()?.takeLast(4)?.let { "••••$it" } ?: "none"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            val statusColor = when (keyStatus) {
                                "Active" -> if (darkTheme) Color(0xFF8ED6AC) else Color(0xFF2F805D)
                                "Limited" -> if (darkTheme) Color(0xFFFFC16E) else Color(0xFF9A540D)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Surface(shape = RoundedCornerShape(50), color = statusColor.copy(alpha = 0.12f)) {
                                Text(keyStatus, Modifier.padding(horizontal = 12.dp, vertical = 7.dp), color = statusColor, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                        keyValues.forEachIndexed { index, keyValue ->
                            OutlinedTextField(
                                value = keyValue,
                                onValueChange = { value ->
                                    val updated = keyValues.toMutableList(); updated[index] = value
                                    keys = updated.joinToString("\n"); Repo.keysText = keys
                                },
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                                label = { Text("Gemini API key ${index + 1}") },
                                placeholder = { Text("API key") },
                                visualTransformation = if (revealKeys) VisualTransformation.None else ShortApiKeyMask(),
                                trailingIcon = {
                                    Row {
                                        IconButton({ revealKeys = !revealKeys }) { Icon(if (revealKeys) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide API keys") }
                                        if (keyValue.isNotBlank()) IconButton({
                                            val updated = keyValues.toMutableList().apply { removeAt(index) }
                                            keys = updated.joinToString("\n"); Repo.keysText = keys
                                        }) { Icon(Icons.Filled.DeleteOutline, "Remove API key") }
                                    }
                                },
                                supportingText = { Text(if (keyValue.isBlank()) "Paste Gemini API keys, one per line" else "•••• ${keyValue.takeLast(4)}") },
                            )
                        }
                        Button({ keys = if (keys.isEmpty()) "\n" else "$keys\n"; Repo.keysText = keys }, Modifier.align(Alignment.End)) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("Add another key") }
                        Text("If a key is rate-limited or rejected, the next saved Gemini key is tried.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Gemini model", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(model, { model = it; Repo.model = it }, Modifier.fillMaxWidth(), singleLine = true,
                            label = { Text("Model ID") }, supportingText = { Text("Audio-capable generateContent model, e.g. ${Repo.DEFAULT_MODEL}") })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(enabled = !loading, onClick = {
                                val key = Repo.keys().firstOrNull()
                                if (key == null) modelInfo = "Add a Gemini API key first" else {
                                    loading = true; modelInfo = ""
                                    scope.launch {
                                        try { models = Gemini.listModels(key); modelInfo = "${models.size} Gemini models available" }
                                        catch (e: Exception) { modelInfo = "Could not fetch models: ${e.message}" }
                                        loading = false
                                    }
                                }
                            }) { if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Fetch models") }
                            OutlinedButton({ model = Repo.DEFAULT_MODEL; Repo.model = model }) { Text("Reset") }
                        }
                        if (modelInfo.isNotBlank()) Text(modelInfo, style = MaterialTheme.typography.bodySmall, color = if (modelInfo.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(models, key = { it }) { availableModel ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { model = availableModel; Repo.model = availableModel }
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(availableModel, Modifier.weight(1f), color = if (availableModel == model) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    if (availableModel == model) Icon(Icons.Filled.Check, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { SettingsGroupLabel("Appearance", Icons.Filled.Palette) }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("App theme", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(selected = !darkTheme, onClick = { onThemeChange(false) }, shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)) {
                            Text("Light")
                        }
                        SegmentedButton(selected = darkTheme, onClick = { onThemeChange(true) }, shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)) {
                            Text("Dark")
                        }
                    }
                    Text("Choose a warm light canvas or a soft, low-glare dark palette.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transcript language and display", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text("Translate Gemini transcripts into", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box {
                        OutlinedButton({ langMenu = true }) { Text(lang) }
                        DropdownMenu(langMenu, { langMenu = false }) {
                            LANGS.forEach { language -> DropdownMenuItem({ Text(language) }, { lang = language; Repo.lang = language; langMenu = false }) }
                        }
                    }
                    Text("Text size: ${size.roundToInt()} sp", color = MaterialTheme.colorScheme.primary)
                    Slider(size, { size = it; Repo.tsSize = it.roundToInt() }, valueRange = 14f..30f)
                }
            }
        }
    }
}
fun showOrig(s: Seg, mode: Int) = mode != 1
fun showTr(s: Seg, mode: Int) = mode != 0

fun occurrences(text: String, q: String): Int {
    if (q.isEmpty()) return 0
    var n = 0; var i = 0
    while (true) {
        val f = text.indexOf(q, i, ignoreCase = true)
        if (f < 0) return n
        n++; i = f + q.length
    }
}

fun highlight(text: String, q: String, startK: Int, curK: Int, accent: Color = Accent, onAccent: Color = Color.White): AnnotatedString {
    if (q.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var idx = 0; var k = startK
        while (true) {
            val f = text.indexOf(q, idx, ignoreCase = true)
            if (f < 0) break
            append(text.substring(idx, f))
            val style = if (k == curK) SpanStyle(background = accent, color = onAccent) else SpanStyle(background = Color(0x66FFD54F))
            withStyle(style) { append(text.substring(f, f + q.length)) }
            idx = f + q.length; k++
        }
        append(text.substring(idx))
    }
}

private fun timedHighlight(words: List<WordStamp>, active: Int, accent: Color = Accent, onAccent: Color = Color.White): AnnotatedString = buildAnnotatedString {
    words.forEachIndexed { index, word ->
        if (index > 0) append(' ')
        if (index == active) withStyle(SpanStyle(background = accent.copy(alpha = 0.85f), color = onAccent)) { append(word.text) }
        else append(word.text)
    }
}

@Composable
fun TranscriptPanel(vm: AppVM, ep: Episode, modifier: Modifier) {
    val allSegs by Transcriber.segs.collectAsState()
    val statuses by Transcriber.status.collectAsState()
    val preferredId = if (Repo.transcriptionEngine == "local") LocalModels.transcriptId(ep.id) else ep.id
    val transcriptIds = remember(ep.id) { listOf(ep.id) + LocalModels.catalog.map { LocalModels.transcriptId(it.id, ep.id) } }
    LaunchedEffect(ep.id) { transcriptIds.forEach(Transcriber::load) }
    val transcriptId = if (allSegs[preferredId].orEmpty().isNotEmpty()) preferredId
        else transcriptIds.firstOrNull { allSegs[it].orEmpty().isNotEmpty() } ?: preferredId
    val segs = allSegs[transcriptId] ?: emptyList()
    val status = statuses[ep.id]
    val busy = status != null && !status.startsWith("Failed")
    val localTranscript = transcriptId.startsWith("local-v")
    val displaySegs = remember(segs, localTranscript) {
        val joined = ArrayList<Seg>()
        val maxGapMs = if (localTranscript) 15_000L else 3_000L
        fun joinText(a: String, b: String): String {
            val left = a.trimEnd(); val right = b.trimStart()
            val punctuation = right.firstOrNull()?.let { it in ".,!;:%…)]}" } == true
            return left + (if (punctuation || left.isEmpty()) "" else " ") + right
        }
        segs.sortedBy { it.startMs }.forEach { next ->
            val previous = joined.lastOrNull()
            if (previous != null && next.startMs - previous.endMs in 0..maxGapMs && next.endMs - previous.startMs <= 45_000L) {
                joined[joined.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, next.endMs),
                    text = joinText(previous.text, next.text),
                    tr = if (previous.tr.isBlank()) next.tr else if (next.tr.isBlank()) previous.tr else joinText(previous.tr, next.tr),
                    words = (previous.words + next.words).sortedBy { it.startMs })
            } else joined.add(next)
        }
        joined
    }
    var mode by remember(transcriptId) { mutableIntStateOf(if (localTranscript) 0 else Repo.tsMode) }
    val transcriptListState = rememberLazyListState()
    var lastFollowedIndex by remember(transcriptId) { mutableIntStateOf(-1) }
    val followedIndex = if (vm.playing) displaySegs.indexOfLast { it.startMs <= vm.pos }.takeIf { it >= 0 && vm.pos <= displaySegs[it].endMs } ?: -1 else -1
    var autoFollow by remember { mutableStateOf(Repo.transcriptAutoFollow) }
    LaunchedEffect(followedIndex, autoFollow) {
        if (autoFollow && followedIndex >= 0 && followedIndex != lastFollowedIndex) {
            transcriptListState.animateScrollToItem(followedIndex)
            lastFollowedIndex = followedIndex
        }
    }
    val size = Repo.tsSize
    val view = LocalView.current
    val toolbar = remember(view) { ShareToolbar(view) }
    DisposableEffect(toolbar) { onDispose { toolbar.hide() } }

    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var cur by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    val matches = remember(segs, query, mode) {
        val out = ArrayList<Pair<Int, Int>>()
        if (query.isNotEmpty()) displaySegs.forEachIndexed { i, s ->
            var k = 0
            if (showOrig(s, mode) || s.tr.isBlank()) k += occurrences(s.text, query)
            if (showTr(s, mode) && s.tr.isNotBlank()) k += occurrences(s.tr, query)
            repeat(k) { out.add(i to it) }
        }
        out
    }
    LaunchedEffect(query, mode) { cur = 0 }
    LaunchedEffect(cur, matches, displaySegs) {
        if (matches.isNotEmpty()) {
            transcriptListState.animateScrollToItem(matches[cur.coerceIn(0, matches.lastIndex)].first)
        }
    }
    fun next() { if (matches.isNotEmpty()) cur = (cur + 1) % matches.size }
    fun prev() { if (matches.isNotEmpty()) cur = (cur - 1 + matches.size) % matches.size }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            (if (localTranscript) listOf("Original") else listOf("Original", "Translation", "Both")).forEachIndexed { i, n ->
                FilterChip(mode == i, { mode = i; Repo.tsMode = i }, { Text(n) }, Modifier.padding(end = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            if (displaySegs.isNotEmpty()) IconButton({ searchOpen = !searchOpen; if (!searchOpen) query = "" }) {
                Icon(Icons.Filled.Search, "Search transcript", tint = if (searchOpen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            if (displaySegs.isNotEmpty()) IconButton({ confirmClear = true }) { Icon(Icons.Filled.DeleteOutline, "Delete transcript", tint = MaterialTheme.colorScheme.error) }
            IconButton({ autoFollow = !autoFollow; lastFollowedIndex = -1; Repo.transcriptAutoFollow = autoFollow }) {
                Icon(Icons.Filled.Sync, if (autoFollow) "Turn off transcript auto-follow" else "Turn on transcript auto-follow",
                    tint = if (autoFollow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton({ vm.transcribe(ep) }) { Icon(Icons.Filled.Subtitles, "Transcribe", tint = MaterialTheme.colorScheme.primary) }
        }
        if (searchOpen) Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Search transcript") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { next() }),
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Filled.Close, "Clear") } })
            Text(if (matches.isEmpty()) "0/0" else "${cur.coerceIn(0, matches.lastIndex) + 1}/${matches.size}",
                Modifier.padding(horizontal = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton({ prev() }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
            IconButton({ next() }) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
        }
        if (status != null) Text(status, style = MaterialTheme.typography.bodySmall, color = if (busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (displaySegs.isEmpty()) Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Filled.Subtitles, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.secondary)
                    Text("No transcript yet.\nTap the subtitles button to transcribe and translate this episode.", Modifier.padding(top = 12.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                SelectionContainer {
                    LazyColumn(state = transcriptListState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 16.dp)) {
                        itemsIndexed(displaySegs, key = { index, item -> "${item.startMs}-$index" }) { i, s ->
                            val cm = matches.getOrNull(cur)
                            val curK = if (cm != null && cm.first == i) cm.second else -1
                            val showOriginal = showOrig(s, mode) || s.tr.isBlank()
                            val showTranslation = showTr(s, mode) && s.tr.isNotBlank()
                            val origCount = if (showOriginal) occurrences(s.text, query) else 0
                            val currentWord = if (vm.playing) s.words.indexOfFirst { vm.pos >= it.startMs && vm.pos < it.endMs } else -1
                            Column(Modifier.fillMaxWidth().padding(vertical = if (localTranscript) 1.dp else 9.dp)) {
                                if (showOriginal) Text(if (s.words.isNotEmpty() && query.isBlank()) timedHighlight(s.words, currentWord, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary) else highlight(s.text, query, 0, curK, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary), color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Serif, fontSize = (if (mode == 2) size - 2 else size).sp, lineHeight = (size * 1.5f).sp, letterSpacing = 0.1.sp, textAlign = TextAlign.Justify)
                                if (showTranslation) Text(highlight(s.tr, query, origCount, curK, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary), Modifier.padding(top = 3.dp),
                                    color = if (mode == 2) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.68f) else MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Serif, fontSize = (if (mode == 2) size - 4 else size).sp, lineHeight = (size * 1.5f).sp, letterSpacing = 0.1.sp, textAlign = TextAlign.Justify)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Delete transcript?") },
        text = { Text("The transcript will be removed. You can transcribe this episode again later.") },
        confirmButton = { TextButton({ confirmClear = false; Repo.clearTranscript(ep.id); transcriptIds.forEach { Transcriber.load(it) } }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } })
}

@Composable
fun SkipBtn(sec: Int, forward: Boolean, onClick: () -> Unit) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    IconButton(onClick, Modifier.size(44.dp), enabled = true) {
        Box(contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Replay, if (forward) "Skip forward $sec seconds" else "Skip back $sec seconds", Modifier.size(36.dp).graphicsLayer { if (forward xor isRtl) scaleX = -1f })
            Text("$sec", fontSize = 11.sp)
        }
    }
}

@Composable
fun NowPlaying(vm: AppVM, onClose: () -> Unit) {
    val e = vm.current ?: return
    val pods by Repo.podcasts.collectAsState()
    var opts by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var acc by remember { mutableFloatStateOf(0f) }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxHeight().padding(horizontal = 18.dp).padding(bottom = 8.dp)) {
            Column(Modifier.fillMaxWidth().pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { acc = 0f },
                    onDragEnd = { if (acc > 60f) onClose() },
                    onVerticalDrag = { c, d -> c.consume(); acc += d }
                )
            }) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).width(40.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.outline))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
                    Art(e.image, 52.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(pods.find { it.id == e.podcastId }?.title ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
            TranscriptPanel(vm, e, Modifier.weight(1f).padding(top = 8.dp))
            Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.large,
                tonalElevation = 2.dp) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Slider(drag ?: if (vm.dur > 0) vm.pos.toFloat() / vm.dur else 0f, { drag = it }, onValueChangeFinished = { drag?.let { vm.seekTo((it * vm.dur).toLong()) }; drag = null })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(vm.pos), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Text("−${fmt((vm.dur - vm.pos).coerceAtLeast(0))}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton({ vm.prev() }, Modifier.size(40.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(28.dp).graphicsLayer { if (isRtl) scaleX = -1f }) }
                        SkipBtn(vm.skipBack, false) { vm.seekBy(-vm.skipBack * 1000L) }
                        FilledIconButton({ vm.toggle() }, Modifier.size(56.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)) {
                            Icon(if (vm.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (vm.playing) "Pause playback" else "Play playback", Modifier.size(36.dp))
                        }
                        SkipBtn(vm.skipFwd, true) { vm.seekBy(vm.skipFwd * 1000L) }
                        IconButton({ vm.next() }, Modifier.size(40.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(28.dp).graphicsLayer { if (isRtl) scaleX = -1f }) }
                        Surface(onClick = { opts = true }, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Tune, "Playback options", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
                                Text("%.1fx".format(vm.speed), Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }
    }
    if (opts) ModalBottomSheet({ opts = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Playback speed: %.1fx".format(vm.speed), color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s -> FilterChip(vm.speed == s, { vm.setSpeedTo(s) }, { Text("${s}x") }) }
            }
            Text("Skip back (seconds)", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SKIPS.forEach { s -> FilterChip(vm.skipBack == s, { vm.setBack(s) }, { Text("$s") }) } }
            Text("Skip forward (seconds)", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { SKIPS.forEach { s -> FilterChip(vm.skipFwd == s, { vm.setFwd(s) }, { Text("$s") }) } }
        }
    }
}
