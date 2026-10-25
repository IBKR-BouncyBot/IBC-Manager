# IBC Manager 2.3.0 - Windows acceptance

These are pending target-machine checks, not claims of execution on the Linux
build host. Record actual outcomes with the exact archive/runtime versions.
See [TEST_REPORT.md](../TEST_REPORT.md) for executed checks.

## 2.3.0 manager.8 established-session expiry and exact pins

- [ ] Run the full headless and GUI gates, including ExpiredSessionTokenGuiSmoke.
  Record any platform-limited fixtures separately from completed assertions.
- [ ] In a paper Gateway 10.50.1e session, verify the exact `IBKR Gateway` expiry
  dialog after a completed login requests one cold restart, reports the reason,
  invalidates old readiness and reaches fresh credentials / real IB Key approval.
  Confirm completed authentication and the owned API listener afterward.
- [ ] Verify the retained logged-out warm-start expiry case, including the
  `Gateway` title. The replacement must start without `-Drestart` even when a
  saved restart descriptor exists alongside the dedicated expiry marker.
- [ ] Confirm `TOKENEXPIREDPENDING<session-id>` survives a failed fresh login and
  prevents fallback to stale warm data. Successful replacement login must clear
  that exact guard; cleanup failures must remain visible. Existing explicit 2FA
  and login-display-timeout retry policies must remain separate and start fresh.
- [ ] Verify graceful post-login shutdown runs without an EDT exception. With a
  controlled blocked-close fixture, verify the 30-second fallback is limited to
  the affected engine JVM; unrelated profiles/processes remain untouched.
- [ ] Exercise competing Stop and Pause, duplicate window events, changed message,
  new login generation, active login/2FA, unrelated errors and invalid twsInfo.
  They must retain their defined exclusion/precedence rules. A canceled or
  unpreparable claimed recovery must not rearm inside the same engine JVM.
  Stop after committed recovery retains the existing stop-in-progress response;
  explicit Force Stop remains separate.
- [ ] Verify normal daily warm restart, Pause/Start and Manager Restart still work.
  An ordinary network disconnect must not trigger the expiry-recovery path.
- [ ] Confirm new managed setup selects only JBR 25.0.4-b508.27 and JavaFX 25.0.4.
  Newer/unavailable catalogue metadata must not select another version or block
  fixed-coordinate selection. Missing required pinned downloads must fail visibly.
- [ ] Confirm a complete owned payload with both exact pins is reused without a
  download. A mismatched JBR or JavaFX version must require explicit maintenance;
  removal of a correctly recorded older owned payload must remain possible.

The previous 2.2.0 Windows validation and packaging logs passed their automated
checks; they are historical evidence, not execution of this checklist. Use the
current TEST_REPORT.md and [fix details](ESTABLISHED_SESSION_EXPIRY_FIX.md).

## Older incompatible payloads only: JavaFX dependency repair

A working JBR 25.0.4-b508.27 / JavaFX 25.0.4 runtime does not need removal for the 2.3.0 upgrade. An older
incomplete managed payload may omit jdk.jsobject.jar. Stop all profiles using its
installation, disable and Save on each owning profile, then enable and Save with
this corrected package. Do not copy a JAR into the owned payload or edit its hash
receipt. Verify payload/javafx/jdk.jsobject.jar exists after successful setup and
Gateway starts without FindException. Missing dependencies must be ERROR, not a
normal scheduled shutdown. See [dependency details](JAVAFX_DEPENDENCY_FIX.md).

## Retained optional-probe acceptance

Confirm the shortened enable/removal consent. With an unavailable checksum and/or
runtime inventory check, Save must complete with a SKIPPED warning and provenance.
Inspect any captured stdout/stderr for the underlying runtime condition. Then perform
a normal paper-profile launch; this change does not force an unlaunchable JVM to run.
Test a scheduled restart while RDP is disconnected and Windows remains secured.
Available checksum mismatches, unsafe archives, missing files, known wrong-version
module inventories and cancellation must still fail. Confirm later removal restores
prior options and preserves manual/shared runtimes. Do not disable build tests.

## Retained checksum-availability acceptance

In Edit Profile, confirm the checkbox omits "1050 / ". Its permission dialog must say
unavailable publisher checksums can be skipped with less assurance. Retry setup with a
checksum download unavailable, while the actual ZIP/JAR is downloadable: confirm Save
succeeds, warnings are visible/recorded, and payload/download-provenance.txt marks the
artifact SKIPPED rather than VERIFIED. Confirm actual mismatches, TLS/redirect rejection,
cancellation, incomplete runtime files and incompatible modules still fail. Test removal
restores the prior override/options and preserves manual files. Do not confuse build
verification with optional publisher checksums: package-windows.bat still runs its tests.

## Retained Windows acceptance checks

These are target-machine checks, not claims of execution on the Linux build host.
Use a paper account, backed-up data directory and retained remote recovery access.
Verify a working 2.1.0/2.2.0-to-2.3.0 upgrade preserves the existing Java override, managed
runtime selection, format-7 profiles and credentials without runtime reinstallation.

