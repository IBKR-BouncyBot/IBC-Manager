package io.github.ibcmanager.workaround;

import io.github.ibcmanager.app.Version;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.function.BooleanSupplier;

/** HTTPS-only publisher allowlist, bounded bodies and bounded redirect/time budgets. */
public interface RuntimeDownloads {
    String text(URI uri, int maxBytes) throws IOException;
    void file(URI uri, Path destination, long maxBytes, BooleanSupplier cancelled) throws IOException;

    static RuntimeDownloads official() { return new Official(); }

    static void requireAllowed(URI uri) throws IOException {
        requireHttps(uri);
        String host = uri.getHost();
        boolean allowed = switch (host.toLowerCase(java.util.Locale.ROOT)) {
            case "api.github.com" -> uri.getPath().startsWith("/repos/JetBrains/JetBrainsRuntime/releases");
            case "cache-redirector.jetbrains.com" -> uri.getPath().startsWith("/intellij-jbr/");
            case "download.jetbrains.com", "download-cdn.jetbrains.com" -> uri.getPath().startsWith("/idea/jbr/") || uri.getPath().startsWith("/jbr/") || uri.getPath().startsWith("/intellij-jbr/");
            case "intellij-jbr.s3.amazonaws.com" -> true;
            case "repo.maven.apache.org" -> uri.getPath().startsWith("/maven2/org/openjfx/");
            default -> false;
        };
        if (!allowed) throw new IOException("Runtime publisher/redirect is not allowed: " + host);
        if (uri.getPath().contains("..") || uri.getPath().contains("\\")) throw new IOException("Unsafe download path");
    }

    private static void requireHttps(URI uri) throws IOException {
        if (uri == null || !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getFragment() != null) {
            throw new IOException("Runtime download requires an approved HTTPS publisher");
        }
    }

    /** The CDN is a redirect destination, never a replacement publisher or wildcard trust rule. */
    static void requireAllowedRedirect(URI initial, URI target) throws IOException {
        requireAllowed(initial);
        requireHttps(target);
        if ("d2xrhe97vsfxuc.cloudfront.net".equalsIgnoreCase(target.getHost())) {
            // JetBrains drops /intellij-jbr/ when redirecting to this distribution.
            // Bind the destination to the exact original Windows-x64 Java-25 artifact,
            // including whether this request is for its ZIP or its SHA-512 sidecar.
            String prefix = "/intellij-jbr/";
            String path = initial.getRawPath();
            boolean jbrPublisher = "cache-redirector.jetbrains.com".equalsIgnoreCase(initial.getHost())
                    && path.startsWith(prefix) && initial.getRawQuery() == null;
            String file = jbrPublisher ? path.substring(prefix.length()) : "";
            if (!file.matches("jbr-25(?:\\.[0-9]+){0,3}-windows-x64-b[0-9]+(?:\\.[0-9]+)*\\.zip(?:\\.checksum)?")
                    || !("/" + file).equals(target.getRawPath()) || target.getRawQuery() != null) {
                throw new IOException("Runtime CDN redirect does not match the requested JetBrains artifact");
            }
            return;
        }
        requireAllowed(target);
    }

    /** Typed status evidence; callers never infer a missing resource from message text. */
    final class HttpStatusException extends IOException {
        private static final long serialVersionUID = 1L;
        private final int statusCode;
        private final URI requestedUri;
        private final URI responseUri;

        HttpStatusException(int statusCode, URI requestedUri, URI responseUri) {
            // All these paths are public publisher resources. Do not print query strings,
            // which can contain signed redirect parameters or other sensitive values.
            super("Runtime publisher returned HTTP " + statusCode + " for "
                    + responseUri.getScheme() + "://" + responseUri.getHost() + responseUri.getRawPath());
            this.statusCode = statusCode;
            this.requestedUri = requestedUri;
            this.responseUri = responseUri;
        }
        public int statusCode() { return statusCode; }
        public URI requestedUri() { return requestedUri; }
        public URI responseUri() { return responseUri; }
    }

    /** A network failure for one validated request; not a redirect or content-policy failure. */
    final class TransportException extends IOException {
        private static final long serialVersionUID = 1L;
        private final URI requestedUri;
        private final URI responseUri;
        TransportException(URI requestedUri, URI responseUri, IOException cause) {
            super("Runtime publisher transfer failed for " + responseUri.getScheme() + "://"
                    + responseUri.getHost() + responseUri.getRawPath(), cause);
            this.requestedUri = requestedUri;
            this.responseUri = responseUri;
        }
        public URI requestedUri() { return requestedUri; }
        public URI responseUri() { return responseUri; }
    }

