# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.0.2

### Added

- **Demo step controls** (spellcasting demo page): a **Step** button advances exactly one step (pausing first if it was playing), a `current/total` step counter shows in the page corner, and a failed step now freezes the demo with the pattern left red until you press Play / Step / Restart. All button labels go through lang keys (`hexguide.demo.button.*`).
- **Scrollable note index** (`hexguide:notes_index`): mouse wheel scrolls the section list with an `x-y / total` range indicator and ▲▼ buttons; clicking a row selects that absolute section and jumps to the notes entry's **native Patchouli page 0** (first iota of the section). The wheel is only consumed by the index page, so normal book paging still works elsewhere.
- **Native multi-page note viewer** (`hexguide:notes`): keeps Patchouli's ~256 `note_page` spread so book page-turning and bookmarks work as usual; each page shows the Nth iota of the current section. Empty / not-yet-synced sections show a friendly hint instead of a totally blank page. (A short-lived single-page ◀▶ viewer was tried and reverted in favor of this native layout.)
- **Note → spellplay drafts**: the `demo` pattern accepts a list whose first element may be a note iota (its title becomes the demo title/filename) and prints a click-to-copy path hint; new `/hexguide demoexport <index>` extracts recognizable `iota:` / `pattern{...}` references from a note section into a demo draft (unparseable references are skipped and counted). Drafts go to `<gameDir>/hexguide/spellplays/`.
- **Clipboard note sharing**: `/hexguide export <index> clipboard` encodes a section as a compact `HG1:` string (JSON → DEFLATE → Ascii85) and copies it to your clipboard; `/hexguide import clipboard <string>` validates it server-side and adds it as a new section. Hard limits: 8 KiB string, 128 KiB inflated, 64 pages, 64/2048 chars title/body, 10 attempts per minute — all enforced on the server.
- Pure-JVM `NoteShareCodec` unit tests (round-trip, bad prefix, over-length, decompression bomb, Ascii85 edge cases) under `common/src/test/kotlin/`.

### Changed

- Hex Casting dependency bumped to **0.11.4**.
- Build toolchain aligned with HexAnime: Kotlin **2.2.21**, Architectury Loom **1.9.436**, Gradle **8.14**, Fabric Loader/API bump.
- Player notes `SavedData` now lives on the **overworld** data storage so notes persist across dimensions.
- Inline is an **optional** soft dependency (publish metadata + runtime `Platform.isModLoaded` guard). Without Inline, iota inline rendering is disabled; other features still load. The `iota:` encode/decode/file code was moved into `IotaInlineCodec`, which does not reference Inline classes, so `copy` and demo pages work without it.
- Book demo casting uses a virtual media pool (no inventory drain / overcast from demos). Patterns still truly execute and may affect the world.
- C2S packets for book demo / stack sync / note save gain rate limits, signature checks, and iota-type / NBT size guards (`SecurityGuards`). Uploaded cast images are checked too, so a client cannot smuggle entity/continuation iotas in through the stack.
- Per-player note library caps (sections / iotas per section), so a client cannot grow the server save without bound.
- CurseForge publish workflow default is **off** until `curseforgeId` is filled.
- English lang keys aligned with Chinese where missing; empty/`TODO: fill` English strings filled with usable placeholders (layout unchanged).
- Note command feedback (export/import/authority/share) moved from hardcoded Chinese strings to lang keys (`hexguide.cmd.*`), so both locales are complete.

### Fixed

- CHANGELOG / copier version typo `0.0,1` → `0.0.2`.
- Added `uv.lock` for hexdoc CI `--frozen` sync.
- Added empty `src/generated/resources` placeholders for Loom/datagen paths.

### Migration notes

- Pre-0.0.2 note saves under `world/dimensions/<dim>/data/hexguide_notes.dat` are **not** auto-merged into the new overworld `world/data/hexguide_notes.dat`. Re-import with `/hexguide export` + `import`, or copy the overworld-side file manually after backing up the world.

## 0.0.1

### Added

- Initial version.
