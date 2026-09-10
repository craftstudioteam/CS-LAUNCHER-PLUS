package net.kdt.pojavlaunch.utils;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.util.concurrent.Callable;

import net.kdt.pojavlaunch.*;
import org.apache.commons.io.*;

@SuppressWarnings("IOStreamConstructor")
public class DownloadUtils {
    public static final String USER_AGENT = Tools.APP_NAME;
    private static final int TIME_OUT = 15000;        // connect: fail fast enough to retry
    private static final int READ_TIME_OUT = 30000;   // read: patient — parallel streams share the radio

    // ── TURBO SEGMENTED ENGINE ─────────────────────────────────────────────
    // A single TCP stream can never saturate a modern 4G/5G/Wi-Fi radio: the
    // latency of each round trip caps the throughput. Big files are therefore
    // split into parallel HTTP Range requests, each writing into its own slice
    // of the output file. Small files, servers without Range support and any
    // cancelled transfer fall straight back to the classic single stream.
    private static final long MIN_SEGMENTED_BYTES = 4L * 1024L * 1024L; // 4 MB
    private static final int SEGMENT_ATTEMPTS = 4; // retries are cheap — we resume, never restart
    private static final int TURBO_BUFFER = 131072; // 128 KB

    public static void download(String url, OutputStream os) throws IOException {
        download(new URL(url), os);
    }

