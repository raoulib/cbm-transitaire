import com.sawki.weierremote.AdbClient;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Test local : un faux "adbd" vérifie l'authentification RSA et l'exécution de commandes shell. */
public class AdbSelfTest {
    static final int CNXN=0x4e584e43, AUTH=0x48545541, OPEN=0x4e45504f, OKAY=0x59414b4f, CLSE=0x45534c43, WRTE=0x45545257;
    static final byte[] PREFIX = {0x30,0x21,0x30,0x09,0x06,0x05,0x2b,0x0e,0x03,0x02,0x1a,0x05,0x00,0x04,0x14};
    static volatile String failure = null;
    static volatile String lastShell = null;

    public static void main(String[] a) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread srv = new Thread(() -> { try { serve(ss); } catch (Throwable t) { failure = "serveur: " + t; t.printStackTrace(); } });
        srv.start();

        AdbClient c = new AdbClient(kp);
        boolean[] asked = {false};
        c.connect("127.0.0.1", ss.getLocalPort(), () -> asked[0] = true);
        check(c.isConnected(), "client connecté");
        check(asked[0], "callback onAuthRequired appelé (clé inconnue)");
        String out = c.shell("input keyevent 24");
        check("ok:input keyevent 24".equals(out), "sortie shell = " + out);
        check("input keyevent 24".equals(lastShell), "commande reçue = " + lastShell);
        String out2 = c.shell("echo second");
        check("ok:echo second".equals(out2), "2e commande = " + out2);
        c.close();
        srv.join(3000);
        if (failure != null) { System.out.println("ECHEC : " + failure); System.exit(1); }
        System.out.println("TOUS LES TESTS OK");
    }

    static void check(boolean ok, String msg) { System.out.println((ok ? "  ok  " : "  KO  ") + msg); if (!ok) { System.out.println("ECHEC"); System.exit(1);} }

    static void serve(ServerSocket ss) throws Exception {
        Socket s = ss.accept();
        DataInputStream in = new DataInputStream(s.getInputStream()); OutputStream out = s.getOutputStream();
        int[] h = new int[3]; byte[] d = rd(in, h);
        check(h[0] == CNXN, "serveur: reçoit CNXN");
        byte[] token = new byte[20]; new SecureRandom().nextBytes(token);
        wr(out, AUTH, 1, 0, token);
        byte[] sig = rd(in, h);
        check(h[0] == AUTH && h[1] == 2, "serveur: reçoit signature (type 2)");
        // La clé n'est pas connue : on redemande un token
        wr(out, AUTH, 1, 0, token);
        d = rd(in, h);
        check(h[0] == AUTH && h[1] == 3, "serveur: reçoit clé publique (type 3)");
        check(d[d.length - 1] == 0, "clé terminée par NUL");
        String txt = new String(d, 0, d.length - 1, StandardCharsets.UTF_8);
        String b64 = txt.substring(0, txt.indexOf(' '));
        byte[] key = Base64.getDecoder().decode(b64);
        check(key.length == 524, "structure clé = 524 octets (" + key.length + ")");
        ByteBuffer kb = ByteBuffer.wrap(key).order(ByteOrder.LITTLE_ENDIAN);
        check(kb.getInt() == 64, "len = 64 mots");
        int n0inv = kb.getInt();
        byte[] nb = new byte[256]; kb.get(nb); byte[] rrb = new byte[256]; kb.get(rrb);
        int e = kb.getInt();
        BigInteger n = le(nb), rr = le(rrb);
        check(e == 65537, "exposant 65537");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        check(n.multiply(BigInteger.valueOf(n0inv & 0xffffffffL)).add(BigInteger.ONE).mod(r32).signum() == 0, "n0inv : n*n0inv = -1 mod 2^32");
        check(rr.equals(BigInteger.ONE.shiftLeft(4096).mod(n)), "rr = (2^2048)^2 mod n");
        // La clé décodée doit valider la signature reçue à la 1re étape (format PKCS#1 v1.5 + DigestInfo SHA-1)
        PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, BigInteger.valueOf(e)));
        Signature v = Signature.getInstance("NONEwithRSA"); v.initVerify(pub); v.update(PREFIX); v.update(token);
        check(v.verify(sig), "signature du token valide avec la clé publique décodée");
        // Variante de référence : SHA1withRSA ne s'applique pas ici (le token est déjà le hash) -> on vérifie aussi la longueur
        check(sig.length == 256, "signature = 256 octets");
        wr(out, CNXN, 0x01000000, 4096, "device::\0".getBytes());
        for (int i = 0; i < 2; i++) {
            d = rd(in, h);
            check(h[0] == OPEN, "serveur: reçoit OPEN");
            String cmd = new String(d, 0, d.length - 1, StandardCharsets.UTF_8);
            check(cmd.startsWith("shell:"), "service shell");
            lastShell = cmd.substring(6);
            int local = h[1], remote = 100 + i;
            wr(out, OKAY, remote, local, null);
            wr(out, WRTE, remote, local, ("ok:" + lastShell).getBytes());
            rd(in, h); check(h[0] == OKAY, "serveur: client acquitte WRTE");
            wr(out, CLSE, remote, local, null);
            rd(in, h); check(h[0] == CLSE, "serveur: client répond CLSE");
        }
    }

    static byte[] rd(DataInputStream in, int[] h) throws IOException {
        byte[] hb = new byte[24]; in.readFully(hb);
        ByteBuffer b = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        h[0] = b.getInt(); h[1] = b.getInt(); h[2] = b.getInt(); int len = b.getInt(); b.getInt(); int magic = b.getInt();
        if (magic != (h[0] ^ 0xffffffff)) throw new IOException("magic");
        byte[] d = new byte[len]; in.readFully(d); return d;
    }
    static void wr(OutputStream o, int cmd, int a0, int a1, byte[] d) throws IOException {
        int len = d == null ? 0 : d.length; ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(0).putInt(cmd ^ 0xffffffff); if (d != null) b.put(d); o.write(b.array()); o.flush();
    }
    static BigInteger le(byte[] x) { byte[] r = new byte[x.length + 1]; for (int i = 0; i < x.length; i++) r[x.length - i] = x[i]; return new BigInteger(r); }
}
