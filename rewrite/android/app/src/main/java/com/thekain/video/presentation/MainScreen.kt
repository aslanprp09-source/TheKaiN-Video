@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.thekain.video.presentation

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import com.thekain.video.R
import com.thekain.video.label
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import com.thekain.video.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class ScreenState(val home: HomeState = HomeState(), val tasks: List<DownloadTask> = emptyList(),
                       val page: Page = Page.HOME, val settings: UiSettings = UiSettings(), val notificationsEnabled: Boolean = true)
data class UiActions(
    val edit: (String) -> Unit = {}, val analyze: () -> Unit = {}, val cancelAnalysis: () -> Unit = {},
    val select: (String) -> Unit = {}, val download: () -> Unit = {}, val confirmRepeat: () -> Unit = {},
    val dismissRepeat: () -> Unit = {}, val paste: () -> Unit = {}, val navigate: (Page) -> Unit = {},
    val action: (String, String) -> Unit = { _, _ -> }, val open: (DownloadTask) -> Unit = {},
    val back: () -> Unit = {}, val theme: (ThemeMode) -> Unit = {}, val language: (String) -> Unit = {}, val notifications: () -> Unit = {})

@Composable fun MainScreen(state: ScreenState, actions: UiActions, snackbar: SnackbarHostState = remember { SnackbarHostState() }) {
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val urlFocus = remember { FocusRequester() }
    var focusHome by remember { mutableStateOf(false) }
    LaunchedEffect(state.page, focusHome) {
        if (state.page == Page.HOME && focusHome) { urlFocus.requestFocus(); focusHome = false }
    }
    BackHandler(enabled = !imeVisible && (state.page != Page.HOME || state.home.phase == AnalysisPhase.ANALYZING)) {
        if (state.page != Page.HOME) actions.back() else actions.cancelAnalysis()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding().semantics { testTagsAsResourceId = true }.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) false else when {
                event.isCtrlPressed && event.key == Key.L -> {
                    if (event.nativeKeyEvent.repeatCount == 0) {
                        actions.navigate(Page.HOME)
                        focusHome = true
                    }; true
                }
                event.isCtrlPressed && event.key == Key.Enter -> {
                    if (state.page == Page.HOME && event.nativeKeyEvent.repeatCount == 0) { keyboard?.hide(); focus.clearFocus(); actions.download() }; true
                }
                event.key == Key.Escape -> { keyboard?.hide(); focus.clearFocus(); if (state.home.phase == AnalysisPhase.ANALYZING) actions.cancelAnalysis(); true }
                else -> false
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(title = {
                if (state.page == Page.HOME) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Image(painterResource(R.drawable.thekain_icon), null, Modifier.size(36.dp))
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 22.sp)
                } else Text(stringResource(state.page.title()), fontWeight = FontWeight.SemiBold)
            }, navigationIcon = {
                if (state.page == Page.SETTINGS) IconButton(onClick = actions.back, modifier = Modifier.size(48.dp).testTag("back_button")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
            }, actions = {
                if (state.page != Page.SETTINGS) IconButton(onClick = { actions.navigate(Page.SETTINGS) }, modifier = Modifier.size(48.dp).testTag("settings_button")) {
                    Icon(Icons.Default.Settings, stringResource(R.string.settings))
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
        },
        bottomBar = {
            if (!imeVisible) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                listOf(Page.HOME, Page.DOWNLOADS, Page.LIBRARY).forEach { page ->
                    NavigationBarItem(selected = state.page == page, onClick = { focus.clearFocus(); actions.navigate(page) },
                        modifier = Modifier.testTag("nav_${page.name.lowercase(Locale.ROOT)}"),
                        icon = { Icon(painterResource(page.icon()), null) }, label = { Text(stringResource(page.title())) })
                }
            }
        }, snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
                when (state.page) {
                    Page.HOME -> Home(state, actions, urlFocus)
                    Page.DOWNLOADS -> Downloads(state.tasks, actions)
                    Page.LIBRARY -> Library(state.tasks, actions)
                    Page.SETTINGS -> Settings(state, actions)
                }
            }
        }
    }
    if (state.home.repeatConfirmation) AlertDialog(onDismissRequest = actions.dismissRepeat,
        title = { Text(stringResource(R.string.repeat_title)) }, text = { Text(stringResource(R.string.repeat_question)) },
        confirmButton = { TextButton(onClick = actions.confirmRepeat, modifier = Modifier.heightIn(min = 48.dp).testTag("repeat_confirm")) { Text(stringResource(R.string.repeat_confirm)) } },
        dismissButton = { TextButton(onClick = actions.dismissRepeat, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cancel)) } })
}

