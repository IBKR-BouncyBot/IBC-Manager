package ibcalpha.ibc;

import java.io.PrintStream;
import java.io.OutputStream;

/** Fresh-JVM fixture for engine logging, which intentionally caches the inherited stdout. */
public final class EngineProbe {
    private EngineProbe() { }
    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("mode config.ini required");
        Settings.initialise(new DefaultSettings(args[1]));
        if (args[0].equals("redaction")) return;
        if (!args[0].equals("events")) throw new IllegalArgumentException("unknown probe");
        EngineEvents.emit("ENGINE_STARTED");
        // Simulate Gateway redirecting System.out after startup. Events must still reach the parent.
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        DefaultLoginManager provider = new DefaultLoginManager();
        provider.setLoginState(LoginManager.LoginState.LOGGING_IN);
        provider.setLoginState(LoginManager.LoginState.TWO_FA_IN_PROGRESS);
        provider.setLoginState(LoginManager.LoginState.LOGGED_IN);
        provider.setLoginState(LoginManager.LoginState.LOGGED_IN);
    }
}