    /** An empty/oversized successful response is invalid content, not an unavailable sidecar. */
    final class ContentException extends IOException {
        private static final long serialVersionUID = 1L;
        ContentException(String message) { super(message); }
    }

    final class Official implements RuntimeDownloads {
        @FunctionalInterface
        interface ConnectionOpener {
            HttpURLConnection open(URI uri) throws IOException;
        }
        private final ConnectionOpener connections;

        Official() { this(uri -> (HttpURLConnection) uri.toURL().openConnection()); }

        /** Package-scoped transport seam: tests exercise the real redirect loop without Internet access. */
        Official(ConnectionOpener connections) { this.connections = java.util.Objects.requireNonNull(connections); }

        private HttpURLConnection open(URI initial) throws IOException {
            requireAllowed(initial);
            URI uri = initial;
            for (int redirects = 0; redirects <= 5; redirects++) {
                requireAllowedRedirect(initial, uri);
                HttpURLConnection connection;
                try { connection = connections.open(uri); }
                catch (IOException ex) { throw transportFailure(initial, uri, ex); }
                try {
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setRequestProperty("User-Agent", "IBC-Manager/" + Version.VERSION + "-runtime-setup");
                    connection.setRequestProperty("Accept", "application/vnd.github+json, text/plain, */*");
                    int status;
                    try { status = connection.getResponseCode(); }
                    catch (IOException ex) { throw transportFailure(initial, uri, ex); }
                    if (Set.of(301, 302, 303, 307, 308).contains(status)) {
                        String location = connection.getHeaderField("Location");
                        if (location == null || location.isBlank()) throw new IOException("Runtime redirect has no location");
                        try { uri = uri.resolve(location); }
                        catch (IllegalArgumentException ex) { throw new IOException("Invalid runtime redirect", ex); }
                        connection.disconnect();
                        continue;
                    }
                    if (status != 200) {
                        throw new HttpStatusException(status, initial, uri);
                    }
                    return connection;
                } catch (IOException | RuntimeException ex) {
                    connection.disconnect();
                    throw ex;
                }
            }
            throw new IOException("Too many runtime download redirects");
        }
        @Override public String text(URI uri, int limit) throws IOException {
            HttpURLConnection connection = open(uri);
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                transfer(input, bytes, limit, () -> false);
                return bytes.toString(StandardCharsets.UTF_8);
            } catch (IOException ex) {
                throw transportFailure(uri, URI.create(connection.getURL().toString()), ex);
            } finally { connection.disconnect(); }
        }
        private static IOException transportFailure(URI initial, URI response, IOException cause) {
            if (cause instanceof ContentException || cause instanceof TransportException
                    || Thread.currentThread().isInterrupted()) return cause;
            // TLS identity failures and cancellation must never authorize an unchecked download.
            for (Throwable failure = cause; failure != null; failure = failure.getCause()) {
                if (failure instanceof javax.net.ssl.SSLException
                        || (failure instanceof java.io.InterruptedIOException
                        && !(failure instanceof java.net.SocketTimeoutException))) return cause;
            }
            return new TransportException(initial, response, cause);
        }
        @Override public void file(URI uri, Path target, long limit, BooleanSupplier cancelled) throws IOException {
            HttpURLConnection connection = open(uri);
            try {
                long size = connection.getContentLengthLong();
                if (size > limit) throw new ContentException("Runtime download exceeds size limit");
                try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                    transfer(input, output, limit, cancelled);
                }
            } finally { connection.disconnect(); }
        }
        private static void transfer(InputStream input, java.io.OutputStream output, long max, BooleanSupplier cancelled) throws IOException {
            byte[] buffer = new byte[65536]; long count = 0;
            long end = System.nanoTime() + java.time.Duration.ofMinutes(15).toNanos();
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Runtime download cancelled");
                if (System.nanoTime() > end) throw new java.net.SocketTimeoutException("Runtime download timed out");
                count += read;
                if (count > max) throw new ContentException("Runtime download exceeds size limit");
                output.write(buffer, 0, read);
            }
            if (count == 0) throw new ContentException("Runtime publisher returned an empty file");
        }
    }
}