@Composable private fun Home(state: ScreenState, actions: UiActions, urlFocus: FocusRequester) {
    val home = state.home
    val ready = home.phase == AnalysisPhase.READY && home.variant != null
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).testTag("home_content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.home_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(value = home.draft, onValueChange = actions.edit, modifier = Modifier.fillMaxWidth().focusRequester(urlFocus).testTag("source_input")
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter && !event.isShiftPressed && !event.isCtrlPressed) {
                            if (event.nativeKeyEvent.repeatCount == 0) { keyboard?.hide(); focus.clearFocus(); actions.analyze() }; true
                        } else false
                    }, enabled = !home.enqueuePending,
                    label = { Text(stringResource(R.string.source_label)) }, placeholder = { Text("https://…") }, maxLines = 3,
                    shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); focus.clearFocus(); actions.analyze() }),
                    trailingIcon = { if (home.draft.isNotEmpty()) IconButton(onClick = { actions.edit("") }, enabled = !home.enqueuePending, modifier = Modifier.size(48.dp).testTag("clear_button")) {
                        Icon(Icons.Default.Close, stringResource(R.string.clear))
                    } })
                TextButton(onClick = actions.paste, enabled = !home.enqueuePending, modifier = Modifier.heightIn(min = 48.dp).testTag("paste_button")) {
                    Icon(painterResource(R.drawable.ic_paste), null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.paste))
                }
                Text(stringResource(R.string.authorized_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (home.phase == AnalysisPhase.ANALYZING) item {
                Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.analyzing))
                }
            }
            home.analysisError?.let { error -> item { ErrorMessage(stringResource(error.label())) } }
            home.enqueueError?.let { error -> item { ErrorMessage(stringResource(error.label())) } }
            home.source?.let { source -> item { Ready(source, home, actions) } }
            if (home.source == null && home.phase != AnalysisPhase.ANALYZING) item {
                if (state.tasks.isEmpty()) EmptyState(R.drawable.ic_downloads, R.string.home_empty_title, R.string.home_empty_text)
                else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.recent), style = MaterialTheme.typography.titleLarge)
                    state.tasks.sortedByDescending { it.createdAt }.take(2).forEach { task ->
                        Card(onClick = { actions.navigate(Page.DOWNLOADS) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                            Column(Modifier.padding(16.dp)) {
                                Text(task.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.height(6.dp)); Text(stringResource(task.error?.label() ?: task.state.label()), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (home.phase == AnalysisPhase.ANALYZING) OutlinedButton(onClick = actions.cancelAnalysis, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("cancel_analysis")) {
                    Text(stringResource(R.string.cancel_analysis))
                } else Button(onClick = { keyboard?.hide(); focus.clearFocus(); if (ready) actions.download() else actions.analyze() },
                    enabled = home.draft.isNotBlank() && !home.enqueuePending,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(if (ready) "download_button" else "analyze_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC52A38), contentColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                    if (home.enqueuePending) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(if (ready) painterResource(R.drawable.ic_downloads) else painterResource(R.drawable.ic_link), null, Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(if (home.enqueuePending) R.string.submitting else if (ready && home.accepted) R.string.download_again else if (ready) R.string.download else R.string.analyze), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable private fun Ready(source: SourceMedia, home: HomeState, actions: UiActions) {
    Card(Modifier.fillMaxWidth().testTag("source_preview"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        BoxWithConstraints(Modifier.padding(16.dp)) {
            val wide = maxWidth >= 520.dp
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Preview(source.poster, Modifier.weight(1f))
                Column(Modifier.weight(1f)) { SourceDetails(source, home, actions) }
            } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Preview(source.poster, Modifier.fillMaxWidth())
                SourceDetails(source, home, actions)
            }
        }
    }
}

@Composable private fun SourceDetails(source: SourceMedia, home: HomeState, actions: UiActions) {
    Text(source.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp)); Text(source.host, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    val selected = home.variant ?: return
    val videos = source.variants.filter { !it.audioOnly }
    val audio = source.variants.filter { it.audioOnly }
    if (videos.isNotEmpty() && audio.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !selected.audioOnly, onClick = { actions.select(videos.first().id) }, label = { Text(stringResource(R.string.video)) }, modifier = Modifier.heightIn(min = 48.dp))
            FilterChip(selected = selected.audioOnly, onClick = { actions.select(audio.first().id) }, label = { Text(stringResource(R.string.audio_only)) }, modifier = Modifier.heightIn(min = 48.dp))
        }
    }
    val choices = source.variants.filter { it.audioOnly == selected.audioOnly }
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(R.string.format), style = MaterialTheme.typography.labelLarge)
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = choices.size > 1 && !home.enqueuePending,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("format_selector"), shape = RoundedCornerShape(10.dp)) {
            Text("${selected.extension} · ${bytes(selected.bytes)}", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { variant -> DropdownMenuItem(text = { Column {
                Text("${variant.extension} · ${bytes(variant.bytes)}")
                Text(variant.request.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } },
                onClick = { expanded = false; actions.select(variant.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("variant_${variant.id}")) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(stringResource(if (choices.size == 1) R.string.single_format_reason else R.string.actual_formats_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_library), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(if (Build.VERSION.SDK_INT >= 29) R.string.destination_public else R.string.destination_private), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun Preview(poster: ByteArray?, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, poster) {
        value = withContext(Dispatchers.Default) {
            poster?.let { bytes ->
                try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth in 1..2048 && bounds.outHeight in 1..2048)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() else null
                } catch (_: Exception) { null }
            }
        }
    }
    Box(modifier.aspectRatio(16f / 9).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.background)), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val image = bitmap
        if (image != null) Image(image, stringResource(R.string.source_poster), Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Image(painterResource(R.drawable.thekain_icon), null, Modifier.size(48.dp))
            Text(stringResource(R.string.no_preview), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun Downloads(tasks: List<DownloadTask>, actions: UiActions) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("task_search"), singleLine = true,
            label = { Text(stringResource(R.string.search)) }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(14.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(R.string.filter_all, R.string.filter_active, R.string.filter_completed, R.string.filter_failed).forEachIndexed { index, label ->
                FilterChip(filter == index, { filter = index }, { Text(stringResource(label)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("filter_$index"))
            }
        }
        val visible = tasks.filter { task -> task.name.contains(query, ignoreCase = true) && when (filter) {
            1 -> task.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.FAILED)
            2 -> task.state == TransferState.COMPLETED
            3 -> task.state == TransferState.FAILED
            else -> true
        } }.sortedByDescending { it.createdAt }
        LazyColumn(Modifier.fillMaxSize().testTag("tasks_list"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (visible.isEmpty()) item { EmptyState(R.drawable.ic_downloads, if (tasks.isEmpty()) R.string.downloads_empty_title else R.string.no_results, R.string.downloads_empty_text) }
            items(visible, key = { it.id }) { task -> TaskCard(task, actions) }
        }
    }
}

@Composable private fun TaskCard(task: DownloadTask, actions: UiActions, library: Boolean = false) {
    Card(Modifier.fillMaxWidth().testTag("task_${task.id}"), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (task.state == TransferState.COMPLETED) Icons.Default.CheckCircle else if (task.state == TransferState.FAILED) Icons.Default.Warning else Icons.Default.Info,
                    null, Modifier.size(18.dp), tint = if (task.state == TransferState.COMPLETED) Color(0xFF36B37E) else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(task.state.label()), style = MaterialTheme.typography.labelLarge)
                if (task.state == TransferState.DOWNLOADING && task.percent != null) Text("${task.percent}%", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            if (!library && task.state !in setOf(TransferState.FAILED, TransferState.CANCELED, TransferState.PAUSED)) {
                val busy = task.total == null || task.state in setOf(TransferState.VERIFYING, TransferState.PUBLISHING)
                if (busy && task.state != TransferState.COMPLETED) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { (task.percent ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Text(if (task.state == TransferState.COMPLETED) bytes(task.verifiedBytes) else "${bytes(task.bytes)} / ${bytes(task.total)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            task.error?.let { Text(stringResource(it.label()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            if (task.state == TransferState.DOWNLOADING && !task.resumable) Text(stringResource(R.string.pause_unavailable), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.state == TransferState.COMPLETED) OutlinedButton(onClick = { actions.open(task) }, modifier = Modifier.heightIn(min = 48.dp).testTag("open_${task.id}")) { Text(stringResource(R.string.open)) }
                if (task.state == TransferState.DOWNLOADING && task.resumable && task.bytes > 0) TaskButton(R.string.pause, task, "pause", actions)
                if (task.state == TransferState.PAUSED) TaskButton(R.string.resume, task, "resume", actions)
                if (task.state == TransferState.FAILED) TaskButton(R.string.retry, task, "retry", actions)
                if (task.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.FAILED, TransferState.PUBLISHING, TransferState.CANCEL_REQUESTED)) TaskButton(R.string.cancel, task, "cancel", actions)
            }
        }
    }
}

@Composable private fun TaskButton(label: Int, task: DownloadTask, kind: String, actions: UiActions) {
    OutlinedButton(onClick = { actions.action(task.id, kind) }, modifier = Modifier.heightIn(min = 48.dp).testTag("${kind}_${task.id}")) { Text(stringResource(label)) }
}

@Composable private fun Library(tasks: List<DownloadTask>, actions: UiActions) {
    val completed = tasks.filter { it.state == TransferState.COMPLETED }.sortedByDescending { it.updatedAt }
    LazyVerticalGrid(GridCells.Adaptive(264.dp), Modifier.fillMaxSize().testTag("library_list"), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { Text(stringResource(R.string.library_description), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (completed.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(R.drawable.ic_library, R.string.library_empty_title, R.string.library_empty_text) }
        items(completed, key = { it.id }) { task -> TaskCard(task, actions, library = true) }
    }
}

@Composable private fun Settings(state: ScreenState, actions: UiActions) {
    var dialog by rememberSaveable { mutableIntStateOf(0) }
    LazyColumn(Modifier.fillMaxSize().testTag("settings_content"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SettingsSection(R.string.appearance) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    val selected = state.settings.theme == mode
                    OutlinedButton(onClick = { actions.theme(mode) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("theme_${mode.name.lowercase(Locale.ROOT)}"),
                        contentPadding = PaddingValues(6.dp), colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onSurface)) { Text(stringResource(mode.label()), maxLines = 2, fontSize = 13.sp) }
                }
            }
        } }
        item { SettingsSection(R.string.language) {
            listOf("ru" to "Русский", "en" to "English").forEach { (code, title) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.settings.language == code, role = Role.RadioButton, onClick = { actions.language(code) }).testTag("locale_$code"), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.settings.language == code, onClick = null); Spacer(Modifier.width(12.dp)); Text(title)
                }
            }
        } }
        item { SettingsSection(R.string.storage) {
            Text(stringResource(if (Build.VERSION.SDK_INT >= 29) R.string.destination_public else R.string.destination_private), fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.folder_fixed), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.concurrency_fixed), style = MaterialTheme.typography.bodyMedium)
        } }
        item { SettingsSection(R.string.background) {
            Text(stringResource(if (state.notificationsEnabled) R.string.notifications_enabled else R.string.notification_denied))
            Text(stringResource(R.string.background_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = actions.notifications, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.notification_settings)) }
        } }
        item { SettingsSection(R.string.about) {
            Text(stringResource(R.string.privacy_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { dialog = 1 }, modifier = Modifier.heightIn(min = 48.dp).testTag("support_info")) { Text(stringResource(R.string.supported_links)) }
            TextButton(onClick = { dialog = 2 }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.licenses)) }
            Text("TheKaiN Video · 0.1.0", style = MaterialTheme.typography.bodySmall)
        } }
    }
    if (dialog != 0) AlertDialog(onDismissRequest = { dialog = 0 }, title = { Text(stringResource(if (dialog == 1) R.string.supported_links else R.string.licenses)) },
        text = { Text(stringResource(if (dialog == 1) R.string.supported_links_body else R.string.licenses_body), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = { dialog = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.close)) } })
}

