# Single Profile configuration in 2.0.2

## User interface

Edit is the only configuration dialog. Its Profile tab contains Gateway installation,
credentials, ports, login/recovery policy and profile startup choices. Gateway settings
contains supported engine properties with their current generated value and source.
Windows startup contains Manager-level task controls, explicitly labelled as shared
and immediate. There is no Managed Config toolbar/menu, raw INI editor, existing/base
config selector or second credential-from-INI mode in the current interface.

Profile and menu Edit share availability. Saving rechecks the controller under the
same monitor used for start/recovery and rejects active or stale profiles. This closes
the old recovery-cooldown window in which no process was alive but fresh Start remained
pending. Normal Stop and Force Stop remain separate controls, not duplicate configuration.

The engine table excludes fields already owned by Profile, password properties, FIX
and TWS-only controls, the obsolete 2FA exit interval and legacy LogComponents alias.
ReadOnlyApi remains available; it is different from unsupported ReadOnlyLogin.
Unknown engine properties can be edited in the same table via Add engine property.
Removing/resetting an unknown row removes that property. It may have no effect if the
engine does not recognize it; no claim is made that arbitrary keys are supported.

## Value semantics

The Value cell is the value to be serialized for launch. Value source says Profile
or Included default. A blank value is intentional: IBC may use its getter fallback
or preserve Gateway's current setting. The Manager does not pretend it has queried
Gateway's actual configuration. Hover for setting-specific information.

Reset selected to included default restores the shipped default rather than a hidden
imported file. Imported effective settings are made explicit once, so a prior 120-second
login timeout is visibly 120, not blank while a tooltip suggests 60. For a sparse old
configuration, missing engine properties are imported as explicit blank/fallback values
rather than silently adding template policies.

Only the retry checkbox controls second-factor timing (enabled => 300 seconds).
There is no competing editable timeout field or obsolete exit-action choice. Disabling
the checkbox works by itself. The retry does not terminate the process tree.

## Authority and generated files

Format 6 has `configuration=profile` and cannot reference external/base configuration
or EXISTING_CONFIG credential mode. `profile.properties` is the authoritative non-secret
state; credential bytes stay in the Windows credential store associated with its UUID.
The generated per-profile `config.ini` is a non-secret diagnostic projection. It can be
regenerated, and editing/deleting it cannot change the Profile editor or runtime settings.
The old filename is retained for diagnostics and predictable layout, not a second editor.

The runtime INI remains necessary for the included engine and wrapper restarts. It is
built from the same profile model with temporary credential injection, owner restrictions
and the existing final cleanup lease. No password is stored in profile.properties.

## Upgrade transaction

After the existing 1.x integrated-engine migration, formats up to 5 are imported once
while stopped. Each profile gets an owner-restricted backup containing the original
profile, original managed config if present and original encrypted bytes if present.
A bounded validated journal makes interruption recoverable. New config/profile writes
are committed before the journal is removed. Failure restores the originals; an
incomplete restore retains its journal for another recovery attempt.

Managed/base effective advanced values and unknown settings are folded into the profile.
For legacy EXISTING_CONFIG mode the source is read once using actual Java Properties
semantics, its username is retained, and a nonempty password is saved through the current
Windows credential store. Empty-password legacy files become Manual. Missing source,
unavailable encrypted storage or invalid data fails the import rather than discarding
settings. The external source is not edited or deleted and is not used after success.
Existing encrypted-mode credentials are preserved byte-for-byte without decryption.

Profile IDs, paths, ports and explicit enabled/disabled retry choices are preserved.
Legacy TWS profiles remain disabled. The old 2FA dropdown is normalized to its inactive
exit compatibility value; LogComponents is translated once to current log controls.
Legacy readers/methods remain in source to support migration and their regression tests;
they are not another current UI or format-6 launch path.

Back up the whole data folder while stopped before upgrading. Keep the same Windows
account and computer. Do not run old/new Managers simultaneously, and do not let an old
version rewrite a format-6 profile. For rollback use the stopped full backup.
