package com.gathin.tvhome;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * A speed test against Cloudflare's public speed-test endpoints: ping, then download, then upload,
 * one stream each. The Home pill only reads TrafficStats, which says what is happening now; this
 * measures what the line can carry, and runs only when someone selects the pill.
 *
 * <p>{@link #run} blocks: call it on a background thread. {@link #cancel} stops it at the next read,
 * so leaving Home does not keep the TV busy for seconds.
 */
final class SpeedTest {
    static final int PHASE_PING = 0;
    static final int PHASE_DOWNLOAD = 1;
    static final int PHASE_UPLOAD = 2;

    interface Listener {
        /** A phase started: PHASE_PING, PHASE_DOWNLOAD or PHASE_UPLOAD. */
        void onPhase(int phase);

        /** A live figure for the running phase: milliseconds for ping, Mbps otherwise. */
        void onProgress(int phase, double value);

        /** The final figure for a phase: milliseconds for ping, Mbps otherwise. */
        void onResult(int phase, double value);

        /** The run is over; false when a phase failed or the test was cancelled. */
        void onFinished(boolean ok);
    }

    private static final String DOWN = "https://speed.cloudflare.com/__down?bytes=";
    private static final String UP = "https://speed.cloudflare.com/__up";
    /** Enough for a 100 Mbps line in a couple of seconds, small enough for a metered one. */
    private static final long DOWNLOAD_BYTES = 25_000_000L;
    private static final int UPLOAD_BYTES = 10_000_000;
    /** Each phase stops after this long, so a slow line still gets a figure. */
    private static final long PHASE_MS = 8_000L;
    private static final int PING_TRIES = 3;
    private static final int BUFFER = 64 * 1024;

    private volatile boolean cancelled;
    private volatile HttpURLConnection open;

    void cancel() {
        cancelled = true;
        HttpURLConnection c = open;
        if (c != null) c.disconnect();
    }

    void run(Listener l) {
        boolean ok = false;
        try {
            l.onPhase(PHASE_PING);
            double ping = ping();
            if (ping < 0) return;
            l.onResult(PHASE_PING, ping);

            l.onPhase(PHASE_DOWNLOAD);
            double down = download(l);
            if (down < 0) return;
            l.onResult(PHASE_DOWNLOAD, down);

            l.onPhase(PHASE_UPLOAD);
            double up = upload(l);
            if (up < 0) return;
            l.onResult(PHASE_UPLOAD, up);
            ok = !cancelled;
        } catch (Exception ignored) {
            ok = false;
        } finally {
            open = null;
            l.onFinished(ok);
        }
    }

    /**
     * The best of a few round trips, not the average: the first request pays for the TLS handshake
     * and would otherwise drag a 20 ms line up to 150 ms.
     */
    private double ping() throws Exception {
        double best = -1;
        for (int i = 0; i < PING_TRIES; i++) {
            if (cancelled) return -1;
            HttpURLConnection c = connect(DOWN + "0", 6000);
            open = c;
            try {
                long start = System.nanoTime();
                int code = c.getResponseCode();
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                if (code < 200 || code >= 400) return -1;
                if (best < 0 || ms < best) best = ms;
            } finally {
                c.disconnect();
                open = null;
            }
        }
        return best;
    }

    private double download(Listener l) throws Exception {
        HttpURLConnection c = connect(DOWN + DOWNLOAD_BYTES, 10_000);
        open = c;
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) return -1;
            InputStream in = c.getInputStream();
            byte[] buf = new byte[BUFFER];
            long start = System.nanoTime(), last = start, bytes = 0;
            while (!cancelled) {
                int n = in.read(buf);
                if (n < 0) break;
                bytes += n;
                long now = System.nanoTime();
                if (now - last >= 300_000_000L) {
                    l.onProgress(PHASE_DOWNLOAD, mbps(bytes, now - start));
                    last = now;
                }
                if (now - start >= PHASE_MS * 1_000_000L) break;
            }
            long elapsed = System.nanoTime() - start;
            if (cancelled || bytes <= 0) return -1;
            return mbps(bytes, elapsed);
        } finally {
            c.disconnect();
            open = null;
        }
    }

    private double upload(Listener l) throws Exception {
        HttpURLConnection c = connect(UP, 10_000);
        open = c;
        try {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/octet-stream");
            c.setFixedLengthStreamingMode(UPLOAD_BYTES);
            OutputStream out = c.getOutputStream();
            byte[] buf = new byte[BUFFER];
            long start = System.nanoTime(), last = start;
            int written = 0;
            while (written < UPLOAD_BYTES && !cancelled) {
                int n = Math.min(buf.length, UPLOAD_BYTES - written);
                out.write(buf, 0, n);
                written += n;
                long now = System.nanoTime();
                if (now - last >= 300_000_000L) {
                    l.onProgress(PHASE_UPLOAD, mbps(written, now - start));
                    last = now;
                }
                if (now - start >= PHASE_MS * 1_000_000L) break;
            }
            out.flush();
            long elapsed = System.nanoTime() - start;
            if (cancelled || written <= 0) return -1;
            return mbps(written, elapsed);
        } finally {
            c.disconnect();
            open = null;
        }
    }

    private static double mbps(long bytes, long nanos) {
        if (nanos <= 0) return 0;
        return bytes * 8.0 / 1_000_000.0 / (nanos / 1_000_000_000.0);
    }

    private static HttpURLConnection connect(String url, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "CleanYourTV-Sola");
        return c;
    }
}
