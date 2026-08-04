package io.github.ibcmanager.config;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
        tests.add(new NamedTest("parses the complete Java Properties separator and continuation grammar",
                this::javaPropertiesGrammar));
        tests.add(new NamedTest("canonical IBC bytes preserve Unicode and literal backslashes",
                this::canonicalIbcSemantics));
        tests.add(new NamedTest("external IBC bytes use strict ISO-8859-1 semantics",
                this::strictByteSemantics));
        tests.add(new NamedTest("legacy manager UTF-8 migration is isolated from external configs",
                this::legacyManagerMigration));
        tests.add(new NamedTest("secret detection covers colon whitespace and continuation syntax",
                this::secretGrammar));
        tests.add(new NamedTest("full-file Properties parsing is authoritative for malformed continuations",
                this::fullFileParserAuthoritative));
        tests.add(new NamedTest("full-file parser matches Properties.load across deterministic malformed input",
                this::fullFileParserDifferential));
        tests.add(new NamedTest("ambiguous Properties syntax requires explicit canonicalization",
                this::ambiguousSyntaxCanonicalization));
        tests.add(new NamedTest("single Windows backslashes are warned without exposing values",
                this::singleBackslashWarning));
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

    private void javaPropertiesGrammar() {
        String source = "# hash comment\n"
                + "! bang comment\n"
                + "ColonKey:colon value\n"
                + "SpaceKey whitespace value\n"
                + "Escaped\\ Key=escaped\\ value\\:\\=\\#\\!\n"
                + "Continued=first\\\n    second\\\n\\tthird\n"
                + "Unicode=\\u004A\\u00F6rg\n";
        IbcConfigDocument document = IbcConfigDocument.parse(source);
        Assertions.equals("colon value", document.get("ColonKey").orElseThrow(),
                "colon separator must match Properties.load");
        Assertions.equals("whitespace value", document.get("SpaceKey").orElseThrow(),
                "whitespace separator must match Properties.load");
        Assertions.equals("escaped value:=#!", document.get("Escaped Key").orElseThrow(),
                "escaped key and value syntax must be decoded");
        Assertions.equals("firstsecond\tthird", document.get("Continued").orElseThrow(),
                "continuation lines and escaped tabs must be decoded");
        Assertions.equals("Jörg", document.get("Unicode").orElseThrow(),
                "Unicode escapes must be decoded");
        Assertions.equals(source, document.render(), "unmodified full grammar input must remain lossless");
    }

    private void canonicalIbcSemantics() throws Exception {
        IbcConfigDocument document = IbcConfigDocument.empty();
        document.set("IbLoginId", "Jörg");
        document.set("SecondFactorDevice", "Téléphone 🔐");
        document.set("IbPassword", "päss\\test\\u0041");
        byte[] bytes = document.toIbcBytes();
        for (byte value : bytes) {
            Assertions.isTrue((value & 0x80) == 0,
                    "canonical IBC configuration must be pure ASCII with Unicode escapes");
        }
        Properties loaded = new Properties();
        loaded.load(new ByteArrayInputStream(bytes));
        Assertions.equals("Jörg", loaded.getProperty("IbLoginId"),
                "IBC must receive the intended non-ASCII username");
        Assertions.equals("Téléphone 🔐", loaded.getProperty("SecondFactorDevice"),
                "IBC must receive the intended second-factor device name");
        Assertions.equals("päss\\test\\u0041", loaded.getProperty("IbPassword"),
                "literal backslashes and Unicode-looking password text must remain literal");
        Assertions.equals(document.activeSettings(), Map.of(
                "IbLoginId", loaded.getProperty("IbLoginId"),
                "SecondFactorDevice", loaded.getProperty("SecondFactorDevice"),
                "IbPassword", loaded.getProperty("IbPassword")),
                "canonical bytes must preserve all active property semantics");
    }

    private void strictByteSemantics() {
        byte[] bytes = new byte[] {'A', '=', (byte) 0xC3, (byte) 0xA4, '\n'};
        IbcConfigDocument strict = IbcConfigDocument.parseBytes(bytes);
        Assertions.equals("Ã¤", strict.get("A").orElseThrow(),
                "external bytes must be interpreted exactly as IBC's ISO-8859-1 loader");
    }

    private void legacyManagerMigration() {
        byte[] bytes = "A=ä\n".getBytes(StandardCharsets.UTF_8);
        Assertions.equals("Ã¤", IbcConfigDocument.parseBytes(bytes).get("A").orElseThrow(),
                "strict external parsing must never guess UTF-8");
        Assertions.equals("ä", IbcConfigDocument.parseLegacyManagerBytes(bytes).get("A").orElseThrow(),
                "legacy manager-owned files may be migrated from the old UTF-8 format");
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'A', '=', '1', '\n'};
        Assertions.equals("1", IbcConfigDocument.parseLegacyManagerBytes(bom).get("A").orElseThrow(),
                "legacy manager UTF-8 BOM must be removed only by the migration path");
        Assertions.isTrue(IbcConfigDocument.parseBytes(bom).get("A").isEmpty(),
                "strict IBC parsing must not silently discard a BOM that IBC itself would read");
    }

    private void secretGrammar() {
        for (String source : List.of(
                "IbPassword:colon-secret\n",
                "IbPassword whitespace-secret\n",
                "FIXPassword=continued\\\n  secret\n",
                "! copied IbPassword:comment-secret\nA=1\n")) {
            IbcConfigDocument document = IbcConfigDocument.parse(source);
            Assertions.isTrue(document.hasPlaintextSecret(),
                    "all Java Properties secret syntaxes must be detected: " + source);
            String redacted = document.renderRedacted();
            Assertions.notContains(redacted, "secret", "diagnostic rendering must redact secret syntax");
        }
    }

    private void fullFileParserAuthoritative() throws Exception {
        String malformed = "\\\r\n";
        IbcConfigDocument document = IbcConfigDocument.parse(malformed);

        Properties expected = new Properties();
        expected.load(new java.io.StringReader(malformed));
        Assertions.equals(Map.of(), expected.stringPropertyNames().stream()
                        .collect(java.util.stream.Collectors.toMap(
                                key -> key, expected::getProperty)),
                "JDK fixture must demonstrate the final-continuation edge case");
        Assertions.equals(Map.of(), document.activeSettings(),
                "the full-file JDK parser must be authoritative over the formatting scanner");
        Assertions.equals(malformed, document.render(),
                "the formatting model must still preserve the user's exact source text");
        Assertions.isFalse(document.formattingMatchesIbcSemantics(),
                "scanner/JDK disagreement must be explicit rather than silently accepted");
    }

    private void fullFileParserDifferential() throws Exception {
        Random random = new Random(0x1BC3241L);
        byte[] alphabet = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                + "\\ =:#!\t\f\r\n._-/").getBytes(StandardCharsets.ISO_8859_1);
        for (int sample = 0; sample < 2_000; sample++) {
            byte[] bytes = new byte[random.nextInt(160)];
            for (int index = 0; index < bytes.length; index++) {
                bytes[index] = alphabet[random.nextInt(alphabet.length)];
            }

            Map<String, String> expected;
            boolean expectedFailure = false;
            try {
                Properties properties = new Properties();
                properties.load(new ByteArrayInputStream(bytes));
                expected = properties.stringPropertyNames().stream().sorted()
                        .collect(java.util.stream.Collectors.toMap(
                                key -> key, properties::getProperty, (left, right) -> right,
                                java.util.LinkedHashMap::new));
            } catch (IllegalArgumentException ex) {
                expected = Map.of();
                expectedFailure = true;
            }

            try {
                IbcConfigDocument document = IbcConfigDocument.parseBytes(bytes);
                if (expectedFailure) {
                    Assertions.fail("Manager accepted input rejected by Properties.load at sample " + sample);
                }
                Assertions.equals(expected, document.activeSettings(),
                        "authoritative semantics must match Properties.load at sample " + sample);
            } catch (IllegalArgumentException ex) {
                if (!expectedFailure) {
                    throw new AssertionError("Manager rejected input accepted by Properties.load at sample "
                            + sample, ex);
                }
            }
        }
    }

    private void ambiguousSyntaxCanonicalization() {
        IbcConfigDocument ambiguous = IbcConfigDocument.parse("\\\r\n");
        List<io.github.ibcmanager.model.ValidationIssue> external =
                new ConfigValueValidator().validate(ambiguous);
        Assertions.isTrue(external.stream().anyMatch(issue -> issue.field().equals("config.ini")
                        && issue.severity() == io.github.ibcmanager.model.Severity.WARNING),
                "an imported ambiguous file must receive a non-destructive warning");
        List<io.github.ibcmanager.model.ValidationIssue> managed =
                new ConfigValueValidator().validateManagedConfig(ambiguous);
        Assertions.isTrue(managed.stream().anyMatch(issue -> issue.field().equals("config.ini")
                        && issue.severity() == io.github.ibcmanager.model.Severity.ERROR),
                "manager-owned ambiguous syntax must be blocked until explicitly canonicalized");
        Assertions.throwsType(IllegalStateException.class, () -> ambiguous.set("A", "1"),
                "ambiguous raw formatting must not be mutated implicitly");
        Assertions.throwsType(IllegalStateException.class, ambiguous::toIbcBytes,
                "ambiguous raw formatting must not be written implicitly");

        IbcConfigDocument canonical = ambiguous.canonicalizedCopy();
        Assertions.isTrue(canonical.formattingMatchesIbcSemantics(),
                "explicit canonicalization must remove the scanner/JDK disagreement");
        Assertions.equals(Map.of(), canonical.activeSettings(),
                "canonicalization must preserve the authoritative IBC semantic map");
        Assertions.equals(0, canonical.toIbcBytes().length,
                "canonical output for an empty semantic map must contain no invented property");
    }

    private void singleBackslashWarning() {
        IbcConfigDocument unsafePath = IbcConfigDocument.parse("IbDir=C:\\Jts\n");
        Assertions.equals("C:Jts", unsafePath.get("IbDir").orElseThrow(),
                "the Manager must expose exactly what Properties.load gives IBC");
        Assertions.equals(java.util.Set.of("IbDir"), unsafePath.suspiciousBackslashKeys(),
                "a path containing an unescaped single backslash must be identified");
        List<io.github.ibcmanager.model.ValidationIssue> pathIssues =
                new ConfigValueValidator().validate(unsafePath);
        Assertions.isTrue(pathIssues.stream().anyMatch(issue -> issue.field().equals("IbDir")
                        && issue.severity() == io.github.ibcmanager.model.Severity.WARNING
                        && issue.message().contains("C:\\\\Jts")
                        && issue.message().contains("C:\\Jts")),
                "the warning must show the safe doubled-backslash form and the unsafe form");

        IbcConfigDocument safePath = IbcConfigDocument.parse("IbDir=C:\\\\Jts\n");
        Assertions.equals("C:\\Jts", safePath.get("IbDir").orElseThrow(),
                "a doubled backslash must remain literal for IBC");
        Assertions.equals(java.util.Set.of(), safePath.suspiciousBackslashKeys(),
                "a correctly escaped path must not produce a warning");

        IbcConfigDocument secret = IbcConfigDocument.parse("IbPassword=do-not-echo\\Jts\n");
        List<io.github.ibcmanager.model.ValidationIssue> secretIssues =
                new ConfigValueValidator().validate(secret);
        String messages = secretIssues.stream()
                .map(io.github.ibcmanager.model.ValidationIssue::message)
                .collect(java.util.stream.Collectors.joining("\n"));
        Assertions.notContains(messages, "do-not-echo",
                "backslash warnings for sensitive settings must never echo the value");
        Assertions.isTrue(secretIssues.stream().anyMatch(issue -> issue.field().equals("IbPassword")
                        && issue.message().contains("single Windows-style backslash")),
                "a suspicious password escape must warn without displaying password material");

        IbcConfigDocument canonical = IbcConfigDocument.empty();
        canonical.set("IbDir", "C:\\Jts");
        IbcConfigDocument reparsed = IbcConfigDocument.parseBytes(canonical.toIbcBytes());
        Assertions.equals(java.util.Set.of(), reparsed.suspiciousBackslashKeys(),
                "the canonical writer must not generate a false-positive backslash warning");
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
