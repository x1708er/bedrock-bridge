package de.bedrocklan.bridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.net.BindException;

import de.bedrocklan.bridge.State.Phase;
import de.bedrocklan.bridge.discovery.Discovery;

public class ProxyService extends Service {
    static final String EXTRA_HOST = "host";
    static final String EXTRA_PORT = "port";
    static final String ACTION_STOP = "de.bedrocklan.bridge.action.STOP";
    private static final String CHANNEL = "bridge";
    private static final int NOTIFY_ID = 1;

    private final Object lock = new Object();
    private ProxyEngine engine;
    private boolean destroyed;
    private boolean failed;
    private boolean starting;
    private String host;
    private int port;

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
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            handleStop();
            return START_NOT_STICKY;
        }
        String h = null;
        int p = 19132;
        if (intent != null && intent.getStringExtra(EXTRA_HOST) != null) {
            h = intent.getStringExtra(EXTRA_HOST);
            p = intent.getIntExtra(EXTRA_PORT, 19132);
        } else {
            // Neustart nach Prozess-Kill: zuletzt in MainActivity gespeicherte Adresse bestmöglich wiederherstellen.
            String saved = getSharedPreferences("MainActivity", MODE_PRIVATE).getString("server", "");
            ServerAddress addr = ServerAddress.parse(saved);
            if (addr != null) {
                h = addr.host;
                p = addr.port;
            }
        }
        if (h == null) {
            State.running = false;
            State.setPhase(Phase.OFF, getString(R.string.status_no_address));
            stopSelf();
            return START_NOT_STICKY;
        }
        synchronized (lock) {
            if (starting || engine != null) return START_STICKY;
            starting = true;
        }
        host = h;
        port = p;
        State.running = true;
        State.status = getString(R.string.status_starting);
        State.setPhase(Phase.STARTING, host + ":" + port);
        Diagnostics.log("service", "Starte Brücke für " + host + ":" + port);
        acquireLocks();
        goForeground();

        new Thread(() -> {
            ProxyEngine e = new ProxyEngine(getApplicationContext(), host, port);
            try {
                e.start();
            } catch (Throwable ex) {
                failed = true;
                synchronized (lock) {
                    starting = false;
                }
                State.running = false;
                String msg;
                if (ex instanceof BindException) {
                    msg = getString(R.string.status_port_busy, Discovery.PORT);
                } else {
                    msg = getString(R.string.status_start_failed, String.valueOf(ex));
                }
                State.status = msg;
                State.setPhase(Phase.ERROR, msg);
                Diagnostics.log("service", "Start fehlgeschlagen: " + msg);
                postNotification();
                stopSelf();
                return;
            }
            synchronized (lock) {
                starting = false;
                if (destroyed) {
                    e.stop();
                } else {
                    engine = e;
                }
            }
            if (!destroyed) {
                State.setPhase(Phase.RUNNING, getString(R.string.notify_channel));
                postNotification();
            }
        }, "engine-start").start();

        return START_STICKY;
    }

    private void handleStop() {
        synchronized (lock) {
            destroyed = true;
            if (engine != null) {
                engine.stop();
                engine = null;
            }
            starting = false;
        }
        releaseLocks();
        State.running = false;
        State.clients = 0;
        State.status = getString(R.string.status_off);
        State.setPhase(Phase.OFF, getString(R.string.status_stopped));
        Diagnostics.log("service", "Über Stop-Aktion beendet");
        stopSelf();
    }

    private Notification.Builder builder() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(
                this, 1, new Intent(this, ProxyService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        String title = host != null ? host + ":" + port : getString(R.string.app_title);
        String text = State.clients > 0 ? getString(R.string.notify_connected)
                : getString(R.string.notify_disconnected);
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_bridge_notify)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_bridge_notify),
                        getString(R.string.action_stop), stop).build())
                .setOngoing(true);
    }

    private void goForeground() {
        Notification n = builder().build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFY_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFY_ID, n);
        }
    }

    private void postNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFY_ID, builder().build());
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

    private void releaseLocks() {
        if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
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
        releaseLocks();
        State.running = false;
        State.clients = 0;
        if (!failed) State.status = getString(R.string.status_off);
        State.setPhase(failed ? Phase.ERROR : Phase.OFF, State.status);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}