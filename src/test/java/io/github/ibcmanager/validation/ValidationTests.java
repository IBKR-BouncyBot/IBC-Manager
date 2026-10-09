package io.github.ibcmanager.validation;

import io.github.ibcmanager.config.ConfigValueValidator;
import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.IbcConfigSchema;
import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.Severity;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.model.TwoFactorTimeoutAction;
import io.github.ibcmanager.model.ValidationIssue;
import io.github.ibcmanager.security.CredentialStore;
import io.github.ibcmanager.security.CredentialStoreException;
import io.github.ibcmanager.security.SecureChars;
import io.github.ibcmanager.security.TextSafety;
import io.github.ibcmanager.security.WindowsCommandSafety;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class ValidationTests implements TestSuite {
    @Override public String name() { return "Profile and setting validation"; }

    @Override
    public List<NamedTest> tests() {
        return List.of(
                new NamedTest("accepts a structurally valid installed profile", this::validInstalled),
                new NamedTest("Gateway 10.50 delegates bundled-Java selection to StartIBC",
                        this::gateway1050BundledJava),
                new NamedTest("edit validation can precede credential storage", this::editCredentialValidation),
                new NamedTest("rejects blank and long names", this::names),
                new NamedTest("rejects invalid version syntax", this::versions),
                new NamedTest("rejects config-breaking profile text", this::unsafeText),
                new NamedTest("requires all launch directories", this::requiredPaths),
                new NamedTest("rejects unsafe batch path characters", this::unsafePathCharacters),
                new NamedTest("external IBC file absence cannot affect integrated validation", this::missingIbcFiles),
                new NamedTest("detects missing offline jars", this::missingJars),
                new NamedTest("validates API and command ports", this::ports),
                new NamedTest("warns about non-loopback command binding", this::remoteBinding),
                new NamedTest("rejects invalid command binding", this::invalidBinding),
                new NamedTest("validates graceful stop timeout", this::stopTimeout),
                new NamedTest("independent 2FA policy is not coupled to obsolete wrapper action",
                        this::secondFactorTimeoutPolicy),
                new NamedTest("warns when automatic recovery cannot complete a manual login",
                        this::manualRecoveryWarning),
                new NamedTest("warns when repeated 2FA login still uses a manual password",
                        this::manualSecondFactorRetryWarning),
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
                new NamedTest("validates timeout and client-ID ranges", this::integerRanges),
                new NamedTest("validates 12-hour and 24-hour times", this::times),
                new NamedTest("validates documented AM/PM independently of the host locale",
                        this::timeLocaleIndependence),
                new NamedTest("validates IBC closedown and settings-save schedules", this::schedules),
                new NamedTest("validates order-ID reset policy", this::orderIdResetPolicy),
                new NamedTest("accepts all IBC window-structure logging aliases", this::logStructureAliases),
                new NamedTest("blocks unsupported FIX CTCI mode", this::unsupportedFixMode),
                new NamedTest("warns that FIX-only trusted API addresses are ignored",
                        this::fixOnlyTrustedApiAddresses),
                new NamedTest("validates TWS-only settings against the selected application",
                        this::applicationSpecificSettings),
                new NamedTest("warns when auto-logoff and auto-restart are both set", this::autoScheduleConflict),
                new NamedTest("validates IP lists", this::ipLists),
                new NamedTest("detects config duplicates and plaintext secrets", this::documentWarnings),
                new NamedTest("known IBC setting names require canonical letter case", this::canonicalSettingCase));
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

    private void gateway1050BundledJava() throws Exception {
        Path root = TestSupport.tempDirectory("validation-gateway-1050");
        try {
            Profile base = TestSupport.validProfile(root);
            TestSupport.createOfflineGatewayInstallation(base.twsPath(), "1050");
            Path install4j = base.twsPath().resolve("ibgateway/1050/.install4j");
            Files.deleteIfExists(install4j.resolve("pref_jre.cfg"));
            Files.deleteIfExists(install4j.resolve("inst_jre.cfg"));
            Profile profile = base.toBuilder()
                    .twsMajorVersion("1050")
                    .ibcJavaPath(Path.of(""))
                    .build();
            ValidationResult result = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(result.isValid(),
                    "a blank Java override must allow official StartIBC to select Gateway 10.50's bundled Java: "
                            + result.issues());
            Assertions.isFalse(result.issues().stream()
                            .anyMatch(issue -> "ibcJavaPath".equals(issue.field())),
                    "blank automatic Java selection must not produce an ibcJavaPath error");
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
            Assertions.isFalse(hasError(new ProfileValidator(new FakeStore(true)).validateForEdit(base.toBuilder().ibcPath(Path.of("")).build(), false), "ibcPath"), "external IBC directory is no longer required");
            assertError(base.toBuilder().twsPath(Path.of("")).build(), "twsPath");
            assertError(base.toBuilder().twsSettingsPath(Path.of("")).build(), "twsSettingsPath");
        } finally { TestSupport.deleteTree(root); }
    }

    private void unsafePathCharacters() throws Exception {
        Path root = TestSupport.tempDirectory("validation-unsafe");
        try {
            Profile base = TestSupport.validProfile(root);
            for (String value : List.of("C:/bad%path", "C:/bad!path", "C:/bad\"path",
                    "C:/bad&path", "C:/bad|path", "C:/bad<path", "C:/bad>path",
                    "C:/bad^path", "C:/bad(path", "C:/bad)path", "C:/bad\npath")) {
                Assertions.isTrue(WindowsCommandSafety.containsUnsafeExternalArgumentCharacter(value),
                        "shared command-safety policy must reject the test value: " + value);
                try {
                    ValidationResult result = new ProfileValidator(new FakeStore(true)).validateForEdit(
                            base.toBuilder().twsPath(Path.of(value)).build(), false);
                    Assertions.isTrue(hasError(result, "twsPath"), "unsafe path must fail: " + value);
                } catch (InvalidPathException ex) {
                    // Windows rejects several CMD metacharacters before ProfileValidator can receive
                    // a Path. The shared policy above proves that these values remain intentionally
                    // covered even when the host file-system parser rejects them first.
                }
            }

            String programFiles = "C:/Program Files (x86)/Java";
            ValidationResult programFilesResult = new ProfileValidator(new FakeStore(true)).validateForEdit(
                    base.toBuilder().twsPath(Path.of(programFiles)).build(), false);
            ValidationIssue pathIssue = programFilesResult.issues().stream()
                    .filter(issue -> issue.field().equals("twsPath")
                            && issue.severity() == Severity.ERROR)
                    .findFirst().orElseThrow();
            Assertions.contains(pathIssue.message(), "unsupported character '('",
                    "the exact offending character must be named");
            Assertions.contains(pathIssue.message(), "C:\\IBC",
                    "the error must offer a simple safe location");
            Assertions.contains(pathIssue.message(), "Program Files (x86)",
                    "the common operational restriction must be documented in the error");
            Assertions.contains(WindowsCommandSafety.externalPathGuidance(),
                    "\"  %  !  &  |  <  >  ^  (  )",
                    "the shared guidance must enumerate every rejected CMD character");
        } finally { TestSupport.deleteTree(root); }
    }

    private void canonicalSettingCase() {
        ConfigValueValidator validator = new ConfigValueValidator();
        List<io.github.ibcmanager.model.ValidationIssue> managed = validator.validateManagedConfig(
                IbcConfigDocument.parse("allowblindtrading=yes\n"));
        Assertions.isTrue(managed.stream().anyMatch(issue -> issue.severity() == Severity.ERROR
                        && issue.field().equals("allowblindtrading")
                        && issue.message().contains("AllowBlindTrading")),
                "manager-owned config must reject a mistyped known setting name");

        List<io.github.ibcmanager.model.ValidationIssue> external = validator.validate(
                IbcConfigDocument.parse("allowblindtrading=yes\n"));
        Assertions.isTrue(external.stream().anyMatch(issue -> issue.severity() == Severity.WARNING
                        && issue.field().equals("allowblindtrading")),
                "external config inspection must warn about a mistyped known setting name");

        Map<String, String> wrongCase = new java.util.LinkedHashMap<>();
        wrongCase.put("allowblindtrading", "yes");
        Assertions.isTrue(validator.validate(wrongCase).stream().anyMatch(issue -> issue.severity() == Severity.ERROR),
                "profile settings must require the canonical known-key spelling");

        Map<String, String> duplicateCase = new java.util.LinkedHashMap<>();
        duplicateCase.put("AllowBlindTrading", "yes");
        duplicateCase.put("allowblindtrading", "no");
        Assertions.isTrue(validator.validate(duplicateCase).stream().anyMatch(issue ->
                        issue.message().contains("more than once")),
                "profile settings must reject case-variant duplicate known keys");
    }

    private void missingIbcFiles() throws Exception {
        Path root = TestSupport.tempDirectory("validation-ibc");
        try {
            Profile profile = TestSupport.validProfile(root);
            Files.delete(profile.ibcPath().resolve("IBC.jar"));
            ValidationResult missingJar = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(missingJar.isValid(), "external IBC.jar is not used");
            TestSupport.writeIbcJar(profile.ibcPath().resolve("IBC.jar"));
            Files.delete(profile.ibcPath().resolve("scripts").resolve("StartIBC.bat"));
            ValidationResult missingScript = new ProfileValidator(new FakeStore(true)).validate(profile, true);
            Assertions.isTrue(missingScript.isValid(), "external launcher is not used");
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
                    base.toBuilder().gracefulStopTimeoutSeconds(30).build(), false), "gracefulStopTimeoutSeconds"),
                    "lower bound must pass");
        } finally { TestSupport.deleteTree(root); }
    }

    private void secondFactorTimeoutPolicy() throws Exception {
        Path root = TestSupport.tempDirectory("validation-2fa-policy");
        try {
            Profile base = TestSupport.validProfile(root);
            Profile impossible = base.toBuilder()
                    .twoFactorTimeoutAction(TwoFactorTimeoutAction.RESTART)
                    .reloginAfterSecondFactorTimeout(false)
                    .build();
            ValidationResult rejected = new ProfileValidator(new FakeStore(true))
                    .validateForEdit(impossible, false);
            Assertions.isFalse(hasError(rejected, "twoFactorTimeoutAction"),
                    "disabling independent retry must not require changing obsolete wrapper metadata");

            Profile coherent = impossible.toBuilder().reloginAfterSecondFactorTimeout(true).build();
            ValidationResult accepted = new ProfileValidator(new FakeStore(true))
                    .validateForEdit(coherent, false);
            Assertions.isFalse(hasError(accepted, "twoFactorTimeoutAction"),
                    "coherent internal and wrapper restart policy must pass validation");
        } finally { TestSupport.deleteTree(root); }
    }

    private void manualRecoveryWarning() throws Exception {
        Path root = TestSupport.tempDirectory("validation-manual-recovery");
        try {
            Profile enabled = TestSupport.validProfile(root).toBuilder()
                    .credentialMode(CredentialMode.MANUAL)
                    .autoRecoverStartupStall(true)
                    .build();
            ValidationResult warning = new ProfileValidator(new FakeStore(true)).validateForEdit(enabled, false);
            Assertions.isTrue(hasWarning(warning, "autoRecoverStartupStall"),
                    "manual credential mode must explain that fresh unattended login cannot complete");
            Profile disabled = enabled.toBuilder().autoRecoverStartupStall(false).build();
            ValidationResult quiet = new ProfileValidator(new FakeStore(true)).validateForEdit(disabled, false);
            Assertions.isFalse(hasWarning(quiet, "autoRecoverStartupStall"),
                    "disabling automatic recovery must remove the manual-login warning");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void manualSecondFactorRetryWarning() throws Exception {
        Path root = TestSupport.tempDirectory("validation-manual-2fa-retry");
        try {
            Profile enabled = TestSupport.validProfile(root).toBuilder()
                    .credentialMode(CredentialMode.MANUAL)
                    .reloginAfterSecondFactorTimeout(true)
                    .build();
            ValidationResult warning = new ProfileValidator(new FakeStore(true)).validateForEdit(enabled, false);
            Assertions.isTrue(hasWarning(warning, "reloginAfterSecondFactorTimeout"),
                    "manual password mode must explain that a warm 2FA retry may need the password again");
            Profile disabled = enabled.toBuilder()
                    .reloginAfterSecondFactorTimeout(false)
                    .twoFactorTimeoutAction(TwoFactorTimeoutAction.EXIT)
                    .build();
            ValidationResult quiet = new ProfileValidator(new FakeStore(true)).validateForEdit(disabled, false);
            Assertions.isFalse(hasWarning(quiet, "reloginAfterSecondFactorTimeout"),
                    "disabling repeated 2FA login must remove the manual-password warning");
        } finally {
            TestSupport.deleteTree(root);
        }
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
            IbcConfigDocument passwordDocument = IbcConfigDocument.parse("IbPassword=\n");
            passwordDocument.set("IbPassword", " secret ");
            Assertions.isTrue(hasError(new ConfigValueValidator().validateRuntimeConfig(passwordDocument),
                    "IbPassword"), "runtime passwords must not be silently changed by IBC trimming");
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

            Files.writeString(config, "IbLoginId=x\nFIX=yes\n");
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validate(
                    base.toBuilder().baseConfigPath(config).build(), false), "FIX"),
                    "external configurations must not bypass the unsupported FIX-mode block");

            Files.writeString(config, "IbLoginId=x\nSaveTwsSettingsAt=Every\n");
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validate(
                    base.toBuilder().baseConfigPath(config).build(), false), "SaveTwsSettingsAt"),
                    "malformed external schedules that can fail inside IBC must be blocked");

            Files.writeString(config, "IbLoginId=x\nReadOnlyLogin=yes\n");
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true)).validate(
                    base.toBuilder().baseConfigPath(config).build(), false), "ReadOnlyLogin"),
                    "external configurations must not enable unsupported read-only Gateway login");
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
        Assertions.equals(List.of(), validator.validate(Map.of("TradingMode", "PAPER")),
                "IBC accepts trading mode case-insensitively");
        Assertions.isTrue(hasError(validator.validate(Map.of("TradingMode", "test")), "TradingMode"),
                "invalid enum must fail");
        Assertions.isTrue(hasError(validator.validate(Map.of(
                "AcceptBidAskLastSizeDisplayUpdateNotification", "ACCEPT")),
                "AcceptBidAskLastSizeDisplayUpdateNotification"),
                "IBC enum values with exact lowercase handlers must reject uppercase aliases");
        Assertions.isTrue(hasError(validator.validate(Map.of("ConfirmCryptoCurrencyOrders", "TRANSMIT")),
                "ConfirmCryptoCurrencyOrders"),
                "cryptocurrency confirmation values must use IBC's exact canonical case");
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
        Assertions.isTrue(hasError(validator.validate(Map.of("LoginDialogDisplayTimeout", " 60")),
                "LoginDialogDisplayTimeout"),
                "IBC integer parsing does not trim surrounding whitespace");
        Assertions.isTrue(hasError(validator.validate(Map.of("AllowBlindTrading", "yes ")),
                "AllowBlindTrading"),
                "IBC boolean parsing does not trim surrounding whitespace");
    }

    private void integerRanges() {
        ConfigValueValidator validator = new ConfigValueValidator();
        for (String key : List.of("LoginDialogDisplayTimeout", "SecondFactorAuthenticationTimeout",
                "SecondFactorAuthenticationExitInterval")) {
            Assertions.isFalse(hasError(validator.validate(Map.of(key, "1")), key), key + " minimum valid");
            Assertions.isTrue(hasError(validator.validate(Map.of(key, "0")), key), key + " zero must fail");
            Assertions.isTrue(hasError(validator.validate(Map.of(key, "86401")), key), key + " excessive value must fail");
        }
        Assertions.isFalse(hasError(validator.validate(Map.of("OverrideTwsMasterClientID", "0")),
                "OverrideTwsMasterClientID"), "master client ID zero valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("OverrideTwsMasterClientID", "-1")),
                "OverrideTwsMasterClientID"), "negative master client ID must fail");
    }

    private void times() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("AutoRestartTime", "11:45 PM")), "12-hour time valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("AutoRestartTime", "23:45")), "AutoRestartTime"),
                "wrong 12-hour format fails");
        Assertions.isTrue(hasError(validator.validate(Map.of("AutoRestartTime", "11:45 pm")), "AutoRestartTime"),
                "IBC's case-sensitive 12-hour parser must reject lowercase am/pm");
        Assertions.equals(List.of(), validator.validate(Map.of("ColdRestartTime", "23:45")), "24-hour time valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("ColdRestartTime", "25:00")), "ColdRestartTime"),
                "invalid hour fails");
    }

    private void timeLocaleIndependence() throws Exception {
        Process process = new ProcessBuilder(TestSupport.javaCommandWithJvmOptions(
                List.of("-Duser.language=nl", "-Duser.country=NL"),
                "validate-documented-time"))
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            Assertions.fail("locale-isolated time validation subprocess timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Assertions.equals(0, process.exitValue(),
                "documented AM/PM validation must not depend on the Windows locale: " + output);
        Assertions.contains(output, "TIME-VALIDATION-OK:nl",
                "regression probe must execute under a Dutch formatting locale");
    }

    private void schedules() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("ClosedownAt", "22:00")),
                "daily closedown schedule valid");
        String today = new java.text.SimpleDateFormat("E").format(new java.util.Date());
        Assertions.equals(List.of(), validator.validate(Map.of("ClosedownAt", today + " 22:00")),
                "locale weekday closedown schedule valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("ClosedownAt", "Friday 25:00")), "ClosedownAt"),
                "invalid closedown time must fail");
        Assertions.isTrue(hasError(validator.validate(Map.of("ClosedownAt", "22:00 trailing")), "ClosedownAt"),
                "trailing closedown data must fail");

        for (String value : List.of("08:00", "08:00 12:30 17:30", "Every 30",
                "Every 30 mins", "Every 1 hours 08:00", "Every 1 hours 08:00 24:00",
                "Every 90 08:00 17:43")) {
            Assertions.isFalse(hasError(validator.validate(Map.of("SaveTwsSettingsAt", value)),
                    "SaveTwsSettingsAt"), "valid settings-save schedule: " + value);
        }
        for (String value : List.of("Every", "Every x", "Every 0", "Every 24 hours",
                "Every 30 25:00", "Every 30 08:00 25:00", "Every 30 08:00 17:00 extra",
                "Every\t30 mins", "08:00\t12:00", "08:00 99:00")) {
            Assertions.isTrue(hasError(validator.validate(Map.of("SaveTwsSettingsAt", value)),
                    "SaveTwsSettingsAt"), "invalid settings-save schedule must fail: " + value);
        }
    }

    private void orderIdResetPolicy() {
        ConfigValueValidator validator = new ConfigValueValidator();
        for (String value : List.of("ignore/ignore", "confirm/reject", "reject/confirm")) {
            Assertions.isFalse(hasError(validator.validate(Map.of("ConfirmOrderIdReset", value)),
                    "ConfirmOrderIdReset"), "valid order-ID reset policy: " + value);
        }
        for (String value : List.of("ignore", "IGNORE/ignore", "confirm/", "/reject",
                "confirm/reject/ignore", "yes/no")) {
            Assertions.isTrue(hasError(validator.validate(Map.of("ConfirmOrderIdReset", value)),
                    "ConfirmOrderIdReset"), "invalid order-ID reset policy must fail: " + value);
        }
    }

    private void logStructureAliases() {
        ConfigValueValidator validator = new ConfigValueValidator();
        for (String value : List.of("never", "open", "openclose", "activate", "yes", "true",
                "no", "false", "activated", "closed", "closing", "deactivated", "deiconified",
                "focused", "iconified", "lost focus", "opened", "state changed")) {
            Assertions.isFalse(hasError(validator.validate(Map.of("LogStructureWhen", value)),
                    "LogStructureWhen"), "IBC logging alias must be accepted: " + value);
        }
        Assertions.isFalse(hasError(validator.validate(Map.of("LogComponents", "open")),
                "LogComponents"), "legacy LogComponents alias must be accepted");
    }

    private void unsupportedFixMode() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.isFalse(hasError(validator.validate(Map.of("FIX", "no")), "FIX"),
                "ordinary gateway mode must remain supported");
        Assertions.isTrue(hasError(validator.validate(Map.of("FIX", "yes")), "FIX"),
                "FIX CTCI mode must be blocked");
        Assertions.isTrue(hasError(validator.validate(IbcConfigDocument.parse("FIX=true\n")), "FIX"),
                "raw configurations must not bypass the FIX-mode block");
        Assertions.isTrue(hasWarning(validator.validate(Map.of("FIXLoginId", "fix-user")), "FIXLoginId"),
                "orphan FIX credentials must warn");
    }

    private void fixOnlyTrustedApiAddresses() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.isTrue(hasWarning(
                        validator.validate(Map.of("TrustedTwsApiClientIPs", "192.0.3.10")),
                        "TrustedTwsApiClientIPs"),
                "IBC's FIX-only trusted-address setting must warn in ordinary TWS/Gateway mode");
    }

    private void applicationSpecificSettings() throws Exception {
        Path root = TestSupport.tempDirectory("validation-application-specific");
        try {
            Profile gateway = TestSupport.validProfile(root);
            Profile readOnlyGateway = gateway.toBuilder()
                    .settings(Map.of("ReadOnlyLogin", "yes"))
                    .build();
            Assertions.isTrue(hasError(new ProfileValidator(new FakeStore(true))
                            .validateForEdit(readOnlyGateway, false).issues(), "ReadOnlyLogin"),
                    "read-only login must be rejected for Gateway because IBC ignores it there");

            Profile gatewayOnlyWarnings = gateway.toBuilder()
                    .settings(Map.of(
                            "StoreSettingsOnServer", "yes",
                            "ConfirmOrderIdReset", "confirm/reject"))
                    .build();
            List<ValidationIssue> gatewayIssues = new ProfileValidator(new FakeStore(true))
                    .validateForEdit(gatewayOnlyWarnings, false).issues();
            Assertions.isTrue(hasWarning(gatewayIssues, "StoreSettingsOnServer"),
                    "Gateway must warn about the TWS-only server-settings option");
            Assertions.isTrue(hasWarning(gatewayIssues, "ConfirmOrderIdReset"),
                    "Gateway must warn that its order-ID reset confirmation policy is ignored");

            Profile tws = gateway.toBuilder()
                    .targetType(io.github.ibcmanager.model.TargetType.TWS)
                    .settings(Map.of("ReadOnlyLogin", "yes", "StoreSettingsOnServer", "yes"))
                    .build();
            List<ValidationIssue> twsIssues = new ProfileValidator(new FakeStore(true))
                    .validateForEdit(tws, false).issues();
            Assertions.isFalse(hasError(twsIssues, "ReadOnlyLogin"),
                    "read-only login remains valid for TWS");
            Assertions.isFalse(hasWarning(twsIssues, "StoreSettingsOnServer"),
                    "server-side settings storage remains valid for TWS");
        } finally {
            TestSupport.deleteTree(root);
        }
    }

    private void autoScheduleConflict() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Map<String, String> settings = Map.of("AutoLogoffTime", "11:00 PM", "AutoRestartTime", "11:30 PM");
        Assertions.isTrue(hasWarning(validator.validate(settings), "AutoLogoffTime"),
                "IBC ignores AutoLogoffTime when AutoRestartTime is also set");
        Assertions.isTrue(hasWarning(validator.validate(IbcConfigDocument.parse(
                "AutoLogoffTime=11:00 PM\nAutoRestartTime=11:30 PM\n")), "AutoLogoffTime"),
                "raw configuration conflict must warn");
    }

    private void ipLists() {
        ConfigValueValidator validator = new ConfigValueValidator();
        Assertions.equals(List.of(), validator.validate(Map.of("ControlFrom", "127.0.0.1,localhost")), "resolvable hosts valid");
        Assertions.isTrue(hasError(validator.validate(Map.of("ControlFrom", "127.0.0.1, localhost")), "ControlFrom"),
                "surrounding whitespace must fail because IBC does not trim command-source entries");
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
