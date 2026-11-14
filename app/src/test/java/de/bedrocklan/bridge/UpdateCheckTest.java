package de.bedrocklan.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckTest {

    @Test
    public void neuereVersionErkannt() {
        assertTrue(UpdateCheck.isNewer("0.0.8", "0.0.7"));
        assertTrue(UpdateCheck.isNewer("1.0", "0.9.9"));
        assertTrue(UpdateCheck.isNewer("v0.1.0", "0.0.9"));
        assertTrue(UpdateCheck.isNewer("1.2.3", "1.2.2"));
    }

    @Test
    public void gleicheUndAeltereVersionen() {
        assertFalse(UpdateCheck.isNewer("0.0.7", "0.0.7"));
        assertFalse(UpdateCheck.isNewer("v0.0.7", "0.0.7"));
        assertFalse(UpdateCheck.isNewer("0.0.6", "0.0.7"));
        assertFalse(UpdateCheck.isNewer("1.2", "1.2.3"));
        assertFalse(UpdateCheck.isNewer(null, "0.0.7"));
        assertFalse(UpdateCheck.isNewer("abc", "0.0.7"));
    }

    @Test
    public void vergleichsrichtung() {
        assertTrue(UpdateCheck.compareVersions("2.0.0", "1.9.9") > 0);
        assertTrue(UpdateCheck.compareVersions("1.9.9", "2.0.0") < 0);
        assertEquals(0, UpdateCheck.compareVersions("1.2.3", "v1.2.3"));
    }

    @Test
    public void intervall() {
        long now = 1_000_000_000L;
        assertFalse(UpdateCheck.due(now, now - 1_000));
        assertFalse(UpdateCheck.due(now, now - UpdateCheck.MIN_INTERVAL_MS + 1));
        assertTrue(UpdateCheck.due(now, now - UpdateCheck.MIN_INTERVAL_MS));
        assertTrue(UpdateCheck.due(now, 0));
    }
}
