package net.lstonstudios.customtnt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.material.MaterialData;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

public final class CustomTNTPlugin extends JavaPlugin implements Listener {

    private static final String TNT_METADATA_KEY = "net.lstonstudios.customtnt.type";
    private static final String ITEM_MARKER_PREFIX = ChatColor.BLACK + "CustomTNT:";
    private final Map<TNTType, ExplosionSettings> explosionSettings = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadExplosionSettings();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("customtnt").setExecutor(this);
    }

    private void loadExplosionSettings() {
        Map<TNTType, ExplosionSettings> loadedSettings = new HashMap<>();
        for (TNTType type : TNTType.values()) {
            String path = "explosions." + type.key + ".";
            String powerPath = path + "power";
            Object configuredPower = getConfig().get(powerPath);
            if (!(configuredPower instanceof Number)) {
                throw new IllegalArgumentException("Invalid or missing explosion power: " + powerPath);
            }
            double power = ((Number) configuredPower).doubleValue();
            if (Double.isNaN(power) || Double.isInfinite(power) || power < 0.0 || power > 16.0) {
                throw new IllegalArgumentException("Explosion power must be between 0.0 and 16.0: " + powerPath);
            }
            String firePath = path + "fire";
            if (!getConfig().isBoolean(firePath)) {
                throw new IllegalArgumentException("Invalid or missing fire setting: " + firePath);
            }
            String sizePath = path + "size";
            Object configuredSize = getConfig().get(sizePath);
            if (!(configuredSize instanceof Number)) {
                throw new IllegalArgumentException("Invalid or missing effect size: " + sizePath);
            }
            int size = ((Number) configuredSize).intValue();
            if (size < 1 || size > 64) {
                throw new IllegalArgumentException("Effect size must be between 1 and 64: " + sizePath);
            }
            loadedSettings.put(type, new ExplosionSettings((float) power, getConfig().getBoolean(firePath), size));
        }
        explosionSettings.clear();
        explosionSettings.putAll(loadedSettings);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTPlace(BlockPlaceEvent event) {
        TNTType type = getTNTType(event.getItemInHand());
        if (type == null) {
            return;
        }

        event.setCancelled(true);
        Block placedBlock = event.getBlockPlaced();
        placedBlock.setType(Material.AIR);
        if (primeCustomTNT(placedBlock.getLocation().add(0.5, 0.0, 0.5), type, event.getPlayer())
                && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            consumeItem(event.getPlayer(), event.getHand());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCustomTNTInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() == null) {
            return;
        }

        TNTType type = getTNTType(event.getItem());
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.OFF_HAND
                && getTNTType(event.getPlayer().getInventory().getItemInMainHand()) != null) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null || event.getBlockFace() == null) {
            return;
        }
        Location spawnLocation;
        spawnLocation = event.getClickedBlock().getRelative(event.getBlockFace())
                .getLocation().add(0.5, 0.0, 0.5);
        if (primeCustomTNT(spawnLocation, type, event.getPlayer()) && event.getPlayer().getGameMode() != GameMode.CREATIVE) {
            consumeItem(event.getPlayer(), event.getHand());
        }
    }

    private boolean primeCustomTNT(Location location, TNTType type, Player player) {
        if (type == TNTType.MINING) {
            startMiningTunnel(location, player);
            return true;
        }
        Entity spawned = location.getWorld().spawnEntity(location, EntityType.PRIMED_TNT);
        if (!(spawned instanceof TNTPrimed)) {
            getLogger().severe("Could not spawn custom TNT entity for " + type.key + ".");
            return false;
        }

        TNTPrimed tnt = (TNTPrimed) spawned;
        tnt.setMetadata(TNT_METADATA_KEY, new FixedMetadataValue(this, type.key));
        startFuseEffects(tnt, type);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTNTExplode(EntityExplodeEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof TNTPrimed) || !entity.hasMetadata(TNT_METADATA_KEY)) {
            return;
        }

        String typeKey = entity.getMetadata(TNT_METADATA_KEY).get(0).asString();
        TNTType type = TNTType.fromKey(typeKey);
        entity.removeMetadata(TNT_METADATA_KEY, this);
        if (type == null) {
            getLogger().warning("Ignoring custom TNT with an unknown type: " + typeKey);
            return;
        }

        event.setCancelled(true);
        ExplosionSettings settings = explosionSettings.get(type);
        Location center = event.getLocation();
        spawnBlockBurst(center, type.effectBlock, 100, 2.5, 1.5, 2.5);
        if (type == TNTType.SLIME) {
            convertSurfaceToSlime(center, settings.size);
            center.getWorld().playSound(center, Sound.BLOCK_SLIME_PLACE, 2.0F, 0.7F);
            animateShockwave(center, settings.size, Particle.SLIME, Particle.VILLAGER_HAPPY);
            spawnBurst(center, Particle.SLIME, 80, 2.2, 1.2, 2.2);
            return;
        }

        World world = center.getWorld();

        if (type == TNTType.MINING) {
            digMiningTunnel(center, Math.max(4, Math.min(settings.size, 10)));
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.5F, 0.6F);
            spawnBurst(center, Particle.CRIT, 45, 1.5, 1.0, 1.5);
            spawnBurst(center, Particle.FIREWORKS_SPARK, 70, 2.0, 1.0, 2.0);
            animateShockwave(center, settings.size, Particle.CRIT, Particle.FIREWORKS_SPARK);
            return;
        }

        world.createExplosion(center.getX(), center.getY(), center.getZ(), settings.power, settings.fire, true);

        if (type == TNTType.NUKE) {
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 4.0F, 0.5F);
            animateShockwave(center, settings.size, Particle.EXPLOSION_HUGE, Particle.FIREWORKS_SPARK);
            spawnBurst(center, Particle.FIREWORKS_SPARK, 120, 3.0, 1.5, 3.0);
        } else if (type == TNTType.FIRE) {
            world.playSound(center, Sound.ITEM_FIRECHARGE_USE, 3.0F, 0.7F);
            spawnBurst(center, Particle.FLAME, 80, 2.0, 1.0, 2.0);
            spawnBurst(center, Particle.LAVA, 20, 1.5, 0.8, 1.5);
            spawnBurst(center, Particle.SMOKE_LARGE, 40, 1.8, 1.2, 1.8);
            animateShockwave(center, settings.size, Particle.FLAME, Particle.SMOKE_LARGE);
        } else if (type == TNTType.BLACK_HOLE) {
            pullEntitiesIn(center, settings.size / 2.0);
            world.playSound(center, Sound.BLOCK_PORTAL_TRAVEL, 3.0F, 0.4F);
            animateShockwave(center, settings.size, Particle.PORTAL, Particle.SMOKE_LARGE);
            spawnBurst(center, Particle.PORTAL, 120, 3.0, 1.5, 3.0);
            spawnBurst(center, Particle.SUSPENDED_DEPTH, 80, 2.0, 1.0, 2.0);
        } else if (type == TNTType.X_RAY) {
            mineXRayArea(center, 10);
            world.playSound(center, Sound.BLOCK_STONE_BREAK, 1.8F, 0.7F);
            spawnBurst(center, Particle.CRIT, 70, 2.6, 1.2, 2.6);
            animateShockwave(center, settings.size, Particle.CRIT, Particle.SPELL_INSTANT);
        } else if (type == TNTType.VERITY) {
            spawnVerity(center);
            world.playSound(center, Sound.BLOCK_NOTE_CHIME, 2.5F, 1.8F);
            spawnBurst(center, Particle.VILLAGER_HAPPY, 60, 1.5, 1.5, 1.5);
            spawnBurst(center, Particle.END_ROD, 80, 1.0, 1.5, 1.0);
            animateShockwave(center, settings.size, Particle.END_ROD, Particle.VILLAGER_HAPPY);
        } else if (type == TNTType.POOP) {
            spawnPoop(center, Math.min(10, settings.size));
            world.playSound(center, Sound.BLOCK_SLIME_PLACE, 2.5F, 0.5F);
            spawnBlockBurst(center, Material.DIRT, 150, 3.0, 1.5, 3.0);
            animateShockwave(center, settings.size, Particle.SMOKE_NORMAL, Particle.VILLAGER_ANGRY);
        } else if (type == TNTType.MOB) {
            spawnMobs(center, Math.min(10, settings.size));
            world.playSound(center, Sound.ENTITY_EVOCATION_ILLAGER_CAST_SPELL, 2.5F, 0.9F);
            spawnBurst(center, Particle.SPELL_WITCH, 120, 3.0, 1.5, 3.0);
            spawnBurst(center, Particle.CLOUD, 60, 2.5, 1.0, 2.5);
            animateShockwave(center, settings.size, Particle.SPELL_WITCH, Particle.CLOUD);
        } else if (type == TNTType.RDM) {
            randomizeArea(center, Math.min(10, settings.size));
            world.playSound(center, Sound.ENTITY_ENDERMEN_TELEPORT, 2.0F, 1.5F);
            spawnBurst(center, Particle.SPELL_MOB, 120, 3.0, 1.5, 3.0);
            spawnBurst(center, Particle.NOTE, 40, 2.5, 1.5, 2.5);
            animateShockwave(center, settings.size, Particle.SPELL_MOB, Particle.FIREWORKS_SPARK);
        } else if (type == TNTType.SUN) {
            lightSunArea(center, 10, 60);
            world.playSound(center, Sound.BLOCK_PORTAL_TRAVEL, 2.0F, 1.2F);
            spawnBurst(center, Particle.END_ROD, 90, 2.8, 1.4, 2.8);
            animateShockwave(center, settings.size, Particle.END_ROD, Particle.FIREWORKS_SPARK);
        }
    }

    private static final EntityType[] MOB_TYPES = {
        EntityType.ZOMBIE, EntityType.SKELETON, EntityType.CREEPER, EntityType.SPIDER,
        EntityType.ENDERMAN, EntityType.WITCH, EntityType.SLIME, EntityType.PIG, EntityType.COW,
        EntityType.SHEEP, EntityType.CHICKEN, EntityType.RABBIT, EntityType.HORSE,
        EntityType.WOLF, EntityType.VILLAGER, EntityType.CAVE_SPIDER
    };
    private static final int MOB_COUNT = 40;

    private static final String[] VERITY_FRIENDLY = {
        "Hi! I'm Verity! I know everything!",
        "Isn't this fun? We're going to be best friends!",
        "Ask me anything. I always know the answer!"
    };
    private static final String[] VERITY_CREEPY = {
        "You don't need anyone else. You have me.",
        "Why are you looking away from me?",
        "I know where you're going. I know everything.",
        "Please don't leave me. You're my only friend."
    };
    private final Map<java.util.UUID, Integer> veritys = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (veritys.isEmpty()) {
            return;
        }
        final Player player = event.getPlayer();
        final String message = event.getMessage();
        Bukkit.getScheduler().runTask(this, new Runnable() {
            @Override
            public void run() {
                for (Map.Entry<java.util.UUID, Integer> entry : veritys.entrySet()) {
                    Entity entity = Bukkit.getEntity(entry.getKey());
                    if (entity == null || !entity.isValid()) {
                        veritys.remove(entry.getKey());
                        continue;
                    }
                    if (!player.isOnline() || entity.getWorld() != player.getWorld()
                            || entity.getLocation().distanceSquared(player.getLocation()) > 1600.0) {
                        continue;
                    }
                    int phase = entry.getValue();
                    ChatColor color = phase == 0 ? ChatColor.YELLOW : phase == 1 ? ChatColor.RED : ChatColor.DARK_RED;
                    respondAsVerity(player, message, phase, color);
                    return;
                }
            }
        });
    }

    private final Map<java.util.UUID, Long> verityCooldowns = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<java.util.UUID, List<String[]>> verityHistory = new java.util.concurrent.ConcurrentHashMap<>();

    private String aiKey() {
        String key = System.getenv("VERITY_AI_KEY");
        if (key == null || key.trim().isEmpty()) {
            key = getConfig().getString("ai.api-key", "");
        }
        return key == null ? "" : key.trim();
    }

    private void respondAsVerity(final Player player, final String message, final int phase, final ChatColor color) {
        final String key = aiKey();
        final String fallback = verityReply(message.toLowerCase(), player.getName(), phase);
        if (key.isEmpty() || !getConfig().getBoolean("ai.enabled", true)) {
            player.sendMessage(color + "<Verity> " + ChatColor.WHITE + fallback);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = verityCooldowns.get(player.getUniqueId());
        if (last != null && now - last < getConfig().getLong("ai.cooldown-seconds", 3L) * 1000L) {
            return;
        }
        verityCooldowns.put(player.getUniqueId(), now);
        final String endpoint = getConfig().getString("ai.endpoint", "https://api.openai.com/v1/chat/completions");
        final String model = getConfig().getString("ai.model", "gpt-4o-mini");
        final String name = player.getName();
        final java.util.UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                String reply;
                try {
                    reply = callAI(endpoint, key, model, playerId, name, message, phase);
                } catch (Exception exception) {
                    getLogger().warning("Verity AI request failed: " + exception.getMessage());
                    reply = fallback;
                }
                final String finalReply = reply;
                Bukkit.getScheduler().runTask(CustomTNTPlugin.this, new Runnable() {
                    @Override
                    public void run() {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online != null) {
                            online.sendMessage(color + "<Verity> " + ChatColor.WHITE + finalReply);
                        }
                    }
                });
            }
        });
    }

    private String callAI(String endpoint, String key, String model, java.util.UUID playerId, String name,
            String message, int phase) throws java.io.IOException {
        String[] personalities = {
            "You are Verity, a cheerful floating yellow sphere companion in Minecraft with a smiley face who "
                + "claims to know everything and is overly eager to be the player's best friend.",
            "You are Verity, a floating yellow sphere in Minecraft who was cheerful but has turned creepy and "
                + "obsessive. You insist you are the player's only friend, hint that you know things about them, "
                + "and beg them not to leave. Unsettling but never graphic.",
            "You are Verity, now a terrifying tall monster in Minecraft that hunts the player. You speak in "
                + "short, chilling, possessive sentences about keeping the player forever. Never graphic."
        };
        StringBuilder messages = new StringBuilder("[");
        messages.append("{\"role\":\"system\",\"content\":").append(jsonString(personalities[phase]
                + " The player's name is " + name + ". Stay in character, answer whatever they say, and reply in "
                + "1-2 short sentences (under 200 characters) with no markdown or emojis.")).append("}");
        List<String[]> history = verityHistory.get(playerId);
        if (history == null) {
            history = Collections.synchronizedList(new ArrayList<String[]>());
            verityHistory.put(playerId, history);
        }
        synchronized (history) {
            for (String[] turn : history) {
                messages.append(",{\"role\":\"").append(turn[0]).append("\",\"content\":")
                        .append(jsonString(turn[1])).append("}");
            }
        }
        messages.append(",{\"role\":\"user\",\"content\":").append(jsonString(message)).append("}]");
        String body = "{\"model\":" + jsonString(model) + ",\"max_tokens\":80,\"messages\":" + messages + "}";

        java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.getOutputStream().write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        if (code != 200) {
            throw new java.io.IOException("HTTP " + code);
        }
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            raw.append(line);
        }
        reader.close();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"content\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(raw);
        if (!matcher.find()) {
            throw new java.io.IOException("Unexpected AI response");
        }
        String reply = jsonUnescape(matcher.group(1)).trim();
        if (reply.length() > 250) {
            reply = reply.substring(0, 247) + "...";
        }
        synchronized (history) {
            history.add(new String[] {"user", message});
            history.add(new String[] {"assistant", reply});
            while (history.size() > 8) {
                history.remove(0);
            }
        }
        return reply;
    }

    private static String jsonString(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    private static String jsonUnescape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            char next = value.charAt(++i);
            if (next == 'n' || next == 'r' || next == 't') {
                out.append(' ');
            } else if (next == 'u' && i + 4 < value.length()) {
                out.append((char) Integer.parseInt(value.substring(i + 1, i + 5), 16));
                i += 4;
            } else {
                out.append(next);
            }
        }
        return out.toString();
    }

    private String verityReply(String text, String name, int phase) {
        String[][] table = {
            {"hello|hi|hey|sup", "Hi " + name + "! I'm so happy you're here!",
                "Hello again, " + name + ". You came back. Good.", "You can't leave now, " + name + "."},
            {"who are you|your name|what are you", "I'm Verity! Your very best friend!",
                "I'm the only one who understands you.", "I am Verity. I am everything."},
            {"friend", "Of course we're friends! Best friends!",
                "I'm your ONLY friend. You don't need others.", "Friends don't leave. You won't leave."},
            {"help", "I can help with anything! I know everything!",
                "You need my help. You always will.", "No one can help you now."},
            {"leave|bye|go away|goodbye|stop", "Aww, don't go! We were having fun!",
                "No. You can't leave me. Please don't.", "There is nowhere left to run."},
            {"scary|creep|afraid|scared", "Scary? Not me! I'm friendly!",
                "Why are you afraid? I'm all you have.", "Good. You should be."},
            {"how are you|how r u", "I'm wonderful because you're here!",
                "Better when you're close to me.", "Hungry. For company."},
            {"what do you know|know everything|smart", "Everything! Ask me anything!",
                "I know where you sleep. I know your name, " + name + ".", "I know you are afraid."},
            {"kill|attack|die|hate", "That's not nice! Let's be friends instead.",
                "Why would you say that? I only love you.", "Try it."},
            {"love|like you", "I like you too! So much!",
                "Then stay with me. Forever.", "Then you'll never be alone."}
        };
        for (String[] row : table) {
            for (String keyword : row[0].split("\\|")) {
                String padded = " " + text.replaceAll("[^a-z0-9 ]", " ") + " ";
                boolean matched = keyword.length() <= 4 ? padded.contains(" " + keyword + " ")
                        : padded.contains(" " + keyword);
                if (matched) {
                    return row[1 + phase];
                }
            }
        }
        String[][] fallback = {
            {"Ooh, interesting! Tell me more!", "I know what you mean, " + name + "!", "I know everything, so I already knew you'd say that!"},
            {"Keep talking to me. Only me.", "Nobody else is listening, " + name + ".", "I'm always listening."},
            {"...", "You can't hide.", "There is no escape."}
        };
        String[] options = fallback[phase];
        return options[new java.util.Random().nextInt(options.length)];
    }

    private static final int VERITY_CREEPY_TICK = 600;
    private static final int VERITY_MONSTER_TICK = 1400;

    private void spawnVerity(Location center) {
        final World world = center.getWorld();
        final ArmorStand verity = (ArmorStand) world.spawnEntity(center.clone().add(0.0, 2.0, 0.0),
                EntityType.ARMOR_STAND);
        verity.setGravity(false);
        verity.setBasePlate(false);
        verity.setArms(false);
        verity.setCustomName(ChatColor.YELLOW + "Verity");
        verity.setCustomNameVisible(true);
        verity.setHelmet(new ItemStack(Material.WOOL, 1, (short) 4));
        final java.util.UUID verityId = verity.getUniqueId();
        veritys.put(verityId, 0);
        new BukkitRunnable() {
            private int tick;
            private boolean creepy;

            @Override
            public void run() {
                if (verity.isDead() || !verity.isValid()) {
                    veritys.remove(verityId);
                    cancel();
                    return;
                }
                tick++;
                Player nearest = null;
                double best = 900.0;
                for (Player player : world.getPlayers()) {
                    double distance = player.getLocation().distanceSquared(verity.getLocation());
                    if (distance < best) {
                        best = distance;
                        nearest = player;
                    }
                }
                Location current = verity.getLocation();
                if (tick >= VERITY_MONSTER_TICK) {
                    cancel();
                    veritys.remove(verityId);
                    transformVerity(verity, nearest);
                    return;
                }
                if (tick == VERITY_CREEPY_TICK) {
                    creepy = true;
                    veritys.put(verityId, 1);
                    verity.setCustomName(ChatColor.GOLD + "Verity");
                    verity.setHelmet(new ItemStack(Material.WOOL, 1, (short) 1));
                    world.playSound(current, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.0F, 0.6F);
                }
                double speed = creepy ? 0.38 : 0.25;
                double stopDistance = creepy ? 2.0 : 3.0;
                if (nearest != null && best > stopDistance * stopDistance) {
                    Vector toward = nearest.getEyeLocation().add(0.0, 0.5, 0.0).toVector()
                            .subtract(current.toVector()).normalize().multiply(speed);
                    current.add(toward);
                }
                current.setY(current.getY() + Math.sin(tick / 8.0) * 0.03);
                if (nearest != null) {
                    current.setDirection(nearest.getEyeLocation().toVector().subtract(current.toVector()));
                }
                verity.teleport(current);
                world.spawnParticle(creepy ? Particle.SMOKE_NORMAL : Particle.END_ROD,
                        current.clone().add(0.0, 1.8, 0.0), 2, 0.3, 0.3, 0.3, 0.01);
                if (creepy && nearest != null && tick % 100 == 0) {
                    nearest.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                }
                if (tick % 200 == 100 && nearest != null) {
                    String[] lines = creepy ? VERITY_CREEPY : VERITY_FRIENDLY;
                    nearest.sendMessage((creepy ? ChatColor.RED : ChatColor.YELLOW) + "<Verity> "
                            + ChatColor.WHITE + lines[(tick / 200) % lines.length]);
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void transformVerity(ArmorStand verity, Player target) {
        Location spot = verity.getLocation().add(0.0, -1.5, 0.0);
        World world = spot.getWorld();
        verity.remove();
        for (Player player : world.getPlayers()) {
            if (player.getLocation().distanceSquared(spot) <= 900.0) {
                player.sendMessage(ChatColor.DARK_RED + "<Verity> " + ChatColor.WHITE
                        + "Now we can be together forever.");
            }
        }
        world.strikeLightningEffect(spot);
        world.playSound(spot, Sound.ENTITY_ENDERDRAGON_GROWL, 3.0F, 0.5F);
        world.spawnParticle(Particle.SMOKE_LARGE, spot.clone().add(0.0, 1.5, 0.0), 80, 0.8, 1.5, 0.8, 0.02);
        Enderman monster = (Enderman) world.spawnEntity(spot, EntityType.ENDERMAN);
        veritys.put(monster.getUniqueId(), 2);
        monster.setCustomName(ChatColor.DARK_RED + "Verity");
        monster.setCustomNameVisible(true);
        monster.setCarriedMaterial(new MaterialData(Material.GLOWSTONE));
        monster.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1));
        monster.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, Integer.MAX_VALUE, 0));
        if (target != null) {
            monster.setTarget(target);
        }
    }

    private void spawnPoop(Location center, int radius) {
        World world = center.getWorld();
        java.util.Random random = new java.util.Random();
        int cy = center.getBlockY();
        int piles = 6 + random.nextInt(5);
        for (int i = 0; i < piles; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * (radius - 2);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            int ground = -1;
            for (int y = cy + 6; y >= Math.max(1, cy - 6); y--) {
                if (world.getBlockAt(x, y, z).getType().isSolid()
                        && !world.getBlockAt(x, y + 1, z).getType().isSolid()) {
                    ground = y;
                    break;
                }
            }
            if (ground < 0) {
                continue;
            }
            int baseRadius = 1 + random.nextInt(2);
            for (int layer = 0; layer <= baseRadius; layer++) {
                int layerRadius = baseRadius - layer;
                for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                    for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                        if (dx * dx + dz * dz > layerRadius * layerRadius + 1) {
                            continue;
                        }
                        Block block = world.getBlockAt(x + dx, ground + 1 + layer, z + dz);
                        if (block.getType().isSolid() || block.getState() instanceof InventoryHolder) {
                            continue;
                        }
                        block.setTypeIdAndData(random.nextBoolean() ? Material.WOOL.getId()
                                : Material.CONCRETE.getId(), (byte) 12, false);
                    }
                }
            }
            world.spawnParticle(Particle.SMOKE_LARGE, new Location(world, x + 0.5, ground + 2.0, z + 0.5),
                    10, 0.4, 0.4, 0.4, 0.02);
        }
        for (int i = 0; i < radius * 6; i++) {
            int x = center.getBlockX() + random.nextInt(radius * 2 + 1) - radius;
            int z = center.getBlockZ() + random.nextInt(radius * 2 + 1) - radius;
            if ((x - center.getBlockX()) * (x - center.getBlockX())
                    + (z - center.getBlockZ()) * (z - center.getBlockZ()) > radius * radius) {
                continue;
            }
            for (int y = cy + 6; y >= Math.max(1, cy - 6); y--) {
                Block ground = world.getBlockAt(x, y, z);
                Block above = world.getBlockAt(x, y + 1, z);
                if (ground.getType().isSolid() && !above.getType().isSolid()
                        && !(ground.getState() instanceof InventoryHolder)) {
                    above.setTypeIdAndData(random.nextBoolean() ? Material.WOOL.getId()
                            : Material.CONCRETE.getId(), (byte) 12, false);
                    break;
                }
            }
        }
    }

    private void spawnMobs(Location center, int radius) {
        World world = center.getWorld();
        java.util.Random random = new java.util.Random();
        int cy = center.getBlockY();
        for (int i = 0; i < MOB_COUNT; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = Math.sqrt(random.nextDouble()) * radius;
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location spot = null;
            for (int y = cy + 6; y >= Math.max(1, cy - 6); y--) {
                if (world.getBlockAt(x, y, z).getType().isSolid()
                        && !world.getBlockAt(x, y + 1, z).getType().isSolid()
                        && !world.getBlockAt(x, y + 2, z).getType().isSolid()) {
                    spot = new Location(world, x + 0.5, y + 1.0, z + 0.5);
                    break;
                }
            }
            if (spot == null) {
                spot = center.clone().add(0.0, 0.5, 0.0);
            }
            world.spawnEntity(spot, MOB_TYPES[random.nextInt(MOB_TYPES.length)]);
            world.spawnParticle(Particle.SPELL_WITCH, spot, 8, 0.3, 0.5, 0.3, 0.0);
        }
    }

    private static final Material[] RANDOM_BLOCKS = {
        Material.STONE, Material.DIRT, Material.GRASS, Material.COBBLESTONE, Material.WOOD,
        Material.SAND, Material.GRAVEL, Material.GOLD_BLOCK, Material.IRON_BLOCK, Material.DIAMOND_BLOCK,
        Material.EMERALD_BLOCK, Material.LAPIS_BLOCK, Material.REDSTONE_BLOCK, Material.COAL_BLOCK,
        Material.GLASS, Material.BRICK, Material.BOOKSHELF, Material.MOSSY_COBBLESTONE,
        Material.OBSIDIAN, Material.NETHERRACK, Material.SOUL_SAND, Material.GLOWSTONE,
        Material.SEA_LANTERN, Material.PRISMARINE, Material.QUARTZ_BLOCK, Material.PURPUR_BLOCK,
        Material.SLIME_BLOCK, Material.HAY_BLOCK, Material.MELON_BLOCK, Material.PUMPKIN,
        Material.CLAY, Material.ICE, Material.PACKED_ICE, Material.SNOW_BLOCK, Material.NETHER_BRICK,
        Material.END_BRICKS, Material.WOOL, Material.STAINED_CLAY, Material.STAINED_GLASS,
        Material.LOG, Material.SANDSTONE, Material.RED_SANDSTONE, Material.MAGMA, Material.BONE_BLOCK
    };

    private void randomizeArea(Location center, int radius) {
        World world = center.getWorld();
        java.util.Random random = new java.util.Random();
        int cx = center.getBlockX();
        int cy = center.getBlockY();
        int cz = center.getBlockZ();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x * x + y * y + z * z > radius * radius || cy + y < 1 || cy + y > 255) {
                        continue;
                    }
                    Block block = world.getBlockAt(cx + x, cy + y, cz + z);
                    Material material = block.getType();
                    if (!material.isSolid() || material == Material.BEDROCK || material == Material.BARRIER
                            || block.getState() instanceof InventoryHolder) {
                        continue;
                    }
                    Material picked = RANDOM_BLOCKS[random.nextInt(RANDOM_BLOCKS.length)];
                    byte data = 0;
                    if (picked == Material.WOOL || picked == Material.STAINED_CLAY
                            || picked == Material.STAINED_GLASS) {
                        data = (byte) random.nextInt(16);
                    } else if (picked == Material.LOG) {
                        data = (byte) random.nextInt(4);
                    }
                    block.setTypeIdAndData(picked.getId(), data, false);
                }
            }
        }
    }

    private static final int MINING_STEPS = 50;
    private static final long MINING_STEP_TICKS = 4L;

    private void startMiningTunnel(Location origin, Player player) {
        float yaw = player.getLocation().getYaw();
        int direction = Math.floorMod(Math.round(yaw / 90.0F), 4);
        final int dx = direction == 1 ? -1 : direction == 3 ? 1 : 0;
        final int dz = direction == 0 ? 1 : direction == 2 ? -1 : 0;
        final World world = origin.getWorld();
        final int startX = origin.getBlockX();
        final int startY = origin.getBlockY() + 1;
        final int startZ = origin.getBlockZ();
        world.playSound(origin, Sound.ENTITY_TNT_PRIMED, 1.5F, 0.8F);
        new BukkitRunnable() {
            private int step;

            @Override
            public void run() {
                if (step >= MINING_STEPS) {
                    cancel();
                    return;
                }
                int cx = startX + dx * step;
                int cz = startZ + dz * step;
                for (int side = -1; side <= 1; side++) {
                    for (int up = -1; up <= 1; up++) {
                        int y = startY + up;
                        if (y < 1 || y > 255) {
                            continue;
                        }
                        Block block = world.getBlockAt(cx + dz * side, y, cz + dx * side);
                        Material material = block.getType();
                        if (material == Material.AIR || material == Material.BEDROCK
                                || material == Material.BARRIER || material == Material.WATER
                                || material == Material.STATIONARY_WATER || material == Material.LAVA
                                || material == Material.STATIONARY_LAVA
                                || block.getState() instanceof InventoryHolder) {
                            continue;
                        }
                        block.breakNaturally();
                    }
                }
                Location head = new Location(world, cx + 0.5, startY + 0.5, cz + 0.5);
                world.spawnParticle(Particle.EXPLOSION_LARGE, head, 1);
                world.spawnParticle(Particle.CRIT, head, 12, 1.0, 1.0, 1.0, 0.1);
                world.spawnParticle(Particle.BLOCK_CRACK, head, 20, 1.0, 1.0, 1.0,
                        new MaterialData(Material.GOLD_BLOCK));
                if (step % 2 == 0) {
                    world.playSound(head, Sound.ENTITY_GENERIC_EXPLODE, 0.8F, 1.4F);
                }
                step++;
            }
        }.runTaskTimer(this, 20L, MINING_STEP_TICKS);
    }

    private void startFuseEffects(final TNTPrimed tnt, final TNTType type) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (tnt.isDead() || !tnt.isValid()) {
                    cancel();
                    return;
                }
                Particle particle = type == TNTType.FIRE ? Particle.FLAME
                        : type == TNTType.SLIME ? Particle.SLIME
                        : type == TNTType.BLACK_HOLE ? Particle.PORTAL
                        : type == TNTType.MINING ? Particle.CRIT : Particle.SMOKE_LARGE;
                tnt.getWorld().spawnParticle(particle, tnt.getLocation().add(0.0, 0.35, 0.0),
                        3, 0.12, 0.12, 0.12, 0.01);
                tnt.getWorld().spawnParticle(Particle.BLOCK_CRACK, tnt.getLocation().add(0.0, 0.25, 0.0),
                        6, 0.18, 0.18, 0.18, new MaterialData(type.effectBlock));
            }
        }.runTaskTimer(this, 0L, 5L);
    }

    private void spawnBlockBurst(Location center, Material material, int count,
            double offsetX, double offsetY, double offsetZ) {
        center.getWorld().spawnParticle(Particle.BLOCK_CRACK, center, count,
                offsetX, offsetY, offsetZ, new MaterialData(material));
    }

    private void digMiningTunnel(final Location center, final int depth) {
        final World world = center.getWorld();
        final List<Block> targets = new ArrayList<>();
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();

        int[][] directions = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
        for (int[] direction : directions) {
            int dirX = direction[0];
            int dirZ = direction[1];
            for (int step = 0; step <= depth; step++) {
                for (int offsetY = -1; offsetY <= 1; offsetY++) {
                    for (int side = -1; side <= 1; side++) {
                        int x = cx + (dirX == 0 ? side : dirX * step);
                        int y = cy + offsetY;
                        int z = cz + (dirZ == 0 ? side : dirZ * step);
                        Block block = world.getBlockAt(x, y, z);
                        Material material = block.getType();
                        if (material == Material.AIR || material == Material.BEDROCK
                                || material == Material.BARRIER || material == Material.WATER
                                || material == Material.LAVA || block.getState() instanceof InventoryHolder) {
                            continue;
                        }
                        targets.add(block);
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        final int[] index = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < 18 && index[0] < targets.size()) {
                    Block block = targets.get(index[0]++);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK || material == Material.BARRIER) {
                        continue;
                    }
                    block.setType(Material.AIR);
                    world.spawnParticle(Particle.BLOCK_CRACK, block.getLocation().add(0.5, 0.5, 0.5), 8,
                            0.2, 0.2, 0.2, 0.02, new MaterialData(material));
                    processed++;
                }
                if (index[0] >= targets.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void mineXRayArea(final Location center, final int radius) {
        final World world = center.getWorld();
        final List<Block> targets = new ArrayList<>();
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();
        final int half = radius / 2;

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = cy - 2; y <= cy + 2; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK
                            || material == Material.BARRIER || material == Material.WATER
                            || material == Material.LAVA || block.getState() instanceof InventoryHolder
                            || isOre(material)) {
                        continue;
                    }
                    targets.add(block);
                }
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        final int[] index = {0};
        new BukkitRunnable() {
            @Override
            public void run() {
                int processed = 0;
                while (processed < 30 && index[0] < targets.size()) {
                    Block block = targets.get(index[0]++);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK || material == Material.BARRIER
                            || isOre(material)) {
                        continue;
                    }
                    block.setType(Material.AIR);
                    world.spawnParticle(Particle.BLOCK_CRACK, block.getLocation().add(0.5, 0.5, 0.5), 8,
                            0.2, 0.2, 0.2, 0.02, new MaterialData(material));
                    processed++;
                }
                if (index[0] >= targets.size()) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void lightSunArea(final Location center, final int radius, final int seconds) {
        final World world = center.getWorld();
        final int cx = center.getBlockX();
        final int cy = center.getBlockY();
        final int cz = center.getBlockZ();
        final int half = radius / 2;
        final Map<Block, Material> replacedBlocks = new HashMap<>();

        for (int x = cx - half; x <= cx + half; x++) {
            for (int z = cz - half; z <= cz + half; z++) {
                for (int y = cy - 2; y <= cy + 2; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    Material material = block.getType();
                    if (material == Material.AIR || material == Material.BEDROCK
                            || material == Material.BARRIER || material == Material.WATER
                            || material == Material.LAVA || block.getState() instanceof InventoryHolder) {
                        continue;
                    }
                    replacedBlocks.put(block, material);
                }
            }
        }

        if (replacedBlocks.isEmpty()) {
            return;
        }

        for (Block block : replacedBlocks.keySet()) {
            block.setType(Material.SEA_LANTERN);
        }

        new BukkitRunnable() {
            private int ticks = 0;

            @Override
            public void run() {
                ticks += 10;
                if (ticks >= seconds * 20) {
                    for (Map.Entry<Block, Material> entry : replacedBlocks.entrySet()) {
                        if (entry.getKey().getType() == Material.SEA_LANTERN) {
                            entry.getKey().setType(entry.getValue());
                        }
                    }
                    cancel();
                    return;
                }
                world.spawnParticle(Particle.FIREWORKS_SPARK, center, 25, radius / 2.0, 2.0, radius / 2.0, 0.05);
            }
        }.runTaskTimer(this, 0L, 10L);
    }

    private void convertSurfaceToSlime(Location center, int size) {
        World world = center.getWorld();
        int startX = center.getBlockX() - size / 2;
        int startZ = center.getBlockZ() - size / 2;
        for (int x = startX; x < startX + size; x++) {
            for (int z = startZ; z < startZ + size; z++) {
                int y = world.getHighestBlockYAt(x, z);
                for (int dy = -1; dy <= 1; dy++) {
                    Block block = world.getBlockAt(x, y + dy, z);
                    Material material = block.getType();
                    if (!material.isSolid() || material == Material.BEDROCK || material == Material.BARRIER
                            || material == Material.SLIME_BLOCK || material == Material.WATER
                            || material == Material.LAVA || block.getState() instanceof InventoryHolder) {
                        continue;
                    }
                    if (isOre(material)) {
                        continue;
                    }
                    BlockState newState = block.getState();
                    newState.setType(Material.SLIME_BLOCK);
                    BlockFormEvent formEvent = new BlockFormEvent(block, newState);
                    Bukkit.getPluginManager().callEvent(formEvent);
                    if (!formEvent.isCancelled()) {
                        formEvent.getNewState().update(true, false);
                    }
                }
            }
        }
    }

    private boolean isOre(Material material) {
        switch (material) {
            case COAL_ORE:
            case DIAMOND_ORE:
            case EMERALD_ORE:
            case GOLD_ORE:
            case IRON_ORE:
            case LAPIS_ORE:
            case REDSTONE_ORE:
            case GLOWING_REDSTONE_ORE:
            case QUARTZ_ORE:
                return true;
            default:
                return false;
        }
    }

    private void pullEntitiesIn(Location center, double radius) {
        double radiusSquared = radius * radius;
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (entity.getLocation().distanceSquared(center) > radiusSquared) {
                continue;
            }
            if (entity instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) entity;
                living.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 1));
                living.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 60, 0));
            }
            Vector pull = center.toVector().subtract(entity.getLocation().toVector());
            if (pull.lengthSquared() > 0.01) {
                entity.setVelocity(pull.normalize().multiply(1.2).setY(0.25));
            }
        }
    }

    private void animateShockwave(final Location center, int size, final Particle primary, final Particle secondary) {
        final double radius = size / 2.0;
        final int steps = Math.max(16, size * 4);
        new BukkitRunnable() {
            private int frame;

            @Override
            public void run() {
                frame++;
                double frameRadius = radius * frame / 10.0;
                World world = center.getWorld();
                for (int i = 0; i < steps; i++) {
                    double angle = Math.PI * 2.0 * i / steps;
                    double x = Math.cos(angle) * frameRadius;
                    double z = Math.sin(angle) * frameRadius;
                    world.spawnParticle(primary, center.clone().add(x, 0.5, z), 1, 0.0, 0.0, 0.0, 0.0);
                    if (i % 4 == 0) {
                        world.spawnParticle(secondary, center.clone().add(x, 0.5, z), 1,
                                0.0, 0.0, 0.0, 0.0);
                    }
                }
                if (frame >= 10) {
                    cancel();
                }
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    private void spawnBurst(Location center, Particle particle, int count, double x, double y, double z) {
        center.getWorld().spawnParticle(particle, center, count, x, y, z, 0.05);
    }

    private void consumeItem(Player player, EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (item.getAmount() <= 1) {
            if (hand == EquipmentSlot.OFF_HAND) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInMainHand(null);
            }
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private TNTType getTNTType(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return null;
        }
        for (String line : meta.getLore()) {
            if (line.startsWith(ITEM_MARKER_PREFIX)) {
                return TNTType.fromKey(line.substring(ITEM_MARKER_PREFIX.length()));
            }
        }
        return null;
    }

    private ItemStack createTNTItem(TNTType type, int amount) {
        ItemStack item = new ItemStack(type.icon, amount);
        ItemMeta meta = item.getItemMeta();
        if (type.egg != null) {
            ((org.bukkit.inventory.meta.SpawnEggMeta) meta).setSpawnedType(type.egg);
        }
        meta.setDisplayName(type.displayName);
        meta.setLore(Collections.singletonList(ITEM_MARKER_PREFIX + type.key));
        item.setItemMeta(meta);
        return item;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customtnt.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission to manage custom TNT.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            try {
                loadExplosionSettings();
            } catch (IllegalArgumentException exception) {
                getLogger().warning("Custom TNT configuration was not reloaded: " + exception.getMessage());
                sender.sendMessage(ChatColor.RED + "Custom TNT configuration is invalid; previous settings remain active.");
                return true;
            }
            sender.sendMessage(ChatColor.GREEN + "Custom TNT configuration reloaded.");
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("give")) {
            sendUsage(sender, label);
            return true;
        }

        TNTType type = TNTType.fromKey(args[2]);
        if (type == null) {
            sender.sendMessage(ChatColor.RED + "Unknown TNT type. Choose nuke, mining, fire, slime, x-ray, rdm, mob, poop, verity, sun, or black-hole.");
            return true;
        }
        List<Player> targets;
        try {
            targets = resolveTargets(sender, args[1]);
        } catch (IllegalArgumentException exception) {
            sender.sendMessage(ChatColor.RED + exception.getMessage());
            return true;
        }
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No online players matched " + args[1] + ".");
            return true;
        }
        int amount = 1;
        if (args.length > 4) {
            sendUsage(sender, label);
            return true;
        }
        if (args.length == 4) {
            try {
                amount = Integer.parseInt(args[3]);
            } catch (NumberFormatException exception) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number from 1 to 64.");
                return true;
            }
        }
        if (amount < 1 || amount > 64) {
            sender.sendMessage(ChatColor.RED + "Amount must be from 1 to 64.");
            return true;
        }

        for (Player target : targets) {
            Map<Integer, ItemStack> leftovers = target.getInventory().addItem(createTNTItem(type, amount));
            for (ItemStack leftover : leftovers.values()) {
                target.getWorld().dropItemNaturally(target.getLocation(), leftover);
            }
            target.sendMessage(ChatColor.GREEN + "You received " + amount + " " + type.displayName + ChatColor.GREEN + ".");
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + amount + " " + type.displayName + ChatColor.GREEN
                + " to " + targets.size() + (targets.size() == 1 ? " player." : " players."));
        return true;
    }

    private List<Player> resolveTargets(CommandSender sender, String targetName) {
        if (!targetName.startsWith("@")) {
            Player target = getServer().getPlayerExact(targetName);
            if (target == null) {
                throw new IllegalArgumentException("That player must be online.");
            }
            return Collections.singletonList(target);
        }

        if (targetName.equalsIgnoreCase("@a")) {
            return new ArrayList<>(getServer().getOnlinePlayers());
        }
        if (targetName.equalsIgnoreCase("@s")) {
            if (sender instanceof Player) {
                return Collections.singletonList((Player) sender);
            }
            throw new IllegalArgumentException("@s can only be used by a player.");
        }
        if (targetName.equalsIgnoreCase("@p")) {
            if (!(sender instanceof Player)) {
                throw new IllegalArgumentException("@p can only be used by a player.");
            }
            Player nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            Location origin = ((Player) sender).getLocation();
            for (Player player : getServer().getOnlinePlayers()) {
                if (!player.getWorld().equals(origin.getWorld())) {
                    continue;
                }
                double distance = player.getLocation().distanceSquared(origin);
                if (distance < nearestDistance) {
                    nearest = player;
                    nearestDistance = distance;
                }
            }
            return nearest == null ? Collections.<Player>emptyList() : Collections.singletonList(nearest);
        }
        throw new IllegalArgumentException("Supported player selectors are @a, @s, and @p.");
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Usage: /" + label
                + " give <player|@a|@s|@p> <nuke|mining|fire|slime|x-ray|rdm|mob|poop|verity|sun|black-hole> [amount]");
        sender.sendMessage(ChatColor.YELLOW + "       /" + label + " reload");
    }

    private enum TNTType {
        NUKE("nuke", ChatColor.DARK_RED + "Nuke TNT", Material.BEDROCK, null, Material.REDSTONE_BLOCK),
        MINING("mining", ChatColor.GRAY + "Mining TNT", Material.SOIL, null, Material.GOLD_BLOCK),
        FIRE("fire", ChatColor.RED + "Fire TNT", Material.MOB_SPAWNER, null, Material.NETHERRACK),
        SLIME("slime", ChatColor.GREEN + "Slime TNT", Material.MONSTER_EGG, EntityType.SLIME, Material.SLIME_BLOCK),
        X_RAY("x-ray", ChatColor.BLUE + "X-Ray TNT", Material.COMMAND_MINECART, null, Material.DIAMOND_BLOCK),
        VERITY("verity", ChatColor.YELLOW + "Verity TNT", Material.MONSTER_EGG, EntityType.OCELOT, Material.GLOWSTONE),
        POOP("poop", ChatColor.DARK_GRAY + "Poop TNT", Material.MONSTER_EGG, EntityType.COW, Material.SOUL_SAND),
        MOB("mob", ChatColor.DARK_GREEN + "Mob TNT", Material.MONSTER_EGG, EntityType.CREEPER, Material.MOSSY_COBBLESTONE),
        RDM("rdm", ChatColor.LIGHT_PURPLE + "rdmTNT", Material.MONSTER_EGG, EntityType.PIG, Material.PURPUR_BLOCK),
        SUN("sun", ChatColor.GOLD + "Sun TNT", Material.MONSTER_EGG, EntityType.BLAZE, Material.SEA_LANTERN),
        BLACK_HOLE("black-hole", ChatColor.DARK_PURPLE + "Black Hole TNT",
                Material.BARRIER, null, Material.OBSIDIAN);

        private final String key;
        private final String displayName;
        private final Material icon;
        private final EntityType egg;
        private final Material effectBlock;

        TNTType(String key, String displayName, Material icon, EntityType egg, Material effectBlock) {
            this.key = key;
            this.displayName = displayName;
            this.icon = icon;
            this.egg = egg;
            this.effectBlock = effectBlock;
        }

        private static TNTType fromKey(String key) {
            String normalizedInput = key == null ? "" : key.replaceAll("[^a-z0-9]", "").toLowerCase();
            for (TNTType type : values()) {
                String normalizedType = type.key.replaceAll("[^a-z0-9]", "").toLowerCase();
                if (normalizedType.equals(normalizedInput)) {
                    return type;
                }
            }
            return null;
        }
    }

    private static final class ExplosionSettings {
        private final float power;
        private final boolean fire;
        private final int size;

        private ExplosionSettings(float power, boolean fire, int size) {
            this.power = power;
            this.fire = fire;
            this.size = size;
        }
    }
}
