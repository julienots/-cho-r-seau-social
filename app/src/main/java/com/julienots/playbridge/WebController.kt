package com.julienots.playbridge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pilote la WebView comme un doigt : captures d'écran, lecture de la page,
 * touchers, glissements, touches clavier et JavaScript.
 *
 * Toutes les coordonnées échangées avec Claude sont en pixels de la capture
 * d'écran (la vue réduite d'un facteur [scale]).
 */
class WebController(
    private val web: WebView,
    private val window: Window,
    private val tapIndicator: TapIndicatorView,
) {
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    /** Mis à true par l'activité quand la page ouvre un sélecteur de fichiers. */
    @Volatile var fileChooserOpened = false

    private inner class Bridge {
        @JavascriptInterface
        fun result(id: String, json: String) {
            pending.remove(id)?.complete(json)
        }
    }

    init {
        web.addJavascriptInterface(Bridge(), "__cbBridge")
    }

    /** Facteur vue → capture (la capture fait au plus 1280 px sur son grand côté). */
    val scale: Float
        get() {
            val w = max(1, web.width)
            val h = max(1, web.height)
            return min(1f, 1280f / max(w, h))
        }

    val imageWidth get() = (web.width * scale).roundToInt()
    val imageHeight get() = (web.height * scale).roundToInt()

    // ---------------------------------------------------------------- JavaScript

    class JsError(msg: String) : Exception(msg)

    /**
     * Exécute [body] comme corps d'une fonction async dans la page et renvoie
     * la valeur retournée (JSON). Le préambule définit les aides window.__cb.
     */
    suspend fun js(body: String, timeoutMs: Long = 20_000): Any? {
        val id = UUID.randomUUID().toString()
        val d = CompletableDeferred<String>()
        pending[id] = d
        val vw = web.width
        val s = scale
        val script = """
            (function(){
              window.__cbVW=$vw; window.__cbS=$s;
              $JS_PRELUDE
              (async()=>{ $body })().then(r=>{
                let out; try { out = JSON.stringify({ok:true, v: (r===undefined?null:r)}); }
                catch(e){ out = JSON.stringify({ok:true, v: String(r)}); }
                __cbBridge.result('$id', out);
              }).catch(e=>__cbBridge.result('$id', JSON.stringify({ok:false, e: String((e&&e.stack)||e)})));
            })(); void 0;
        """.trimIndent()
        withContext(Dispatchers.Main) { web.evaluateJavascript(script, null) }
        val raw = withTimeoutOrNull(timeoutMs) { d.await() }
        pending.remove(id)
        if (raw == null) throw JsError("Pas de réponse de la page (délai dépassé ou page en cours de chargement).")
        val o = JSONObject(raw)
        if (!o.optBoolean("ok")) throw JsError(o.optString("e"))
        return if (o.isNull("v")) null else o.get("v")
    }

    // ---------------------------------------------------------------- capture

    suspend fun screenshotJpegBase64(quality: Int): String {
        val bmp = withContext(Dispatchers.Main) { captureView() }
        val w = imageWidth.coerceAtLeast(1)
        val h = imageHeight.coerceAtLeast(1)
        return withContext(Dispatchers.Default) {
            val scaled = if (w != bmp.width || h != bmp.height) Bitmap.createScaledBitmap(bmp, w, h, true) else bmp
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 90), out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        }
    }

    private suspend fun captureView(): Bitmap {
        val w = max(1, web.width)
        val h = max(1, web.height)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val loc = IntArray(2)
        web.getLocationInWindow(loc)
        val ok = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(
                    window,
                    android.graphics.Rect(loc[0], loc[1], loc[0] + w, loc[1] + h),
                    bmp,
                    { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) },
                    Handler(Looper.getMainLooper()),
                )
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(false)
            }
        }
        if (!ok) {
            val c = Canvas(bmp)
            web.draw(c)
        }
        return bmp
    }

    // ---------------------------------------------------------------- doigt

    /** Toucher aux coordonnées de la vue (pixels réels). */
    suspend fun tapView(vx: Float, vy: Float, long: Boolean = false) = withContext(Dispatchers.Main) {
        tapIndicator.show(vx, vy)
        val t0 = SystemClock.uptimeMillis()
        dispatch(t0, t0, MotionEvent.ACTION_DOWN, vx, vy)
        delay(if (long) 800 else 70)
        val t1 = SystemClock.uptimeMillis()
        dispatch(t0, t1, MotionEvent.ACTION_UP, vx, vy)
    }

    suspend fun tapImage(x: Float, y: Float, long: Boolean = false) = tapView(x / scale, y / scale, long)

    /** Glissement du doigt de (x0,y0) à (x1,y1) en pixels de vue. */
    suspend fun swipeView(x0: Float, y0: Float, x1: Float, y1: Float) = withContext(Dispatchers.Main) {
        tapIndicator.show(x0, y0)
        val t0 = SystemClock.uptimeMillis()
        dispatch(t0, t0, MotionEvent.ACTION_DOWN, x0, y0)
        val steps = 14
        for (i in 1..steps) {
            delay(18)
            val f = i.toFloat() / steps
            dispatch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)
        }
        delay(120) // immobile avant de relâcher : pas d'effet « lancer »
        dispatch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x1, y1)
        dispatch(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x1, y1)
    }

    private fun dispatch(down: Long, time: Long, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(down, time, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    suspend fun pressKey(name: String): Boolean = withContext(Dispatchers.Main) {
        val code = KEYS[name.lowercase()] ?: return@withContext false
        web.requestFocus()
        val t = SystemClock.uptimeMillis()
        web.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        web.dispatchKeyEvent(KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
        true
    }

    // ---------------------------------------------------------------- navigation

    suspend fun currentUrl(): String = withContext(Dispatchers.Main) { web.url ?: "" }
    suspend fun currentTitle(): String = withContext(Dispatchers.Main) { web.title ?: "" }
    suspend fun progress(): Int = withContext(Dispatchers.Main) { web.progress }

    suspend fun waitForLoad(maxMs: Long = 15_000) {
        val start = SystemClock.uptimeMillis()
        delay(400)
        while (SystemClock.uptimeMillis() - start < maxMs) {
            if (progress() >= 100) {
                val ready = runCatching { js("return document.readyState", 3000) }.getOrNull()
                if (ready == "complete") break
            }
            delay(300)
        }
        delay(500)
    }

    companion object {
        val KEYS = mapOf(
            "enter" to KeyEvent.KEYCODE_ENTER,
            "tab" to KeyEvent.KEYCODE_TAB,
            "escape" to KeyEvent.KEYCODE_ESCAPE,
            "backspace" to KeyEvent.KEYCODE_DEL,
            "delete" to KeyEvent.KEYCODE_FORWARD_DEL,
            "arrowup" to KeyEvent.KEYCODE_DPAD_UP,
            "arrowdown" to KeyEvent.KEYCODE_DPAD_DOWN,
            "arrowleft" to KeyEvent.KEYCODE_DPAD_LEFT,
            "arrowright" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "space" to KeyEvent.KEYCODE_SPACE,
            "pageup" to KeyEvent.KEYCODE_PAGE_UP,
            "pagedown" to KeyEvent.KEYCODE_PAGE_DOWN,
            "home" to KeyEvent.KEYCODE_MOVE_HOME,
            "end" to KeyEvent.KEYCODE_MOVE_END,
        )

        /** Aides injectées à chaque appel (la page peut avoir changé). */
        private val JS_PRELUDE = """
            if(!window.__cb){ window.__cb = {
              els: [],
              vv(){ const v=window.visualViewport; return v?{w:v.width,h:v.height,x:v.offsetLeft,y:v.offsetTop}:{w:innerWidth,h:innerHeight,x:0,y:0}; },
              k(){ return window.__cbVW/this.vv().w; },
              toView(cx,cy){ const v=this.vv(), k=this.k(); return {vx:(cx-v.x)*k, vy:(cy-v.y)*k}; },
              toImg(cx,cy){ const p=this.toView(cx,cy); return {x:Math.round(p.vx*window.__cbS), y:Math.round(p.vy*window.__cbS)}; },
              fromImg(x,y){ const v=this.vv(), k=this.k(); return {cx:x/window.__cbS/k+v.x, cy:y/window.__cbS/k+v.y}; },
              visible(el){ const r=el.getBoundingClientRect(); if(r.width<1||r.height<1) return false;
                const s=getComputedStyle(el); return !(s.visibility==='hidden'||s.display==='none'||parseFloat(s.opacity)===0); },
              inView(el){ const r=el.getBoundingClientRect(), v=this.vv(); return r.right>v.x && r.bottom>v.y && r.left<v.x+v.w && r.top<v.y+v.h; },
              walk(root,out){ for(const el of root.querySelectorAll('*')){ out.push(el); if(el.shadowRoot) this.walk(el.shadowRoot,out);} return out; },
              SEL: 'a[href],button,input:not([type=hidden]),textarea,select,summary,[role=button],[role=link],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],[role=checkbox],[role=radio],[role=option],[role=combobox],[role=switch],[role=textbox],[role=treeitem],[role=listitem][tabindex],[contenteditable=""],[contenteditable=true],[tabindex]:not([tabindex="-1"]),[onclick]',
              interactive(){ const out=[]; for(const el of this.walk(document,[])){ try{ if(el.matches(this.SEL)) out.push(el); }catch(e){} } return out; },
              clean(t){ return (t||'').replace(/\s+/g,' ').trim(); },
              label(el){
                let t=this.clean(el.getAttribute('aria-label'));
                if(!t && el.labels && el.labels[0]) t=this.clean(el.labels[0].innerText);
                const inner=this.clean(el.innerText||el.textContent);
                if(!t) t=inner; else if(inner && !t.toLowerCase().includes(inner.toLowerCase()) && inner.length<80) t+=' | '+inner;
                if(!t) t=this.clean(el.getAttribute('title')||el.getAttribute('placeholder')||el.getAttribute('alt')||el.getAttribute('name')||'');
                return t.slice(0,140);
              },
              kind(el){ const tag=el.tagName.toLowerCase(), role=el.getAttribute('role');
                if(tag==='input') return 'input['+(el.type||'text')+']';
                if(role && tag!=='a' && tag!=='button') return role; return tag; },
              center(el){ const r=el.getBoundingClientRect(); return {cx:r.left+r.width/2, cy:r.top+r.height/2}; },
              deepActive(){ let a=document.activeElement; while(a && a.shadowRoot && a.shadowRoot.activeElement) a=a.shadowRoot.activeElement; return a; },
              async point(el){
                if(!this.inView(el)){ el.scrollIntoView({block:'center', inline:'center'}); await new Promise(r=>setTimeout(r,350)); }
                const c=this.center(el); const p=this.toView(c.cx,c.cy);
                const rn=el.getRootNode(); const top=(rn&&rn.elementFromPoint?rn:document).elementFromPoint(c.cx,c.cy); let covered='';
                if(top && top!==el && !el.contains(top) && !top.contains(el)) covered=this.clean(top.innerText||top.tagName).slice(0,60);
                return {vx:p.vx, vy:p.vy, label:this.label(el), kind:this.kind(el), covered};
              },
              findByText(txt){
                const q=txt.toLowerCase(); let best=null, bestScore=1e18;
                for(const el of this.interactive()){ if(!this.visible(el)) continue; const l=this.label(el).toLowerCase(); if(!l.includes(q)) continue;
                  const s=(l===q?0:1)*1e9 + l.length*1e4 + (this.inView(el)?0:1e6); if(s<bestScore){best=el;bestScore=s;} }
                if(best) return best;
                for(const el of this.walk(document,[])){ if(!this.visible(el)) continue; const own=this.clean(el.innerText||'').toLowerCase(); if(!own.includes(q)) continue;
                  const r=el.getBoundingClientRect(); const s=r.width*r.height + (this.inView(el)?0:1e9); if(s<bestScore){best=el;bestScore=s;} }
                return best;
              }
            }; }
        """.trimIndent()
    }
}
