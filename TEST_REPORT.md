# IBC Manager 1.0.21 test report

## Release identity

- Application version: **1.0.21**
- IBC download channel: **latest published official GitHub release**
- IBC compatibility floor/reference: **3.24.2**
- Change scope: dynamic latest-release resolution and validation
- Java source target: **Java 17**

## Latest-release validation

The release gate verifies that IBC Manager:

- queries only `https://api.github.com/repos/IbcAlpha/IBC/releases/latest`;
- rejects draft, prerelease, malformed, duplicate-key, non-numeric, and
  below-floor metadata;
- selects exactly `IBCWin-<resolved-version>.zip` from the official release;
- rejects unexpected hosts, paths, ports, user information, queries, fragments,
  duplicate Windows assets, missing digests, and unsafe asset sizes;
- verifies the downloaded byte count and SHA-256 against both transfer results
  and GitHub's release-asset metadata;
- validates external and embedded IBC versions, required distribution files,
  JAR classes, launcher switches, and referenced helper scripts;
- accepts compatible future numeric releases in deterministic fixtures;
- reuses an existing installation only when it matches the currently resolved
  latest release and never silently overwrites a different non-empty tree;
- retains 3.24.2 only as the compatibility floor and retained reference template;
- uses 1.0.21 consistently in JAR, normal release ZIP, source ZIP, Windows
  packaging scripts, GUI title, and documentation.

## Automated result

- Production Java files compiled: **119**
- Test Java files compiled: **24**
- Automated test cases: **541 passed**
- Assertions: **8,125 passed**
- Failed tests: **0**
- Skipped tests: **0**
- Compiler warnings: **0; warnings treated as errors**
- Class-file target: **Java 17 / major version 61**

Compilation uses:

```text
javac --release 17 -encoding UTF-8 -Xlint:all -Werror
```

## Release gates

- Three complete clean build/test/package runs passed.
- JAR, release ZIP, and source ZIP hashes were identical across all three runs.
- Packaged JAR `--version` and isolated headless smoke passed.
- Real-window Swing GUI smoke passed under Xvfb.
- Release and source ZIP integrity/path/duplicate checks passed.
- Extracted release execution passed.
- Extracted source clean rebuild and full retest passed.
- Extracted-source JAR and ZIP artifacts were byte-identical to the originals.
- Windows `.bat`/`.ps1` CRLF and no-BOM policy passed.
- Source archive cleanliness and absence of bundled official `IBC.jar` passed.

## Platform boundary

The Java/cross-platform release gate cannot execute Windows-native WiX,
`jpackage --type exe`, DPAPI, NTFS ACL, Task Scheduler, a live GitHub download
through the GUI, or a real IBC/Gateway session. `validate-windows.bat` and
`package-windows.bat` remain the final native Windows acceptance path.
