package de.bedrocklan.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.bedrocklan.bridge.auth.AccountManager;

import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int BG = 0xFF13141B;
    private static final int CARD = 0xFF1D1E28;
    private static final int FIELD = 0xFF171821;
    private static final int STROKE = 0xFF2B2C3B;
    private static final int TEXT = 0xFFEDEDF2;
    private static final int MUTED = 0xFF9A9DAF;
    private static final int GREEN = 0xFF4CAF6E;
    private static final int RED = 0xFFE0564F;
    private static final int LINK = 0xFF8AB8FF;

    private EditText input;
    private Button button;
    private TextView status;
    private String localError;
    private AccountManager account;
    private TextView accountStatus;
    private Button accountButton;
    private TextView updateRow;
    private TextView serverName;
    private TextView serverMeta;
    private LinearLayout serverCard;
    private LinearLayout favCard;
    private LinearLayout favList;
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

        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(20));
        root.setBackgroundColor(BG);

        TextView title = new TextView(this);
        title.setText("Bedrock LAN-Brücke");
        title.setTextSize(26);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Minecraft-Bedrock-Server im LAN sichtbar machen");
        subtitle.setTextSize(13);
        subtitle.setTextColor(MUTED);
        root.addView(subtitle);

        LinearLayout inputCard = card();

        TextView hint = new TextView(this);
        hint.setText("Serveradresse (mit oder ohne Port)");
        hint.setTextSize(13);
        hint.setTextColor(MUTED);
        inputCard.addView(hint);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("z. B. play.beispiel.de:19132");
        input.setText(getPreferences(MODE_PRIVATE).getString("server", ""));
        input.setTextSize(15);
        input.setTextColor(TEXT);
        input.setHintTextColor(0x809A9DAF);
        StateListDrawable field = new StateListDrawable();
        field.addState(new int[]{android.R.attr.state_focused}, rounded(FIELD, GREEN));
        field.addState(new int[0], rounded(FIELD, STROKE));
        input.setBackground(field);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(8);
        inputCard.addView(input, ip);

        button = new Button(this);
        button.setTextSize(17);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setRipple(button, rounded(GREEN, 0));
        button.setOnClickListener(v -> onButton());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        bp.topMargin = dp(12);
        inputCard.addView(button, bp);

        TextView favAdd = new TextView(this);
        favAdd.setText("★ Diese Adresse zu Favoriten");
        favAdd.setTextSize(13);
        favAdd.setTextColor(LINK);
        favAdd.setPadding(dp(2), dp(12), dp(2), 0);
        favAdd.setOnClickListener(v -> addFavorite());
        inputCard.addView(favAdd);

        root.addView(inputCard, cardLp());

        favCard = card();
        TextView favTitle = new TextView(this);
        favTitle.setText("Favoriten");
        favTitle.setTextSize(13);
        favTitle.setTextColor(MUTED);
        favCard.addView(favTitle);
        favList = new LinearLayout(this);
        favList.setOrientation(LinearLayout.VERTICAL);
        favCard.addView(favList);
        root.addView(favCard, cardLp());

        status = new TextView(this);
        status.setTextSize(15);
        status.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        status.setPadding(dp(4), 0, dp(4), 0);
        root.addView(status);

        LinearLayout serverCard = card();
        this.serverCard = serverCard;
        serverName = new TextView(this);
        serverName.setTextSize(15);
        serverName.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        serverName.setTextColor(TEXT);
        serverName.setSingleLine(true);
        serverName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        serverCard.addView(serverName);
        serverMeta = new TextView(this);
        serverMeta.setTextSize(13);
        serverMeta.setTextColor(MUTED);
        LinearLayout.LayoutParams smp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        smp.topMargin = dp(2);
        serverCard.addView(serverMeta, smp);
        root.addView(serverCard, cardLp());
        serverCard.setVisibility(View.GONE);

        LinearLayout accountCard = card();

        accountStatus = new TextView(this);
        accountStatus.setTextSize(13);
        accountStatus.setTextColor(MUTED);
        accountCard.addView(accountStatus);

        accountButton = new Button(this);
        accountButton.setTextSize(14);
        accountButton.setTextColor(TEXT);
        accountButton.setAllCaps(false);
        setRipple(accountButton, rounded(0x00000000, 0xFF3B3D52));
        accountButton.setOnClickListener(v -> onAccountButton());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ap.topMargin = dp(10);
        accountCard.addView(accountButton, ap);

        root.addView(accountCard, cardLp());

        updateRow = new TextView(this);
        updateRow.setTextSize(14);
        updateRow.setTextColor(LINK);
        updateRow.setPadding(dp(4), 0, dp(4), dp(12));
        updateRow.setVisibility(View.GONE);
        root.addView(updateRow);

        LinearLayout helpCard = card();
        TextView help = new TextView(this);
        help.setText("So geht's:\n"
                + "1. Handy und Konsole/PC im selben WLAN.\n"
                + "2. Serveradresse eingeben, „Starten“ drücken.\n"
                + "3. Minecraft öffnen und in der Liste der LAN-Spiele den Server wählen.\n\n"
                + "Die App muss laufen, solange ihr spielt. Danach „Stoppen“ drücken.");
        help.setTextSize(13);
        help.setTextColor(MUTED);
        help.setLineSpacing(dp(3), 1f);
        helpCard.addView(help);
        root.addView(helpCard, cardLp());

        TextView licenses = new TextView(this);
        licenses.setText("Lizenzen & Namensnennung");
        licenses.setTextSize(14);
        licenses.setTextColor(LINK);
        licenses.setPadding(dp(4), 0, dp(4), dp(8));
        licenses.setOnClickListener(v -> showLicenses());
        root.addView(licenses);

        account = new AccountManager(this);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().getInsetsController().setSystemBarsAppearance(
                    0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    & ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        updateAccountUi();
        updateUi();
        renderFavorites();
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
        if (button.getTag() == null || !button.getTag().equals(on)) {
            button.setTag(on);
            button.setText(on ? "Stoppen" : "Starten");
            setRipple(button, rounded(on ? RED : GREEN, 0));
        }
        input.setEnabled(!on);
        input.setAlpha(on ? 0.6f : 1f);
        accountButton.setEnabled(!on);
        accountButton.setAlpha(on ? 0.6f : 1f);
        if (localError != null) {
            status.setText(localError);
            status.setTextColor(0xFFFF7B72);
        } else {
            status.setText(State.status);
            status.setTextColor(on ? 0xFF6EDC7E : MUTED);
        }
        String name = State.serverName;
        if (on && name != null && !name.isEmpty()) {
            serverName.setText(name);
            StringBuilder meta = new StringBuilder();
            if (State.players >= 0) {
                meta.append(State.players).append(State.maxPlayers > 0 ? " / " + State.maxPlayers : "")
                        .append(" Spieler");
            }
            if (State.levelName != null && !State.levelName.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append(State.levelName);
            }
            if (State.version != null && !State.version.isEmpty()) {
                if (meta.length() > 0) meta.append(" · ");
                meta.append("v").append(State.version);
            }
            serverMeta.setText(meta.toString());
            serverCard.setVisibility(View.VISIBLE);
        } else {
            serverCard.setVisibility(View.GONE);
        }
    }

    private void onAccountButton() {
        if (State.running) {
            android.widget.Toast.makeText(this,
                    "Zum Wechseln erst die Brücke stoppen.", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<AccountManager.Account> accounts = account.accounts();
        if (accounts.isEmpty()) {
            startSignIn();
            return;
        }
        String active = account.activeId();
        int checked = 0;
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).id.equals(active)) {
                checked = i;
                break;
            }
        }
        String[] names = new String[accounts.size()];
        for (int i = 0; i < accounts.size(); i++) {
            String gtg = accounts.get(i).gamertag;
            names[i] = accounts.get(i).id.equals(active) ? "● " + gtg : "○ " + gtg;
        }
        final int[] sel = {checked};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Microsoft-Konten")
                .setSingleChoiceItems(names, checked, (d, which) -> sel[0] = which)
                .setPositiveButton("Wechseln", (d, w) -> {
                    if (!accounts.get(sel[0]).id.equals(active)) {
                        account.setActive(accounts.get(sel[0]).id);
                    }
                    updateAccountUi();
                })
                .setNeutralButton("Hinzufügen", (d, w) -> startSignIn())
                .setNegativeButton("Entfernen", (d, w) -> {
                    account.remove(accounts.get(sel[0]).id);
                    updateAccountUi();
                })
                .show();
    }

    private void startSignIn() {
        accountButton.setEnabled(false);
        accountStatus.setTextColor(MUTED);
        accountStatus.setText("Anmeldung läuft …");
        account.signIn(new AccountManager.SignInCallback() {
            @Override
            public void onCode(String userCode, String url) {
                handler.post(() -> accountStatus.setText(
                        "Auf einem beliebigen Gerät " + url + " öffnen\nund Code eingeben:\n\n"
                                + userCode + "\n\n(Microsoft-Konto, mit dem Minecraft spielt)"));
            }

            @Override
            public void onDone(String gamertag, String error) {
                handler.post(() -> {
                    updateAccountUi();
                    if (gamertag == null) {
                        accountStatus.setTextColor(0xFFFF7B72);
                        accountStatus.setText(error != null ? error : "Anmeldung fehlgeschlagen.");
                    }
                });
            }
        });
    }

    private void updateAccountUi() {
        boolean in = account.isSignedIn();
        String tag = account.gamertag();
        int count = account.accounts().size();
        accountButton.setEnabled(!State.running);
        accountButton.setAlpha(State.running ? 0.6f : 1f);
        if (in) {
            accountButton.setText(count > 1 ? tag + " ▾" : tag != null ? tag : "Konto");
            accountStatus.setTextColor(TEXT);
            accountStatus.setText("Angemeldet als " + (tag != null ? tag : "Konto")
                    + (count > 1 ? " · " + count + " Konten gespeichert, tippen zum Wechseln" : ""));
        } else if (count > 0) {
            accountButton.setText("Konto wählen ▾");
            accountStatus.setTextColor(MUTED);
            accountStatus.setText(count + " Konto(s) gespeichert – tippen zum Auswählen.");
        } else {
            accountButton.setText("Mit Microsoft-Konto anmelden");
            accountStatus.setTextColor(MUTED);
            accountStatus.setText("Nicht angemeldet. Für Online-Server nötig "
                    + "(Signaling-Identität von Mojang).");
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

    private void showLicenses() {
        TextView view = new TextView(this);
        view.setText(Licenses.text());
        view.setTextSize(13);
        int pad = dp(16);
        view.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Lizenzen")
                .setView(scroll)
                .setPositiveButton("Schließen", null)
                .show();
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

    // ------------------------------------------------------------------ Favoriten

    private java.util.List<String> loadFavorites() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(
                    getSharedPreferences("servers", MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveFavorites(java.util.List<String> list) {
        getSharedPreferences("servers", MODE_PRIVATE).edit()
                .putString("list", new org.json.JSONArray(list).toString()).apply();
    }

    private void renderFavorites() {
        favList.removeAllViews();
        java.util.List<String> favs = loadFavorites();
        favCard.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        for (int i = 0; i < favs.size(); i++) {
            final String addr = favs.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            setRipple(row, rounded(0, 0));
            TextView name = new TextView(this);
            name.setText(addr);
            name.setTextSize(14);
            name.setTextColor(TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            row.addView(name, np);
            TextView del = new TextView(this);
            del.setText("Entfernen");
            del.setTextSize(13);
            del.setTextColor(LINK);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dp2.leftMargin = dp(12);
            row.addView(del, dp2);
            row.setOnClickListener(v -> {
                input.setText(addr);
                input.setSelection(input.getText().length());
            });
            del.setOnClickListener(v -> confirmRemoveFavorite(addr));
            favList.addView(row);
            if (i < favs.size() - 1) {
                View sep = new View(this);
                sep.setBackgroundColor(STROKE);
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                favList.addView(sep, sp);
            }
        }
    }

    private void addFavorite() {
        String addr = input.getText().toString().trim();
        if (addr.isEmpty()) {
            toast("Erst eine Serveradresse eingeben.");
            return;
        }
        if (parse(addr) == null) {
            toast("Das ist keine gültige Adresse.");
            return;
        }
        java.util.List<String> favs = loadFavorites();
        if (favs.contains(addr)) {
            toast("Diese Adresse ist schon in den Favoriten.");
            return;
        }
        favs.add(addr);
        saveFavorites(favs);
        renderFavorites();
        toast("Zu Favoriten hinzugefügt.");
    }

    private void confirmRemoveFavorite(String addr) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Favorit entfernen")
                .setMessage("„" + addr + "“ aus den Favoriten löschen?")
                .setPositiveButton("Entfernen", (d, w) -> {
                    java.util.List<String> favs = loadFavorites();
                    favs.remove(addr);
                    saveFavorites(favs);
                    renderFavorites();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable rounded(int color, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(16));
        if (strokeColor != 0) g.setStroke(dp(1), strokeColor);
        return g;
    }

    private void setRipple(View v, GradientDrawable bg) {
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), bg, null));
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(CARD, STROKE));
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(16);
        return lp;
    }
}
