package com.dabao.proxy;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import hev.htproxy.TProxyService;

/**
 * VpnService + hev-socks5-tunnel（.so）把系统流量接入 sing-box 的 SOCKS5（127.0.0.1:7890）。
 * 不用 hev 独立可执行文件：hev 自己建不了 tun，必须用 VpnService 拿到的 tun fd。
 */
public class ProxyVpnService extends VpnService {
    private static volatile boolean running = false;
    private ParcelFileDescriptor tun = null;
    private Thread hevThread = null;

    public static boolean isRunning() { return running; }
    public static boolean hevRunning() {
        try { return TProxyService.TProxyIsRunning(); } catch (Throwable t) { return false; }
    }

    public static void stopStatic(Context ctx) {
        try { ctx.stopService(new Intent(ctx, ProxyVpnService.class)); } catch (Exception ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("xinghe", "星核代理", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("星核代理 VPN 运行中");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopTunnel(); stopSelf(); return START_NOT_STICKY; }
        if (running) return START_STICKY;
        try {
            Intent launch = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, launch,
                    Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, "xinghe") : new Notification.Builder(this);
            nb.setContentTitle("星核代理运行中").setContentText("系统流量经 sing-box 代理")
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentIntent(pi).setOngoing(true);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(1, nb.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, nb.build());
            }

            Builder b = new Builder();
            b.setSession("星核代理");
            b.addAddress("10.8.0.2", 32);
            b.addRoute("0.0.0.0", 0);
            b.addDnsServer("198.18.0.2");
            b.setMtu(8500);
            try { b.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { /* 防止 sing-box 子进程流量回环 */ }
            try {
                android.content.SharedPreferences p = getSharedPreferences("xinghe", Context.MODE_PRIVATE);
                String mode = p.getString("xh_app_mode", "off");
                java.util.Set<String> list = p.getStringSet("xh_app_list", new java.util.HashSet<String>());
                if ("allow".equals(mode)) { for (String pkg : list) { try { b.addAllowedApplication(pkg); } catch (Exception ignored) {} } }
                else if ("deny".equals(mode)) { for (String pkg : list) { try { b.addDisallowedApplication(pkg); } catch (Exception ignored) {} } }
            } catch (Exception ignored) { /* 按 App 分流读取失败时按全部走代理 */ }
            tun = b.establish();
            if (tun == null) { stopSelf(); return START_NOT_STICKY; }
            final int fd = tun.detachFd();
            running = true;

            // hev 配置：tunnel 内网关 198.18.0.1，socks5 走 sing-box 7890，mapdns 198.18.0.2
            File conf = new File(getFilesDir(), "tproxy.conf");
            OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(conf), StandardCharsets.UTF_8);
            w.write("tunnel:\n  mtu: 8500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n");
            w.write("socks5:\n  address: 127.0.0.1\n  port: 7890\n  udp: 'udp'\n");
            w.write("mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 100.64.0.0\n  netmask: 255.192.0.0\n");
            w.write("misc:\n  log-level: warn\n");
            w.close();

            hevThread = new Thread(new Runnable() {
                @Override public void run() {
                    try { TProxyService.TProxyStartService(conf.getAbsolutePath(), fd); } catch (Throwable t) { t.printStackTrace(); }
                }
            }, "hev-tunnel");
            hevThread.start();
        } catch (Exception e) {
            e.printStackTrace();
            stopTunnel();
            stopSelf();
        }
        return START_STICKY;
    }

    private void stopTunnel() {
        running = false;
        try { TProxyService.TProxyStopService(); } catch (Throwable ignored) {}
        try { if (tun != null) tun.close(); } catch (Exception ignored) {}
        tun = null;
        try { stopForeground(true); } catch (Exception ignored) {}
    }

    @Override public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }

    @Override public void onRevoke() {
        stopTunnel();
        super.onRevoke();
    }
}
