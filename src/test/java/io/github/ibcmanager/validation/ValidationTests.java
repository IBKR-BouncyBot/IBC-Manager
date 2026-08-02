package io.github.ibcmanager.validation;

import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ValidationTests implements TestSuite {
    @Override public String name() { return "Profile and setting validation"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("accepts a structurally valid installed profile", this::validInstalled),
                new NamedTest("edit validation can precede credential storage", this::editCredentialValidation),
                new NamedTest("rejects blank and long names", this::names),
                new NamedTest("rejects invalid version syntax", this::versions),
                new NamedTest("rejects config-breaking profile text", this::unsafeText),
                new NamedTest("requires all launch directories", this::requiredPaths),
                new NamedTest("rejects unsafe batch path characters", this::unsafePathCharacters),
                new NamedTest("detects missing IBC files", this::missingIbcFiles),
                new NamedTest("detects missing offline jars", this::missingJars),
                new NamedTest("validates API and command ports", this::ports),
                new NamedTest("warns about non-loopback command binding", this::remoteBinding),
                new NamedTest("rejects invalid command binding", this::invalidBinding),
                new NamedTest("validates graceful stop timeout", this::stopTimeout),
                new NamedTest("validates encrypted credentials", this::encryptedCredentials),
                new NamedTest("validates existing config mode", this::existingConfig),
                new NamedTest("warns that reserved advanced settings are ignored", this::reservedSettings),
                new NamedTest("warns about additional command sources", this::controlFrom),
                new NamedTest("detects duplicate profile resources", this::profileSetConflicts),
                new NamedTest("ignores disabled profiles in conflict checks", this::disabledProfilesIgnored),
                new NamedTest("warns about duplicate live usernames", this::duplicateLiveUser),
                new NamedTest("accepts schema defaults", this::schemaDefaults),
                new NamedTest("validates boolean values", this::booleanValues),
                new NamedTest("validates enum values", this::enumValues),
                new NamedTest("validates integer and port values", this::numbers),
                new NamedTest("validates 12-hour and 24-hour times", this::times),
                new NamedTest("validates IP lists", this::ipLists),
                new NamedTest("detects config duplicates and plaintext secrets", this::documentWarnings));
    }

    private void validInstalled() throws Exception {
        Path root = TestSupport.tempDirectory("validation-valid");
        try {
            Profile profile = TestSupport.validProfile(root);
            ValidationResult result = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(result.isValid(), "valid profile must have no errors: " + result.issues());
            Assertions.equals(0L, result.errorCount(), "valid profile error count");
        } finally { TestSupport.deleteTree(root); }
    }

    private void editCredentialValidation() throws Exception {
        Path root = TestSupport.tempDirectory("validation-edit");
        try {
            FakeStore store = new FakeStore(true);
            Profile profile = TestSupport.validProfile(root).toBuilder().credentialMode(CredentialMode.ENCRYPTED).build();
            ProfileValidator validator = new ProfileValidator(store);
            Assertions.isTrue(validator.validateForEdit(profile, true).isValid(), "edit validation must not require credential to exist yet");
            Assertions.isFalse(validator.validate(profile, true).isValid(), "runtime validation must require stored credential");
        } finally { TestSupport.deleteTree(root); }
    }

    private void names() throws Exception {
        Path root = TestSupport.tempDirectory("validation-name");
        try {
            Profile base = TestSupport.validProfile(root);
            assertError(base.toBuilder().name(" ").build(), "name");
            assertError(base.toBuilder().name("x".repeat(81)).build(), "name");
        } finally { TestSupport.deleteTree(root); }
    }

    private void versions() throws Exception {
        Path root = TestSupport.tempDirectory("validation-version");
        try {
            Profile base = TestSupport.validProfile(root);
            for (String version : List.of("", "10.45", "abc", "12", "123456")) {
                assertError(base.toBuilder().twsMajorVersion(version).build(), "twsMajorVersion");
            }
            for (String version : List.of("123", "1045", "12345")) {
                ValidationResult result = new ProfileValidator(new FakeStore(true)).validateForEdit(
                        base.toBuilder().twsMajorVersion(version).build(), false);
                Assertions.isFalse(hasError(result, "twsMajorVersion"), "numeric version should pass syntax: " + version);
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void unsafeText() throws Exception {
        Path root = TestSupport.tempDirectory("validation-text");
        try {
            Profile base = TestSupport.validProfile(root);
            assertError(base.toBuilder().name("Line one\nLine two").build(), "name");
            assertError(base.toBuilder().username("user\u2028injected").build(), "username");
            assertError(base.toBuilder().bindAddress("127.0.0.1\rspoof").build(), "bindAddress");
            assertError(base.toBuilder().setting("ControlFrom", "127.0.0.1\nmalicious").build(),
                    "ControlFrom");
            ValidationResult invalidKey = new ProfileValidator(new FakeStore(true)).validateForEdit(
                    base.toBuilder().setting("bad key", "value").build(), false);
            Assertions.isTrue(hasError(invalidKey, "settings"),
                    "malformed advanced setting key must be rejected");
        } finally { TestSupport.deleteTree(root); }
    }

    private void requiredPaths() throws Exception {
        Path root = TestSupport.tempDirectory("validation-paths");
        try {
            Profile base = TestSupport.validProfile(root);
            assertError(base.toBuilder().ibcPath(Path.of("")).build(), "ibcPath");
            assertError(base.toBuilder().twsPath(Path.of("")).build(), "twsPath");
            assertError(base.toBuilder().twsSettingsPath(Path.of("")).build(), "twsSettingsPath");
        } finally { TestSupport.deleteTree(root); }
    }

    private void unsafePathCharacters() throws Exception {
        Path root = TestSupport.tempDirectory("validation-unsafe");
        try {
            Profile base = TestSupport.validProfile(root);
            for (String value : List.of("C:/bad%path", "C:/bad!path", "C:/bad\"path", "C:/bad\npath")) {
                try {
                    ValidationResult result = new ProfileValidator(new FakeStore(true)).validateForEdit(
                            base.toBuilder().ibcPath(Path.of(value)).build(), false);
                    Assertions.isTrue(hasError(result, "ibcPath"), "unsafe path must fail: " + value);
                } catch (InvalidPathException ex) {
                    Assertions.isTrue(value.indexOf('\"') >= 0 || TextSafety.containsConfigBreakingControl(value),
                            "the host file-system rejected an unexpected path value: " + value);
                }
            }
        } finally { TestSupport.deleteTree(root); }
    }

    private void missingIbcFiles() throws Exception {
        Path root = TestSupport.tempDirectory("validation-ibc");
        try {
            Profile profile = TestSupport.validProfile(root);
            Files.delete(profile.ibcPath().resolve("IBC.jar"));
            ValidationResult missingJar = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(hasError(missingJar, "ibcPath"), "missing IBC.jar must fail");
            Files.writeString(profile.ibcPath().resolve("IBC.jar"), "x");
            Files.delete(profile.ibcPath().resolve("scripts").resolve("StartIBC.bat"));
            ValidationResult missingScript = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(hasError(missingScript, "ibcPath"), "missing launcher must fail");
        } finally { TestSupport.deleteTree(root); }
    }

    private void missingJars() throws Exception {
        Path root = TestSupport.tempDirectory("validation-jars");
        try {
            Profile profile = TestSupport.validProfile(root);
            TestSupport.deleteTree(profile.twsPath().resolve("ibgateway").resolve("1045").resolve("jars"));
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validate(profile, true), "twsPath"),
                    "missing offline jars must fail");
        } finally { TestSupport.deleteTree(root); }
    }

    private void ports() throws Exception {
        Path root = TestSupport.tempDirectory("validation-ports");
        try {
            Profile base = TestSupport.validProfile(root);
            for (int port : List.of(0, -1, 65536)) assertError(base.toBuilder().apiPort(port).build(), "apiPort");
            for (int port : List.of(0, -1, 65536)) assertError(base.toBuilder().commandServerPort(port).build(), "commandServerPort");
            assertError(base.toBuilder().commandServerPort(base.apiPort()).build(), "commandServerPort");
        } finally { TestSupport.deleteTree(root); }
    }

    private void remoteBinding() throws Exception {
        Path root = TestSupport.tempDirectory("validation-bind");
        try {
            Profile base = TestSupport.validProfile(root);
            ValidationResult blank = new ProfileValidator(new FakeStore(true)).validateForEdit(
                    base.toBuilder().bindAddress("").build(), false);
            Assertions.isTrue(hasWarning(blank, "bindAddress"), "blank binding must warn");
            ValidationResult all = new ProfileValidator(new FakeStore(true)).validateForEdit(
                    base.toBuilder().bindAddress("0.0.0.0").build(), false);
            Assertions.isTrue(hasWarning(all, "bindAddress"), "all-interface binding must warn");
            ValidationResult loopback = new ProfileValidator(new FakeStore(true)).validateForEdit(base, false);
            Assertions.isFalse(hasWarning(loopback, "bindAddress"), "loopback binding must not warn");
        } finally { TestSupport.deleteTree(root); }
    }

    private void invalidBinding() throws Exception {
        Path root = TestSupport.tempDirectory("validation-badbind");
        try {
            Profile profile = TestSupport.validProfile(root).toBuilder().bindAddress("999.999.999.999").build();
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validateForEdit(profile, false), "bindAddress"),
                    "invalid host must fail");
        } finally { TestSupport.deleteTree(root); }
    }

    private void stopTimeout() throws Exception {
        Path root = TestSupport.tempDirectory("validation-timeout");
        try {
            Profile base = TestSupport.validProfile(root);
            assertError(base.toBuilder().gracefulStopTimeoutSeconds(2).build(), "gracefulStopTimeoutSeconds");
            assertError(base.toBuilder().gracefulStopTimeoutSeconds(301).build(), "gracefulStopTimeoutSeconds");
            Assertions.isFalse(hasError(new ProfileValidator(new FakeStore(true)).validateForEdit(
                    base.toBuilder().gracefulStopTimeoutSeconds(3).build(), false), "gracefulStopTimeoutSeconds"),
                    "lower bound must pass");
        } finally { TestSupport.deleteTree(root); }
    }

    private void encryptedCredentials() throws Exception {
        Path root = TestSupport.tempDirectory("validation-creds");
        try {
            Profile base = TestSupport.validProfile(root).toBuilder().credentialMode(CredentialMode.ENCRYPTED).build();
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(false)).validate(base, false), "credentialMode"),
                    "unavailable store must fail");
            FakeStore store = new FakeStore(true);
            Assertions.isTrue(hasError(new ProfileValidator(store).validate(base, false), "credentialMode"),
                    "missing secret must fail");
            store.save(base.id(), "secret".toCharArray());
            Assertions.isFalse(hasError(new ProfileValidator(store).validate(base, false), "credentialMode"),
                    "stored secret must pass");
            Assertions.isTrue(hasError(new ProfileValidator(store).validate(
                    base.toBuilder().username("").build(), false), "username"), "username required");
        } finally { TestSupport.deleteTree(root); }
    }

    private void existingConfig() throws Exception {
        Path root = TestSupport.tempDirectory("validation-existing");
        try {
            Profile base = TestSupport.validProfile(root).toBuilder()
                    .credentialMode(CredentialMode.EXISTING_CONFIG).baseConfigPath(Path.of("")).build();
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validate(base, false), "baseConfigPath"),
                    "missing external config must fail");
            Path config = root.resolve("external.ini");
            Files.writeString(config, "IbLoginId=x\n");
            Assertions.isFalse(hasError(new ProfileValidator(new FakeStore(true)).validate(
                    base.toBuilder().baseConfigPath(config).build(), false), "baseConfigPath"),
                    "existing external config must pass");
        } finally { TestSupport.deleteTree(root); }
    }

    private void reservedSettings() throws Exception {
        Path root = TestSupport.tempDirectory("validation-reserved");
        try {
            Profile profile = TestSupport.validProfile(root).toBuilder().setting("CommandServerPort", "9999").build();
            Assertions.isTrue(hasWarning(new ProfileValidator(new FakeStore(true)).validateForEdit(profile, false), "settings"),
                    "reserved advanced key must warn");
        } finally { TestSupport.deleteTree(root); }
    }

    private void controlFrom() throws Exception {
        Path root = TestSupport.tempDirectory("validation-control");
        try {
            Profile profile = TestSupport.validProfile(root).toBuilder().setting("ControlFrom", "192.168.1.10").build();
            Assertions.isTrue(hasWarning(new ProfileValidator(new FakeStore(true)).validateForEdit(profile, false), "ControlFrom"),
                    "remote command source must warn");
        } finally { TestSupport.deleteTree(root); }
    }

    private void profileSetConflicts() throws Exception {
        Path root = TestSupport.tempDirectory("validation-set");
        try {
            Profile first = TestSupport.validProfile(root);
            Profile second = first.toBuilder().id(UUID.randomUUID()).name(first.name()).build();
            ValidationResult names = new ProfileSetValidator().validate(List.of(first, second));
            Assertions.isTrue(hasError(names, "name"), "duplicate name must fail");
            second = second.toBuilder().name("Other").build();
            ValidationResult shared = new ProfileSetValidator().validate(List.of(first, second));
            Assertions.isTrue(hasError(shared, "apiPort"), "duplicate API port must fail");
            Assertions.isTrue(hasError(shared, "commandServerPort"), "duplicate command port must fail");
            Assertions.isTrue(hasError(shared, "twsSettingsPath"), "duplicate settings path must fail");
        } finally { TestSupport.deleteTree(root); }
    }

    private void disabledProfilesIgnored() throws Exception {
        Path root = TestSupport.tempDirectory("validation-disabled");
        try {
            Profile first = TestSupport.validProfile(root);
            Profile second = first.toBuilder().id(UUID.randomUUID()).enabled(false).build();
            Assertions.isTrue(new ProfileSetValidator().validate(List.of(first, second)).isValid(),
                    "disabled profile must not reserve resources");
        } finally { TestSupport.deleteTree(root); }
    }

    private void duplicateLiveUser() throws Exception {
        Path root = TestSupport.tempDirectory("validation-liveuser");
        try {
            Profile first = TestSupport.validProfile(root).toBuilder().tradingMode(TradingMode.LIVE).build();
            Profile second = first.toBuilder().id(UUID.randomUUID()).name("Other")
                    .apiPort(4003).commandServerPort(7463)
                    .twsSettingsPath(root.resolve("other-settings")).build();
            ValidationResult result = new ProfileSetValidator().validate(List.of(first, second));
            Assertions.isTrue(hasWarning(result, "username"), "same live username must warn");
            Assertions.isTrue(result.isValid(), "same live username is a warning, not blocking");
        } finally { TestSupport.deleteTree(root); }
    }

    private void schemaDefaults() {
        ConfigValueValidator validator = new ConfigValueValidator();
        for (var definition : IbcConfigSchema.definitions()) {
            Map<String, String> value = Map.of(definition.key(), definition.defaultValue());
            List<io.github.ibcmanager.model.ValidationIssue> issues = validator.validate(value);
            Assertions.isFalse(issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR),
                    "schema default must be valid for " + definition.key() + ": " + issues);
        }
    }

    private void booleanValues() {
        ConfigValueValidator validator = new ConfigValueValidator();
        for (String value : List.of("yes", "no", "true", "false", "YES")) {
            Assertions.equals(List.of(), validator.validate(Map.of("AllowBlindTrading", value)), "valid boolean: " + value);
        }
        Assertions.isTrue(hasError(validator.validate(Map.of("AllowBlindTrading", "maybe")), "AllowBlindTrading"),
                "invalid boolean must fail");
    }

    private void enumValues() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("TradingMode", "paper")), "paper mode valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("TradingMode", "test")), "TradingMode"),
                "invalid enum must fail");
    }

    private void numbers() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("CommandServerPort", "0")), "IBC allows command port zero");
        Assertions.equals(List.of(), validator.validate(Map.of("CommandServerPort", "65535")), "max port valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("CommandServerPort", "65536")), "CommandServerPort"),
                "too-high port fails");
        Assertions.isTrue(hasError(validator.validate(Map.of("CommandServerPort", "x")), "CommandServerPort"),
                "noninteger port fails");
        Assertions.equals(List.of(), validator.validate(Map.of("LoginDialogDisplayTimeout", "60")), "integer valid");
    }

    private void times() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("AutoRestartTime", "11:45 PM")), "12-hour time valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("AutoRestartTime", "23:45")), "AutoRestartTime"),
                "wrong 12-hour format fails");
        Assertions.equals(List.of(), validator.validate(Map.of("ColdRestartTime", "23:45")), "24-hour time valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("ColdRestartTime", "25:00")), "ColdRestartTime"),
                "invalid hour fails");
    }

    private void ipLists() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("ControlFrom", "127.0.0.1, localhost")), "resolvable hosts valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("ControlFrom", "127.0.0.1,,localhost")), "ControlFrom"),
                "empty item fails");
        Assertions.isTrue(hasWarning(validator.validate(Map.of("ControlFrom", "999.999.999.999")), "ControlFrom"),
                "unresolvable host warns");
    }

    private void documentWarnings() {
        ConfigValueValidator validator = new ConfigValueValidator();
        IbcConfigDocument document = IbcConfigDocument.parse(
                "TradingMode=paper\nTradingMode=live\nIbPassword=secret\nFuture=bad\u2028value\n");
        List<io.github.ibcmanager.model.ValidationIssue> issues = validator.validate(document);
        Assertions.isTrue(hasError(issues, "TradingMode"), "duplicate active key must fail");
        Assertions.isTrue(hasWarning(issues, "IbPassword"), "plaintext password must warn");
        Assertions.isTrue(hasError(issues, "Future"),
                "parsed future setting must not bypass single-line value validation");

        Map<String, String> malformed = new HashMap<>();
        malformed.put(null, "value");
        Assertions.isTrue(hasError(validator.validate(malformed), "settings"),
                "public map validator must reject a null setting key without throwing");

        IbcConfigDocument managed = IbcConfigDocument.parse(
                "IbPassword=earlier-secret\nIbPassword=\n# FIXPassword=comment-secret\n");
        List<io.github.ibcmanager.model.ValidationIssue> managedIssues =
                validator.validateManagedConfig(managed);
        Assertions.isTrue(hasError(managedIssues, "IbPassword"),
                "plaintext data in a manager-owned config must be a blocking error");
        Assertions.isFalse(hasWarning(managedIssues, "IbPassword"),
                "a managed-config secret must not be presented as an overridable warning");
    }

    private static void assertError(Profile profile, String field) {
        ValidationResult result = new ProfileValidator(new FakeStore(true)).validateForEdit(profile, false);
        Assertions.isTrue(hasError(result, field), "expected error for " + field + ": " + result.issues());
    }

    private static boolean hasError(ValidationResult result, String field) {
        return hasError(result.issues(), field);
    }

    private static boolean hasWarning(ValidationResult result, String field) {
        return hasWarning(result.issues(), field);
    }

    private static boolean hasError(List<io.github.ibcmanager.model.ValidationIssue> issues, String field) {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.ERROR && issue.field().equals(field));
    }

    private static boolean hasWarning(List<io.github.ibcmanager.model.ValidationIssue> issues, String field) {
        return issues.stream().anyMatch(issue -> issue.severity() == Severity.WARNING && issue.field().equals(field));
    }

    private static final class FakeStore implements CredentialStore {
        private final boolean available;
        private final Map<UUID, char[]> values = new HashMap<>();
        private FakeStore(boolean available) { this.available = available; }
        @Override public boolean isAvailable() { return available; }
        @Override public void save(UUID id, char[] password) throws CredentialStoreException {
            if (!available) throw new CredentialStoreException("unavailable");
            values.put(id, password.clone());
        }
        @Override public SecureChars load(UUID id) throws CredentialStoreException {
            if (!values.containsKey(id)) throw new CredentialStoreException("missing");
            return new SecureChars(values.get(id));
        }
        @Override public boolean exists(UUID id) { return values.containsKey(id); }
        @Override public void delete(UUID id) { values.remove(id); }
    }
}
