package io.github.ibcmanager.install;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

interface IbcReleaseDownloader {
    DownloadResult download(URI source, Path destination, InstallProgress progress) throws IOException;

    record DownloadResult(URI finalUri, long bytes, String sha256) {
        public DownloadResult {
            finalUri = Objects.requireNonNull(finalUri, "finalUri");
            if (bytes < 1) throw new IllegalArgumentException("bytes must be positive");
            sha256 = Objects.requireNonNull(sha256, "sha256").trim().toLowerCase(Locale.ROOT);
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must contain 64 hexadecimal characters");
            }
        }
    }
}
