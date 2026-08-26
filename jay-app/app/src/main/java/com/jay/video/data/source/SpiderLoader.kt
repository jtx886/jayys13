package com.jay.video.data.source

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * TVBox / 影视仓 spider 运行时加载器
 *
 * 流程：下载 jar → DexClassLoader 加载 → 反射初始化 Init →
 * 按站点实例化爬虫类（com.github.catvod.spider.{Api}）→ init(context, ext)
 *
 * 站点数据流（TVBox 协议）：
 * - searchContent(key, false) → {"list":[{"vod_id","vod_name","vod_pic","vod_remarks"}]}
 * - detailContent(ids)        → {"list":[{..."vod_play_from","vod_play_url"}]}
 * - playerContent(flag, id)   → {"parse":0/1,"url":"...","header":{...}}
 *
 * 注：部分「壳 jar」（如嗷呜系）Init.init 时会联网下载真实爬虫 jar（含加密 dex 与 so），
 * 首次加载可能耗时数十秒，因此失败缓存必须可过期重试，不能一次失败永久拉黑。
 */
object SpiderLoader {
    private const val TAG = "SpiderLoader"

    /** jar 下载/加载失败后的冷却时间（首次冷启动下载真实 jar 很慢，必须允许重试） */
    private const val FAIL_RETRY_MS = 60_000L

    /** spider 调用超时（秒） */
    private const val CALL_TIMEOUT_MS = 45_000L

    private lateinit var app: Context

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** jarUrl → DexClassLoader */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /** siteKey → Spider 实例 */
    private val spiders = ConcurrentHashMap<String, Spider>()

    /** siteKey → 上次失败时间戳（过期后允许重试） */
    private val failed = ConcurrentHashMap<String, Long>()

    /** jarUrl → 上次失败时间戳 */
    private val jarFailed = ConcurrentHashMap<String, Long>()

    /** 最近错误（供 UI 展示） */
    @Volatile
    var lastError: String = ""
        private set

    private fun fail(msg: String) {
        lastError = msg
        Log.w(TAG, msg)
    }

    /** 清除错误记录（新一轮搜索前调用） */
    fun clearError() {
        lastError = ""
    }

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** 已加载的 jar 数 */
    fun loadedCount(): Int = loaders.size

    /** spider 是否就绪 */
    fun isReady(siteKey: String): Boolean = spiders.containsKey(siteKey)

