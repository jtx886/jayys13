package com.github.catvod.crawler;

import android.content.Context;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/**
 * TVBox / 影视仓爬虫基类（兼容层）
 * 签名与 com.github.catvod.crawler.Spider 完全一致，
 * 供 DexClassLoader 加载的 spider jar 中的子类继承。
 */
public abstract class Spider {

    public void init(Context context) {
    }

    public void init(Context context, String extend) {
    }

    public String homeContent(boolean filter) {
        return "";
    }

    public String homeVideoContent() {
        return "";
    }

    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return "";
    }

    public String searchContent(String key, boolean quick) {
        return "";
    }

    public String searchContent(String key, boolean quick, String pg) {
        return "";
    }

    public String detailContent(List<String> ids) {
        return "";
    }

    public String playerContent(String flag, String id, List<String> vipFlags) {
        return "";
    }

    public String liveContent(String txt) {
        return "";
    }

    public String action(String action) {
        return "";
    }

    public boolean isVideoFormat(String url) {
        return false;
    }

    public boolean manualVideoCheck() {
        return false;
    }

    public Object[] proxyLocal(Map<String, String> params) {
        return null;
    }

    public void destroy() {
    }

    /** 子类通过 super.client() 获取 HTTP 客户端 */
    public OkHttpClient client() {
        return SpiderHttp.client();
    }

    /** 子类通过 super.safeDns() 获取 DNS 解析器 */
    public Dns safeDns() {
        return SpiderHttp.dns();
    }
}
