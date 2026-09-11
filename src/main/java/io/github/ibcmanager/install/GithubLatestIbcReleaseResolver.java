package io.github.ibcmanager.install;

import io.github.ibcmanager.app.Version;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Resolves GitHub's latest published full IBC release without pinning a release version. */
final class GithubLatestIbcReleaseResolver implements IbcReleaseResolver {
    static final URI LATEST_RELEASE_API = URI.create(
            "https://api.github.com/repos/IbcAlpha/IBC/releases/latest");
    private static final String API_VERSION = "2026-03-10";
    private static final int CONNECT_TIMEOUT_MILLIS = 30_000;
    private static final int READ_TIMEOUT_MILLIS = 60_000;
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
    private static final String ASSET_PREFIX = "IBCWin-";

    @Override
    public IbcReleaseInfo resolveLatest(InstallProgress progress)
            throws IOException, IbcInstallationException {
        InstallProgress listener = progress == null ? InstallProgress.none() : progress;
        listener.update("Checking GitHub for the latest official IBC release", 0, -1);
        checkCancelled();
        HttpURLConnection connection = open(LATEST_RELEASE_API);
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                if (status == 403 && "0".equals(connection.getHeaderField("X-RateLimit-Remaining"))) {
                    throw new IOException("GitHub API rate limit reached while checking the latest IBC release. "
                            + "Try again after the limit resets.");
                }
                throw new IOException("GitHub returned HTTP " + status
                        + " while checking the latest IBC release.");
            }
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_METADATA_BYTES) {
                throw new IOException("GitHub release metadata exceeds the 2 MiB safety limit.");
            }
            byte[] metadata = readBounded(connection.getInputStream(), contentLength);
            checkCancelled();
            return parseReleaseMetadata(decodeUtf8(metadata));
        } finally {
            connection.disconnect();
        }
    }

    static IbcReleaseInfo parseReleaseMetadata(String json)
            throws IOException, IbcInstallationException {
        Map<?, ?> root = requireObject(StrictJsonParser.parse(json), "GitHub release metadata");
        if (requireBoolean(root, "draft")) {
            throw new IbcInstallationException("GitHub marked the latest IBC release as a draft.");
        }
        if (requireBoolean(root, "prerelease")) {
            throw new IbcInstallationException("GitHub marked the latest IBC release as a prerelease.");
        }

        String tagName = requireString(root, "tag_name");
        IbcVersion releaseVersion = IbcVersion.parseTag(tagName);
        IbcVersion minimumVersion = IbcVersion.parse(Version.IBC_MINIMUM_SUPPORTED_VERSION);
        if (releaseVersion.compareTo(minimumVersion) < 0) {
            throw new IbcInstallationException("GitHub's latest IBC release is " + releaseVersion
                    + ", which is older than IBC Manager's minimum compatible release "
                    + minimumVersion + '.');
        }

        String expectedAssetName = ASSET_PREFIX + releaseVersion.text() + ".zip";
        List<?> assets = requireArray(root, "assets");
        Map<?, ?> matchingAsset = null;
        for (Object candidate : assets) {
            Map<?, ?> asset = requireObject(candidate, "GitHub release asset");
            Object nameValue = asset.get("name");
            if (!(nameValue instanceof String name) || !expectedAssetName.equals(name)) continue;
            if (matchingAsset != null) {
                throw new IbcInstallationException("GitHub's latest IBC release contains duplicate Windows assets: "
                        + expectedAssetName);
            }
            matchingAsset = asset;
        }
        if (matchingAsset == null) {
            throw new IbcInstallationException("GitHub's latest IBC release " + releaseVersion
                    + " does not contain the expected Windows asset " + expectedAssetName + '.');
        }

        String state = requireString(matchingAsset, "state");
        if (!"uploaded".equals(state)) {
            throw new IbcInstallationException("The latest IBC Windows asset is not in the uploaded state.");
        }
        long assetBytes = requireLong(matchingAsset, "size");
        if (assetBytes < 1 || assetBytes > HttpsIbcReleaseDownloader.MAX_ARCHIVE_BYTES) {
            throw new IbcInstallationException("The latest IBC Windows asset is outside the 64 MiB safety limit.");
        }
        String digest = requireString(matchingAsset, "digest").toLowerCase(Locale.ROOT);
        if (!digest.matches("sha256:[0-9a-f]{64}")) {
            throw new IbcInstallationException("GitHub did not publish a usable SHA-256 digest for the latest "
                    + "IBC Windows asset.");
        }
        URI downloadUri;
        try {
            downloadUri = URI.create(requireString(matchingAsset, "browser_download_url"));
        } catch (IllegalArgumentException ex) {
            throw new IbcInstallationException("GitHub returned an invalid IBC asset download URL.", ex);
        }
        validateAssetUri(downloadUri, tagName, expectedAssetName);
        return new IbcReleaseInfo(releaseVersion.text(), tagName, expectedAssetName,
                downloadUri, assetBytes, digest.substring("sha256:".length()));
    }

    static void validateMetadataUri(URI uri) throws IOException {
        if (!LATEST_RELEASE_API.equals(uri)) {
            throw new IOException("Unexpected GitHub latest-release API URL: " + uri);
        }
    }

    static void validateAssetUri(URI uri, String tagName, String assetName)
            throws IbcInstallationException {
        Objects.requireNonNull(uri, "uri");
        String expectedPath = "/IbcAlpha/IBC/releases/download/" + tagName + '/' + assetName;
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || !expectedPath.equals(uri.getPath())
                || !Objects.equals(uri.getRawPath(), uri.getPath())) {
            throw new IbcInstallationException("GitHub returned an unexpected IBC asset download URL.");
        }
    }

    private static HttpURLConnection open(URI uri) throws IOException {
        validateMetadataUri(uri);
        URLConnection raw = uri.toURL().openConnection();
        if (!(raw instanceof HttpURLConnection connection)) {
            throw new IOException("GitHub release metadata did not use an HTTP connection.");
        }
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        connection.setReadTimeout(READ_TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
        connection.setRequestProperty("User-Agent", "IBC-Manager/" + Version.VERSION);
        return connection;
    }

    private static byte[] readBounded(InputStream input, long contentLength) throws IOException {
        int initialSize = contentLength > 0 && contentLength <= MAX_METADATA_BYTES
                ? (int) contentLength : 16 * 1024;
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize)) {
            byte[] buffer = new byte[16 * 1024];
            while (true) {
                checkCancelled();
                int count = source.read(buffer);
                if (count < 0) break;
                if (output.size() + count > MAX_METADATA_BYTES) {
                    throw new IOException("GitHub release metadata exceeded the 2 MiB safety limit.");
                }
                output.write(buffer, 0, count);
            }
            if (output.size() == 0) throw new IOException("GitHub returned empty release metadata.");
            return output.toByteArray();
        }
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("GitHub release metadata is not valid UTF-8.", ex);
        }
    }

    private static Map<?, ?> requireObject(Object value, String description) throws IOException {
        if (value instanceof Map<?, ?> map) return map;
        throw new IOException(description + " must be a JSON object.");
    }

    private static List<?> requireArray(Map<?, ?> object, String key) throws IOException {
        Object value = object.get(key);
        if (value instanceof List<?> list) return list;
        throw new IOException("GitHub release metadata field '" + key + "' must be an array.");
    }

    private static String requireString(Map<?, ?> object, String key) throws IOException {
        Object value = object.get(key);
        if (value instanceof String string && !string.isBlank()) return string;
        throw new IOException("GitHub release metadata field '" + key + "' must be a nonblank string.");
    }

    private static boolean requireBoolean(Map<?, ?> object, String key) throws IOException {
        Object value = object.get(key);
        if (value instanceof Boolean bool) return bool;
        throw new IOException("GitHub release metadata field '" + key + "' must be a boolean.");
    }

    private static long requireLong(Map<?, ?> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof BigDecimal number)) {
            throw new IOException("GitHub release metadata field '" + key + "' must be a number.");
        }
        try {
            return number.longValueExact();
        } catch (ArithmeticException ex) {
            throw new IOException("GitHub release metadata field '" + key + "' is not an integer.", ex);
        }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("IBC installation was cancelled");
        }
    }
}
