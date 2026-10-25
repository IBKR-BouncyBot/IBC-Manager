# IBC Manager 2.3.0 — executed validation

25 October 2026. Application **2.3.0**, embedded engine **3.24.2-manager.8**.
Tests ran on the Linux analysis host with Java 17. No working installation was
modified and no broker login or trading operation was performed.

| Check | Newly executed result |
| --- | --- |
| Build-driver self-test | Passed for 2.3.0 |
| Strict compilation | Passed: 91 engine, 126 Manager production, 52 test Java files; warnings remain errors |
| Complete headless suite | 1,121 named cases / 27,161 assertions; 1,111 reported passed, 10 failed |
| Fresh unchanged 2.2.0 baseline | 1,050 cases / 27,034 assertions; 1,040 passed, the same 10 failed |
| Failure comparison | All ten exact failure lines match; no added failures |
| Engine expiry focused suite | 34 cases / 129 assertions passed |
| Manager expiry-event focused suite | 35 cases / 714 assertions passed |
| Runtime installation/pin focused suites | 328 cases / 16,854 assertions passed |
| Versioned JAR / headless smoke | Built; reports 2.3.0; smoke OK with zero recovery warnings |
| Embedded payload integrity | All seven checksums and manager.8 revision verified |
| Archived upstream integrity | All 89 file hashes match; upstream manifest unchanged |
| Engine patch replay | Eight Java patches reproduce all 91 maintained sources byte-for-byte |
| Resource patch replay | Three patches reproduce both maintained Windows scripts byte-for-byte after CRLF restoration |
| Windows CMD / GUI / native packaging / live Gateway | Not executed on this host |

The final suite adds 71 named cases over 2.2.0. Fixture updates replace moving
runtime-catalogue expectations with exact-pin expectations; incomplete synthetic
JBR release metadata now includes valid build identity. No test was disabled to
remove the ten failures. The focused suites overlap the complete suite.

The 1,111 passing named results include platform-conditional tests whose Windows
branches cannot run on Linux. In particular, three wrapper test groups report
`PLATFORM LIMIT`; their **15 native CMD scenarios were not executed**. The new
expiry GUI runner's **10 scenarios compiled but were not executed**. There is no
claim that headless execution validates Windows DPAPI, ACLs, Swing dialogs or RDP.

## Ten unchanged failures

These exact lines occur in both freshly executed baseline and final runs:

```text
[FAIL] process tree terminator kills the exact root and descendants - java.lang.AssertionError: test process must create a descendant
[FAIL] process identity store round-trips identity - java.util.NoSuchElementException: No value present
[FAIL] process identity store reattaches only matching start time - java.lang.AssertionError: matching live PID and start time must reattach
[FAIL] controller reattaches to an exact live process identity - java.lang.AssertionError: constructor must reattach exact live identity expected=<UNKNOWN> actual=<STOPPED>
[FAIL] reattached Force Stop tolerates a delayed parent Process exit observation - java.lang.AssertionError: constructor must reattach exact live identity expected=<UNKNOWN> actual=<STOPPED>
[FAIL] reattachment exit verification rejects a still-live child within its deadline - java.lang.AssertionError: the negative-control native process must still be alive
[FAIL] reattachment exit verification propagates an interrupted wait - java.lang.AssertionError: interruption must not kill the observed child
[FAIL] live legacy process prevents migration without changing configuration - java.util.NoSuchElementException: No value present
[FAIL] live legacy process blocks configuration import - java.util.NoSuchElementException: No value present
[FAIL] process-handle identity matches the current process - java.util.NoSuchElementException: No value present
```

The existing process probe demonstrates a mismatch between the JVM PID and the
available `/proc` namespace: live child Process objects can have unavailable or
non-live ProcessHandle identities and missing start times/descendants. See
[PROCESS_HANDLE_ENVIRONMENT.txt](docs/validation/PROCESS_HANDLE_ENVIRONMENT.txt).
This is concrete environment evidence, not a separate causal proof for every
failed assertion. Production process-identity checks and their tests are unchanged.

The supplied **2.2.0 Windows** logs reported 1,050 passed / 27,105 assertions,
including these ten cases, and passed their GUI/build gates; four symbolic-link
fixtures were unavailable. Those historical results do not validate new 2.3.0 code.

## Commands and preserved build gate

```text
java src/build/java/io/github/ibcmanager/build/BuildProject.java self-test clean test
java src/build/java/io/github/ibcmanager/build/BuildProject.java jar smoke
```

The full test command exits **1** because of the ten failures. The normal `dist`
target still requires a passing full suite and is not claimed to have passed.
The separately built JAR is packaged in the standard Java distribution layout;
this does not change the failed test gate into a pass. Build and test requirements
were not weakened. A first full 2.3.0 run also had exactly those ten failures;
final JBR metadata refinements were then compiled and tested in the run above.

Exact execution records:

- [Build and complete test output](docs/validation/V230_BUILD_AND_TEST.txt)
- [JAR build and smoke output](docs/validation/V230_JAR_SMOKE.txt)
- [Upstream integrity and patch replay](docs/validation/V230_PATCH_REPRODUCTION.txt)

JAR SHA-256: `4a7aa16f89bb6bdaa465a8c487fec2e9829d5787dee2f9f9b56faa76c2a8d48c`.

## Artifact verification

Both delivery ZIPs passed CRC and byte-content verification against staged files.
Building the JAR from the extracted final source archive produced byte-identical
output, and the rebuilt JAR passed headless smoke. The Java release contains
the JAR, Windows Java launcher scripts,
documentation and licensing. The complete corresponding source includes the
build driver, upstream snapshot, reproducible patches and all regression tests.
No native Windows EXE installer or bundled Windows runtime is included.

## Target-platform acceptance still required

Run `validate-windows.bat` from the 2.3.0 source, then build the native package on
Windows. Its gates must remain intact. In an isolated paper-Gateway profile,
verify established-session expiry after both originally warm and fresh logins,
fresh credentials/2FA completion, normal daily warm restart, Pause/Start, Stop,
API listener ownership and an RDP disconnect/reconnect cycle with both pinned
runtimes. The screenshot proves the visible expiry message; the exact proprietary
post-login component tree was not recorded, so fixture coverage is not live proof.

The fix responds to explicit expiry, without a calendar-based Sunday trigger.
Successful fresh authentication still depends on valid credentials and any IBKR
approval. See [implementation evidence](docs/ESTABLISHED_SESSION_EXPIRY_FIX.md),
[restart modes](docs/RESTART_MODES.md), [review](CODE_REVIEW.md), and
[Windows checklist](docs/WINDOWS_VALIDATION_CHECKLIST.md).
