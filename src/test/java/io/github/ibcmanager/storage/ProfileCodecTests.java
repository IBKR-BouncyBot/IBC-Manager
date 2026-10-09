package io.github.ibcmanager.storage;

import io.github.ibcmanager.model.CredentialMode;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.model.TargetType;
import io.github.ibcmanager.model.TradingMode;
import io.github.ibcmanager.model.TwoFactorTimeoutAction;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class ProfileCodecTests implements TestSuite {
    private final ProfileCodec codec = new ProfileCodec();

    @Override public String name() { return "Profile codec and model"; }

    @Override
    public List<NamedTest> tests() {
        List<NamedTest> tests = new ArrayList<>();
        tests.add(new NamedTest("round-trips a complete profile", this::roundTripComplete));
        tests.add(new NamedTest("uses deterministic setting ordering", this::deterministicOrdering));
        tests.add(new NamedTest("supports CRLF input", this::crlfInput));
        tests.add(new NamedTest("preserves escaped control characters", this::escapedCharacters));
        tests.add(new NamedTest("preserves unknown escape sequences", this::unknownEscape));
        tests.add(new NamedTest("rejects duplicate core keys", this::duplicateCore));
        tests.add(new NamedTest("rejects duplicate setting keys", this::duplicateSetting));
        tests.add(new NamedTest("reports the encoded source format using full structural validation",
                this::sourceFormatVersion));
        tests.add(new NamedTest("rejects unsupported format versions", this::unsupportedVersion));
        tests.add(new NamedTest("migrates version 1 compatibility defaults", this::migrateVersionOne));
        tests.add(new NamedTest("migrates version 2 automatic-recovery default", this::migrateVersionTwo));
        tests.add(new NamedTest("migrates version 3 to unattended 2FA retry defaults", this::migrateVersionThree));
        tests.add(new NamedTest("new profiles enable unattended 2FA retry defaults", this::defaultSecondFactorPolicy));
        tests.add(new NamedTest("format 4 preserves an explicit disabled 2FA retry", this::preserveDisabledSecondFactorPolicy));
        tests.add(new NamedTest("rejects missing required keys", this::missingKey));
        tests.add(new NamedTest("rejects invalid integers", this::invalidInteger));
        tests.add(new NamedTest("rejects invalid booleans", this::invalidBoolean));
        tests.add(new NamedTest("rejects invalid encoded setting keys", this::invalidSettingKey));
        tests.add(new NamedTest("ignores unknown forward-compatible core keys", this::unknownCoreKey));
        tests.add(new NamedTest("never serializes a password field", this::noPasswordField));
        tests.add(new NamedTest("normalizes builder input", this::builderNormalization));
        tests.add(new NamedTest("profile equality includes all settings", this::profileEquality));
        Random random = new Random(0x1BC2026L);
        for (int index = 0; index < 60; index++) {
            int caseIndex = index;
            long seed = random.nextLong();
            tests.add(new NamedTest("randomized round-trip " + caseIndex,
                    () -> randomizedRoundTrip(seed, caseIndex)));
        }
        return tests;
    }

    private void roundTripComplete() {
        Profile profile = Profile.builder()
                .id(UUID.fromString("12345678-1234-5678-1234-567812345678"))
                .name("NBIS Gateway")
                .enabled(false)
                .targetType(TargetType.TWS)
                .tradingMode(TradingMode.LIVE)
                .twsMajorVersion("1045")
                .ibcPath(Path.of("C:/IBC Test"))
                .twsPath(Path.of("C:/Jts"))
                .twsSettingsPath(Path.of("C:/Users/test/Jts-NBIS"))
                .baseConfigPath(Path.of("C:/base/config.ini"))
                .ibcJavaPath(Path.of("C:/Java17/bin"))
                .apiPort(7497)
                .commandServerPort(7463)
                .bindAddress("127.0.0.1")
                .username("U1234567")
                .credentialMode(CredentialMode.ENCRYPTED)
                .twoFactorTimeoutAction(TwoFactorTimeoutAction.RESTART)
                .reloginAfterSecondFactorTimeout(true)
                .forceApiPortAtLaunch(false)
                .autoRecoverStartupStall(false)
                .autoStart(true)
                .minimizeMainWindow(false)
                .gracefulStopTimeoutSeconds(45)
                .settings(Map.of("AutoRestartTime", "11:45 PM", "ControlFrom", "127.0.0.1"))
                .build();
        String encoded = codec.encode(profile);
        Assertions.equals(profile, codec.decode(encoded), "complete profile must round-trip");
        Assertions.isTrue(encoded.endsWith("\n"), "encoded profile must end with one newline");
        Assertions.contains(encoded, "formatVersion=5", "format version must be emitted");
    }

    private void deterministicOrdering() {
        Profile first = base().settings(new LinkedHashMap<>(Map.of("z", "1", "a", "2"))).build();
        Profile second = first.toBuilder().settings(new LinkedHashMap<>(Map.of("a", "2", "z", "1"))).build();
        Assertions.equals(codec.encode(first), codec.encode(second), "map insertion order must not affect output");
        Assertions.isTrue(codec.encode(first).indexOf(encodedSettingKey("a"))
                        < codec.encode(first).indexOf(encodedSettingKey("z")),
                "settings must be sorted by key");
    }

    private void crlfInput() {
        Profile profile = base().build();
        String crlf = codec.encode(profile).replace("\n", "\r\n");
        Assertions.equals(profile, codec.decode(crlf), "CRLF input must decode identically");
    }

    private void escapedCharacters() {
        Profile profile = base().name("line1\nline2\t\\tail")
                .username("user\rname")
                .setting("custom.key", "a=b\n\\c\t")
                .build();
        Assertions.equals(profile, codec.decode(codec.encode(profile)), "escaped text must round-trip");
        Assertions.notContains(codec.encode(profile), "line1\nline2", "literal newlines must not split values");
    }

    private void unknownEscape() {
        Assertions.equals("a\\qb", ProfileCodec.unescape("a\\qb"), "unknown escapes must remain literal");
        Assertions.equals("tail\\", ProfileCodec.unescape("tail\\"), "trailing slash must remain literal");
    }

    private void duplicateCore() {
        String text = codec.encode(base().build()) + "name=duplicate\n";
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "duplicate core key must fail");
    }

    private void duplicateSetting() {
        Profile profile = base().setting("Alpha", "one").build();
        String line = "setting." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("Alpha".getBytes(StandardCharsets.UTF_8)) + "=two\n";
        Assertions.throwsType(IllegalArgumentException.class,
                () -> codec.decode(codec.encode(profile) + line), "duplicate setting must fail");
    }

    private void sourceFormatVersion() {
        String current = codec.encode(base().build());
        Assertions.equals(5, codec.sourceFormatVersion(current),
                "the current encoded format must be reported");
        String legacy = current.replace("formatVersion=5", "formatVersion=3");
        Assertions.equals(3, codec.sourceFormatVersion(legacy),
                "the legacy encoded format must be reported without decoding migration");
        Assertions.throwsType(IllegalArgumentException.class,
                () -> codec.sourceFormatVersion(legacy + "formatVersion=3\n"),
                "format inspection must reject duplicate core keys");
    }

    private void unsupportedVersion() {
        String text = codec.encode(base().build()).replace("formatVersion=5", "formatVersion=999");
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "unsupported format must fail");
    }


    private void migrateVersionOne() {
        Profile current = base()
                .twoFactorTimeoutAction(TwoFactorTimeoutAction.RESTART)
                .forceApiPortAtLaunch(false)
                .reloginAfterSecondFactorTimeout(false)
                .build();
        String legacy = codec.encode(current)
                .replace("formatVersion=5", "formatVersion=1")
                .replaceAll("(?m)^ibcJavaPath=.*\n", "")
                .replaceAll("(?m)^reloginAfterSecondFactorTimeout=.*\n", "")
                .replaceAll("(?m)^forceApiPortAtLaunch=.*\n", "")
                .replaceAll("(?m)^autoRecoverStartupStall=.*\n", "");
        Profile migrated = codec.decode(legacy);
        Assertions.isTrue(migrated.reloginAfterSecondFactorTimeout(),
                "version 1 restart policy must preserve the old internal-relogin behavior");
        Assertions.isTrue(migrated.forceApiPortAtLaunch(),
                "version 1 profiles must preserve the old forced API-port behavior");
        Assertions.equals(Path.of(""), migrated.ibcJavaPath(),
                "version 1 profiles must leave the Java override unset");
    }

    private void migrateVersionTwo() {
        Profile current = base().autoRecoverStartupStall(false).build();
        String legacy = codec.encode(current)
                .replace("formatVersion=5", "formatVersion=2")
                .replaceAll("(?m)^autoRecoverStartupStall=.*\n", "");
        Profile migrated = codec.decode(legacy);
        Assertions.isTrue(migrated.autoRecoverStartupStall(),
                "version 2 profiles must enable the unattended startup-stall recovery default");
    }

    private void migrateVersionThree() {
        Profile current = base()
                .twoFactorTimeoutAction(TwoFactorTimeoutAction.EXIT)
                .reloginAfterSecondFactorTimeout(false)
                .build();
        String legacy = codec.encode(current).replace("formatVersion=5", "formatVersion=3");
        Profile migrated = codec.decode(legacy);
        Assertions.equals(TwoFactorTimeoutAction.RESTART, migrated.twoFactorTimeoutAction(),
                "version 3 profiles must adopt the unattended wrapper restart fallback");
        Assertions.isTrue(migrated.reloginAfterSecondFactorTimeout(),
                "version 3 profiles must adopt repeated five-minute 2FA notification retries");
    }

    private void defaultSecondFactorPolicy() {
        Profile profile = Profile.builder().build();
        Assertions.equals(TwoFactorTimeoutAction.RESTART, profile.twoFactorTimeoutAction(),
                "new profiles must restart the login path after an unresolved 2FA timeout");
        Assertions.isTrue(profile.reloginAfterSecondFactorTimeout(),
                "new profiles must repeat an uncompleted second-factor notification");
    }

    private void preserveDisabledSecondFactorPolicy() {
        Profile disabled = base()
                .twoFactorTimeoutAction(TwoFactorTimeoutAction.EXIT)
                .reloginAfterSecondFactorTimeout(false)
                .build();
        Profile decoded = codec.decode(codec.encode(disabled));
        Assertions.equals(TwoFactorTimeoutAction.EXIT, decoded.twoFactorTimeoutAction(),
                "format 4 must preserve an explicit wrapper exit policy");
        Assertions.isFalse(decoded.reloginAfterSecondFactorTimeout(),
                "format 4 must preserve an explicit disabled notification retry");
    }

    private void missingKey() {
        String text = codec.encode(base().build()).replaceAll("(?m)^name=.*\\n", "");
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "missing required key must fail");
    }

    private void invalidInteger() {
        String text = codec.encode(base().build()).replace("apiPort=4002", "apiPort=abc");
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "invalid port integer must fail");
    }

    private void invalidBoolean() {
        String text = codec.encode(base().build()).replace("enabled=true", "enabled=maybe");
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "invalid boolean must fail");
    }

    private void invalidSettingKey() {
        String text = codec.encode(base().build()) + "setting.!=value\n";
        Assertions.throwsType(IllegalArgumentException.class, () -> codec.decode(text),
                "invalid Base64 key must fail");
    }

    private void unknownCoreKey() {
        Profile profile = base().build();
        String text = codec.encode(profile) + "futureField=futureValue\n";
        Assertions.equals(profile, codec.decode(text), "unknown core keys should be ignored for forward compatibility");
    }

    private void noPasswordField() {
        String encoded = codec.encode(base().credentialMode(CredentialMode.ENCRYPTED).build());
        Assertions.notContains(encoded.toLowerCase(java.util.Locale.ROOT), "password=", "profile file must not contain password data");
        Assertions.notContains(encoded, "IbPassword", "profile file must not mimic IBC secret settings");
    }

    private void builderNormalization() {
        Profile profile = base().name("  Name  ").bindAddress("  127.0.0.1  ")
                .setting(" key ", null).build();
        Assertions.equals("Name", profile.name(), "name must be trimmed");
        Assertions.equals("127.0.0.1", profile.bindAddress(), "bind address must be trimmed");
        Assertions.equals("", profile.settings().get("key"), "null setting values become blank");
    }

    private void profileEquality() {
        Profile first = base().setting("a", "1").build();
        Profile second = first.toBuilder().setting("a", "2").build();
        Assertions.notEquals(first, second, "settings must participate in equality");
        Assertions.notEquals(first.hashCode(), second.hashCode(), "hash should normally change with settings");
        Profile third = first.toBuilder().autoRecoverStartupStall(!first.autoRecoverStartupStall()).build();
        Assertions.notEquals(first, third, "automatic recovery must participate in equality");
    }

    private void randomizedRoundTrip(long seed, int index) {
        Random random = new Random(seed);
        Map<String, String> settings = new LinkedHashMap<>();
        for (int item = 0; item < random.nextInt(8); item++) {
            settings.put("k" + item + randomText(random, 8), randomText(random, 30));
        }
        Profile profile = base()
                .id(new UUID(random.nextLong(), random.nextLong()))
                .name("P" + index + randomText(random, 20))
                .enabled(random.nextBoolean())
                .targetType(random.nextBoolean() ? TargetType.GATEWAY : TargetType.TWS)
                .tradingMode(random.nextBoolean() ? TradingMode.LIVE : TradingMode.PAPER)
                .apiPort(1 + random.nextInt(65535))
                .commandServerPort(1 + random.nextInt(65535))
                .username(randomText(random, 24))
                .credentialMode(CredentialMode.values()[random.nextInt(CredentialMode.values().length)])
                .reloginAfterSecondFactorTimeout(random.nextBoolean())
                .forceApiPortAtLaunch(random.nextBoolean())
                .autoRecoverStartupStall(random.nextBoolean())
                .autoStart(random.nextBoolean())
                .minimizeMainWindow(random.nextBoolean())
                .gracefulStopTimeoutSeconds(30 + random.nextInt(271))
                .settings(settings)
                .build();
        Assertions.equals(profile, codec.decode(codec.encode(profile)), "random profile must round-trip for seed " + seed);
    }

    private static Profile.Builder base() {
        return Profile.builder()
                .id(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
                .name("Profile")
                .targetType(TargetType.GATEWAY)
                .tradingMode(TradingMode.PAPER)
                .twsMajorVersion("1045")
                .ibcPath(Path.of("C:/IBC"))
                .twsPath(Path.of("C:/Jts"))
                .twsSettingsPath(Path.of("C:/JtsSettings"))
                .apiPort(4002)
                .commandServerPort(7462)
                .bindAddress("127.0.0.1")
                .username("user")
                .credentialMode(CredentialMode.MANUAL);
    }

    private static String encodedSettingKey(String key) {
        return "setting." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomText(Random random, int maxLength) {
        String alphabet = "abcXYZ019 =\\\n\r\t-_./äΩ";
        int length = random.nextInt(maxLength + 1);
        StringBuilder result = new StringBuilder(length);
        for (int index = 0; index < length; index++) result.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return result.toString();
    }
}
