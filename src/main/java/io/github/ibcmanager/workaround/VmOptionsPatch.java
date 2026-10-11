package io.github.ibcmanager.workaround;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/** Reversible owned block; user edits outside the block survive removal. */
public final class VmOptionsPatch {
    public static final String BEGIN = "# BEGIN IBC MANAGER JBR25 WORKAROUND";
    public static final String END = "# END IBC MANAGER JBR25 WORKAROUND";
    private VmOptionsPatch() { }
    public static byte[] apply(byte[] original, Path javafxLib) throws IOException {
        String source = decode(original);
        if (Pattern.compile("(?m)^\\s*-Djava\\.awt\\.headless=true\\s*$").matcher(source).find()) {
            throw new IOException("Remove the explicit java.awt.headless=true option before enabling the graphical workaround");
        }
        if (source.contains(BEGIN) || source.contains(END)) throw new IOException("An existing managed JavaFX block needs recovery before setup");
        String nl = source.contains("\r\n") ? "\r\n" : "\n";
        String cleaned = removeModuleLines(source);
        if (!cleaned.isEmpty() && !cleaned.endsWith("\n") && !cleaned.endsWith("\r")) cleaned += nl;
        String path = javafxLib.toAbsolutePath().toString();
        io.github.ibcmanager.security.WindowsCommandSafety.requireSafeExternalArgument(path);
        return (cleaned + BEGIN + nl + "--module-path=\"" + path + "\"" + nl
                + "--add-modules=" + RuntimeBundleInstaller.moduleList() + nl
                + "-Djavafx.cachedir=\"" + javafxLib.toAbsolutePath().getParent().getParent().resolve("native-cache") + "\"" + nl + END + nl).getBytes(StandardCharsets.UTF_8);
    }
    public static byte[] remove(byte[] original, byte[] installed, byte[] current) throws IOException {
        if (Arrays.equals(installed, current)) return original.clone();
        String expected = decode(installed); String now = decode(current);
        int a = expected.indexOf(BEGIN); int b = expected.indexOf(END, a);
        if (a < 0 || b < a) throw new IOException("Invalid workaround backup");
        String block = expected.substring(a, b + END.length());
        int at = now.indexOf(block);
        if (at < 0 || at != now.lastIndexOf(block) || now.indexOf(BEGIN) != at || now.lastIndexOf(BEGIN) != at) {
            throw new IOException("The managed vmoptions block was changed; no options or runtimes were removed");
        }
        String without = now.substring(0, at) + now.substring(at + block.length());
        if (!without.equals(removeModuleLines(without)) || without.contains(BEGIN) || without.contains(END)) {
            throw new IOException("New module options conflict with restoration; no runtime files were removed");
        }
        String nl = now.contains("\r\n") ? "\r\n" : "\n";
        String restoredKeys = lines(decode(original)).stream().filter(VmOptionsPatch::moduleLine)
                .collect(java.util.stream.Collectors.joining());
        if (!restoredKeys.isEmpty() && !restoredKeys.endsWith("\n")) restoredKeys += nl;
        return (now.substring(0, at) + restoredKeys + now.substring(at + block.length())).getBytes(StandardCharsets.UTF_8);
    }
    public static String decode(byte[] bytes) throws IOException {
        if (bytes.length > 1024 * 1024) throw new IOException("VM options exceed safety limit");
        try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException ex) { throw new IOException("VM options must use UTF-8/ASCII", ex); }
    }
    private static List<String> lines(String source) {
        return Pattern.compile(".*?(?:\\r\\n|\\r|\\n|$)", Pattern.DOTALL).matcher(source).results()
                .map(java.util.regex.MatchResult::group).filter(s -> !s.isEmpty()).toList();
    }
    private static boolean moduleLine(String line) {
        String value = line.strip().replace("\ufeff", "");
        return value.startsWith("--module-path=") || value.startsWith("--module-path ") || value.startsWith("-p ")
                || value.startsWith("-Djavafx.cachedir=") || value.startsWith("--add-modules=") || value.startsWith("--add-modules ");
    }
    private static String removeModuleLines(String source) { return lines(source).stream().filter(s -> !moduleLine(s)).collect(java.util.stream.Collectors.joining()); }
}
