package de.bedrocklan.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reine Favoriten-Logik: Normalisierung, exakte Dedupe, alphabetische Sortierung. */
public final class Favorites {

    public static final class Entry {
        public final String address;
        public final String name;

        public Entry(String address, String name) {
            this.address = address;
            this.name = name;
        }
    }

    private Favorites() {}

    /** Entfernt ungültige und doppelte Einträge (nach normalisierter Adresse) und sortiert alphabetisch. */
    public static List<Entry> sanitize(List<Entry> in) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry e : in) {
            String key = ServerAddress.normalize(e.address);
            if (key == null) continue;
            String trimmedName = e.name == null ? null : e.name.trim();
            String name = trimmedName == null || trimmedName.isEmpty() ? null : trimmedName;
            Entry prev = map.get(key);
            if (prev == null) {
                map.put(key, new Entry(e.address.trim(), name));
            } else if (prev.name == null && name != null) {
                map.put(key, new Entry(prev.address, name));
            }
        }
        List<Entry> out = new ArrayList<>(map.values());
        out.sort((x, y) -> {
            int c = sortKey(x).compareTo(sortKey(y));
            if (c != 0) return c;
            return String.valueOf(ServerAddress.normalize(x.address))
                    .compareTo(String.valueOf(ServerAddress.normalize(y.address)));
        });
        return out;
    }

    public static String displayName(Entry e) {
        return e.name != null ? e.name : e.address;
    }

    private static String sortKey(Entry e) {
        return displayName(e).toLowerCase(Locale.ROOT);
    }
}
