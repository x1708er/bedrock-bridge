package de.bedrocklan.bridge.auth;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import de.bedrocklan.bridge.Guard;

/**
 * Xbox-Online-Anmeldung für den NetherNet-Upstream, wie der Vanilla-Client:
 * Microsoft-Device-Code → Xbox Live (RPS) → XSTS → PlayFab-SessionTicket →
 * MCToken (authorization.franchise.minecraft-services.net) → Multiplayer-Token
 * (RS256-JWT mit cpk-Claim für die SDP-Identität).
 *
 * Endpunkte live-verifiziert laut gophertunnel/go-xsapi/go-playfab,
 * prismarine-auth und acacia-auth (docs/auth.md).
 */
public final class AccountManager {
    private static final String CLIENT_ID = "00000000403fc600";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String LIVE_CONNECT = "https://login.live.com/oauth20_connect.srf";
    private static final String LIVE_TOKEN = "https://login.live.com/oauth20_token.srf";
    private static final String XBL_USER_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String PLAYFAB_LOGIN = "https://20ca2.playfabapi.com/Client/LoginWithXbox";
    private static final String PLAYFAB_TITLE = "20CA2";
    private static final String SERVICE_URI = "https://authorization.franchise.minecraft-services.net";
    private static final String USER_AGENT = "libhttpclient/1.0.0.0";

    public interface SignInCallback {
        /** Zeigt dem Nutzer den Code und die Verifikations-URL. */
        void onCode(String userCode, String url);

        /** Abschluss: gamertag bei Erfolg, sonst null; error mit Meldung bei Fehlschlag. */
        void onDone(String gamertag, String error);
    }

    private final SharedPreferences prefs;
    private volatile boolean signingIn;
    private volatile boolean cancelSignIn;

    // Laufzeit-Cache
    private String playfabTicket;
    private long playfabValidUntil;
    private String mcTokenHeader;
    private long mcTokenValidUntil;
    private String signedToken;
    private String signedTokenKey;
    private long signedTokenValidUntil;

    public AccountManager(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences("account", Context.MODE_PRIVATE);
    }

    public boolean isSignedIn() {
        return prefs.contains("msa_refresh_token");
    }

    public String gamertag() {
        return prefs.getString("gamertag", null);
    }

    public void signOut() {
        cancelSignIn = true;
        prefs.edit().clear().apply();
        synchronized (this) {
            playfabTicket = null;
            mcTokenHeader = null;
            signedToken = null;
        }
    }

    /** Startet den Device-Code-Login im Hintergrund. */
    public synchronized void signIn(final SignInCallback cb) {
        if (signingIn) return;
        signingIn = true;
        cancelSignIn = false;
        Thread t = new Thread(() -> {
            try {
                deviceLogin(cb);
            } catch (Exception e) {
                Guard.logError("Anmeldung fehlgeschlagen", e);
                cb.onDone(null, "Anmeldung fehlgeschlagen: " + e.getMessage());
            } finally {
                signingIn = false;
            }
        }, "msa-signin");
        t.setDaemon(true);
        t.start();
    }

