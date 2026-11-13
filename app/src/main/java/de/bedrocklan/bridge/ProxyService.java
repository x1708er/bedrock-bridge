package de.bedrocklan.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.IOException;
import java.net.BindException;

public class ProxyService extends Service {
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    private static final String CHANNEL = "bridge";

    private final Object lock = new Object();
    private ProxyEngine engine;
    private boolean destroyed;
    private boolean failed;

    private WifiManager.MulticastLock multicastLock;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        Guard.install(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getStringExtra(EXTRA_HOST) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final String host = intent.getStringExtra(EXTRA_HOST);
        final int port = intent.getIntExtra(EXTRA_PORT, 19132);

        goForeground(host, port);
        State.running = true;
        State.status = "Starte …";
        acquireLocks();

        new Thread(() -> {
            ProxyEngine e = new ProxyEngine(host, port);
            try {
                e.start();
            } catch (IOException ex) {
                failed = true;
                State.running = false;
                if (ex instanceof BindException) {
                    State.status = "Port 19132 ist belegt. Läuft schon eine andere Brücken-App? Dann diese beenden.";
                } else {
                    State.status = "Start fehlgeschlagen: " + ex.getMessage();
                }
                stopSelf();
                return;
            }
            synchronized (lock) {
                if (destroyed) {
                    e.stop();
                } else {
                    engine = e;
                }
            }
        }, "engine-start").start();

        return START_NOT_STICKY;
    }

    private void goForeground(String host, int port) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, "Brücke läuft", NotificationManager.IMPORTANCE_LOW));
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Bedrock LAN-Brücke läuft")
                .setContentText(host + ":" + port)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(1, n);
        }
    }

    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("bedrockbridge:multicast");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
            int mode = Build.VERSION.SDK_INT >= 29
                    ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
            wifiLock = wm.createWifiLock(mode, "bedrockbridge:wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bedrockbridge:cpu");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            destroyed = true;
            if (engine != null) {
                engine.stop();
                engine = null;
            }
        }
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        State.running = false;
        State.clients = 0;
        if (!failed) State.status = "Aus";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
