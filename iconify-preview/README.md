# Iconify Icon Preview

JetBrains IDE plugin that draws [Iconify](https://iconify.design) icons next to their names in the editor, e.g.
`<Icon icon="emojione-monotone:atm-sign" />`.

- Inline preview in the editor's text color, in any file type.
- Hover for a 64 px preview, click to open the icon on iconify.design.
- Only prefixes of real Iconify sets are previewed (the list comes from `/collections` and is refreshed weekly).
- Icons are downloaded once from the Iconify API and cached in `<IDE system dir>/iconify-preview/`.

## Build and install

```bash
./gradlew test buildPlugin     # → build/distributions/iconify-preview-<version>.zip
./gradlew verifyPlugin         # Plugin Verifier against 2025.2 and the latest release
./gradlew runIde               # sandbox IDE with the plugin loaded
```

Install the zip with *Settings → Plugins → ⚙ → Install Plugin from Disk…*. No restart needed.

## Structure

| File | Role |
|---|---|
| `IconNameScanner.kt` | Finds quoted `prefix:name` strings (no IntelliJ dependencies) |
| `IconifyClient.kt` | Collection list, SVG download, disk cache, coloring; publishes `TOPIC` when icons load |
| `EditorIconPreview.kt` | Per editor: inlays, hover popup, click to open, refresh on edit/theme/load |
| `IconInlayRenderer.kt` | Paints the icon scaled to the line height |
| `Listeners.kt` | Attaches previews to editors (also after a dynamic install) |
| `IconifyPreviewSettings.kt` / `IconifyPreviewConfigurable.kt` | *Settings → Tools → Iconify Icon Preview* |

User-facing texts live in `messages/IconifyPreviewBundle.properties` (English) and `_es.properties` (Spanish), with the
same keys.
