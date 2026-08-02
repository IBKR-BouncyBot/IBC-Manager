# IBC Manager 1.0.8 test report

- Validation date: **2026-08-02**
- Application version: **1.0.8**
- IBC compatibility baseline: **3.24.1**
- Runtime target: **Java 17 or newer**
- Cross-platform validation JDK: **OpenJDK 21**, compiled with `--release 17`

## Scope of this release

Version 1.0.8 extends `package-windows.bat` so a successful Windows native build
also produces:

```text
dist\IBC_Manager_1.0.8_Release_windows.zip
```

The filename matches the normal release ZIP and adds `_windows` immediately
before `.zip`. The Windows archive contains the complete normal release tree,
the generated `IBC Manager-1.0.8.exe` installer, and an installer
`SHA256SUMS.txt` file.

No IBC Manager runtime behavior was changed.

## Automated result

```text
Production Java files: 91
Test Java files:       23
Build-driver files:     1
Test cases:           425
Assertions:         5,113
Failed:                 0
Skipped:                0
```

## Compiler gate

Production and test code is compiled by the JDK-native build driver with:

```text
--release 17
-encoding UTF-8
-Xlint:all
-Werror
```

The production class-file target remains major version 61. The optional
`build.xml` compatibility wrapper delegates to the same build driver.

## Windows release packaging coverage

The build-driver self-test creates a temporary synthetic packaging tree with:

- a normal versioned release staging directory;
- a nonempty fake jpackage app-image launcher;
- one nonempty fake versioned EXE installer.

It then verifies that the packaging implementation:

- creates exactly `IBC_Manager_9.8.7_Release_windows.zip` for a synthetic 9.8.7
  release;
- retains the normal release JAR and `run.bat`;
- includes the generated installer;
- creates and reads back `SHA256SUMS.txt` for the installer;
- validates a temporary ZIP before activating the final Windows release path;
- rejects a second direct EXE under the jpackage output directory.

Static tests verify that the Windows batch script:

- runs all existing test, JAR, and GUI smoke gates first;
- creates the app image before the installer;
- creates the Windows ZIP only after the installer command;
- uses version 1.0.8 consistently for the JAR, jpackage application version,
  installer, and ZIP filename;
- reports a missing Windows ZIP as a packaging failure.

## Full regression coverage retained

The complete suite continues to cover:

- profile serialization, validation, and multi-profile conflicts;
- line-preserving IBC configuration editing and secret removal;
- DPAPI command handling, redaction, and permission hardening;
- runtime configuration and temporary credential-file cleanup;
- process ownership, exact reattachment, port preflight, IBC commands, and log
  parsing;
- graceful Stop, Pause, Restart, and Start confirmations;
- diagnostic export and redaction;
- Task Scheduler command construction;
- official IBC 3.24.1 download and hostile ZIP protections;
- profile-dialog layout and `SecondFactorDevice` handling;
- Java prerequisite selection and Windows PowerShell source invariants;
- cross-platform subprocess fixtures and single-instance locking;
- source architecture, dependency, licensing, and release invariants.

## Artifact validation

The release process validates:

1. build-driver self-test, including synthetic Windows ZIP assembly;
2. strict production and test compilation;
3. all 425 automated tests;
4. versioned JAR creation;
5. JAR `--version` and isolated `--headless-smoke` execution;
6. real `MainFrame` GUI smoke under Xvfb;
7. normal release and source ZIP creation;
8. repeated deterministic normal release builds;
9. extracted normal release execution;
10. extracted source clean rebuild and complete retest;
11. ZIP integrity, source cleanliness, CRLF/no-BOM scripts, class-file target,
    module dependency, and absence of bundled official `IBC.jar`;
12. patch reproduction from the 1.0.7 source baseline;
13. final SHA-256 artifact-manifest verification.

## Windows-native limitation

The validation environment cannot run Windows PowerShell 5.1, WiX, or Windows
`jpackage`. It therefore cannot produce the genuine EXE or the final genuine
Windows ZIP. The archive assembly and all failure cases are tested with
synthetic EXE files.

The remaining native validation command is:

```bat
package-windows.bat
```

A successful Windows run must create:

```text
dist\windows\IBC Manager\IBC Manager.exe
dist\windows\IBC Manager-1.0.8.exe
dist\IBC_Manager_1.0.8_Release_windows.zip
```

After extraction, `SHA256SUMS.txt` inside the Windows ZIP must match the
installer bytes.
