# Where tf is that item (WTFI)

A Fabric mod for Minecraft 26.3. Open any inventory and a library of every item (vanilla and modded) appears beside it. Click an item to see how to get it, where it generates or drops, what it's used for, and how to enchant it.

## Requirements

- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API, Java 25
- Optional: Mod Menu (adds a "Configure" button)
- Install on the client. Installing on the server too is recommended (see Multiplayer).

## Using it

| Action | How |
|---|---|
| Show/hide the library | `O`, or the settings screen |
| Search | Click the box under the grid, or `Ctrl+F`. Supports `@mod`, `#tag`, `$tooltip` and `-word` to exclude. |
| Item page | Click an item (right click opens Uses). Hover any item in any inventory and press `R` (how to get) or `U` (uses). |
| What's this block/mob? | Look at it and press `H` |
| Close the hunt/checklist box | Press `J` (closes one section at a time), or open chat (`T`) and click the ✕ next to a section |
| Filters / sort / mod | Buttons at the top of the library |
| Cheat mode | Command block button. Click gives 1, Shift+click a stack, right click still opens the page. Needs creative or operator. |

All keys can be rebound in Controls.

### Item page tabs

- **Overview**: stats, block drops, mob drops and spawn biomes, comparison with what you're holding or wearing, tags, related advancements (✔ done / not done).
- **How to get**: survival verdict (obtainable / creative-only / renewable / non-renewable, with the reason), recipes for every station (what you're missing, and which remembered chest has it), loot sources with approximate chances and conditions, villager trades, tool interactions, full brewing chains for potions.
- **Where to find**: natural generation by biome and height (checked against where you're standing), structures and mobs that carry it, and your own containers that hold it ("Show me" outlines the chest through walls).
- **Uses**: recipes it goes into (craftable-now first), villagers that buy it, fuel/compost/brewing fuel, tool interactions.
- **Enchanting** (enchantable items and books): table odds at every level, best bookshelf count, cost to apply a book to your held item, the cheapest anvil order for the books you carry, anvil uses left before "Too Expensive!", and grindstone XP.

### On-screen helpers

- **Hunt mode**: click *Hunt* on an item page. A corner box tells you whether you're at the right height, in the right biome, which nearby structures or mobs have it, and the nearest chest that holds it.
- **Gathering checklist**: click `+` on any recipe. The materials (broken down to things you can actually find) count down on screen as you collect them.
- **Storage memory**: every container you open is remembered (client side, per world/server). Turn it off in settings.
- **New items**: items you've never seen (a new mod or game update) get a yellow dot and a "New" filter.

## Multiplayer

- **Server has WTFI**: the server sends its real recipes, loot tables, trades and world generation (datapacks and mods included) to every player with WTFI. Cheat mode is checked on the server.
- **Server doesn't have WTFI**: the client falls back to the vanilla data bundled in the jar. Item pages note this. Cheat mode uses `/give` (operator) or the creative inventory.
- **Server only**: harmless. Players without WTFI are unaffected.

## For mod and pack makers

WTFI reads everything from the game's registries and data, so modded items, recipes (anything that provides a vanilla `RecipeDisplay`, or JSON with ingredients and a result), loot tables, trades, world generation and enchantments show up automatically. To add knowledge the data can't express:

- Lang keys `wtfi.note.<namespace>.<item_path>.obtain` and `.use` add notes to item pages. An obtain note also marks the item as survival-obtainable.
- `assets/<namespace>/wtfi_flags/*.json` takes arrays `renewable`, `non_renewable`, `creative_only`, `obtainable`, plus regex lists `renewable_patterns` and `obtainable_patterns`.

## Building

```
./gradlew build                 # jar in build/libs
./gradlew runClient             # dev client
./gradlew runClientGameTest     # automated in-game test; screenshots in build/run/clientGameTest/screenshots
```

`runClientGameTest` also regenerates the bundled vanilla dataset (`build/wtfi/vanilla_dataset.dat`). Copy it to `src/main/resources/wtfi_data/` after a Minecraft update.

To test alongside other mods, put their jars in a folder and run `./gradlew runClientGameTest -PcompatModsDir=<folder>`. Don't include Fabric API; the dev environment already has it.

## License

MIT. See [LICENSE](LICENSE). Made by probablyjeth.

## Notes and limits

- Loot chances are estimates. Conditions like Looting, biome or weather are listed, not calculated.
- Natural generation covers features and terrain. Blocks that only appear inside structures (e.g. purpur in End Cities) aren't listed.
- Enchanting odds come from running the game's own enchantment code a few thousand times per level.
