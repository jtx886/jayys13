package com.jay.video.data.source

import android.content.Context
import android.util.Log
import com.github.catvod.Init
import com.github.catvod.crawler.Spider
import com.github.catvod.crawler.SpiderNull
import com.github.catvod.net.OkHttp
import com.github.catvod.utils.Crypto
import com.github.catvod.utils.Path
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 影视仓 / FongMi 同源 spider 加载器（对照 JarLoader 移植）
 *
 * 关键点（与影视仓一致）：
 * 1. DexClassLoader 的 optimizedDirectory 与 librarySearchPath 都指向 cache/jar
 *    （spider 的 so 依赖放在同一目录才能被 System.loadLibrary 找到）
 * 2. 加载前 file.setReadOnly()（Android 10+ 强制要求 dex 只读）
 * 3. 引导 com.github.catvod.spider.Init.init(Context)（嗷呜系壳 jar 在此联网下载解密真实爬虫）
 * 4. 爬虫类名 = "com.github.catvod.spider." + api.split("csp_")[1]
 * 5. spider.siteKey = key；spider.init(context, ext)
 * 6. 实例缓存 key = md5(jar) + siteKey
 * 7. jar 支持 "url;md5;校验值" 约定，md5 段也可以是 http 地址（从网络取真实 md5）
 */
object SpiderLoader {
    private const val TAG = "SpiderLoader"

    private lateinit var app: Context

    /** jarUrl(md5) → DexClassLoader */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /** jarUrl(md5)+siteKey → Spider 实例 */
    private val spiders = ConcurrentHashMap<String, Spider>()

    /** 每个 jar 的加载锁（防并发重复加载） */
    private val locks = ConcurrentHashMap<String, Any>()

    /** siteKey → 上次失败时间戳（短冷却重试，冷启动下载慢不能永久拉黑） */
    private val failed = ConcurrentHashMap<String, Long>()

    /** jar md5 key → 上次失败时间戳 */
    private val jarFailed = ConcurrentHashMap<String, Long>()

    /** 最近错误（供 UI 展示） */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(msg: String) {
        lastError = msg
        Log.w(TAG, msg)
    }

    fun clearError() {
        lastError = ""
    }

    fun init(context: Context) {
        app = context.applicationContext
        // 宿主全局 Context（Path / Util / Prefers 依赖）
        Init.set(app)
    }

    fun loadedCount(): Int = loaders.size

    fun isReady(siteKey: String): Boolean = spiders.keys.any { it.endsWith(siteKey) }

