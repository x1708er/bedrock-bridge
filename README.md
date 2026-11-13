# Bedrock LAN-Brücke

**Kostenlos. Werbefrei. Open Source. Kein Xbox-Konto nötig.**

Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste von Minecraft an —
auf Konsole und PC. Eine Seite, ein Eingabefeld, ein Knopf.

- **Komplett ohne RakNet:** LAN-Discovery über das offizielle NetherNet-Protokoll (UDP 7551)
- **WebRTC end-to-end:** Geräte verbinden sich per WebRTC mit der App, die App per WebRTC mit dem Server
- Serverliste, Spielerzahl, Version und Nonce kommen live aus dem `/v1/join`-Endpoint des Servers

## Download
Fertiges APK aus dem neuesten [Release](../../releases) laden und installieren
(„Unbekannte Quellen“ erlauben). Updates lassen sich ohne Deinstallation drüberinstallieren.
Die App prüft beim Start selbst auf neue Releases und zeigt einen Download-Link an.

### Play Protect warnt?
Das ist ein Heuristik-Filter gegen selbstgeladene APKs, keine echte Malware-Erkennung —
der Quellcode liegt offen. In der Meldung **„Details“ → „Trotzdem installieren“** wählen.
Falls die Schaltfläche fehlt: Play Store → Profil → Play Protect → Einstellungen →
„Apps prüfen“ kurz aus, installieren, wieder an.

## Konto (Online-Server)
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

## Benutzen
Handy und Konsole/PC im selben WLAN → Serveradresse eintragen → Starten → in Minecraft
in der LAN-Liste auswählen.

## Voraussetzung am Server
Der Server muss NetherNet anbieten, also auf `GET /v1/join` antworten
(aktuelle BDS/Geyser-Versionen tun das von sich aus).

## Selbst bauen
Einfach forken — jedes Push auf `main` baut unter **Actions** ein signiertes Release-APK
(Signaturschlüssel in den Repo-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEYSTORE_ALIAS`, `KEYSTORE_KEY_PASSWORD`).

## Technik (Kurzfassung)
NetherNet-LAN-Discovery mit AES/HMAC nach Spec, WebRTC-Answerer für Geräte im LAN,
WebRTC-Client zum Server, Nachrichten werden als komplette NetherNet-Frames 1:1
durchgereicht. Nintendo Switch wird nicht unterstützt.
