package com.jay.video

import android.app.Application
import com.google.gson.Gson
import com.jay.video.data.local.AppDatabase
import com.jay.video.data.source.SourceRepo
import com.jay.video.data.tmdb.TmdbApi
import com.jay.video.data.tmdb.TmdbRepo
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/** 全局容器（手动依赖注入） */
object App {
    const val TMDB_KEY = "cb44223c5dee5676ed3a839f42ed27e3"
    const val TMDB_BASE = "https://api.tmdb.org/3/"

    lateinit var db: AppDatabase
        private set
    lateinit var tmdb: TmdbRepo
        private set
    lateinit var source: SourceRepo
        private set

    fun init() {
        val plain = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        // TMDB 专用客户端：统一附加 api_key / language / UA
        val tmdbClient = plain.newBuilder()
            .addInterceptor { chain ->
                val req = chain.request()
                val url = req.url.newBuilder().apply {
                    addQueryParameter("api_key", TMDB_KEY)
                    if (req.url.queryParameter("language") == null) {
                        addQueryParameter("language", "zh-CN")
                    }
                    addQueryParameter("include_adult", "false")
                }.build()
                chain.proceed(
                    req.newBuilder()
                        .url(url)
                        .header("User-Agent", "JayVideo/1.0")
                        .build()
                )
            }
            .build()

        val api = Retrofit.Builder()
            .baseUrl(TMDB_BASE)
            .client(tmdbClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(TmdbApi::class.java)

        db = AppDatabase.build(CtxHolder.app)
        tmdb = TmdbRepo(api)
        source = SourceRepo(plain, Gson())
    }
}

/** Application 引用持有 */
object CtxHolder {
    lateinit var app: Application
}

class JayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CtxHolder.app = this
        App.init()
    }
}
