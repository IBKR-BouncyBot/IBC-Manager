package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.util.List;

/** Exercises the actual Manager parser, without changing its ordinary login-wait watchdog policy. */
public final class ExpiredTokenEventTests implements TestSuite {
    private static final String GENERATION = "54a88933-3afb-4a27-8b66-27dcddd72f57";
    private static final String NEXT_GENERATION = "120c6e0f-a13a-4141-a6a1-dccbb642737c";
    private static final String MESSAGE = "Session token expired; restarting for fresh authentication";
    private static final String EVENT = "LOGIN_RESTART_TOKEN_EXPIRED";
    private static final String SESSION_EVENT = "SESSION_TOKEN_EXPIRED";
    private static final String FAILURE_EVENT = "SESSION_TOKEN_RECOVERY_FAILED";
    private static final String GUARD_FAILURE_EVENT = "SESSION_TOKEN_RECOVERY_GUARD_FAILED";

    @Override public String name() { return "Expired session token: startup and established-session Manager lifecycle"; }

    @Override public List<NamedTest> tests() {
        return List.of(
                new NamedTest("recorded stalled-login prefix gains an explicit queued recovery state", this::recordedPrefix),
                new NamedTest("queued recovery retains its reason through old-child login and exit output", this::recoveryReason),
                new NamedTest("replacement child resumes normal credentials, 2FA and successful login", this::replacement),
                new NamedTest("duplicate and out-of-order expiry events do not change the pending observation", this::duplicates),
                new NamedTest("foreign, retired and malformed events cannot claim recovery", this::invalidEvents),
                new NamedTest("invalid twsInfo takes precedence before and after expiry", this::rejectionPrecedence),
                new NamedTest("normal wrapper completion ends pending recovery", this::wrapperCompletion),
                new NamedTest("completed login is not overridden by a late expiry event", this::completedLogin),
                new NamedTest("active 2FA is not overridden by an expiry event", this::activeSecondFactor),
                new NamedTest("an already exited child cannot newly announce expiry recovery", this::exitedChild),
                new NamedTest("raw expiry prose alone does not authorize recovery", this::rawText),
                new NamedTest("command-server lifecycle still updates while recovery is pending", this::commandServer),
                new NamedTest("manual lifecycle resets clear the expired-token latch", this::resets),
                new NamedTest("October 25 established-session expiry invalidates running readiness", this::recordedEstablishedSession),
                new NamedTest("established-session expiry requires completed login and the main window", this::establishedRequiresReadyLogin),
                new NamedTest("established-session expiry does not interrupt a new login or second factor", this::establishedAuthenticationProgress),
                new NamedTest("established-session expiry cannot restart an exited stopped paused or closing child", this::establishedTerminalStates),
                new NamedTest("established-session expiry rejects malformed foreign and stale events", this::establishedInvalidEvents),
                new NamedTest("established-session expiry is claimed once across duplicate notifications", this::establishedDuplicates),
                new NamedTest("established-session recovery retains its reason through old-child output", this::establishedReason),
                new NamedTest("established-session replacement reaches fresh login and rejects retired events", this::establishedReplacement),
                new NamedTest("invalid twsInfo remains authoritative around established-session recovery", this::establishedRejectionPrecedence),
                new NamedTest("old child cannot restore readiness after expiry wrapper completion", this::establishedCompletion),
                new NamedTest("manual reset retires an established-session expiry generation", this::establishedReset),
                new NamedTest("normal scheduled warm restart does not acquire an expiry reason", this::ordinaryWarmRestart),
                new NamedTest("cold-restart preparation failure is actionable before either expiry event", this::preparationFailure),
                new NamedTest("cold-restart preparation failure ends an already announced recovery", this::pendingPreparationFailure),
                new NamedTest("recovery failure survives old-child output and normal wrapper completion", this::failureReason),
                new NamedTest("recovery failure ignores stale malformed terminal and active-2FA events", this::failureIsolation),
                new NamedTest("recovery failure clears only for replacement or explicit reset", this::failureReset),
                new NamedTest("invalid twsInfo outranks a token recovery preparation failure", this::failureRejectionPrecedence),
                new NamedTest("successful authentication guard-clear failure remains visible after the login event", this::guardFailureAtLogin),
                new NamedTest("guard-clear failure requires the current nonterminal application and main window", this::guardFailureIsolation),
                new NamedTest("guard-clear failure clears on real replacement and survives old-child noise", this::guardFailureLifecycle),
                new NamedTest("invalid twsInfo takes precedence over guard-clear failure", this::guardFailureRejection));
    }

