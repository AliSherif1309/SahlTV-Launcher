package com.aly.sahl.connect;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
public class ProtocolTest {
    @Test public void pairingIsOneUseExpiresAndLocks() {
        Protocol.PairingGate g=new Protocol.PairingGate(); String s=g.open(0);
        assertTrue(g.accept(s,1)); assertFalse(g.accept(s,2));
        s=g.open(0); assertFalse(g.accept(s,180000));
        s=g.open(0); for(int i=0;i<5;i++) assertFalse(g.accept("bad",1)); assertFalse(g.accept(s,2));
        s=g.open(0); g.close(); assertFalse(g.accept(s,1));
    }
    @Test public void concurrentPairingHasOneWinner() throws Exception {
        Protocol.PairingGate g=new Protocol.PairingGate(); String s=g.open(0);
        ExecutorService e=Executors.newFixedThreadPool(2);
        try { Future<Boolean> a=e.submit(()->g.accept(s,1)), b=e.submit(()->g.accept(s,1)); assertNotEquals(a.get(),b.get()); }
        finally { e.shutdownNow(); }
    }
    @Test public void framesRoundTripAndRejectTruncationOrOversize() throws Exception {
        ByteArrayOutputStream o=new ByteArrayOutputStream(); Protocol.write(o,new byte[]{1,2});
        assertArrayEquals(new byte[]{1,2}, Protocol.read(new ByteArrayInputStream(o.toByteArray())));
        for(int n:new int[]{-1,0,Protocol.MAX_FRAME+1,8}) {
            o.reset(); new DataOutputStream(o).writeInt(n);
            try { Protocol.read(new ByteArrayInputStream(o.toByteArray())); fail(); } catch(IOException expected) {}
        }
    }
    @Test public void pairingCodeRejectsPublicDnsAndMalformedPins() {
        String pin=Protocol.random(), secret=Protocol.random();
        String suffix=":"+Protocol.PORT+"/v1?pin="+pin+"&secret="+secret;
        Protocol.PairCode c=new Protocol.PairCode("sahl-connect://192.168.1.2"+suffix);
        assertEquals(secret,c.secret); assertEquals(pin,c.pin);
        for(String h:new String[]{"example.org","8.8.8.8","127.0.0.1","192.168.001.2","192.168.300.1"}) {
            try { new Protocol.PairCode("sahl-connect://"+h+suffix); fail(h); } catch(IllegalArgumentException expected) {}
        }
        assertFalse(Protocol.same("a","b")); assertTrue(Protocol.same(pin,pin)); assertEquals(64,secret.length());
    }
}
