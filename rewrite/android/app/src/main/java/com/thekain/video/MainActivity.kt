package com.thekain.video

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.*
import com.thekain.video.data.DownloadTask
import com.thekain.video.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/** Minimal M4 view. WorkManager/Room own all tasks; Compose/page extraction is M5. */
class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val graph get() = (application as TheKaiNApp).graph
    private lateinit var input: EditText
    private lateinit var submit: Button
    private lateinit var info: TextView
    private lateinit var tasks: LinearLayout
    private var handledSubmission: String? = null
    private var notificationAsked = false
    private var busy = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledSubmission = savedInstanceState?.getString("handled_submission")
        notificationAsked = savedInstanceState?.getBoolean("notification_asked") ?: false
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bar = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()) else null
            @Suppress("DEPRECATION")
            view.setPadding(dp(16) + (bar?.left ?: insets.systemWindowInsetLeft), dp(16) + (bar?.top ?: insets.systemWindowInsetTop),
                dp(16) + (bar?.right ?: insets.systemWindowInsetRight), dp(16) + (bar?.bottom ?: insets.systemWindowInsetBottom))
            insets
        }
        root.addView(TextView(this).apply { setText(R.string.app_name); textSize = 25f })
        root.addView(TextView(this).apply { setText(R.string.direct_only) })
        input = EditText(this).apply {
            id = R.id.source_input
            setHint(R.string.direct_hint)
            setSingleLine(true)
            minHeight = dp(48)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setText(savedInstanceState?.getString("draft").orEmpty())
        }
        root.addView(input)
        val clear = Button(this).apply { setText(R.string.clear); minHeight = dp(48); setOnClickListener { input.text.clear() } }
        root.addView(clear)
        submit = Button(this).apply { id = R.id.download_button; setText(R.string.download); minHeight = dp(48); isEnabled = input.text.isNotBlank() }
        root.addView(submit)
        info = TextView(this).apply { id = R.id.status_text; setText(R.string.idle) }
        root.addView(info)
        root.addView(TextView(this).apply { setText(R.string.storage_hint) })
        tasks = LinearLayout(this).apply { id = R.id.task_list; orientation = LinearLayout.VERTICAL }
        root.addView(tasks)
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { submit.isEnabled = !busy && !s.isNullOrBlank() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        submit.setOnClickListener {
            if (busy) return@setOnClickListener
            val request = try { DirectLinks.parse(input.text.toString()) } catch (error: TransferFailure) {
                info.setText(error.code.label()); return@setOnClickListener
            }
            busy = true
            submit.isEnabled = false
            graph.submit(request) // App-owned action survives Activity recreation.
            if (Build.VERSION.SDK_INT >= 33 && !notificationAsked && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationAsked = true
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
            }
        }
        scope.launch {
            graph.submission.collectLatest { state ->
                busy = state.busy
                input.isEnabled = !busy
                clear.isEnabled = !busy
                submit.isEnabled = !busy && input.text.isNotBlank()
                submit.setText(if (busy) R.string.submitting else R.string.download)
                if (state.busy) info.setText(R.string.submitting)
                if (state.operation.isNotEmpty() && state.operation != handledSubmission && !state.busy) {
                    handledSubmission = state.operation
                    if (state.accepted != null) { input.text.clear(); info.setText(R.string.accepted) }
                    state.error?.let { info.setText(it.label()) }
                }
            }
        }
        scope.launch { graph.repository.changes.collectLatest { render(it) } }
    }

    private fun render(rows: List<DownloadTask>) {
        tasks.removeAllViews()
        rows.forEach { task ->
            val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = task.id; setPadding(0, dp(20), 0, dp(12)) }
            item.addView(TextView(this).apply { text = task.name; textSize = 18f })
            item.addView(TextView(this).apply { setText(task.error?.label() ?: task.state.label()) })
            item.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = task.state in setOf(TransferState.VERIFYING, TransferState.PUBLISHING) || task.percent == null
                progress = task.percent ?: 0
                contentDescription = getString(task.state.label())
                visibility = if (task.state in setOf(TransferState.FAILED, TransferState.CANCELED)) android.view.View.GONE else android.view.View.VISIBLE
            })
            item.addView(TextView(this).apply { text = getString(R.string.progress_bytes, task.bytes, task.total?.toString() ?: getString(R.string.unknown_size)) })
            fun action(label: Int, kind: String) { item.addView(Button(this).apply {
                setText(label); minHeight = dp(48); setOnClickListener { graph.action(task.id, kind) }
            }) }
            if (task.state == TransferState.DOWNLOADING && task.resumable && task.bytes > 0) action(R.string.pause, "pause")
            if (task.state == TransferState.PAUSED) action(R.string.resume, "resume")
            if (task.state == TransferState.FAILED) action(R.string.retry, "retry")
            if (task.state !in setOf(TransferState.COMPLETED, TransferState.CANCELED, TransferState.FAILED, TransferState.PUBLISHING, TransferState.CANCEL_REQUESTED)) action(R.string.cancel, "cancel")
            if (task.state == TransferState.COMPLETED) item.addView(Button(this).apply {
                setText(R.string.open); minHeight = dp(48)
                setOnClickListener {
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(task.outputUri), task.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: Exception) { info.setText(R.string.open_failed) }
                }
            })
            tasks.addView(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 10 && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) info.setText(R.string.notification_denied)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("draft", if (busy) "" else input.text.toString())
        outState.putString("handled_submission", handledSubmission)
        outState.putBoolean("notification_asked", notificationAsked)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
