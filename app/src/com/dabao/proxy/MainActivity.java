package com.dabao.proxy;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

/**
 * 星核代理 — Android 代理客户端（WebView 壳 + 真 sing-box 内核）。
 */
public class MainActivity extends Activity {
    private WebView web;
    private static final int VPN_REQ = 1002;
    private static final int SCAN_REQ = 1003;
    private static final int PERM_REQ = 1004;
    private boolean pendingVpn = false;
    private boolean pendingScan = false;
    private static final AtomicReference<Process> coreProcess = new AtomicReference<>(null);
    private static volatile long coreStartedAt = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (Exception e) { }
                    return true;
                }
                return false;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    @Override protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public void onBackPressed() {
        web.evaluateJavascript("if(window.onBackPressed){onBackPressed()}else{showTab('home')}", null);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQ) {
            pendingVpn = false;
            if (resultCode == RESULT_OK) {
                startVpnService();
                web.evaluateJavascript("if(window.__vpnGranted)__vpnGranted(true)", null);
            } else {
                web.evaluateJavascript("if(window.__vpnGranted)__vpnGranted(false)", null);
                web.evaluateJavascript("if(window.toast)toast('未授权 VPN 权限，无法开启全局代理')", null);
            }
        }
        if (requestCode == SCAN_REQ) {
            if (resultCode == RESULT_OK && data != null) {
                String qr = data.getStringExtra("qr");
                if (qr != null && !qr.trim().isEmpty()) {
                    web.evaluateJavascript("if(window.handleScanned)handleScanned(" + JSONObject.quote(qr) + ")", null);
                }
            }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_REQ) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (pendingScan && granted) {
                pendingScan = false;
                startActivityForResult(new Intent(this, ScanActivity.class), SCAN_REQ);
            } else {
                pendingScan = false;
                web.evaluateJavascript("if(window.toast)toast('需要相机权限才能扫码')", null);
            }
        }
    }

    private void startVpnService() {
        try {
            Intent i = new Intent(this, ProxyVpnService.class);
            i.setAction("START");
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) { e.printStackTrace(); }
    }

    // ---------- sing-box 内核 ----------
    private File homeDir() { File d = new File(getFilesDir(), "sing-box-home"); if (!d.exists()) d.mkdirs(); return d; }
    private File binFile() { File d = new File(getFilesDir(), "bin"); if (!d.exists()) d.mkdirs(); return new File(d, "sing-box"); }
    private File logFile() { return new File(homeDir(), "sing-box.log"); }

    /** 按 Build.SUPPORTED_ABIS 选内核：arm64-v8a > armeabi-v7a > x86_64 > x86 */
    private static String assetForAbi(String abi) {
        if ("arm64-v8a".equals(abi)) return "sing-box-arm64-v8a.gz";
        if ("armeabi-v7a".equals(abi)) return "sing-box-armeabi-v7a.gz";
        if ("x86_64".equals(abi)) return "sing-box-x86_64.gz";
        if ("x86".equals(abi)) return "sing-box-x86.gz";
        return null;
    }
    private static String currentAbi() {
        for (String abi : Build.SUPPORTED_ABIS) { if (assetForAbi(abi) != null) return abi; }
        return "";
    }
    private static String currentAsset() {
        String abi = currentAbi();
        return abi.isEmpty() ? null : assetForAbi(abi);
    }

    /** 给核心文件加执行位并校验：先用 File API，失败再调 chmod 755 补一次 */
    private boolean makeExecutable(File bin) {
        try { bin.setExecutable(true, false); bin.setReadable(true, false); } catch (Exception ignored) {}
        if (bin.canExecute()) return true;
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"chmod", "755", bin.getAbsolutePath()});
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        try { bin.setExecutable(true, false); bin.setReadable(true, false); } catch (Exception ignored) {}
        return bin.canExecute();
    }

    private synchronized String ensureBinary() {
        try {
            String asset = currentAsset();
            if (asset == null) {
                StringBuilder abis = new StringBuilder();
                for (String a : Build.SUPPORTED_ABIS) { if (abis.length() > 0) abis.append(", "); abis.append(a); }
                return new JSONObject().put("ok", false)
                        .put("error", "当前 ABI 不支持：" + abis + "，本 App 仅支持 arm64-v8a / armeabi-v7a / x86_64 / x86").toString();
            }
            File bin = binFile();
            File marker = new File(bin.getParentFile(), "abi.txt");
            if (bin.exists() && bin.length() > 10000000L && marker.exists()) {
                try {
                    BufferedReader mr = new BufferedReader(new InputStreamReader(new FileInputStream(marker), StandardCharsets.UTF_8));
                    String old = mr.readLine(); mr.close();
                    if (asset.equals(old)) {
                        // 早返回前必须先修执行位：旧版解出的文件可能丢了执行位，
                        // 直接返回 ok 会让此后所有版本 exec 永远 EACCES
                        if (makeExecutable(bin)) return "{\"ok\":true}";
                        try { bin.delete(); } catch (Exception ignored) {}
                        try { marker.delete(); } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
            InputStream in = getAssets().open(asset);
            GZIPInputStream gz = new GZIPInputStream(in);
            File tmp = new File(bin.getParentFile(), "sing-box.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[65536]; int n;
            while ((n = gz.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); gz.close(); in.close();
            if (bin.exists()) bin.delete();
            if (!tmp.renameTo(bin)) {
                FileInputStream fi = new FileInputStream(tmp);
                FileOutputStream fo = new FileOutputStream(bin);
                byte[] b2 = new byte[65536]; int k; while ((k = fi.read(b2)) > 0) fo.write(b2, 0, k);
                fi.close(); fo.close(); tmp.delete();
            }
            if (!makeExecutable(bin)) {
                return new JSONObject().put("ok", false)
                        .put("error", "核心文件无法获得执行权限：" + bin.getAbsolutePath() + "，请重装 App 或检查系统限制").toString();
            }
            FileOutputStream mo = new FileOutputStream(marker);
            mo.write(asset.getBytes(StandardCharsets.UTF_8)); mo.close();
            return "{\"ok\":true}";
        } catch (Exception e) { return jsonErr(e); }
    }

    private String jsonErr(Exception e) {
        try {
            String msg = String.valueOf(e);
            if (msg.contains("Permission denied") || msg.contains("error=13")) {
                msg = msg + "（系统拒绝执行核心文件）";
            }
            return new JSONObject().put("ok", false).put("error", msg).toString();
        }
        catch (Exception ex) { return "{\"ok\":false,\"error\":\"unknown\"}"; }
    }

    /** 把 assets 里的 geoip-cn.srs 解到 home（供配置里本地 rule_set 引用；相对路径按工作目录解析） */
    private void ensureGeoipFile(File home) {
        try {
            File dst = new File(home, "geoip-cn.srs");
            if (dst.exists() && dst.length() > 10000) return;
            InputStream in = getAssets().open("geoip-cn.srs");
            FileOutputStream out = new FileOutputStream(dst);
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close(); in.close();
        } catch (Exception ignored) {}
    }

    /** 等待 9090 就绪，最多约 12 秒 */
    private boolean waitApiReady() {
        for (int i = 0; i < 24; i++) {
            try {
                URL u = new URL("http://127.0.0.1:9090/version");
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(500); c.setReadTimeout(500);
                if (c.getResponseCode() == 200) { c.disconnect(); return true; }
                c.disconnect();
            } catch (Exception ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    public class AndroidBridge {
        @JavascriptInterface public String getInfo() {
            try {
                return new JSONObject().put("ok", true)
                        .put("package", getPackageName())
                        .put("app", "星核代理")
                        .put("sing-box", "1.14.4").put("hev", "2.19.3")
                        .put("abi", currentAbi())
                        .put("model", Build.MODEL).put("sdk", Build.VERSION.SDK_INT)
                        .put("coreRunning", coreProcess.get() != null)
                        .put("vpnRunning", ProxyVpnService.isRunning())
                        .toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String getAbi() {
            try {
                org.json.JSONArray arr = new org.json.JSONArray();
                for (String a : Build.SUPPORTED_ABIS) arr.put(a);
                return new JSONObject().put("ok", true)
                        .put("abi", currentAbi())
                        .put("asset", currentAsset() == null ? "" : currentAsset())
                        .put("supportedAbis", arr).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String fetchSubscription(String url, String siteId) {
            try {
                String target = url == null ? "" : url.trim();
                HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
                c.setConnectTimeout(15000); c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "sing-box/1.14.4");
                c.setRequestProperty("Accept", "*/*");
                if (siteId != null && !siteId.trim().isEmpty())
                    c.setRequestProperty("X-Site-Id", siteId.trim());
                int code = c.getResponseCode();
                String userinfo = c.getHeaderField("subscription-userinfo");
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) return new JSONObject().put("ok", false).put("error", "HTTP " + code).toString();
                String enc = c.getContentEncoding();
                if ("gzip".equalsIgnoreCase(enc)) in = new java.util.zip.GZIPInputStream(in);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                in.close();
                String text = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                return new JSONObject().put("ok", code < 400).put("code", code)
                        .put("userinfo", userinfo == null ? "" : userinfo)
                        .put("text", text).put("url", c.getURL().toString()).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        /** 读 sing-box.log 最后 maxLines 行非空行（失败诊断用） */
        private String logTail(int maxLines) {
            try {
                File f = logFile(); if (!f.exists()) return "";
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
                String l; while ((l = r.readLine()) != null) { if (l.trim().length() > 0) { q.addLast(l); while (q.size() > maxLines) q.removeFirst(); } }
                r.close();
                StringBuilder sb = new StringBuilder();
                for (String s : q) sb.append(s).append("\n");
                return sb.toString().trim();
            } catch (Exception ignored) { return ""; }
        }

        /** 启动前用 sing-box check 预检配置：通过返回 ok，失败返回 FATAL 行（供 JS 剔掉坏节点）。
         *  注意：check 不校验出站引用悬空，JS 侧 buildConfig 后还有一层 checkOutboundRefs 自查。 */
        @JavascriptInterface public String validateConfig(String configJson) {
            try {
                String binOk = ensureBinary();
                if (!binOk.contains("\"ok\":true")) return binOk;
                File home = homeDir();
                ensureGeoipFile(home);
                File cfg = new File(home, "config-check.json");
                OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(cfg), StandardCharsets.UTF_8);
                w.write(configJson == null ? "" : configJson); w.close();
                ProcessBuilder pb = new ProcessBuilder(binFile().getAbsolutePath(), "check", "--disable-color", "-c", cfg.getAbsolutePath());
                pb.directory(home);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                StringBuilder out = new StringBuilder();
                BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
                String line; while ((line = r.readLine()) != null) { out.append(line).append("\n"); }
                r.close();
                boolean done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) { try { proc.destroyForcibly(); } catch (Exception ignored) {} return new JSONObject().put("ok", false).put("error", "配置预检超时").toString(); }
                if (proc.exitValue() == 0) return new JSONObject().put("ok", true).toString();
                String text = out.toString(), fatal = "";
                for (String l : text.split("\n")) { if (l.contains("FATAL")) fatal = l; }
                if (fatal.isEmpty()) fatal = text.length() > 500 ? text.substring(0, 500) : text;
                return new JSONObject().put("ok", false).put("error", fatal.trim())
                        .put("logTail", text.length() > 2000 ? text.substring(text.length() - 2000) : text).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String startCore(String configJson) {
            try {
                Process existing = coreProcess.get();
                if (existing != null) {
                    if (existing.isAlive()) {
                        boolean ready = waitApiReady();
                        if (ready) return new JSONObject().put("ok", true).put("already", true).put("ready", true).toString();
                        try { existing.destroy(); try { Thread.sleep(300); } catch (InterruptedException ignored) {} if (existing.isAlive()) existing.destroyForcibly(); } catch (Exception ignored) {}
                    }
                    coreProcess.set(null);
                }
                String binOk = ensureBinary();
                if (!binOk.contains("\"ok\":true")) return binOk;
                File home = homeDir();
                ensureGeoipFile(home);
                File cfg = new File(home, "config.json");
                OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(cfg), StandardCharsets.UTF_8);
                w.write(configJson == null ? "" : configJson); w.close();
                File log = logFile();
                try { new FileOutputStream(log, false).close(); } catch (Exception ignored) {}
                ProcessBuilder pb = new ProcessBuilder(binFile().getAbsolutePath(), "run", "-c", cfg.getAbsolutePath());
                pb.directory(home);
                pb.redirectErrorStream(true);
                pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
                pb.environment().put("HOME", home.getAbsolutePath());
                Process p = pb.start();
                coreProcess.set(p); coreStartedAt = System.currentTimeMillis();
                boolean ready = waitApiReady();
                if (!ready) {
                    try { p.destroy(); try { Thread.sleep(300); } catch (InterruptedException ignored) {} if (p.isAlive()) p.destroyForcibly(); } catch (Exception ignored) {}
                    coreProcess.set(null);
                    return new JSONObject().put("ok", false).put("ready", false)
                            .put("error", "核心已启动但 9090 未就绪，请查看日志")
                            .put("logTail", logTail(15)).toString();
                }
                return new JSONObject().put("ok", true).put("ready", true).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String stopCore() {
            try {
                try { ProxyVpnService.stopStatic(MainActivity.this); } catch (Exception ignored) {}
                Process p = coreProcess.getAndSet(null);
                if (p != null) { p.destroy(); try { Thread.sleep(300); } catch (InterruptedException ignored) {} if (p.isAlive()) p.destroyForcibly(); }
                return new JSONObject().put("ok", true).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String coreStatus() {
            try {
                boolean api = false;
                try { URL u = new URL("http://127.0.0.1:9090/version"); HttpURLConnection c = (HttpURLConnection) u.openConnection(); c.setConnectTimeout(400); c.setReadTimeout(400); api = c.getResponseCode() == 200; c.disconnect(); } catch (Exception ignored) {}
                return new JSONObject().put("ok", true).put("running", coreProcess.get() != null)
                        .put("apiReady", api).put("vpnRunning", ProxyVpnService.isRunning())
                        .put("uptimeSec", coreStartedAt == 0 ? 0 : (System.currentTimeMillis() - coreStartedAt) / 1000).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String getCoreLog() {
            try {
                File f = logFile(); if (!f.exists()) return new JSONObject().put("ok", true).put("log", "").toString();
                BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
                java.util.ArrayList<String> lines = new java.util.ArrayList<>();
                String l; while ((l = r.readLine()) != null) lines.add(l); r.close();
                int from = Math.max(0, lines.size() - 200);
                StringBuilder sb = new StringBuilder();
                for (int i = from; i < lines.size(); i++) sb.append(lines.get(i)).append("\n");
                return new JSONObject().put("ok", true).put("log", sb.toString()).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String apiGet(String path) {
            try {
                URL u = new URL("http://127.0.0.1:9090" + path);
                HttpURLConnection c = (HttpURLConnection) u.openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(5000);
                int code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                if (in != null) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) bos.write(buf, 0, n); in.close(); }
                return new JSONObject().put("ok", code < 400).put("code", code)
                        .put("body", new String(bos.toByteArray(), StandardCharsets.UTF_8)).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String apiPut(String path, String json) { return apiRequest("PUT", path, json); }

        /** PATCH/PUT 用原始 Socket 发，避免 HttpURLConnection 在部分机型上不支持 PATCH */
        @JavascriptInterface public String apiPatch(String path, String json) { return apiRequest("PATCH", path, json); }

        public String apiRequest(String method, String path, String json) {
            try {
                byte[] body = json.getBytes(StandardCharsets.UTF_8);
                Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", 9090), 5000); s.setSoTimeout(5000);
                OutputStream out = s.getOutputStream();
                String head = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1:9090\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.UTF_8)); out.write(body); out.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String status = r.readLine(); int code = 200;
                try { code = Integer.parseInt(status.split(" ")[1]); } catch (Exception ignored) {}
                String line; int len = 0;
                while ((line = r.readLine()) != null && !line.isEmpty()) {
                    if (line.toLowerCase().startsWith("content-length:")) { try { len = Integer.parseInt(line.substring(15).trim()); } catch (Exception ignored) {} }
                }
                char[] cb = new char[Math.max(0, len)]; int read = 0;
                while (read < len) { int k = r.read(cb, read, len - read); if (k < 0) break; read += k; }
                s.close();
                return new JSONObject().put("ok", code == 204 || code < 400).put("code", code)
                        .put("body", new String(cb, 0, read)).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        /** 读 /traffic 流的第一行（单次快照，不断开轮询） */
        @JavascriptInterface public String trafficOnce() {
            try {
                Socket s = new Socket(); s.connect(new InetSocketAddress("127.0.0.1", 9090), 3000); s.setSoTimeout(4000);
                OutputStream out = s.getOutputStream();
                out.write("GET /traffic HTTP/1.1\r\nHost: 127.0.0.1:9090\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8)); out.flush();
                BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                String line, json = "";
                while ((line = r.readLine()) != null) {
                    if (line.trim().startsWith("{")) { json = line.trim(); break; }
                }
                try { s.close(); } catch (Exception ignored) {}
                return new JSONObject().put("ok", !json.isEmpty()).put("body", json).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String tcpPing(String host, int port) {
            long t0 = System.currentTimeMillis();
            try {
                Socket s = new Socket(); s.connect(new InetSocketAddress(host, port), 3000); s.close();
                return new JSONObject().put("ok", true).put("ms", System.currentTimeMillis() - t0).toString();
            } catch (Exception e) {
                return "{\"ok\":false,\"ms\":-1}";
            }
        }

        @JavascriptInterface public String connectVpn() {
            runOnUiThread(new Runnable() { @Override public void run() {
                try {
                    Intent prep = VpnService.prepare(MainActivity.this);
                    if (prep != null) { pendingVpn = true; startActivityForResult(prep, VPN_REQ); }
                    else startVpnService();
                } catch (Exception e) { web.evaluateJavascript("if(window.toast)toast(" + JSONObject.quote("启动 VPN 失败：" + e) + ")", null); }
            }});
            return "{\"ok\":true}";
        }

        @JavascriptInterface public String disconnectVpn() {
            try { ProxyVpnService.stopStatic(MainActivity.this); return "{\"ok\":true}"; }
            catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String vpnStatus() {
            try { return new JSONObject().put("ok", true).put("vpnRunning", ProxyVpnService.isRunning())
                    .put("hevRunning", ProxyVpnService.hevRunning()).toString(); }
            catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String saveSettings(String json) {
            try { FileOutputStream o = openFileOutput("settings.json", Context.MODE_PRIVATE); o.write(json.getBytes(StandardCharsets.UTF_8)); o.close(); return "{\"ok\":true}"; }
            catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String loadSettings() {
            try { File f = new File(getFilesDir(), "settings.json"); if (!f.exists()) return "{\"ok\":true,\"json\":\"\"}";
                FileInputStream in = new FileInputStream(f); ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); in.close();
                return new JSONObject().put("ok", true).put("json", new String(b.toByteArray(), StandardCharsets.UTF_8)).toString(); }
            catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String exportConfig(String json, String name) {
            try {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, name == null || name.isEmpty() ? "xinghe-config.json" : name);
                FileOutputStream o = new FileOutputStream(f); o.write(json.getBytes(StandardCharsets.UTF_8)); o.close();
                return new JSONObject().put("ok", true).put("path", f.getAbsolutePath()).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
                ArrayList<JSONObject> out = new ArrayList<>();
                for (ApplicationInfo ai : apps) {
                    boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    boolean upd = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
                    if (sys && !upd) continue;
                    if (ai.packageName.equals(getPackageName())) continue;
                    String label = String.valueOf(pm.getApplicationLabel(ai));
                    out.add(new JSONObject().put("package", ai.packageName).put("label", label));
                }
                Collections.sort(out, new Comparator<JSONObject>() { @Override public int compare(JSONObject a, JSONObject b) { return a.optString("label").compareToIgnoreCase(b.optString("label")); } });
                org.json.JSONArray arr = new org.json.JSONArray();
                for (JSONObject o : out) arr.put(o);
                return new JSONObject().put("ok", true).put("apps", arr).toString();
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public String saveAppRules(String mode, String packagesJson) {
            try {
                SharedPreferences p = getSharedPreferences("xinghe", Context.MODE_PRIVATE);
                Set<String> set = new HashSet<>();
                org.json.JSONArray arr = new org.json.JSONArray(packagesJson == null ? "[]" : packagesJson);
                for (int i = 0; i < arr.length(); i++) set.add(arr.optString(i));
                p.edit().putString("xh_app_mode", mode == null ? "off" : mode).putStringSet("xh_app_list", set).apply();
                return "{\"ok\":true}";
            } catch (Exception e) { return jsonErr(e); }
        }

        @JavascriptInterface public void scanQr() {
            runOnUiThread(new Runnable() { @Override public void run() {
                if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), SCAN_REQ);
                } else {
                    pendingScan = true;
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, PERM_REQ);
                }
            } });
        }

        @JavascriptInterface public void toast(String msg) {
            final String m = msg == null ? "" : msg;
            runOnUiThread(new Runnable() { @Override public void run() { web.evaluateJavascript("if(window.toast)toast(" + JSONObject.quote(m) + ")", null); } });
        }

        @JavascriptInterface public void openUrl(String url) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); } catch (ActivityNotFoundException e) { web.evaluateJavascript("if(window.toast)toast('无法打开链接')", null); }
        }
    }
}
