package com.julienots.playbridge

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
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
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : Activity(), BrowserTools.Ui, McpServer.Listener {

    enum class LogKind { CLIENT, CLAUDE, ACTION, INFO, ERROR }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var web: WebView
    private lateinit var controller: WebController
    private lateinit var server: McpServer
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var addressView: TextView
    private lateinit var headerText: TextView
    private lateinit var urlView: TextView
    private lateinit var progressBar: ProgressBar
    private val logText = SpannableStringBuilder()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    override val homeUrl = "https://play.google.com/console"

    private val accent = Color.parseColor("#D97757")
    private val dark = Color.parseColor("#1F1E1D")

    private val isTablet get() = resources.configuration.smallestScreenWidthDp >= 600
    private val deviceLabel get() = prefs.getString("device_name", null)?.takeIf { it.isNotBlank() } ?: if (isTablet) "tablette" else "telephone"
    private val serverKey get() = "playconsole-" + deviceLabel.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

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

        // ----- panneau MCP
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(accent)
            gravity = Gravity.CENTER_VERTICAL
        }
        headerText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { togglePanel() }
        }
        header.addView(headerText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(this).apply {
            text = "Connecter Claude"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            setOnClickListener { showConnectHelp() }
        })
        root.addView(header)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.parseColor("#2A2927")) }
        val addrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(2))
        }
        addressView = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEBE6"))
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextIsSelectable(true)
        }
        addrRow.addView(addressView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addrRow.addView(Button(this).apply {
            text = "Copier"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setColor(accent) }
            setOnClickListener { copy("Adresse MCP", mcpUrl() ?: "") }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { leftMargin = dp(8) })
        body.addView(addrRow)

        logView = TextView(this).apply {
            setTextColor(Color.parseColor("#EDEBE6"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(4), dp(12), dp(8))
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply { addView(logView) }
        body.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * (if (isTablet) 0.16f else 0.2f)).toInt()))
        root.addView(body)

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        setupWebView()
        controller = WebController(web, window, indicator)
        server = McpServer(BrowserTools(controller, this), "${deviceLabel} · ${Build.MODEL}", this)
        server.token = token()

        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(homeUrl)
        startServer()
        log(LogKind.INFO, "Connecte-toi à ta Play Console ci-dessus, puis touche « Connecter Claude » pour relier Claude à cet appareil (même Wi-Fi). Garde l'appli ouverte pendant qu'il travaille.")
    }

    // ----------------------------------------------------------------- serveur MCP

    private fun token(): String {
        var t = prefs.getString("token", null)
        if (t.isNullOrBlank()) { t = newToken(); prefs.edit().putString("token", t).apply() }
        return t
    }

    private fun newToken(): String {
        val chars = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val r = SecureRandom()
        return (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun startServer() {
        try {
            server.start(prefs.getInt("port", 8765))
            if (prefs.getBoolean("keep_screen", true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            log(LogKind.ERROR, "Impossible de démarrer le serveur MCP : ${e.message}")
        }
        refreshAddress()
    }

    private fun mcpUrl(): String? {
        if (!server.isRunning) return null
        val ip = McpServer.localAddresses().firstOrNull() ?: return null
        return "http://$ip:${server.port}/mcp/${server.token}"
    }

    private fun refreshAddress() {
        val url = mcpUrl()
        addressView.text = when {
            !server.isRunning -> "Serveur MCP arrêté"
            url == null -> "Pas de réseau Wi-Fi : connecte l'appareil au même Wi-Fi que ton ordinateur.\nSur l'appareil lui-même : http://127.0.0.1:${server.port}/mcp/${server.token}"
            else -> url
        }
        updateHeader()
    }

    private fun updateHeader() {
        val open = body.visibility == View.VISIBLE
        headerText.text = (if (open) "▼ " else "▲ ") + (if (server.isRunning) "● MCP actif — $deviceLabel" else "○ MCP arrêté")
    }

    private fun togglePanel() {
        body.visibility = if (body.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        updateHeader()
    }

    override fun onClientConnected(clientName: String) {
        runOnUiThread { log(LogKind.CLIENT, "$clientName s'est connecté.") }
    }

    override fun onToolCall(name: String, args: JSONObject) {
        runOnUiThread { log(LogKind.ACTION, describe(name, args)) }
    }

    override fun onToolResult(name: String, output: ToolOutput) {
        if (output.isError) runOnUiThread { log(LogKind.ERROR, output.text.take(300)) }
    }

    private fun describe(name: String, a: JSONObject): String {
        fun g(k: String) = if (a.has(k) && !a.isNull(k)) a.opt(k)?.toString() else null
        return when (name) {
            "screenshot" -> "📷 Capture d'écran"
            "read_page" -> "📖 Lecture de la page"
            "click" -> "👆 Toucher " + (g("text")?.let { "« $it »" } ?: g("index")?.let { "élément #$it" } ?: g("selector") ?: "(${g("x")}, ${g("y")})")
            "type_text" -> "⌨️ Écrire « ${g("text")?.take(60)} »"
            "select_option" -> "☑️ Choisir « ${g("option")} »"
            "press_key" -> "⌨️ Touche ${g("key")}"
            "scroll" -> "↕️ Défiler ${g("direction")}"
            "navigate" -> "🌐 " + (g("url") ?: g("action") ?: "")
            "wait" -> "⏳ Attendre " + (g("until_text")?.let { "« $it »" } ?: g("until_gone")?.let { "disparition de « $it »" } ?: "${g("seconds") ?: 3} s")
            "run_js" -> "🧩 Script dans la page"
            "message" -> "💬 Message"
            "status" -> "ℹ️ État"
            else -> name
        }
    }

    private fun showConnectHelp() {
        val url = mcpUrl()
        if (url == null) {
            AlertDialog.Builder(this).setTitle("Pas de Wi-Fi")
                .setMessage("Connecte cet appareil au même réseau Wi-Fi que l'ordinateur où tourne Claude, puis réessaie.")
                .setPositiveButton("OK", null).show()
            refreshAddress()
            return
        }
        val name = serverKey
        val codeCmd = "claude mcp add --transport http $name $url"
        val desktopJson = """
{
  "mcpServers": {
    "$name": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "$url", "--allow-http"]
    }
  }
}""".trim()
        val msg = """
Claude se connecte à cet appareil par le Wi-Fi (MCP local, sans clé API). L'ordinateur et cet appareil doivent être sur le même Wi-Fi.

• Claude Code (terminal) :
$codeCmd

• Claude Desktop : Réglages → Développeur → Modifier la config, ajoute dans claude_desktop_config.json :
$desktopJson
puis redémarre Claude Desktop (Node.js doit être installé).

• Téléphone ET tablette : installe l'appli sur les deux. Chacun affiche sa propre adresse : ajoute les deux dans Claude avec deux noms différents (ex. playconsole-telephone et playconsole-tablette). Tu choisis ensuite sur lequel Claude travaille en le lui disant.

L'adresse contient un jeton secret : ne la partage pas. Si l'adresse IP de l'appareil change (autre Wi-Fi, redémarrage de la box), mets-la à jour dans Claude.
        """.trimIndent()
        val tv = TextView(this).apply {
            text = msg
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(4))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        AlertDialog.Builder(this)
            .setTitle("Connecter Claude à cet appareil")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copier commande Claude Code") { _, _ -> copy("Commande Claude Code", codeCmd) }
            .setNeutralButton("Copier config Desktop") { _, _ -> copy("Config Claude Desktop", desktopJson) }
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun copy(label: String, text: String) {
        if (text.isEmpty()) return
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(this, "$label copiée", Toast.LENGTH_SHORT).show()
    }

    // ----------------------------------------------------------------- WebView

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
                log(LogKind.INFO, "📁 Choisis le fichier demandé dans le sélecteur qui vient de s'ouvrir.")
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
        val desktop = prefs.getBoolean("desktop", true)
        web.settings.userAgentString = if (desktop) DESKTOP_UA
        else WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
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
        log(LogKind.INFO, if (result != null) "Fichier choisi." else "Aucun fichier choisi.")
    }

    // ----------------------------------------------------------------- journal

    private fun log(kind: LogKind, text: String) {
        val (prefix, color) = when (kind) {
            LogKind.CLIENT -> "🔗 " to Color.parseColor("#9CC7FF")
            LogKind.CLAUDE -> "Claude : " to accent
            LogKind.ACTION -> "   " to Color.parseColor("#B5B2AA")
            LogKind.INFO -> "ℹ " to Color.parseColor("#B5B2AA")
            LogKind.ERROR -> "⚠ " to Color.parseColor("#FF8A80")
        }
        if (logText.isNotEmpty()) logText.append("\n")
        val start = logText.length
        logText.append(prefix)
        logText.setSpan(ForegroundColorSpan(color), start, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val bodyStart = logText.length
        logText.append(text)
        if (kind == LogKind.CLAUDE) {
            logText.setSpan(StyleSpan(Typeface.BOLD), start, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else if (kind != LogKind.CLIENT) {
            logText.setSpan(ForegroundColorSpan(color), bodyStart, logText.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (logText.length > 30_000) logText.delete(0, logText.length - 20_000)
        logView.text = logText
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ----------------------------------------------------------------- réglages

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(0)) }
        box.addView(TextView(this).apply { text = "Nom de cet appareil (affiché à Claude)" })
        val nameEdit = EditText(this).apply { setText(deviceLabel); isSingleLine = true }
        box.addView(nameEdit)
        box.addView(TextView(this).apply { text = "Port du serveur MCP"; setPadding(0, dp(10), 0, 0) })
        val portEdit = EditText(this).apply {
            setText(prefs.getInt("port", 8765).toString())
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        box.addView(portEdit)
        val desktopCheck = CheckBox(this).apply {
            text = "Version ordinateur du site (recommandé pour la Play Console)"
            isChecked = prefs.getBoolean("desktop", true)
        }
        box.addView(desktopCheck)
        val screenCheck = CheckBox(this).apply {
            text = "Garder l'écran allumé (sinon Claude ne peut plus agir)"
            isChecked = prefs.getBoolean("keep_screen", true)
        }
        box.addView(screenCheck)
        val newTokenCheck = CheckBox(this).apply { text = "Générer un nouveau jeton secret (déconnecte Claude, il faudra recopier l'adresse)" }
        box.addView(newTokenCheck)

        AlertDialog.Builder(this)
            .setTitle("Réglages")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Enregistrer") { _, _ ->
                val desktopChanged = desktopCheck.isChecked != prefs.getBoolean("desktop", true)
                val port = portEdit.text.toString().toIntOrNull()?.coerceIn(1024, 65535) ?: 8765
                val restart = port != prefs.getInt("port", 8765) || newTokenCheck.isChecked
                val e = prefs.edit()
                    .putString("device_name", nameEdit.text.toString().trim())
                    .putInt("port", port)
                    .putBoolean("desktop", desktopCheck.isChecked)
                    .putBoolean("keep_screen", screenCheck.isChecked)
                if (newTokenCheck.isChecked) e.putString("token", newToken())
                e.apply()
                if (screenCheck.isChecked) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (desktopChanged) { applyUserAgent(); web.reload() }
                server.token = token()
                if (restart) startServer() else refreshAddress()
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
    override suspend fun showMessage(text: String) = withContext(Dispatchers.Main) {
        log(LogKind.CLAUDE, text)
        if (body.visibility != View.VISIBLE) togglePanel()
        Toast.makeText(this@MainActivity, text, Toast.LENGTH_LONG).show()
    }

    // ----------------------------------------------------------------- cycle de vie

    override fun onResume() {
        super.onResume()
        if (::server.isInitialized) refreshAddress()
    }

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
        server.stop()
        scope.cancel()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_FILE = 42
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
