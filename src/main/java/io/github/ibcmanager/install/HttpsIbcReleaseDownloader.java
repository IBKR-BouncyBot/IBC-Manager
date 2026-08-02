package io.github.ibcmanager.install;

import io.github.ibcmanager.app.Version;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;

final class HttpsIbcReleaseDownloader implements IbcReleaseDownloader {
    static final long MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_REDIRECTS = 5;
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int READ_TIMEOUT_MILLIS = 120_000;
    private static final Set<String> EXACT_HOSTS = Set.of("github.com");

    @Override
    public DownloadResult download(URI source, Path destination, InstallProgress progress) throws IOException {
        validateUri(source);
        Files.deleteIfExists(destination);
        HttpURLConnection connection = null;
        try {
            URI current = source;
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                checkCancelled();
                connection = open(current);
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    if (redirect == MAX_REDIRECTS) {
                        throw new IOException("Too many redirects while downloading IBC.");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isBlank()) {
                        throw new IOException("GitHub returned a redirect without a destination.");
                    }
                    URI next = current.resolve(location);
                    validateUri(next);
                    connection.disconnect();
                    connection = null;
                    current = next;
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new IOException("GitHub returned HTTP " + status + " while downloading IBC.");
                }
                long contentLength = connection.getContentLengthLong();
                if (contentLength > MAX_ARCHIVE_BYTES) {
                    throw new IOException("The IBC release archive is larger than the 64 MiB safety limit.");
                }
                MessageDigest digest = sha256Digest();
                long copied = copyBounded(connection.getInputStream(), destination, digest,
                        contentLength, progress == null ? InstallProgress.none() : progress);
                checkCancelled();
                return new DownloadResult(current, copied, HexFormat.of().formatHex(digest.digest()));
            }
            throw new IOException("Unexpected redirect state while downloading IBC.");
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(destination);
            throw ex;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    static void validateUri(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null
                || uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IOException("IBC downloads must use a plain HTTPS URL on the standard port.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!EXACT_HOSTS.contains(host) && !host.endsWith(".githubusercontent.com")) {
            throw new IOException("IBC download redirected to an untrusted host: " + host);
        }
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        URLConnection raw = uri.toURL().openConnection();
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("IBC download did not use an HTTP connection.");
        }
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/octet-stream");
        connection.setRequestProperty("User-Agent", "IBC-Manager/" + Version.VERSION);
        return connection;
    }

    private static long copyBounded(InputStream input, Path destination, MessageDigest digest,
            long contentLength, InstallProgress progress) throws IOException {
        long copied = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream source = input;
             var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            while (true) {
                checkCancelled();
                int count = source.read(buffer);
                if (count < 0) break;
                copied += count;
                if (copied > MAX_ARCHIVE_BYTES) {
                    throw new IOException("The IBC release archive exceeded the 64 MiB safety limit.");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
                progress.update("Downloading IBC from GitHub", copied, contentLength);
            }
        }
        if (copied == 0) throw new IOException("GitHub returned an empty IBC release archive.");
        return copied;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == 307 || status == 308;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("IBC installation was cancelled");
        }
    }
}
