package de.bedrocklan.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;


import org.json.JSONObject;

public class MainActivity extends Activity {
    private EditText input;
    private Button button;
    private TextView status;
    private String localError;
    private TextView updateRow;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateUi();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Bedrock LAN-Brücke");
        title.setTextSize(24);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Serveradresse (mit oder ohne Port):");
        hint.setPadding(0, dp(24), 0, dp(4));
        root.addView(hint);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("z. B. play.beispiel.de:19132");
        input.setText(getPreferences(MODE_PRIVATE).getString("server", ""));
        root.addView(input);

        button = new Button(this);
        button.setTextSize(20);
        button.setOnClickListener(v -> onButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        bp.topMargin = dp(16);
        root.addView(button, bp);

        status = new TextView(this);
        status.setTextSize(18);
        status.setPadding(0, dp(24), 0, dp(24));
        root.addView(status);

        updateRow = new TextView(this);
        updateRow.setTextSize(15);
        updateRow.setTextColor(Color.rgb(120, 170, 255));
        updateRow.setPadding(0, dp(20), 0, 0);
        updateRow.setVisibility(android.view.View.GONE);
        root.addView(updateRow);

        TextView help = new TextView(this);
        help.setText("So geht's:\n"
                + "1. Handy und Konsole/PC im selben WLAN.\n"
                + "2. Serveradresse eingeben, „Starten“ drücken.\n"
                + "3. Minecraft öffnen und in der Liste der LAN-Spiele den Server wählen.\n\n"
                + "Die App muss laufen, solange ihr spielt. Danach „Stoppen“ drücken.");
        root.addView(help);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        checkForUpdate();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void onButton() {
        localError = null;
        if (State.running) {
            stopService(new Intent(this, ProxyService.class));
            State.running = false;
            State.status = "Aus";
            updateUi();
            return;
        }
        Object[] target = parse(input.getText().toString());
        if (target == null) {
            localError = "Das ist keine gültige Adresse. Beispiel: play.beispiel.de:19132";
            updateUi();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("server", input.getText().toString().trim()).apply();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);

        Intent i = new Intent(this, ProxyService.class);
        i.putExtra(ProxyService.EXTRA_HOST, (String) target[0]);
        i.putExtra(ProxyService.EXTRA_PORT, (Integer) target[1]);
        State.running = true;
        State.status = "Starte …";
        startForegroundService(i);
        updateUi();
    }

    private void updateUi() {
        boolean on = State.running;
        button.setText(on ? "Stoppen" : "Starten");
        input.setEnabled(!on);
        if (localError != null) {
            status.setText(localError);
            status.setTextColor(Color.rgb(255, 120, 120));
        } else {
            status.setText(State.status);
            status.setTextColor(on ? Color.rgb(120, 220, 120) : Color.LTGRAY);
        }
    }

    /** Prüft die neueste GitHub-Release gegen die installierte Version. */
    private void checkForUpdate() {
        Thread t = new Thread(() -> {
            try {
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(
                        "https://api.github.com/repos/x1708er/bedrock-bridge/releases/latest")
                        .openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("User-Agent", "BedrockLanBruecke-UpdateCheck");
                try (java.io.InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    JSONObject release = new JSONObject(out.toString("UTF-8"));
                    String tag = release.optString("tag_name", "").replaceFirst("^v", "");
                    String url = release.optString("html_url", "");
                    String current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                    if (!tag.isEmpty() && !url.isEmpty() && isNewer(tag, current)) {
                        handler.post(() -> {
                            updateRow.setText("Update verfügbar: v" + tag + " – tippen zum Herunterladen");
                            updateRow.setVisibility(android.view.View.VISIBLE);
                            updateRow.setOnClickListener(v ->
                                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))));
                        });
                    }
                }
            } catch (Exception ignored) {
                // kein Internet oder GitHub nicht erreichbar: still bleiben
            }
        }, "update-check");
        t.setDaemon(true);
        t.start();
    }

    private static boolean isNewer(String candidate, String current) {
        int[] a = versionParts(candidate);
        int[] b = versionParts(current);
        for (int i = 0; i < 3; i++) {
            if (a[i] != b[i]) return a[i] > b[i];
        }
        return false;
    }

    private static int[] versionParts(String v) {
        int[] out = new int[3];
        String[] parts = v.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < 3; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (Exception e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Akzeptiert "host", "host:port", "host port", auch mit "minecraft://" davor. */
    private static Object[] parse(String raw) {
        String s = raw.trim().replaceAll("^[a-zA-Z]+://", "").replaceAll("/.*$", "");
        if (s.isEmpty()) return null;
        String[] t = s.split("\\s+");
        String host;
        int port = 19132;
        try {
            if (t.length == 1) {
                host = t[0];
                int idx = host.lastIndexOf(':');
                if (idx >= 0) {
                    port = Integer.parseInt(host.substring(idx + 1));
                    host = host.substring(0, idx);
                }
            } else if (t.length == 2) {
                host = t[0];
                port = Integer.parseInt(t[1]);
            } else {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (host.isEmpty() || port < 1 || port > 65535) return null;
        return new Object[]{host, port};
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
