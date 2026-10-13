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

## Plugins

### Custom TNT

Adds nuke, mining, fire, slime, x-ray, sun, black-hole, rdm, mob, poop, and
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
After rebuilding the pack, update `resource-pack-sha1` and `?v=` in
`server/server.properties`.
Explosion settings are in `server/plugins/CustomTNT/config.yml`; apply changes
with `/rldcustomtnt`.

Build with `gradle -p custom-tnt-plugin build`, copy
`custom-tnt-plugin/build/libs/CustomTNT-1.0.0.jar` to `server/plugins/`, then
restart the server.
