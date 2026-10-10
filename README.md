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
enter the following: `startserver 1`
<br>
then, make a new tab and enter the following: `startserver 2`
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
   are not overwritten. The `backup`, `backups`, `delbackup`, and
   `loadbackup` commands are added to the workspace terminal PATH. Rebuild
   or reopen the dev container after changing its configuration, then open a
   new terminal. The backup command validates the Overworld `region/` folder
   and the Nether/End folders' `DIM-1/region/` and `DIM1/region/` paths.
3. Check that the archive was created:

   ```sh
   backups
   ```

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
   Server configuration and plugins are not replaced.
5. Start the server again as usual and check that the restored world loads.

The loader prefers the world matching `level-name` in
`server/server.properties`. If an archive contains multiple worlds and none
matches, it stops instead of guessing. Do not extract an archive over the
live server folders manually.

## Custom TNT

The server includes a Bukkit plugin that adds nuke, mining, fire, slime,
x-ray, sun, and black hole TNT. The server prompts clients to download the 1.8-compatible
resource pack served from `web/custom-tnt-pack.zip`, which gives each custom
TNT item a block-shaped TNT icon with the regular TNT band and lettering,
recolored by type: red/white for nuke, gray/white for mining, orange/white for
fire, green/white for slime, cyan/white for x-ray, gold/white for sun, and
black/white for black hole. The types keep their distinct explosion effects.
Because this pack is compatible with 1.8 clients, it recolors the corresponding
vanilla item textures too (gold ingots, fire charges, slimeballs, diamond ore,
glowstone dust, and ender pearls).
The plugin adds matching colored block-fragment particles around each primed
TNT and its blast. The vanilla 1.12.2 client still renders the primed TNT
entity itself with the standard TNT texture. In Bukkit 1.12, the creative
inventory still exposes the base TNT item type rather than separate item IDs,
so the supported access path remains `/customtnt give ...` and the custom
resource pack. This is a vanilla creative-menu limitation, not a missing
plugin registration.
For browser clients served from a different website origin, keep the
CORS-enabled pack endpoint running with
`python3 custom-tnt-resource-pack/serve_pack.py`; port 8082 must be publicly
forwarded so clients can download the pack.
Operators can issue types with
`/customtnt give <player|@a|@s|@p> <nuke|mining|fire|slime|x-ray|rdm|mob|poop|glitch|sun|black-hole> [amount]`
(`/ctnt` is an alias). `@a` gives TNT to all online players, `@s` to the
commanding player, and `@p` to the nearest player in the commander's world.
Place TNT-icon variants normally; right-click a block while holding mining,
fire, slime, x-ray, sun, or black-hole TNT to place and prime it. Right-clicking
air will not throw pearls or otherwise use the item. Custom TNT is consumed on
placement, except in Creative mode.
rdmTNT (`rdm`, a prismarine shard icon) replaces every solid block within a
10-block radius with random blocks; containers and bedrock are left alone.
Mob TNT (`mob`, a bone icon) spawns 40 random mobs within a 10-block radius.
Poop TNT (`poop`, a clay ball icon) builds random brown wool and brown
concrete piles plus scattered brown blocks on the surface within 10 blocks.
Glitch TNT (`glitch`, an eye of ender icon) fills about a quarter of the air
within 10 blocks with nether portal blocks.
Fire TNT makes a fiery, fire-setting explosion. Nuke TNT has an approximately
8-block-wide blast and an expanding particle shockwave. Mining TNT drills a
cardinal 3x3 tunnel. X-Ray TNT clears a 10x10 area while leaving ores intact.
Sun TNT lights a 10x10 area to full brightness for 60 seconds. Slime TNT no
longer turns nearby blocks into slime; it now emits a slime burst without
converting terrain. Black hole TNT uses a high explosion power to target an
approximately 30-block physical blast radius, with portal/smoke effects, and
pulls nearby surviving entities inward while applying brief blindness,
slowness, and wither effects. Native Minecraft explosions vary with terrain
resistance, so their damaged area is approximate. Explosion power and effect
size are configurable in `server/plugins/CustomTNT/config.yml`; `/customtnt reload`
applies changes.

To build the plugin after changing its source, run
`gradle -p custom-tnt-plugin build` from the repository root. Copy `custom-tnt-plugin/build/libs/CustomTNT-1.0.0.jar`
to `server/plugins/` and restart the server.
