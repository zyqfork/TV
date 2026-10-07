package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.storage.NetworkPlayResolver;
import com.github.catvod.utils.Path;
import com.p2p.P2PClass;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.List;

public class JianPian implements Source.Extractor {

    private P2PClass p2p;
    private String path;

    @Override
    public boolean match(Uri uri) {
        // Saved FTP storage URLs are opaque profile IDs, not legacy JianPian links.
        // Leave them unchanged so engine selection and the FTP data source see the real scheme.
        String scheme = UrlUtil.scheme(uri);
        if ("ftp".equals(scheme) && NetworkPlayResolver.isNetworkPlayUrl(uri.toString())) return false;
        return List.of("tvbox-xg", "jianpian", "ftp").contains(scheme);
    }

    private void init() {
        if (p2p == null) p2p = new P2PClass();
    }

    @Override
    public String fetch(String url) throws Exception {
        init();
        stop();
        check();
        start(url);
        return "http://127.0.0.1:" + p2p.port + "/" + URLEncoder.encode(UrlUtil.path(path), "GBK");
    }

    private void check() {
        double cache = Path.size(Path.jpa());
        double total = cache + Path.available(Path.jpa());
        if (total <= 0) return;
        int percent = (int) (cache / total * 100);
        if (percent > 10) Path.clear(Path.jpa());
    }

    private void start(String url) {
        try {
            path = URLDecoder.decode(url).split("\\|")[0];
            path = path.replace("jianpian://pathtype=url&path=", "");
            path = path.replace("tvbox-xg://", "").replace("tvbox-xg:", "");
            path = path.replace("xg://", "ftp://").replace("xgplay://", "ftp://");
            p2p.P2Pdoxstart(path.getBytes("GBK"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void stop() {
        try {
            if (p2p == null || path == null) return;
            p2p.P2Pdoxpause(path.getBytes("GBK"));
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            path = null;
        }
    }

    @Override
    public void exit() {
    }
}
