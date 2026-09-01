package com.jay.video.player

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.jay.video.data.source.Sniffer
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 影视仓同源网页嗅探解析器（对照 ParseJob + CustomWebView 移植）
 *
 * 流程与影视仓完全一致：
 * 1. 隐藏 WebView 加载 解析地址 + 视频页地址（raw 拼接，不编码）
 * 2. shouldInterceptRequest 拦截请求：
 *    - PLAYER 正则命中（player 页再跳播放器）→ 新开 WebView 继续嗅探（detect=false）
 *    - 视频格式命中 → 回调真实地址 + 请求头
 * 3. 15 秒超时 → 失败回调
 */
object ParseSniffer {

    private const val TIMEOUT_MS = 15_000L
    private const val MAX_URLS = 5
    private val MAIN = Handler(Looper.getMainLooper())

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    class Result(
        val ok: Boolean,
        val url: String = "",
        val headers: Map<String, String> = emptyMap(),
    )

    /**
     * 嗅探解析真实播放地址
     * @param parseUrl 解析前缀（如 https://svip.ffzyplay.com/?url=）
     * @param videoUrl 待解析的视频页地址
     * @param headers 站点请求头（UA/Cookie 注入 WebView）
     * @param isVideo 自定义视频格式判定（spider manualVideoCheck），null 用默认规则
     * @param onResult 主线程回调（一次性）
     */
    fun sniff(
        context: Context,
        parseUrl: String,
        videoUrl: String,
        headers: Map<String, String> = emptyMap(),
        isVideo: ((String) -> Boolean)? = null,
        onResult: (Result) -> Unit,
    ) {
        val done = AtomicBoolean(false)
        val webViews = Collections.synchronizedList(mutableListOf<WebView>())
        val videoCheck: (String) -> Boolean = isVideo ?: { Sniffer.isVideoFormat(it) }

        fun finish(result: Result) {
            if (!done.compareAndSet(false, true)) return
            MAIN.post {
                onResult(result)
                MAIN.post { stop(webViews) }
            }
        }

        fun startWeb(url: String, detect: Boolean, hdrs: Map<String, String>) {
            MAIN.post {
                if (done.get()) return@post
                try {
                    val wv = create(context, hdrs, url)
                    webViews += wv
                    attachHidden(context, wv)
                    wv.webViewClient = client(url, webViews, done, ::finish, videoCheck, hdrs, detect)
                    // 15 秒嗅探超时
                    wv.postDelayed({ finish(Result(false)) }, TIMEOUT_MS)
                    wv.loadUrl(url, hdrs)
                } catch (e: Exception) {
                    finish(Result(false))
                }
            }
        }

        // 影视仓 ParseJob：parse.getUrl() + webUrl（raw 拼接）
        startWeb(parseUrl + videoUrl, true, headers)
    }

    private fun stop(webViews: MutableList<WebView>) {
        val copy = webViews.toList()
        webViews.clear()
        for (wv in copy) {
            runCatching {
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.destroy()
            }
        }
    }

    /** 隐藏挂载（WebView 必须挂到视图树才能正常嗅探） */
    private fun attachHidden(context: Context, wv: WebView) {
        val root = (context as? android.app.Activity)?.window?.decorView as? ViewGroup
        root?.addView(
            wv,
            ViewGroup.LayoutParams(1, 1),
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(context: Context, headers: Map<String, String>, targetUrl: String = ""): WebView {
        val wv = WebView(context)
        wv.layoutParams = ViewGroup.LayoutParams(1, 1)
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        s.userAgentString = headers.entries.firstOrNull { it.key.equals("User-Agent", true) || it.key.equals("ua", true) }?.value ?: UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // Cookie 注入（影视仓 checkHeader 行为）
        headers.entries.firstOrNull { it.key.equals("Cookie", true) || it.key.equals("cookie", true) }?.value?.let { cookie ->
            runCatching {
                val origin = Regex("^https?://[^/]+").find(targetUrl)?.value ?: "https://svip.ffzyplay.com"
                CookieManager.getInstance().setCookie(origin, cookie)
            }
        }
        return wv
    }

    private fun client(
        startUrl: String,
        webViews: MutableList<WebView>,
        done: AtomicBoolean,
        finish: (Result) -> Unit,
        videoCheck: (String) -> Boolean,
        headers: Map<String, String>,
        detect: Boolean,
    ): WebViewClient = object : WebViewClient() {
        private val urls = LinkedHashSet<String>()

        private fun addUrl(url: String): Boolean {
            if (urls.size > MAX_URLS) urls.clear()
            return urls.add(url)
        }

        /** 影视仓 CustomWebView.isVideoFormat：二跳 WebView 不判定自身起始地址 */
        private fun checkVideo(url: String): Boolean {
            if (!detect && url == startUrl) return false
            return videoCheck(url)
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val host = request.url.host ?: ""
            if (host.isEmpty()) return empty()
            // 影视仓：player 链 → 新 WebView 继续嗅探（不再 detect）
            if (detect && Sniffer.PLAYER.containsMatchIn(url) && addUrl(url)) {
                val hdrs = request.requestHeaders ?: headers
                finishAdd(webViews, done, finish, videoCheck, url, hdrs)
                return super.shouldInterceptRequest(view, request)
            }
            if (checkVideo(url)) {
                val hdrs = request.requestHeaders ?: emptyMap()
                finish(Result(true, url, hdrs))
            }
            return super.shouldInterceptRequest(view, request)
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) {
            handler.proceed()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
    }

    /** 影视仓 onParseAdd：player 链新开嗅探 WebView */
    private fun finishAdd(
        webViews: MutableList<WebView>,
        done: AtomicBoolean,
        finish: (Result) -> Unit,
        videoCheck: (String) -> Boolean,
        url: String,
        headers: Map<String, String>,
    ) {
        MAIN.post {
            if (done.get()) return@post
            runCatching {
                val ctx = webViews.firstOrNull()?.context ?: return@post
                val wv = create(ctx, headers, url)
                webViews += wv
                attachHidden(ctx, wv)
                wv.webViewClient = client(url, webViews, done, finish, videoCheck, headers, false)
                wv.postDelayed({ finish(Result(false)) }, TIMEOUT_MS)
                wv.loadUrl(url, headers)
            }
        }
    }

    private fun empty(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream("".toByteArray()))
}
