package io.github.ibcmanager.config;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public final class ConfigDocumentTests implements TestSuite {
    @Override public String name() { return "IBC configuration document"; }

    @Override
    public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>();
        tests.add(new NamedTest("round-trips LF text exactly", () -> exact("# c\nA=1\n\nB = two\n")));
        tests.add(new NamedTest("round-trips CRLF text exactly", () -> exact("# c\r\nA=1\r\n\r\nB = two\r\n")));
        tests.add(new NamedTest("round-trips CR text exactly", () -> exact("# c\rA=1\rB=2\r")));
        tests.add(new NamedTest("round-trips text without final newline", () -> exact("A=1\nB=2")));
        tests.add(new NamedTest("round-trips empty text", () -> exact("")));
        tests.add(new NamedTest("reads the last duplicate value", this::lastDuplicateWins));
        tests.add(new NamedTest("reports active duplicate keys", this::duplicates));
        tests.add(new NamedTest("set preserves key formatting", this::preservesFormatting));
        tests.add(new NamedTest("set disables earlier duplicates", this::disablesDuplicates));
        tests.add(new NamedTest("set appends unknown keys", this::appendUnknown));
        tests.add(new NamedTest("remove deletes all matching settings", this::remove));
        tests.add(new NamedTest("comments and malformed lines stay untouched", this::rawLines));
        tests.add(new NamedTest("redacts both password settings", this::redaction));
        tests.add(new NamedTest("detects plaintext secrets", this::secretDetection));
        tests.add(new NamedTest("detects every duplicate secret even when the final value is blank",
                this::hiddenDuplicateSecretDetection));
        tests.add(new NamedTest("disabling a duplicate secret never persists its value",
                this::duplicateSecretNeverPersisted));
        tests.add(new NamedTest("a configuration damaged by an earlier release is detected and repaired",
                this::repairsLeakedDuplicateComment));
        tests.add(new NamedTest("redacts sensitive assignments in user comments and raw lines",
                this::redactsNonSettingSecrets));
        tests.add(new NamedTest("sanitizes every sensitive occurrence case-insensitively",
                this::sanitizesAllSensitiveValues));
        tests.add(new NamedTest("copy is structurally independent", this::copyIndependent));
        tests.add(new NamedTest("rejects invalid setting keys", this::invalidKey));
        tests.add(new NamedTest("rejects config-breaking setting values", this::unsafeValue));
        tests.add(new NamedTest("schema keys are unique", this::schemaUnique));
        tests.add(new NamedTest("schema sensitive keys agree with definitions", this::schemaSensitive));
        tests.add(new NamedTest("bundled template parses and covers core keys", this::templateParses));
        tests.add(new NamedTest("profile-controlled settings are identified", this::profileControlled));
        Random random = new Random(0xC0F16L);
        for (int index = 0; index < 50; index++) {
            long seed = random.nextLong();
            tests.add(new NamedTest("random exact parse/render " + index, () -> randomExact(seed)));
        }
        return tests;
    }

    private static void exact(String text) {
        IbcConfigDocument document = IbcConfigDocument.parse(text);
        Assertions.equals(text, document.render(), "parse/render must be lossless");
    }

    private void lastDuplicateWins() {
        IbcConfigDocument document = IbcConfigDocument.parse("A=one\nA=two\n");
        Assertions.equals("two", document.get("A").orElseThrow(), "last active duplicate must be returned");
        Assertions.equals(List.of("one", "two"), document.getAll("A"), "all values must remain queryable");
    }

    private void duplicates() {
        IbcConfigDocument document = IbcConfigDocument.parse("A=1\nB=2\nA=3\nB=4\nC=5\n");
        Assertions.equals(java.util.Set.of("A", "B"), document.duplicateKeys(), "duplicates must be reported once");
    }

    private void preservesFormatting() {
        IbcConfigDocument document = IbcConfigDocument.parse("  A   =   old\r\n");
        document.set("A", "new");
        Assertions.equals("  A   =   new\r\n", document.render(), "spacing and line ending must be preserved");
    }

    private void disablesDuplicates() {
        IbcConfigDocument document = IbcConfigDocument.parse("A=1\n# keep\nA = 2\nA=3\n");
        document.set("A", "final");
        String rendered = document.render();
        Assertions.contains(rendered, "# IBC Manager disabled duplicate: A=1", "first duplicate must be commented");
        Assertions.contains(rendered, "# IBC Manager disabled duplicate: A = 2", "second duplicate must be commented");
        Assertions.contains(rendered, "A=final", "last value must be updated");
        Assertions.equals(java.util.Set.of(), IbcConfigDocument.parse(rendered).duplicateKeys(), "duplicates must be eliminated");
    }

    private void appendUnknown() {
        IbcConfigDocument document = IbcConfigDocument.parse("A=1\n");
        document.set("Future.Setting", "x");
        Assertions.equals("A=1\n\nFuture.Setting=x\n", document.render(), "new setting must be separated by a blank line");
    }

    private void remove() {
        IbcConfigDocument document = IbcConfigDocument.parse("A=1\nB=2\nA=3\n");
        document.remove("A");
        Assertions.equals("B=2\n", document.render(), "all active occurrences must be removed");
        Assertions.isTrue(document.get("A").isEmpty(), "removed key must not be returned");
    }

    private void rawLines() {
        String source = "; semicolon\n# hash\nnot a setting\n =bad\nA=1\n";
        IbcConfigDocument document = IbcConfigDocument.parse(source);
        document.set("A", "2");
        Assertions.contains(document.render(), "; semicolon\n# hash\nnot a setting\n =bad\n", "non-settings must remain exact");
    }

    private void redaction() {
        IbcConfigDocument document = IbcConfigDocument.parse(
                "IbLoginId=user\nIbPassword = secret one\nFIXPassword=secret-two\nOther=keep\n");
        String redacted = document.renderRedacted();
        Assertions.notContains(redacted, "secret one", "IB password must be redacted");
        Assertions.notContains(redacted, "secret-two", "FIX password must be redacted");
        Assertions.contains(redacted, "IbPassword = [REDACTED]", "original formatting must remain");
        Assertions.contains(redacted, "Other=keep", "nonsecret values must remain");
        Assertions.contains(document.render(), "secret one", "redaction must not mutate source");
    }

    private void secretDetection() {
        Assertions.isFalse(IbcConfigDocument.parse("IbPassword=\n").hasPlaintextSecret(), "blank secret is safe");
        Assertions.isTrue(IbcConfigDocument.parse("IbPassword=x\n").hasPlaintextSecret(), "IB secret must be detected");
        Assertions.isTrue(IbcConfigDocument.parse("FIXPassword=x\n").hasPlaintextSecret(), "FIX secret must be detected");
    }

    private void hiddenDuplicateSecretDetection() {
        IbcConfigDocument document = IbcConfigDocument.parse(
                "IbPassword=earlier-secret\nIbPassword=\nFIXPassword=first-fix-secret\nFIXPassword=\n");
        Assertions.isTrue(document.hasPlaintextSecret(),
                "an earlier active duplicate must not be hidden by a later blank value");
        String redacted = document.renderRedacted();
        Assertions.notContains(redacted, "earlier-secret", "all IB password duplicates must be redacted");
        Assertions.notContains(redacted, "first-fix-secret", "all FIX password duplicates must be redacted");
        Assertions.contains(redacted, "IbPassword=[REDACTED]", "earlier IB password must be visibly redacted");
        Assertions.contains(redacted, "FIXPassword=[REDACTED]", "earlier FIX password must be visibly redacted");
    }

    private void duplicateSecretNeverPersisted() {
        IbcConfigDocument document = IbcConfigDocument.parse(
                "IbLoginId=trader\nIbPassword=first-secret\nIbPassword=second-secret\n");
        document.set("IbPassword", "");
        Assertions.notContains(document.render(), "first-secret",
                "a disabled duplicate must not carry its password into a comment");
        Assertions.notContains(document.render(), "second-secret",
                "the retained duplicate must be blanked");
        Assertions.contains(document.render(), "IBC Manager disabled duplicate",
                "the duplicate must still be visibly disabled");
        Assertions.isFalse(document.hasPlaintextSecret(), "no plaintext secret may remain");
        Assertions.notContains(document.renderRedacted(), "first-secret",
                "an exported bundle must not contain the password");
    }

    private void repairsLeakedDuplicateComment() {
        String damaged = "# IBC Manager disabled duplicate: IbPassword=leaked-secret\nIbPassword=\n";
        Assertions.isTrue(IbcConfigDocument.parse(damaged).hasPlaintextSecret(),
                "a configuration damaged by an earlier release must be detected");
        Assertions.notContains(IbcConfigDocument.parse(damaged).renderRedacted(), "leaked-secret",
                "export must redact a leaked duplicate comment");
        IbcConfigDocument repaired = IbcConfigDocument.parse(damaged);
        repaired.redactSensitiveComments();
        Assertions.isFalse(repaired.hasPlaintextSecret(), "repair must clear the leak");
        Assertions.notContains(repaired.render(), "leaked-secret", "the password must be gone");

        IbcConfigDocument documented = IbcConfigDocument.parse("# set IbPassword= only at runtime\nA=1\n");
        Assertions.isTrue(documented.hasPlaintextSecret(),
                "assignment-shaped text in a comment must be treated conservatively");
        documented.redactSensitiveComments();
        Assertions.contains(documented.render(), "# set IbPassword=[REDACTED]",
                "repair must redact assignment-shaped text in user comments");
    }

    private void redactsNonSettingSecrets() {
        IbcConfigDocument document = IbcConfigDocument.parse(
                "# copied value: IbPassword=comment-secret\n"
                        + "; FIXPassword = semicolon-secret\n"
                        + "not-a-setting IbPassword=raw-secret\n"
                        + "# IbPassword=   \n"
                        + "IbPassword=\nFIXPassword=\n");
        Assertions.isTrue(document.hasPlaintextSecret(), "comment and raw-line assignments must be detected");
        String redacted = document.renderRedacted();
        for (String secret : List.of("comment-secret", "semicolon-secret", "raw-secret")) {
            Assertions.notContains(redacted, secret, "diagnostic rendering must remove " + secret);
        }
        document.redactSensitiveComments();
        Assertions.isFalse(document.hasPlaintextSecret(), "in-place non-setting redaction must remove all leaks");
    }

    private void sanitizesAllSensitiveValues() {
        IbcConfigDocument document = IbcConfigDocument.parse(
                "ibpassword=lower-secret\n"
                        + "IbPassword=first-secret\nIbPassword=last-secret\n"
                        + "fixpassword=lower-fix-secret\nFIXPassword=fix-secret\n"
                        + "# IbPassword=comment-secret\n"
                        + "raw FIXPassword=raw-secret\n");
        document.sanitizeSensitiveValues();
        Assertions.isFalse(document.hasPlaintextSecret(), "sanitizer must remove every sensitive occurrence");
        String rendered = document.render();
        for (String secret : List.of("lower-secret", "first-secret", "last-secret", "lower-fix-secret",
                "fix-secret", "comment-secret", "raw-secret")) {
            Assertions.notContains(rendered, secret, "persistent rendering must remove " + secret);
        }
        Assertions.contains(rendered, "ibpassword=", "case-variant key formatting must be retained safely");
        Assertions.contains(rendered, "# IbPassword=[REDACTED]", "comment assignment must be redacted");
    }

    private void copyIndependent() {
        IbcConfigDocument original = IbcConfigDocument.parse("A=1\n");
        IbcConfigDocument copy = original.copy();
        copy.set("A", "2");
        Assertions.equals("A=1\n", original.render(), "copy mutation must not affect source");
        Assertions.equals("A=2\n", copy.render(), "copy must mutate independently");
    }

    private void invalidKey() {
        IbcConfigDocument document = IbcConfigDocument.empty();
        for (String key : List.of("", "1bad", "bad key", "bad=key", "bad\nkey")) {
            Assertions.throwsType(IllegalArgumentException.class, () -> document.set(key, "x"),
                    "invalid key must fail: " + key);
        }
    }

    private void unsafeValue() {
        IbcConfigDocument document = IbcConfigDocument.empty();
        for (String value : List.of("line one\nline two", "line one\rline two",
                "nul\0value", "unicode\u2028separator", "unicode\u2029separator")) {
            Assertions.throwsType(IllegalArgumentException.class, () -> document.set("A", value),
                    "config-breaking value must fail");
        }
        document.set("A", "tabs\tare allowed");
        Assertions.equals("tabs\tare allowed", document.get("A").orElseThrow(),
                "non-line-breaking text must remain supported");
    }

    private void schemaUnique() {
        long unique = IbcConfigSchema.definitions().stream().map(SettingDefinition::key).distinct().count();
        Assertions.equals((long) IbcConfigSchema.definitions().size(), unique, "schema keys must be unique");
        Assertions.isTrue(IbcConfigSchema.definitions().size() >= 45, "schema should cover the active IBC template");
    }

    private void schemaSensitive() {
        for (String key : IbcConfigSchema.sensitiveKeys()) {
            SettingDefinition definition = IbcConfigSchema.find(key).orElseThrow();
            Assertions.isTrue(definition.sensitive(), key + " must be marked sensitive");
            Assertions.equals(SettingType.PASSWORD, definition.type(), key + " must use PASSWORD type");
        }
    }

    private void templateParses() throws Exception {
        try (InputStream stream = ConfigDocumentTests.class.getResourceAsStream("/default-config.ini")) {
            Assertions.isTrue(stream != null, "default config resource must exist");
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            IbcConfigDocument document = IbcConfigDocument.parse(text);
            Assertions.isTrue(document.lineCount() > 500, "official template should not be truncated");
            for (String key : List.of("IbLoginId", "IbPassword", "TradingMode", "CommandServerPort", "BindAddress")) {
                Assertions.isTrue(document.get(key).isPresent(), "template must contain " + key);
            }
            Assertions.equals(java.util.Set.of(), document.duplicateKeys(), "official template must not contain active duplicates");
        }
    }

    private void profileControlled() {
        for (String key : List.of("IbLoginId", "IbPassword", "TradingMode", "MinimizeMainWindow",
                "OverrideTwsApiPort", "CommandServerPort", "BindAddress", "IbDir", "SecondFactorDevice")) {
            Assertions.isTrue(ManagedConfigService.isProfileControlled(key), key + " must be profile-controlled");
            Assertions.isTrue(ManagedConfigService.isProfileControlled(key.toLowerCase(java.util.Locale.ROOT)),
                    key + " case variant must remain controlled");
        }
        Assertions.isFalse(ManagedConfigService.isProfileControlled("AutoRestartTime"), "ordinary setting must not be controlled");
    }

    private void randomExact(long seed) {
        Random random = new Random(seed);
        String newline = switch (random.nextInt(3)) { case 0 -> "\n"; case 1 -> "\r\n"; default -> "\r"; };
        List<String> lines = new ArrayList<>();
        int count = random.nextInt(40);
        for (int index = 0; index < count; index++) {
            lines.add(switch (random.nextInt(5)) {
                case 0 -> "";
                case 1 -> "# comment " + random.nextInt();
                case 2 -> "; other " + random.nextLong();
                case 3 -> "raw text " + random.nextInt();
                default -> "Key" + index + (random.nextBoolean() ? " = " : "=") + randomValue(random);
            });
        }
        String text = String.join(newline, lines);
        if (random.nextBoolean() && !lines.isEmpty()) text += newline;
        Assertions.equals(text, IbcConfigDocument.parse(text).render(), "random parse/render must be exact for seed " + seed);
    }

    private static String randomValue(Random random) {
        String alphabet = "abcXYZ019 =[]()_-/\\äΩ";
        int length = random.nextInt(30);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < length; index++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return result.toString();
    }
}