@Composable private fun SettingsSection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}
@Composable private fun ErrorMessage(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.Warning, null, Modifier.size(20.dp)); Text(text)
        }
    }
}
@Composable private fun EmptyState(icon: Int, title: Int, description: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(painterResource(icon), null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(stringResource(description), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
@Composable private fun bytes(value: Long?): String {
    if (value == null) return stringResource(R.string.unknown_size)
    val language = LocalConfiguration.current.locales[0]
    return when {
        value < 1024 -> stringResource(R.string.bytes_short, value)
        value < 1024 * 1024 -> String.format(language, "%.1f %s", value / 1024.0, stringResource(R.string.kib))
        value < 1024L * 1024 * 1024 -> String.format(language, "%.1f %s", value / 1048576.0, stringResource(R.string.mib))
        else -> String.format(language, "%.2f %s", value / 1073741824.0, stringResource(R.string.gib))
    }
}
private fun Page.title() = when (this) { Page.HOME -> R.string.home; Page.DOWNLOADS -> R.string.downloads; Page.LIBRARY -> R.string.library; Page.SETTINGS -> R.string.settings }
private fun Page.icon() = when (this) { Page.HOME -> R.drawable.ic_home; Page.DOWNLOADS -> R.drawable.ic_downloads; else -> R.drawable.ic_library }
private fun ThemeMode.label() = when (this) { ThemeMode.SYSTEM -> R.string.theme_system; ThemeMode.DARK -> R.string.theme_dark; ThemeMode.LIGHT -> R.string.theme_light }
private fun AnalysisError.label() = when (this) {
    AnalysisError.INVALID_URL -> R.string.analysis_invalid
    AnalysisError.UNSUPPORTED_SOURCE -> R.string.analysis_unsupported
    AnalysisError.NO_FORMATS -> R.string.analysis_no_formats
    AnalysisError.TOO_LARGE -> R.string.analysis_too_large
    AnalysisError.FORBIDDEN -> R.string.error_remote_forbidden
    AnalysisError.MISSING -> R.string.error_remote_missing
    AnalysisError.RATE_LIMITED -> R.string.error_rate_limited
    AnalysisError.TIMEOUT -> R.string.error_timeout
    AnalysisError.NETWORK -> R.string.error_network_lost
    AnalysisError.UNKNOWN -> R.string.error_unknown
}
