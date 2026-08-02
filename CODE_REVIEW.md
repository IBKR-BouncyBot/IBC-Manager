# IBC Manager 1.0.8 code review and implementation report

## Review scope

Version 1.0.8 extends the Windows packaging path so a successful
`package-windows.bat` run creates a distributable ZIP containing the generated
Windows installer. The requested filename is:

```text
dist\IBC_Manager_1.0.8_Release_windows.zip
```

This is the normal release filename, `IBC_Manager_1.0.8_Release.zip`, with the
additional `_windows` suffix immediately before `.zip`.

No application runtime, profile, credential, IBC-control, or trading behavior is
changed by this release.

## Implementation

### Packaging order

`scripts\package-windows.bat` continues to execute the full release gates before
native packaging:

```text
self-test clean test jar smoke gui-smoke dist
```

It then creates, in order:

1. the self-contained jpackage application image;
2. the versioned Windows EXE installer;
3. the Windows release ZIP through the build driver's
   `windows-release-zip` target.

ZIP assembly therefore cannot run before the EXE installer has completed
successfully. Failure of any step returns a nonzero exit code.

### Windows release contents

The ZIP is assembled from the exact normal release staging directory created by
the `dist` target. It retains:

- `IBC-Manager-1.0.8.jar`;
- `run.bat` and the runtime prerequisite scripts;
- README, changelog, licence, notices, code review, and test report;
- the documentation and third-party notice trees.

The Windows-specific stage then adds:

- `IBC Manager-1.0.8.exe`;
- `SHA256SUMS.txt`, containing the SHA-256 of that installer.

The archive uses the same internal root as the normal release:

```text
IBC_Manager_1.0.8/
```

Only the archive filename receives the `_windows` suffix.

ZIP output is first written to a temporary file in `dist`. The temporary
archive is reopened and validated before it atomically replaces the final path
where the file system supports atomic moves; a replace move is used as the
portable fallback. Failed assembly therefore does not activate a partial final
release ZIP.

## Fail-closed validation

The build driver refuses to create the Windows release archive unless all of the
following are true:

- the jpackage app-image launcher exists and is nonempty;
- exactly one direct `.exe` installer exists under `dist\windows`;
- the installer is nonempty;
- the installer is named exactly `IBC Manager-1.0.8.exe`;
- the normal release staging tree exists;
- the normal release staging tree contains README, the versioned JAR, and
  `run.bat`;
- the completed ZIP can be reopened;
- the completed ZIP contains the installer, README, launcher, and checksum file;
- the checksum text read back from the ZIP exactly matches the generated value.

The direct-file scan deliberately ignores the app-image executable nested under
`dist\windows\IBC Manager`. A second installer-like EXE directly under
`dist\windows` is treated as ambiguous output and blocks the release.

## Test and self-test coverage

The executable build-driver self-test now creates a temporary synthetic normal
release, a fake app-image launcher, and a fake versioned installer. It then:

1. builds the Windows release ZIP;
2. verifies the exact `_Release_windows.zip` name;
3. opens the ZIP and checks installer, normal JAR, and `run.bat` retention;
4. verifies the generated installer checksum file;
5. adds a second direct EXE and proves that ambiguous output is rejected.

Static release tests additionally verify:

- ZIP creation occurs after `--type exe` in `package-windows.bat`;
- the script references the exact 1.0.8 Windows ZIP filename;
- the current JAR and jpackage application versions agree;
- the build driver contains installer selection, ZIP validation, checksum, and
  executable self-test protections;
- Windows scripts retain CRLF line endings and no UTF-8 BOM.

## Validation summary

- 91 production Java files;
- 23 test Java files;
- 425 automated test cases;
- 5,113 assertions;
- zero failed and zero skipped tests;
- strict Java 17 bytecode target;
- `-Xlint:all -Werror` compilation;
- packaged-JAR version and headless smoke checks;
- real Swing GUI smoke under Xvfb;
- deterministic normal release/source builds;
- synthetic Windows-release ZIP creation and failure-path validation;
- extracted normal release execution and extracted source rebuild/retest;
- release/source ZIP integrity and source-cleanliness checks.

## Platform limitation

The release environment is Linux. It cannot execute Windows PowerShell 5.1,
WiX, or Windows `jpackage --type exe`, so it cannot produce or validate a real
Windows installer. The ZIP assembly itself is exercised with synthetic EXE
outputs, but the final Windows-native gate remains running:

```bat
package-windows.bat
```

on Windows. A successful Windows run should create:

```text
dist\windows\IBC Manager\IBC Manager.exe
dist\windows\IBC Manager-1.0.8.exe
dist\IBC_Manager_1.0.8_Release_windows.zip
```
