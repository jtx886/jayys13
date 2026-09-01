package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import okhttp3.Dns;

/**
 * 影视仓 DNS 解析器（域名映射 + 兜底系统 DNS）
 * 不含 DoH（宿主未启用），保留原 lookup 行为。
 */
public class OkDns implements Dns {

    private final ConcurrentHashMap<String, String> map;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public void clear() {
        map.clear();
    }

    public void addAll(List<String> hosts) {
        map.putAll(hosts.stream().filter(Objects::nonNull).map(host -> host.split("=", 2)).filter(splits -> splits.length == 2).collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim(), (oldHost, newHost) -> newHost)));
    }

    private String get(String hostname) {
        String target = map.get(hostname);
        if (target != null) return target;
        for (Map.Entry<String, String> entry : map.entrySet()) if (Util.containOrMatch(hostname, entry.getKey())) return entry.getValue();
        return hostname;
    }

    @NonNull
    @Override
    public List<InetAddress> lookup(@NonNull String hostname) throws UnknownHostException {
        return Dns.SYSTEM.lookup(get(hostname));
    }
}
