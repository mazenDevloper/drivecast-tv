package app.drivecast.tv;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * 🔧 DriveCast TV talks to the TV's own «network debugging» (adb on 127.0.0.1:5555), the way Shizuku-style apps do — no
 * computer, no cable. The first time, Android asks once on the TV «السماح بالتصحيح؟» (tick «دائماً»); after that the app can
 * install its own updates without the confirm screen. Only fixed commands written in this app are ever run (Update.java);
 * nothing from the web reaches this shell.
 * The adb protocol: 24-byte little-endian headers {command, arg0, arg1, length, checksum, magic} + data.
 */
final class SelfAdb {
    static final int PORT = 5555;
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = { 0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14 };

    /** why the last try failed: "" (worked), "off" (no network debugging), "auth" (waiting for «السماح» on the TV), or an error */
    static volatile String last = "";

    static final class Off extends Exception { Off(String m) { super(m); } }

    private static KeyPair keys(Context c) throws Exception {
        File pr = new File(c.getFilesDir(), "adbkey"), pu = new File(c.getFilesDir(), "adbkey.pub");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pr.exists() && pu.exists()) {
            try { return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pu.toPath()))), kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(pr.toPath())))); }
            catch (Exception ignored) { }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        try (OutputStream o = new FileOutputStream(pr)) { o.write(k.getPrivate().getEncoded()); }
        try (OutputStream o = new FileOutputStream(pu)) { o.write(k.getPublic().getEncoded()); }
        return k;
    }

    /** the public key the way adbd stores it (Android's mincrypt RSAPublicKey, little-endian), base64 + " name" */
    private static byte[] adbPub(RSAPublicKey k) {
        BigInteger n = k.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        int words = 64;
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(r32.subtract(n.mod(r32).modInverse(r32)).intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        BigInteger rr = BigInteger.ONE.shiftLeft(words * 32 * 2).mod(n);
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " DriveCastTV@tv";
        byte[] t = s.getBytes(), out = new byte[t.length + 1]; System.arraycopy(t, 0, out, 0, t.length); return out;
    }

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] d) throws Exception {
        if (d == null) d = new byte[0];
        int sum = 0; for (byte x : d) sum += x & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(d.length).putInt(sum).putInt(~cmd);
        o.write(h.array()); if (d.length > 0) o.write(d); o.flush();
    }

    private static final class Msg { int cmd, a0, a1; byte[] d; }

    private static Msg read(DataInputStream in) throws Exception {
        byte[] h = new byte[24]; in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg(); m.cmd = b.getInt(); m.a0 = b.getInt(); m.a1 = b.getInt(); int len = b.getInt();
        if (len < 0 || len > 1 << 20) throw new Exception("adb: bad length");
        m.d = new byte[len]; in.readFully(m.d); return m;
    }

    private static byte[] z(String s) { byte[] t = s.getBytes(), o = new byte[t.length + 1]; System.arraycopy(t, 0, o, 0, t.length); return o; }

    /** run one shell command on the TV and return what it printed. authWaitMs: how long to wait for «السماح» on the TV. */
    static synchronized String shell(Context c, String cmd, int authWaitMs, int runMs) throws Exception {
        KeyPair k = keys(c);
        try (Socket s = new Socket()) {
            try { s.connect(new InetSocketAddress("127.0.0.1", PORT), 2000); }
            catch (Exception e) { last = "off"; throw new Off("network debugging is off"); }
            s.setSoTimeout(8000);
            DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream o = s.getOutputStream();
            send(o, CNXN, 0x01000000, 4096, z("host::"));
            boolean signed = false;
            while (true) {
                Msg m;
                try { m = read(in); } catch (java.net.SocketTimeoutException e) { last = "auth"; throw new Exception("adb: not allowed on the TV"); }
                if (m.cmd == CNXN) break;
                if (m.cmd != AUTH || m.a0 != 1) continue;
                if (!signed) {
                    Cipher ci = Cipher.getInstance("RSA/ECB/PKCS1Padding"); ci.init(Cipher.ENCRYPT_MODE, (PrivateKey) k.getPrivate());
                    byte[] t = new byte[SHA1_PREFIX.length + m.d.length]; System.arraycopy(SHA1_PREFIX, 0, t, 0, SHA1_PREFIX.length); System.arraycopy(m.d, 0, t, SHA1_PREFIX.length, m.d.length);
                    send(o, AUTH, 2, 0, ci.doFinal(t)); signed = true;
                } else {
                    // this key is new to the TV: Android shows «السماح بالتصحيح؟» — wait for the person to allow it
                    last = "auth";
                    send(o, AUTH, 3, 0, adbPub((RSAPublicKey) k.getPublic()));
                    s.setSoTimeout(Math.max(authWaitMs, 1000));
                }
            }
            last = "";
            s.setSoTimeout(Math.max(runMs, 2000));
            send(o, OPEN, 1, 0, z("shell:" + cmd));
            ByteArrayOutputStream out = new ByteArrayOutputStream(); int remote = 0;
            try {
                while (true) {
                    Msg m = read(in);
                    if (m.cmd == OKAY) remote = m.a0;
                    else if (m.cmd == WRTE) { out.write(m.d); remote = m.a0; send(o, OKAY, 1, remote, null); }
                    else if (m.cmd == CLSE) { try { send(o, CLSE, 1, m.a0, null); } catch (Exception ignored) { } break; }
                }
            } catch (java.net.SocketTimeoutException e) { /* a command left running: what it printed so far */ }
            return out.toString("UTF-8");
        }
    }

    /** is the TV's network debugging there and allowed for this app? (a quick «echo») */
    static boolean ready(Context c, int authWaitMs) {
        try { boolean ok = shell(c, "echo ok", authWaitMs, 4000).trim().endsWith("ok"); if (ok) last = "ok"; return ok; } catch (Exception e) { if (last.isEmpty()) last = String.valueOf(e.getMessage()); return false; }
    }
}
