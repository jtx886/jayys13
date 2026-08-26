package com.github.catvod.crawler;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

/** Spider 基类使用的 HTTP 客户端（信任所有证书 + 系统 DNS） */
public final class SpiderHttp {

    private static volatile OkHttpClient client;
    private static final Dns DNS = Dns.SYSTEM;

    private SpiderHttp() {
    }

    public static OkHttpClient client() {
        if (client == null) {
            synchronized (SpiderHttp.class) {
                if (client == null) client = build();
            }
        }
        return client;
    }

    public static Dns dns() {
        return DNS;
    }

    private static OkHttpClient build() {
        try {
            X509TrustManager trust = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[]{trust}, new SecureRandom());
            SSLSocketFactory sf = ssl.getSocketFactory();
            HostnameVerifier hv = (hostname, session) -> true;
            return new OkHttpClient.Builder()
                    .sslSocketFactory(sf, trust)
                    .hostnameVerifier(hv)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(20, TimeUnit.SECONDS)
                    .dns(DNS)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .build();
        }
    }
}
