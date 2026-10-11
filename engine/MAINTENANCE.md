# Maintaining the included engine

Engine revision 3.24.2-manager.6 derives from supplied IBC 3.24.2. The exact
archive hashes and tag commit are in PROVENANCE.json. Keep engine revisioning
independent from the Manager application version.

1. Work from an explicitly selected upstream revision, never an unreviewed
   runtime 'latest' download. Preserve the old snapshot and review the change.
2. Update the reference snapshot/provenance and verify Windows script/helper
   changes against that revision. The source ZIP retains original reference bytes
   with `engine/upstream/** -text` in .gitattributes.
3. Reapply/review the logical patch. Keep engine changes localized and document
   any intentional Gateway behavior difference. The maintained Java tree uses LF.
4. Keep public event protocol 1 stable or version a changed protocol explicitly.
   Do not send secrets or arbitrary dialog text in lifecycle events.
5. Update both `Version.ENGINE_VERSION` and `engine/resources/engine-version`.
   Update IbcVersionInfo and the upstream version file when the upstream changes.
6. Run the complete build, real engine contract suite, Manager and engine Swing
   GUI tests. Preserve negative tests for an unavailable main window, stale
   generations, runtime credentials and interrupted migration.
7. Reproduce the JAR/ZIPs from an extracted source archive. Run native Windows
   packaging and test a real paper account with at least the supported installed
   Gateway versions. Exercise warm/cold restart, missed phone approval, process
   hang, exact-tree cleanup and multi-profile isolation.
8. Publish the Manager release and complete corresponding source together. Stop
   old profiles before switching engines. Never replace an active engine cache
   in place; the content-addressed revision creates a different cache directory.

Build entry point (from repository root):

```text
java src/build/java/io/github/ibcmanager/build/BuildProject.java clean test jar smoke dist
```

Use `validate-windows.bat` for the GUI gate on Windows. A Linux validation host
may run `xvfb-run -a java .../BuildProject.java gui-smoke`; that does not make Linux
a supported production platform.

The original upstream build.xml is reference material, not this product's build
entry point. Manager's build compiles the maintained tree without proprietary
Gateway classes. Do not restore a direct compile-time dependency on GWClient.

## Reproducing the logical patch

Copy `engine/upstream/src` to a separate review directory, normalize those copied
Java text files to LF without altering any characters, then apply
each patch listed in `engine/patches/series`, in order, from that directory
using `git apply --no-index`. The second patch adds the independent second-factor
deadline, safe GUI retry and related lifecycle guards. The third patch repairs
credential-first returned-login handling, registered replacement frames and heading-free
inline challenge observation. The resulting Java tree must match
`engine/src/main/java` byte-for-byte. Do not normalize or edit the archival
`engine/upstream` copy itself; verify it against UPSTREAM_SHA256.json instead.

## 3.24.2-manager.3 authentication timer contract

`SecondFactorRetry` owns one monotonic deadline per recognized challenge, runs
only bounded readiness checks after expiry, and delegates all UI work to the EDT.
`SecondFactorRetryTarget` binds actions to the same LoginManager/handler and exact
challenge generation. It accepts a reconstructed form only after the existing handler
registers it. It requires an unambiguous visible Cancel control when a challenge is
still visible, restores credentials once on a recognized form and waits at most ten
seconds for the real login button to enable before submitting without rewriting fields. Do not replace those checks with a window dispose,
global keyboard input or a process kill.

Login success, failed state, stop, provider replacement and new attempts cancel
old tasks. The obsolete dialog-close retry/exit scheduler was removed to avoid
double submission and premature exits. A closed prompt alone is not proof of
authentication success. The current profile maps enabled retry to 300 seconds.

Test the actual scheduler and Swing dialog as well as virtual-time boundaries.
`SecondFactorRetryGuiSmoke --real-five-minutes` runs the real 300-second path
using fixture controls. It never connects to IBKR and does not certify push delivery.

## 3.24.2-manager.4 command and launcher changes

The fourth Java patch removes Toolkit shortcut lookup from CommandDispatcher class
initialization. Only reconnect commands resolve graphics-dependent modifiers, and
unavailable graphics return a negative acknowledgement. STOP retains the existing
pre-login halt or logged-in graceful menu path; it is not converted to a global kill.

The separate resource patch series is listed in `engine/resource-patches/series`.
For reproduction, copy upstream/windows/StartIBC.bat and getExtraJavaOptions.ps1
into a temporary `scripts/` directory. Normalize the copied scripts to LF, apply the
resource patches from that temporary root, then encode both scripts as CRLF without
BOM. The results must match `engine/resources/scripts/` byte-for-byte. Archival
upstream bytes and UPSTREAM_SHA256.json remain unchanged. All four Gateway Java
invocation branches now route stderr to stdout with `2>&1`; ERRORLEVEL is unchanged.
The Manager relay redacts complete bounded lines before disk or live delivery.

Run HeadlessCommandTests in fresh headless JVMs as well as EngineGuiSmoke on a display.
The STOP test uses a temporary loopback command channel and the actual StopTask in
an isolated pre-login fixture process. It never launches Gateway or uses real credentials.
The authentication timer code is unchanged from manager.3; run its full regression suites.

## 3.24.2-manager.5 restoration

The fifth Java patch restores TWS through the included upstream handlers and a
reflection-only jclient.LoginFrame bridge, adds TWS_STARTING/main-window events and
guards TWS EnableAPI before desktop/main-window-dependent work. No proprietary
IBKR classes enter the production JAR. Gateway's existing bootstrap and all manager.4
watchdog/command/2FA guarantees remain. Both applications reject FIX mode.

The second resource patch preserves complete VM-option lines, including quoted
module paths with spaces. All four merged-stderr invocation branches remain. It
introduces no automatic download: runtime provisioning is a separate consented Manager
service. Apply both resource patches in series to normalized upstream copies and
restore CRLF for exact comparison. Do not edit the original upstream snapshots.

TWS retry selection also differs from Gateway: LoginFrameHandler requires Live/Paper
Trading controls but has no IB API selector. The fifth patch keeps Gateway's selector
guard and validates TWS controls before the inherited handler can request a cold restart
for a deconstructed form. The timer and approval/modal/credential safeguards are unchanged.
SecondFactorRetryGuiSmoke includes the original 23 Gateway cases and five TWS cases.

## 3.24.2-manager.6 rejection reporting

The sixth Java patch recognizes the exact invalid twsInfo/OK dialog (including its
untitled form), cancels that failed login state, and emits LOGIN_REJECTED_TWSINFO.
It does not click, retry, alter launcher identity or reveal dialog/credential text.
Existing authentication timers and the Windows resource patch series are unchanged.
EngineGuiSmoke covers detection, HTML whitespace, unrelated dialogs, duplicate events,
no dismissal, failure state and registration before generic handlers. Manager tests
verify the event generation and terminal reason surviving the wrapper's normal footer.
