package io.github.ibcmanager.workaround;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Fixed compatibility coordinates for both components; no catalogue-selected upgrades. */
public final class RuntimeCatalog {
    public static final String MAVEN = "https://repo.maven.apache.org/maven2/org/openjfx/";
    public static final List<String> FX_MODULES = List.of("base", "graphics", "controls", "fxml", "media", "swing", "web", "jsobject");
    public static final String JBR_VERSION = "25.0.4-b508.27";
    public static final String JAVAFX_VERSION = "25.0.4";
    public static final String JBR_FILE = "jbr-25.0.4-windows-x64-b508.27.zip";
    public static final URI JBR_ZIP = URI.create("https://cache-redirector.jetbrains.com/intellij-jbr/" + JBR_FILE);
    /** OpenJFX also publishes a required module whose name does not start with javafx. */
    public static String moduleName(String module) {
        if (!FX_MODULES.contains(module)) throw new IllegalArgumentException("Unsupported JavaFX component: " + module);
        return module.equals("jsobject") ? "jdk.jsobject" : "javafx." + module;
    }
    public static String artifactId(String module) { return moduleName(module).replace('.', '-'); }
    public static String artifactUrl(String module, String version) {
        if (!JAVAFX_VERSION.equals(version)) throw new IllegalArgumentException("Expected pinned JavaFX " + JAVAFX_VERSION);
        String artifact = artifactId(module);
        return MAVEN + artifact + "/" + version + "/" + artifact + "-" + version + "-win.jar";
    }
    public RuntimeCatalog(RuntimeDownloads downloads) { java.util.Objects.requireNonNull(downloads); }
    public record Selection(String jbrVersion, URI jbrZip, String javafxVersion) {
        public Selection {
            if (!JBR_VERSION.equals(jbrVersion) || !JBR_ZIP.equals(jbrZip) || !JAVAFX_VERSION.equals(javafxVersion)) {
                throw new IllegalArgumentException("Runtime selection must use the pinned JBR and JavaFX coordinates");
            }
        }
    }

    public Selection resolve() throws IOException {
        // No network request is needed to select versions. Missing pinned artifacts
        // fail installation; neither publisher catalogue can select a replacement.
        return new Selection(JBR_VERSION, JBR_ZIP, JAVAFX_VERSION);
    }
}
