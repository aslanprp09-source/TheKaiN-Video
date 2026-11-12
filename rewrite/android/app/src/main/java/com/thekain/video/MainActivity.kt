package com.thekain.video

import android.Manifest
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thekain.video.presentation.*
import kotlinx.coroutines.launch

/** Presentation only; the accepted M4 transfer pipeline remains outside the Activity. */
class MainActivity : ComponentActivity() {
    private val model: MainViewModel by viewModels()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val home by model.home.state.collectAsStateWithLifecycle()
            val tasks by model.tasks.collectAsStateWithLifecycle()
            val preferences by model.preferences.state.collectAsStateWithLifecycle()
            val page by model.page.collectAsStateWithLifecycle()
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val owner = LocalLifecycleOwner.current
            var notifications by remember { mutableStateOf(getSystemService(NotificationManager::class.java).areNotificationsEnabled()) }
            DisposableEffect(owner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) notifications = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
                }
                owner.lifecycle.addObserver(observer)
                onDispose { owner.lifecycle.removeObserver(observer) }
            }
            val dark = preferences.theme == ThemeMode.DARK || preferences.theme == ThemeMode.SYSTEM && isSystemInDarkTheme()
            SideEffect {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            fun message(key: Int) { scope.launch { snackbar.showSnackbar(localized(model.preferences.state.value.language).getString(key)) } }
            LaunchedEffect(model) {
                model.accepted.collect {
                    message(R.string.accepted)
                    val prefs = getSharedPreferences("interface", Context.MODE_PRIVATE)
                    if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("notification_prompted", false) &&
                        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        prefs.edit().putBoolean("notification_prompted", true).apply()
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            TheKaiNTheme(preferences) {
                MainScreen(ScreenState(home, tasks, Page.valueOf(page), preferences, notifications), UiActions(
                    edit = model::edit, analyze = model.home::analyze, cancelAnalysis = model.home::cancelAnalysis,
                    select = model.home::select, download = { model.home.download() }, confirmRepeat = { model.home.download(true) }, dismissRepeat = model.home::dismissRepeat,
                    navigate = model::navigate, back = model::back, action = model::action,
                    paste = {
                        val clip = getSystemService(ClipboardManager::class.java).primaryClip // Explicit tap only.
                        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                        // Never dereference clipboard content providers or read their streams on the UI thread.
                        val text = item?.text?.toString() ?: item?.uri?.toString()
                        if (text.isNullOrBlank()) message(R.string.clipboard_empty) else model.edit(text)
                    },
                    open = { task ->
                        try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri), task.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                        catch (_: Exception) { message(R.string.open_failed) }
                    }, theme = model.preferences::theme, language = model.preferences::language,
                    notifications = { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                ), snackbar)
            }
        }
    }
}
