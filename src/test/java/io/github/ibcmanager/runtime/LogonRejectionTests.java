package io.github.ibcmanager.runtime;

import io.github.ibcmanager.model.RuntimeState;
import io.github.ibcmanager.tests.Assertions;
import io.github.ibcmanager.tests.NamedTest;
import io.github.ibcmanager.tests.TestSuite;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class LogonRejectionTests implements TestSuite {
    @Override public String name() { return "Invalid twsInfo: actual rejection lines and versioned engine event"; }
    @Override public List<NamedTest> tests() {
        List<NamedTest> result = new ArrayList<>();
        for (String input : List.of(
                "2026-10-11 19:31:57.466 ERROR [JTS-LogonDispatcherS2-28] - LogonDispatcher received reject(probably running an old ccp version), reason [invalid twsInfo]",
                "IBC: Gateway/TWS login rejected: invalid twsInfo. Review the runtime and application logs; automatic retry was not requested.",
                "2026-10-11 19:31:57.623 INFO [JTS-LogonDispatcherS2-28] - Reject text cannot be translated: invalid twsInfo")) {
            result.add(new NamedTest("observed rejection variant " + result.size() + " survives normal exit footer", () -> raw(input)));
        }
        result.add(new NamedTest("unrelated diagnostic prose does not synthesize a server rejection", this::unrelated));
        result.add(new NamedTest("versioned rejection ignores stale events and protects terminal reason", this::structured));
        result.add(new NamedTest("new wrapper child permits a subsequent genuine successful login", this::replacement));
        return result;
    }
    private void raw(String line) {
        IbcLogStateParser parser = new IbcLogStateParser(); parser.accept("Starting IBC with this command:");
        parser.accept("IBC: Starting Gateway"); parser.accept(line);
        Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "rejection state");
        for (String footer : List.of("Program has exited", "Normal exit", "Gateway finished at 19:32", "IBC: Login has completed")) {
            parser.accept(footer);
            Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "terminal rejection preserved");
            Assertions.contains(parser.latest().orElseThrow().message(), "invalid twsInfo", "error context");
            Assertions.isTrue(parser.errorExitConfirmed(), "cannot label clean exit a success");
            Assertions.isFalse(parser.loginCompleted(), "no false readiness");
        }
    }
    private void unrelated() {
        IbcLogStateParser parser = new IbcLogStateParser();
        parser.accept("Starting IBC with this command:");
        parser.accept("Reading invalidTwsInfo troubleshooting documentation");
        Assertions.isTrue(parser.logonRejection().isEmpty(), "phrase alone is not a rejection event");
    }
    private void structured() {
        IbcLogStateParser parser = new IbcLogStateParser(); String generation = UUID.randomUUID().toString();
        String prefix = "IBC_MANAGER_EVENT|1|" + generation + "|";
        parser.accept(prefix + "1|ENGINE_STARTED"); parser.accept(prefix + "2|GATEWAY_STARTING");
        parser.accept("IBC_MANAGER_EVENT|1|" + UUID.randomUUID() + "|3|LOGIN_REJECTED_TWSINFO");
        Assertions.isTrue(parser.logonRejection().isEmpty(), "foreign engine cannot inject rejection");
        parser.accept(prefix + "3|LOGIN_REJECTED_TWSINFO");
        parser.accept(prefix + "4|LOGIN_LOGGED_IN"); parser.accept(prefix + "5|MAIN_WINDOW_READY");
        Assertions.equals(RuntimeState.ERROR, parser.latest().orElseThrow().state(), "late readiness cannot erase rejected attempt");
        Assertions.isFalse(parser.mainWindowReady(), "no false command readiness");
        parser.accept("Starting IBC with this command:"); parser.accept(prefix + "6|LOGIN_REJECTED_TWSINFO");
        Assertions.isTrue(parser.logonRejection().isEmpty(), "retired generation stays ignored");
    }
    private void replacement() {
        IbcLogStateParser parser = new IbcLogStateParser(); parser.accept("Starting IBC with this command:");
        parser.accept("IBC: Gateway/TWS login rejected: invalid twsInfo.");
        parser.accept("Starting IBC with this command:"); parser.accept("IBC: Starting Gateway"); parser.accept("IBC: Login has completed");
        Assertions.isTrue(parser.logonRejection().isEmpty(), "explicit new child clears prior failure");
        Assertions.isTrue(parser.loginCompleted(), "genuine subsequent login accepted");
        parser.resetSessionState(); Assertions.isTrue(parser.logonRejection().isEmpty(), "manual lifecycle reset");
    }
}
