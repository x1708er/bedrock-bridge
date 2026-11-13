# Bedrock LAN-Brücke

**Kostenlos. Werbefrei. Open Source. Kein Xbox-Konto nötig.**

Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste deiner Konsole an (Prinzip wie „phantom“).
Eine Seite, ein Eingabefeld, ein Knopf.

- **NetherNet (WebRTC)** zum Server — wie der offizielle Client, auch wenn RakNet abgeschaltet wird
- **RakNet** im LAN zur Konsole (UDP 19132) — vollständige Terminierung, kein 08/15-Relay
- Automatischer Fallback: Server ohne NetherNet laufen über den klassischen UDP-Relay weiter

## Download
Fertiges APK aus dem neuesten [Release](../../releases) laden und installieren
(„Unbekannte Quellen“ erlauben). Updates lassen sich ohne Deinstallation drüberinstallieren.

### Play Protect warnt?
Das ist ein Heuristik-Filter gegen selbstgeladene APKs, keine echte Malware-Erkennung —
der Quellcode liegt offen. In der Meldung **„Details“ → „Trotzdem installieren“** wählen.
Falls die Schaltfläche fehlt: Play Store → Profil → Play Protect → Einstellungen →
„Apps prüfen“ kurz aus, installieren, wieder an.

## Benutzen
Handy und Konsole im selben WLAN → Serveradresse eintragen → Starten → in Minecraft
auf der Konsole in der LAN-Liste auswählen.

## Selbst bauen
Einfach forken — jedes Push auf `main` baut unter **Actions** ein signiertes Release-APK
(Signaturschlüssel in den Repo-Secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEYSTORE_ALIAS`, `KEYSTORE_KEY_PASSWORD`).

## Technik (Kurzfassung)
RakNet-Server mit vollem Handshake, ACKs, Split-Reassembly und Compression-Handling
für die Konsole; NetherNet via `/v1/join`-Endpoint und WebRTC-DataChannels mit
Segment-Framing zum Server. Nintendo Switch wird nicht unterstützt.