    public static void download(URL url, OutputStream os) throws IOException {
        InputStream is = null;
        try {
            // System.out.println("Connecting: " + url.toString());
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(TIME_OUT);
            conn.setReadTimeout(READ_TIME_OUT);
            conn.setDoInput(true);
            conn.connect();
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + conn.getResponseCode()
                        + ": " + conn.getResponseMessage());
            }
            is = new BufferedInputStream(conn.getInputStream(), 131072);
            IOUtils.copyLarge(is, os, new byte[131072]); // TURBO: 128K blocks
        } catch (IOException e) {
            throw new IOException("Unable to download from " + url, e);
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static String downloadString(String url) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        download(url, bos);
        bos.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    public static void downloadFile(String url, File out) throws IOException {
        FileUtils.ensureParentDirectory(out);
        try (FileOutputStream fileOutputStream = new FileOutputStream(out)) {
            download(url, fileOutputStream);
        } catch (IOException e) {
            if (out.length() < 1) { // Only delete it if file is 0 bytes cause this file might already be downloaded and something else went wrong.
                Log.i("DownloadUtils", "Cleaning up failed download: " + out.getAbsolutePath());
                out.delete();
                throw e;
            }
        }
    }

    public static void downloadFileMonitored(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        // RESILIENT TURBO: transient SocketTimeout/SocketException on congested
        // mobile radios must not kill a whole install. Retry with a short
        // backoff; user cancellation is never retried.
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(700L * attempt); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw lastFailure; }
            }
            try {
                downloadFileMonitoredOnce(urlInput, outputFile, buffer, monitor);
                return;
            } catch (DownloadControl.DownloadCancelledException cancelled) {
                throw cancelled; // user pressed stop — respect it instantly
            } catch (IOException e) {
                lastFailure = e;
                Log.i("DownloadUtils", "Attempt " + (attempt + 1) + " failed for " + urlInput + " — " + e);
            }
        }
        throw lastFailure;
    }

    /** Thrown when a server ignores our Range request — caller falls back to a plain stream. */
    private static final class RangeUnsupportedException extends IOException {
        RangeUnsupportedException(String m) { super(m); }
    }


    /** Connection setup: raw stream, mobile-friendly timeouts, cookies, no blind reconnects. */
    private static HttpURLConnection openGet(String urlInput) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlInput).openConnection();
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept-Encoding", "identity"); // raw stream — no proxy recompression stalls
        conn.setConnectTimeout(TIME_OUT);
        conn.setReadTimeout(READ_TIME_OUT);
        conn.setDoInput(true);
        // Some hosts (optifine.net among them) only serve the real file to a
        // request carrying back the session id they handed out one page earlier.
        net.kdt.pojavlaunch.utils.HttpCookies.apply(urlInput, conn);
        return conn;
    }

    /** A connection plus the URL it actually ended up on after redirects. */
    private static final class Response {
        final HttpURLConnection conn;
        final String url;
        Response(HttpURLConnection conn, String url) {
            this.conn = conn;
            this.url = url;
        }
    }

    private static final int MAX_REDIRECTS = 5;

    /**
     * openGet + follow redirects ourselves.
     *
     * HttpURLConnection follows redirects only while the protocol stays the
     * same, and it drops our headers when it does. Download hosts do both
     * (https page → http file), so a "redirect" that we let the library half-handle
     * looks exactly like a broken or slow download. Following the Location
     * header ourselves also lets the session cookies travel with the hop.
     */
    private static Response openFollowingRedirects(String urlInput, @Nullable String referer)
            throws IOException {
        String url = urlInput;
        HttpURLConnection conn = null;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            conn = openGet(url);
            if (referer != null) conn.setRequestProperty("Referer", referer);
            int code = conn.getResponseCode();
            net.kdt.pojavlaunch.utils.HttpCookies.store(url, conn);
            if (code < 300 || code > 399 || hop == MAX_REDIRECTS) {
                return new Response(conn, url);
            }
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location == null || location.isEmpty()) {
                throw new IOException("HTTP " + code + " without a Location for " + url);
            }
            conn = null;
            referer = url; // keep the chain honest for hosts that check it
            url = new URL(new URL(url), location).toString();
        }
        throw new IOException("Too many redirects for " + urlInput);
    }

    /**
     * Fetch a page as text (same UA/timeouts/cookies as every other request).
     * Public because scraping with a raw {@code URL.openStream()} sends
     * {@code User-Agent: Java/1.8.0_x}, which Cloudflare answers with
     * "403 error code: 1010" — the classic "download never starts" bug.
     */
    public static String fetchString(String urlInput) throws IOException {
        return fetchString(urlInput, null);
    }

    public static String fetchString(String urlInput, @Nullable String referer) throws IOException {
        HttpURLConnection conn = null;
        try {
            Response res = openFollowingRedirects(urlInput, referer);
            conn = res.conn;
            int code = conn.getResponseCode();
            if (code < 200 || code > 299) {
                throw new IOException("Server returned HTTP " + code + " for " + urlInput);
            }
            try (InputStream in = new BufferedInputStream(conn.getInputStream(), TURBO_BUFFER);
                 java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                byte[] buf = new byte[TURBO_BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * host → whether it served 206 on our last big transfer. A thousand asset
     * files must not each pay a HEAD round trip just to find out the answer for
     * the ninth time; and a small file never needs to ask at all.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Boolean> sRangeHosts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String hostOf(String url) {
        // Same host extraction the cookie jar uses, so the two keys never differ
        // (one by port, one without, would silently break both).
        return net.kdt.pojavlaunch.utils.HttpCookies.hostOf(url);
    }

    /** Parallel streams scaled to the payload — never so many that mobile radios choke. */
    private static int resolveSegments(long total) {
        if (total < 8L * 1024 * 1024) return 3;
        if (total < 32L * 1024 * 1024) return 5;
        if (total < 96L * 1024 * 1024) return 7;
        return 9;
    }

    private static void downloadFileMonitoredOnce(String urlInput, File outputFile, @Nullable byte[] buffer,
                                             Tools.DownloaderFeedback monitor) throws IOException {
        FileUtils.ensureParentDirectory(outputFile);
        String controlKey = resolveControlKey(monitor);
        // One connection, one request. The old code asked the server for the
        // length with a HEAD first, which is a whole extra round trip per file —
        // thousands of assets, thousands of wasted trips, and it is precisely how
        // a "slow download" is manufactured. The GET response already carries
        // content-length and accept-ranges, so we read the answer where it lives.
        HttpURLConnection conn = null;
        InputStream readStr;
        String finalUrl = urlInput;
        long length;
        try {
            Response res = openFollowingRedirects(urlInput, null);
            conn = res.conn;
            finalUrl = res.url;
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                conn.disconnect();
                conn = null;
                // FileNotFoundException, not IOException: the mirror layer keys off
                // this type to retry a file against the official source.
                throw new FileNotFoundException("404 for " + finalUrl);
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                conn.disconnect();
                conn = null;
                throw new IOException("Server returned HTTP " + code + " for " + finalUrl);
            }

            // Big enough to be worth splitting? Hand it to the segmented engine and
            // remember that this host honours Range, so the next big file gets the
            // engine from the first byte instead of after a wasted stream.
            length = contentLength(conn);
            boolean supportsRange = !"none".equalsIgnoreCase(conn.getHeaderField("accept-ranges"));
            if (supportsRange) sRangeHosts.put(hostOf(finalUrl), Boolean.TRUE);
            if (length >= MIN_SEGMENTED_BYTES && supportsRange) {
                conn.disconnect();
                conn = null;
                downloadFileSegmented(finalUrl, outputFile, length, controlKey, monitor);
                return;
            }
            readStr = new BufferedInputStream(conn.getInputStream(), TURBO_BUFFER);
        } catch (DownloadControl.DownloadCancelledException cancelled) {
            throw cancelled;      // the user pressed STOP: never rewrap, never retry
        } catch (IOException e) {
            if (conn != null) conn.disconnect();
            throw new IOException("Unable to download from " + urlInput, e);
        }

        // readStr is closed here as well as disconnected below: a stream left open
        // on an error path is a socket leak, and mobile networks feel that a lot.
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             InputStream closeMe = readStr) {
            int current;
            int overall = 0;
            if (buffer == null) buffer = new byte[TURBO_BUFFER];

            while ((current = readStr.read(buffer)) != -1) {
                // Pause/stop control from the download deck lives here
                DownloadControl.checkpoint(controlKey);
                overall += current;
                fos.write(buffer, 0, current);
                if (monitor != null) monitor.updateProgress(overall, (int) Math.min(Integer.MAX_VALUE, length));
            }
            conn.disconnect();
        } catch (DownloadControl.DownloadCancelledException e) {
            conn.disconnect();
            outputFile.delete(); // never keep a half-written file after user stop
            throw e;
        } catch (IOException e) {
            throw new IOException("Unable to download from " + urlInput, e);
        }
    }

    /** Content-Length that works on every API level without tripping the lint. */
    private static long contentLength(HttpURLConnection conn) {
        long len = -1;
        try {
            len = Long.parseLong(conn.getHeaderField("content-length"));
        } catch (Throwable ignored) {}
        if (len < 0) {
            try { len = conn.getContentLength(); } catch (Throwable ignored) {}
        }
        return len;
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }

    private static void deleteQuietly(File f) {
        if (f == null) return;
        try { f.delete(); } catch (Throwable ignored) {}
    }

    private static String resolveControlKey(Tools.DownloaderFeedback monitor) {
        if (monitor instanceof net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper) {
            return ((net.kdt.pojavlaunch.progresskeeper.DownloaderProgressWrapper) monitor).getRecord();
        } else if (monitor instanceof DownloadControl.KeyedFeedback) {
            return ((DownloadControl.KeyedFeedback) monitor).getControlKey();
        }
        return null;
    }

    /* ───────────────────────── resume state (.csdl sidecar) ─────────────────────────
     * Every segment remembers how far it got. A dropped socket — or the whole
     * activity being restarted — then costs only the missing slice instead of
     * the entire file. This is the single biggest real-world speed win on
     * flaky mobile networks.
     * ------------------------------------------------------------------------------- */

    private static synchronized void writeResumeState(File sidecar, String url,
                                                      java.util.concurrent.atomic.AtomicLongArray offsets) {
        java.io.FileOutputStream fos = null;
        try {
            fos = new java.io.FileOutputStream(sidecar);
            java.io.DataOutputStream dos = new java.io.DataOutputStream(fos);
            dos.writeUTF(url);
            dos.writeInt(offsets.length());
            for (int i = 0; i < offsets.length(); i++) dos.writeLong(offsets.get(i));
            dos.flush();
            try { fos.getFD().sync(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        } finally {
            closeQuietly(fos);
        }
    }

    private static boolean readResumeState(File sidecar, String url, int segments,
                                           java.util.concurrent.atomic.AtomicLongArray offsets) {
        if (offsets.length() != segments || sidecar == null || !sidecar.isFile()) return false;
        java.io.DataInputStream dis = null;
        try {
            dis = new java.io.DataInputStream(new java.io.FileInputStream(sidecar));
            if (!url.equals(dis.readUTF())) return false;
            if (dis.readInt() != segments) return false;
            for (int i = 0; i < segments; i++) offsets.set(i, dis.readLong());
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            closeQuietly(dis);
        }
    }

    /**
     * Splits one big file into several parallel HTTP Range requests. Every
     * segment writes into its own slice of the output file, remembers its
     * offset and retries on its own, so a single stalled socket can never drag
     * the whole download down. Progress is aggregated from the live offsets.
     */
    private static void downloadFileSegmented(String urlInput, File outputFile, long total,
                                             String controlKey, Tools.DownloaderFeedback monitor)
            throws IOException {
        final int segments = resolveSegments(total);
        final long chunk = total / segments;
        final File sidecar = new File(outputFile.getAbsolutePath() + ".csdl");
        final java.util.concurrent.atomic.AtomicLongArray offsets =
                new java.util.concurrent.atomic.AtomicLongArray(segments);
        final java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(segments);
        final java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        // ── try to pick up where a previous attempt stopped ──
        boolean resumed = readResumeState(sidecar, urlInput, segments, offsets)
                && outputFile.length() == total;
        if (resumed) {
            for (int i = 0; i < segments; i++) {
                long start = i * chunk;
                long end = (i == segments - 1) ? (total - 1L) : (start + chunk - 1L);
                long cur = offsets.get(i);
                if (cur < start || cur > end + 1L) { resumed = false; break; }
            }
        }
        if (!resumed) {
            for (int i = 0; i < segments; i++) offsets.set(i, i * chunk);
            deleteQuietly(sidecar);
        }
        // Reserve the file up-front: each segment seeks straight to its slice.
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
            raf.setLength(total);
        }

        for (int i = 0; i < segments; i++) {
            final int idx = i;
            final long start = i * chunk;
            final long end = (i == segments - 1) ? (total - 1L) : (start + chunk - 1L);
            futures.add(pool.submit((java.util.concurrent.Callable<Void>) () -> {
                byte[] buf = new byte[TURBO_BUFFER];
                long cursor = offsets.get(idx);
                int attempt = 0;
                long lastFlush = android.os.SystemClock.elapsedRealtime();
                while (true) {
                    HttpURLConnection c = openGet(urlInput); // per-segment GET, cookies included
                    try {
                        c.setRequestProperty("Range", "bytes=" + cursor + "-" + end);
                        int code = c.getResponseCode();
                        if (code != HttpURLConnection.HTTP_PARTIAL && code != HttpURLConnection.HTTP_OK) {
                            throw new IOException("Server returned HTTP " + code + " for " + urlInput);
                        }
                        if (code == HttpURLConnection.HTTP_OK && start > 0) {
                            // Server ignored the Range header — writing this body
                            // at our offset would corrupt the file.
                            throw new RangeUnsupportedException("Range ignored by " + urlInput);
                        }
                        try (InputStream in = new BufferedInputStream(c.getInputStream(), TURBO_BUFFER);
                             java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputFile, "rw")) {
                            raf.seek(cursor);
                            int n;
                            while ((n = in.read(buf)) != -1) {
                                DownloadControl.checkpoint(controlKey);
                                raf.write(buf, 0, n);
                                cursor += n;
                                offsets.set(idx, cursor);
                                long now = android.os.SystemClock.elapsedRealtime();
                                if (now - lastFlush >= 1500L) {
                                    lastFlush = now;
                                    writeResumeState(sidecar, urlInput, offsets);
                                }
                                if (monitor != null) {
                                    monitor.updateProgress(
                                            (int) Math.min(Integer.MAX_VALUE, bytesDone(offsets, chunk)),
                                            (int) Math.min(Integer.MAX_VALUE, total));
                                }
                            }
                        }
                        offsets.set(idx, end + 1L);
                        writeResumeState(sidecar, urlInput, offsets);
                        c.disconnect();
                        return null;
                    } catch (DownloadControl.DownloadCancelledException cancel) {
                        c.disconnect();
                        throw cancel;
                    } catch (RangeUnsupportedException rangeUnsupported) {
                        c.disconnect();
                        throw rangeUnsupported;
                    } catch (IOException e) {
                        c.disconnect();
                        offsets.set(idx, cursor);           // keep every byte we already own
                        writeResumeState(sidecar, urlInput, offsets);
                        if (++attempt >= SEGMENT_ATTEMPTS) throw e;
                        Log.i("DownloadUtils", "Segment " + start + "-" + end + " retry " + attempt + " — " + e);
                        try { Thread.sleep(600L * attempt); }
                        catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw e; }
                    }
                }
            }));
        }
        pool.shutdown();
        try {
            for (java.util.concurrent.Future<?> f : futures) f.get();
            if (monitor != null) monitor.updateProgress((int) Math.min(Integer.MAX_VALUE, total),
                    (int) Math.min(Integer.MAX_VALUE, total));
            deleteQuietly(sidecar); // complete — nothing left to resume
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
            throw new IOException("Interrupted while downloading " + urlInput, ie);
        } catch (java.util.concurrent.ExecutionException ee) {
            pool.shutdownNow();
            Throwable cause = ee.getCause();
            if (cause instanceof DownloadControl.DownloadCancelledException) {
                outputFile.delete();
                deleteQuietly(sidecar);
                throw (DownloadControl.DownloadCancelledException) cause;
            }
            throw new IOException("Unable to download from " + urlInput, cause);
        }
    }

    /** Total bytes owned so far, recomputed from the live per-segment offsets. */
    private static long bytesDone(java.util.concurrent.atomic.AtomicLongArray offsets, long chunk) {
        long sum = 0;
        for (int i = 0; i < offsets.length(); i++) sum += offsets.get(i) - (i * chunk);
        return sum < 0 ? 0 : sum;
    }


    public static <T> T downloadStringCached(String url, String cacheName, ParseCallback<T> parseCallback) throws IOException, ParseException{
        File cacheDestination = new File(Tools.DIR_CACHE, "string_cache/"+cacheName);
        if(cacheDestination.isFile() &&
                cacheDestination.canRead() &&
                System.currentTimeMillis() < (cacheDestination.lastModified() + 86400000)) {
            try {
                String cachedString = Tools.read(new FileInputStream(cacheDestination));
                return parseCallback.process(cachedString);
            }catch(IOException e) {
                Log.i("DownloadUtils", "Failed to read the cached file", e);
            }catch (ParseException e) {
                Log.i("DownloadUtils", "Failed to parse the cached file", e);
            }
        }
        String urlContent = DownloadUtils.downloadString(url);
        // if we download the file and fail parsing it, we will yeet outta there
        // and not cache the unparseable sting. We will return this after trying to save the downloaded
        // string into cache
        T parseResult = parseCallback.process(urlContent);

        boolean tryWriteCache;
        if(cacheDestination.exists()) {
            tryWriteCache = cacheDestination.canWrite();
        } else {
            tryWriteCache = FileUtils.ensureParentDirectorySilently(cacheDestination);
        }

        if(tryWriteCache) try {
            Tools.write(cacheDestination.getAbsolutePath(), urlContent);
        }catch(IOException e) {
            Log.i("DownloadUtils", "Failed to cache the string", e);
        }
        return parseResult;
    }

    private static <T> T downloadFile(Callable<T> downloadFunction) throws IOException{
        try {
            return downloadFunction.call();
        } catch (IOException e){
            throw e;
        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean verifyFile(File file, String sha1) {
        return file.exists() && Tools.compareSHA1(file, sha1);
    }

    public static <T> T ensureSha1(File outputFile, @Nullable String sha1, Callable<T> downloadFunction) throws IOException {
        // Skip if needed
        if(sha1 == null) {
            // If the file exists and we don't know it's SHA1, don't try to redownload it.
            if(outputFile.exists()) return null;
            else return downloadFile(downloadFunction);
        }

        int attempts = 0;
        boolean fileOkay = verifyFile(outputFile, sha1);
        T result = null;
        while (attempts < 5 && !fileOkay){
            attempts++;
            downloadFile(downloadFunction);
            fileOkay = verifyFile(outputFile, sha1);
        }
        if(!fileOkay) throw new SHA1VerificationException("SHA1 verifcation failed after 5 download attempts");
        return result;
    }

    /**
     * Get the content length for a given URL.
     * @param url the URL to get the length for
     * @return the length in bytes or -1 if not available
     * @throws IOException if an I/O error occurs.
     */
    public static long getContentLength(String url) throws IOException {
        HttpURLConnection urlConnection = openGet(url);
        try {
            urlConnection.setRequestMethod("HEAD");
            int responseCode = urlConnection.getResponseCode();
            if (responseCode >= 200 && responseCode <= 299) {
                net.kdt.pojavlaunch.utils.HttpCookies.store(url, urlConnection);
                long len = contentLength(urlConnection);
                // Some servers hide the size behind a redirect chain; ask once
                // more with the session/Location we just learned.
                if (len <= 0) {
                    Response res = openFollowingRedirects(url, null);
                    try {
                        if (res.conn.getResponseCode() >= 200
                                && res.conn.getResponseCode() <= 299) {
                            len = contentLength(res.conn);
                        }
                    } finally {
                        res.conn.disconnect();
                    }
                }
                return len;
            }
            return -1;
        } finally {
            urlConnection.disconnect();
        }
    }

    public interface ParseCallback<T> {
        T process(String input) throws ParseException;
    }
    public static class ParseException extends Exception {
        public ParseException(Exception e) {
            super(e);
        }
    }

    public static class SHA1VerificationException extends IOException {
        public SHA1VerificationException(String message) {
            super(message);
        }
    }
}