## Bootstrap and packaging

- [ ] Run the replacement source with Microsoft Java 25 and confirm the original
  real-process reattachment case and its four new observer/negative-control cases
  pass, then allow the GUI and native packaging gates to complete. Do not skip
  tests or delete Gateway/runtime data to bypass this source-test correction.

- [ ] run.bat, build and package select Microsoft Java 25, not an existing Java 17
  or JBR installation. Declining prerequisite installation changes nothing.
- [ ] The packaged runtime reports Microsoft Java 25. The installer and portable
  app work, and only those appear in Release_windows.zip. HTTPS downloads work
  from the packaged runtime, including publisher certificate validation.
- [ ] Existing 2.1.0 format-7 profiles/DPAPI credentials load unchanged.
  Existing 2.0.4 profiles also load; saving writes format 7.
  Legacy TWS profiles previously disabled remain disabled.

## Profiles and applications

- [ ] Copy then Cancel saves nothing. Save creates a new identity and unique ports,
  without a password/session association. Select a separate settings directory.
- [ ] TWS discovery and startup use the correct installation and application.
  Test paper/live ports, login, 2FA, main-window readiness, API availability,
  Stop, controlled Restart, Pause, reconnect and confirmed Enable API.
- [ ] Gateway 1045's bundled Java 17 and Gateway 1050's bundled Java 25 remain
  selectable when managed setup is off.
- [ ] Runtime config remains present after authentication while `StartIBC.bat` is
  alive, and is scrubbed after the complete wrapper exits.

## Download corrections

- [ ] With the actual packaged Microsoft Java 25, start a new setup and confirm
  JBR ZIP/checksum redirects complete, all eight OpenJFX JARs verify, and Save succeeds.
- [ ] When SHA-256 is not published, progress names SHA-1 and provenance records
  both its actual URL/value and the separate local SHA-256 digest. No MD5 request.
- [ ] Repeat setup with a paper profile and stopped TWS/Gateway installation,
  then test a secured disconnected-RDP restart and last-owner removal.
- [ ] Use controlled tests for invalid checksums/redirects; never disable TLS or
  verification on the production server. Cancellation leaves old files untouched.

## Managed runtime installation

- [ ] Declining checkbox consent or cancelling the editor changes no files/profile.
- [ ] Explicit Save downloads pinned Windows x64 JBR 25.0.4-b508.27 and JavaFX 25.0.4,
  verifies checksums, probes module resolution, writes the owned block and assigns
  the override. Windows native JavaFX libraries load from the private cache.
- [ ] Network failure, checksum mismatch and cancellation retain the old setup.
- [ ] Quoted paths with spaces/non-ASCII characters are read as complete options.
- [ ] Known running, starting or recovering profiles block shared setup/removal.
  Manually launched applications and other managers' sessions are stopped too.
- [ ] Existing manual Java/JavaFX files remain intact. Reopening the profile keeps
  the managed selection. Normal startup does not redownload the runtime.

## Unattended acceptance and removal

- [ ] With RDP disconnected and Windows locked, a replacement process completes
  warm restart before reconnection, without a headless exception or timeout loop.
  Test cold login, unanswered/approved 2FA, and protected recovery progress too.
- [ ] Two registered profiles share files. Disabling one retains the other; the
  final disable restores old overrides/options and removes owned payload/cache.
- [ ] Changed options outside the block survive. Conflicting block/payload/cache
  changes stop removal rather than deleting user content.
- [ ] Test file-sharing/antivirus interference during removal. Journals and hash
  receipts are retained when required and complete safely after files are released.
- [ ] Disable managed ownership before deleting a profile, changing its selected
  installation/application or rolling back to an older Manager.
- [ ] Diagnostics includes recent live output and versions without plaintext
  credentials. Review each bundle before public sharing.

A restart that only resumes after returning through RDP is not a pass. Do not use
an automatic unlock to mask the failure. Record native acceptance separately from
fixture test counts.

## Pinned compatibility and native diagnostics

- [ ] Only for an older non-pinned managed installation: disable/save all owners, re-enable/save,
  and confirm JBR25.0.4-b508.27 plus JavaFX25.0.4 in the launch output and provenance.
- [ ] Confirm actual post-2FA login and an RDP-disconnected/locked warm restart.
  Do not claim the compatibility correction passed from local module probes alone.
- [ ] An actual invalid twsInfo dialog remains visible, reports ERROR once, and is
  not automatically dismissed/retried. A later process exit retains its cause.
- [ ] Diagnostics includes current/rotated launcher logs, readable application logs,
  JVM crash logs, versions/options/provenance and source/omission inventory.
- [ ] Verify native identity hints match installed launcher data; no example TWS
  flags are substituted into Gateway. No EXE is run or copied by diagnostics.
- [ ] Check Windows file locks, junctions, symlinks and access denial: unsafe files
  are omitted without aborting other evidence collection or changing source files.
- [ ] Encrypted logs are inventoried only; obtain plaintext using Gateway/TWS Logs.
- [ ] Review for secrets, account/trading data and other sessions before sharing.
