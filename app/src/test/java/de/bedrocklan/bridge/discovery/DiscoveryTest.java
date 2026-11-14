package de.bedrocklan.bridge.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DiscoveryTest {

    private static final long SENDER = 0x1122334455667788L;
    private static final long RECIPIENT = 0x8877665544332211L;

    @Test
    public void messageRoundtrip() {
        byte[] wire = Discovery.marshalMessage(SENDER, RECIPIENT, "CONNECTREQUEST 42 offer");
        Discovery.Packet p = Discovery.unmarshal(wire, wire.length);
        assertNotNull(p);
        assertEquals(Discovery.ID_MESSAGE, p.id);
        assertEquals(SENDER, p.senderId);
        assertEquals(RECIPIENT, p.recipientId);
        assertEquals("CONNECTREQUEST 42 offer", p.messageData);
    }

    @Test
    public void message_langerInhalt_wirdVollstaendigUebertragen() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 4000; i++) s.append('x');
        byte[] wire = Discovery.marshalMessage(SENDER, RECIPIENT, s.toString());
        Discovery.Packet p = Discovery.unmarshal(wire, wire.length);
        assertNotNull(p);
        assertEquals(s.length(), p.messageData.length());
        assertEquals(s.toString(), p.messageData);
    }

    @Test
    public void requestRoundtrip() {
        byte[] wire = Discovery.marshalRequest(SENDER);
        Discovery.Packet p = Discovery.unmarshal(wire, wire.length);
        assertNotNull(p);
        assertEquals(Discovery.ID_REQUEST, p.id);
        assertEquals(SENDER, p.senderId);
        assertNull(p.messageData);
    }

    @Test
    public void responseRoundtrip() {
        byte[] appData = {0x07, 0x01, 0x02, (byte) 0xff};
        byte[] wire = Discovery.marshalResponse(SENDER, appData);
        Discovery.Packet p = Discovery.unmarshal(wire, wire.length);
        assertNotNull(p);
        assertEquals(Discovery.ID_RESPONSE, p.id);
        assertEquals(SENDER, p.senderId);
        assertEquals(4, p.responseData.length);
        assertEquals(0xff, p.responseData[3] & 0xff);
    }

    @Test
    public void kaputtePruefsumme_wirdVerworfen() {
        byte[] wire = Discovery.marshalMessage(SENDER, RECIPIENT, "hallo");
        wire[0] ^= 0x01;
        assertNull(Discovery.unmarshal(wire, wire.length));
    }

    @Test
    public void abgeschnittenePakete_werdenVerworfen() {
        byte[] wire = Discovery.marshalMessage(SENDER, RECIPIENT, "hallo");
        assertNull(Discovery.unmarshal(wire, 40));
        assertNull(Discovery.unmarshal(new byte[8], 8));
    }

    @Test
    public void empfaengerFeld_wirdErhalten() {
        byte[] wire = Discovery.marshalMessage(SENDER, RECIPIENT, "x");
        Discovery.Packet p = Discovery.unmarshal(wire, wire.length);
        assertNotNull(p);
        assertEquals(RECIPIENT, p.recipientId);
    }
}
