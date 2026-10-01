# EaglerMOTD for Velocity

Velocity-native port of EaglerMOTD for EaglercraftXServer. It supports the original JSON message/frame format, animated text and icons, player totals/lists, and static custom query responses.

Build from this directory with `gradle build`. The plugin jar is written to `build/libs/EaglerMOTD-Velocity-1.0.4-velocity.1.jar`; copy it to `../bungee/plugins/` and restart Velocity.

On first startup, the plugin creates `plugins/eaglermotd/messages.json`, `frames.json`, and `queries.json`. Edit those files and run `/motd-reload` to apply changes. Listener-specific groups may use the listener name or its bound address; `all` is the fallback group.

The port uses the native MOTD APIs from both EaglercraftXServer and EaglerXVelocity, and requires both `eaglerxserver` and `eaglerxvelocity`. It does not load the original BungeeCord jar.