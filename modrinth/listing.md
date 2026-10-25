# Modrinth listing for WTFI

Fill these in on modrinth.com → **Create a project**, then the project's **Settings** tabs. Author: probablyjeth.

## 1. Create project dialog

| Field | Value |
|---|---|
| Project type | Mod |
| Name | `Where tf is that item (WTFI)` |
| URL (slug) | `wtfi` (free as of 25 Oct 2026; `where-tf-is-that-item` is free too) |
| Visibility | Public (or Unlisted while you set it up) |
| Summary | `A recipe viewer that also tells you where to find things: how to craft any item (modded too), where it generates or drops, what it's for, enchanting odds and the cheapest anvil order, plus storage memory and a hunt mode.` |

The summary is 220 of the 256 characters allowed.

## 2. Settings → General

| Field | Value |
|---|---|
| Icon | `modrinth/icon-512.png` |
| Client-side | **Required** |
| Server-side | **Optional** |

## 3. Settings → Description

Paste the whole of `modrinth/description.md`.

## 4. Settings → Tags

| Field | Value |
|---|---|
| Featured categories (up to 3) | **Utility**, **Storage**, **Adventure** |
| Additional categories | Equipment, Game Mechanics |

## 5. Settings → License

| Field | Value |
|---|---|
| License | **MIT License** |
| License URL (if asked) | `https://github.com/Haniel023/Where-TF-is-that-Item/blob/main/LICENSE` |

## 6. Settings → Links

| Field | Value |
|---|---|
| Issue tracker | `https://github.com/Haniel023/Where-TF-is-that-Item/issues` |
| Source code | `https://github.com/Haniel023/Where-TF-is-that-Item` |
| Wiki | (leave empty) |
| Discord | (leave empty) |

## 7. Gallery

Upload from `modrinth/gallery/`. Mark the first one as **Featured**.

| File | Title | Description |
|---|---|---|
| `01_library.png` (Featured) | The library | Every item in the game, right beside your inventory, in vanilla style. |
| `02_search.png` | Search | Search by name, @mod, #tag or $tooltip text. |
| `03_filters.png` | Filters | Filter by type, creative-only, renewable, craftable now, in your storage or new. |
| `04_how_to_get.png` | How to get it | Recipes show what you're missing and which of your chests has it. |
| `05_where_to_find.png` | Where to find it | Biomes and heights, checked against where you're standing. |
| `06_enchanting_odds.png` | Enchanting odds | Enchanting table odds for the item you're holding. |
| `07_anvil_planner.png` | Anvil planner | The cheapest order to put your books on an item. |
| `08_brewing_chain.png` | Brewing chains | The full brewing path from a water bottle. |
| `09_mob_lookup.png` | What's this? | Look at a mob and press H: what it drops and where it spawns. |
| `10_hud_hunt_checklist_storage.png` | Hunt mode & checklist | Live hints for finding an item, a gathering checklist, and your chest outlined through walls. |

These came from the automated test world at a small window size. Screenshots from your own game at a bigger window (or with shaders) will look better if you want to replace them.

## 8. Versions → Create a version

| Field | Value |
|---|---|
| File | `build/libs/wtfi-0.1.0.jar` (don't upload the `-sources` jar as the main file) |
| Version number | `0.1.0` |
| Version title | `WTFI 0.1.0` |
| Release channel | **Beta** |
| Loaders | Fabric |
| Game versions | 26.3 |
| Dependencies | **Fabric API**: Required. **Mod Menu**: Optional. |
| Changelog | Paste `modrinth/changelog-0.1.0.md` |

## Before you publish

- Modrinth's moderators review new projects. If they object to "tf" in the name, a fallback is `WTFI: Where's That Item`.
