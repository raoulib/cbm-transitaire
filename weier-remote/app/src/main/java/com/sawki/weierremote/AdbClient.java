package com.sawki.weierremote;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

/**
 * Client ADB minimal (protocole TCP), sans dépendance Android :
 * connexion + authentification RSA + exécution de commandes "shell:".
 */
public class AdbClient implements Closeable {

    public interface Listener {
        /** La TV ne connaît pas encore cette clé : l'utilisateur doit accepter sur l'écran de la TV. */
        void onAuthRequired();
    }

    static final int A_CNXN = 0x4e584e43;
    static final int A_AUTH = 0x48545541;
    static final int A_OPEN = 0x4e45504f;
    static final int A_OKAY = 0x59414b4f;
    static final int A_CLSE = 0x45534c43;
    static final int A_WRTE = 0x45545257;

    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    /** Préfixe DER DigestInfo pour SHA-1 : le token ADB (20 octets) est traité comme un hash déjà calculé. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final KeyPair keyPair;
    private Socket socket;
    private DataInputStream in;
    private OutputStream out;
    private int nextLocalId = 1;
    private volatile boolean connected = false;

    /** Si non nul, chaque message ADB envoyé/reçu y est consigné (outil de diagnostic). */
    public volatile StringBuilder trace;

    private void log(String dir, int cmd, int a0, int a1, byte[] d) {
        StringBuilder t = trace;
        if (t == null) return;
        String name = cmd == A_CNXN ? "CNXN" : cmd == A_AUTH ? "AUTH" : cmd == A_OPEN ? "OPEN"
                : cmd == A_OKAY ? "OKAY" : cmd == A_CLSE ? "CLSE" : cmd == A_WRTE ? "WRTE" : "?";
        String txt = "";
        if (d != null && d.length > 0) {
            int n = Math.min(d.length, 60);
            txt = " '" + new String(d, 0, n, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7E]", ".") + "'";
        }
        t.append(dir).append(' ').append(name).append(' ').append(a0).append(' ').append(a1)
                .append(" len=").append(d == null ? 0 : d.length).append(txt).append('\n');
    }

    public AdbClient(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public synchronized void connect(String host, int port, Listener listener) throws IOException {
        close();
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), 4000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(6000);
            socket = s;
            in = new DataInputStream(s.getInputStream());
            out = s.getOutputStream();

            send(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            boolean keySent = false;
            while (true) {
                Message m = read();
                if (m.cmd == A_CNXN) {
                    s.setSoTimeout(6000);
                    connected = true;
                    return;
                }
                if (m.cmd != A_AUTH || m.arg0 != AUTH_TOKEN) {
                    throw new IOException("Réponse inattendue de la TV");
                }
                if (!signed) {
                    send(A_AUTH, AUTH_SIGNATURE, 0, sign(m.data));
                    signed = true;
                } else if (!keySent) {
                    if (listener != null) listener.onAuthRequired();
                    s.setSoTimeout(60000); // temps pour accepter sur la TV
                    send(A_AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyPayload());
                    keySent = true;
                } else {
                    throw new IOException("Connexion refusée sur la TV");
                }
            }
        } catch (IOException | RuntimeException e) {
            close();
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Comme shell(), avec un délai de lecture plus long (commandes lentes). */
    public synchronized String shell(String command, int timeoutMs) throws IOException {
        if (!isConnected()) throw new IOException("Non connecté");
        int old = socket.getSoTimeout();
        socket.setSoTimeout(timeoutMs);
        try {
            return shell(command);
        } finally {
            try {
                if (socket != null && !socket.isClosed()) socket.setSoTimeout(old);
            } catch (IOException ignored) { }
        }
    }

    /** Exécute une commande shell et renvoie sa sortie. */
    public synchronized String shell(String command) throws IOException {
        if (!isConnected()) throw new IOException("Non connecté");
        try {
            int local = nextLocalId++;
            send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            while (true) {
                Message m = read();
                if (m.arg1 != local) continue; // message d'un autre flux : on ignore
                if (m.cmd == A_WRTE) {
                    result.write(m.data);
                    send(A_OKAY, local, m.arg0, null);
                } else if (m.cmd == A_CLSE) {
                    send(A_CLSE, local, m.arg0, null);
                    return result.toString("UTF-8");
                }
                // A_OKAY : flux ouvert, on continue à lire
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    @Override
    public synchronized void close() {
        connected = false;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        socket = null;
    }

    // ---------------------------------------------------------------- protocole

    private static final class Message {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        log("->", cmd, arg0, arg1, data);
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(checksum(data)).putInt(cmd ^ 0xffffffff);
        if (data != null) b.put(data);
        out.write(b.array());
        out.flush();
    }

    private Message read() throws IOException {
        byte[] h = new byte[24];
        in.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Message m = new Message();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum des données (ignoré, comme les ADB récents)
        int magic = b.getInt();
        if (magic != (m.cmd ^ 0xffffffff) || len < 0 || len > 1024 * 1024) {
            throw new IOException("Trame ADB invalide");
        }
        m.data = new byte[len];
        if (len > 0) in.readFully(m.data);
        log("<-", m.cmd, m.arg0, m.arg1, m.data);
        return m;
    }

    private static int checksum(byte[] data) {
        int sum = 0;
        if (data != null) for (byte x : data) sum += (x & 0xff);
        return sum;
    }

    // ------------------------------------------------------------------- crypto

    byte[] sign(byte[] token) throws IOException {
        try {
            PrivateKey priv = keyPair.getPrivate();
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign(priv);
            s.update(SHA1_DIGEST_INFO);
            s.update(token);
            return s.sign();
        } catch (Exception e) {
            throw new IOException("Signature impossible : " + e.getMessage(), e);
        }
    }

    /** Clé publique au format "mincrypt" d'ADB, encodée en base64, suivie du nom et d'un NUL. */
    byte[] publicKeyPayload() {
        RSAPublicKey pk = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pk.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        int n0inv = r32.subtract(n.mod(r32).modInverse(r32)).intValue();
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);

        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(256 / 4);
        b.putInt(n0inv);
        b.put(toLittleEndian(n, 256));
        b.put(toLittleEndian(rr, 256));
        b.putInt(pk.getPublicExponent().intValue());
        String text = base64(b.array()) + " weier-remote@android\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] toLittleEndian(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len; i++) {
            int idx = be.length - 1 - i;
            le[i] = idx >= 0 ? be[idx] : 0;
        }
        return le;
    }

    static String base64(byte[] d) {
        final String t = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff;
            int b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0;
            int b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            sb.append(t.charAt(b0 >> 2));
            sb.append(t.charAt(((b0 & 3) << 4) | (b1 >> 4)));
            sb.append(i + 1 < d.length ? t.charAt(((b1 & 15) << 2) | (b2 >> 6)) : '=');
            sb.append(i + 2 < d.length ? t.charAt(b2 & 63) : '=');
        }
        return sb.toString();
    }
}
