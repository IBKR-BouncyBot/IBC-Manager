package io.github.ibcmanager.install;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

record IbcReleaseInfo(
        String version,
        String tagName,
        String assetName,
        URI downloadUri,
        long assetBytes,
        String sha256) {

    IbcReleaseInfo {
        version = requireText(version, "version");
        tagName = requireText(tagName, "tagName");
        assetName = requireText(assetName, "assetName");
        downloadUri = Objects.requireNonNull(downloadUri, "downloadUri");
        if (assetBytes < 1 || assetBytes > HttpsIbcReleaseDownloader.MAX_ARCHIVE_BYTES) {
            throw new IllegalArgumentException("assetBytes is outside the supported archive-size range");
        }
        sha256 = requireText(sha256, "sha256").toLowerCase(Locale.ROOT);
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
        }
    }

    private static String requireText(String value, String name) {
        String result = Objects.requireNonNull(value, name).trim();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }
}
