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
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.bedrocklan.bridge.State.Phase;
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
    private static final int AMBER = 0xFFFFB862;

    private static final long STARTING_TIMEOUT_MS = 10_000L;

    private EditText input;
    private Button button;
    private TextView status;
    private TextView statusTime;
    private String localError;
    private AccountManager account;
    private TextView accountStatus;
    private Button accountButton;
    private TextView notifHint;
    private TextView notifSettings;
    private TextView updateRow;
    private TextView serverName;
    private TextView serverMeta;
    private LinearLayout serverCard;
    private LinearLayout favCard;
    private LinearLayout favList;
    private long startRequestedAt;
    private String pendingUpdateTag;
    private String pendingUpdateUrl;
    private String pendingUpdateNotes;
    private static volatile boolean updateCheckedInProcess = false;
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
        title.setText(R.string.app_title);
        title.setTextSize(26);
        title.setTextColor(TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subtitle);
        subtitle.setTextSize(13);
        subtitle.setTextColor(MUTED);
        root.addView(subtitle);

        LinearLayout inputCard = card();

        TextView hint = new TextView(this);
        hint.setText(R.string.address_hint);
        hint.setTextSize(13);
        hint.setTextColor(MUTED);
        inputCard.addView(hint);

        input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(R.string.address_example);
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
        favAdd.setText(R.string.add_favorite);
        favAdd.setTextSize(13);
        favAdd.setTextColor(LINK);
        favAdd.setPadding(dp(2), dp(12), dp(2), 0);
        favAdd.setClickable(true);
        favAdd.setFocusable(true);
        favAdd.setContentDescription(getString(R.string.add_favorite_cd));
        favAdd.setOnClickListener(v -> addFavorite());
        inputCard.addView(favAdd);

        root.addView(inputCard, cardLp());

        favCard = card();
        TextView favTitle = new TextView(this);
        favTitle.setText(R.string.favorites_title);
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
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status);

        statusTime = new TextView(this);
        statusTime.setTextSize(12);
        statusTime.setTextColor(MUTED);
        statusTime.setPadding(dp(4), 0, dp(4), 0);
        statusTime.setVisibility(View.GONE);
        root.addView(statusTime);

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

        notifHint = new TextView(this);
        notifHint.setText(R.string.notif_hint);
        notifHint.setTextSize(13);
        notifHint.setTextColor(MUTED);
        notifHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams nh = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nh.topMargin = dp(10);
        accountCard.addView(notifHint, nh);

        notifSettings = link();
        notifSettings.setText(R.string.notif_settings);
        notifSettings.setVisibility(View.GONE);
        notifSettings.setOnClickListener(v -> openNotificationSettings());
        accountCard.addView(notifSettings);

        root.addView(accountCard, cardLp());

        updateRow = link();
        updateRow.setVisibility(View.GONE);
        root.addView(updateRow);

        LinearLayout helpCard = card();
        TextView help = new TextView(this);
        help.setText(R.string.help_steps);
        help.setTextSize(13);
        help.setTextColor(MUTED);
        help.setLineSpacing(dp(3), 1f);
        helpCard.addView(help);

        TextView battery = new TextView(this);
        battery.setText(R.string.help_battery);
        battery.setTextSize(13);
        battery.setTextColor(MUTED);
        battery.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp2.topMargin = dp(10);
        helpCard.addView(battery, bp2);

        TextView batteryLink = link();
        batteryLink.setText(R.string.battery_link_label);
        batteryLink.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://dontkillmyapp.com/"))));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bl.topMargin = dp(4);
        helpCard.addView(batteryLink, bl);
        root.addView(helpCard, cardLp());

        TextView licenses = link();
        licenses.setText(R.string.licenses_label);
        licenses.setOnClickListener(v -> showLicenses());
        root.addView(licenses);

        TextView diag = link();
        diag.setText(R.string.diag_label);
        diag.setOnClickListener(v -> shareDiagnostics());
        root.addView(diag);

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
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) updateNotifHint();
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
            State.status = getString(R.string.status_off);
            State.setPhase(Phase.OFF, null);
            startRequestedAt = 0L;
            updateUi();
            return;
        }
        ServerAddress target = ServerAddress.parse(input.getText().toString());
        if (target == null) {
            localError = getString(R.string.invalid_address);
            updateUi();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putString("server", input.getText().toString().trim()).apply();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);

        Intent i = new Intent(this, ProxyService.class);
        i.putExtra(ProxyService.EXTRA_HOST, target.host);
        i.putExtra(ProxyService.EXTRA_PORT, target.port);
        State.running = true;
        State.status = getString(R.string.status_starting);
        startRequestedAt = System.currentTimeMillis();
        State.setPhase(Phase.STARTING, target.host + ":" + target.port);
        startForegroundService(i);
        updateUi();
    }

    private void updateUi() {
        Phase phase = State.getPhase();
        boolean on = State.running;
        if (button.getTag() == null || !button.getTag().equals(on)) {
            button.setTag(on);
            button.setText(on ? R.string.action_stop : R.string.action_start);
            setRipple(button, rounded(on ? RED : GREEN, 0));
        }
        input.setEnabled(!on);
        input.setAlpha(on ? 0.6f : 1f);
        accountButton.setEnabled(!on);
        accountButton.setAlpha(on ? 0.6f : 1f);

        String text;
        int color;
        if (localError != null) {
            text = localError;
            color = 0xFFFF7B72;
        } else if (phase == Phase.DEGRADED) {
            text = State.status;
            color = AMBER;
        } else if (phase == Phase.ERROR) {
            text = State.status;
            color = 0xFFFF7B72;
        } else if (phase == Phase.STARTING) {
            text = State.status;
            long since = Math.max(State.getLastChangeMillis(), startRequestedAt);
            if (since > 0 && System.currentTimeMillis() - since > STARTING_TIMEOUT_MS) {
                text = text + "\n" + getString(R.string.status_starting_slow);
            }
            color = on ? 0xFF6EDC7E : MUTED;
        } else {
            text = State.status;
            color = on ? 0xFF6EDC7E : MUTED;
        }
        status.setText(text);
        status.setTextColor(color);

        boolean showTime = phase == Phase.DEGRADED || phase == Phase.ERROR;
        if (showTime && State.getLastChangeMillis() > 0) {
            String t = DateFormat.getTimeInstance(DateFormat.MEDIUM, Locale.getDefault())
                    .format(new Date(State.getLastChangeMillis()));
            statusTime.setText(getString(R.string.status_changed_at, t));
            statusTime.setVisibility(View.VISIBLE);
        } else {
            statusTime.setVisibility(View.GONE);
        }

        String name = State.serverName;
        if (on && name != null && !name.isEmpty()) {
            serverName.setText(name);
            String meta = "";
            if (State.players >= 0) {
                meta = State.maxPlayers > 0
                        ? getString(R.string.meta_players, State.players, State.maxPlayers)
                        : getString(R.string.meta_players_open, State.players);
            }
            if (State.levelName != null && !State.levelName.isEmpty()) {
                meta = joinMeta(meta, State.levelName);
            }
            if (State.version != null && !State.version.isEmpty()) {
                meta = joinMeta(meta, getString(R.string.meta_version, State.version));
            }
            serverMeta.setText(meta);
            serverCard.setVisibility(View.VISIBLE);
        } else {
            serverCard.setVisibility(View.GONE);
        }
        updateNotifHint();
    }

    private String joinMeta(String meta, String part) {
        if (meta.isEmpty()) return part;
        return meta + getString(R.string.meta_separator) + part;
    }

    private void updateNotifHint() {
        boolean denied = Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED;
        notifHint.setVisibility(denied ? View.VISIBLE : View.GONE);
        notifSettings.setVisibility(denied ? View.VISIBLE : View.GONE);
    }

    private void openNotificationSettings() {
        Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        i.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        startActivity(i);
    }

    private void onAccountButton() {
        if (State.running) {
            toast(getString(R.string.account_switch_blocked));
            return;
        }
        List<AccountManager.Account> accounts = account.accounts();
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
            names[i] = getString(accounts.get(i).id.equals(active)
                    ? R.string.account_selected : R.string.account_unselected, gtg);
        }
        final int[] sel = {checked};
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.account_dialog_title)
                .setSingleChoiceItems(names, checked, (d, which) -> sel[0] = which)
                .setPositiveButton(R.string.account_switch, (d, w) -> {
                    if (!accounts.get(sel[0]).id.equals(active)) {
                        account.setActive(accounts.get(sel[0]).id);
                    }
                    updateAccountUi();
                })
                .setNeutralButton(R.string.account_add, (d, w) -> startSignIn())
                .setNegativeButton(R.string.account_remove, (d, w) -> {
                    account.remove(accounts.get(sel[0]).id);
                    updateAccountUi();
                })
                .show();
    }

    private void startSignIn() {
        accountButton.setEnabled(false);
        accountStatus.setTextColor(MUTED);
        accountStatus.setText(R.string.sign_in_running);
        account.signIn(new AccountManager.SignInCallback() {
            @Override
            public void onCode(String userCode, String url) {
                handler.post(() -> accountStatus.setText(getString(R.string.sign_in_code, url, userCode)));
            }

            @Override
            public void onDone(String gamertag, String error) {
                handler.post(() -> {
                    updateAccountUi();
                    if (gamertag == null) {
                        accountStatus.setTextColor(0xFFFF7B72);
                        accountStatus.setText(error != null ? error : getString(R.string.sign_in_failed));
                    }
                });
            }
        });
    }

    private void updateAccountUi() {
        boolean in = account.isSignedIn();
        String tag = account.gamertag();
        if (tag == null) tag = getString(R.string.account_fallback);
        int count = account.accounts().size();
        accountButton.setEnabled(!State.running);
        accountButton.setAlpha(State.running ? 0.6f : 1f);
        if (in) {
            accountButton.setText(count > 1
                    ? getString(R.string.account_with_arrow, tag) : tag);
            accountStatus.setTextColor(TEXT);
            accountStatus.setText(count > 1
                    ? getString(R.string.account_signed_in_multi, tag, count)
                    : getString(R.string.account_signed_in, tag));
        } else if (count > 0) {
            accountButton.setText(R.string.account_choose);
            accountStatus.setTextColor(MUTED);
            accountStatus.setText(getString(R.string.account_stored, count));
        } else {
            accountButton.setText(R.string.account_sign_in);
            accountStatus.setTextColor(MUTED);
            accountStatus.setText(R.string.account_not_signed_in);
        }
    }

    private void checkForUpdate() {
        SharedPreferences prefs = getSharedPreferences("update", MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (updateCheckedInProcess || !UpdateCheck.due(now, prefs.getLong("last", 0L))) return;
        updateCheckedInProcess = true;
        final SharedPreferences updatePrefs = prefs;
        final long checkedAt = now;
        new Thread(() -> {
            try {
                String current;
                try {
                    current = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                } catch (Exception e) {
                    current = null;
                }
                String tag = null;
                String url = null;
                String notes = null;
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
                        tag = release.optString("tag_name", "").replaceFirst("^v", "");
                        url = release.optString("html_url", "");
                        notes = release.optString("body", "");
                    }
                } catch (Exception e) {
                    // Netzwerkfehler: kein Zeitstempel — der Check wird nicht für eine Stunde gesperrt.
                    return;
                }
                if (tag == null || tag.isEmpty()) return;
                // Zeitstempel erst nach erfolgreichem Check schreiben.
                updatePrefs.edit().putLong("last", checkedAt).apply();
                if (!UpdateCheck.isNewer(tag, current)) {
                    handler.post(() -> {
                        updateRow.setText(R.string.update_current);
                        updateRow.setTextColor(MUTED);
                        updateRow.setClickable(false);
                        updateRow.setVisibility(View.VISIBLE);
                    });
                    return;
                }
                // Update-URL validieren: ohne gültige HTTPS-Adresse kein Dialog.
                if (url == null || !url.startsWith("https://")) return;
                if (tag.equals(updatePrefs.getString("skipped", null))) return;
                final String t = tag;
                final String u = url;
                final String n = notes;
                handler.post(() -> {
                    pendingUpdateTag = t;
                    pendingUpdateUrl = u;
                    pendingUpdateNotes = n;
                    updateRow.setTextColor(LINK);
                    updateRow.setText(getString(R.string.update_available, t));
                    updateRow.setClickable(true);
                    updateRow.setVisibility(View.VISIBLE);
                    updateRow.setOnClickListener(v -> showUpdateDialog());
                });
            } finally {
                updateCheckedInProcess = false;
            }
        }, "update-check").start();
    }

    private void showUpdateDialog() {
        TextView view = new TextView(this);
        view.setText(pendingUpdateNotes == null || pendingUpdateNotes.isEmpty()
                ? getString(R.string.update_notes_title, pendingUpdateTag)
                : pendingUpdateNotes);
        view.setTextSize(13);
        int pad = dp(16);
        view.setPadding(pad, pad, pad, pad);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(view);
        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_notes_title, pendingUpdateTag))
                .setView(scroll)
                .setPositiveButton(R.string.update_download, (d, w) ->
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(pendingUpdateUrl))))
                .setNegativeButton(R.string.update_skip, (d, w) ->
                        getSharedPreferences("update", MODE_PRIVATE).edit()
                                .putString("skipped", pendingUpdateTag).apply())
                .setNeutralButton(R.string.update_later, null)
                .show();
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
                .setTitle(R.string.licenses_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void shareDiagnostics() {
        try {
            File report = Diagnostics.writeReport(getCacheDir());
            Uri uri = FileProvider.getUriForFile(this, "de.bedrocklan.bridge.files", report);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/plain");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.diag_label)));
        } catch (Exception e) {
            toast(getString(R.string.diag_failed));
        }
    }

    // ------------------------------------------------------------------ Favoriten

    private List<Favorites.Entry> loadFavorites() {
        List<Favorites.Entry> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(
                    getSharedPreferences("servers", MODE_PRIVATE).getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Object o = a.get(i);
                if (o instanceof JSONObject) {
                    JSONObject j = (JSONObject) o;
                    String name = j.optString("n", null);
                    out.add(new Favorites.Entry(j.optString("a", ""), name));
                } else {
                    out.add(new Favorites.Entry(a.getString(i), null));
                }
            }
        } catch (Exception ignored) {
        }
        return Favorites.sanitize(out);
    }

    private void saveFavorites(List<Favorites.Entry> list) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Favorites.Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("a", e.address);
                if (e.name != null) o.put("n", e.name);
                a.put(o);
            }
        } catch (Exception ignored) {
        }
        getSharedPreferences("servers", MODE_PRIVATE).edit().putString("list", a.toString()).apply();
    }

    private void renderFavorites() {
        favList.removeAllViews();
        List<Favorites.Entry> favs = loadFavorites();
        favCard.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);
        String activeKey = ServerAddress.normalize(input.getText().toString());
        for (int i = 0; i < favs.size(); i++) {
            final Favorites.Entry entry = favs.get(i);
            String key = ServerAddress.normalize(entry.address);
            boolean active = key != null && key.equals(activeKey);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(4), dp(10), dp(4), dp(10));
            setRipple(row, rounded(0, 0));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(this);
            name.setTextSize(14);
            name.setTextColor(TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            String display = Favorites.displayName(entry);
            name.setText(active ? getString(R.string.favorite_row_active, display) : display);
            col.addView(name);
            if (entry.name != null) {
                TextView addr = new TextView(this);
                addr.setText(entry.address);
                addr.setTextSize(12);
                addr.setTextColor(MUTED);
                addr.setSingleLine(true);
                addr.setEllipsize(android.text.TextUtils.TruncateAt.END);
                col.addView(addr);
            }
            row.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView del = new TextView(this);
            del.setText(R.string.remove_favorite);
            del.setTextSize(13);
            del.setTextColor(LINK);
            del.setClickable(true);
            del.setFocusable(true);
            del.setContentDescription(getString(R.string.remove_favorite_cd, display));
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dp2.leftMargin = dp(12);
            row.addView(del, dp2);
            row.setOnClickListener(v -> {
                if (State.running) {
                    toast(getString(R.string.favorite_change_blocked));
                    return;
                }
                input.setText(entry.address);
                input.setSelection(input.getText().length());
                renderFavorites();
            });
            del.setOnClickListener(v -> confirmRemoveFavorite(entry));
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
            toast(getString(R.string.favorite_empty));
            return;
        }
        if (ServerAddress.parse(addr) == null) {
            toast(getString(R.string.favorite_invalid));
            return;
        }
        String key = ServerAddress.normalize(addr);
        for (Favorites.Entry e : loadFavorites()) {
            if (key.equals(ServerAddress.normalize(e.address))) {
                toast(getString(R.string.favorite_exists));
                return;
            }
        }
        final EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.favorite_name_hint);
        nameInput.setTextSize(15);
        int pad = dp(16);
        nameInput.setPadding(pad, dp(8), pad, 0);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.favorite_name_title)
                .setMessage(getString(R.string.favorite_name_message, key))
                .setView(nameInput)
                .setPositiveButton(R.string.favorite_add_ok, (d, w) -> {
                    String name = nameInput.getText().toString().trim();
                    List<Favorites.Entry> favs = loadFavorites();
                    favs.add(new Favorites.Entry(addr, name.isEmpty() ? null : name));
                    saveFavorites(Favorites.sanitize(favs));
                    renderFavorites();
                    toast(getString(R.string.favorite_added));
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void confirmRemoveFavorite(Favorites.Entry entry) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.remove_favorite_title)
                .setMessage(getString(R.string.remove_favorite_message, entry.address))
                .setPositiveButton(R.string.remove_favorite, (d, w) -> {
                    List<Favorites.Entry> favs = loadFavorites();
                    String key = ServerAddress.normalize(entry.address);
                    List<Favorites.Entry> keep = new ArrayList<>();
                    for (Favorites.Entry e : favs) {
                        String k = ServerAddress.normalize(e.address);
                        if (k == null || !k.equals(key)) keep.add(e);
                    }
                    saveFavorites(keep);
                    renderFavorites();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private TextView link() {
        TextView tv = new TextView(this);
        tv.setTextSize(14);
        tv.setTextColor(LINK);
        tv.setPadding(dp(4), 0, dp(4), dp(8));
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
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
