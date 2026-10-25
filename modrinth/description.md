# Where tf is that item?

Open your inventory and every item in the game, vanilla and modded, is right there beside it. Click one to see how to get it, where it generates, what it's used for and how to enchant it.

WTFI is a recipe viewer that also answers the question every other one skips: **where do I actually find this?**

---

## The library

- Sits next to your inventory, chests, crafting tables, furnaces and every other container. Each screen can be turned on or off.
- Built from the game's own menu textures, so it looks like vanilla, and resource packs that retexture menus restyle it too.
- **Search** by name, `@mod`, `#tag` or `$tooltip` text. Add `-word` to exclude something. Terms can be combined.
- **Filter** by Blocks, Tools, Weapons, Armor, Food, Potions, Enchanted books, Redstone, Spawn eggs, Creative only, Renewable, Non-renewable, Craftable now, In my storage and New.
- **Sort** by name, mod, ID, attack damage, damage per second, armor, durability, food, saturation, fuel time or enchantability.

## Item pages

Click any item, or hover any item in any inventory and press `R` or `U`.

- **Overview:** stack size, durability, damage, armor, food, fuel, hardness and more. For blocks, what they drop; for mobs, what they drop and where they spawn. Also shows a side-by-side comparison with what you're holding or wearing, and which advancements need the item (with ✔ once you've done them).
- **How to get:** every recipe drawn like its station (crafting table, furnace, stonecutter, smithing table, brewing stand), with what you're still missing. Then loot with approximate chances and conditions ("needs Silk Touch", "player kill only"), villager trades, and tool interactions like stripping logs.
- **Where to find:** which biomes and heights it generates at, checked against where you're standing ("↓ Go down 98 blocks"). Also which structures and mobs carry it, and which of *your* chests hold it.
- **Uses:** every recipe it goes into (ones you can craft right now come first), villagers that buy it, fuel time and composter chance.
- **Enchanting** (for enchantable items and books): enchanting table odds at every level, the best number of bookshelves, what a book costs on the item in your hand, and the cheapest anvil order for the books you carry.

## Things other recipe viewers don't do

- **Storage memory:** WTFI remembers what's in every chest you open. Ask where your diamonds are and it lists the chests, then outlines the one you pick through walls.
- **Craft from storage:** missing an ingredient? The recipe tells you which chest has it.
- **Gathering checklist:** click `+` on any recipe and the materials appear on screen, counting down as you collect them.
- **Hunt mode:** pin an item and a small box tells you if you're at the right height and in the right biome, which nearby structures or mobs have it, and the nearest chest that holds it.
- **What's this?** Look at any block or mob and press `H`.
- **Survival status:** every item is marked *Survival obtainable* or *Creative only*, and *Renewable* or *Non-renewable*, with the reason.
- **Brewing chains:** the whole path from a water bottle to the potion you want, not one step at a time.
- **Enchanting planner:** table odds simulated with the game's own enchanting code, anvil uses left before "Too Expensive!", and grindstone XP.
- **New items:** after you add a mod or update the game, items you've never seen get a yellow dot and their own filter.

## Controls

| Key | What it does |
|---|---|
| `O` | Show or hide the library |
| `R` / `U` | How to get / uses of the hovered item |
| `H` | What's this? (the block or mob you're looking at) |
| `J` | Close the hunt / checklist box (or open chat and click its ✕) |
| `Ctrl` + `F` | Jump to the search box |
| Right click search | Clear the search |

All keys can be changed in Controls.

## Cheat mode

Turn it on with the command block button. Clicking an item then gives you one; Shift+click gives a stack; right click still opens the page. It needs creative mode or operator permission, and the server checks this.

## Multiplayer

- **Server has WTFI:** you see the server's real recipes, loot, trades and world generation, including its datapacks and mods.
- **Server doesn't have WTFI:** it falls back to vanilla data built into the mod, and says so on the page.
- **Server only:** fine to install. Players without the mod aren't affected.

## Compatibility

- Modded items, recipes, loot tables, villager trades, world generation and enchantments show up automatically.
- Tested with Sodium, Iris, ImmediatelyFast, Lithium, FerriteCore and Mod Menu.
- Not tested alongside JEI, REI or EMI yet.

## For mod and pack makers

WTFI reads everything from the game's data. To teach it things the data can't express:

- Lang keys `wtfi.note.<namespace>.<item>.obtain` and `.use` add notes to an item's page. An obtain note also marks the item as survival-obtainable.
- `assets/<namespace>/wtfi_flags/*.json` can mark items `renewable`, `non_renewable`, `creative_only` or `obtainable`, by ID or by regex pattern.

## Good to know

- Loot chances are estimates. Things like Looting, biome or weather are listed, not calculated.
- Blocks that only appear inside structures (like purpur in End Cities) aren't listed under natural generation.
- Requires Fabric API. Mod Menu is optional (adds a settings button).
