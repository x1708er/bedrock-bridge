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
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private EditText input;
    private Button button;
    private TextView status;
    private String localError;
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

        TextView help = new TextView(this);
        help.setText("So geht's:\n"
                + "1. Handy und Konsole im selben WLAN.\n"
                + "2. Serveradresse eingeben, „Starten“ drücken.\n"
                + "3. Minecraft auf der Konsole öffnen, in der Liste der LAN-Spiele den Server wählen.\n\n"
                + "Die App muss laufen, solange ihr spielt. Danach „Stoppen“ drücken.");
        root.addView(help);

        setContentView(root);

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
