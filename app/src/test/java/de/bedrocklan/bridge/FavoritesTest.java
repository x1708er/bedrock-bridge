package de.bedrocklan.bridge;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class FavoritesTest {

    @Test
    public void dedupeUeberNormalisierteAdresse() {
        List<Favorites.Entry> in = Arrays.asList(
                new Favorites.Entry("play.beispiel.de:19132", null),
                new Favorites.Entry("play.beispiel.de", "Zuhause"),
                new Favorites.Entry("PLAY.BEISPIEL.DE", null));
        List<Favorites.Entry> out = Favorites.sanitize(in);
        assertEquals(1, out.size());
        assertEquals("play.beispiel.de:19132", out.get(0).address);
        assertEquals("Zuhause", out.get(0).name);
    }

    @Test
    public void ungueltigeEintraegeWerdenEntfernt() {
        List<Favorites.Entry> out = Favorites.sanitize(Arrays.asList(
                new Favorites.Entry("!!!", null),
                new Favorites.Entry("gültig.de", null)));
        assertEquals(1, out.size());
        assertEquals("gültig.de", out.get(0).address);
    }

    @Test
    public void alphabetischeSortierungGrossKlein() {
        List<Favorites.Entry> out = Favorites.sanitize(Arrays.asList(
                new Favorites.Entry("zeta.example", "Zeta"),
                new Favorites.Entry("alpha.example", "alpha"),
                new Favorites.Entry("beta.example", null)));
        assertEquals("alpha", out.get(0).name);
        assertEquals("beta.example", out.get(1).address);
        assertEquals("Zeta", out.get(2).name);
    }

    @Test
    public void namenWerdenBereinigt() {
        List<Favorites.Entry> out = Favorites.sanitize(Arrays.asList(
                new Favorites.Entry("a.example", "   "),
                new Favorites.Entry("b.example", "  Name  ")));
        assertEquals(null, out.get(0).name);
        assertEquals("Name", out.get(1).name);
        assertEquals("Name", Favorites.displayName(out.get(1)));
        assertEquals("a.example", Favorites.displayName(out.get(0)));
    }

    @Test
    public void leereListe() {
        assertEquals(0, Favorites.sanitize(new ArrayList<>()).size());
    }
}
