# EaglerCraftX Server

## Credits
Original Project: Lax1Dude
<br>
1.12 Project: PeytonPlayz595
<br>
Original Server Fork: EcoliEater87
<br> 
Adapted to Canary Craft (ADSCRAFT): QuizzityMC
<br>

## To make an eaglercraft 1.12 server, follow the instructions below:
Here is how you can setup a connection:
<br>
<br>
First, go to the top of the repo and click on code > codespaces > create codespace
<br>
now you have your own free server instance to host eaglercraft. Next you need to run the setup commands:
<br>
<br>
Create a terminal tab and paste the following:<br>
<br>
enter the following: `startserver`
<br>
This starts both the game server and the EaglerCraft gateway. Press `Ctrl+C`
to stop them; the game server will save and shut down cleanly.
<br>
To start either server separately instead, use `startserver 1` for the gateway
or `startserver 2` for the game server.
<br>
Now go to the ports area and forward (and make public) port `8081`
<br>
Load up the client! When you are in, copy the link, paste it in the add server section, replace https:// with wss:// and join!
Your eaglercraft server is setup!

PS: You can use this as a normal server too (like properly hosted) just clone this repo and run it in much the same way.

## Eaglercraft voice chat

Eaglercraft voice chat is enabled on the WebSocket listener and EaglerXServer
voice service. Players need a compatible Eaglercraft client with microphone
permission. Voice uses browser WebRTC and the configured public STUN/TURN
servers; network/firewall policies may prevent some players from connecting.
The EaglerXServer configuration warns that its voice protocol is easy to stress
and abuse, so consider the risks before enabling it on a large public server.

## Saving and loading world backups

Stop the Minecraft server before saving or restoring so its world files are
not being changed while copied.

### Save a backup

1. Stop the Minecraft server.
2. From the repository's top-level directory, create a backup. You can give
   it a name:

   ```sh
   backup before-update
   ```

   This creates `backups/before-update.tar.gz`. Or omit the name to generate
   one automatically:

   ```sh
   backup
   ```

   Automatic names use the `world-YYYY-MM-DD-HHMMSS.tar.gz` format. Backups
   contain the configured Overworld, Nether, and End. Explicit names can
   contain letters, numbers, dots, underscores, and hyphens. Existing names
   are not overwritten. The `backup`, `backups`, `delbackup`,
   `loadbackup`, and `restorebackup` commands are added to the workspace terminal PATH. Rebuild
   or reopen the dev container after changing its configuration, then open a
   new terminal. The backup command validates the Overworld `region/` folder
   and the Nether/End folders' `DIM-1/region/` and `DIM1/region/` paths.