    private void recordedPrefix() {
        IbcLogStateParser parser = waiting();
        // Sanitized relevant lines from the 2026-10-18 incident. The original log contained no expiry event.
        parser.accept(event(GENERATION, 4, "MAIN_WINDOW_READY"));
        parser.accept("2026-10-18 10:02:16:131 IBC: Login dialog WINDOW_OPENED: LoginState is LOGGED_OUT");
        parser.accept("2026-10-18 10:02:16:250 IBC: detected dialog entitled: Gateway; event=Opened");
        parser.accept("2026-10-18 10:02:16:252 IBC: GATEWAY");
        Assertions.equals(RuntimeState.WAITING_FOR_LOGIN, parser.latest().orElseThrow().state(), "recorded prefix stays waiting");
        Assertions.isFalse(parser.restartPending(), "the old log does not itself request recovery");
        parser.accept(event(GENERATION, 5, EVENT));
        assertRecovery(parser);
        Assertions.isFalse(parser.childExitObserved(), "queued stop does not claim the child has exited");
        Assertions.isFalse(parser.normalExitConfirmed(), "no wrapper completion yet");
        Assertions.isFalse(parser.errorExitConfirmed(), "expected credential expiry is not an error exit");
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(), "command server remains observed open");
    }

    private void recoveryReason() {
        IbcLogStateParser parser = recovering();
        for (String noise : List.of(
                "IBC: Login dialog WINDOW_OPENED: LoginState is LOGGED_OUT",
                "IBC: Login has completed",
                "IBC: detected dialog entitled: Second Factor Authentication; event=Closed",
                event(GENERATION, 5, "LOGIN_LOGGED_OUT"),
                event(GENERATION, 6, "LOGIN_LOGGED_IN"),
                event(GENERATION, 7, "LOGIN_TWO_FA_IN_PROGRESS"),
                event(GENERATION, 8, "MAIN_WINDOW_READY"),
                "Program has exited",
                "IBC will cold-restart shortly")) {
            parser.accept(noise);
            assertRecovery(parser);
        }
        Assertions.isTrue(parser.childExitObserved(), "actual child-exit output is still tracked");
    }

    private void replacement() {
        IbcLogStateParser parser = recovering();
        parser.accept("Program has exited");
        parser.accept("IBC will cold-restart shortly");
        parser.accept("Starting IBC with this command:");
        Assertions.isFalse(parser.restartPending(), "replacement clears pending restart");
        Assertions.isFalse(parser.childExitObserved(), "replacement clears old child exit");
        Assertions.isTrue(parser.latest().isEmpty(), "replacement clears expiry reason");
        parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
        parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
        parser.accept(event(NEXT_GENERATION, 3, "LOGIN_AWAITING_CREDENTIALS"));
        Assertions.equals(RuntimeState.WAITING_FOR_LOGIN, parser.latest().orElseThrow().state(), "fresh credential login");
        parser.accept(event(NEXT_GENERATION, 4, "LOGIN_TWO_FA_IN_PROGRESS"));
        Assertions.equals(RuntimeState.WAITING_FOR_SECOND_FACTOR, parser.latest().orElseThrow().state(), "fresh 2FA prompt");
        parser.accept(event(NEXT_GENERATION, 5, "SECOND_FACTOR_RETRY_ARMED"));
        Assertions.contains(parser.latest().orElseThrow().message(), "retry timer is armed", "existing 2FA retry remains visible");
        parser.accept(event(NEXT_GENERATION, 6, "MAIN_WINDOW_READY"));
        parser.accept(event(NEXT_GENERATION, 7, "LOGIN_LOGGED_IN"));
        Assertions.equals(RuntimeState.RUNNING, parser.latest().orElseThrow().state(), "fresh login completion");
        Assertions.isTrue(parser.mainWindowReady(), "fresh child is ready for commands after login");
    }

    private void duplicates() {
        IbcLogStateParser parser = recovering();
        IbcLogStateParser.StateHint original = parser.latest().orElseThrow();
        for (long sequence : List.of(4L, 3L, 5L, 6L)) {
            Assertions.isTrue(parser.accept(event(GENERATION, sequence, EVENT)).isEmpty(), "duplicate does not announce another recovery");
            Assertions.equals(original, parser.latest().orElseThrow(), "duplicate does not refresh the reason or observation time");
        }
    }

    private void invalidEvents() {
        IbcLogStateParser parser = waiting();
        IbcLogStateParser.StateHint original = parser.latest().orElseThrow();
        for (String invalid : List.of(
                event(NEXT_GENERATION, 4, EVENT),
                event(GENERATION, 3, EVENT),
                event(GENERATION, 0, EVENT),
                "IBC_MANAGER_EVENT|2|" + GENERATION + "|4|" + EVENT,
                "IBC_MANAGER_EVENT|1|not-a-uuid|4|" + EVENT,
                "IBC_MANAGER_EVENT|1|" + GENERATION + "|NaN|" + EVENT,
                event(GENERATION, 4, EVENT) + "|unexpected-payload",
                event(GENERATION, 4, "LOGIN_RESTART_TOKEN_EXPIRED_UNKNOWN"))) {
            Assertions.isTrue(parser.accept(invalid).isEmpty(), "reject invalid event framing or generation");
            Assertions.equals(original, parser.latest().orElseThrow(), "invalid event cannot alter state");
            Assertions.isFalse(parser.restartPending(), "invalid event cannot set pending restart");
        }
        parser.accept("Starting IBC with this command:");
        parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
        Assertions.isTrue(parser.accept(event(GENERATION, 4, EVENT)).isEmpty(), "retired child cannot affect replacement");
        Assertions.equals(RuntimeState.STARTING, parser.latest().orElseThrow().state(), "replacement keeps its current status");
    }

    private void rejectionPrecedence() {
        IbcLogStateParser first = waiting();
        first.accept(event(GENERATION, 4, "LOGIN_REJECTED_TWSINFO"));
        first.accept(event(GENERATION, 5, EVENT));
        assertRejected(first);
        Assertions.isFalse(first.restartPending(), "expiry cannot claim recovery after startup rejection");

        IbcLogStateParser second = recovering();
        second.accept(event(GENERATION, 5, "LOGIN_REJECTED_TWSINFO"));
        second.accept("IBC will cold-restart shortly");
        second.accept(event(GENERATION, 6, "LOGIN_LOGGED_IN"));
        assertRejected(second);

        IbcLogStateParser third = recovering();
        third.accept("IBC: Gateway/TWS login rejected: invalid twsInfo.");
        third.accept("Normal exit");
        assertRejected(third);
    }

    private void wrapperCompletion() {
        for (String footer : List.of("Normal exit", "Gateway finished at 10:02", "TWS finished at 10:02")) {
            IbcLogStateParser parser = recovering();
            parser.accept(footer);
            Assertions.isFalse(parser.restartPending(), "actual wrapper completion ends pending restart");
            Assertions.isTrue(parser.normalExitConfirmed(), "wrapper completion is tracked");
            Assertions.equals(RuntimeState.STOPPED, parser.latest().orElseThrow().state(), "wrapper completion remains authoritative");
            Assertions.isTrue(parser.accept(event(GENERATION, 5, EVENT)).isEmpty(), "old child cannot reannounce after completion");
        }
    }

    private void completedLogin() {
        IbcLogStateParser parser = waiting();
        parser.accept(event(GENERATION, 4, "MAIN_WINDOW_READY"));
        parser.accept(event(GENERATION, 5, "LOGIN_LOGGED_IN"));
        Assertions.isTrue(parser.accept(event(GENERATION, 6, EVENT)).isEmpty(), "late expiry cannot restart a completed login");
        Assertions.equals(RuntimeState.RUNNING, parser.latest().orElseThrow().state(), "completed login preserved");
        Assertions.isTrue(parser.mainWindowReady(), "normal readiness preserved");
        Assertions.isFalse(parser.restartPending(), "no pending restart");
    }

    private void activeSecondFactor() {
        for (String secondFactor : List.of(event(GENERATION, 4, "LOGIN_TWO_FA_IN_PROGRESS"),
                "IBC: Second factor authentication initiated")) {
            IbcLogStateParser parser = waiting();
            parser.accept(secondFactor);
            Assertions.isTrue(parser.accept(event(GENERATION, 5, EVENT)).isEmpty(), "active 2FA is not interrupted");
            Assertions.equals(RuntimeState.WAITING_FOR_SECOND_FACTOR, parser.latest().orElseThrow().state(), "2FA waiting preserved");
            Assertions.isFalse(parser.restartPending(), "no restart during active 2FA");
        }
    }

    private void exitedChild() {
        IbcLogStateParser parser = waiting();
        parser.accept("Program has exited");
        Assertions.isTrue(parser.accept(event(GENERATION, 4, EVENT)).isEmpty(), "exited child cannot newly claim queued work");
        Assertions.isFalse(parser.restartPending(), "wrapper still determines the next action");
    }

    private void rawText() {
        IbcLogStateParser parser = waiting();
        for (String raw : List.of(
                "Session expired",
                "Authorization failed: <html>The security tokens associated with your login credentials have expired<br>(routinely) in accordance with our security protocols. Please manually<br>enter your username and password to access IBKR Gateway.</html>",
                "Session token expired; restarting for fresh authentication",
                "Reading ColdRestartTime=", "IBC: GATEWAY")) {
            parser.accept(raw);
            Assertions.isFalse(parser.restartPending(), "plain diagnostic text is not a recovery command");
        }
        Assertions.equals(RuntimeState.WAITING_FOR_LOGIN, parser.latest().orElseThrow().state(), "ordinary login waiting remains unchanged");
    }

    private void commandServer() {
        IbcLogStateParser parser = recovering();
        parser.accept(event(GENERATION, 5, "COMMAND_SERVER_CLOSED"));
        Assertions.equals(IbcLogStateParser.CommandServerState.CLOSED, parser.commandServerState(), "closure still tracked");
        assertRecovery(parser);
        parser.accept(event(GENERATION, 6, "COMMAND_SERVER_READY"));
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(), "readiness still tracked");
        assertRecovery(parser);
    }

    private void resets() {
        for (boolean complete : List.of(false, true)) {
            IbcLogStateParser parser = recovering();
            if (complete) parser.reset(); else parser.resetSessionState();
            Assertions.isFalse(parser.restartPending(), "manual reset clears recovery");
            Assertions.isTrue(parser.latest().isEmpty(), "manual reset clears old reason");
            parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
            parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
            parser.accept(event(NEXT_GENERATION, 3, EVENT));
            assertRecovery(parser);
        }
    }

    private void recordedEstablishedSession() {
        IbcLogStateParser parser = established();
        long generation = parser.sessionGeneration();
        // Sanitized sequence from the October 25 incident. No new child or
        // LOGGED_OUT event occurred between Saturday login and Sunday rejection.
        parser.accept("2026-10-24 19:47:22:617 IBC: Login has completed");
        parser.accept("2026-10-25 06:25:47:169 IBC: detected frame entitled: Connecting to server...; event=Opened");
        parser.accept("2026-10-25 06:29:44:146 IBC: detected dialog entitled: IBKR Gateway; event=Opened");
        parser.accept("2026-10-25 06:29:44:148 IBC: IBKR GATEWAY");
        Assertions.isTrue(parser.loginCompleted(), "recorded reconnect did not emit a logout transition");
        Assertions.isTrue(parser.mainWindowReady(), "the old recorded readiness remains until confirmed expiry");
        Assertions.isFalse(parser.restartPending(), "window titles alone do not authorize recovery");
        parser.accept(event(GENERATION, 6, SESSION_EVENT));
        assertRecovery(parser);
        Assertions.equals(generation, parser.sessionGeneration(), "an event does not invent a replacement generation");
        Assertions.isFalse(parser.childExitObserved(), "recovery request does not invent an exit");
        Assertions.isFalse(parser.errorExitConfirmed(), "confirmed expiry is not a startup rejection");
        Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(), "observed command port state retained");
    }

    private void establishedRequiresReadyLogin() {
        for (String readiness : List.of("neither", "main-only", "login-only")) {
            IbcLogStateParser parser = waiting();
            if (readiness.equals("main-only")) parser.accept(event(GENERATION, 4, "MAIN_WINDOW_READY"));
            if (readiness.equals("login-only")) parser.accept(event(GENERATION, 4, "LOGIN_LOGGED_IN"));
            IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
            Assertions.isTrue(parser.accept(event(GENERATION, 5, SESSION_EVENT)).isEmpty(), "established event requires both readiness observations: " + readiness);
            Assertions.equals(before, parser.latest().orElseThrow(), "unqualified event preserves current state");
            Assertions.isFalse(parser.restartPending(), "unqualified event does not request recovery");
        }
    }

    private void establishedAuthenticationProgress() {
        for (String progress : List.of("LOGIN_LOGGING_IN", "LOGIN_AWAITING_CREDENTIALS", "LOGIN_LOGGED_OUT",
                "LOGIN_TWO_FA_IN_PROGRESS", "LOGIN_LOGIN_FAILED")) {
            IbcLogStateParser parser = established();
            parser.accept(event(GENERATION, 6, progress));
            IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
            Assertions.isTrue(parser.accept(event(GENERATION, 7, SESSION_EVENT)).isEmpty(), "new authentication cancels old established state: " + progress);
            Assertions.equals(before, parser.latest().orElseThrow(), "authentication state retained");
            Assertions.isFalse(parser.restartPending(), "no expiry restart while authentication is underway");
        }
        IbcLogStateParser parser = established();
        parser.accept("IBC: Second factor authentication initiated");
        Assertions.isTrue(parser.accept(event(GENERATION, 6, SESSION_EVENT)).isEmpty(), "raw observed second-factor wait is protected too");
        Assertions.equals(RuntimeState.WAITING_FOR_SECOND_FACTOR, parser.latest().orElseThrow().state(), "second-factor status retained");
    }

    private void establishedTerminalStates() {
        for (String terminal : List.of("Program has exited", "Normal exit", "Gateway finished at 09:15:25",
                "IBC is paused", "IBC: CommandServer closing", "IBC: Exiting after error with exit code=1",
                "IBC: Login failed", "IBC will autorestart shortly")) {
            IbcLogStateParser parser = established();
            parser.accept(terminal);
            IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
            boolean pending = parser.restartPending();
            Assertions.isTrue(parser.accept(event(GENERATION, 6, SESSION_EVENT)).isEmpty(), "terminal/closing observation blocks expiry: " + terminal);
            Assertions.equals(before, parser.latest().orElseThrow(), "existing lifecycle outcome retained");
            Assertions.equals(pending, parser.restartPending(), "new event cannot alter the existing restart decision");
        }
        IbcLogStateParser closed = established();
        closed.accept(event(GENERATION, 6, "COMMAND_SERVER_CLOSED"));
        Assertions.isTrue(closed.accept(event(GENERATION, 7, SESSION_EVENT)).isEmpty(), "versioned command shutdown also blocks late expiry");
        Assertions.isFalse(closed.restartPending(), "late event cannot restart a closing child");
    }

    private void establishedInvalidEvents() {
        IbcLogStateParser parser = established();
        IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
        for (String invalid : List.of(event(NEXT_GENERATION, 6, SESSION_EVENT),
                event(GENERATION, 5, SESSION_EVENT), event(GENERATION, 0, SESSION_EVENT),
                "IBC_MANAGER_EVENT|2|" + GENERATION + "|6|" + SESSION_EVENT,
                "IBC_MANAGER_EVENT|1|not-a-uuid|6|" + SESSION_EVENT,
                "IBC_MANAGER_EVENT|1|" + GENERATION + "|NaN|" + SESSION_EVENT,
                event(GENERATION, 6, SESSION_EVENT) + "|unexpected-payload",
                event(GENERATION, 6, "SESSION_TOKEN_EXPIRED_UNKNOWN"))) {
            Assertions.isTrue(parser.accept(invalid).isEmpty(), "reject noncurrent established event");
            Assertions.equals(before, parser.latest().orElseThrow(), "invalid event cannot change readiness");
            Assertions.isFalse(parser.restartPending(), "invalid event cannot request a restart");
        }
        parser.accept(event(GENERATION, 6, SESSION_EVENT));
        assertRecovery(parser);
    }

    private void establishedDuplicates() {
        IbcLogStateParser parser = establishedRecovering();
        IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
        for (long sequence : List.of(6L, 5L, 7L, 8L)) {
            Assertions.isTrue(parser.accept(event(GENERATION, sequence, SESSION_EVENT)).isEmpty(), "duplicate cannot reclaim established recovery");
            Assertions.equals(before, parser.latest().orElseThrow(), "duplicate cannot refresh the recovery observation");
        }
        Assertions.isTrue(parser.accept(event(GENERATION, 9, EVENT)).isEmpty(), "startup event cannot reclassify committed recovery");
        assertRecovery(parser);
    }

    private void establishedReason() {
        IbcLogStateParser parser = establishedRecovering();
        for (String noise : List.of("IBC: Login has completed", "IBC: Login dialog WINDOW_OPENED: LoginState is LOGGED_OUT",
                "IBC: detected dialog entitled: Second Factor Authentication; event=Closed",
                event(GENERATION, 7, "LOGIN_LOGGING_IN"), event(GENERATION, 8, "LOGIN_LOGGED_IN"),
                event(GENERATION, 9, "MAIN_WINDOW_READY"), event(GENERATION, 10, "LOGIN_TWO_FA_IN_PROGRESS"),
                "Program has exited", "IBC will cold-restart shortly")) {
            parser.accept(noise);
            assertRecovery(parser);
        }
        Assertions.isTrue(parser.childExitObserved(), "actual old-child exit is recorded");
        parser.accept(event(GENERATION, 11, "COMMAND_SERVER_CLOSED"));
        Assertions.equals(IbcLogStateParser.CommandServerState.CLOSED, parser.commandServerState(), "old command close remains observable");
        assertRecovery(parser);
    }

    private void establishedReplacement() {
        IbcLogStateParser parser = establishedRecovering();
        parser.accept("Program has exited");
        parser.accept("IBC will cold-restart shortly");
        parser.accept("Starting IBC with this command:");
        Assertions.isFalse(parser.restartPending(), "actual replacement clears pending recovery");
        Assertions.isFalse(parser.childExitObserved(), "replacement clears old exit");
        parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
        parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
        parser.accept(event(NEXT_GENERATION, 3, "LOGIN_AWAITING_CREDENTIALS"));
        Assertions.isTrue(parser.accept(event(GENERATION, 7, SESSION_EVENT)).isEmpty(), "old generation cannot interrupt new credentials");
        Assertions.isTrue(parser.accept(event(GENERATION, 8, "ENGINE_STARTED")).isEmpty(), "old engine cannot replace current generation");
        Assertions.equals(RuntimeState.WAITING_FOR_LOGIN, parser.latest().orElseThrow().state(), "fresh credentials remain visible");
        parser.accept(event(NEXT_GENERATION, 4, "LOGIN_TWO_FA_IN_PROGRESS"));
        Assertions.equals(RuntimeState.WAITING_FOR_SECOND_FACTOR, parser.latest().orElseThrow().state(), "fresh authentication requires normal second factor");
        parser.accept(event(NEXT_GENERATION, 5, "MAIN_WINDOW_READY"));
        parser.accept(event(NEXT_GENERATION, 6, "LOGIN_LOGGED_IN"));
        Assertions.isTrue(parser.mainWindowReady(), "replacement becomes ready only after fresh successful login");
        Assertions.isFalse(parser.restartPending(), "no lingering recovery flag");
        Assertions.isFalse(parser.errorExitConfirmed(), "clean fresh authentication has no recovery-guard failure");
    }

    private void establishedRejectionPrecedence() {
        IbcLogStateParser first = established();
        first.accept(event(GENERATION, 6, "LOGIN_REJECTED_TWSINFO"));
        first.accept(event(GENERATION, 7, SESSION_EVENT));
        assertRejected(first);
        Assertions.isFalse(first.restartPending(), "confirmed server rejection is not token recovery");
        IbcLogStateParser second = establishedRecovering();
        second.accept(event(GENERATION, 7, "LOGIN_REJECTED_TWSINFO"));
        second.accept("IBC will cold-restart shortly");
        second.accept(event(GENERATION, 8, "LOGIN_LOGGED_IN"));
        assertRejected(second);
    }

    private void establishedCompletion() {
        for (String footer : List.of("Normal exit", "Gateway finished at 09:15:25")) {
            IbcLogStateParser parser = establishedRecovering();
            parser.accept(footer);
            IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
            Assertions.equals(RuntimeState.STOPPED, before.state(), "wrapper completion remains authoritative");
            Assertions.isFalse(parser.restartPending(), "wrapper completion ends pending restart");
            Assertions.isTrue(parser.accept(event(GENERATION, 7, SESSION_EVENT)).isEmpty(), "completion cannot trigger another recovery");
            Assertions.isTrue(parser.accept(event(GENERATION, 8, "LOGIN_LOGGED_IN")).isEmpty(), "completed old child cannot restore login readiness");
            Assertions.isTrue(parser.accept(event(GENERATION, 9, "MAIN_WINDOW_READY")).isEmpty(), "completed old child cannot restore its window");
            Assertions.isTrue(parser.accept("IBC: Login has completed").isEmpty(), "delayed raw login output cannot revive a completed wrapper");
            Assertions.isTrue(parser.accept("IBC: Second factor authentication initiated").isEmpty(), "delayed raw challenge output cannot revive a completed wrapper");
            Assertions.equals(before, parser.latest().orElseThrow(), "late old events preserve stopped observation");
            Assertions.isFalse(parser.mainWindowReady(), "completed recovery child stays unavailable");
        }
    }

    private void establishedReset() {
        for (boolean complete : List.of(false, true)) {
            IbcLogStateParser parser = establishedRecovering();
            if (complete) parser.reset(); else parser.resetSessionState();
            Assertions.isFalse(parser.restartPending(), "manual lifecycle reset clears established recovery");
            Assertions.isFalse(parser.mainWindowReady(), "manual reset never preserves old readiness");
            Assertions.isTrue(parser.accept(event(GENERATION, 7, SESSION_EVENT)).isEmpty(), "old event cannot attach itself after manual reset");
            parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
            parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
            parser.accept(event(NEXT_GENERATION, 3, "MAIN_WINDOW_READY"));
            parser.accept(event(NEXT_GENERATION, 4, "LOGIN_LOGGED_IN"));
            parser.accept(event(NEXT_GENERATION, 5, SESSION_EVENT));
            assertRecovery(parser);
        }
    }

    private void ordinaryWarmRestart() {
        IbcLogStateParser parser = established();
        parser.accept("Program has exited");
        parser.accept("IBC will autorestart shortly");
        Assertions.equals(RuntimeState.RESTARTING, parser.latest().orElseThrow().state(), "normal warm restart remains a restart");
        Assertions.isFalse(parser.latest().orElseThrow().message().contains("expired"), "ordinary warm restart is not labelled expiry");
        parser.accept("Starting IBC with this command:");
        parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
        parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
        parser.accept(event(NEXT_GENERATION, 3, "MAIN_WINDOW_READY"));
        parser.accept(event(NEXT_GENERATION, 4, "LOGIN_LOGGED_IN"));
        Assertions.isTrue(parser.mainWindowReady(), "successful warm restart restores normal readiness");
        Assertions.equals(RuntimeState.RUNNING, parser.latest().orElseThrow().state(), "warm restart completes normally");
        Assertions.isFalse(parser.restartPending(), "warm restart leaves no recovery pending");
    }

    private void preparationFailure() {
        for (boolean loggedIn : List.of(false, true)) {
            IbcLogStateParser parser = loggedIn ? established() : waiting();
            long generation = parser.sessionGeneration();
            parser.accept(event(GENERATION, 6, FAILURE_EVENT));
            assertRecoveryFailed(parser);
            Assertions.isFalse(parser.childExitObserved(), "preparation failure leaves observed child liveness unchanged");
            Assertions.isFalse(parser.normalExitConfirmed(), "preparation failure does not invent wrapper completion");
            Assertions.equals(generation, parser.sessionGeneration(), "preparation failure does not invent a replacement");
            Assertions.equals(IbcLogStateParser.CommandServerState.OPEN, parser.commandServerState(), "existing command server observation retained");
            IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
            Assertions.isTrue(parser.accept(event(GENERATION, 7, FAILURE_EVENT)).isEmpty(), "duplicate failure is not another action");
            Assertions.equals(before, parser.latest().orElseThrow(), "duplicate failure preserves its original timestamp");
        }
    }

    private void pendingPreparationFailure() {
        for (boolean loggedIn : List.of(false, true)) {
            IbcLogStateParser parser = loggedIn ? establishedRecovering() : recovering();
            parser.accept(event(GENERATION, 7, FAILURE_EVENT));
            assertRecoveryFailed(parser);
            Assertions.isFalse(parser.childExitObserved(), "failed preparation does not assert a child exit");
        }
    }

    private void failureReason() {
        IbcLogStateParser parser = established();
        parser.accept(event(GENERATION, 6, FAILURE_EVENT));
        for (String noise : List.of(event(GENERATION, 7, "LOGIN_LOGGED_IN"),
                event(GENERATION, 8, "MAIN_WINDOW_READY"), event(GENERATION, 9, SESSION_EVENT),
                event(GENERATION, 10, EVENT), "IBC: Login has completed", "IBC: Login dialog WINDOW_OPENED",
                "IBC: Second factor authentication initiated", "Program has exited", "Normal exit",
                "Gateway finished at 09:15:25", "IBC will cold-restart shortly")) {
            parser.accept(noise);
            assertRecoveryFailed(parser);
        }
        Assertions.isTrue(parser.childExitObserved(), "real exit remains observable after failure");
        parser.accept(event(GENERATION, 11, "COMMAND_SERVER_CLOSED"));
        Assertions.equals(IbcLogStateParser.CommandServerState.CLOSED, parser.commandServerState(), "command closure still tracked after failure");
        assertRecoveryFailed(parser);
    }

    private void failureIsolation() {
        IbcLogStateParser parser = established();
        IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
        for (String invalid : List.of(event(NEXT_GENERATION, 6, FAILURE_EVENT), event(GENERATION, 5, FAILURE_EVENT),
                "IBC_MANAGER_EVENT|2|" + GENERATION + "|6|" + FAILURE_EVENT,
                event(GENERATION, 6, FAILURE_EVENT) + "|unexpected-payload")) {
            Assertions.isTrue(parser.accept(invalid).isEmpty(), "untrusted failure event is rejected");
            Assertions.equals(before, parser.latest().orElseThrow(), "untrusted failure cannot replace current status");
        }
        for (String terminal : List.of("Program has exited", "Normal exit", "Gateway finished at 09:15:25",
                "IBC is paused", "IBC: CommandServer closing", "IBC: Exiting after error with exit code=1",
                "IBC will autorestart shortly", "IBC: Second factor authentication initiated")) {
            IbcLogStateParser current = established();
            current.accept(terminal);
            IbcLogStateParser.StateHint existing = current.latest().orElseThrow();
            Assertions.isTrue(current.accept(event(GENERATION, 6, FAILURE_EVENT)).isEmpty(), "late failure cannot change observed lifecycle: " + terminal);
            Assertions.equals(existing, current.latest().orElseThrow(), "existing lifecycle stays authoritative");
        }
    }

    private void failureReset() {
        for (String reset : List.of("launch", "session", "all")) {
            IbcLogStateParser parser = established();
            parser.accept(event(GENERATION, 6, FAILURE_EVENT));
            if (reset.equals("launch")) parser.accept("Starting IBC with this command:");
            else if (reset.equals("session")) parser.resetSessionState();
            else parser.reset();
            Assertions.isTrue(parser.latest().isEmpty(), "replacement/reset clears failure status");
            Assertions.isFalse(parser.errorExitConfirmed(), "replacement/reset clears old failure flag");
            parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
            parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
            parser.accept(event(NEXT_GENERATION, 3, "MAIN_WINDOW_READY"));
            parser.accept(event(NEXT_GENERATION, 4, "LOGIN_LOGGED_IN"));
            Assertions.isTrue(parser.accept(event(GENERATION, 7, FAILURE_EVENT)).isEmpty(), "old engine cannot fail a replacement");
            Assertions.isTrue(parser.mainWindowReady(), "newly completed login recovers normal readiness");
            parser.accept(event(NEXT_GENERATION, 5, SESSION_EVENT));
            assertRecovery(parser);
        }
    }

    private void failureRejectionPrecedence() {
        IbcLogStateParser first = established();
        first.accept(event(GENERATION, 6, "LOGIN_REJECTED_TWSINFO"));
        first.accept(event(GENERATION, 7, FAILURE_EVENT));
        assertRejected(first);
        IbcLogStateParser second = established();
        second.accept(event(GENERATION, 6, FAILURE_EVENT));
        second.accept(event(GENERATION, 7, "LOGIN_REJECTED_TWSINFO"));
        second.accept("Normal exit");
        assertRejected(second);
    }

    private void guardFailureAtLogin() {
        for (String preceding : List.of("LOGIN_LOGGED_OUT", "LOGIN_LOGGING_IN", "LOGIN_AWAITING_CREDENTIALS",
                "LOGIN_TWO_FA_IN_PROGRESS", "LOGIN_LOGGED_IN")) {
            IbcLogStateParser parser = waiting();
            parser.accept(event(GENERATION, 4, "MAIN_WINDOW_READY"));
            parser.accept(event(GENERATION, 5, preceding));
            parser.accept(event(GENERATION, 6, GUARD_FAILURE_EVENT));
            assertGuardRecoveryFailed(parser);
            Assertions.isFalse(parser.childExitObserved(), "guard deletion failure is not an observed process exit");
            Assertions.isTrue(parser.accept(event(GENERATION, 7, "LOGIN_LOGGED_IN")).isEmpty(), "following successful-login event cannot hide the guard problem");
            assertGuardRecoveryFailed(parser);
        }
    }

    private void guardFailureIsolation() {
        IbcLogStateParser waiting = waiting();
        Assertions.isTrue(waiting.accept(event(GENERATION, 4, GUARD_FAILURE_EVENT)).isEmpty(), "main window must have been observed before login cleanup");
        IbcLogStateParser noApplication = new IbcLogStateParser();
        noApplication.accept(event(GENERATION, 1, "ENGINE_STARTED"));
        noApplication.accept(event(GENERATION, 2, "MAIN_WINDOW_READY"));
        Assertions.isTrue(noApplication.accept(event(GENERATION, 3, GUARD_FAILURE_EVENT)).isEmpty(), "application launch must have been observed");
        IbcLogStateParser parser = established();
        IbcLogStateParser.StateHint before = parser.latest().orElseThrow();
        for (String invalid : List.of(event(NEXT_GENERATION, 6, GUARD_FAILURE_EVENT), event(GENERATION, 5, GUARD_FAILURE_EVENT),
                event(GENERATION, 6, GUARD_FAILURE_EVENT) + "|unexpected-payload")) {
            Assertions.isTrue(parser.accept(invalid).isEmpty(), "foreign stale or malformed guard failure is rejected");
            Assertions.equals(before, parser.latest().orElseThrow(), "untrusted guard failure cannot alter readiness");
        }
        for (String terminal : List.of("Program has exited", "Normal exit", "IBC is paused",
                "IBC: CommandServer closing", "IBC: Exiting after error with exit code=1", "IBC will autorestart shortly")) {
            IbcLogStateParser current = established();
            current.accept(terminal);
            IbcLogStateParser.StateHint existing = current.latest().orElseThrow();
            Assertions.isTrue(current.accept(event(GENERATION, 6, GUARD_FAILURE_EVENT)).isEmpty(), "terminal child cannot announce a login cleanup failure");
            Assertions.equals(existing, current.latest().orElseThrow(), "terminal state remains authoritative");
        }
        IbcLogStateParser pending = establishedRecovering();
        Assertions.isTrue(pending.accept(event(GENERATION, 7, GUARD_FAILURE_EVENT)).isEmpty(), "old recovering child cannot announce replacement login cleanup");
        assertRecovery(pending);
    }

    private void guardFailureLifecycle() {
        IbcLogStateParser parser = established();
        parser.accept(event(GENERATION, 6, GUARD_FAILURE_EVENT));
        for (String noise : List.of(event(GENERATION, 7, "LOGIN_LOGGED_IN"),
                event(GENERATION, 8, "MAIN_WINDOW_READY"), event(GENERATION, 9, SESSION_EVENT),
                "IBC: Login has completed", "IBC: Second factor authentication initiated", "Normal exit")) {
            parser.accept(noise);
            assertGuardRecoveryFailed(parser);
        }
        parser.accept("Starting IBC with this command:");
        parser.accept(event(NEXT_GENERATION, 1, "ENGINE_STARTED"));
        parser.accept(event(NEXT_GENERATION, 2, "GATEWAY_STARTING"));
        parser.accept(event(NEXT_GENERATION, 3, "MAIN_WINDOW_READY"));
        parser.accept(event(NEXT_GENERATION, 4, "LOGIN_LOGGED_IN"));
        Assertions.isTrue(parser.mainWindowReady(), "clean replacement restores readiness");
        Assertions.isFalse(parser.errorExitConfirmed(), "clean replacement has no historical guard error");
        Assertions.isTrue(parser.accept(event(GENERATION, 10, GUARD_FAILURE_EVENT)).isEmpty(), "retired guard failure cannot contaminate replacement");
    }

    private void guardFailureRejection() {
        IbcLogStateParser first = established();
        first.accept(event(GENERATION, 6, "LOGIN_REJECTED_TWSINFO"));
        first.accept(event(GENERATION, 7, GUARD_FAILURE_EVENT));
        assertRejected(first);
        IbcLogStateParser second = established();
        second.accept(event(GENERATION, 6, GUARD_FAILURE_EVENT));
        second.accept(event(GENERATION, 7, "LOGIN_REJECTED_TWSINFO"));
        second.accept("Normal exit");
        assertRejected(second);
    }

    private static IbcLogStateParser waiting() {
        IbcLogStateParser parser = new IbcLogStateParser();
        parser.accept("Starting IBC with this command:");
        parser.accept(event(GENERATION, 1, "ENGINE_STARTED"));
        parser.accept(event(GENERATION, 2, "GATEWAY_STARTING"));
        parser.accept(event(GENERATION, 3, "COMMAND_SERVER_READY"));
        parser.accept("IBC: Login dialog WINDOW_OPENED: LoginState is LOGGED_OUT");
        return parser;
    }

    private static IbcLogStateParser recovering() {
        IbcLogStateParser parser = waiting();
        parser.accept(event(GENERATION, 4, EVENT));
        return parser;
    }

    private static IbcLogStateParser established() {
        IbcLogStateParser parser = waiting();
        parser.accept(event(GENERATION, 4, "MAIN_WINDOW_READY"));
        parser.accept(event(GENERATION, 5, "LOGIN_LOGGED_IN"));
        return parser;
    }

    private static IbcLogStateParser establishedRecovering() {
        IbcLogStateParser parser = established();
        parser.accept(event(GENERATION, 6, SESSION_EVENT));
        return parser;
    }

    private static String event(String generation, long sequence, String kind) {
        return "IBC_MANAGER_EVENT|1|" + generation + "|" + sequence + "|" + kind;
    }

    private static void assertRecovery(IbcLogStateParser parser) {
        Assertions.equals(RuntimeState.RESTARTING, parser.latest().orElseThrow().state(), "recovery state");
        Assertions.equals(MESSAGE, parser.latest().orElseThrow().message(), "specific expiry reason");
        Assertions.isTrue(parser.restartPending(), "recovery pending");
        Assertions.isFalse(parser.loginCompleted(), "old child is not login-ready");
        Assertions.isFalse(parser.mainWindowReady(), "old child is not command-ready");
    }

    private static void assertRejected(IbcLogStateParser parser) {
        Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "startup rejection is authoritative");
        Assertions.contains(parser.latest().orElseThrow().message(), "invalid twsInfo", "startup rejection reason retained");
        Assertions.isFalse(parser.loginCompleted(), "rejected login is not ready");
        Assertions.isTrue(parser.errorExitConfirmed(), "startup rejection records an error");
    }

    private static void assertRecoveryFailed(IbcLogStateParser parser) {
        Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "failed preparation is an explicit error");
        Assertions.contains(parser.latest().orElseThrow().message(), "could not be prepared", "failure explains cold-restart preparation");
        Assertions.contains(parser.latest().orElseThrow().message(), "Stop the profile and restart it", "failure has a concrete recovery action");
        Assertions.isFalse(parser.restartPending(), "failure never promises a pending restart");
        Assertions.isFalse(parser.loginCompleted(), "failed expired session is not login-ready");
        Assertions.isFalse(parser.mainWindowReady(), "failed expired session is not command-ready");
        Assertions.isTrue(parser.errorExitConfirmed(), "future wrapper exit retains recovery failure");
    }

    private static void assertGuardRecoveryFailed(IbcLogStateParser parser) {
        Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "guard failure is actionable");
        Assertions.contains(parser.latest().orElseThrow().message(), "login completed", "guard failure does not misreport authentication rejection");
        Assertions.contains(parser.latest().orElseThrow().message(), "token-recovery guard could not be cleared", "guard failure has its precise cause");
        Assertions.isFalse(parser.restartPending(), "guard failure does not request another restart");
        Assertions.isFalse(parser.mainWindowReady(), "guard failure prevents normal command readiness");
        Assertions.isTrue(parser.errorExitConfirmed(), "guard failure survives an eventual wrapper exit");
    }
}
