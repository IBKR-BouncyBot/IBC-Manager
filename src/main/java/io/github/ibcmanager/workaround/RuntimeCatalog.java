package io.github.ibcmanager.workaround;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.regex.Pattern;

/** Fixed JBR compatibility build and released JavaFX-25 modules; no silent JBR upgrades. */
public final class RuntimeCatalog {
    public static final String MAVEN = "https://repo.maven.apache.org/maven2/org/openjfx/";
    public static final List<String> FX_MODULES = List.of("base", "graphics", "controls", "fxml", "media", "swing", "web", "jsobject");
    public static final String JBR_VERSION = "25.0.4-b508.27";
    public static final String JBR_FILE = "jbr-25.0.4-windows-x64-b508.27.zip";
    public static final URI JBR_ZIP = URI.create("https://cache-redirector.jetbrains.com/intellij-jbr/" + JBR_FILE);
    /** OpenJFX also publishes a required module whose name does not start with javafx. */
    public static String moduleName(String module) {
        if (!FX_MODULES.contains(module)) throw new IllegalArgumentException("Unsupported JavaFX component: " + module);
        return module.equals("jsobject") ? "jdk.jsobject" : "javafx." + module;
    }
    public static String artifactId(String module) { return moduleName(module).replace('.', '-'); }
    public static String artifactUrl(String module, String version) {
        if (!version.matches("25(?:\\.[0-9]+){0,3}")) throw new IllegalArgumentException("Expected a released JavaFX 25 version");
        String artifact = artifactId(module);
        return MAVEN + artifact + "/" + version + "/" + artifact + "-" + version + "-win.jar";
    }
    private final RuntimeDownloads downloads;
    public RuntimeCatalog(RuntimeDownloads downloads) { this.downloads = downloads; }
    public record Selection(String jbrVersion, URI jbrZip, String javafxVersion) { }

    public Selection resolve() throws IOException {
        // Exact publisher coordinates: do not scan a moving release list, or fall back to
        // another JBR build if this compatibility download is temporarily unavailable.
        String xml = downloads.text(URI.create(MAVEN + "javafx-controls/maven-metadata.xml"), 1024 * 1024);
        return new Selection(JBR_VERSION, JBR_ZIP, latestJavafxVersion(xml));
    }
    static String latestJavafxVersion(String metadata) throws IOException {
        // Only literal numeric <version> values are accepted; DTD/entities are never expanded.
        if (metadata.contains("<!DOCTYPE") || metadata.contains("<!ENTITY")) throw new IOException("Unsafe JavaFX metadata");
        var matcher = Pattern.compile("<version>\\s*(25(?:\\.[0-9]+){0,3})\\s*</version>").matcher(metadata);
        String best = null;
        while (matcher.find()) if (best == null || compareVersion(matcher.group(1), best) > 0) best = matcher.group(1);
        if (best == null) throw new IOException("No released JavaFX 25 version found in the OpenJFX Maven catalogue");
        return best;
    }
    public static int compareVersion(String a, String b) {
        String[] left = a.split("\\."); String[] right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            java.math.BigInteger x = i < left.length ? new java.math.BigInteger(left[i]) : java.math.BigInteger.ZERO;
            java.math.BigInteger y = i < right.length ? new java.math.BigInteger(right[i]) : java.math.BigInteger.ZERO;
            int c = x.compareTo(y); if (c != 0) return c;
        }
        return 0;
    }
}
