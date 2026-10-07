# FOX ONE V12

A native Android presentation refresh for the existing private FOX + AmneziaWG build.

- Compact session controls and integrated AmneziaWG connection card.
- Existing floating controls, settings and packet processing retained.
- No access-key screen or forced-update popup.
- Readable, separately compiled presentation sources with a shared visual theme.
- GitHub Actions build, signed APK, method-preservation audit and Android 15/16 UI checks.

See [architecture and preservation boundary](docs/ARCHITECTURE.md). The backend reference sources are for inspection; the workflow intentionally retains the original DEX implementation rather than recompiling behavior-sensitive code.

Download the `FOX-V12-Modern` artifact from a successful **Build FOX V12 presentation refresh** workflow. Install `FOX_V12_Modern.apk` as an update to the existing signed build; uninstalling first removes app data and the imported configuration.
