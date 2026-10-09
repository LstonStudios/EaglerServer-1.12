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
enter the following: `cd bungee && sudo java -Deaglerxvelocity.stfu=true -jar bungee.jar`
<br>
then, make a new tab and enter the following: `cd server && sudo java -jar server.jar`
<br>
Now go to the ports area and forward (and make public) port `8081`
<br>
Load up the client! When you are in, copy the link, paste it in the add server section, replace https:// with wss:// and join!
Your eaglercraft server is setup!

PS: You can use this as a normal server too (like properly hosted) just clone this repo and run it in much the same way.

## Saving and loading world backups

Run these commands from the repository's top-level directory. Stop the
Minecraft server before saving or restoring so its world files are not being
changed while copied.

### Save a backup

1. Stop the Minecraft server.
2. Create a backup archive containing the Overworld, Nether, and End. The
   command below is for this repository's configured world name:

   ```sh
   mkdir -p backups
   tar -czf "backups/world-$(date +%Y-%m-%d-%H%M%S).tar.gz" -C server \
     "The Lston EaglerCraft Server" \
     "The Lston EaglerCraft Server_nether" \
     "The Lston EaglerCraft Server_the_end"
   ```

   If you changed `level-name` in `server/server.properties`, replace those
   three folder names with `<level-name>`, `<level-name>_nether`, and
   `<level-name>_the_end`. If the server uses a different world name, check
   the folder names under `server/`.
3. Check that the archive was created:

   ```sh
   ls -lh backups/
   ```

4. Start the server again as usual. Keep a copy of the archive somewhere
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
   python3 loadbackup.py
   ```

   Or provide the archive path directly:

   ```sh
   python3 loadbackup.py backups/world-2026-10-08-120000.tar.gz
   ```

4. Confirm the success message. The loader moves the current world folders
   into a timestamped `backups/pre-restore-*` folder before replacing them.
   Server configuration and plugins are not replaced.
5. Start the server again as usual and check that the restored world loads.

The loader prefers the world matching `level-name` in
`server/server.properties`. If an archive contains multiple worlds and none
matches, it stops instead of guessing. Do not extract an archive over the
live server folders manually.
