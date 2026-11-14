package de.bedrocklan.bridge.nethernet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NetherNetClientTest {

    @Test
    public void candidateBases_httpsZuerstDannHttpFallback() {
        List<String> bases = NetherNetClient.candidateBases("mc.example.com", 19132);
        assertEquals(4, bases.size());
        assertEquals("https://mc.example.com:19132", bases.get(0));
        assertEquals("http://mc.example.com:19132", bases.get(1));
        assertEquals("https://mc.example.com", bases.get(2));
        assertEquals("http://mc.example.com", bases.get(3));
    }

    @Test
    public void candidateBases_andererPort_nurMitPort() {
        List<String> bases = NetherNetClient.candidateBases("mc.example.com", 19140);
        assertEquals(2, bases.size());
        assertEquals("https://mc.example.com:19140", bases.get(0));
        assertEquals("http://mc.example.com:19140", bases.get(1));
    }

    @Test
    public void candidateBases_portNull_defaultPort() {
        List<String> bases = NetherNetClient.candidateBases("h.example", 0);
        assertEquals("https://h.example:19132", bases.get(0));
        assertEquals("http://h.example:19132", bases.get(1));
    }

    @Test
    public void candidateBases_schemaWirdNormalisiert() {
        List<String> bases = NetherNetClient.candidateBases("http://h.example", 19140);
        assertEquals("https://h.example:19140", bases.get(0));
        assertEquals("http://h.example:19140", bases.get(1));
    }

    @Test
    public void candidateBases_unbekanntesSchema_wirdVerworfen() {
        assertTrue(NetherNetClient.candidateBases("ftp://h.example", 19132).isEmpty());
        assertTrue(NetherNetClient.candidateBases("tcp://h.example", 19132).isEmpty());
    }

    @Test
    public void candidateBases_leerOhneHost() {
        assertTrue(NetherNetClient.candidateBases("", 19132).isEmpty());
        assertTrue(NetherNetClient.candidateBases(null, 19132).isEmpty());
    }

    @Test
    public void candidateBases_ipv6WirdGeklammert() {
        List<String> bases = NetherNetClient.candidateBases("2001:db8::1", 19132);
        assertEquals("https://[2001:db8::1]:19132", bases.get(0));
        assertEquals("http://[2001:db8::1]:19132", bases.get(1));
        assertEquals("https://[2001:db8::1]", bases.get(2));
        assertEquals("http://[2001:db8::1]", bases.get(3));
    }

    @Test
    public void candidateBases_ipv6AndererPort() {
        List<String> bases = NetherNetClient.candidateBases("fe80::a", 19140);
        assertEquals(2, bases.size());
        assertEquals("https://[fe80::a]:19140", bases.get(0));
        assertEquals("http://[fe80::a]:19140", bases.get(1));
    }

    @Test
    public void candidateBes_bereitsGeklammertBleibtEinfach() {
        assertEquals("[2001:db8::1]", NetherNetClient.bracketIpv6("[2001:db8::1]"));
        assertEquals("h.example", NetherNetClient.bracketIpv6("h.example"));
        assertEquals("[::1]", NetherNetClient.bracketIpv6("::1"));
    }

    @Test
    public void parseStatus_vollstaendigesJson() {
        NetherNetClient.Status s = NetherNetClient.parseStatus(
                "{\"name\":\"Mein Server\",\"protocol\":818,\"version\":\"1.21.9\","
                        + "\"level\":\"World\",\"players\":3,\"maxPlayers\":10,"
                        + "\"gameType\":1,\"editor\":false,\"hardcore\":false,"
                        + "\"onlineAuth\":false,\"selfSignedAuth\":true,\"nonce\":\"abcd\"}");
        assertEquals("Mein Server", s.name);
        assertEquals(818, s.protocol);
        assertEquals("1.21.9", s.version);
        assertEquals("World", s.level);
        assertEquals(3, s.players);
        assertEquals(10, s.maxPlayers);
        assertEquals(1, s.gameType);
        assertFalse(s.editor);
        assertFalse(s.hardcore);
        assertFalse(s.onlineAuth);
        assertTrue(s.selfSignedAuth);
        assertEquals("abcd", s.nonce);
    }

    @Test
    public void parseStatus_defaults() {
        NetherNetClient.Status s = NetherNetClient.parseStatus("{}");
        assertEquals("Unbekannt", s.name);
        assertEquals(0, s.protocol);
        assertEquals("?", s.version);
        assertEquals(0, s.players);
        assertEquals(0, s.maxPlayers);
        assertTrue(s.onlineAuth);
        assertTrue(s.selfSignedAuth);
        assertEquals("", s.nonce);
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseStatus_defektesJson() {
        NetherNetClient.parseStatus("kein json");
    }

    @Test
    public void plain_bereinigtNamen() {
        assertEquals("Mein Server", NetherNetClient.Status.plain("\"Mein Server\""));
        assertEquals("a b", NetherNetClient.Status.plain("a;b"));
        assertEquals("", NetherNetClient.Status.plain(null));
        assertEquals("x", NetherNetClient.Status.plain("  x "));
    }
}
