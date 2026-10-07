# FOX ONE V12 — presentation refresh

## Modules

- `src/presentation/java/com/ponie/dayov12/FoxAwgUi.java`: stable Activity entry points.
- `src/presentation/java/com/ponie/dayov12/ui/FoxTheme.java`: spacing, typography, colors, surfaces and touch targets.
- `.../FoxDashboard.java`: home layout and foreground-only refresh lifecycle.
- `.../FoxConnectionCard.java`: read-only transport status and connection actions.
- `.../ConfigImportController.java`: document selection, bounded import and error handling. Uses the existing encryption/parser APIs.
- `.../LegacyViews.java`: the explicit V12 view contract. Reuses original View objects, listeners and settings; never controls packets.
- `src/presentation/stubs`: compile-only signatures, excluded from the APK.
- `src/backend-reference`: recovered integration source for inspection. Not recompiled by this workflow; original backend bytecode is retained and compared.
- `scripts/repair_startup.py`: audited startup and update-popup repairs.
- `scripts/install_presentation.py`: replaces old AWG presentation classes and enables V12's reduced-motion path.
- `scripts/repack_startup.py`: preserves original NP asset names/resources and package identity.
- `scripts/audit_startup.py`: compares every resolved DEX method, validates the tiny legacy allowlist, requires backend/native/resource identity.
- `scripts/runtime_presentation.py`: emulator UI checks including import cancellation and Activity resume.

## Build and compatibility

GitHub Actions compiles Java 8 presentation code with Java 17, Android API 35 and D8. No new runtime framework or network dependency is added. It packages only the new presentation classes, rebuilds the three DEX files, and retains the existing signing identity, application ID, native AWG engine, preference keys and encrypted configuration location.

The experimental APK and private project ZIP are immutable input archives, verified by SHA-256 before decoding. They are not the actively maintained UI source. Never hand-edit or overwrite them to implement a new feature.

## Preservation boundary

No changes to MyVpnService, FloatingService, FoxTransport, FoxNative, FoxConfigStore or FoxAwgConfig are allowed by the audit. Existing settings widgets and start/stop handlers are reused. The update popup stays suppressed and the key screen stays removed. The APK's name/version remain FOX ONE V12.

The original obfuscated Activity and NP resource loader remain compatibility dependencies. This is an incremental separation of presentation, not a claim to have recovered and refactored the whole original Java project. Removing legacy code requires a dependency proof; the current audit deliberately rejects broad deletions. The old AWG dialog and its obsolete synthetic UI helpers are removed and rebuilt from readable source.

## Verification limits

Emulator tests verify UI launch, navigation, the document picker cancellation, and lifecycle behavior on Android 15/16. They do not establish an Amnezia handshake with a real server or prove gameplay outcomes. Backend method equality preserves the existing implementation, including any pre-existing limitations.