    private void deviceLogin(SignInCallback cb) throws Exception {
        JSONObject code = parse(httpForm(LIVE_CONNECT,
                "client_id=" + CLIENT_ID
                        + "&scope=" + enc(SCOPE)
                        + "&response_type=device_code"));
        String deviceCode = code.getString("device_code");
        String userCode = code.getString("user_code");
        String verifyUrl = code.optString("verification_url", "https://www.microsoft.com/link");
        int interval = Math.max(code.optInt("interval", 5), 3);
        long deadline = System.currentTimeMillis() + code.optInt("expires_in", 900) * 1000L;
        cb.onCode(userCode, verifyUrl);

        while (!cancelSignIn && System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            if (cancelSignIn) break;
            HttpURLConnection c = open(LIVE_TOKEN);
            try {
                String body = "client_id=" + CLIENT_ID
                        + "&grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                        + "&device_code=" + enc(deviceCode)
                        + "&scope=" + enc(SCOPE);
                write(c, body.getBytes(StandardCharsets.UTF_8));
                int status = c.getResponseCode();
                String resp = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream(), 64 * 1024);
                if (status == 200) {
                    JSONObject token = parse(resp);
                    String refresh = token.optString("refresh_token", "");
                    if (refresh.isEmpty()) throw new IOException("kein Refresh-Token erhalten");
                    prefs.edit().putString("msa_refresh_token", refresh).apply();
                    try {
                        completeProfile();
                    } catch (Exception ignored) {
                    }
                    cb.onDone(gamertag() != null ? gamertag() : "Konto", null);
                    return;
                }
                JSONObject err = parse(resp);
                String error = err.optString("error", "");
                if ("authorization_pending".equals(error) || "slow_down".equals(error)) continue;
                if ("expired_token".equals(error)) {
                    cb.onDone(null, "Code abgelaufen. Bitte erneut versuchen.");
                    return;
                }
                if ("authorization_declined".equals(error)) {
                    cb.onDone(null, "Anmeldung abgelehnt.");
                    return;
                }
                throw new IOException(error);
            } finally {
                c.disconnect();
            }
        }
        if (!cancelSignIn) cb.onDone(null, "Code abgelaufen. Bitte erneut versuchen.");
    }

    /** XUID/Gamertag über die XSTS-Ansprüche ermitteln und speichern. */
    private void completeProfile() throws Exception {
        JSONObject xsts = xstsToken(msaAccessToken());
        JSONObject xui = xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0);
        String xuid = xui.optString("xid", "");
        String gtg = xui.optString("gtg", "");
        SharedPreferences.Editor e = prefs.edit().putString("xuid", xuid);
        if (!gtg.isEmpty()) e.putString("gamertag", gtg);
        e.apply();
    }

    /** Liefert einen frischen MSA-Access-Token (Refresh-Token-Flow). */
    private synchronized String msaAccessToken() throws IOException {
        String refresh = prefs.getString("msa_refresh_token", null);
        if (refresh == null) throw new IOException("nicht angemeldet");
        String resp = httpForm(LIVE_TOKEN,
                "client_id=" + CLIENT_ID
                        + "&grant_type=refresh_token"
                        + "&refresh_token=" + enc(refresh)
                        + "&scope=" + enc(SCOPE));
        try {
            JSONObject token = parse(resp);
            String access = token.getString("access_token");
            String newRefresh = token.optString("refresh_token", "");
            if (!newRefresh.isEmpty()) {
                prefs.edit().putString("msa_refresh_token", newRefresh).apply();
            }
            return access;
        } catch (JSONException e) {
            throw new IOException("Live-Antwort defekt", e);
        }
    }

    private JSONObject xblToken(String msaAccess) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("Properties", new JSONObject()
                            .put("AuthMethod", "RPS")
                            .put("SiteName", "user.auth.xboxlive.com")
                            .put("RpsTicket", "t=" + msaAccess))
                    .put("RelyingParty", "http://auth.xboxlive.com")
                    .put("TokenType", "JWT");
            return parse(httpJson(XBL_USER_AUTH, body, null));
        } catch (JSONException e) {
            throw new IOException("Xbox-Antwort defekt", e);
        }
    }

    private JSONObject xstsToken(String msaAccess) throws IOException {
        try {
            JSONObject xbl = xblToken(msaAccess);
            JSONObject body = new JSONObject()
                    .put("Properties", new JSONObject()
                            .put("SandboxId", "RETAIL")
                            .put("UserTokens", new JSONArray().put(xbl.getString("Token"))))
                    .put("RelyingParty", "http://xboxlive.com")
                    .put("TokenType", "JWT");
            HttpURLConnection c = open(XSTS_AUTH);
            try {
                writeJson(c, body, null);
                int status = c.getResponseCode();
                String resp = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream(), 64 * 1024);
                if (status != 200) {
                    JSONObject err = parse(resp);
                    long xerr = err.optLong("XErr", 0);
                    throw new IOException(xerr == 0 ? "XSTS-Fehler " + status
                            : xboxError(xerr));
                }
                return parse(resp);
            } finally {
                c.disconnect();
            }
        } catch (JSONException e) {
            throw new IOException("XSTS-Antwort defekt", e);
        }
    }

    private static String xboxError(long xerr) {
        if (xerr == 2148916227L) return "Konto wurde von Xbox gesperrt.";
        if (xerr == 2148916229L) return "Konto ist eingeschränkt (elterliche Kontrolle).";
        if (xerr == 2148916233L) return "Kein Xbox-Profil. Auf signup.live.com eines erstellen.";
        if (xerr == 2148916234L) return "Xbox-Nutzungsbedingungen wurden nicht akzeptiert.";
        if (xerr == 2148916235L) return "Region ist von Xbox gesperrt.";
        if (xerr == 2148916236L) return "Altersnachweis erforderlich.";
        if (xerr == 2148916237L) return "Spielzeit-Limit erreicht.";
        if (xerr == 2148916238L) return "Jugendkonto braucht Familiengenehmigung.";
        return "Xbox-Fehler " + xerr;
    }

    private String playfabTicket() throws IOException {
        if (playfabTicket != null && System.currentTimeMillis() < playfabValidUntil) return playfabTicket;
        try {
            JSONObject xsts = xstsToken(msaAccessToken());
            JSONObject xui = xsts.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0);
            String auth = "XBL3.0 x=" + xui.getString("uhs") + ";" + xsts.getString("Token");
            JSONObject body = new JSONObject()
                    .put("TitleId", PLAYFAB_TITLE)
                    .put("CreateAccount", true)
                    .put("XboxToken", auth);
            HttpURLConnection c = open(PLAYFAB_LOGIN);
            try {
                writeJson(c, body, null);
                int status = c.getResponseCode();
                String resp = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream(), 64 * 1024);
                JSONObject o = parse(resp);
                if (status != 200 || !o.optString("status", "").equals("OK")) {
                    throw new IOException("PlayFab: " + o.optString("errorMessage", "HTTP " + status));
                }
                playfabTicket = o.getJSONObject("data").getString("SessionTicket");
                playfabValidUntil = System.currentTimeMillis() + 23L * 3600 * 1000;
                return playfabTicket;
            } finally {
                c.disconnect();
            }
        } catch (JSONException e) {
            throw new IOException("PlayFab-Antwort defekt", e);
        }
    }

    private String mcTokenHeader() throws IOException {
        if (mcTokenHeader != null && System.currentTimeMillis() < mcTokenValidUntil) return mcTokenHeader;
        try {
            JSONObject body = new JSONObject()
                    .put("device", new JSONObject()
                            .put("applicationType", "MinecraftPE")
                            .put("capabilities", JSONObject.NULL)
                            .put("gameVersion", "1.26.50")
                            .put("id", deviceId())
                            .put("isPreview", false)
                            .put("memory", "17179869184")
                            .put("platform", "Windows10")
                            .put("playFabTitleId", PLAYFAB_TITLE)
                            .put("storePlatform", "uwp.store")
                            .put("treatmentOverrides", JSONObject.NULL)
                            .put("type", "Windows10"))
                    .put("user", new JSONObject()
                            .put("language", "de")
                            .put("languageCode", "de-DE")
                            .put("regionCode", "DE")
                            .put("token", playfabTicket())
                            .put("tokenType", "PlayFab"));
            String resp = httpJson(SERVICE_URI + "/api/v1.0/session/start", body, null);
            JSONObject result = parse(resp).getJSONObject("result");
            mcTokenHeader = result.getString("authorizationHeader");
            mcTokenValidUntil = parseTime(result.optString("validUntil", ""),
                    System.currentTimeMillis() + 3600 * 1000L) - 60_000;
            return mcTokenHeader;
        } catch (JSONException e) {
            throw new IOException("MCToken-Antwort defekt", e);
        }
    }

    /**
     * Multiplayer-Token (RS256-JWT mit cpk-Claim) für den eigenen P-384-Schlüssel.
     * Wird in die SDP-Identität des Upstream-Offers eingebettet und von
     * Online-Servern gegen Mojangs JWKS geprüft.
     */
    public synchronized String multiplayerToken(PublicKey key) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(key.getEncoded());
        if (signedToken != null && encoded.equals(signedTokenKey)
                && System.currentTimeMillis() < signedTokenValidUntil) {
            return signedToken;
        }
        try {
            String auth = mcTokenHeader();
            JSONObject body = new JSONObject().put("publicKey", encoded);
            String resp = httpJson(SERVICE_URI + "/api/v1.0/multiplayer/session/start", body, auth);
            JSONObject result = parse(resp).getJSONObject("result");
            signedToken = result.getString("signedToken");
            signedTokenKey = encoded;
            signedTokenValidUntil = parseTime(result.optString("validUntil", ""),
                    System.currentTimeMillis() + 55 * 60 * 1000L) - 60_000;
            Guard.log("Multiplayer-Token ausgestellt");
            return signedToken;
        } catch (JSONException e) {
            throw new IOException("Multiplayer-Token-Antwort defekt", e);
        }
    }

    private String deviceId() {
        String id = prefs.getString("device_id", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString("device_id", id).apply();
        }
        return id;
    }

    private static JSONObject parse(String body) throws IOException {
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            throw new IOException("Antwort ist kein JSON", e);
        }
    }

    private static long parseTime(String iso, long fallback) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            try {
                return Long.parseLong(iso) * 1000L;
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    // ------------------------------------------------------------------ HTTP-Hilfen

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        return c;
    }

    private static void write(HttpURLConnection c, byte[] body) throws IOException {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }
    }

    private static void writeJson(HttpURLConnection c, JSONObject body, String authHeader)
            throws IOException {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (authHeader != null) c.setRequestProperty("Authorization", authHeader);
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(data);
        }
    }

    private static String httpForm(String url, String form) throws IOException {
        HttpURLConnection c = open(url);
        try {
            write(c, form.getBytes(StandardCharsets.UTF_8));
            int status = c.getResponseCode();
            String resp = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream(), 64 * 1024);
            if (status != 200) throw new IOException("HTTP " + status + ": " + resp);
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String httpJson(String url, JSONObject body, String authHeader) throws IOException {
        HttpURLConnection c = open(url);
        try {
            writeJson(c, body, authHeader);
            int status = c.getResponseCode();
            String resp = readAll(status >= 400 ? c.getErrorStream() : c.getInputStream(), 128 * 1024);
            if (status != 200) throw new IOException("HTTP " + status + ": " + resp);
            return resp;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in, int max) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IOException("Antwort zu groß");
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }
}
