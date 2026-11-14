# Bedrock LAN-Brücke

**Kostenlos. Werbefrei. Open Source.**

**Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste von Minecraft an — auf Konsole und PC.**
Eine Seite, ein Eingabefeld, ein Knopf.

[![Release](https://img.shields.io/github/v/release/x1708er/bedrock-bridge?style=flat&label=Release)](../../releases)
[![Downloads](https://img.shields.io/github/downloads/x1708er/bedrock-bridge/total?style=flat&label=Downloads&color=blue)](../../releases)
[![Build](https://img.shields.io/github/actions/workflow/status/x1708er/bedrock-bridge/build.yml?style=flat&label=Build)](../../actions/workflows/build.yml)
[![Plattform](https://img.shields.io/badge/Plattform-Android-3DDC84?style=flat&logo=android&logoColor=white)](#download)
[![Android-Version](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat)](#download)
[![Sprache](https://img.shields.io/badge/Sprache-Java-f89820?style=flat&logo=openjdk&logoColor=white)](#technik-kurzfassung)

![Kostenlos](https://img.shields.io/badge/Kostenlos-brightgreen?style=flat)
![Werbefrei](https://img.shields.io/badge/Werbefrei-brightgreen?style=flat)
![Open Source](https://img.shields.io/badge/Open_Source-brightgreen?style=flat)

---

## Download

Fertiges APK aus dem neuesten [Release](../../releases) laden und installieren
(„Unbekannte Quellen“ erlauben). Updates lassen sich ohne Deinstallation drüberinstallieren.
Die App prüft beim Start selbst auf neue Releases und zeigt einen Download-Link an.

### Play Protect warnt?

Das ist ein Heuristik-Filter gegen selbstgeladene APKs, keine echte Malware-Erkennung —
der Quellcode liegt offen. In der Meldung **„Details“ → „Trotzdem installieren“** wählen.

Falls die Schaltfläche fehlt: Play Store → Profil → Play Protect → Einstellungen →
„Apps prüfen“ kurz aus, installieren, wieder an.

## So funktioniert's

1. App installieren und öffnen.
2. Handy und Konsole/PC mit **demselben WLAN** verbinden.
3. **Serveradresse** in der App eintragen (z. B. `mc.example.net`).
4. **Starten** tippen.
5. In Minecraft in der Freundes-/LAN-Liste den Server auswählen. Fertig.

> **Hinweis:** Die App muss laufen bleiben, solange gespielt wird. Viele Handy-Hersteller
> (Samsung, Xiaomi, Huawei, …) killen Apps im Hintergrund „zum Stromsparen“ — die Verbindung
> bricht dann mitten im Spiel ab. Anleitung für dein Gerät:
> [dontkillmyapp.com](https://dontkillmyapp.com)

## Voraussetzungen

- Android-Smartphone mit **Android 8.0** oder neuer
- Minecraft auf Konsole (Switch wird **nicht** unterstützt) oder PC
- Der Server muss **NetherNet** anbieten, also auf `GET /v1/join` antworten
  (aktuelle BDS/Geyser-Versionen tun das von sich aus)

## Online-Server: Konto-Anmeldung

Server mit aktivem Online-Mode akzeptieren nur Verbindungen mit einem echten
Multiplayer-Token von Mojang. Dafür einmalig oben in der App **„Mit Microsoft-Konto
anmelden“** tippen und den angezeigten Code auf `microsoft.com/link` eingeben
(das Konto, unter dem auf dem Server gespielt werden soll). Die App speichert
nur den Refresh-Token, holt damit automatisch XSTS → PlayFab → MCToken →
Multiplayer-Token und bettet ihn in die WebRTC-Identität ein.

**Wichtig:** Mojangs Anti-Proxy-Binding (Transport-Identität = Login-Identität)
verlangt, dass das Login der Konsole mit demselben Schlüssel signiert ist wie
die Verbindung der App. Die App schreibt deshalb das Login auf das App-Konto um:
Der Brücken-Spieler erscheint auf dem Server als **das App-Konto** (Gamertag/XUID
des angemeldeten Microsoft-Kontos) und nur **ein** Brücken-Spieler kann gleichzeitig
online sein. Das ist das Maximum, das das Protokoll zulässt.

---

## Selbst bauen

Einfach forken — unter **Actions** den Workflow **„APK bauen“** per Hand starten
(**„Run workflow“**), das erzeugt ein signiertes Release-APK
(Signaturschlüssel in den Repo-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEYSTORE_ALIAS`, `KEYSTORE_KEY_PASSWORD`).

## Technik (Kurzfassung)

- **Komplett ohne RakNet:** LAN-Discovery über das offizielle NetherNet-Protokoll (UDP 7551),
  mit AES/HMAC nach Spec
- **WebRTC end-to-end:** Geräte verbinden sich per WebRTC mit der App, die App per WebRTC mit dem Server
- Die App ist ein WebRTC-Answerer für Geräte im LAN und ein WebRTC-Client zum Server
- Serverliste, Spielerzahl, Version und Nonce kommen live aus dem `/v1/join`-Endpoint des Servers
- Nachrichten werden als komplette NetherNet-Frames 1:1 durchgereicht
- Nintendo Switch wird nicht unterstützt

## Lizenzen & Namensnennung

Die App bündelt **stream-webrtc-android** (Apache 2.0, inkl. Google-libwebrtc
unter BSD-3). Protokoll-Implementierungen sind Java-Portierungen bzw.
Ableitungen von:

| Projekt | Lizenz | Grundlage für |
|---|---|---|
| [df-mc/go-nethernet](https://github.com/df-mc/go-nethernet) | MIT (Dragonfly Tech) | NetherNet-LAN-Discovery, Signaling, Segment-Framing, Identitäts-Assertionen |
| [df-mc/nethernet-spec](https://github.com/df-mc/nethernet-spec) | — (Spec, keine Lizenzdatei) | Protokollbeschreibung LAN-Discovery |
| [Sandertv/gophertunnel](https://github.com/Sandertv/gophertunnel) | MIT (Sandertv) | Xbox-Online-Anmeldung (MSA → XSTS → PlayFab → MCToken) |
| [GeyserMC/Geyser](https://github.com/GeyserMC/Geyser) | MIT (GeyserMC) | Transport-Identity-Binding, Login-Validierung |
| [CloudburstMC/Network](https://github.com/CloudburstMC/Network) | Apache 2.0 (CloudburstMC) | HTTP-Signaling (`/v1/join`), Identity-Validierung |
| [CloudburstMC/Protocol](https://github.com/CloudburstMC/Protocol) | Apache 2.0 (CloudburstMC) | Login-Envelope-Format |
| [acacia-rs/acacia](https://github.com/acacia-rs/acacia) | MIT (Hexay) | Doku der Authentifizierungskette (`docs/auth.md`) |
| [GetStream/webrtc-android](https://github.com/GetStream/webrtc-android) | Apache 2.0 | Gebündelte WebRTC-Bibliothek |

Vollständige Lizenztexte und Copyright-Hinweise:
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)
