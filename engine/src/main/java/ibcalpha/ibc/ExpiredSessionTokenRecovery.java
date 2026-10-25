// IBC Manager integration; GPL-3.0-or-later, like the upstream engine.
package ibcalpha.ibc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

/** One confirmed token-expiry recovery, confined to the current Gateway JVM. */
final class ExpiredSessionTokenRecovery {
    private ExpiredSessionTokenRecovery() { }

    static void start(boolean establishedSession, Runnable dismissDialog, long timeoutMillis) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Token recovery claim outside EDT");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Nonpositive graceful shutdown deadline");
        if (!StopTask.claimExpiryRecovery()) return;

        Path marker = null;
        boolean markerCreated = false;
        try {
            marker = markerPath("TOKENEXPIRED");
            try {
                Files.readAttributes(markerPath("TOKENEXPIREDPENDING"), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                throw new IOException("Previous token recovery has not completed");
            } catch (NoSuchFileException noPendingRecovery) {
                // Only successful replacement login clears the wrapper's guard.
            }
            // CREATE_NEW prevents silently treating a directory, symlink or
            // leftover request as a successfully prepared new recovery.
            Files.createFile(marker);
            markerCreated = true;
            LoginManager.loginManager().cancelSecondFactorRetry();

            if (!establishedSession) {
                EngineEvents.emit("LOGIN_RESTART_TOKEN_EXPIRED");
                Utils.logToConsole("Session token expired; restarting for fresh authentication");
                CommandServer.commandServer().shutdown();
                Utils.logToConsole("Login has not completed: exiting immediately");
                Runtime.getRuntime().halt(0);
                return;
            }

            Path preparedMarker = marker;
            CountDownLatch dialogContinuation = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean();
            Thread close = new Thread(() -> closeEstablishedSession(dialogContinuation, cancelled), "IBC-token-expiry-close");
            Thread deadline = new Thread(() -> awaitDeadline(preparedMarker, timeoutMillis, close, cancelled), "IBC-token-expiry-deadline");
            deadline.setDaemon(true);
            close.setDaemon(true);
            // Arm the independent deadline before either a worker dispatch or
            // modal button callback can fail/block. It is never cancelled on
            // menu acceptance alone: only actual JVM exit proves completion.
            deadline.start();
            EngineEvents.emit("SESSION_TOKEN_EXPIRED");
            Utils.logToConsole("Session token expired; restarting for fresh authentication");
            try {
                close.start();
            } catch (RuntimeException unavailableWorker) {
                Utils.logError("Expired-token graceful shutdown worker unavailable; cold-restart deadline remains active");
            }
            // If OK opens a new modal synchronously, its nested event loop will
            // execute this continuation. Never wait for that modal's dismissal
            // before attempting File/Close. The normal return path also releases it.
            SwingUtilities.invokeLater(dialogContinuation::countDown);
            try {
                dismissDialog.run();
            } catch (RuntimeException unavailableDialog) {
                Utils.logError("Expired-token dialog dismissal failed; cold-restart deadline remains active");
            } finally {
                dialogContinuation.countDown();
            }
        } catch (IOException | RuntimeException preparationFailure) {
            if (markerCreated) {
                try { Files.deleteIfExists(marker); }
                catch (IOException | RuntimeException cleanupFailure) {
                    Utils.logError("Expired-token request marker could not be removed after preparation failure");
                }
            }
            StopTask.releaseExpiryRecovery();
            EngineEvents.emit("SESSION_TOKEN_RECOVERY_FAILED");
            Utils.logError("Expired-token cold restart could not be prepared; manual restart is required");
        }
    }

    private static void closeEstablishedSession(CountDownLatch dialogContinuation, AtomicBoolean cancelled) {
        try {
            dialogContinuation.await();
            // Do not falsify LoginState to select StopTask's immediate-halt
            // branch. The established session receives its normal File/Close.
            JFrame window = MainWindowManager.mainWindowManager().getMainWindow();
            while (!cancelled.get() && StopTask.expiryRecoveryInProgress()) {
                FutureTask<Integer> attempt = new FutureTask<>(() -> {
                    // A queued GUI action must not outlive an aborted recovery.
                    if (cancelled.get() || !StopTask.expiryRecoveryInProgress()) return 1;
                    JMenuItem close = SwingUtils.findMenuItemInAnyMenuBar(window, new String[] {"File", "Close"});
                    if (close == null) return -1;
                    if (!close.isEnabled()) return 0;
                    close.doClick();
                    return 1;
                });
                SwingUtilities.invokeLater(attempt);
                int result;
                try { result = attempt.get(); }
                catch (InterruptedException interrupted) { attempt.cancel(false); throw interrupted; }
                if (result > 0) return;
                if (result < 0) {
                    Utils.logError("Expired-token graceful close control unavailable; cold-restart deadline remains active");
                    return;
                }
                TimeUnit.MILLISECONDS.sleep(250);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RuntimeException closeFailure) {
            Utils.logError("Expired-token graceful shutdown failed; cold-restart deadline remains active");
        }
    }

    private static void awaitDeadline(Path marker, long timeoutMillis, Thread close, AtomicBoolean cancelled) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long started = System.nanoTime();
        while (remaining > 0) {
            try { TimeUnit.NANOSECONDS.sleep(remaining); }
            catch (InterruptedException interrupted) { /* An interrupt cannot silently disable a claimed recovery. */ }
            remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - started);
        }
        // The wrapper needs this exact marker to guarantee fresh authentication.
        // If it disappeared or is no longer a regular file, report failure
        // without forcing exit. This does not authenticate file identity.
        if (!StopTask.expiryRecoveryInProgress()) return;
        boolean markerAvailable;
        try { markerAvailable = Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS); }
        catch (RuntimeException unavailableMarker) { markerAvailable = false; }
        if (!markerAvailable) {
            cancelled.set(true);
            close.interrupt();
            StopTask.releaseExpiryRecovery();
            EngineEvents.emit("SESSION_TOKEN_RECOVERY_FAILED");
            Utils.logError("Expired-token restart marker is unavailable; current Gateway JVM was not forcibly stopped");
            return;
        }
        Utils.logToConsole("Expired-token cold restart: graceful shutdown deadline reached; terminating current Gateway JVM");
        Runtime.getRuntime().halt(0);
    }

    static void acknowledgeSuccessfulLogin() {
        if (System.getProperty("jtsConfigDir", "").isBlank() || System.getProperty("ibcsessionid", "").isBlank()) return;
        try {
            Path marker = markerPath("TOKENEXPIREDPENDING");
            BasicFileAttributes attributes = Files.readAttributes(marker, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) throw new IOException("Invalid recovery completion marker");
            Files.delete(marker);
            Utils.logToConsole("Expired-token cold restart completed: login successful");
        } catch (NoSuchFileException noPendingRecovery) {
            // Ordinary fresh login / daily warm restart has no recovery marker.
        } catch (IOException | RuntimeException cleanupFailure) {
            EngineEvents.emit("SESSION_TOKEN_RECOVERY_GUARD_FAILED");
            Utils.logError("Expired-token recovery completion marker could not be cleared; manual review is required");
        }
    }

    private static Path markerPath(String prefix) throws IOException {
        String directory = System.getProperty("jtsConfigDir", "");
        String session = System.getProperty("ibcsessionid", "");
        if (directory.isBlank() || !session.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IOException("Missing or invalid token recovery location");
        }
        return Path.of(directory).resolve(prefix + session);
    }
}
