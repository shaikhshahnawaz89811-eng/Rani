package com.sa.aidesktop.core.browser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.net.http.SslError
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import kotlin.coroutines.resume

/**
 * Real Android WebView browser backend. DOM inspection and interaction are performed by
 * JavaScript inside the loaded page; no coordinates or fabricated DOM are used.
 *
 * A WebView is owned by a desktop browser window. Tools never create their own hidden browser.
 */
class AndroidBrowserService(
    private val context: Context,
    private val workspaceRoot: File? = null,
    private val limitsProvider: () -> BrowserLimits = { BrowserLimits() }
) {
    private val main = Handler(Looper.getMainLooper())
    private val _states = MutableStateFlow<Map<String, BrowserState>>(emptyMap())
    val states: StateFlow<Map<String, BrowserState>> = _states.asStateFlow()
    private val views = mutableMapOf<String, WebView>()
    private val pendingUploads = mutableMapOf<String, PendingUpload>()
    private val downloadManager by lazy { context.getSystemService(DownloadManager::class.java) }

    private data class PendingUpload(
        val inputRef: String,
        val file: File,
        val callback: (Array<Uri>?) -> Unit
    )

    @SuppressLint("SetJavaScriptEnabled")
    fun register(windowId: String, view: WebView) {
        main.post {
            views[windowId]?.let { if (it !== view) it.stopLoading() }
            views[windowId] = view
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.loadsImagesAutomatically = true
            view.settings.useWideViewPort = true
            view.settings.loadWithOverviewMode = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = true
            view.webViewClient = object : WebViewClient() {
                override fun onPageStarted(v: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    update(windowId) { it.copy(url = url, title = v.title.orEmpty(), loading = true, progress = 0, lastError = null, inspectionVersion = it.inspectionVersion + 1) }
                }

                override fun onPageFinished(v: WebView, url: String) {
                    update(windowId) { it.copy(url = url, title = v.title.orEmpty(), loading = false, progress = 100, lastError = null, inspectionVersion = it.inspectionVersion + 1) }
                }

                override fun onReceivedError(v: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) {
                        update(windowId) { it.copy(url = v.url.orEmpty(), title = v.title.orEmpty(), loading = false, lastError = error.description?.toString() ?: "Page load failed") }
                    }
                }
                override fun onReceivedHttpError(v: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                    if (request.isForMainFrame) {
                        update(windowId) { it.copy(url = v.url.orEmpty(), title = v.title.orEmpty(), loading = false, lastError = "HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase.orEmpty()}".trim()) }
                    }
                }

                override fun onReceivedSslError(v: WebView, handler: android.webkit.SslErrorHandler, error: SslError) {
                    handler.cancel()
                    update(windowId) { it.copy(url = v.url.orEmpty(), title = v.title.orEmpty(), loading = false, lastError = "SSL/security error: ${error.primaryError}") }
                }
            }
            view.webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(v: WebView, progress: Int) {
                    update(windowId) { it.copy(url = v.url.orEmpty(), title = v.title.orEmpty(), loading = progress < 100, progress = progress) }
                }

                override fun onReceivedTitle(v: WebView, title: String) {
                    update(windowId) { it.copy(title = title) }
                }

                override fun onShowFileChooser(
                    v: WebView,
                    filePathCallback: android.webkit.ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    val pending = pendingUploads.remove(windowId) ?: return false
                    if (!pending.file.isFile || !pending.file.canRead()) {
                        filePathCallback.onReceiveValue(null)
                        return true
                    }
                    val uri = runCatching {
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pending.file)
                    }.getOrNull()
                    if (uri == null) {
                        filePathCallback.onReceiveValue(null)
                        return true
                    }
                    filePathCallback.onReceiveValue(arrayOf(uri))
                    return true
                }
            }
            view.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                enqueueDownload(windowId, url, userAgent, contentDisposition, mimeType)
            })
            if (view.url.isNullOrBlank()) {
                view.loadUrl("https://www.google.com")
            } else {
                update(windowId) { it.copy(url = view.url.orEmpty(), title = view.title.orEmpty()) }
            }
        }
    }

    fun unregister(windowId: String) {
        main.post {
            pendingUploads.remove(windowId)
            views.remove(windowId)?.let { runCatching { it.stopLoading(); it.destroy() } }
            _states.value = _states.value - windowId
        }
    }

    suspend fun open(windowId: String, url: String): BrowserResult<Unit> {
        val normalized = normalizeUrl(url)
            ?: return BrowserResult.Failure("Invalid URL. Use an http(s) URL or a non-empty web search.")
        val loaded = onMain(windowId) {
            views[windowId]?.loadUrl(normalized) ?: return@onMain BrowserResult.Failure("Browser window is not ready.")
            BrowserResult.Success(Unit)
        }
        if (loaded is BrowserResult.Failure) return loaded
        val deadline = System.currentTimeMillis() + 30_000L
        while (System.currentTimeMillis() < deadline) {
            val state = _states.value[windowId]
            if (state?.lastError != null) {
                return BrowserResult.Failure("Navigation failed: ${state.lastError}")
            }
            if (state != null && !state.loading && state.url.isNotBlank()) {
                return BrowserResult.Success(Unit)
            }
            delay(100)
        }
        val state = _states.value[windowId]
        return BrowserResult.Failure(
            "Navigation timed out after 30 seconds. Current URL=${state?.url.orEmpty()} loading=${state?.loading == true}"
        )
    }

    suspend fun back(windowId: String) = onMain(windowId) {
        val v = views[windowId] ?: return@onMain BrowserResult.Failure("Browser window is not ready.")
        if (!v.canGoBack()) return@onMain BrowserResult.Failure("No previous page in browser history.")
        v.goBack(); BrowserResult.Success(Unit)
    }

    suspend fun forward(windowId: String) = onMain(windowId) {
        val v = views[windowId] ?: return@onMain BrowserResult.Failure("No forward page in browser history.")
        if (!v.canGoForward()) return@onMain BrowserResult.Failure("No next page in browser history.")
        v.goForward(); BrowserResult.Success(Unit)
    }

    suspend fun reload(windowId: String) = onMain(windowId) {
        val v = views[windowId] ?: return@onMain BrowserResult.Failure("Browser window is not ready.")
        v.reload(); BrowserResult.Success(Unit)
    }

    suspend fun stop(windowId: String) = onMain(windowId) {
        val v = views[windowId] ?: return@onMain BrowserResult.Failure("Browser window is not ready.")
        v.stopLoading(); BrowserResult.Success(Unit)
    }

    suspend fun inspect(windowId: String): BrowserResult<BrowserPage> {
        val raw = evaluate(windowId, inspectionScript(limitsProvider()))
        return when (raw) {
            is BrowserResult.Failure -> raw
            is BrowserResult.Success -> parsePage(raw.value, limitsProvider())
        }
    }

    suspend fun search(windowId: String, query: String): BrowserResult<String> {
        if (query.isBlank()) return BrowserResult.Failure("Search query cannot be empty.")
        val result = evaluate(windowId, """
            (function(){
              const q=${JSONObject.quote(query)};
              const body=document.body;
              if(!body) return JSON.stringify({found:false,reason:"No document body"});
              const text=body.innerText||"";
              const lower=text.toLocaleLowerCase();
              const i=lower.indexOf(q.toLocaleLowerCase());
              if(i<0) return JSON.stringify({found:false,reason:"Text not found"});
              return JSON.stringify({found:true,index:i,snippet:text.slice(Math.max(0,i-220),Math.min(text.length,i+q.length+220))});
            })()
        """.trimIndent())
        return when (result) {
            is BrowserResult.Failure -> result
            is BrowserResult.Success -> {
                val o = JSONObject(result.value)
                if (o.optBoolean("found")) BrowserResult.Success("Found at text offset ${o.optInt("index")}.\n${o.optString("snippet")}")
                else BrowserResult.Failure(o.optString("reason", "Text not found"))
            }
        }
    }

    suspend fun click(windowId: String, ref: String): BrowserResult<String> {
        val js = """
            (function(){
              const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');
              if(!e) return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});
              if(!isVisible(e)) return JSON.stringify({ok:false,reason:"Element is hidden"});
              if(e.disabled || e.getAttribute('aria-disabled')==='true') return JSON.stringify({ok:false,reason:"Element is disabled"});
              if(!isInteractable(e)) return JSON.stringify({ok:false,reason:"Element is not interactable"});
              e.focus({preventScroll:true});
              e.click();
              return JSON.stringify({ok:true,tag:e.tagName,text:(e.innerText||e.getAttribute('aria-label')||"").trim().slice(0,300)});
              function isVisible(x){const r=x.getBoundingClientRect(),s=getComputedStyle(x);return !!(r.width&&r.height&&s.visibility!=="hidden"&&s.display!=="none")}
              function isInteractable(x){return ["A","BUTTON","INPUT","TEXTAREA","SELECT","SUMMARY"].includes(x.tagName)||typeof x.onclick==="function"||x.getAttribute("role")}
            })()
        """.trimIndent()
        return actionResult(windowId, js, "click")
    }

    suspend fun focus(windowId: String, ref: String) = actionResult(windowId, """
        (function(){const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');if(!e)return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});e.focus();return JSON.stringify({ok:true});})()
    """.trimIndent(), "focus")

    suspend fun type(windowId: String, ref: String, text: String): BrowserResult<String> {
        val js = """
            (function(){
              const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');
              if(!e)return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});
              if(!isEditable(e))return JSON.stringify({ok:false,reason:"Element is not an editable input"});
              e.focus();
              const value=${JSONObject.quote(text)};
              const proto=e.tagName==="TEXTAREA"?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
              const setter=Object.getOwnPropertyDescriptor(proto,"value")?.set;
              if(setter)setter.call(e,value);else e.value=value;
              e.dispatchEvent(new InputEvent("input",{bubbles:true,inputType:"insertText",data:value}));
              e.dispatchEvent(new Event("change",{bubbles:true}));
              return JSON.stringify({ok:true,valueLength:value.length});
              function isEditable(x){return (x.tagName==="INPUT"&&!["button","submit","reset","checkbox","radio","file"].includes((x.type||"").toLowerCase()))||x.tagName==="TEXTAREA"||x.isContentEditable}
            })()
        """.trimIndent()
        return actionResult(windowId, js, "type")
    }

    suspend fun clear(windowId: String, ref: String) = type(windowId, ref, "")

    suspend fun select(windowId: String, ref: String, value: String): BrowserResult<String> {
        val js = """
            (function(){
              const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');
              if(!e)return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});
              if(e.tagName!=="SELECT")return JSON.stringify({ok:false,reason:"Element is not a select control"});
              const wanted=${JSONObject.quote(value)};
              const option=[...e.options].find(o=>o.value===wanted||o.text.trim()===wanted);
              if(!option)return JSON.stringify({ok:false,reason:"Requested option is not present"});
              e.value=option.value;e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}));
              return JSON.stringify({ok:true,value:e.value,text:option.text});
            })()
        """.trimIndent()
        return actionResult(windowId, js, "select")
    }

    suspend fun check(windowId: String, ref: String, checked: Boolean): BrowserResult<String> {
        val js = """
            (function(){
              const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');
              if(!e)return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});
              if(e.tagName!=="INPUT"||!["checkbox","radio"].includes((e.type||"").toLowerCase()))return JSON.stringify({ok:false,reason:"Element is not a checkbox/radio"});
              if(e.disabled)return JSON.stringify({ok:false,reason:"Element is disabled"});
              e.checked=${checked};e.dispatchEvent(new Event("input",{bubbles:true}));e.dispatchEvent(new Event("change",{bubbles:true}));
              return JSON.stringify({ok:true,checked:e.checked});
            })()
        """.trimIndent()
        return actionResult(windowId, js, "check")
    }

    suspend fun scroll(windowId: String, direction: String, amount: Int): BrowserResult<String> {
        val dy = amount.coerceIn(50, 4000) * if(direction.equals("up",true)) -1 else 1
        val js = "window.scrollBy({top:$dy,left:0,behavior:'auto'});JSON.stringify({ok:true,y:window.scrollY});"
        return actionResult(windowId, js, "scroll")
    }

    suspend fun download(windowId: String, url: String? = null): BrowserResult<String> {
        val target = url?.takeIf { it.isNotBlank() } ?: currentUrl(windowId)
        if (target.isBlank()) return BrowserResult.Failure("No current URL to download.")
        val uri = runCatching { Uri.parse(target) }.getOrNull() ?: return BrowserResult.Failure("Invalid download URL.")
        if (uri.scheme !in listOf("http","https")) return BrowserResult.Failure("Only HTTP(S) downloads are supported.")
        val request = DownloadManager.Request(uri)
            .setTitle(uri.lastPathSegment ?: "browser-download")
            .setDescription("SA Desktop browser download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitizeFileName(uri.lastPathSegment ?: "browser-download"))
        CookieManager.getInstance().getCookie(target)?.let { request.addRequestHeader("Cookie", it) }
        views[windowId]?.settings?.userAgentString?.let { request.addRequestHeader("User-Agent", it) }
        val id = runCatching { downloadManager.enqueue(request) }.getOrElse { return BrowserResult.Failure("Download could not be started: ${it.message}") }
        var last = ""
        repeat(120) {
            delay(500)
            val status = queryDownload(id)
            if (status != null) {
                last = status
                if (status.startsWith("SUCCESS:")) return BrowserResult.Success(status)
                if (status.startsWith("FAILURE:")) return BrowserResult.Failure(status.removePrefix("FAILURE:"))
            }
        }
        return BrowserResult.Failure("Download did not finish within the browser timeout. Last state: $last")
    }

    suspend fun upload(windowId: String, ref: String, filePath: String): BrowserResult<String> {
        val file = resolveUploadFile(filePath)
            ?: return BrowserResult.Failure("File is outside the allowed workspace or does not exist: $filePath")
        if (!file.isFile || !file.canRead()) return BrowserResult.Failure("File does not exist or is not readable: $filePath")
        return onMain(windowId) {
            val view = views[windowId] ?: return@onMain BrowserResult.Failure("Browser window is not ready.")
            val callbackResult = kotlinx.coroutines.CompletableDeferred<BrowserResult<String>>()
            pendingUploads[windowId] = PendingUpload(ref, file) { uris ->
                if (uris.isNullOrEmpty()) callbackResult.complete(BrowserResult.Failure("WebView rejected the selected file."))
                else callbackResult.complete(BrowserResult.Success("Real file selected into the WebView file input: ${file.name}. The page/server upload is not claimed complete; that depends on the page's own upload flow."))
            }
            val js = """
                (function(){
                  const e=document.querySelector('[data-sa-ref="${escapeJs(ref)}"]');
                  if(!e)return JSON.stringify({ok:false,reason:"Element reference is stale or missing"});
                  if(e.tagName!=="INPUT"||e.type!=="file")return JSON.stringify({ok:false,reason:"Element is not a file input"});
                  e.click();return JSON.stringify({ok:true});
                })()
            """.trimIndent()
            view.evaluateJavascript(js) { raw ->
                val parsed = runCatching { JSONObject(raw.trim('"').replace("\\\"","\"")) }.getOrNull()
                if (parsed?.optBoolean("ok") != true) {
                    pendingUploads.remove(windowId)
                    callbackResult.complete(BrowserResult.Failure(parsed?.optString("reason") ?: "File input could not be activated."))
                }
            }
            callbackResult.await()
        }
    }

    suspend fun currentUrl(windowId: String): String = onMain(windowId) { views[windowId]?.url.orEmpty() }

    private suspend fun actionResult(windowId: String, js: String, action: String): BrowserResult<String> {
        val result = evaluate(windowId, js)
        if (result is BrowserResult.Failure) return result
        val parsed = runCatching { JSONObject(result.value) }.getOrNull()
            ?: return BrowserResult.Failure("Browser returned an invalid $action result.")
        if (!parsed.optBoolean("ok")) return BrowserResult.Failure(parsed.optString("reason", "$action was not performed."))
        // A real action result is followed by the current browser state; no success is inferred
        // from merely issuing a JavaScript request.
        delay(150)
        val state = _states.value[windowId]
        val withState = JSONObject()
            .put("action", action)
            .put("result", parsed)
            .put("url", state?.url.orEmpty())
            .put("title", state?.title.orEmpty())
            .put("loading", state?.loading == true)
            .put("progress", state?.progress ?: 0)
        return BrowserResult.Success(withState.toString())
    }

    private suspend fun evaluate(windowId: String, script: String): BrowserResult<String> =
        suspendCancellableCoroutine<BrowserResult<String>> { cont ->
            main.post {
                val view = views[windowId]
                if (view == null) {
                    cont.resume(BrowserResult.Failure("Browser window is not ready."))
                } else {
                    view.evaluateJavascript(script) { raw ->
                        if (cont.isActive) cont.resume(BrowserResult.Success(unquoteJavascriptResult(raw)))
                    }
                }
            }
            cont.invokeOnCancellation { /* WebView callback may still arrive; it is ignored safely. */ }
        }

    private fun unquoteJavascriptResult(raw: String): String =
        runCatching { org.json.JSONTokener(raw).nextValue() as? String ?: raw }
            .getOrElse { raw.removeSurrounding("\"").replace("\\n","\n").replace("\\\"","\"") }

    private suspend fun <T> onMain(windowId: String, block: () -> BrowserResult<T>): BrowserResult<T> =
        suspendCancellableCoroutine<BrowserResult<String>> { cont ->
            main.post { if (cont.isActive) cont.resume(block()) }
        }

    private fun update(windowId: String, transform: (BrowserState) -> BrowserState) {
        val current = _states.value[windowId] ?: BrowserState(windowId)
        _states.value = _states.value + (windowId to transform(current))
    }

    private fun currentUrlUnsafe(windowId: String): String = views[windowId]?.url.orEmpty()

    private fun normalizeUrl(input: String): String? {
        val t = input.trim()
        if (t.isBlank()) return null
        if (t.startsWith("http://") || t.startsWith("https://")) return t
        return if (t.contains(" ")) "https://www.google.com/search?q=" + Uri.encode(t) else "https://$t"
    }

    private fun escapeJs(s: String): String = s.replace("\\","\\\\").replace("\"","\\\"")

    private fun inspectionScript(limits: BrowserLimits): String = """
        (function(){
          const max=${limits.maxElements};
          const maxText=${limits.maxPageText};
          document.querySelectorAll('[data-sa-ref]').forEach(e=>delete e.dataset.saRef);
          const nodes=[...document.querySelectorAll('a,button,input,textarea,select,[role],summary,h1,h2,h3,h4,h5,h6,label,form')];
          let counter=0;
          function visible(e){const r=e.getBoundingClientRect(),s=getComputedStyle(e);return !!(r.width&&r.height&&s.visibility!=="hidden"&&s.display!=="none")}
          function interactable(e){return ["A","BUTTON","INPUT","TEXTAREA","SELECT","SUMMARY"].includes(e.tagName)||e.isContentEditable||typeof e.onclick==="function"||!!e.getAttribute("role")}
          function role(e){return e.getAttribute("role")||({"A":"link","BUTTON":"button","INPUT":"input","TEXTAREA":"textarea","SELECT":"select","SUMMARY":"button","FORM":"form"}[e.tagName]||e.tagName.toLowerCase())}
          function add(e){if(!e.dataset.saRef)e.dataset.saRef="p2-"+(++counter);return e.dataset.saRef}
          function item(e,i){return {ref:add(e),index:i,role:role(e),text:(e.innerText||e.value||"").trim().replace(/\s+/g," ").slice(0,300),ariaLabel:(e.getAttribute("aria-label")||e.getAttribute("title")||"").trim().slice(0,200),inputType:(e.type||"").toString(),value:(e.tagName==="INPUT" && (e.type||"").toLowerCase()==="password")?null:((e.tagName==="INPUT"||e.tagName==="TEXTAREA"||e.tagName==="SELECT")?String(e.value||"").slice(0,300):null),enabled:!e.disabled,visible:visible(e),interactable:interactable(e),required:e.required===true}
          }
          const all=nodes.slice(0,max).map(item);
          const links=nodes.filter(e=>e.tagName==="A").slice(0,${limits.maxLinks}).map((e,i)=>item(e,i));
          const headings=nodes.filter(e=>/^H[1-6]$/.test(e.tagName)).slice(0,60).map((e,i)=>item(e,i));
          const forms=nodes.filter(e=>e.tagName==="FORM").slice(0,40).map((e,i)=>item(e,i));
          const text=(document.body?.innerText||"").slice(0,maxText);
          return JSON.stringify({url:location.href,title:document.title||"",visibleText:text,headings,elements:all,links,forms,truncated:(document.body?.innerText||"").length>maxText||nodes.length>max});
        })()
    """.trimIndent()

    private fun parsePage(raw: String, limits: BrowserLimits): BrowserResult<BrowserPage> = runCatching {
        val o = JSONObject(raw)
        fun arr(key:String): List<BrowserElement> {
            val a=o.optJSONArray(key)?:JSONArray()
            return (0 until a.length()).map { i ->
                val e=a.getJSONObject(i)
                BrowserElement(e.optString("ref"),e.optInt("index"),e.optString("role"),e.optString("text"),e.optString("ariaLabel"),e.optString("inputType"),if(e.isNull("value"))null else e.optString("value"),e.optBoolean("enabled"),e.optBoolean("visible"),e.optBoolean("interactable"),if(e.has("required"))e.optBoolean("required") else null)
            }
        }
        BrowserPage(o.optString("url"),o.optString("title"),o.optString("visibleText"),arr("headings"),arr("elements"),arr("links"),arr("forms"),o.optBoolean("truncated"))
    }.fold({ BrowserResult.Failure("Could not parse page inspection: ${it.message}") }, { BrowserResult.Success(it) })

    private fun enqueueDownload(windowId:String,url:String,userAgent:String?,contentDisposition:String?,mimeType:String?) {
        val req=DownloadManager.Request(Uri.parse(url))
            .setTitle(URL(url).path.substringAfterLast('/').ifBlank{"browser-download"})
            .setDescription("SA Desktop browser download")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, sanitizeFileName(URL(url).path.substringAfterLast('/').ifBlank { "browser-download" }))
        CookieManager.getInstance().getCookie(url)?.let { req.addRequestHeader("Cookie",it) }
        userAgent?.let { req.addRequestHeader("User-Agent",it) }
        mimeType?.let { req.setMimeType(it) }
        runCatching { downloadManager.enqueue(req) }
    }

    private fun resolveUploadFile(path: String): File? {
        val raw = File(path)
        val candidate = if (raw.isAbsolute) raw else workspaceRoot?.resolve(path) ?: return null
        val canonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (workspaceRoot == null) return if (canonical.isFile && canonical.canRead()) canonical else null
        val root = runCatching { workspaceRoot.canonicalFile }.getOrNull() ?: return null
        if (canonical.path != root.path && !canonical.path.startsWith(root.path + File.separator)) return null
        return canonical
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120).ifBlank { "browser-download" }

    private fun queryDownload(id:Long):String? {
        val c=downloadManager.query(DownloadManager.Query().setFilterById(id)) ?: return null
        c.use {
            if(!it.moveToFirst()) return "FAILURE:Download disappeared from DownloadManager."
            val status=it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            return when(status){
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val uri=it.getString(it.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI))
                    "SUCCESS:$uri"
                }
                DownloadManager.STATUS_FAILED -> "FAILURE:DownloadManager reported failure code ${it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))}"
                else -> "PENDING:${it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))}"
            }
        }
    }
}
