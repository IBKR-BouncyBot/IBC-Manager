# Batch files in IBC Manager 2.0.3

This description follows the actual commands in `scripts/`. The matching root
files delegate to them; use the root files for normal operation.

| File | Build-driver command after prerequisite checks | Use |
|---|---|---|
| `run.bat` | With a missing source JAR only: `self-test clean jar smoke`; otherwise no compilation | Start the GUI. The release ZIP already contains its JAR. |
| `test.bat` | `self-test clean test` | Compile both components and run headless tests, without producing the Manager release JAR/ZIPs. |
| `build.bat` | `self-test clean test jar smoke dist` | Produce the Manager JAR plus normal release/source archives; no real GUI checks or native installer. |
| `validate-windows.bat` | PowerShell bootstrap `-SelfTest`, then `self-test clean test jar smoke gui-smoke`, followed by extra version/headless checks | Validate this computer/source with real GUI fixtures; no distribution ZIP or EXE stage. |
| `package-windows.bat` | `self-test clean test jar smoke gui-smoke dist`, then jpackage and `windows-release-zip` | Produce everything needed for Windows distribution. |

**Only using the app:** use the portable/installed EXE, or `run.bat` for the JAR
release. You do not need a build or test command.

**Creating the Windows installer and portable folder:** run
`package-windows.bat` by itself. It already runs the complete automated test/build
and GUI gates. Running `test.bat`, `build.bat`, then `validate-windows.bat` first
is redundant, though each remains useful when isolating a build problem. Keep
these scripts in the source package; there is no need to remove them.

All four development commands clean `build` and `dist` first. In particular,
running `test.bat` after packaging deletes the previous package outputs. Copy
artifacts elsewhere first if they must be retained.

User-facing scripts pause on both success and failure and preserve the exit code.
`run.bat` pauses after the GUI exits, not while the GUI is starting. Set
`IBC_MANAGER_NO_PAUSE=1` or a `CI` environment variable for automated invocation.
Internal bootstrap, engine and scheduled-launch paths do not pause.

The root and canonical script have the same effect; running both repeats work.
Prerequisites are requested with consent. Running needs Java 17+, development
needs a JDK, and native installer packaging also needs the configured WiX toolchain.
