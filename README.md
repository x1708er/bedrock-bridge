# Bedrock LAN-Brücke

Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste deiner Konsole an (Prinzip wie „phantom“).
Eine Seite, ein Eingabefeld, ein Knopf. Keine Werbung, keine Abhängigkeiten außer WebRTC.

## NetherNet-Unterstützung (Beta)

Da Mojang RakNet für öffentliche Server ablöst, verbindet die App sich wie der offizielle
Client per **NetherNet (WebRTC)** mit dem Server: `GET/POST /v1/join` am Server, dann ein
verschlüsselter DataChannel. Die Konsole bleibt im LAN bei RakNet und hängt an dem
selbstgebauten RakNet-Server der App (Port 19132), der die Verbindung vollständig
terminiert (Handshake, Frames, ACKs, Splits, Kompression).

- Server mit NetherNet-Endpunkt (z. B. aktuelle BDS) → direkter WebRTC-Upstream,
  MOTD aus `GET /v1/join`.
- Server ohne NetherNet → automatischer Fallback auf den klassischen
  RakNet-UDP-Relay (wie vorher).
- Ohne Xbox-Konto: das `/v1/join`-Verfahren des Clients für „ externe Server“ braucht
  kein Signaling bei Mojang.

Erforderlich für NetherNet: Server und App müssen WebRTC-Verbindungen zulassen;
bei strictem NAT kann ein TURN-Server nötig sein (aktuell nur STUN konfiguriert).

## APK bauen (ohne Android Studio)
1. Neues GitHub-Repository anlegen, den Inhalt dieses Ordners hochladen (inkl. `.github`).
2. Reiter **Actions** → „APK bauen“ läuft automatisch (oder „Run workflow“).
3. Fertigen Lauf öffnen → unten unter **Artifacts** `BedrockLanBruecke-APK` laden, entpacken.
4. `app-release.apk` aufs Handy kopieren und installieren („Unbekannte Quellen“ erlauben).

Das Release-APK ist mit einem festen Schlüssel signiert (in GitHub-Secrets hinterlegt),
sodass Updates ohne Deinstallation installierbar bleiben.

### „App wurde als schädlich eingestuft“ (Google Play Protect)

Play Protect blockiert manche selbstgeladene APKs mit einer Warnung – das ist ein
Heuristik-Filter, keine echte Malware-Erkennung (der Quellcode liegt offen in diesem Repo).
So kommst du trotzdem an die Installation:

- In der Play-Protect-Meldung **„Details“** antippen und **„Trotzdem installieren“** wählen
  (teilweise erscheint die Schaltfläche erst nach dem Aufklappen der Details).
- Falls die Schaltfläche fehlt: **Play Store → Profil → Play Protect → Zahnrad →
  „Apps prüfen“** (bzw. „Apps scannen“) vorübergehend deaktivieren, App installieren,
  danach wieder aktivieren.
- Bei Samsung zusätzlich: **Galaxy Store → Play Protect** kann dieselbe Sperre zeigen
  (gleicher Weg). Bei Xiaomi/Huawei heißt die Option „Sicherheit prüfen“ in den
  Sicherheits-App-Einstellungen.

Alternativ: Ordner in Android Studio öffnen und „Build APK“.

## Benutzen
Handy und Konsole im selben WLAN → Serveradresse eintragen → Starten → in Minecraft auf der Konsole
in der LAN-Liste auswählen. Benachrichtigungen erlauben, sonst bleibt der Dienst unsichtbar im Hintergrund.

## Technik
LAN-Seite: RakNet-Server auf UDP 19132 (Unconnected-Ping mit umgeschriebener Server-ID,
Connection-Handshake, Frame-Sets mit ACK/Resend, Split-Reassembly). Upstream: NetherNet über
WebRTC-DataChannels mit Segment-Framing; Bedrock-Batches werden zwischen den Transporten
1:1 verschoben. RakNet-Fallback: transparentes UDP-Relay pro Konsole (Prinzip wie „phantom“).
Nintendo Switch wird laut phantom nicht unterstützt.
