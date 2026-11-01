# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## 0.0.2

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

### Fixed

- CHANGELOG / copier version typo `0.0,1` → `0.0.2`.
- Added `uv.lock` for hexdoc CI `--frozen` sync.
- Added empty `src/generated/resources` placeholders for Loom/datagen paths.

### Migration notes

- Pre-0.0.2 note saves under `world/dimensions/<dim>/data/hexguide_notes.dat` are **not** auto-merged into the new overworld `world/data/hexguide_notes.dat`. Re-import with `/hexguide export` + `import`, or copy the overworld-side file manually after backing up the world.

## 0.0.1

### Added

- Initial version.