    /** 下载并加载 spider jar（带缓存；失败冷却后可重试） */
    suspend fun loadJar(jarUrl: String): Boolean = withContext(Dispatchers.IO) {
        loaders[jarUrl]?.let { return@withContext true }
        jarFailed[jarUrl]?.let {
            if (System.currentTimeMillis() - it < FAIL_RETRY_MS) {
                return@withContext false
            }
            jarFailed.remove(jarUrl)
        }
        try {
            // spider url 格式: "https://xxx.jar;md5;abc123"（TVBox 约定）
            val url = jarUrl.substringBefore(";md5;")
            val expectMd5 = jarUrl.substringAfter(";md5;", "").ifBlank { null }

            val dir = File(app.filesDir, "spiders").apply { mkdirs() }
            val fileName = "spider_" + (expectMd5 ?: url.hashCode().toString().replace("-", "n")) + ".jar"
            val file = File(dir, fileName)

            var ok = false
            if (file.exists() && file.length() > 1024) {
                ok = expectMd5 == null || md5(file.readBytes()) == expectMd5
            }
            if (!ok) {
                val bytes = download(url)
                if (bytes == null || bytes.size < 1024) {
                    fail("jar下载失败: $url")
                    jarFailed[jarUrl] = System.currentTimeMillis()
                    return@withContext false
                }
                file.writeBytes(bytes)
                if (expectMd5 != null && md5(bytes) != expectMd5) {
                    fail("jar md5不匹配: $url")
                    // md5 不匹配时仍尝试加载（配置方可能更新了 jar 未同步 md5）
                }
            }

            val optDir = File(app.cacheDir, "spider_opt").apply { mkdirs() }
            val loader = DexClassLoader(file.absolutePath, optDir.absolutePath, null, app.classLoader)

            // 反射引导 Init 类（静态或实例 init(Context)），异常必须可见
            bootstrapInit(loader)

            loaders[jarUrl] = loader
            Log.i(TAG, "jar加载成功: $url (${file.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            fail("jar加载异常: ${e.message}")
            Log.e(TAG, "jar加载失败: $jarUrl", e)
            jarFailed[jarUrl] = System.currentTimeMillis()
            false
        }
    }

    /**
     * 引导 spider jar 的 Init 类（TVBox 协议核心）
     * 壳 jar（嗷呜系/影视仓）在 Init.init(context) 内完成：
     * 联网拉取真实爬虫 jar → 解密 dex → 加载 so → 构建二级 DexClassLoader
     */
    private fun bootstrapInit(loader: DexClassLoader) {
        // 常见 Init 全限定名（不同 jar 打包习惯不同）
        val names = listOf(
            "com.github.catvod.spider.Init",
            "com.github.catvod.Init",
        )
        var initOk = false
        var lastEx: Exception? = null

        for (clsName in names) {
            val cls = try {
                loader.loadClass(clsName)
            } catch (e: Exception) {
                continue  // 该 jar 没有此类，试下一个
            }
            try {
                // 1. 静态 init(Context)
                val m = cls.methods.firstOrNull {
                    it.name == "init" && it.parameterCount == 1 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (m != null) {
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                        m.invoke(null, app)
                    } else {
                        m.invoke(cls.getDeclaredConstructor().newInstance(), app)
                    }
                    initOk = true
                    Log.i(TAG, "Init引导成功: $clsName (init)")
                    break
                }
                // 2. 静态 init(Context, String)（部分 jar 需传 jar 路径）
                val m2 = cls.methods.firstOrNull {
                    it.name == "init" && it.parameterCount == 2 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }
                if (m2 != null && java.lang.reflect.Modifier.isStatic(m2.modifiers)) {
                    m2.invoke(null, app, "")
                    initOk = true
                    Log.i(TAG, "Init引导成功: $clsName (init,2)")
                    break
                }
                // 3. get() 单例 + init(Context)
                runCatching {
                    cls.getMethod("get").invoke(null)?.let { instance ->
                        instance.javaClass.methods.firstOrNull {
                            it.name == "init" && it.parameterCount == 1 &&
                                Context::class.java.isAssignableFrom(it.parameterTypes[0])
                        }?.invoke(instance, app)
                        initOk = true
                        Log.i(TAG, "Init引导成功: $clsName (get)")
                    }
                }
                if (initOk) break
            } catch (e: Exception) {
                lastEx = e
                Log.w(TAG, "Init引导异常: $clsName — ${e.cause ?: e}")
            }
        }
        // Init 不是必需的（部分简单 jar 不含 Init），仅记录
        if (!initOk && lastEx != null) {
            fail("Init初始化异常: ${lastEx.cause ?: lastEx}")
        }
    }

    /**
     * 获取站点爬虫实例（懒加载 + 缓存 + 失败冷却重试）
     * @param siteKey 站点 key（缓存标识）
     * @param jarUrl 配置的 spider jar 地址
     * @param api 爬虫类名（csp_XXX → com.github.catvod.spider.XXX）
     * @param ext 扩展参数（字符串或 JSON 对象序列化）
     */
    suspend fun obtain(siteKey: String, jarUrl: String, api: String, ext: String): Spider? =
        withContext(Dispatchers.IO) {
            spiders[siteKey]?.let { return@withContext it }
            failed[siteKey]?.let {
                if (System.currentTimeMillis() - it < FAIL_RETRY_MS) return@withContext null
                failed.remove(siteKey)
            }

            if (!loadJar(jarUrl)) {
                failed[siteKey] = System.currentTimeMillis()
                return@withContext null
            }
            val loader = loaders[jarUrl] ?: return@withContext null

            try {
                val clsName = when {
                    api.startsWith("csp_") -> "com.github.catvod.spider." + api.removePrefix("csp_")
                    api.startsWith("com.") -> api
                    else -> "com.github.catvod.spider.$api"
                }
                val cls = loader.loadClass(clsName)
                val spider = cls.getDeclaredConstructor().newInstance() as Spider
                spider.init(app, ext ?: "")
                spiders[siteKey] = spider
                Log.i(TAG, "spider就绪: $siteKey ($clsName)")
                spider
            } catch (e: Exception) {
                val cause = e.cause ?: e
                fail("spider初始化失败 [$siteKey]: ${cause.javaClass.simpleName}: ${cause.message}")
                Log.e(TAG, "spider初始化失败: $siteKey api=$api", e)
                failed[siteKey] = System.currentTimeMillis()
                null
            }
        }

    /** 搜索（阻塞调用包装 + 超时保护） */
    suspend fun search(siteKey: String, jarUrl: String, api: String, ext: String, key: String): String? =
        withContext(Dispatchers.IO) {
            val spider = obtain(siteKey, jarUrl, api, ext) ?: return@withContext null
            try {
                withTimeout(CALL_TIMEOUT_MS) { spider.searchContent(key, false) }
            } catch (e: Exception) {
                val cause = e.cause ?: e
                fail("搜索失败 [$siteKey]: ${cause.javaClass.simpleName}: ${cause.message}")
                Log.e(TAG, "search失败: $siteKey", e)
                null
            }
        }

    /** 详情（vod_id → 完整信息含播放串） */
    suspend fun detail(siteKey: String, jarUrl: String, api: String, ext: String, vodId: String): String? =
        withContext(Dispatchers.IO) {
            val spider = obtain(siteKey, jarUrl, api, ext) ?: return@withContext null
            try {
                withTimeout(CALL_TIMEOUT_MS) { spider.detailContent(listOf(vodId)) }
            } catch (e: Exception) {
                fail("详情失败 [$siteKey]: ${(e.cause ?: e).message}")
                Log.e(TAG, "detail失败: $siteKey", e)
                null
            }
        }

    /** 播放地址解析（flag + id → 真实地址） */
    suspend fun player(
        siteKey: String, jarUrl: String, api: String, ext: String,
        flag: String, id: String,
    ): String? = withContext(Dispatchers.IO) {
        val spider = obtain(siteKey, jarUrl, api, ext) ?: return@withContext null
        try {
            withTimeout(CALL_TIMEOUT_MS) { spider.playerContent(flag, id, emptyList()) }
        } catch (e: Exception) {
            fail("播放解析失败 [$siteKey]: ${(e.cause ?: e).message}")
            Log.e(TAG, "player失败: $siteKey", e)
            null
        }
    }

    /** 清理全部实例（配置刷新时调用） */
    fun reset() {
        spiders.values.forEach { runCatching { it.destroy() } }
        spiders.clear()
        failed.clear()
        // loaders 保留（jar 文件缓存仍有效，Init 引用可能持有）
    }

    private fun md5(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 下载文件 */
    private fun download(url: String): ByteArray? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) JayVideo/1.0")
            .build()
        http.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else {
                fail("下载HTTP ${resp.code}: $url")
                null
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "download失败: $url", e)
        null
    }
}
