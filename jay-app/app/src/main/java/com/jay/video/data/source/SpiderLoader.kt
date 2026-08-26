package com.jay.video.data.source

import android.content.Context
import android.util.Log
import com.github.catvod.crawler.Spider
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 */
object SpiderLoader {
    private const val TAG = "SpiderLoader"

    private lateinit var app: Context

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** jarUrl → DexClassLoader */
    private val loaders = ConcurrentHashMap<String, DexClassLoader>()

    /** siteKey → Spider 实例 */
    private val spiders = ConcurrentHashMap<String, Spider>()

    /** 初始化失败的站点（避免重复尝试） */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** 已加载的 jar 数 */
    fun loadedCount(): Int = loaders.size

    /** spider 是否就绪 */
    fun isReady(siteKey: String): Boolean = spiders.containsKey(siteKey)

    /** 下载并加载 spider jar（带缓存） */
    suspend fun loadJar(jarUrl: String): Boolean = withContext(Dispatchers.IO) {
        if (loaders.containsKey(jarUrl)) return@withContext true
        try {
            // spider url 格式: "https://xxx.jar;md5;abc123"（TVBox 约定）
            val url = jarUrl.substringBefore(";md5;")
            val expectMd5 = jarUrl.substringAfter(";md5;", "").ifBlank { null }

            val dir = File(app.filesDir, "spiders").apply { mkdirs() }
            val fileName = "spider_" + (expectMd5 ?: url.hashCode().toString().replace("-", "n")) + ".jar"
            val file = File(dir, fileName)

            // 缓存命中且校验通过
            if (!file.exists() || file.length() < 1024) {
                val bytes = download(url)
                if (bytes == null || bytes.size < 1024) {
                    Log.w(TAG, "jar下载失败: $url")
                    return@withContext false
                }
                file.writeBytes(bytes)
            }
            if (expectMd5 != null) {
                val md5 = md5(file.readBytes())
                if (md5 != expectMd5) {
                    Log.w(TAG, "md5不匹配: $md5 != $expectMd5，重新下载")
                    file.delete()
                    val bytes = download(url)
                    if (bytes != null && bytes.size > 1024) {
                        file.writeBytes(bytes)
                        if (md5(file.readBytes()) != expectMd5) {
                            Log.w(TAG, "md5二次校验失败，放弃该jar")
                        }
                    }
                }
            }

            val optDir = File(app.cacheDir, "spider_opt").apply { mkdirs() }
            val loader = DexClassLoader(file.absolutePath, optDir.absolutePath, null, app.classLoader)

            // 反射引导 Init 类（标准 TVBox 协议：Init.init(context) 静态或实例方法）
            bootstrapInit(loader)

            loaders[jarUrl] = loader
            Log.i(TAG, "jar加载成功: $url (${file.length() / 1024}KB)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "jar加载失败: $jarUrl", e)
            false
        }
    }

    /** 引导 spider jar 的 Init 类 */
    private fun bootstrapInit(loader: DexClassLoader) {
        runCatching {
            val cls = loader.loadClass("com.github.catvod.spider.Init")
            // 1. 尝试静态 init(Context)
            runCatching {
                cls.methods.firstOrNull {
                    it.name == "init" && it.parameterCount == 1 &&
                        Context::class.java.isAssignableFrom(it.parameterTypes[0])
                }?.let { m ->
                    if (java.lang.reflect.Modifier.isStatic(m.modifiers)) {
                        m.invoke(null, app)
                    } else {
                        m.invoke(cls.getDeclaredConstructor().newInstance(), app)
                    }
                }
            }
            // 2. 尝试 Init.get() 静态单例再调 init
            runCatching {
                cls.getMethod("get").invoke(null)?.let { instance ->
                    instance.javaClass.methods.firstOrNull {
                        it.name == "init" && it.parameterCount == 1 &&
                            Context::class.java.isAssignableFrom(it.parameterTypes[0])
                    }?.invoke(instance, app)
                }
            }
        }
    }

    /**
     * 获取站点爬虫实例（懒加载 + 缓存）
     * @param siteKey 站点 key（缓存标识）
     * @param jarUrl 配置的 spider jar 地址
     * @param api 爬虫类名（csp_XXX → com.github.catvod.spider.XXX）
     * @param ext 扩展参数
     */
    suspend fun obtain(siteKey: String, jarUrl: String, api: String, ext: String): Spider? =
        withContext(Dispatchers.IO) {
            spiders[siteKey]?.let { return@withContext it }
            if (failed.contains(siteKey)) return@withContext null

            if (!loadJar(jarUrl)) {
                failed.add(siteKey)
                return@withContext null
            }
            val loader = loaders[jarUrl] ?: return@withContext null

            try {
                val clsName = if (api.startsWith("csp_")) {
                    "com.github.catvod.spider." + api.removePrefix("csp_")
                } else if (api.startsWith("com.")) {
                    api
                } else {
                    "com.github.catvod.spider.$api"
                }
                val cls = loader.loadClass(clsName)
                val spider = cls.getDeclaredConstructor().newInstance() as Spider
                spider.init(app, ext ?: "")
                spiders[siteKey] = spider
                Log.i(TAG, "spider就绪: $siteKey ($clsName)")
                spider
            } catch (e: Exception) {
                Log.e(TAG, "spider初始化失败: $siteKey api=$api", e)
                failed.add(siteKey)
                null
            }
        }

    /** 搜索（阻塞调用包装） */
    suspend fun search(siteKey: String, jarUrl: String, api: String, ext: String, key: String): String? =
        withContext(Dispatchers.IO) {
            val spider = obtain(siteKey, jarUrl, api, ext) ?: return@withContext null
            try {
                spider.searchContent(key, false)
            } catch (e: Exception) {
                Log.e(TAG, "search失败: $siteKey", e)
                null
            }
        }

    /** 详情（vod_id → 完整信息含播放串） */
    suspend fun detail(siteKey: String, jarUrl: String, api: String, ext: String, vodId: String): String? =
        withContext(Dispatchers.IO) {
            val spider = obtain(siteKey, jarUrl, api, ext) ?: return@withContext null
            try {
                spider.detailContent(listOf(vodId))
            } catch (e: Exception) {
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
            spider.playerContent(flag, id, emptyList())
        } catch (e: Exception) {
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
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "download失败: $url", e)
        null
    }
}
