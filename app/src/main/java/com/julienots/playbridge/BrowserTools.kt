package com.julienots.playbridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Résultat d'un outil : texte, et éventuellement une capture JPEG en base64. */
data class ToolOutput(val text: String, val imageB64: String? = null, val isError: Boolean = false) {
    /** Contenu MCP : [{type:text}, {type:image}] */
    fun toMcpContent(): JSONArray {
        val arr = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        if (imageB64 != null) arr.put(JSONObject().put("type", "image").put("data", imageB64).put("mimeType", "image/jpeg"))
        return arr
    }
}

class BrowserTools(
    private val web: WebController,
    private val ui: Ui,
) : ToolHost {
    /** Actions de l'interface que les outils peuvent demander. */
    interface Ui {
        suspend fun loadUrl(url: String)
        suspend fun goBack(): Boolean
        suspend fun goForward(): Boolean
        suspend fun reload()
        suspend fun showMessage(text: String)
        val homeUrl: String
    }

    override val definitions: List<JSONObject> get() = DEFINITIONS

    // ------------------------------------------------------------ exécution

    override suspend fun run(name: String, input: Map<String, Any?>): ToolOutput = try {
        when (name) {
            "screenshot" -> screenshot(input.int("quality") ?: 60)
            "read_page" -> readPage(input.bool("all") ?: false, input.int("max_text") ?: 2500)
            "click" -> click(input)
            "type_text" -> typeText(input)
            "select_option" -> selectOption(input)
            "press_key" -> pressKey(input.str("key") ?: "")
            "scroll" -> scroll(input)
            "navigate" -> navigate(input)
            "wait" -> waitFor(input)
            "run_js" -> runJs(input.str("code") ?: "")
            "message" -> { ui.showMessage(input.str("text") ?: ""); ToolOutput("Message affiché à l'utilisateur.") }
            "status" -> status()
            else -> ToolOutput("Outil inconnu : $name", isError = true)
        }
    } catch (e: WebController.JsError) {
        ToolOutput("Erreur JavaScript : ${e.message}", isError = true)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolOutput("Erreur : ${e.javaClass.simpleName}: ${e.message}", isError = true)
    }

    private suspend fun screenshot(quality: Int): ToolOutput {
        val img = web.screenshotJpegBase64(quality)
        return ToolOutput(
            "Capture ${web.imageWidth}x${web.imageHeight} px — ${web.currentTitle()} — ${web.currentUrl()}",
            img,
        )
    }

    private suspend fun readPage(all: Boolean, maxText: Int): ToolOutput {
        val res = web.js(
            """
            const c=window.__cb; const out=[]; c.els=[]; const seen=new Set();
            for(const el of c.interactive()){
              if(!c.visible(el)) continue;
              const inv=c.inView(el); if(!${all} && !inv) continue;
              const r=el.getBoundingClientRect(); const lab=c.label(el);
              const key=Math.round(r.left)+','+Math.round(r.top)+','+Math.round(r.width)+','+lab;
              if(seen.has(key)) continue; seen.add(key);
              const i=c.els.length; c.els.push(el);
              const p=c.toImg(r.left+r.width/2, r.top+r.height/2);
              let extra='';
              if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){
                const t=(el.type||'').toLowerCase();
                if(t==='checkbox'||t==='radio') extra+=el.checked?' [coché]':' [non coché]';
                else if(t==='password') extra+=el.value?' (rempli)':' (vide)';
                else if(el.value) extra+=' value="'+c.clean(el.value).slice(0,80)+'"';
                if(el.placeholder && !lab.includes(el.placeholder)) extra+=' placeholder="'+el.placeholder.slice(0,60)+'"';
              }
              if(el.tagName==='SELECT'){ const o=el.options[el.selectedIndex]; extra+=' choisi="'+(o?c.clean(o.text):'')+'" options=['+Array.from(el.options).slice(0,15).map(o=>c.clean(o.text)).join(' / ')+']'; }
              const ac=el.getAttribute('aria-checked'); if(ac) extra+=ac==='true'?' [coché]':' [non coché]';
              if(el.getAttribute('aria-selected')==='true') extra+=' [sélectionné]';
              const ae=el.getAttribute('aria-expanded'); if(ae) extra+=ae==='true'?' [ouvert]':' [fermé]';
              if(el.disabled||el.getAttribute('aria-disabled')==='true') extra+=' [désactivé]';
              if(document.activeElement===el) extra+=' [focus]';
              out.push('['+i+'] '+c.kind(el)+' "'+lab+'"'+extra+' @('+p.x+','+p.y+')'+(inv?'':' (hors écran)'));
              if(c.els.length>=200) break;
            }
            const v=c.vv();
            const txt=c.clean(document.body?document.body.innerText:'').slice(0, $maxText);
            return {items: out, text: txt, scrollY: Math.round(scrollY), scrollH: Math.round(document.documentElement.scrollHeight), vh: Math.round(v.h)};
            """.trimIndent()
        ) as JSONObject
        val items = res.getJSONArray("items")
        val sb = StringBuilder()
        sb.append("URL : ").append(web.currentUrl()).append('\n')
        sb.append("Titre : ").append(web.currentTitle()).append('\n')
        sb.append("Capture : ${web.imageWidth}x${web.imageHeight} px. Défilement ${res.optInt("scrollY")}/${res.optInt("scrollH")} (hauteur visible ${res.optInt("vh")}).\n\n")
        sb.append("Éléments interactifs (${items.length()}) :\n")
        for (i in 0 until items.length()) sb.append(items.getString(i)).append('\n')
        sb.append("\nTexte visible :\n").append(res.optString("text"))
        return ToolOutput(sb.toString())
    }

    private suspend fun pointFor(input: Map<String, Any?>): JSONObject? {
        val index = input.int("index")
        val text = input.str("text")
        val selector = input.str("selector")
        val code = when {
            index != null ->
                "const el=window.__cb.els[$index]; if(!el||!el.isConnected) throw new Error('Élément $index introuvable : relancez read_page'); return await window.__cb.point(el);"
            !text.isNullOrBlank() ->
                "const el=window.__cb.findByText(${JSONObject.quote(text)}); if(!el) throw new Error('Aucun élément visible avec ce texte'); return await window.__cb.point(el);"
            !selector.isNullOrBlank() ->
                "const el=document.querySelector(${JSONObject.quote(selector)}); if(!el) throw new Error('Aucun élément pour ce sélecteur'); return await window.__cb.point(el);"
            else -> return null
        }
        return web.js(code) as JSONObject
    }

    private suspend fun click(input: Map<String, Any?>): ToolOutput {
        val long = input.bool("long") ?: false
        web.fileChooserOpened = false
        val urlBefore = web.currentUrl()
        val desc: String
        val p = pointFor(input)
        if (p != null) {
            web.tapView(p.getDouble("vx").toFloat(), p.getDouble("vy").toFloat(), long)
            desc = "Touché ${p.optString("kind")} \"${p.optString("label")}\"" +
                (if (p.optString("covered").isNotEmpty()) " (attention : un autre élément le recouvrait : \"${p.optString("covered")}\")" else "")
        } else {
            val x = input.num("x") ?: return ToolOutput("Donnez index, text, selector ou x/y.", isError = true)
            val y = input.num("y") ?: return ToolOutput("Donnez aussi y.", isError = true)
            web.tapImage(x.toFloat(), y.toFloat(), long)
            desc = "Touché en ($x, $y)"
        }
        delay(900)
        val sb = StringBuilder(desc)
        val urlAfter = web.currentUrl()
        if (urlAfter != urlBefore) sb.append("\nNouvelle page : ").append(urlAfter)
        if (web.fileChooserOpened) sb.append("\nUn sélecteur de fichiers s'est ouvert sur le téléphone : l'utilisateur doit choisir le fichier lui-même. Prévenez-le puis utilisez wait.")
        return ToolOutput(sb.toString())
    }

    private suspend fun typeText(input: Map<String, Any?>): ToolOutput {
        val text = input.str("text") ?: return ToolOutput("text manquant", isError = true)
        val clear = input.bool("clear") ?: false
        if (input.int("index") != null) {
            val p = pointFor(input)!!
            web.tapView(p.getDouble("vx").toFloat(), p.getDouble("vy").toFloat())
            delay(350)
        }
        val res = web.js(
            """
            const c=window.__cb; const el=c.deepActive(); const T=${JSONObject.quote(text)};
            if(!el || !(el.isContentEditable || el.tagName==='INPUT' || el.tagName==='TEXTAREA'))
              throw new Error('Aucun champ de saisie n\'a le focus : touchez d\'abord le champ (type_text avec index).');
            if((el.type||'').toLowerCase()==='password') throw new Error('Champ mot de passe : c\'est à l\'utilisateur de le remplir.');
            if(${clear}){ if(el.select) el.select(); else document.execCommand('selectAll'); }
            let ok=false; try{ ok=document.execCommand('insertText', false, T); }catch(e){}
            if(!ok){
              if(el.isContentEditable){ if(${clear}) el.textContent=''; el.textContent+=T; }
              else { const proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
                const set=Object.getOwnPropertyDescriptor(proto,'value').set; set.call(el, (${clear}?'':el.value)+T); }
              el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true}));
            }
            return {label:c.label(el), value: el.isContentEditable? c.clean(el.innerText).slice(0,200) : String(el.value).slice(0,200)};
            """.trimIndent()
        ) as JSONObject
        if (input.bool("submit") == true) { delay(150); web.pressKey("enter"); delay(800) }
        return ToolOutput("Écrit dans \"${res.optString("label")}\". Contenu actuel : \"${res.optString("value")}\"")
    }

    private suspend fun selectOption(input: Map<String, Any?>): ToolOutput {
        val index = input.int("index") ?: return ToolOutput("index manquant", isError = true)
        val option = input.str("option") ?: return ToolOutput("option manquante", isError = true)
        val res = web.js(
            """
            const el=window.__cb.els[$index]; if(!el||el.tagName!=='SELECT') throw new Error('L\'élément $index n\'est pas un <select>');
            const q=${JSONObject.quote(option)}.toLowerCase();
            const o=Array.from(el.options).find(o=>o.text.trim().toLowerCase()===q||o.value.toLowerCase()===q) || Array.from(el.options).find(o=>o.text.toLowerCase().includes(q));
            if(!o) throw new Error('Option introuvable');
            el.value=o.value; el.dispatchEvent(new Event('input',{bubbles:true})); el.dispatchEvent(new Event('change',{bubbles:true}));
            return o.text.trim();
            """.trimIndent()
        )
        return ToolOutput("Option choisie : $res")
    }

    private suspend fun pressKey(key: String): ToolOutput {
        val ok = web.pressKey(key.replace(" ", ""))
        if (!ok) return ToolOutput("Touche inconnue : $key", isError = true)
        delay(500)
        return ToolOutput("Touche $key appuyée.")
    }

    private suspend fun scroll(input: Map<String, Any?>): ToolOutput {
        val dir = input.str("direction") ?: "down"
        val s = web.scale
        val amountView = ((input.num("amount") ?: 500.0) / s).toFloat()
        var vx: Float
        var vy: Float
        val idx = input.int("index")
        if (idx != null) {
            val p = pointFor(mapOf("index" to idx))!!
            vx = p.getDouble("vx").toFloat(); vy = p.getDouble("vy").toFloat()
        } else {
            vx = ((input.num("x") ?: (web.imageWidth / 2.0)) / s).toFloat()
            vy = ((input.num("y") ?: (web.imageHeight / 2.0)) / s).toFloat()
        }
        val w = web.imageWidth / s
        val h = web.imageHeight / s
        // Le doigt va dans le sens opposé au contenu.
        var (x1, y1) = when (dir) {
            "down" -> vx to vy - amountView
            "up" -> vx to vy + amountView
            "right" -> vx - amountView to vy
            else -> vx + amountView to vy
        }
        // Rester dans l'écran : on décale le point de départ si besoin.
        if (y1 < 5f) { vy += 5f - y1; y1 = 5f }
        if (y1 > h - 5f) { vy -= y1 - (h - 5f); y1 = h - 5f }
        if (x1 < 5f) { vx += 5f - x1; x1 = 5f }
        if (x1 > w - 5f) { vx -= x1 - (w - 5f); x1 = w - 5f }
        vx = vx.coerceIn(5f, w - 5f); vy = vy.coerceIn(5f, h - 5f)
        web.swipeView(vx, vy, x1, y1)
        delay(500)
        val pos = runCatching { web.js("return Math.round(scrollY)+'/'+Math.round(document.documentElement.scrollHeight)", 3000) }.getOrNull()
        return ToolOutput("Défilement $dir effectué. Position de la page : $pos")
    }

    private suspend fun navigate(input: Map<String, Any?>): ToolOutput {
        val url = input.str("url")
        val action = input.str("action")
        when {
            !url.isNullOrBlank() -> ui.loadUrl(if (url.startsWith("http")) url else "https://$url")
            action == "back" -> if (!ui.goBack()) return ToolOutput("Impossible de revenir en arrière.", isError = true)
            action == "forward" -> if (!ui.goForward()) return ToolOutput("Impossible d'avancer.", isError = true)
            action == "reload" -> ui.reload()
            action == "home" -> ui.loadUrl(ui.homeUrl)
            else -> return ToolOutput("Donnez url ou action.", isError = true)
        }
        web.waitForLoad()
        return ToolOutput("Page : ${web.currentTitle()} — ${web.currentUrl()}")
    }

    private suspend fun waitFor(input: Map<String, Any?>): ToolOutput {
        val untilText = input.str("until_text")
        val untilGone = input.str("until_gone")
        val seconds = (input.num("seconds") ?: if (untilText != null || untilGone != null) 30.0 else 3.0).coerceIn(0.5, 60.0)
        if (untilText == null && untilGone == null) {
            delay((seconds * 1000).toLong())
            return ToolOutput("Attendu $seconds s.")
        }
        val end = System.currentTimeMillis() + (seconds * 1000).toLong()
        while (System.currentTimeMillis() < end) {
            val body = runCatching { web.js("return document.body ? document.body.innerText.toLowerCase() : ''", 4000) as? String }.getOrNull() ?: ""
            if (untilText != null && body.contains(untilText.lowercase())) return ToolOutput("Le texte « $untilText » est apparu.")
            if (untilGone != null && !body.contains(untilGone.lowercase())) return ToolOutput("Le texte « $untilGone » a disparu.")
            delay(1000)
        }
        return ToolOutput("Délai de $seconds s dépassé sans changement.")
    }

    private suspend fun runJs(code: String): ToolOutput {
        val v = web.js(code, 30_000)
        val s = when (v) {
            null -> "null"
            is JSONObject -> v.toString(1)
            is JSONArray -> v.toString(1)
            else -> v.toString()
        }
        return ToolOutput(if (s.length > 8000) s.take(8000) + "… (tronqué)" else s)
    }

    private suspend fun status(): ToolOutput = withContext(Dispatchers.Main) {
        ToolOutput(
            "URL : ${web.currentUrl()}\nTitre : ${web.currentTitle()}\nChargement : ${web.progress()} %\n" +
                "Capture : ${web.imageWidth}x${web.imageHeight} px\n" +
                "Sélecteur de fichiers ouvert : ${if (web.fileChooserOpened) "oui" else "non"}"
        )
    }

    companion object {
        // ------------------------------------------------------------ définitions

        val DEFINITIONS: List<JSONObject> = listOf(
            tool(
                "screenshot",
                "Capture de ce qu'affiche l'application (image JPEG). Les coordonnées de l'image sont celles à utiliser pour click/scroll avec x et y.",
                mapOf("quality" to prop("integer", "Qualité JPEG 20-90 (défaut 60)")),
            ),
            tool(
                "read_page",
                "Lit la page : URL, titre, liste numérotée des éléments interactifs visibles (boutons, liens, champs, onglets...) avec leur position (x,y dans la capture), puis le texte visible. Les numéros servent à click / type_text / select_option. all=true inclut les éléments hors écran.",
                mapOf(
                    "all" to prop("boolean", "Inclure les éléments hors écran"),
                    "max_text" to prop("integer", "Nombre max de caractères de texte (défaut 2500)"),
                ),
            ),
            tool(
                "click",
                "Touche un élément comme un vrai doigt : par index (read_page), text, selector, ou coordonnées x/y de la capture. long=true pour un appui long.",
                mapOf(
                    "index" to prop("integer", "Numéro d'élément donné par read_page"),
                    "text" to prop("string", "Texte visible de l'élément (correspondance partielle, insensible à la casse)"),
                    "selector" to prop("string", "Sélecteur CSS"),
                    "x" to prop("number", "Abscisse en pixels de la capture d'écran"),
                    "y" to prop("number", "Ordonnée en pixels de la capture d'écran"),
                    "long" to prop("boolean", "Appui long"),
                ),
            ),
            tool(
                "type_text",
                "Écrit du texte dans un champ. Si index est donné, le champ est d'abord touché pour lui donner le focus ; sinon le texte va dans le champ qui a déjà le focus. clear=true remplace le contenu existant. submit=true appuie sur Entrée ensuite.",
                mapOf(
                    "text" to prop("string", "Texte à écrire"),
                    "index" to prop("integer", "Numéro du champ donné par read_page"),
                    "clear" to prop("boolean", "Effacer le contenu avant d'écrire"),
                    "submit" to prop("boolean", "Appuyer sur Entrée après"),
                ),
                required = listOf("text"),
            ),
            tool(
                "select_option",
                "Choisit une option dans une liste déroulante HTML native <select> (index donné par read_page). Pour les menus non natifs, utilisez click.",
                mapOf(
                    "index" to prop("integer", "Numéro de l'élément <select>"),
                    "option" to prop("string", "Texte (ou valeur) de l'option à choisir"),
                ),
                required = listOf("index", "option"),
            ),
            tool(
                "press_key",
                "Appuie sur une touche : Enter, Tab, Escape, Backspace, Delete, ArrowUp, ArrowDown, ArrowLeft, ArrowRight, Space, PageUp, PageDown, Home, End.",
                mapOf("key" to prop("string", "Nom de la touche")),
                required = listOf("key"),
            ),
            tool(
                "scroll",
                "Fait défiler par un glissement du doigt : direction up|down|left|right (sens du contenu), amount en pixels de capture (défaut 500). Glisse au centre de l'écran, ou à x/y, ou sur l'élément index.",
                mapOf(
                    "direction" to JSONObject(mapOf("type" to "string", "enum" to listOf("up", "down", "left", "right"))),
                    "amount" to prop("number", "Distance en pixels de capture"),
                    "x" to prop("number", "Abscisse de départ (capture)"),
                    "y" to prop("number", "Ordonnée de départ (capture)"),
                    "index" to prop("integer", "Glisser sur cet élément (read_page)"),
                ),
                required = listOf("direction"),
            ),
            tool(
                "navigate",
                "Ouvre une adresse (url), ou action : back, forward, reload, home (Google Play Console).",
                mapOf(
                    "url" to prop("string", "Adresse à ouvrir"),
                    "action" to JSONObject(mapOf("type" to "string", "enum" to listOf("back", "forward", "reload", "home"))),
                ),
            ),
            tool(
                "wait",
                "Attend : seconds (1-60), ou jusqu'à ce qu'un texte apparaisse (until_text) ou disparaisse (until_gone) de la page, avec seconds comme délai maximum (défaut 30).",
                mapOf(
                    "seconds" to prop("number", "Durée ou délai maximum en secondes"),
                    "until_text" to prop("string", "Attendre que ce texte apparaisse"),
                    "until_gone" to prop("string", "Attendre que ce texte disparaisse"),
                ),
            ),
            tool(
                "run_js",
                "Exécute du JavaScript dans la page (corps d'une fonction async : utilisez return pour renvoyer une valeur JSON). Pour les cas que les autres outils ne couvrent pas. Lecture de préférence : ne modifiez pas la page de façon cachée à l'utilisateur.",
                mapOf("code" to prop("string", "Corps de la fonction")),
                required = listOf("code"),
            ),
            tool(
                "message",
                "Affiche un message bien visible à l'utilisateur sur l'écran de l'appareil (par exemple : « Choisis le fichier AAB dans le sélecteur », « Connecte-toi puis dis-le-moi »).",
                mapOf("text" to prop("string", "Message à afficher")),
                required = listOf("text"),
            ),
            tool(
                "status",
                "État de l'application : page affichée (URL, titre), chargement, taille de la capture, sélecteur de fichiers ouvert ou non.",
                emptyMap(),
            ),
        )

        private fun prop(type: String, description: String): JSONObject =
            JSONObject().put("type", type).put("description", description)

        private fun tool(name: String, description: String, props: Map<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
            val properties = JSONObject()
            props.forEach { (k, v) -> properties.put(k, v) }
            val schema = JSONObject().put("type", "object").put("properties", properties)
            if (required.isNotEmpty()) schema.put("required", JSONArray(required))
            return JSONObject().put("name", name).put("description", description).put("inputSchema", schema)
        }
    }
}

private fun Map<String, Any?>.num(k: String): Double? = (this[k] as? Number)?.toDouble() ?: (this[k] as? String)?.toDoubleOrNull()
private fun Map<String, Any?>.int(k: String): Int? = num(k)?.toInt()
private fun Map<String, Any?>.str(k: String): String? = this[k]?.toString()
private fun Map<String, Any?>.bool(k: String): Boolean? = this[k] as? Boolean ?: (this[k] as? String)?.toBooleanStrictOrNull()
