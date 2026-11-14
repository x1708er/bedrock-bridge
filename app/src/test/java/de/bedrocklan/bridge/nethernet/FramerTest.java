package de.bedrocklan.bridge.nethernet;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

public class FramerTest {

    private static byte[] seg(int remaining, byte[] payload) {
        byte[] out = new byte[payload.length + 1];
        out[0] = (byte) remaining;
        System.arraycopy(payload, 0, out, 1, payload.length);
        return out;
    }

    private static byte[] bytes(int from, int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) b[i] = (byte) (from + i);
        return b;
    }

    @Test
    public void einfacheNachricht_liefertAlles() {
        Framer f = new Framer();
        assertArrayEquals(bytes(1, 5), f.feed(seg(0, bytes(1, 5))));
    }

    @Test
    public void segmentierteNachricht_wirdZusammengesetzt() {
        Framer f = new Framer();
        assertNull(f.feed(seg(1, bytes(1, 3))));
        assertArrayEquals(bytes(1, 8), f.feed(seg(0, bytes(4, 5))));
    }

    @Test
    public void zweiNachrichtenHintereinander() {
        Framer f = new Framer();
        assertArrayEquals(bytes(10, 2), f.feed(seg(0, bytes(10, 2))));
        assertArrayEquals(bytes(30, 1), f.feed(seg(0, bytes(30, 1))));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zuKurzeEingabe_wirdAbgelehnt() {
        new Framer().feed(new byte[1]);
    }

    @Test(expected = IllegalArgumentException.class)
    public void kaputteSegmentkette_wirdAbgelehnt() {
        Framer f = new Framer();
        f.feed(seg(2, bytes(1, 2)));
        f.feed(seg(0, bytes(1, 2)));
    }

    @Test
    public void einByteNutzlast_undDanachNormaleNachricht() {
        Framer f = new Framer();
        // [rest=0][ein Byte Nutzlast] ist die kürzeste mögliche Nachricht.
        assertArrayEquals(new byte[]{0x2a}, f.feed(new byte[]{0, 0x2a}));
        assertArrayEquals(bytes(1, 1), f.feed(seg(0, bytes(1, 1))));
    }
}
