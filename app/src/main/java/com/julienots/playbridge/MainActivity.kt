package com.julienots.playbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

class MainActivity : Activity(), ClaudeAgent.Listener, BrowserTools.Ui {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var web: WebView
    private lateinit var controller: WebController
    private lateinit var agent: ClaudeAgent
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var chatPanel: LinearLayout
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private lateinit var urlView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var toggleBtn: TextView
    private val logText = SpannableStringBuilder()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override val homeUrl = "https://play.google.com/console"

    private val accent = Color.parseColor("#D97757")
    private val dark = Color.parseColor("#1F1E1D")

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = dark
        window.navigationBarColor = dark

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(dark) }

        // ----- barre du haut
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setBackgroundColor(dark)
        }
        fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onClick() }
        }
        bar.addView(barButton("←") { if (web.canGoBack()) web.goBack() })
        bar.addView(barButton("⌂") { web.loadUrl(homeUrl) })
        urlView = TextView(this).apply {
            setTextColor(Color.parseColor("#C8C6C0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { askUrl() }
        }
        bar.addView(urlView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(barButton("↻") { web.reload() })
        bar.addView(barButton("⚙") { showSettings() })
        root.addView(bar)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // ----- WebView + indicateur de toucher
        val frame = FrameLayout(this)
        web = WebView(this)
        val indicator = TapIndicatorView(this)
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        frame.addView(indicator, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ----- panneau Claude
        toggleBtn = TextView(this).apply {
            text = "▼ Claude"
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setBackgroundColor(accent)
            setOnClickListener { toggleChat() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(accent)
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(toggleBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "⟲ Nouvelle conversation"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { confirmReset() }
        })
        root.addView(header)

        chatPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#2A2927")) }
        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEBE6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        chatPanel.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.24f).toInt()))

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        input = EditText(this).apply {
            hint = "Dis à Claude quoi faire dans la Play Console…"
            setHintTextColor(Color.parseColor("#8A8780"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 4
            imeOptions = EditorInfo.IME_ACTION_SEND
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(Color.parseColor("#3A3936")) }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { onSend(); true } else false }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sendBtn = Button(this).apply {
            text = "Envoyer"
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(accent) }
            setOnClickListener { onSendOrStop() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply { leftMargin = dp(8) })
        chatPanel.addView(inputRow)
        root.addView(chatPanel)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        controller = WebController(web, window, indicator)
        agent = ClaudeAgent(BrowserTools(controller, this), this, scope)

        log(ClaudeAgent.LogKind.INFO, "Bienvenue ! Connecte-toi à ta Play Console ci-dessus, puis dis à Claude ce qu'il doit faire (ex. « Crée une nouvelle application nommée Kittyro »). Il navigue tant que l'appli reste ouverte.")
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(homeUrl)
        if (apiKey().isBlank()) showSettings()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(false)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        applyUserAgent()
        // Google refuse parfois la connexion dans une WebView : on retire les marqueurs « WebView ».
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val scheme = u.scheme ?: return false
                if (scheme == "http" || scheme == "https") return false
                // intent:, mailto:, market: … → autres applis
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                urlView.text = url ?: ""
            }

            override fun onPageFinished(view: WebView, url: String?) {
                urlView.text = url ?: ""
                CookieManager.getInstance().flush()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                progressBar.visibility = if (newProgress >= 100) View.INVISIBLE else View.VISIBLE
            }

            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                controller.fileChooserOpened = true
                log(ClaudeAgent.LogKind.INFO, "📁 Choisis le fichier demandé dans le sélecteur qui vient de s'ouvrir.")
                return try {
                    val intent = params.createIntent().apply {
                        if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        // Les .aab n'ont pas de type MIME reconnu : on autorise tout.
                        type = "*/*"
                    }
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) {
                    fileCallback = null
                    false
                }
            }
        }
        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            runCatching {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                    .addRequestHeader("User-Agent", userAgent)
                    .setMimeType(mimeType)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Téléchargement : $name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyUserAgent() {
        val s = web.settings
        val desktop = prefs.getBoolean("desktop", true)
        s.userAgentString = if (desktop) {
            DESKTOP_UA
        } else {
            WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FILE) return
        val cb = fileCallback ?: return
        fileCallback = null
        var result: Array<Uri>? = null
        if (resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            result = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        }
        cb.onReceiveValue(result)
        controller.fileChooserOpened = false
        if (result != null) log(ClaudeAgent.LogKind.INFO, "Fichier choisi.") else log(ClaudeAgent.LogKind.INFO, "Aucun fichier choisi.")
    }

    // ----------------------------------------------------------------- discussion

    private fun onSendOrStop() {
        if (agent.isRunning && input.text.isBlank()) {
            agent.stop()
        } else onSend()
    }

    private fun onSend() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        val key = apiKey()
        if (key.isBlank()) { showSettings(); return }
        input.setText("")
        if (chatPanel.getChildAt(0).visibility != View.VISIBLE) toggleChat()
        agent.send(text, ClaudeAgent.Config(key, prefs.getString("model", MODELS[0].first)!!, prefs.getString("effort", "medium")!!))
    }

    override fun onLog(kind: ClaudeAgent.LogKind, text: String) = log(kind, text)

    private fun log(kind: ClaudeAgent.LogKind, text: String) {
        val (prefix, color) = when (kind) {
            ClaudeAgent.LogKind.USER -> "Toi : " to Color.parseColor("#9CC7FF")
            ClaudeAgent.LogKind.CLAUDE -> "Claude : " to accent
            ClaudeAgent.LogKind.ACTION -> "   " to Color.parseColor("#8A8780")
            ClaudeAgent.LogKind.INFO -> "ℹ " to Color.parseColor("#B5B2AA")
            ClaudeAgent.LogKind.ERROR -> "⚠ " to Color.parseColor("#FF8A80")
        }
        if (logText.isNotEmpty()) logText.append("\n")
        val start = logText.length
        logText.append(prefix)
        logText.setSpan(ForegroundColorSpan(color), start, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (kind == ClaudeAgent.LogKind.CLAUDE || kind == ClaudeAgent.LogKind.USER) {
            logText.setSpan(StyleSpan(Typeface.BOLD), start, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val bodyStart = logText.length
        logText.append(text)
        if (kind == ClaudeAgent.LogKind.ACTION || kind == ClaudeAgent.LogKind.ERROR || kind == ClaudeAgent.LogKind.INFO) {
            logText.setSpan(ForegroundColorSpan(color), bodyStart, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // On garde la discussion légère.
        if (logText.length > 40_000) logText.delete(0, logText.length - 30_000)
        logView.text = logText
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onRunningChanged(running: Boolean) {
        sendBtn.text = if (running) "Stop" else "Envoyer"
        toggleBtn.text = (if (chatPanel.getChildAt(0).visibility == View.VISIBLE) "▼" else "▲") + if (running) " Claude travaille…" else " Claude"
        // Écran allumé pendant que Claude travaille : la page ne doit pas se mettre en veille.
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun toggleChat() {
        val v = chatPanel.getChildAt(0)
        v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        onRunningChanged(agent.isRunning)
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setMessage("Effacer la conversation avec Claude ? (La page reste ouverte.)")
            .setPositiveButton("Effacer") { _, _ ->
                agent.reset(); logText.clear(); logView.text = ""
                log(ClaudeAgent.LogKind.INFO, "Nouvelle conversation.")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ----------------------------------------------------------------- réglages

    private fun apiKey() = prefs.getString("api_key", "") ?: ""

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(0)) }
        box.addView(TextView(this).apply {
            text = "Clé API Anthropic (console.anthropic.com → API Keys). Elle reste sur ce téléphone."
            setPadding(0, dp(4), 0, dp(4))
        })
        val keyEdit = EditText(this).apply {
            setText(apiKey())
            hint = "sk-ant-…"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        box.addView(keyEdit)
        box.addView(TextView(this).apply { text = "Modèle"; setPadding(0, dp(12), 0, 0) })
        val modelSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, MODELS.map { it.second })
            setSelection(MODELS.indexOfFirst { it.first == prefs.getString("model", MODELS[0].first) }.coerceAtLeast(0))
        }
        box.addView(modelSpin)
        box.addView(TextView(this).apply { text = "Effort de réflexion"; setPadding(0, dp(12), 0, 0) })
        val efforts = listOf("low" to "Rapide (low)", "medium" to "Normal (medium)", "high" to "Approfondi (high)")
        val effortSpin = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, efforts.map { it.second })
            setSelection(efforts.indexOfFirst { it.first == prefs.getString("effort", "medium") }.coerceAtLeast(0))
        }
        box.addView(effortSpin)
        val desktopCheck = CheckBox(this).apply {
            text = "Version ordinateur du site (recommandé pour la Play Console)"
            isChecked = prefs.getBoolean("desktop", true)
        }
        box.addView(desktopCheck)

        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Enregistrer") { _, _ ->
                val desktopChanged = desktopCheck.isChecked != prefs.getBoolean("desktop", true)
                prefs.edit()
                    .putString("api_key", keyEdit.text.toString().trim())
                    .putString("model", MODELS[modelSpin.selectedItemPosition].first)
                    .putString("effort", efforts[effortSpin.selectedItemPosition].first)
                    .putBoolean("desktop", desktopCheck.isChecked)
                    .apply()
                if (desktopChanged) { applyUserAgent(); web.reload() }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun askUrl() {
        val edit = EditText(this).apply {
            setText(web.url)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Adresse")
            .setView(edit)
            .setPositiveButton("Ouvrir") { _, _ ->
                val u = edit.text.toString().trim()
                if (u.isNotEmpty()) web.loadUrl(if (u.startsWith("http")) u else "https://$u")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // ----------------------------------------------------------------- BrowserTools.Ui

    override suspend fun loadUrl(url: String) = withContext(Dispatchers.Main) { web.loadUrl(url) }
    override suspend fun goBack(): Boolean = withContext(Dispatchers.Main) { if (web.canGoBack()) { web.goBack(); true } else false }
    override suspend fun goForward(): Boolean = withContext(Dispatchers.Main) { if (web.canGoForward()) { web.goForward(); true } else false }
    override suspend fun reload() = withContext(Dispatchers.Main) { web.reload() }

    // ----------------------------------------------------------------- cycle de vie

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) { web.goBack(); return true }
        return super.onKeyDown(keyCode, event)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        scope.cancel()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_FILE = 42
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        val MODELS = listOf(
            "claude-opus-5-5" to "Claude Opus 5.5 (le plus fiable)",
            "claude-sonnet-5-5" to "Claude Sonnet 5.5 (moins cher)",
            "claude-haiku-5-5" to "Claude Haiku 5.5 (très économique)",
        )
    }
}
