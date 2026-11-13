# Bedrock LAN-Brücke

Zeigt einen öffentlichen Bedrock-Server in der LAN-Liste deiner Konsole an (Prinzip wie „phantom“).
Eine Seite, ein Eingabefeld, ein Knopf. Keine Werbung, keine Abhängigkeiten.

## APK bauen (ohne Android Studio)
1. Neues GitHub-Repository anlegen, den Inhalt dieses Ordners hochladen (inkl. `.github`).
2. Reiter **Actions** → „APK bauen“ läuft automatisch (oder „Run workflow“).
3. Fertigen Lauf öffnen → unten unter **Artifacts** `BedrockLanBruecke-APK` laden, entpacken.
4. `app-debug.apk` aufs Handy kopieren und installieren („Unbekannte Quellen“ erlauben).

Alternativ: Ordner in Android Studio öffnen und „Build APK“.

## Benutzen
Handy und Konsole im selben WLAN → Serveradresse eintragen → Starten → in Minecraft auf der Konsole
in der LAN-Liste auswählen. Benachrichtigungen erlauben, sonst bleibt der Dienst unsichtbar im Hintergrund.

## Technik
UDP-Port 19132. Beantwortet RakNet-Pings mit dem MOTD des echten Servers (Server-ID und Ports umgeschrieben)
und leitet alle anderen Pakete pro Konsole an den echten Server weiter. Nintendo Switch wird laut phantom nicht unterstützt.
