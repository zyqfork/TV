package com.github.catvod.net;

import androidx.annotation.NonNull;

import com.github.catvod.bean.Doh;
import com.github.catvod.utils.Util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class OkDns implements Dns {

    private static final int DOH_FAILURE_LIMIT = 3;

    private final ConcurrentHashMap<String, String> map;
    private volatile Supplier<Doh> supplier;
    private volatile DnsOverHttps doh;
    private volatile int dohFailures;

    public OkDns() {
        this.map = new ConcurrentHashMap<>();
    }

    public synchronized void setDoh(Doh item) {
        this.supplier = null;
        this.dohFailures = 0;
        this.doh = null;
        if (item == null) return;
        HttpUrl url = HttpUrl.parse(item.getUrl().trim());
        if (!isDohUrl(url)) return;
        try {
            OkHttpClient client = new OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(2, TimeUnit.SECONDS).callTimeout(3, TimeUnit.SECONDS).build();
            this.doh = new DnsOverHttps.Builder().client(client).url(url).bootstrapDnsHosts(item.getHosts()).build();
        } catch (RuntimeException ignored) {
            this.doh = null;
        }
    }

    private static boolean isDohUrl(HttpUrl url) {
        if (url == null || url.host() == null || url.host().isEmpty()) return false;
        String scheme = url.scheme();
        return "https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme);
    }

    public synchronized void setDoh(Supplier<Doh> supplier) {
        this.supplier = supplier;
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
        Supplier<Doh> pending = this.supplier;
        if (pending != null) initDoh(pending);
        String target = get(hostname);
        DnsOverHttps current = this.doh;
        if (current == null) return Dns.SYSTEM.lookup(target);
        try {
            List<InetAddress> addresses = current.lookup(target);
            dohFailures = 0;
            return addresses;
        } catch (UnknownHostException e) {
            if (e.getCause() == null) throw e;
            return fallback(target);
        } catch (RuntimeException ignored) {
            return fallback(target);
        }
    }

    private List<InetAddress> fallback(String hostname) throws UnknownHostException {
        if (++dohFailures >= DOH_FAILURE_LIMIT) doh = null;
        return Dns.SYSTEM.lookup(hostname);
    }

    private synchronized void initDoh(Supplier<Doh> supplier) {
        if (supplier != this.supplier) return;
        Doh item;
        try {
            item = supplier.get();
        } catch (RuntimeException ignored) {
            this.supplier = null;
            this.doh = null;
            return;
        }
        setDoh(item);
    }
}
