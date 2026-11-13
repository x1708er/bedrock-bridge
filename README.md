# Bedrock LAN-Brücke

**Kostenlos. Werbefrei. Open Source. Kein Xbox-Konto nötig.**

Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste von Minecraft an —
auf Konsole und PC. Eine Seite, ein Eingabefeld, ein Knopf.

- **Komplett ohne RakNet:** LAN-Discovery über das offizielle NetherNet-Protokoll (UDP 7551)
- **Direkt verbunden:** Die App reicht nur den Verbindungsaufbau (Offer/Answer/Kandidaten) an den Server durch — der Spielverkehr läuft direkt zwischen Konsole/PC und Server
- **Jeder Spieler mit eigenem Konto:** Da die Verbindung direkt zum Server steht, akzeptieren Online-Server jeden Spieler mit dessen eigenem Xbox-Login. Die App braucht selbst kein Konto
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
NetherNet-LAN-Discovery mit AES/HMAC nach Spec (UDP 7551); Verbindungswünsche
der Geräte (WebRTC-Offers) werden mit deren ICE-Kandidaten an `POST /v1/join`
des Servers weitergereicht, das Answer kommt über die Discovery-Signale zurück.
Spielverkehr läuft direkt Gerät↔Server — die App ist kein Teil der Datenverbindung
und nicht dem Anti-Proxy-Binding (Transport-Identität = Login-Identität) ausgesetzt.
Nintendo Switch wird nicht unterstützt.
