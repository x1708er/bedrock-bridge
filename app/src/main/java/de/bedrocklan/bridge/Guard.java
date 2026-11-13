package de.bedrocklan.bridge;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Absturzsicherung: ungefangene Fehler landen in Logcat und crash.log statt
 * die App zu töten; WebRTC-Objekte werden nie aus nativen Callback-Threads
 * heraus geschlossen, sondern über einen eigenen Closer-Thread.
 */
public final class Guard {
    private static final String TAG = "BedrockLanBruecke";
    private static final ExecutorService CLOSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webrtc-closer");
        t.setDaemon(true);
        return t;
    });

    private Guard() {}

    public static void install(final Context c) {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logError("Ungefangener Fehler in Thread " + t.getName(), e);
            write(c, t, e);
            if (t == Looper.getMainLooper().getThread() && prev != null) {
                prev.uncaughtException(t, e);
            }
        });
        log("Sturzsicherung aktiv");
    }

    private static void write(Context c, Thread t, Throwable e) {
        try {
            File dir = c.getExternalFilesDir(null);
            if (dir == null) dir = c.getFilesDir();
            FileWriter w = new FileWriter(new File(dir, "crash.log"), true);
            String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            w.write(time + " [" + t.getName() + "] " + Log.getStackTraceString(e) + "\n\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    /** WebRTC-Objekte schließen, nie aus einem nativen Callback-Thread heraus. */
    public static void closeLater(Runnable r) {
        CLOSER.execute(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            try {
                r.run();
            } catch (Throwable e) {
                logError("Fehler beim Schließen", e);
            }
        });
    }

    public static void log(String msg) {
        Log.d(TAG, msg);
    }

    public static void logError(String msg, Throwable e) {
        Log.e(TAG, msg, e);
    }
}
