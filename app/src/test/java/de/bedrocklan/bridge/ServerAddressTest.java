package de.bedrocklan.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ServerAddressTest {

    @Test
    public void hostOnly() {
        ServerAddress a = ServerAddress.parse("play.beispiel.de");
        assertEquals("play.beispiel.de", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void hostPort() {
        ServerAddress a = ServerAddress.parse("play.beispiel.de:19133");
        assertEquals("play.beispiel.de", a.host);
        assertEquals(19133, a.port);
    }

    @Test
    public void hostSpacePort() {
        ServerAddress a = ServerAddress.parse("play.beispiel.de 19140");
        assertEquals("play.beispiel.de", a.host);
        assertEquals(19140, a.port);
    }

    @Test
    public void schemeUndPfadWerdenEntfernt() {
        ServerAddress a = ServerAddress.parse("minecraft://play.beispiel.de:19133/spielen");
        assertEquals("play.beispiel.de", a.host);
        assertEquals(19133, a.port);
    }

    @Test
    public void ipv4MitPort() {
        ServerAddress a = ServerAddress.parse("192.168.1.10:19132");
        assertEquals("192.168.1.10", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void ipv6InKlammernMitPort() {
        ServerAddress a = ServerAddress.parse("[2001:db8::1]:19132");
        assertEquals("2001:db8::1", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void ipv6InKlammernOhnePort() {
        ServerAddress a = ServerAddress.parse("[2001:db8::1]");
        assertEquals("2001:db8::1", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void ipv6LiteralOhneKlammern() {
        ServerAddress a = ServerAddress.parse("2001:db8::1");
        assertEquals("2001:db8::1", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void ipv6LoopbackOhneKlammern() {
        ServerAddress a = ServerAddress.parse("::1");
        assertEquals("::1", a.host);
        assertEquals(19132, a.port);
    }

    @Test
    public void ipv6InKlammernMitAnderemPort() {
        ServerAddress a = ServerAddress.parse("[fe80::a]:19140");
        assertEquals("fe80::a", a.host);
        assertEquals(19140, a.port);
    }

    @Test
    public void ungueltigeEingaben() {
        assertNull(ServerAddress.parse(null));
        assertNull(ServerAddress.parse(""));
        assertNull(ServerAddress.parse("   "));
        assertNull(ServerAddress.parse("host:"));
        assertNull(ServerAddress.parse("host:abc"));
        assertNull(ServerAddress.parse("[schlecht"));
        assertNull(ServerAddress.parse("[]"));
        assertNull(ServerAddress.parse("host:0"));
        assertNull(ServerAddress.parse("host:99999"));
        assertNull(ServerAddress.parse("host 19132 extra"));
        assertNull(ServerAddress.parse(":19132"));
    }

    @Test
    public void multiColonNurAlsValideIpv6() {
        // offensichtlich ungültige Multi-Colon-Hosts werden abgelehnt
        assertNull(ServerAddress.parse("a:b:c:d"));
        assertNull(ServerAddress.parse("gg::1"));
        assertNull(ServerAddress.parse("1:2:3:4:5:6:7:8:9"));
        assertNull(ServerAddress.parse(":::"));
        assertNull(ServerAddress.parse("1::2::3"));
        assertNull(ServerAddress.parse(":nicht:ipv6"));
        assertNull(ServerAddress.parse("2001:db8::x"));
        // valide IPv6-Literale bleiben erlaubt
        assertEquals("2001:db8::1", ServerAddress.parse("2001:db8::1").host);
        assertEquals("fe80::a", ServerAddress.parse("fe80::a").host);
        assertEquals("::1", ServerAddress.parse("::1").host);
        assertEquals("1:2:3:4:5:6:7:8", ServerAddress.parse("1:2:3:4:5:6:7:8").host);
        assertEquals("2001:db8::192.168.1.1", ServerAddress.parse("2001:db8::192.168.1.1").host);
    }

    @Test
    public void normalizeLaesstDefaultPortWeg() {
        assertEquals("play.beispiel.de", ServerAddress.normalize("PLAY.Beispiel.de:19132"));
        assertEquals("play.beispiel.de", ServerAddress.normalize("play.beispiel.de"));
        assertEquals("play.beispiel.de:19133", ServerAddress.normalize("play.beispiel.de:19133"));
        assertEquals("[2001:db8::1]", ServerAddress.normalize("[2001:DB8::1]:19132"));
        assertEquals("[2001:db8::1]", ServerAddress.normalize("2001:db8::1"));
        assertNull(ServerAddress.normalize("!!!"));
    }
}
