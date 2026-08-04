# Release checklist

## Source and documentation

- [ ] Application, build metadata, launch scripts, package scripts, tests, and
      documentation use the same version.
- [ ] `CHANGELOG.md`, `TEST_REPORT.md`, `CODE_REVIEW.md`, README, security
      documentation, and Windows checklist are current.
- [ ] No unresolved work markers, generated binaries, secrets, local paths, or
      unredacted logs are present in the source archive.
- [ ] GPLv3 licence, IBC attribution, and third-party notices are retained.

## Automated gates

- [ ] Compile with Java 17, UTF-8, `-Xlint:all`, and `-Werror`.
- [ ] Run every automated test with zero failures and zero skipped tests.
- [ ] Run the JAR version and isolated headless smoke tests.
- [ ] Run the real-window Swing smoke test.
- [ ] Differential-check imported IBC configuration bytes against one full-file
      `Properties.load(InputStream)` pass; malformed scanner/JDK disagreement
      must require explicit canonicalization.
- [ ] Exercise normal/scheduled shutdown, automatic/cold/timeout restart, exact
      IBC error-exit wording, and bare/status-bearing command acknowledgements.
- [ ] Verify all paths passed to `StartIBC.bat` reject the documented CMD
      character set with field-specific guidance.
- [ ] Run idle-profile API and command-server regressions: periodic status
      refreshes must not create repeated accepted/closed IBC command channels or
      IB Gateway `version was not sent`/`API client version is missing` entries.
      Passive reattachment inspection and one connection per real IBC command
      are permitted.
- [ ] Complete three clean builds and compare JAR, release ZIP, and source ZIP
      hashes for deterministic output.
- [ ] Extract and execute the release ZIP.
- [ ] Extract the source ZIP, rebuild, retest, rerun GUI smoke, and compare the
      regenerated artifacts byte-for-byte.
- [ ] Validate archive CRCs, paths, duplicate entries, expected contents, source
      cleanliness, Java 17 bytecode, module dependencies, CRLF/no-BOM scripts,
      and absence of bundled `IBC.jar`.
- [ ] Generate and verify a SHA-256 manifest.

## GitHub gates

- [ ] GitHub Actions passes on Ubuntu and Windows.
- [ ] The Ubuntu real-window GUI-smoke job passes.
- [ ] Release notes and artifact hashes are attached to the tag.
- [ ] The tag points to the exact reviewed source.

## Windows-native gates

- [ ] Exercise PowerShell 5.1 prerequisite detection from a Java 8-only starting
      environment and verify private Java 17 installation after consent.
- [ ] Exercise DPAPI save/load/delete and NTFS ACL behavior as a non-admin user.
- [ ] Exercise Task Scheduler creation/removal and startup after login.
- [ ] Install official IBC 3.24.1 through the GUI into `C:\IBC` and verify refusal
      to overwrite an invalid non-empty destination.
- [ ] Build the app image, EXE installer, and `_Release_windows.zip` with
      `package-windows.bat`; confirm the portable and installed runtimes contain
      nonempty `runtime\bin\java.exe`, then install, uninstall, and run both
      installer and portable outputs on a clean Windows machine.
- [ ] Start paper IB Gateway/TWS through official IBC, complete second factor,
      verify command-port operations, and complete a real IB API handshake with
      the expected paper account.
