package ibcalpha.ibc;

import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;
import io.github.ibcmanager.tests.TestSupport;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.html.HTMLEditorKit;

/** Recorded server wording plus bounded Swing extraction; no broker or display required. */
public final class ExpiredSessionTokenTests implements TestSuite {
    // Verbatim message body from the 2026-10-18 diagnostic launcher.log, line 169.
    // It contains no account, credential, session identifier or device information.
    static final String RECORDED_HTML = "<html>The security tokens associated with your login credentials have expired<br>"
            + "(routinely) in accordance with our security protocols. Please manually<br>"
            + "enter your username and password to access IBKR Gateway.<br>"
            + "<a href=\"https://www.ibkrguides.com/traderworkstation/auto-restart-considerations.htm\">"
            + "Auto Restart Information</a></html>";
    static final String EXPIRED = "The security tokens associated with your login credentials have expired";
    static final String ENTER = "Please manually enter your username and password to access IBKR Gateway.";
    static final String PLAIN = EXPIRED + " (routinely) in accordance with our security protocols. " + ENTER;

    @Override public String name() { return "Expired restart-token recognition and safe text extraction"; }
    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("recorded HTML and equivalent plain message match", this::recorded),
                new NamedTest("HTML entities case and line breaks normalize", this::normalization),
                new NamedTest("both specific expiry and credential-entry phrases are required", this::bothPhrases),
                new NamedTest("other authentication and connection failures are excluded", this::otherErrors),
                new NamedTest("visible JLabel message with OK is extracted", this::label),
                new NamedTest("read-only HTML JEditorPane message is extracted", this::editorPane),
                new NamedTest("Gateway 10.50.1e JTextPane construction recognizes the recorded rejection", this::actualGatewayWidget),
                new NamedTest("read-only JTextArea and JTextField messages are extracted", this::readOnlyText),
                new NamedTest("message split over visible controls is recognized", this::splitText),
                new NamedTest("editable and password contents are never read", this::privateInputs),
                new NamedTest("hidden labels and hidden ancestor subtrees are never read", this::hidden),
                new NamedTest("missing hidden disabled and unrelated buttons do not authorize recovery", this::okControl),
                new NamedTest("button text tooltips and accessible names are not treated as messages", this::nonMessageText),
                new NamedTest("component traversal is bounded and late messages cannot trigger", this::componentBound),
                new NamedTest("oversized message controls cannot bypass the extraction bound", this::textBound),
                new NamedTest("queued recovery is claimed once across multiple matching windows", this::duplicateRecovery),
                new NamedTest("changed eligibility cancels an already queued recovery", this::eligibilityChanges),
                new NamedTest("login generation changes cancel queued recovery even after returning to logged out", this::generationChanges),
                new NamedTest("replacement login manager cancels queued recovery", this::ownerChanges),
                new NamedTest("established-session reauthentication cancels queued expiry even after returning logged in", this::establishedGenerationChanges),
                new NamedTest("changed or dismissed message cancels queued recovery", this::messageChanges),
                new NamedTest("off-EDT recovery cannot claim or execute", this::threadGuard),
                new NamedTest("already claimed StopTask safely ignores null-channel duplicate", this::duplicateStop),
                new NamedTest("production eligibility and cold-restart marker run in isolated child", this::productionRecovery),
                new NamedTest("logged-in warm child bounds unavailable-window shutdown", () -> postloginTimeout("warm")),
                new NamedTest("logged-in fresh child bounds unavailable-window shutdown", () -> postloginTimeout("fresh")),
                new NamedTest("manual STOP wins over queued expiry recovery", () -> productionGuard("stop")),
                new NamedTest("manual PAUSE wins over queued expiry recovery", () -> productionGuard("pause")),
                new NamedTest("expiry marker creation failure preserves the process and reports failure", () -> productionGuard("marker")),
                new NamedTest("directory at expiry marker is rejected without killing Gateway", () -> productionGuard("marker-directory")),
                new NamedTest("pending cold authentication blocks another expiry-restart cycle", () -> productionGuard("pending")),
                new NamedTest("lost prepared marker cancels bounded recovery and permits manual stop", this::lostMarker),
                new NamedTest("successful fresh authentication clears only its own pending expiry marker", () -> acknowledgeMarker("success")),
                new NamedTest("pending marker cleanup failure is observable without changing login success", () -> acknowledgeMarker("directory")));
    }

    private void recorded() {
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.isMessage(RECORDED_HTML), "exact recorded server HTML");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.isMessage(PLAIN), "plain equivalent");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(null), "null is not evidence");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(""), "empty is not evidence");
    }

    private void normalization() {
        String spaced = "<html><body><p>THE security&nbsp;tokens associated with your login credentials have expired</p>"
                + "<div>Please manually&#32;enter your username and password to access IBKR Gateway.</div></body></html>";
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.isMessage(spaced), "HTML paragraphs and entities");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.isMessage(PLAIN.replace(" ", " \n\t")), "plain whitespace");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.isMessage(RECORDED_HTML.replace("<br>", "<br/>")), "self-closing break tags");
    }

    private void bothPhrases() {
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(EXPIRED), "expiry text alone insufficient");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(ENTER), "credential instruction alone insufficient");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage("Security token expired. " + ENTER), "generic token failure insufficient");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(EXPIRED + " Please contact support."), "no fresh-login instruction");
    }

    private void otherErrors() {
        for (String message : List.of("invalid twsInfo", "invalidTwsinfo", "Connection to server failed",
                "Invalid username or password", "Your password has expired. " + ENTER,
                "Second Factor Authentication", "The security tokens are valid. " + ENTER,
                "Gateway", "Trust this IP address?")) {
            Assertions.isFalse(ExpiredSessionTokenDialogHandler.isMessage(message), "unrelated failure excluded: " + message);
        }
    }

    private void label() {
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel(new JLabel(RECORDED_HTML))), "HTML JLabel");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel(new JLabel(PLAIN))), "plain JLabel");
    }

    private void editorPane() {
        JEditorPane message = new JEditorPane("text/html", RECORDED_HTML);
        message.setEditable(false);
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel(message)), "rendered HTML message document");
    }

    private void actualGatewayWidget() {
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(gatewayMessagePanel()),
                "Gateway constructs noneditable JTextPane with HTMLEditorKit, verified from ibgateway.aw.b(String)");
    }

    static JTextPane gatewayMessage() {
        JTextPane message = new JTextPane();
        message.setEditorKit(new HTMLEditorKit());
        message.setEditable(false);
        // ibgateway.N prepends localized Gateway_login_error before aw.d rewraps HTML.
        message.setText("<html>Connection to server failed: "
                + RECORDED_HTML.substring("<html>".length(), RECORDED_HTML.length() - "</html>".length()) + "</html>");
        return message;
    }

    private static JPanel gatewayMessagePanel() { return panel(gatewayMessage()); }

    private void readOnlyText() {
        JTextArea area = new JTextArea(PLAIN); area.setEditable(false);
        JTextField field = new JTextField(PLAIN); field.setEditable(false);
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel(area)), "read-only multiline control");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel(field)), "read-only text field");
    }

    private void splitText() {
        JPanel panel = panel(new JLabel("<html>" + EXPIRED + "</html>"));
        JTextArea instruction = new JTextArea(ENTER); instruction.setEditable(false); panel.add(instruction);
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "separate message controls compose the verified wording");
    }

    private void privateInputs() {
        PrivateText editable = new PrivateText(); editable.setText(PLAIN); editable.armed = true;
        PrivatePassword password = new PrivatePassword(); password.setText(PLAIN); password.setEditable(false); password.armed = true;
        JPanel panel = panel(editable); panel.add(password);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "credential and editable documents ignored before reading");
    }

    private void hidden() {
        PrivateLabel label = new PrivateLabel(PLAIN); label.setVisible(false); label.armed = true;
        JPanel panel = panel(label);
        JPanel retainedCard = new JPanel(); retainedCard.setVisible(false);
        PrivateLabel nested = new PrivateLabel(PLAIN); nested.armed = true; retainedCard.add(nested); panel.add(retainedCard);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "retained hidden cards are not current error evidence");
    }

    private void okControl() {
        JPanel panel = new JPanel(); panel.add(new JLabel(PLAIN));
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "requires recognized dismissal control");
        JButton ok = new JButton("OK"); panel.add(ok); ok.setEnabled(false);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "disabled OK");
        ok.setEnabled(true); ok.setVisible(false);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "hidden OK");
        ok.setVisible(true); ok.setText("Continue");
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "unrecognized action");
        ok.setText("OK");
        Assertions.isTrue(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "visible enabled OK");
    }

    private void nonMessageText() {
        JPanel panel = panel(new JButton(PLAIN));
        JLabel title = new JLabel("Gateway"); title.setToolTipText(PLAIN); title.getAccessibleContext().setAccessibleName(PLAIN);
        panel.add(title);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "metadata and button labels are not the error body");
    }

    private void componentBound() {
        JPanel panel = panel(new JLabel("Gateway"));
        for (int i = 0; i < ExpiredSessionTokenDialogHandler.MAX_COMPONENTS + 1; i++) panel.add(new JLabel("ordinary control"));
        panel.add(new JLabel(PLAIN));
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel), "no unbounded scan beyond component cap");
        JPanel deep = panel(new JLabel("Gateway")); JPanel cursor = deep;
        for (int i = 0; i < ExpiredSessionTokenDialogHandler.MAX_COMPONENTS + 1; i++) {
            JPanel child = new JPanel(); cursor.add(child); cursor = child;
        }
        cursor.add(new JLabel(PLAIN));
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(deep), "deep nesting also consumes the shared component budget");
    }

    private void textBound() {
        String oversized = "x".repeat(ExpiredSessionTokenDialogHandler.MAX_TEXT_LENGTH + 1) + PLAIN;
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel(new JLabel(oversized))), "oversized label excluded");
        JTextArea text = new JTextArea(oversized); text.setEditable(false);
        Assertions.isFalse(ExpiredSessionTokenDialogHandler.containsExpiry(panel(text)), "oversized text document excluded");
    }

    private void duplicateRecovery() throws Exception {
        Decision f = new Decision();
        onEdt(() -> {
            f.handler.handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(f.panel));
            f.handler.handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(f.panel));
            JPanel replacementDialog = gatewayMessagePanel();
            f.handler.handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(replacementDialog));
        });
        Assertions.equals(1, f.queue.size(), "one engine handler claim covers duplicate events and different windows");
        Assertions.equals(0, f.context.restarts, "action is deferred");
        f.flush();
        Assertions.equals(1, f.context.restarts, "one cold restart");
        onEdt(() -> f.handler.handleCandidate(() -> true)); f.flush();
        Assertions.equals(1, f.context.restarts, "claim survives successful callback");
    }

    private void eligibilityChanges() throws Exception {
        Decision f = new Decision(); f.context.eligible = false; f.recognize();
        Assertions.isTrue(f.queue.isEmpty(), "ineligible startup does not claim recovery");
        f.context.eligible = true; f.recognize(); f.context.eligible = false; f.flush();
        Assertions.equals(0, f.context.restarts, "stop or login progress before callback cancels action");
        f.context.eligible = true; f.recognize(); f.flush();
        Assertions.equals(0, f.context.restarts, "cancelled attempt is not rearmed by repeated stale window events");
    }

    private void generationChanges() throws Exception {
        Decision f = new Decision();
        long before = f.context.owner.loginGeneration();
        onEdt(() -> f.context.owner.setLoginState(LoginManager.LoginState.LOGGED_OUT));
        Assertions.equals(before, f.context.owner.loginGeneration(), "repeated state does not fabricate progress");
        f.recognize();
        onEdt(() -> {
            f.context.owner.setLoginState(LoginManager.LoginState.TWO_FA_IN_PROGRESS);
            f.context.owner.setLoginState(LoginManager.LoginState.LOGGED_OUT);
        });
        Assertions.isTrue(f.context.owner.loginGeneration() > before, "real state cycle retains monotonic progress evidence");
        f.flush();
        Assertions.equals(0, f.context.restarts, "stale expiry cannot restart after a new challenge, even at same final state");
    }

    private void establishedGenerationChanges() throws Exception {
        Decision f = new Decision();
        onEdt(() -> f.context.owner.setLoginState(LoginManager.LoginState.LOGGED_IN));
        f.recognize();
        onEdt(() -> {
            f.context.owner.setLoginState(LoginManager.LoginState.LOGGING_IN);
            f.context.owner.setLoginState(LoginManager.LoginState.LOGGED_IN);
        });
        f.flush();
        Assertions.equals(0, f.context.restarts, "completed reauthentication invalidates the previous expiry candidate");
    }

    private void ownerChanges() throws Exception {
        Decision f = new Decision(); f.recognize();
        f.context.owner = new DefaultLoginManager(); f.flush();
        Assertions.equals(0, f.context.restarts, "new login manager invalidates old callback independently of generation value");
    }

    private void messageChanges() throws Exception {
        Decision changed = new Decision(); changed.recognize();
        onEdt(() -> { changed.panel.removeAll(); changed.panel.add(new JLabel("invalid twsInfo")); changed.panel.add(new JButton("OK")); });
        changed.flush();
        Assertions.equals(0, changed.context.restarts, "replaced error content is rechecked");
        Decision hidden = new Decision(); hidden.recognize();
        onEdt(() -> hidden.panel.setVisible(false)); hidden.flush();
        Assertions.equals(0, hidden.context.restarts, "hidden message is rechecked");
    }

    private void threadGuard() throws Exception {
        Decision f = new Decision();
        Assertions.throwsType(IllegalStateException.class, () -> f.handler.handleCandidate(() -> true), "initial claim requires EDT");
        Assertions.isTrue(f.queue.isEmpty(), "bad caller has no side effect");
        f.recognize();
        Assertions.throwsType(IllegalStateException.class, () -> f.queue.get(0).run(), "queued decision requires EDT");
        Assertions.equals(0, f.context.restarts, "off-EDT callback cannot restart");
    }

    private void productionRecovery() throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-production-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--production-child", directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(12, TimeUnit.SECONDS), "isolated production recovery exits promptly");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "real StopTask exits successfully: " + log);
            Assertions.contains(log, "EXPIRY_EXCLUSIONS_PASSED", "fresh startup, TWS and active login states were preserved before eligible restart");
            Assertions.equals(1L, log.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)
                    && line.endsWith("|LOGIN_RESTART_TOKEN_EXPIRED")).count(), "exactly one specific recovery event");
            Assertions.fileExists(directory.resolve("TOKENEXPIREDexpiry-test-session"), "actual recovery created launcher-consumed expiry marker");
            Assertions.notContains(log, "fixture-private-password", "recovery never logs credential field");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    private void duplicateStop() throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-duplicate-stop-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--duplicate-stop-child", directory.toString()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(8, TimeUnit.SECONDS), "duplicate stop returns promptly in isolated child");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "null-channel duplicate returns without exception: " + log);
            Assertions.contains(log, "EXPIRY_DUPLICATE_STOP_PASSED", "completed duplicate-stop branch");
            Assertions.isFalse(Files.exists(directory.resolve("TOKENEXPIREDexpiry-test-session")), "duplicate cannot create cold-restart marker");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    private void postloginTimeout(String mode) throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-postlogin-timeout-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            long started = System.nanoTime();
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--postlogin-" + mode + "-child", directory.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(10, TimeUnit.SECONDS), "post-login shutdown cannot hang on a missing main window");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "same-JVM recovery exits for fresh authentication: " + log);
            Assertions.isTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 10_000,
                    "actual missing-window recovery has an enforced deadline");
            Assertions.equals(1L, log.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)
                    && line.endsWith("|SESSION_TOKEN_EXPIRED")).count(), "one established-session event");
            Assertions.notContains(log, "|LOGIN_RESTART_TOKEN_EXPIRED", "established-session expiry has its distinct event");
            Assertions.fileExists(directory.resolve("TOKENEXPIREDexpiry-test-session"), "cold bootstrap requested before forced same-JVM exit");
            Assertions.contains(log, "graceful shutdown deadline reached; terminating current Gateway JVM", "deadline fallback is explicit");
            Assertions.contains(log, "POSTLOGIN_TIMEOUT_FIXTURE_READY", "actual LOGGED_IN and launch-mode preconditions checked");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    private void productionGuard(String mode) throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-production-guard-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--guard-" + mode + "-child", directory.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(10, TimeUnit.SECONDS), "guard child returns without recovery exit or leaked worker");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "guard child confirms expected state: " + log);
            Assertions.contains(log, "EXPIRY_GUARD_PASSED_" + mode, "guard assertions completed");
            Assertions.notContains(log, "|SESSION_TOKEN_EXPIRED", "blocked recovery never reports that restart was requested");
            Assertions.isFalse(Files.isRegularFile(directory.resolve("TOKENEXPIREDexpiry-test-session")), "blocked recovery leaves no usable expiry marker");
            if (mode.startsWith("marker") || mode.equals("pending")) Assertions.contains(log, "|SESSION_TOKEN_RECOVERY_FAILED", "blocked or failed marker preparation is observable");
            if (mode.equals("pending")) Assertions.fileExists(directory.resolve("TOKENEXPIREDPENDINGexpiry-test-session"), "unresolved previous cold authentication remains visible");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    private void lostMarker() throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-lost-marker-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--lost-marker-child", directory.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(8, TimeUnit.SECONDS), "missing marker cannot leave claimed shutdown hanging");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "recovery cancellation preserves manual control: " + log);
            Assertions.contains(log, "EXPIRY_LOST_MARKER_PASSED", "child survived deadline and acquired manual STOP claim");
            Assertions.equals(1L, log.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)
                    && line.endsWith("|SESSION_TOKEN_EXPIRED")).count(), "recovery was prepared before marker disappeared");
            Assertions.equals(1L, log.lines().filter(line -> line.startsWith(EngineEvents.PREFIX)
                    && line.endsWith("|SESSION_TOKEN_RECOVERY_FAILED")).count(), "lost marker reports exactly one failure");
            Assertions.notContains(log, "terminating current Gateway JVM", "no forced exit without launcher recovery marker");
            Assertions.isFalse(Files.exists(directory.resolve("TOKENEXPIREDexpiry-test-session")), "cancelled recovery does not recreate lost marker");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    private void acknowledgeMarker(String mode) throws Exception {
        Path directory = Files.createTempDirectory("ibc-expiry-ack-");
        Path output = directory.resolve("child-output.txt");
        Process process = null;
        try {
            process = new ProcessBuilder(TestSupport.javaExecutable().toString(), "-Djava.awt.headless=true",
                    "-classpath", System.getProperty("java.class.path"), ExpiredSessionTokenTests.class.getName(),
                    "--ack-" + mode + "-child", directory.toString())
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            Assertions.isTrue(process.waitFor(8, TimeUnit.SECONDS), "pending marker acknowledgment returns promptly");
            String log = Files.readString(output, StandardCharsets.UTF_8);
            Assertions.equals(0, process.exitValue(), "production authentication transition preserves guard ownership: " + log);
            Assertions.contains(log, "EXPIRY_ACK_PASSED", "actual successful-login acknowledgment assertions completed");
            Assertions.notContains(log, "|SESSION_TOKEN_EXPIRED", "successful authentication does not trigger recovery");
            if (mode.equals("directory")) Assertions.contains(log, "|SESSION_TOKEN_RECOVERY_GUARD_FAILED", "unsafe pending marker cleanup is reported");
            else Assertions.notContains(log, "|SESSION_TOKEN_RECOVERY_", "normal acknowledgment succeeds");
        } finally {
            if (process != null && process.isAlive()) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            TestSupport.deleteTree(directory);
        }
    }

    /** Forked by productionRecovery: no Gateway, API connection or real installation. */
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !List.of("--production-child", "--duplicate-stop-child",
                "--postlogin-warm-child", "--postlogin-fresh-child", "--guard-stop-child", "--guard-pause-child",
                "--guard-marker-child", "--guard-marker-directory-child", "--guard-pending-child",
                "--lost-marker-child", "--ack-success-child", "--ack-directory-child").contains(args[0])) {
            throw new IllegalArgumentException("fixture mode required");
        }
        Path directory = Path.of(args[1]);
        System.setProperty("jtsConfigDir", directory.toString());
        System.setProperty("ibcsessionid", "expiry-test-session");
        if (args[0].equals("--duplicate-stop-child")) {
            var field = StopTask.class.getDeclaredField("_Running"); field.setAccessible(true);
            SwitchLock running = (SwitchLock) field.get(null);
            if (!running.set()) throw new AssertionError("fresh process already stopping");
            new StopTask(null, true, "isolated duplicate fixture").run();
            if (!running.query()) throw new AssertionError("duplicate released another stop's claim");
            System.out.println("EXPIRY_DUPLICATE_STOP_PASSED");
            return;
        }
        Path config = directory.resolve("fixture.ini");
        Files.writeString(config, "LoginDialogDisplayTimeout=60\n", StandardCharsets.ISO_8859_1);
        Settings.initialise(new DefaultSettings(config.toString()));
        new CommandServer(); // Construct shutdown target; do not run or bind any socket.
        if (args[0].equals("--lost-marker-child")) {
            SessionManager.initialise(true); System.clearProperty("restart"); SessionManager.startSession();
            LoginManager.initialise(new DefaultLoginManager());
            SwingUtilities.invokeAndWait(() -> {
                LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGED_IN);
                new ExpiredSessionTokenDialogHandler(500).handleCandidate(() -> true, () -> {
                    try { Files.delete(directory.resolve("TOKENEXPIREDexpiry-test-session")); }
                    catch (java.io.IOException failure) { throw new AssertionError(failure); }
                });
            });
            SwingUtilities.invokeAndWait(() -> { });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (StopTask.shutdownInProgress() && System.nanoTime() < deadline) Thread.sleep(20);
            if (StopTask.shutdownInProgress() || StopTask.expiryRecoveryInProgress())
                throw new AssertionError("lost marker left expiry shutdown claimed");
            if (LoginManager.loginManager().getLoginState() != LoginManager.LoginState.LOGGED_IN)
                throw new AssertionError("cancelled recovery changed actual login state");
            for (Thread worker : Thread.getAllStackTraces().keySet()) {
                if (worker.getName().startsWith("IBC-token-expiry-")) {
                    worker.join(1_000);
                    if (worker.isAlive()) throw new AssertionError("cancelled expiry worker remained active");
                }
            }
            var field = StopTask.class.getDeclaredField("_Running"); field.setAccessible(true);
            if (!((SwitchLock) field.get(null)).set()) throw new AssertionError("manual STOP cannot claim after failed expiry recovery");
            System.out.println("EXPIRY_LOST_MARKER_PASSED");
            MyScheduledExecutorService.getInstance().shutdownNow();
            // Cancellation deliberately leaves the application alive. End only this
            // fixture after its assertions; the ordinary main-window future is shared.
            System.exit(0);
            return;
        }
        if (args[0].startsWith("--ack-")) {
            Path pending = directory.resolve("TOKENEXPIREDPENDINGexpiry-test-session");
            Path other = directory.resolve("TOKENEXPIREDPENDINGother-profile");
            Files.writeString(other, "other profile guard");
            if (args[0].contains("-directory-")) Files.writeString(Files.createDirectory(pending).resolve("keep.txt"), "must not delete recursively");
            else Files.writeString(pending, "pending previous cold login");
            LoginManager.initialise(new DefaultLoginManager());
            SwingUtilities.invokeAndWait(() -> LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGED_IN));
            if (LoginManager.loginManager().getLoginState() != LoginManager.LoginState.LOGGED_IN)
                throw new AssertionError("marker cleanup changed completed authentication");
            if (!Files.readString(other).equals("other profile guard")) throw new AssertionError("another profile guard was changed");
            if (args[0].contains("-directory-")) {
                if (!Files.exists(pending.resolve("keep.txt"))) throw new AssertionError("cleanup deleted unknown directory contents");
            } else if (Files.exists(pending)) throw new AssertionError("successful login retained its pending expiry guard");
            System.out.println("EXPIRY_ACK_PASSED");
            MyScheduledExecutorService.getInstance().shutdownNow();
            return;
        }
        if (args[0].startsWith("--postlogin-")) {
            SessionManager.initialise(true);
            if (args[0].contains("-warm-")) System.setProperty("restart", "fixture-only"); else System.clearProperty("restart");
            SessionManager.startSession(); LoginManager.initialise(new DefaultLoginManager());
            SwingUtilities.invokeAndWait(() -> {
                LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGED_IN);
                if (SessionManager.isRestart() != args[0].contains("-warm-")) throw new AssertionError("incorrect original launch mode");
                System.out.println("POSTLOGIN_TIMEOUT_FIXTURE_READY"); System.out.flush();
                new ExpiredSessionTokenDialogHandler(500).handleCandidate(() -> true);
            });
            new java.util.concurrent.CountDownLatch(1).await();
            return;
        }
        if (args[0].startsWith("--guard-")) {
            productionGuardChild(args[0].substring("--guard-".length(), args[0].length() - "-child".length()), directory);
            return;
        }
        try {
            for (LoginManager.LoginState state : LoginManager.LoginState.values()) {
                if (state != LoginManager.LoginState.LOGGED_OUT && state != LoginManager.LoginState.LOGGED_IN) {
                    excludedProductionState(true, true, state);
                    excludedProductionState(false, true, state);
                }
            }
            excludedProductionState(false, true, LoginManager.LoginState.LOGGED_OUT);
            excludedProductionState(true, false, LoginManager.LoginState.LOGGED_IN);
            excludedProductionState(false, false, LoginManager.LoginState.LOGGED_IN);
            excludedProductionState(true, false, LoginManager.LoginState.LOGGED_OUT);
            System.out.println("EXPIRY_EXCLUSIONS_PASSED");
            System.out.flush();
            SessionManager.initialise(true); System.setProperty("restart", "fixture-only"); SessionManager.startSession();
            LoginManager.initialise(new DefaultLoginManager());
            SwingUtilities.invokeAndWait(() -> {
                JPanel panel = gatewayMessagePanel();
                JPasswordField privateInput = new JPasswordField("fixture-private-password"); panel.add(privateInput);
                new ExpiredSessionTokenDialogHandler().handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(panel));
            });
            SwingUtilities.invokeAndWait(() -> { });
            throw new AssertionError("production cold restart did not halt the isolated process");
        } finally { MyScheduledExecutorService.getInstance().shutdownNow(); }
    }

    private static void productionGuardChild(String mode, Path directory) throws Exception {
        SessionManager.initialise(true); System.setProperty("restart", "fixture-only"); SessionManager.startSession();
        LoginManager.initialise(new DefaultLoginManager());
        SwingUtilities.invokeAndWait(() -> {
            LoginManager.loginManager().setLoginState(LoginManager.LoginState.LOGGED_IN);
            ExpiredSessionTokenDialogHandler handler = new ExpiredSessionTokenDialogHandler(500);
            handler.handleCandidate(() -> true);
            try {
                if (mode.equals("pending")) {
                    Files.writeString(directory.resolve("TOKENEXPIREDPENDINGexpiry-test-session"), "pending previous cold login");
                } else if (mode.equals("marker-directory")) {
                    Files.createDirectory(directory.resolve("TOKENEXPIREDexpiry-test-session"));
                } else if (mode.equals("marker")) {
                    Path notDirectory = directory.resolve("file-in-place-of-settings"); Files.writeString(notDirectory, "fixture");
                    System.setProperty("jtsConfigDir", notDirectory.toString());
                } else {
                    Class<?> type = mode.equals("stop") ? StopTask.class : RestartTask.class;
                    var field = type.getDeclaredField("_Running"); field.setAccessible(true);
                    if (!((SwitchLock) field.get(null)).set()) throw new AssertionError("fixture action could not claim operation");
                }
            } catch (ReflectiveOperationException | java.io.IOException failure) { throw new AssertionError(failure); }
        });
        SwingUtilities.invokeAndWait(() -> { });
        if (mode.startsWith("marker") || mode.equals("pending")) {
            if (StopTask.shutdownInProgress()) throw new AssertionError("marker failure retained shutdown ownership");
            if (LoginManager.loginManager().getLoginState() != LoginManager.LoginState.LOGGED_IN)
                throw new AssertionError("marker failure changed authentication state");
        } else {
            Class<?> type = mode.equals("stop") ? StopTask.class : RestartTask.class;
            var field = type.getDeclaredField("_Running"); field.setAccessible(true);
            if (!((SwitchLock) field.get(null)).query()) throw new AssertionError("expiry stole or cleared the user's operation");
        }
        System.out.println("EXPIRY_GUARD_PASSED_" + mode);
        MyScheduledExecutorService.getInstance().shutdownNow();
    }

    private static void excludedProductionState(boolean restart, boolean gateway, LoginManager.LoginState state) throws Exception {
        SessionManager.initialise(gateway);
        if (restart) System.setProperty("restart", "fixture-only"); else System.clearProperty("restart");
        SessionManager.startSession();
        LoginManager.initialise(new DefaultLoginManager());
        SwingUtilities.invokeAndWait(() -> {
            LoginManager.loginManager().setLoginState(state);
            JPanel panel = gatewayMessagePanel();
            new ExpiredSessionTokenDialogHandler().handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(panel));
        });
        SwingUtilities.invokeAndWait(() -> { });
        if (StopTask.shutdownInProgress()) throw new AssertionError("excluded state requested shutdown");
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }

    private static final class Decision {
        final RecordingContext context = new RecordingContext();
        final List<Runnable> queue = new ArrayList<>();
        final ExpiredSessionTokenDialogHandler handler = new ExpiredSessionTokenDialogHandler(context, queue::add);
        JPanel panel;
        Decision() throws Exception { onEdt(() -> panel = gatewayMessagePanel()); }
        void recognize() throws Exception { onEdt(() -> handler.handleCandidate(() -> ExpiredSessionTokenDialogHandler.containsExpiry(panel))); }
        void flush() throws Exception {
            onEdt(() -> { List<Runnable> copy = List.copyOf(queue); queue.clear(); copy.forEach(Runnable::run); });
        }
    }

    private static final class RecordingContext implements ExpiredSessionTokenDialogHandler.Context {
        DefaultLoginManager owner = new DefaultLoginManager();
        boolean eligible = true;
        int restarts;
        @Override public boolean isEligible() { return eligible; }
        @Override public Object loginOwner() { return owner; }
        @Override public long loginGeneration() { return owner.loginGeneration(); }
        @Override public void requestColdRestart(Runnable dismissDialog) { restarts++; }
    }

    private static JPanel panel(java.awt.Component message) {
        JPanel panel = new JPanel(); panel.add(message); panel.add(new JButton("OK")); return panel;
    }

    private static final class PrivateText extends JTextField {
        private static final long serialVersionUID = 1L;
        boolean armed;
        @Override public String getText() { if (armed) throw new AssertionError("editable input was read"); return super.getText(); }
        @Override public javax.swing.text.Document getDocument() {
            if (armed) throw new AssertionError("editable document was read"); return super.getDocument();
        }
    }
    @SuppressWarnings("deprecation")
    private static final class PrivatePassword extends JPasswordField {
        private static final long serialVersionUID = 1L;
        boolean armed;
        @Override public String getText() { if (armed) throw new AssertionError("password input was read"); return super.getText(); }
        @Override public javax.swing.text.Document getDocument() {
            if (armed) throw new AssertionError("password document was read"); return super.getDocument();
        }
    }
    private static final class PrivateLabel extends JLabel {
        private static final long serialVersionUID = 1L;
        boolean armed;
        PrivateLabel(String text) { super(text); }
        @Override public String getText() { if (armed) throw new AssertionError("hidden label was read"); return super.getText(); }
    }
}
