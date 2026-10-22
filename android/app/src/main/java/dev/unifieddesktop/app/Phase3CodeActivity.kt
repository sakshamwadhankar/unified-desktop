package dev.unifieddesktop.app

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.WindowManager
import java.io.File

/**
 * Item 7: the Phase-3 gate surface - a WebView that shows code-server's browser
 * workbench. The server (Phase3Server) binds 127.0.0.1 only with `auth: none` (B-106);
 * this activity reads the port from the app-private session file and loads the
 * workbench directly. Nothing opens the listener to the network.
 *
 * Deliberately minimal: this is the Phase-3 gate, Phase 4 embeds it in the PC session
 * host (one Exit button, locked). No menus, no file pickers, no back-history handling.
 */
class Phase3CodeActivity : Activity() {

    private var web: WebView? = null
    private var t0 = 0L

    companion object {
        /** The running instance, for the harness `cs-dom` probe broadcast. */
        @Volatile private var live: Phase3CodeActivity? = null

        /** The port read at onCreate (for the ?folder= deep link). */
        @Volatile private var sessionPort = 0

        /**
         * Run a named DOM probe in the live WebView and log the result. Named probes
         * (not raw JS) so the broadcast extra never needs shell quoting.
         */
        fun probe(name: String) {
            val js = when (name) {
                "state" -> STATE_PROBE
                "quick" -> QUICK_PROBE
                "keylog" -> KEYLOG_PROBE
                "boxes" -> BOXES_PROBE
                else -> { Phase3Native.say("CS_WEB dom FAIL unknown probe $name"); return }
            }
            val a = live
            if (a == null) { Phase3Native.say("CS_WEB dom FAIL no live activity"); return }
            a.runOnUiThread {
                a.web?.evaluateJavascript(js) { r -> Phase3Native.say("CS_WEB dom[$name] $r") }
                    ?: Phase3Native.say("CS_WEB dom FAIL web destroyed")
            }
        }

        /**
         * Inject a key chord into the WebView. Spec: `c<mods>_<key>` where the mods and
         * the final code are raw android view keycodes, e.g. `c113_59_44` = Ctrl+Shift+P,
         * `c113_47` = Ctrl+S. Delivered with ACTION_DOWN then ACTION_UP and the matching
         * META_* state, through the activity's own dispatch path (injected input, the
         * same category as Phase 2's XTEST injection: keyboard input that no hand enters).
         *
         * Why not `adb shell input keyevent`: F1 never reached the page (measured: two
         * attempts at 14:1x-14:2x, quick-input stayed closed with page focus healthy -
         * WebView does not map hardware F1 to DOM key "F1"), and `input keycombination`
         * cannot send three-key chords (Ctrl+Shift+P). The IME `input text` route stays
         * for text and single keys.
         */
        fun chord(spec: String) {
            if (live == null) { Phase3Native.say("CS_KEY FAIL no live activity"); return }
            val body = spec.removePrefix("c").removePrefix("k") // c<mods>_<key>
            val codes = body.split('_').mapNotNull { it.toIntOrNull() }
            if (codes.size < 2 || codes.any { it <= 0 || it > 1000 }) {
                Phase3Native.say("CS_KEY FAIL bad spec $spec"); return
            }
            var meta = 0
            for (m in codes.dropLast(1)) {
                meta = meta or when (m) {
                    113, 114 -> android.view.KeyEvent.META_CTRL_LEFT_ON or android.view.KeyEvent.META_CTRL_ON
                    59, 60 -> android.view.KeyEvent.META_SHIFT_LEFT_ON or android.view.KeyEvent.META_SHIFT_ON
                    57, 58 -> android.view.KeyEvent.META_ALT_LEFT_ON or android.view.KeyEvent.META_ALT_ON
                    else -> 0
                }
            }
            val keyCode = codes.last()
            Phase3Native.say("CS_KEY spec=$spec codes=$codes meta=0x${meta.toString(16)}")
            live?.runOnUiThread {
                val a = live ?: return@runOnUiThread
                // Physical keyboards deliver the modifier as its own key event before the
                // chorded key; mirror that exactly. Log every dispatch return value: a
                // false return means the window/view chain refused it outright.
                val web = a.web
                if (web == null) { Phase3Native.say("CS_KEY FAIL web gone"); return@runOnUiThread }
                var route = ""
                for ((i, m) in codes.dropLast(1).withIndex()) {
                    val isMod = m == 113 || m == 114 || m == 59 || m == 60 || m == 57 || m == 58
                    if (isMod) {
                        val d = android.view.KeyEvent(0, System.currentTimeMillis() + i, android.view.KeyEvent.ACTION_DOWN, m, 0, 0)
                        route += "modDown(" + a.dispatchKeyEvent(d) + ")"
                    }
                }
                val down = android.view.KeyEvent(0, System.currentTimeMillis(), android.view.KeyEvent.ACTION_DOWN, keyCode, 0, meta)
                route += " keyDown(" + a.dispatchKeyEvent(down) + ")"
                web.postDelayed({
                    val up = android.view.KeyEvent(0, System.currentTimeMillis(), android.view.KeyEvent.ACTION_UP, keyCode, 0, meta)
                    var r2 = "keyUp(" + a.dispatchKeyEvent(up) + ")"
                    for (m in codes.dropLast(1)) {
                        val isMod = m == 113 || m == 114 || m == 59 || m == 60 || m == 57 || m == 58
                        if (isMod) r2 += " modUp(" + a.dispatchKeyEvent(
                            android.view.KeyEvent(0, System.currentTimeMillis(), android.view.KeyEvent.ACTION_UP, m, 0, 0)
                        ) + ")"
                    }
                    Phase3Native.say("CS_KEY route=" + route.trim() + "|" + r2)
                }, 40)
            }
        }

        /**
         * Keyboard chord path (see chord()) is measured-dead for the page (B-039), so
         * folder openings take code-server's documented deep link instead:
         * navigation to `http://127.0.0.1:<port>/?folder=<path>` after login is the same
         * "VS Code opens a folder" operation the workbench does for a real user.
         */
        fun goFolder(path: String) {
            val a = live
            if (a == null) { Phase3Native.say("CS_WEB gofolder FAIL no live activity"); return }
            if (Phase3CodeActivity.sessionPort == 0) { Phase3Native.say("CS_WEB gofolder FAIL no port"); return }
            a.runOnUiThread {
                val web = a.web ?: return@runOnUiThread
                val enc = android.net.Uri.encode(path, "/")
                val url = "http://127.0.0.1:" + Phase3CodeActivity.sessionPort + "/?folder=" + enc
                Phase3Native.say("CS_WEB gofolder $url")
                web.loadUrl(url)
            }
        }

        /** Full workbench state: what is open, what dialogs/inputs are visible. */
        private val STATE_PROBE = """(function(){
var q=document.querySelector('.quick-input-widget');
var rows=document.querySelectorAll('.quick-input-list .monaco-highlighted-label');
var vis=function(e){return e&&e.offsetParent!==null};
var inputs=[];var all=document.querySelectorAll('input,textarea');
for(var i=0;i<all.length;i++){if(vis(all[i]))inputs.push({ph:all[i].placeholder||'',v:all[i].value,ro:all[i].readOnly,cls:all[i].className.slice(0,40)})}
return JSON.stringify({title:document.title,url:location.href,qi:vis(q),qiRows:rows.length,pos:[].map.call([].slice.call(rows,0,4),function(e){return e.innerText}).join('|'),inputs:inputs.slice(0,4),term:!!document.querySelector('.terminal-container'),editors:document.querySelectorAll('.monaco-editor').length,dirty:!!document.querySelector('.tab.active.dirty'),listRows:document.querySelectorAll('.monaco-list-row').length,bodyChars:document.body?document.body.innerText.length:-1});})()"""

        /** Just the quick-input (palette / picker): visibility, rows, highlighted entry. */
        private val QUICK_PROBE = """(function(){
var q=document.querySelector('.quick-input-widget');
var vis=q&&q.offsetParent!==null;
var rows=[].slice.call(document.querySelectorAll('.quick-input-list .monaco-highlighted-label'),0,6).map(function(e){return e.innerText});
var curs=document.querySelector('.quick-input-widget .monaco-inputbox .input');
var f=document.activeElement;
return JSON.stringify({qi:vis,rows:rows,inputValue:curs?curs.value:null,active:f?f.className.slice(0,30):null});})()"""

        /**
         * Screen coordinates of the UI elements the injected-input flow needs, measured
         * with getBoundingClientRect (CSS px; device px = CSS x 3, measured wm density
         * 480 -> dpr 3, and the CSS viewport came out 997x452 which matches the
         * landscape 2992x1344 frame divided by 3). Covers the menu-bar buttons, the
         * welcome-page "open folder" entries, open *menu items* after a menu tap, and
         * quick-input rows, plus the raw scale so the conversion is measured, not
         * assumed.
         */
        private val BOXES_PROBE = """(function(){
function box(e){var r=e.getBoundingClientRect();return {x:Math.round(r.x+r.width/2),y:Math.round(r.y+r.height/2),w:Math.round(r.width),h:Math.round(r.height)}}
var out={vp:{dpr:window.devicePixelRatio,w:window.innerWidth,h:window.innerHeight},menubar:[],menuitems:[],welcome:[],quick:[],status:[]};
var vis=function(e){return e&&e.offsetParent!==null};
var mb=document.querySelectorAll('.menubar .menubar-menu-button');
for(var i=0;i<mb.length;i++){if(vis(mb[i]))out.menubar.push({t:mb[i].getAttribute('aria-label')||mb[i].innerText.slice(0,15),...box(mb[i])})}
var mi=document.querySelectorAll('[role=menuitem],.action-menu-item,.monaco-menu .action-item');
for(var i=0;i<mi.length&&out.menuitems.length<26;i++){if(!vis(mi[i]))continue;var t=(mi[i].getAttribute('aria-label')||mi[i].innerText||'').replace(/\\s+/g,' ').slice(0,44);if(t)out.menuitems.push({t:t,...box(mi[i])})}
var q=document.querySelector('.quick-input-widget');
if(q){var r=q.getBoundingClientRect();if(r.width>0)out.quick.push({t:'widget',...box(q)});var qi=document.querySelectorAll('.quick-input-list .monaco-list-row,.quick-input-list .monaco-highlighted-label,.quick-input-widget input,.quick-input-widget button,.context-view .monaco-menu .action-item');
for(var i=0;i<qi.length&&out.quick.length<13;i++){var rr=qi[i].getBoundingClientRect();if(rr.width<=0&&rr.height<=0)continue;out.quick.push({t:(qi[i].innerText||qi[i].placeholder||qi[i].getAttribute('aria-label')||'').toString().slice(0,40),ro:qi[i].readOnly===true?1:0,x:Math.round(rr.x+rr.width/2),y:Math.round(rr.y+rr.height/2),w:Math.round(rr.width),h:Math.round(rr.height)})}}
var wb=document.querySelectorAll('button[title],a.monaco-button,a[role=button] .codicon-folder,button[role=button]');
for(var i=0;i<wb.length&&out.welcome.length<10;i++){if(wb[i].offsetParent===null)continue;var t=(wb[i].title||wb[i].getAttribute('aria-label')||wb[i].innerText||'').slice(0,40);if(/open|folder/i.test(t))out.welcome.push({t:t,...box(wb[i])})}
var st=document.querySelectorAll('.part.statusbar > .statusbar-item, .part.statusbar .statusbar-item');
for(var i=0;i<st.length&&out.status.length<14;i++){var t=(st[i].getAttribute('aria-label')||st[i].innerText||'').slice(0,25);if(t&&st[i].offsetParent!==null)out.status.push({t:t,...box(st[i])})}
var ed=document.querySelector('.monaco-editor');
if(ed){var er=ed.getBoundingClientRect();out.editor={x:Math.round(er.x+er.width/2),y:Math.round(er.y+er.height/2),x2:Math.round(er.right),w:Math.round(er.width),h:Math.round(er.height)}}
var vl=document.querySelectorAll('.monaco-editor .view-line');
out.viewlines=[];for(var i=0;i<vl.length&&out.viewlines.length<4;i++){var lr=vl[i].getBoundingClientRect();if(vl[i].offsetParent===null)continue;out.viewlines.push({t:(vl[i].innerText||'').slice(0,30),y:Math.round(lr.y+lr.height/2),x1:Math.round(lr.x),x2:Math.round(lr.right),h:Math.round(lr.height)})}
var tc=document.querySelector('.terminal-container');
if(tc){var tr=tc.getBoundingClientRect();out.termbox={x:Math.round(tr.x+tr.width/2),y:Math.round(tr.y+tr.height/2),w:Math.round(tr.width),h:Math.round(tr.height)}}
var ab=document.querySelectorAll('.activitybar .action-item');
out.actbar=[];for(var i=0;i<ab.length&&out.actbar.length<10;i++){if(!vis(ab[i]))continue;var t=(ab[i].title||ab[i].getAttribute('aria-label')||ab[i].innerText||'').slice(0,30);out.actbar.push({t:t,...box(ab[i])})}
var exs=document.querySelector('.extensions .monaco-inputbox input, .part.sidebar .monaco-inputbox input');
if(exs&&vis(exs)){var xr=exs.getBoundingClientRect();out.exsearch={x:Math.round(xr.x+xr.width/2),y:Math.round(xr.y+xr.height/2),w:Math.round(xr.width),h:Math.round(xr.height)}}
var ex=document.querySelectorAll('.extensions .extension-list-item, .part.sidebar .extension-list-item');
out.ext=[];for(var i=0;i<ex.length&&out.ext.length<10;i++){if(!vis(ex[i]))continue;var nm=ex[i].querySelector('.name');var bn=ex[i].querySelector('.monaco-button');out.ext.push({t:(nm?nm.innerText:'').slice(0,30),btn:(bn?(bn.title||bn.innerText||'').slice(0,15):''),...box(ex[i])})}
return JSON.stringify(out);})()"""

        /**
         * Installs (once) a window-level keydown recorder and returns the last events.
         * Turns "did the chord reach the page?" from a guess into a measurement: if the
         * key shows up here with the right key/code but the palette stayed closed, the
         * failure is on VS Code's binding side; if it never shows up, it is delivery.
         */
        private val KEYLOG_PROBE = """(function(){
if(!window.__udk){window.__udk=[];window.addEventListener('keydown',function(e){window.__udk.push({key:e.key,code:e.code,ctrl:e.ctrlKey,shift:e.shiftKey,alt:e.altKey,meta:e.metaKey,def:e.defaultPrevented});if(window.__udk.length>6)window.__udk.shift()})}
return JSON.stringify({n:window.__udk.length,events:window.__udk});})()"""
    }

