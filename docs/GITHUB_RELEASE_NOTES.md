# IBC Manager 2.0.3

Status presentation and documentation update. Windows and IB Gateway only.

Green now reads **Gateway running - API listener available**. API-handshake and
account verification are explained in an on-demand tooltip rather than presented
as a failed check. The permanent API notice below Overview is removed. Other
states have clearer headlines, compact list labels and unchanged warning colours.

The README is shorter, uses a current GUI smoke-test screenshot, includes the
supplied Thank me section and retains GPL licensing/attribution. Engine
3.24.2-manager.3 and profile format 6 are unchanged; there is no authentication or
recovery policy change. This release does not claim to resolve phone-delivery
behavior reported during real Gateway authentication tests.

Stop profiles and exit the previous Manager before updating. Back up the data
folder and retain the same Windows account/computer for saved credentials.
Run the Java release with run.bat. Build a native Windows installer/portable archive
from source with package-windows.bat; native outputs require a Windows build.

See TEST_REPORT.md and the final validation logs for executed checks and limits.