3. Check that the archive was created:

   ```sh
   backups
   ```

   `backups` hides the automatic `pre-restore-*` folders; see
   [Recover a pre-restore folder](#recover-a-pre-restore-folder).

4. To remove backup archives, run `delbackup`. Choose one or more menu codes
   (`1`-`9`, then `A`-`M`) separated by commas, then type `Y` to confirm or
   `N` to cancel. Enter `Q` at the selection prompt to quit. This command
   only deletes archive files; it does not delete `pre-restore-*` folders.
5. Start the server again as usual. Keep a copy of the archive somewhere
   outside this repository too, so it remains safe if the server files are
   lost.

### Load a backup

1. Stop the Minecraft server.
2. Put the `.zip`, `.tar`, `.tar.gz`, or `.tgz` backup in the repository's
   `backups/` folder. The archive must contain the world folder with both
   `level.dat` and `region/`; include sibling `<world>_nether` and
   `<world>_the_end` folders if you want to restore those dimensions too.
3. From the repository's top-level directory, run the loader and choose the
   listed backup:

   ```sh
   loadbackup
   ```

   Or provide the archive path directly:

   ```sh
   loadbackup backups/world-YYYY-MM-DD-HHMMSS.tar.gz
   ```

4. Confirm the success message. The loader moves the current world folders
   into a timestamped `backups/pre-restore-*` folder before replacing them.
   Server configuration and plugins are not replaced. These folders are
   not shown by `backups` or `loadbackup`.
5. Start the server again as usual and check that the restored world loads.

### Recover a pre-restore folder

To make a saved `pre-restore-*` folder loadable, run:

```sh
restorebackup
```

Choose a folder from the list (or pass one, e.g.
`restorebackup backups/pre-restore-YYYYMMDD-HHMMSS-ffffff`). This creates
`backups/restored-YYYYMMDD-HHMMSS-ffffff.tar.gz` and leaves the original folder
untouched. The archive then shows up in `backups` and can be loaded with
`loadbackup`.

The loader prefers the world matching `level-name` in
`server/server.properties`. If an archive contains multiple worlds and none
matches, it stops instead of guessing. Do not extract an archive over the
live server folders manually.

## Plugins

### Basic plugins

#### WorldEdit

WorldEdit (`worldedit-bukkit-6.1.9.jar`) is an in-game building tool for
operators. Select two corners with the wooden axe (`//wand`, left/right click)
or `//pos1` and `//pos2`, then edit the selection: `//set <block>` fills it,
`//replace <from> <to>` swaps blocks, `//copy`, `//paste`, `//undo`, and
`//redo` move or revert builds, and `//sphere` / `//cyl` / `//fill` make shapes.
Settings are in `server/plugins/WorldEdit/config.yml`.

#### WorldGuard

WorldGuard (`worldguard-bukkit-6.2.2.jar`) protects areas and requires
WorldEdit. Select an area with the WorldEdit wand, then
`/region define <name>` creates a region, `/region addowner <name> <player>`
and `/region addmember <name> <player>` choose who may build there, and
`/region flag <name> <flag> <allow|deny>` controls behavior (for example
`pvp`, `tnt`, `creeper-explosion`, `mob-spawning`, `entry`). Other commands:
`/region list`, `/region info <name>`, `/region remove <name>`. Global settings
are in `server/plugins/WorldGuard/config.yml` and per-world settings in
`server/plugins/WorldGuard/worlds/`. Note that region flags (such as `tnt` and
`other-explosion`) affect the Custom TNT plugin's explosions.

#### Spawn Mobs

Adds `/spawn <entity> <x y z> [amount] [radius]` (`/spawnmob` alias, requires
`spawnmobs.use`, default op) to spawn many entities in an area, for example
`/spawn pig -197 64 229 10` or `/spawn wither_skeleton ~ ~ ~ 10 5`. Entities
are spread randomly within the radius (default 3 when amount is more than 1,
max 50). Coordinates accept `~` relative values, amount is 1-100, and entity
names use Bukkit names such as `pig`, `wither_skeleton`, or `zombie`. It works
from players and command blocks, and `/spawn <entity>` spawns one at your feet.

Build with `gradle -p spawn-plugin build`, copy
`spawn-plugin/build/libs/SpawnMobs-1.0.0.jar` to `server/plugins/`, then
restart the server.

### Custom plugins

#### Custom TNT

Adds nuke, mining, fire, slime, x-ray, sun, black-hole, rdm, mob, boss, poop, and
firework TNT. Give variants with `/customtnt <player|@a|@s|@p> <type>
[amount]` (`/ctnt` alias); custom variants are not separate items in the
creative menu. Place most variants normally; right-click a block with mining,
fire, slime, x-ray, sun, or black-hole TNT to prime it. Items are consumed
except in Creative.

Firework TNT (`firework`, a firework star icon) launches about 30 random
fireworks in all directions when it explodes; the count is
`explosions.firework.size` (max 60).

The 1.8-compatible resource pack is `web/custom-tnt-pack.zip`. It retextures
reused vanilla items, which may also change their normal appearance. For browser
clients hosted on another origin, run
`python3 custom-tnt-resource-pack/serve_pack.py` and publicly forward port 8082.

Item textures replace that vanilla item's icon for all players. Give custom items their own
unused material where possible, then register each texture in
`custom-tnt-resource-pack/check_pack.py` and run it after changes.

Verity's 3D body is a voxel smiley sphere worn on her armor stand. Each facial expression
(happy, joy, sad, angry, surprised, neutral, evil) is its own model on an item players cannot
obtain: Barrier and the six silverfish-block variants. `python3
custom-tnt-resource-pack/build_verity.py` regenerates those models and textures; then run
`build_pack.py`. Keep `EXPRESSIONS` there in the same order as the item list in `VerityManager`.
Her face changes with what happens: questions, requests, compliments, insults, pats, gifts,
her owner getting hurt, dying, killing mobs, or rejoining, and her phase twists the faces
(phase 3 turns smiles flat, phase 4 turns them evil). Owners can also tell Verity "turn around",
"face me", "spin", "nod", "shake your head", "tilt your head", "look up" or "look down".
A blank face (Mob Spawner item) is shown while Verity can still be calmed.

Phase 4's Demon is a gaunt, T-posed skeleton model (`build_demon.py`, Command Block item) worn
by a marker armor stand that follows an invisible Wither Skeleton hitbox. Behaviours (toggles in
`server/plugins/Verity/config.yml`, `demon:` and `world-decay:`): a calm window where "I came
back for you" (or "I'm sorry" x3, looking at Verity within 10 blocks) ends it; otherwise it
hunts, targets villagers first, ignores water, climbs walls, smashes glass/doors/iron bars,
grabs and hurls the owner, eats kills (cobwebs), blocks sleeping within 100 blocks and kicks the
owner with "Farewell" on death. Killing it restores Verity (karma 100, phase 1). In phase 3 new
animals/villagers die and halfway through, nearby creatures vanish and time freezes.
After rebuilding the pack, update `resource-pack-sha1` and `?v=` in
`server/server.properties`.
Explosion settings are in `server/plugins/CustomTNT/config.yml`; apply changes
with `/rldcustomtnt`.

Build with `gradle -p custom-tnt-plugin build`, copy
`custom-tnt-plugin/build/libs/CustomTNT-1.0.0.jar` to `server/plugins/`, then
restart the server.

#### Custom Items

Adds custom items. Give them with `/customsword <player|@a|@s|@p> <item|mace|spear|wind|all>
[amount]` (`/cweapon` alias, requires `customitems.admin`, default op).

- `lightning`: Lightning Sword (gold sword, yellow and white). Hitting a living
  entity strikes lightning on it.
- `explosion`: Explosion Sword (iron sword, red and orange). Hitting a living
  entity causes a power-3 explosion (no fire, breaks blocks unless protected,
  and can hurt you if you are close).
- `pearl`: Pearl Sword (diamond sword, purple and cyan). Hitting a living entity
  teleports you to it.

Amount is 1-64. The swords are unbreakable and glow. The resource pack
retextures the vanilla gold, iron, and diamond swords, so every sword of those
types looks like the custom ones.

Build with `gradle -p custom-items-plugin build`, copy
`custom-items-plugin/build/libs/CustomItems-1.0.0.jar` to `server/plugins/`, then
restart the server.

Admins and ops can give enchanted paper redstone passes with
`/pass <player|@a|@p> [amount]` (`@s` is also supported; amount is 1-64).
Admins and ops give special enchanted stone buttons with
`/passbutton <player|@a|@p> [amount]` (`@s` is also supported; amount is 1-64).
Place a special button to make it require a pass; ordinary buttons keep their
normal behavior. Right-clicking a special button without a pass is blocked and
shows an error message. Holding a pass and right-clicking consumes one and
powers the special button for 3 seconds. Passes do nothing on ordinary buttons
(the click is blocked with a message). Special button locations are saved
across restarts and cleared when the button is broken.

#### Custom guns

Build with `gradle -p custom-gun-plugin build`, copy
`custom-gun-plugin/build/libs/CustomGun-1.0.0.jar` to `server/plugins/`, then restart.
Admins and ops use `/cgun <player|@a|@s|@p> <gun|all> (27 guns; run /cgun with a bad name to list them) [amount]`
(alias `/customgun`). All guns are golden hoes with a gun texture from the resource pack. Right-click to shoot; shots are instant, cost no ammo, and have a short cooldown.