    /** DOM-level proof that the VS Code workbench (not just any page) got rendered. */
    private val domProbe =
        "(function(){var w=document.querySelector('.monaco-workbench');" +
        "var b=document.querySelector('.part.activitybar,.part.titlebar,.part.sidebar');" +
        "return JSON.stringify({title:document.title,ready:document.readyState," +
        "workbench:!!w,parts:!!b,bodyChars:document.body?document.body.innerText.length:-1});})()"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A PC session keeps the phone awake; Phase 4's session host owns the real
        // foreground-service lifetime, this is the Phase-3 gate surface only.
        live = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val session = readSession()
        if (session == null) {
            Phase3Native.say("CS_WEB FAIL no session file - run cs-start first")
            finish()
            return
        }
        val (port, _) = session
        sessionPort = port

        val v = WebView(this)
        web = v
        setContentView(v)
        v.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        // Accept hardware input: without explicit focusable+touchmode+requestFocus the
        // window's key path never delivered events into the page (measured 14:1x-14:4x,
        // B-039); taps always worked.
        v.isFocusable = true
        v.isFocusableInTouchMode = true
        v.requestFocus()
        v.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Phase3Native.say("CS_WEB loaded url=$url ms=${System.currentTimeMillis() - t0} " +
                        "title=${view.title?.take(80)}")
                // Re-focus on every finished page (the ?folder= navigation reloads too):
                view.isFocusable = true
                view.isFocusableInTouchMode = true
                view.requestFocus()
                if (url.endsWith("/")) {
                    // The workbench bootstraps asynchronously; probe it at 1/3/6 s so the
                    // log records when `.monaco-workbench` and a `role=application` node
                    // exist (a DOM-level proof, independent of the screenshot).
                    for (delay in longArrayOf(1_000, 3_000, 6_000)) {
                        view.postDelayed({
                            try {
                                view.evaluateJavascript(domProbe) { r ->
                                    Phase3Native.say("CS_WEB dom+${delay}ms $r")
                                }
                            } catch (_: Throwable) { }
                        }, delay)
                    }
                }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        // B-108: log in from the app and give the WebView the session cookie before the
        // first load. Never a password in a URL; never the cookie in the log.
        val cookie = Phase3Server.cookieFor(this, port) ?: Phase3Server.login(port, session.second)
        if (cookie != null) {
            CookieManager.getInstance().setCookie(
                "http://127.0.0.1:$port/",
                "code-server-session=$cookie; Path=/; HttpOnly"
            )
            CookieManager.getInstance().flush()
        }
        t0 = System.currentTimeMillis()
        Phase3Native.say("CS_WEB load http://127.0.0.1:$port/ (password auth, cookie=${cookie != null})")
        v.loadUrl("http://127.0.0.1:$port/")
    }

    override fun onDestroy() {
        if (live === this) live = null
        web?.destroy()
        web = null
        super.onDestroy()
    }

    /** (port, password) from Phase3Server.sessionFile, or null if there is no session. */
    private fun readSession(): Pair<Int, String>? {
        val f: File = Phase3Server.sessionFile(this)
        if (!f.isFile) return null
        var port = -1
        var pw = ""
        f.useLines { ls ->
            for (l in ls) {
                when {
                    l.startsWith("port=") -> port = l.substringAfter('=').trim().toIntOrNull() ?: -1
                    l.startsWith("password=") -> pw = l.substringAfter('=').trim()
                }
            }
        }
        return if (port > 0 && pw.isNotEmpty()) port to pw else null
    }
}
