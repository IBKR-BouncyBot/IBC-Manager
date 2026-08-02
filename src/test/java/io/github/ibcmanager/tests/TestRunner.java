package io.github.ibcmanager.tests;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class TestRunner {
    private static final List<String> SUITES = List.of(
            "io.github.ibcmanager.storage.ProfileCodecTests",
            "io.github.ibcmanager.config.ConfigDocumentTests",
            "io.github.ibcmanager.validation.ValidationTests",
            "io.github.ibcmanager.security.SecurityTests",
            "io.github.ibcmanager.storage.StorageTests",
            "io.github.ibcmanager.runtime.RuntimeComponentTests",
            "io.github.ibcmanager.runtime.ControllerTests",
            "io.github.ibcmanager.diagnostics.DiagnosticsTests",
            "io.github.ibcmanager.task.TaskSchedulerTests",
            "io.github.ibcmanager.discovery.InstallationDiscoveryTests",
            "io.github.ibcmanager.install.IbcInstallerTests",
            "io.github.ibcmanager.app.ApplicationTests",
            "io.github.ibcmanager.ui.UiModelTests",
            "io.github.ibcmanager.tests.PrerequisiteBootstrapTests",
            "io.github.ibcmanager.tests.ArchitectureTests");

    private TestRunner() { }

    public static void main(String[] args) throws Exception {
        Instant started = Instant.now();
        List<String> failures = new ArrayList<>();
        int executed = 0;
        int assertions = 0;
        for (String suiteName : SUITES) {
            TestSuite suite = (TestSuite) Class.forName(suiteName).getConstructor().newInstance();
            System.out.println("[SUITE] " + suite.name());
            for (NamedTest test : suite.tests()) {
                executed++;
                Assertions.resetCount();
                try {
                    test.body().run();
                    assertions += Assertions.count();
                    System.out.println("  [PASS] " + test.name());
                } catch (Throwable throwable) {
                    assertions += Assertions.count();
                    String failure = suite.name() + " :: " + test.name() + " :: " + throwable;
                    failures.add(failure);
                    System.out.println("  [FAIL] " + test.name() + " - " + throwable);
                    throwable.printStackTrace(System.out);
                }
            }
        }
        Duration elapsed = Duration.between(started, Instant.now());
        System.out.println();
        System.out.println("Executed " + executed + " test cases with " + assertions
                + " assertions in " + elapsed.toMillis() + " ms");
        if (!failures.isEmpty()) {
            System.out.println(failures.size() + " failure(s):");
            for (String failure : failures) System.out.println(" - " + failure);
            System.exit(1);
        }
        System.out.println("ALL TESTS PASSED");
    }
}
