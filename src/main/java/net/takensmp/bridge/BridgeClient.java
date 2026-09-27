package net.takensmp.bridge;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

/**
 * Connection to the Discord bot. Pure Java (no Bukkit) so it can be tested alone.
 *
 * <p>TLS with certificate pinning: the bot's certificate fingerprint is remembered the
 * first time we pair ("trust on first use") and every later connection must match it.
 * Runs on its own threads; never blocks the server thread. While disconnected, outgoing
 * events wait in a bounded queue (oldest dropped first).
 */
public final class BridgeClient {

    /** Callbacks. They run on the bridge's IO thread — hop to the main thread yourself. */
    public interface Handler {
        void onFrame(Map<String, Object> frame);

        void onStatus(String status, String detail);

        void onPaired(String token, String fingerprint);
    }

    private static final int QUEUE_LIMIT = 500;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 70_000; // bot pings every 20s

    private final String host;
    private final int port;
    private final String serverName;
    private final String version;
    private final Handler handler;
    private final LinkedBlockingDeque<String> outbox = new LinkedBlockingDeque<>(QUEUE_LIMIT);
    private final Object writeLock = new Object();

    private volatile String token;
    private volatile String fingerprint;
    private volatile String pairCode;
    private volatile boolean running;
    private volatile boolean authed;
    private volatile SSLSocket socket;
    private volatile Writer out;
    private volatile String status = "starting";
    private Thread ioThread;

    public BridgeClient(String host, int port, String serverName, String version, Handler handler) {
        this.host = host;
        this.port = port;
        this.serverName = serverName;
        this.version = version;
        this.handler = handler;
    }

    public void setCredentials(String token, String fingerprint) {
        this.token = blankToNull(token);
        this.fingerprint = blankToNull(fingerprint);
    }

    public boolean isConnected() { return authed; }

    public boolean isPaired() { return token != null; }

    public String status() { return status; }

    public String fingerprint() { return fingerprint; }

    public int queued() { return outbox.size(); }

    public synchronized void start() {
        if (running) return;
        running = true;
        ioThread = new Thread(this::loop, "TakenBridge-IO");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    /** Stop, giving queued events up to {@code flushMillis} to be delivered. */
    public void stop(long flushMillis) {
        long end = System.currentTimeMillis() + flushMillis;
        while (authed && !outbox.isEmpty() && System.currentTimeMillis() < end) sleepQuietly(25);
        running = false;
        closeSocket();
        if (ioThread != null) ioThread.interrupt();
    }

    /** Pair with a one-time code from /bridge pair. Forgets the old token and pinned certificate. */
    public void pair(String code) {
        this.pairCode = code.trim().toUpperCase();
        this.token = null;
        this.fingerprint = null;
        closeSocket(); // the IO loop reconnects immediately using the code
        if (ioThread != null) ioThread.interrupt();
    }

    public void forget() {
        this.token = null;
        this.fingerprint = null;
        this.pairCode = null;
        closeSocket();
    }

    /** Queue a frame. Adds a timestamp so the bot can drop stale events after an outage. */
    public void send(Map<String, Object> frame) {
        Map<String, Object> f = new LinkedHashMap<>(frame);
        f.putIfAbsent("ts", System.currentTimeMillis());
        String line = Json.write(f);
        while (!outbox.offerLast(line)) outbox.pollFirst();
    }

    // ------------------------------------------------------------------ internals

    private void setStatus(String s, String detail) {
        boolean changed = !s.equals(status);
        status = s;
        if (changed || detail != null) handler.onStatus(s, detail);
    }

    private void loop() {
        long backoff = 2_000;
        while (running) {
            if (token == null && pairCode == null) {
                setStatus("unpaired", null);
                sleepQuietly(2_000);
                continue;
            }
            boolean wasAuthed = false;
            try {
                wasAuthed = connectAndRun();
            } catch (Exception e) {
                if (running) setStatus("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                authed = false;
                closeSocket();
            }
            if (!running) break;
            if (wasAuthed) backoff = 2_000;
            if (pairCode != null) { backoff = 2_000; continue; } // a new pair code arrived: go now
            setStatus("reconnecting", null);
            sleepQuietly(backoff);
            backoff = Math.min(backoff * 2, 60_000);
        }
        setStatus("stopped", null);
    }

    /** @return true if we got authenticated at some point during this connection */
    private boolean connectAndRun() throws Exception {
        final String code = pairCode;
        final String[] seenFingerprint = new String[1];
        final String pinned = fingerprint;

        X509TrustManager pinning = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Client certificates are not used");
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) throw new CertificateException("No certificate from bot");
                String fp = sha256(chain[0].getEncoded());
                seenFingerprint[0] = fp;
                if (pinned != null && !pinned.equalsIgnoreCase(fp)) {
                    throw new CertificateException("The bot's certificate changed (expected " + pinned + ", got " + fp
                            + "). If you moved or rebuilt the bot, re-pair with /bridge pair.");
                }
                if (pinned == null && code == null) throw new CertificateException("Not paired yet");
            }

            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{pinning}, new SecureRandom());
        setStatus("connecting", null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket();
        socket = s;
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setSoTimeout(READ_TIMEOUT_MS);
        s.setTcpNoDelay(true);
        s.startHandshake();

        out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));

        Map<String, Object> first = new LinkedHashMap<>();
        if (code != null) {
            first.put("t", "pair");
            first.put("code", code);
        } else {
            first.put("t", "hello");
            first.put("token", token);
        }
        first.put("name", serverName);
        first.put("version", version);
        writeLine(Json.write(first));

        Thread writer = null;
        boolean everAuthed = false;
        try {
            String line;
            while (running && (line = in.readLine()) != null) {
                Map<String, Object> f;
                try {
                    f = Json.parseObject(line);
                } catch (IllegalArgumentException bad) {
                    continue;
                }
                String t = String.valueOf(f.get("t"));
                switch (t) {
                    case "paired" -> {
                        token = String.valueOf(f.get("token"));
                        fingerprint = seenFingerprint[0];
                        pairCode = null;
                        handler.onPaired(token, fingerprint);
                    }
                    case "welcome" -> {
                        authed = true;
                        everAuthed = true;
                        setStatus("connected", null);
                        final SSLSocket mine = s;
                        writer = new Thread(() -> drain(mine), "TakenBridge-Writer");
                        writer.setDaemon(true);
                        writer.start();
                    }
                    case "error" -> {
                        String msg = String.valueOf(f.get("msg"));
                        if (code != null) pairCode = null; // don't retry a bad code forever
                        setStatus("rejected", msg);
                    }
                    case "ping" -> writeLine("{\"t\":\"pong\"}");
                    case "pong" -> { /* keepalive */ }
                    default -> {
                        if (authed) handler.onFrame(f);
                    }
                }
            }
        } finally {
            authed = false;
            if (writer != null) writer.interrupt();
        }
        return everAuthed;
    }

    private void drain(SSLSocket mine) {
        while (running && authed && socket == mine) {
            String line;
            try {
                line = outbox.pollFirst(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (line == null) continue;
            try {
                writeLine(line);
            } catch (IOException e) {
                outbox.offerFirst(line); // keep it for the next connection
                closeSocket();
                return;
            }
        }
    }

    private void writeLine(String line) throws IOException {
        synchronized (writeLock) {
            Writer w = out;
            if (w == null) throw new IOException("not connected");
            w.write(line);
            w.write('\n');
            w.flush();
        }
    }

    private void closeSocket() {
        SSLSocket s = socket;
        socket = null;
        out = null;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    static String sha256(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}
