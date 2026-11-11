package com.thekain.video

import android.app.Activity
import android.app.DownloadManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/** Basic direct HTTPS file downloader. No webpage scraping and no DRM. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private lateinit var progress: ProgressBar
    private lateinit var download: Button
    private var active: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(34, 54, 34, 24)
        }
        root.addView(TextView(this).apply { text = "TheKaiN Video"; textSize = 25f })
        root.addView(TextView(this).apply {
            text = "Версия 0.1: прямые HTTPS-ссылки на видео и аудио."
        })
        val input = EditText(this).apply {
            hint = "https://example.com/video.mp4"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(input)
        download = Button(this).apply { text = "Скачать файл" }
        root.addView(download)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(progress)
        info = TextView(this).apply { text = "Ожидание ссылки" }
        root.addView(info)
        setContentView(root)
        val manager = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
        download.setOnClickListener {
            val uri = Uri.parse(input.text.toString().trim())
            val ext = uri.path.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || ext !in setOf("mp4", "webm", "mp3", "m4a", "mov")) {
                info.text = "Требуется прямая HTTPS-ссылка на mp4, webm, mp3, m4a или mov"
                return@setOnClickListener
            }
            val safeName = uri.lastPathSegment.orEmpty()
                .replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
                .ifBlank { "media." + ext }
            try {
                val request = DownloadManager.Request(uri)
                    .setTitle(safeName)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                active = manager.enqueue(request)
                progress.progress = 0
                info.text = "Загрузка запущена"
                download.isEnabled = false
                handler.postDelayed({ poll(manager) }, 500)
            } catch (ex: Exception) {
                info.text = "Ошибка запуска: " + ex.message
            }
        }
    }

    private fun poll(manager: DownloadManager) {
        val id = active ?: return
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) {
                info.text = "Задача не найдена"
                done()
                return
            }
            val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val size = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            when (state) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    progress.progress = 100
                    info.text = "Система подтвердила загрузку в папку Downloads"
                    done()
                }
                DownloadManager.STATUS_FAILED -> {
                    info.text = "Загрузка не удалась. Проверьте ссылку и подключение."
                    done()
                }
                else -> {
                    if (size > 0) progress.progress = (bytes * 100 / size).toInt().coerceIn(0, 99)
                    info.text = "Передано " + (bytes / 1024) + " КБ"
                    handler.postDelayed({ poll(manager) }, 750)
                }
            }
        }
    }

    private fun done() {
        active = null
        download.isEnabled = true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
