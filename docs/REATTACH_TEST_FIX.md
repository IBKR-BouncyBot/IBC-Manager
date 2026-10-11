# Reattached Force Stop build-test correction - IBC Manager 2.1.0

## Reported result

The supplied Windows log `CWINDOWSsystem32cmd.exe(20261011-152249).txt` selects
Microsoft Java 25 and successfully compiles the sources. Its 867-case headless run
ends with one failure at `ControllerTests.reattach`: immediately after Force Stop,
the test's original `Process.isAlive()` still returned true. The native packaging
stages were not reached. This is a different issue from runtime download errors.

The uploaded log has no later observation of that child's exit, so it does not
establish that the child stayed alive indefinitely, nor identify the precise
Windows native-process implementation interleaving.

## Cause and evidence

The test owns a `Process` returned by ProcessBuilder. The production controller
reattaches through `ProcessIdentityStore` and a separate `ProcessHandle`, verifies
that handle during termination, then cleans its state. Checking the original
Process immediately assumes both independent observations update simultaneously.

On the unmodified Linux/OpenJDK 21 validation runtime, a 200-child probe observed
160 instances of ProcessHandle reporting non-alive while the original Process
still reported alive. All 200 original processes then confirmed termination with
bounded waitFor; the maximum additional wait measured 0.411247 milliseconds.
These are real spawned Java fixture processes, not Gateway, Windows, or user PIDs.
This verifies that the unsafe observer-ordering assumption can fail; it is not a
claim to have rerun the user's Windows Java 25 binary.

A deterministic regression wraps only the test's original Process observation,
keeping its actual native handle and the unchanged controller/terminator. The old
immediate assertion fails while the actual child has stopped. The corrected test
passes by waiting for the original observer to confirm exit.

## Narrow correction

`src/test/java/io/github/ibcmanager/runtime/ControllerTests.java` is the only Java
source changed. After Force Stop, the test now:

1. Requires the real native handle to be non-alive immediately on return.
2. Requires the original Process's `waitFor(5, TimeUnit.SECONDS)` to complete.
3. Requires both original Process and native handle to remain non-alive and an
   exit value to be available.
4. Requires the controller to report STOPPED/no live process, the stored identity
   to be gone and the temporary runtime configuration/lease to be scrubbed.
5. Verifies that an unrelated real child process remains alive.

The five-second bound is a test-observer timeout, not a change to Manager's Stop,
Force Stop, startup, login, two-factor or recovery timers. The child normally sleeps
for 30 seconds, so the test cannot pass simply by waiting for its normal timeout.
No fixed sleep, skip, expected-failure label, warning suppression, or retry-until-pass
was added. If the native handle is still alive on return, the immediate assertion
fails. If the original Process never confirms exit, the bounded wait fails.

Four new named cases cover delayed observation, rejecting a still-live child,
already-completed nonzero exit, and propagating interrupted observation. The
original real-process reattachment case remains enabled on Windows and Linux.
The five focused cases passed 40 successive runs (200 executions, 1,800 assertions).

## Unchanged behavior and version

All Manager production source/resources, build scripts, maintained engine source,
payload, profile format, license files, screenshot and README are byte-identical
to the preceding corrected 2.1.0. The same-toolchain production JAR is also identical.
Both the JetBrains CDN redirect fix and Maven checksum-sidecar fallback remain.
Version is 2.1.0; engine is 3.24.2-manager.5; saved profiles remain format 7.

Replace the source folder or the changed test before packaging again. Installing
another Java runtime, changing profiles, deleting managed JBR/JavaFX files, or
reconfiguring Gateway is not required for this build-test correction.

## Executed and remaining validation

Local baseline: 867 cases / 14,615 assertions. Updated suite: 871 cases / 14,653
assertions on Linux/OpenJDK 21.0.12.1 with Java 17 bytecode. The Windows run's earlier
14,640 assertion total differs because some tests execute Windows-only subchecks.
Case/assertion counts are not coverage percentages.

Final artifact reproducibility, extracted-source retest, GUI smoke and archive
integrity results are recorded in the delivered FINAL_VALIDATION/logs. The user
must still rerun `package-windows.bat` with Microsoft Java 25 on Windows; no native
EXE, Microsoft Java 25 test, live Gateway/TWS, account, or real network download is
represented as newly executed on this Linux host.

## Primary API reference

Java documents process termination as asynchronous and provides bounded
Process.waitFor to synchronize with the original Process observation:
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Process.html
https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html
The exact implementation varies by platform. The investigation does not infer a
new production Force Stop defect solely from the old immediate test assertion.
