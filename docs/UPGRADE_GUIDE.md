# Upgrading from 2.0.2 to 2.0.3

Stop profiles, exit Manager and back up its data directory. Replace the application
with the complete new release; keep the same Windows account/computer and data
folder. Engine revision 3.24.2-manager.3 and profile format 6 are unchanged. This is
a status/screenshot/documentation update, not a new migration or retry policy.

The previous migration guidance is retained below for older installations.

# Upgrade to 2.0.2 (profile format 6)

Stop all profiles and exit the old Manager first. Back up the complete data folder.
Run under the same Windows account/computer and reuse the same default or custom
`--data-dir`. Profile UUIDs and encrypted-password associations remain stable.

Existing formats are imported into one self-contained profile per Gateway. Old
Managed Config/base values are folded into visible Profile settings. Legacy
external-config credentials are read once and stored through Windows DPAPI;
without a password the profile becomes Manual. Source files are never changed.
Ordinary encrypted-mode credential bytes are preserved without decryption. A
missing/invalid legacy source prevents migration rather than erasing data.

An owner-restricted `upgrade-profile-config-backup-<id>` and bounded pending journal
protect each import. Partial transactions restore originals before retry. A live
tracked session prevents conversion. After success there is no external/base config
dependency, raw configuration editor or credentials-from-INI launch mode. The
remaining config.ini is generated output only, not a second configuration source.

Use Edit -> Profile / Gateway settings / Windows startup. Update the shared Windows
startup task from its new location inside the Profile editor if the EXE/JAR path changed.
Task changes are immediate; the Profile Save button controls the actual profile edits.
Reset of an engine setting now means the included default, not an old hidden base.

The new engine is 3.24.2-manager.3 and deploys in a new content-addressed directory.
Never replace an active engine cache in place. Keep the old release and the stopped
full data backup for rollback. Do not run both versions against the same directory.
Older applications cannot read format 6; restore the original full backup to roll back.

See `SINGLE_PROFILE_CONFIGURATION.md` for migration details.

For 1.x installations, the integrated-engine import runs first and retains its
original upgrade backup; the profile-only import then completes format 6. External
IBC engine paths remain ignored metadata. TWS profiles are retained disabled rather
than converted to Gateway. A custom data directory must still be supplied via
`--data-dir`; arbitrary disks are not searched automatically.
