# Contributing

IBC Manager is a Java 17 Swing application with no third-party Java runtime
dependencies. Changes should preserve that boundary unless a proposal explains
why the dependency and supply-chain cost is justified.

## Build and test

From the source root, run:

```text
java src/build/java/io/github/ibcmanager/build/BuildProject.java clean test jar smoke dist
```

On Linux, run the real-window GUI smoke test with:

```text
xvfb-run -a java src/build/java/io/github/ibcmanager/build/BuildProject.java gui-smoke
```

On Windows, `build.bat`, `test.bat`, `validate-windows.bat`, and
`package-windows.bat` invoke the same JDK-native build driver.

## Change requirements

- Add regression tests for every corrected defect and important failure path.
- Keep compilation clean with `--release 17 -Xlint:all -Werror`.
- Do not write credentials to profiles, logs, diagnostics, command lines, or
  persistent managed IBC configuration.
- Do not use global window-title automation, simulated keyboard input, or global
  process termination.
- Keep process actions scoped to an exact PID/start-time identity.
- Treat application-owned control files as bounded, untrusted input and do not
  follow symbolic links.
- Preserve unknown IBC settings and comments when editing `config.ini`.
- Keep Windows batch and PowerShell files CRLF encoded without a UTF-8 BOM.
- Update the changelog, reports, user documentation, and version consistency
  tests for release changes.

## Pull requests

Describe the user-visible behavior, threat or failure model, tests added, and
platforms exercised. State explicitly which Windows-native paths were not tested.
Do not include generated `build/`, `dist/`, JAR, EXE, ZIP, or log files.
