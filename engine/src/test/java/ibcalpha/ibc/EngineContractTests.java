package ibcalpha.ibc;

import io.github.ibcmanager.config.IbcConfigDocument;
import io.github.ibcmanager.config.ManagedConfigService;
import io.github.ibcmanager.model.Profile;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Random;

/** Tests execute maintained upstream engine classes, not a duplicate implementation. */
public final class EngineContractTests implements TestSuite {
    @Override public String name() { return "Maintained IBC engine: actual upstream configuration/login/channel code"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("engine version preserves upstream provenance", this::version),
                new NamedTest("actual Properties loader preserves Unicode and literal backslashes", this::settings),
                new NamedTest("actual engine getters retain upstream trim and fallback semantics", this::getters),
                new NamedTest("empty imported properties preserve actual engine fallback semantics", this::emptyFallbacks),
                new NamedTest("actual configuration log redacts credentials", this::redaction),
                new NamedTest("generated five-minute retry is read correctly by the actual engine", this::twoFactor),
                new NamedTest("actual default login provider uses current settings", this::credentials),
                new NamedTest("actual argument credentials retain upstream precedence", this::credentialArguments),
                new NamedTest("actual login state transitions emit bounded structured events", this::loginEvents),
                new NamedTest("structured events reject arbitrary strings", this::eventBounds),
                new NamedTest("actual trading-mode provider honors settings and arguments", this::tradingMode),
                new NamedTest("actual command channel preserves OK ERROR and line framing", this::channel),
                new NamedTest("TWS entry is rejected before any IBKR launch", this::twsRejected),
                new NamedTest("Gateway bootstrap environment initializes without starting a session", this::environment),
                new NamedTest("randomized Manager output is consumed by actual IBC settings", this::roundTrips));
    }
    private void version() {
        Assertions.equals("3.24.2", IbcVersionInfo.IBC_VERSION, "upstream version");
        Assertions.equals("IBC_MANAGER_EVENT|1|", EngineEvents.PREFIX, "protocol version");
    }
    private interface WithSettings { void run(DefaultSettings settings) throws Exception; }
    private static void with(String text, WithSettings test) throws Exception {
        Path file = Files.createTempFile("ibc-engine-settings-", ".ini");
        Settings old = Settings.settings();
        try {
            Files.write(file, text.getBytes(StandardCharsets.ISO_8859_1));
            DefaultSettings settings = new DefaultSettings(file.toString());
            Settings.initialise(settings);
            test.run(settings);
        } finally {
            if (old != null) Settings.initialise(old);
            Files.deleteIfExists(file);
        }
    }
    private void settings() throws Exception {
        with("IbLoginId=J\\u00f6rg\nIbPassword=p\\u00e4ss\\\\test\\\\u0041\n"
                + "SecondFactorDevice=T\\u00e9l\\u00e9phone \\ud83d\\udd10\n"
                + "continued = first\\\n    second\ncolon:value\n", s -> {
            Assertions.equals("J\u00f6rg", s.getString("IbLoginId", ""), "Unicode username");
            Assertions.equals("p\u00e4ss\\test\\u0041", s.getString("IbPassword", ""), "literal backslashes");
            Assertions.equals("T\u00e9l\u00e9phone \ud83d\udd10", s.getString("SecondFactorDevice", ""), "Unicode device");
            Assertions.equals("firstsecond", s.getString("continued", ""), "continuation");
            Assertions.equals("value", s.getString("colon", ""), "colon separator");
        });
    }
    private void getters() throws Exception {
        with("Text= value \nInteger=7\nBadInteger=7 \nYes=yes\nBadYes=yes \nEmpty=\n", s -> {
            Assertions.equals("value", s.getString("Text", ""), "text trims");
            Assertions.equals(7, s.getInt("Integer", 3), "integer parses");
            Assertions.equals(3, s.getInt("BadInteger", 3), "integer whitespace fallback");
            Assertions.isTrue(s.getBoolean("Yes", false), "yes accepted");
            Assertions.isFalse(s.getBoolean("BadYes", false), "boolean whitespace fallback");
            Assertions.equals("fallback", s.getString("Empty", "fallback"), "blank fallback");
        });
    }
    private void emptyFallbacks() throws Exception {
        String text = "AcceptNonBrokerageAccountWarning=\nExistingSessionDetectedAction=\n"
                + "SuppressInfoMessages=\nLoginDialogDisplayTimeout=\n";
        Assertions.equals(List.of(), new io.github.ibcmanager.config.ConfigValueValidator()
                .validateManagedConfig(IbcConfigDocument.parse(text)), "empty typed properties are valid engine fallbacks");
        with(text, s -> {
            Assertions.isTrue(s.getBoolean("AcceptNonBrokerageAccountWarning", true), "actual warning handler default remains true");
            Assertions.isTrue(s.getBoolean("SuppressInfoMessages", true), "actual channel fallback");
            Assertions.equals("manual", s.getString("ExistingSessionDetectedAction", "manual"), "actual enum fallback");
            Assertions.equals(60, s.getInt("LoginDialogDisplayTimeout", 60), "actual integer fallback");
        });
    }

    private static String probe(String mode, String config) throws Exception {
        Path file = Files.createTempFile("engine-probe-", ".ini");
        try {
            Files.writeString(file, config);
            Process process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-classpath",
                    System.getProperty("java.class.path"), EngineProbe.class.getName(), mode, file.toString())
                    .redirectErrorStream(true).start();
            try {
                Assertions.isTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "probe exits");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Assertions.equals(0, process.exitValue(), "probe success: " + output);
                return output;
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        } finally { Files.deleteIfExists(file); }
    }
    private void redaction() throws Exception {
        String output = probe("redaction", "IbLoginId=private-user\nIbPassword=private-password\nFIXPassword=private-fix\n");
        Assertions.notContains(output, "private-user", "username is hidden");
        Assertions.notContains(output, "private-password", "password is hidden");
        Assertions.notContains(output, "private-fix", "FIX secret is hidden");
        Assertions.contains(output, "IbPassword=***", "redaction marker");
    }
    private void twoFactor() throws Exception {
        var config = IbcConfigDocument.parse("");
        ManagedConfigService.applyProfileControlled(config, Profile.builder().reloginAfterSecondFactorTimeout(true).build());
        with(new String(config.toIbcBytes(), StandardCharsets.ISO_8859_1), s -> {
            Assertions.equals(300, s.getInt("SecondFactorAuthenticationTimeout", -1), "five minutes");
            Assertions.isTrue(s.getBoolean("ReloginAfterSecondFactorAuthenticationTimeout", false), "native relogin on");
            Assertions.equals("fallback", s.getString("ExitAfterSecondFactorAuthenticationTimeout", "fallback"), "legacy policy cleared");
        });
    }
    private void credentials() throws Exception {
        with("IbLoginId=user-one\nIbPassword=password-one\n", s -> {
            DefaultLoginManager provider = new DefaultLoginManager();
            Assertions.equals("user-one", provider.IBAPIUserName(), "settings username");
            Assertions.equals("password-one", provider.IBAPIPassword(), "settings password");
        });
    }
    private void credentialArguments() {
        DefaultLoginManager provider = new DefaultLoginManager(new String[] {"unused.ini", "argument-user", "argument-password"});
        Assertions.equals("argument-user", provider.IBAPIUserName(), "upstream argument username");
        Assertions.equals("argument-password", provider.IBAPIPassword(), "upstream argument password");
    }
    private void loginEvents() throws Exception {
        String output = probe("events", "IbLoginId=\nIbPassword=\n");
        var events = output.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)).toList();
        Assertions.equals(4, events.size(), "boot plus one event per actual transition");
        Assertions.isTrue(events.stream().allMatch(line -> line.length() < 192), "bounded event size");
        Assertions.contains(events.get(2), "LOGIN_TWO_FA_IN_PROGRESS", "second factor state survives stdout redirection");
        Assertions.contains(events.get(3), "LOGIN_LOGGED_IN", "completed state survives stdout redirection");
    }
    private void eventBounds() {
        Assertions.throwsType(IllegalArgumentException.class, () -> EngineEvents.emit("password=secret"), "free text rejected");
        Assertions.throwsType(NullPointerException.class, () -> EngineEvents.emit(null), "null rejected");
    }
    private void tradingMode() throws Exception {
        with("TradingMode=paper\n", s -> {
            Assertions.equals("paper", new DefaultTradingModeManager(new String[] {"config.ini"}).getTradingMode(), "settings mode");
            Assertions.equals("live", new DefaultTradingModeManager(new String[] {"config.ini", "live"}).getTradingMode(), "argument mode");
        });
    }
    private void channel() throws Exception {
        with("SuppressInfoMessages=yes\nCommandPrompt=\n", settings -> {
            try (ServerSocket server = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
                    Socket client = new Socket(java.net.InetAddress.getLoopbackAddress(), server.getLocalPort());
                    Socket accepted = server.accept()) {
                client.setSoTimeout(3000); accepted.setSoTimeout(3000);
                CommandChannel channel = new CommandChannel(accepted);
                client.getOutputStream().write("\nSTOP\n".getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
                Assertions.equals("STOP", channel.getCommand(), "blank lines ignored");
                channel.writeAck("accepted"); channel.writeNack("failed");
                var input = new java.io.BufferedReader(new java.io.InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                Assertions.equals("OK accepted", input.readLine(), "ack framing");
                Assertions.equals("ERROR failed", input.readLine(), "error framing");
                channel.close(); channel.close();
            }
        });
    }
    private void twsRejected() {
        Assertions.throwsType(UnsupportedOperationException.class, () -> IbcTws.main(new String[0]), "TWS entry disabled");
    }
    private void environment() throws Exception {
        Path file = Files.createTempFile("engine-environment-", ".ini");
        try {
            Files.writeString(file, "TradingMode=paper\nFIX=no\n");
            IbcTws.setupDefaultEnvironment(new String[] {file.toString()}, true);
            Assertions.isTrue(SessionManager.isGateway(), "Gateway context");
            Assertions.isFalse(Settings.settings().getBoolean("FIX", true), "API, not FIX");
            Assertions.equals(LoginManager.LoginState.LOGGED_OUT, LoginManager.loginManager().getLoginState(), "no premature login");
            Assertions.throwsType(UnsupportedOperationException.class,
                    () -> IbcTws.setupDefaultEnvironment(new String[] {file.toString()}, false), "TWS setup disabled");
        } finally { Files.deleteIfExists(file); }
    }
    private void roundTrips() throws Exception {
        Random random = new Random(6238);
        String alphabet = "abXYZ09 :=#!\\\t\u00e9\u03bb";
        PrintStream old = System.out;
        try (PrintStream sink = new PrintStream(java.io.OutputStream.nullOutputStream())) {
            System.setOut(sink);
            for (int i = 0; i < 100; i++) {
                StringBuilder value = new StringBuilder("x");
                for (int k = 0; k < 30; k++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
                value.append('z');
                var document = IbcConfigDocument.parse(""); document.set("Value", value.toString());
                with(new String(document.toIbcBytes(), StandardCharsets.ISO_8859_1),
                        s -> Assertions.equals(value.toString(), s.getString("Value", ""), "engine round trip"));
            }
        } finally { System.setOut(old); }
    }
}
