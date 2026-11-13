package com.aly.sahl.connect;

import java.io.*;
import java.net.URI;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** Versioned, bounded frames carried exclusively over pinned TLS. No HTTP/browser endpoints. */
public final class Protocol {
    private Protocol() {}
    public static final int PORT = 8766, MAX_FRAME = 128 * 1024, MAX_FILE = 8 * 1024 * 1024;
    public static String random() {
        byte[] b = new byte[32]; new SecureRandom().nextBytes(b); return hex(b);
    }
    public static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte v : b) s.append(String.format(Locale.ROOT, "%02x", v & 255));
        return s.toString();
    }
    public static String hash(byte[] b) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(b)); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean same(String a, String b) {
        return a != null && b != null && MessageDigest.isEqual(a.getBytes(java.nio.charset.StandardCharsets.UTF_8), b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public static byte[] read(InputStream in) throws IOException {
        DataInputStream d = new DataInputStream(in); int n = d.readInt();
        if (n < 1 || n > MAX_FRAME) throw new IOException("Invalid frame size");
        byte[] b = new byte[n]; d.readFully(b); return b;
    }
    public static void write(OutputStream out, byte[] b) throws IOException {
        if (b.length < 1 || b.length > MAX_FRAME) throw new IOException("Invalid frame size");
        DataOutputStream d = new DataOutputStream(out); d.writeInt(b.length); d.write(b); d.flush();
    }
    public static boolean privateV4(String host) {
        if (host == null || !host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] p = host.split("\\."); int[] v = new int[4];
        for (int i=0;i<4;i++) { v[i]=Integer.parseInt(p[i]); if(v[i]>255 || !p[i].equals(Integer.toString(v[i]))) return false; }
        return v[0]==10 || v[0]==192 && v[1]==168 || v[0]==172 && v[1]>=16 && v[1]<=31;
    }
    public static final class PairCode {
        public final String host, pin, secret;
        public PairCode(String code) {
            if (code == null || code.length() > 256) throw new IllegalArgumentException("Invalid pairing code length");
            URI u = URI.create(code.trim());
            if (!"sahl-connect".equals(u.getScheme()) || u.getPort()!=PORT || !"/v1".equals(u.getPath()) ||
                u.getUserInfo()!=null || u.getFragment()!=null || !privateV4(u.getHost())) throw new IllegalArgumentException("Invalid TV address");
            String q=u.getRawQuery();
            if(q==null || !q.matches("pin=[a-f0-9]{64}&secret=[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid pairing code");
            host=u.getHost(); pin=q.substring(4,68); secret=q.substring(76);
        }
    }
    /** Synchronization makes consumption/lockout atomic even with concurrent clients. Uses monotonic time. */
    public static final class PairingGate {
        private String secret; private long until; private int attempts;
        public synchronized String open(long now) { secret=random(); until=now+180_000; attempts=0; return secret; }
        public synchronized void close() { secret=null; until=0; }
        public synchronized boolean accept(String value, long now) {
            if(secret==null || now>=until) { close(); return false; }
            if(!same(secret,value)) { if(++attempts>=5) close(); return false; }
            close(); return true;
        }
    }
}
