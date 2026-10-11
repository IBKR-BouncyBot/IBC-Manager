# JavaFX dependency and startup error correction - IBC Manager 2.1.0

## Cause established by the October 11 diagnostic

The 16:56:33 UTC export contains three failed fresh starts around 18:54-18:56 local
Europe/Amsterdam time. Each JVM exits before the IBC main method with:

```
Error occurred during initialization of boot layer
java.lang.module.FindException: Module jdk.jsobject not found, required by javafx.web
```

At 16:54:46 UTC the setup probe had already returned that same failure. The prior
optional-check implementation classified all nonzero probe exits as unavailable and
continued installation. The wrapper then printed Normal exit despite its child code 1,
so Manager incorrectly displayed a normal stopped state. This is not a new RDP or
2FA failure: the VM does not get far enough to construct Gateway or start login.

## Corrected dependency set

The complete OpenJFX set is `javafx.base`, `javafx.graphics`, `javafx.controls`,
`javafx.fxml`, `javafx.media`, `javafx.swing`, `javafx.web`, and **`jdk.jsobject`**.
The last module does not follow the javafx.* naming prefix. The installer previously
hard-coded only the first seven. JavaFX has published the additional module since 24.

For the selected stable JavaFX 25 version V, the extra Windows artifact is:

```
https://repo.maven.apache.org/maven2/org/openjfx/jdk-jsobject/V/jdk-jsobject-V-win.jar
```

It is installed as `payload/javafx/jdk.jsobject.jar` beside the other modules, included
in the launch/module inventory, local ownership hashes and download provenance, and
removed through the existing last-owner transaction. The JBR selection remains on
stable Windows-x64 vanilla Java 25. No files are borrowed from Microsoft's or Azul's
runtime. Do not replace the requested platform JAR with Maven's unclassified empty JAR.

Every downloaded module descriptor is read and checked for its module name and 25
version. Core JavaFX native libraries are still checked. Publisher checksums retain
the requested optional-download policy. An explicit JVM boot-layer/FindException
failure is now a real setup error, not an unavailable check; blank or unavailable
probe output and generic probe-execution failure remain optional as before.

## Repair an existing seven-library installation

1. Close the old Manager and use the complete corrected package in a fresh folder.
   Keep the same data directory and Windows account. Back up data first.
2. Stop every profile using the affected TWS/Gateway installation.
3. In each owning profile, deselect the managed workaround and Save. The last owner
   restores prior .vmoptions and removes only recorded Manager-owned runtime files.
4. Reopen the affected profile, enable the workaround, confirm, and Save. The new
   download now includes jdk.jsobject at the same selected JavaFX version.
5. Start the profile only after Save completes. Repeat the previously qualifying
   disconnected-RDP/locked-session restart separately.

No profile/password recreation or manual cache/hash deletion is needed. A restored
manual Java override may still be invalid if the user previously deleted that manual
runtime; enabling managed setup replaces it with the newly installed managed path.
Keep the old seven-library native-cache removal logic functional during this repair.
Editing and saving without toggling the workaround does not silently upgrade the
owned payload. Missing dependencies report these explicit reinstall instructions.

## Tests and limits

New publisher/transaction tests use compiled module descriptors, including javafx.web's
requires jdk.jsobject edge. The real Java Platform Module System resolver rejects the
seven-module graph and resolves the eight-module graph. The resolver test intentionally
excludes Java 21's host-image hashes: Java 24+ makes jdk.jsobject upgradable, whereas this
Linux test host uses Java 21. This is dependency-graph validation, not execution of
native JavaFX 25 or Gateway on Windows. A first attempted Java21 minimal-JVM test exposed
that host hash difference; it was not counted as a successful application test.

Tests cover missing/corrupt/incorrect/empty jsobject artifacts, absent checksums,
unavailable versus known-invalid probes, cancellation, rollback, safe old-payload
removal with a native cache, parser lifecycle and the exact misleading wrapper footer.

Microsoft Java25 bootstrap, engine revision 3.24.2-manager.5, profile format 7, licence,
short confirmation, TWS support, profile copy and existing reliability fixes are retained.
Live Maven/JetBrains download and native Windows execution were unavailable here.

## Primary references

- https://openjfx.io/highlights/24/ (jdk.jsobject is included with JavaFX)
- https://openjfx.io/javadoc/25/ (the additional module alongside seven core modules)
- https://github.com/openjdk/jfx/blob/master/modules/javafx.web/src/main/java/module-info.java
- https://central.sonatype.com/artifact/org.openjfx/jdk-jsobject (platform-classified dependency)