    /** 下载（走宿主 OkHttp，信任所有证书） */
    private fun download(url: String): ByteArray? = try {
        OkHttp.client(120_000).newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else {
                fail("下载HTTP ${resp.code}: $url")
                null
            }
        }
    } catch (e: Exception) {
        fail("下载失败: ${e.message}")
        null
    }

    /** 解析 "url;md5;xxx" 约定，md5 段可为 http 地址 */
    private fun parseJarSpec(jar: String): Pair<String, String> {
        val texts = jar.split(";md5;")
        var md5 = if (texts.size > 1) texts[1].trim() else ""
        if (md5.startsWith("http")) md5 = OkHttp.string(md5).trim()
        return texts[0] to md5
    }

    /** 加载 jar（影视仓 parseJar + load 逻辑） */
    private fun loadJar(jarUrl: String): DexClassLoader? {
        val jaKey = Crypto.md5(jarUrl)
        loaders[jaKey]?.let { return it }
        if (jarUrl.isEmpty()) return null

        // 冷却检查（失败 30 秒内不重复尝试，防止搜索风暴）
        jarFailed[jaKey]?.let {
            if (System.currentTimeMillis() - it < 30_000) return null
            jarFailed.remove(jaKey)
        }

        val lock = locks.computeIfAbsent(jaKey) { Any() }
        synchronized(lock) {
            loaders[jaKey]?.let { return it }
            try {
                val (url, md5) = parseJarSpec(jarUrl)
                val file = Path.jar(url)

                var ok = Path.exists(file) && (md5.isEmpty() || Crypto.equals(file, md5))
                if (!ok && url.startsWith("http")) {
                    val bytes = download(url)
                    if (bytes != null && bytes.size > 1024) {
                        Path.write(file, bytes)
                        ok = true
                    } else {
                        fail("jar下载失败: $url")
                    }
                } else if (!ok && url.startsWith("file")) {
                    val local = Path.local(url)
                    if (Path.exists(local)) {
                        Path.write(file, Path.readToByte(local))
                        ok = true
                    }
                } else if (!ok && url.startsWith("assets")) {
                    runCatching {
                        app.assets.open(url.removePrefix("assets/")).use { Path.write(file, it) }
                        ok = true
                    }
                }
                if (!ok || !Path.exists(file)) {
                    jarFailed[jaKey] = System.currentTimeMillis()
                    return null
                }

                // 影视仓关键：只读 + optimizedDirectory/librarySearchPath 同为 jar 缓存目录
                if (!file.setReadOnly()) Log.w(TAG, "setReadOnly失败(忽略)")
                val cachePath = Path.jar().absolutePath
                val loader = DexClassLoader(file.absolutePath, cachePath, cachePath, app.classLoader)

                // 引导壳 Init（嗷呜系在此下载解密真实爬虫）
                invokeInit(loader)

                loaders[jaKey] = loader
                Log.i(TAG, "jar加载成功: $url (${file.length() / 1024}KB)")
                return loader
            } catch (e: Throwable) {
                fail("jar加载异常: ${e.message}")
                Log.e(TAG, "jar加载失败: $jarUrl", e)
                jarFailed[jaKey] = System.currentTimeMillis()
                return null
            }
        }
    }

    /** 影视仓 invokeInit：com.github.catvod.spider.Init.init(Context) */
    private fun invokeInit(loader: DexClassLoader) {
        try {
            val clz = loader.loadClass("com.github.catvod.spider.Init")
            val method: Method = clz.getMethod("init", Context::class.java)
            method.invoke(clz, app)
            Log.i(TAG, "Init引导成功")
        } catch (e: Throwable) {
            // 无 Init 类属正常（简单 jar），仅记录
            Log.d(TAG, "Init引导跳过: ${e.javaClass.simpleName}")
        }
    }

    /** 获取站点爬虫（影视仓 getSpider 逻辑） */
    fun obtainSpider(siteKey: String, jarUrl: String, api: String, ext: String): Spider {
        val spKey = Crypto.md5(jarUrl) + siteKey
        spiders[spKey]?.let { return it }

        // 失败冷却（60 秒后允许重试）
        failed[spKey]?.let {
            if (System.currentTimeMillis() - it < 60_000) return SpiderNull()
            failed.remove(spKey)
        }

        return try {
            val loader = loadJar(jarUrl) ?: return SpiderNull().also { failed[spKey] = System.currentTimeMillis() }
            val clsName = "com.github.catvod.spider." + api.split("csp_")[1]
            val spider = loader.loadClass(clsName).newInstance() as Spider
            spider.siteKey = siteKey
            spider.init(app, ext)
            spiders[spKey] = spider
            Log.i(TAG, "spider就绪: $siteKey ($clsName)")
            spider
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            fail("spider初始化失败 [$siteKey]: ${cause.javaClass.simpleName}: ${cause.message}")
            Log.e(TAG, "spider初始化失败: $siteKey api=$api", e)
            failed[spKey] = System.currentTimeMillis()
            SpiderNull()
        }
    }

    /** 搜索 */
    suspend fun search(siteKey: String, jarUrl: String, api: String, ext: String, key: String): String? =
        withContext(Dispatchers.IO) {
            try {
                obtainSpider(siteKey, jarUrl, api, ext).searchContent(key, false)
            } catch (e: Throwable) {
                fail("搜索失败 [$siteKey]: ${(e.cause ?: e).message}")
                Log.e(TAG, "search失败: $siteKey", e)
                null
            }
        }

    /** 详情 */
    suspend fun detail(siteKey: String, jarUrl: String, api: String, ext: String, vodId: String): String? =
        withContext(Dispatchers.IO) {
            try {
                obtainSpider(siteKey, jarUrl, api, ext).detailContent(listOf(vodId))
            } catch (e: Throwable) {
                fail("详情失败 [$siteKey]: ${(e.cause ?: e).message}")
                Log.e(TAG, "detail失败: $siteKey", e)
                null
            }
        }

    /** 播放解析 */
    suspend fun player(
        siteKey: String, jarUrl: String, api: String, ext: String,
        flag: String, id: String, vipFlags: List<String> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        try {
            obtainSpider(siteKey, jarUrl, api, ext).playerContent(flag, id, vipFlags)
        } catch (e: Throwable) {
            fail("播放解析失败 [$siteKey]: ${(e.cause ?: e).message}")
            Log.e(TAG, "player失败: $siteKey", e)
            null
        }
    }

    /** 是否视频格式（影视仓 CustomWebView 用 spider 自检规则） */
    fun isVideoFormat(siteKey: String, jarUrl: String, api: String, ext: String, url: String): Boolean {
        return try {
            val spider = obtainSpider(siteKey, jarUrl, api, ext)
            if (spider.manualVideoCheck()) spider.isVideoFormat(url) else Sniffer.isVideoFormat(url)
        } catch (e: Throwable) {
            Sniffer.isVideoFormat(url)
        }
    }

    /** 清理全部实例（配置刷新时调用） */
    fun reset() {
        spiders.values.forEach { runCatching { it.destroy() } }
        spiders.clear()
        failed.clear()
    }
}
