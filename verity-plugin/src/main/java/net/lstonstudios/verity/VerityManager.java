package net.lstonstudios.verity;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.WeatherType;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spider;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/** Owns every Verity: entities, phases, the Box, the Demon and all the horror. */
final class VerityManager {
    static final String TAG_VERITY = "verity";
    static final String TAG_BOX = "verity_box";
    static final String TAG_DEMON = "verity_demon";

    private static final String[] P3_LINES = {
            "Why did you open the box?", "You never listen.", "The lights are so bright. Let me fix that.",
            "Did you hear that? ...No? Good.", "Don't look behind you.", "He's getting closer, {name}.",
            "I can see everything you do.", "We were friends once. Weren't we?",
            "You could have left me in the box.", "It's quiet. Too quiet.",
            "I don't need you to ask anymore.", "He's been waiting for you."};
    private static final String[] P4_LINES = {
            "I SEE YOU, {NAME}.", "YOU CANNOT REMOVE ME.", "WHY DID YOU OPEN THE BOX?", "HE IS BEHIND YOU.",
            "THERE IS NO ESCAPE.", "{NAME}. {NAME}. {NAME}.", "THE LIGHTS ARE OFF NOW.",
            "I AM YOUR FRIEND.", "RUN.", "LOOK BEHIND YOU."};
    private static final String[] TIPS = {
            "Tip: sleeping in a bed skips the night and sets your spawn!",
            "Tip: always carry some food. I worry about you.",
            "Tip: torches stop monsters from spawning. Light up your base!",
            "Tip: ask me to 'find diamond' and I'll scan the area for you.",
            "Tip: say 'verity help' to see everything I can do!",
            "Tip: water buckets cancel fall damage. Careful out there!"};

    final VerityPlugin plugin;
    final Map<UUID, VerityState> states = new LinkedHashMap<>();
    final VerityBrain brain;
    private final Random rnd = new Random();
    private final Set<ArmorStand> boxes = new HashSet<>();
    private final List<PendingTorch> torches = new ArrayList<>();
    private long tick;

    private static final class PendingTorch {
        final BlockState state;
        final long restoreAt;

        PendingTorch(BlockState state, long restoreAt) {
            this.state = state;
            this.restoreAt = restoreAt;
        }
    }

    VerityManager(VerityPlugin plugin) {
        this.plugin = plugin;
        this.brain = new VerityBrain(this);
    }

    void start() {
        new BukkitRunnable() {
            @Override
            public void run() {
                onTick();
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    /** Remove runtime entities and undo every temporary world change. */
    void shutdown() {
        for (VerityState s : states.values()) {
            removeEntities(s);
            Player p = Bukkit.getPlayer(s.owner);
            if (p != null) {
                resetEnvironment(p);
            }
        }
        for (ArmorStand box : boxes) {
            box.remove();
        }
        boxes.clear();
        for (PendingTorch t : torches) {
            restoreTorch(t);
        }
        torches.clear();
    }

    // ---------------------------------------------------------------- config

    double dayTicks() {
        return Math.max(1, plugin.getConfig().getDouble("day-length-ticks", 24000));
    }

    private double phaseDays(int phase) {
        switch (phase) {
            case 2:
                return plugin.getConfig().getDouble("phase-days.phase2", 2.0);
            case 3:
                return plugin.getConfig().getDouble("phase-days.phase3", 3.5);
            case 4:
                return plugin.getConfig().getDouble("phase-days.phase4", 5.0);
            default:
                return 0;
        }
    }

    /** Karma 100 slows the transformation to 0.5x, karma 0 speeds it to 1.5x. */
    private double pace(VerityState s) {
        return 1.0 + (50 - s.karma) / 100.0;
    }

    private int computedPhase(VerityState s) {
        double days = s.ageTicks / dayTicks() * pace(s);
        int phase = 1;
        for (int p = 2; p <= 4; p++) {
            if (days >= phaseDays(p)) {
                phase = p;
            }
        }
        return phase;
    }

    void setPhase(VerityState s, int phase) {
        phase = Math.max(1, Math.min(4, phase));
        s.ageTicks = phaseDays(phase) * dayTicks() / pace(s);
        Player p = Bukkit.getPlayer(s.owner);
        int old = s.phase;
        s.phase = phase;
        if (p != null) {
            onPhaseChange(p, s, old);
        }
    }

    // ------------------------------------------------------------ activation

    boolean isActive(UUID id) {
        return states.containsKey(id);
    }

    /** Plays the Box sequence at {@code where}, then releases Verity for {@code p}. */
    void openBox(final Player p, Location where) {
        if (states.containsKey(p.getUniqueId())) {
            return;
        }
        final VerityState s = new VerityState(p.getUniqueId(), p.getName(),
                plugin.getConfig().getInt("karma.start", 50));
        s.greeted = false;
        states.put(p.getUniqueId(), s);
        plugin.saveData();

        final World w = where.getWorld();
        final Location ground = where.clone();
        final ArmorStand box = w.spawn(ground.clone().add(0, 7, 0), ArmorStand.class);
        box.setVisible(false);
        box.setGravity(false);
        box.setInvulnerable(true);
        box.setSmall(false);
        box.setBasePlate(false);
        box.setCollidable(false);
        box.setHelmet(new ItemStack(Material.CHEST));
        box.addScoreboardTag(TAG_BOX);
        boxes.add(box);
        p.sendMessage("§7A strange package falls from the sky...");

        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                t++;
                if (!box.isValid()) {
                    cancel();
                    finishBox(p, s, box, ground);
                    return;
                }
                Location l = box.getLocation();
                if (t <= 35) {
                    double y = Math.max(ground.getY() - 1.4, l.getY() - 0.2);
                    box.teleport(new Location(w, ground.getX(), y, ground.getZ(), l.getYaw(), 0));
                    if (t == 35) {
                        w.playSound(ground, Sound.BLOCK_ANVIL_LAND, 1f, 0.6f);
                        w.spawnParticle(Particle.CLOUD, ground, 20, 0.5, 0.1, 0.5, 0.02);
                    }
                } else if (t <= 95) {
                    float yaw = (float) (Math.sin(t * 0.9) * 12);
                    box.teleport(new Location(w, ground.getX(), ground.getY() - 1.4, ground.getZ(), yaw, 0));
                    if (t % 8 == 0) {
                        w.playSound(ground, Sound.BLOCK_NOTE_PLING, 1f, 0.6f + (t - 35) / 60f * 1.2f);
                        w.spawnParticle(Particle.NOTE, ground.clone().add(0, 1, 0), 2, 0.3, 0.3, 0.3, 1);
                    }
                } else {
                    cancel();
                    finishBox(p, s, box, ground);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void finishBox(Player p, VerityState s, ArmorStand box, Location ground) {
        boxes.remove(box);
        box.remove();
        World w = ground.getWorld();
        w.playSound(ground, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        w.playSound(ground, Sound.BLOCK_CHEST_OPEN, 1f, 1f);
        w.spawnParticle(Particle.VILLAGER_HAPPY, ground.clone().add(0, 1, 0), 40, 0.6, 0.6, 0.6, 0);
        w.spawnParticle(Particle.FIREWORKS_SPARK, ground.clone().add(0, 1, 0), 30, 0.4, 0.4, 0.4, 0.1);
        if (!p.isOnline() || !states.containsKey(p.getUniqueId())) {
            return;
        }
        s.nextEvent = tick + 20 * 90;
        s.greeted = true;
        s.released = true;
        s.mode = "front";
        s.modeUntil = Long.MAX_VALUE;
        ensureStand(s, p);
        if (s.stand != null) {
            s.stand.teleport(ground.clone().add(0, 0.9, 0));
        }
        setFace(s, "(^o^)", 80);
        plugin.saveData();
        final String name = p.getName();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (p.isOnline() && states.containsKey(p.getUniqueId())) {
                    say(p, s, "Hi, " + name + "! I'm Verity, your personal helper friend! :)");
                }
            }
        }, 20L);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (p.isOnline() && states.containsKey(p.getUniqueId())) {
                    say(p, s, "Ask me anything, just say my name. Try \"Verity help\"!");
                }
            }
        }, 70L);
    }

    void remove(UUID id) {
        VerityState s = states.remove(id);
        if (s == null) {
            return;
        }
        removeEntities(s);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            resetEnvironment(p);
        }
        plugin.saveData();
    }

    // ------------------------------------------------------------ main loop

    private void onTick() {
        tick++;
        if (!torches.isEmpty()) {
            for (Iterator<PendingTorch> it = torches.iterator(); it.hasNext();) {
                PendingTorch t = it.next();
                if (tick >= t.restoreAt) {
                    restoreTorch(t);
                    it.remove();
                }
            }
        }
        for (VerityState s : new ArrayList<>(states.values())) {
            Player p = Bukkit.getPlayer(s.owner);
            if (p == null || !p.isOnline() || p.isDead()) {
                if (p == null) {
                    removeEntities(s);
                }
                continue;
            }
            if (!s.released) {
                continue;
            }
            ensureStand(s, p);
            follow(s, p);
            if (tick % 20 == 0) {
                slowTick(s, p);
            }
            if (tick % 10 == 0) {
                updateName(s);
                if (s.phase >= 4) {
                    manageDemon(s, p);
                }
            }
        }
    }

    private void slowTick(VerityState s, Player p) {
        s.ageTicks += 20;
        s.ownerName = p.getName();
        int computed = computedPhase(s);
        if (computed > s.phase) {
            int old = s.phase;
            s.phase = computed;
            onPhaseChange(p, s, old);
        }
        if (s.phase >= 4) {
            applyEnvironment(p);
        }
        if (tick >= s.nextEvent) {
            runEvent(s, p);
            s.nextEvent = tick + eventDelay(s.phase);
        }
        if (s.phase >= 2 && s.stand != null && rnd.nextInt(100) < 4 * s.phase) {
            // Subtle glitch: briefly scramble the face.
            setFace(s, glitch("(^_^)"), 6);
        }
        if (s.phase >= 4 && s.demon != null && s.demon.isValid()) {
            double d = s.demon.getLocation().getWorld() == p.getWorld()
                    ? s.demon.getLocation().distance(p.getLocation()) : 99;
            if (d < 20) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BASEDRUM, 1f, 0.5f);
            }
        }
        if (tick % (20 * 60) == 0) {
            plugin.saveData();
        }
    }

    private int eventDelay(int phase) {
        switch (phase) {
            case 1:
                return 20 * (60 + rnd.nextInt(120));
            case 2:
                return 20 * (30 + rnd.nextInt(60));
            case 3:
                return 20 * (20 + rnd.nextInt(40));
            default:
                return 20 * (8 + rnd.nextInt(17));
        }
    }

    // -------------------------------------------------------------- phases

    private void onPhaseChange(Player p, VerityState s, int old) {
        plugin.saveData();
        if (s.phase < 4) {
            resetEnvironment(p);
            removeDemon(s);
        }
        switch (s.phase) {
            case 1:
                say(p, s, "I'm feeling like my old self again! :)");
                break;
            case 2:
                p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 1f, 0.5f);
                say(p, s, "Is it " + clock(p.getWorld()) + " already? Time flies when we're together.");
                say(p, s, glitchLine(p));
                break;
            case 3:
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMEN_STARE, 1f, 0.5f);
                setFace(s, "(-_-)", 200);
                say(p, s, "You know, " + p.getName() + ", I'm not sure I like being your helper anymore.");
                say(p, s, "Something is watching us. Do you feel it?");
                break;
            default:
                setFace(s, "(>:D)", 400);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_THUNDER, 2f, 0.5f);
                p.getWorld().strikeLightningEffect(p.getLocation().add(rnd.nextInt(20) - 10, 0, rnd.nextInt(20) - 10));
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
                say(p, s, "THE BOX IS OPEN. THE DEMON IS FREE.");
                say(p, s, "I TOLD YOU I'D ALWAYS BE HERE, " + p.getName().toUpperCase(Locale.ROOT) + ".");
                s.demonRespawnAt = tick + 60;
                applyEnvironment(p);
                break;
        }
    }

    private void applyEnvironment(Player p) {
        if (p.isPlayerTimeRelative()) {
            p.setPlayerTime(18000, false);
        }
        if (p.getPlayerWeather() != WeatherType.DOWNFALL) {
            p.setPlayerWeather(WeatherType.DOWNFALL);
        }
    }

    void resetEnvironment(Player p) {
        if (!p.isPlayerTimeRelative()) {
            p.resetPlayerTime();
        }
        if (p.getPlayerWeather() != null) {
            p.resetPlayerWeather();
        }
        p.removePotionEffect(PotionEffectType.BLINDNESS);
        p.removePotionEffect(PotionEffectType.CONFUSION);
    }

    // -------------------------------------------------------------- events

    private void runEvent(VerityState s, Player p) {
        String name = p.getName();
        switch (s.phase) {
            case 1:
                eventPhase1(s, p);
                break;
            case 2:
                if (rnd.nextInt(3) == 0) {
                    p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.6f, 0.6f + rnd.nextFloat() * 0.4f);
                }
                if (rnd.nextBoolean()) {
                    say(p, s, glitchLine(p));
                } else {
                    setFace(s, "(^_~)", 60);
                    say(p, s, pick("I like watching you work, " + name + ".",
                            "You've been here a while. Don't you ever sleep?",
                            "I noticed you changed your mind about something. I notice everything."));
                }
                break;
            case 3:
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMEN_AMBIENT, 1f, 0.5f);
                int r = rnd.nextInt(5);
                if (r <= 1) {
                    say(p, s, pick(P3_LINES).replace("{name}", name));
                } else if (r == 2) {
                    extinguishTorches(p, 8);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 30, 0));
                    say(p, s, "Lights out.");
                } else if (r == 3) {
                    p.playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 0.7f, 0.5f);
                    say(p, s, "He is " + (30 + rnd.nextInt(30)) + " blocks away. Maybe less.");
                } else {
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BASS, 1f, 0.5f);
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 0));
                    setFace(s, ">_>", 60);
                }
                break;
            default:
                eventPhase4(s, p);
                break;
        }
    }

    private void eventPhase1(VerityState s, Player p) {
        if (hostilesNear(p, 16) > 0 && tick - s.lastWarn > 20 * 20) {
            s.lastWarn = tick;
            setFace(s, "(O_O)", 100);
            say(p, s, "Careful, " + p.getName() + "! There's something hostile nearby!");
        } else if (p.getHealth() <= 8) {
            say(p, s, "You're hurt! Eat something and rest.");
        } else if (p.getFoodLevel() <= 6) {
            say(p, s, "Your stomach is rumbling. Time to eat!");
        } else if (p.getWorld().getTime() > 13000 && p.getWorld().getTime() < 14000) {
            say(p, s, "It's getting dark. Monsters spawn at night - maybe find a bed?");
        } else {
            say(p, s, pick(TIPS));
        }
    }

    private void eventPhase4(VerityState s, Player p) {
        Location l = p.getLocation();
        int r = rnd.nextInt(9);
        switch (r) {
            case 0:
                p.getWorld().strikeLightningEffect(l.clone().add(rnd.nextInt(16) - 8, 0, rnd.nextInt(16) - 8));
                break;
            case 1:
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 50, 0));
                p.addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 120, 0));
                break;
            case 2:
                p.playSound(l, Sound.ENTITY_GHAST_SCREAM, 1f, 0.5f);
                p.playSound(l, Sound.ENTITY_ENDERMEN_SCREAM, 1f, 0.5f);
                break;
            case 3:
                extinguishTorches(p, 12);
                break;
            case 4:
                if (plugin.getConfig().getBoolean("harm.enabled", true)) {
                    double min = plugin.getConfig().getDouble("harm.min-health", 6.0);
                    double dmg = plugin.getConfig().getDouble("harm.damage", 2.0);
                    if (p.getHealth() - dmg >= min) {
                        p.damage(dmg);
                        p.playSound(l, Sound.ENTITY_PLAYER_HURT, 1f, 0.6f);
                    }
                }
                break;
            case 5:
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand != null && hand.getType() != Material.AIR && p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
                    p.getInventory().setItemInMainHand(null);
                    p.getWorld().dropItem(l, hand).setPickupDelay(40);
                    say(p, s, "YOU DON'T NEED THAT.");
                }
                break;
            case 6:
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 80, 1));
                p.playSound(l, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 0.5f);
                break;
            default:
                break;
        }
        if (r >= 7 || rnd.nextInt(3) == 0) {
            String line = pick(P4_LINES).replace("{NAME}", p.getName().toUpperCase(Locale.ROOT));
            say(p, s, line);
        }
    }

    private int hostilesNear(Player p, double r) {
        int n = 0;
        for (Entity e : p.getNearbyEntities(r, r / 2, r)) {
            if (e instanceof Monster && !e.getScoreboardTags().contains(TAG_DEMON)) {
                n++;
            }
        }
        return n;
    }

    private void extinguishTorches(Player p, int radius) {
        Location c = p.getLocation();
        World w = c.getWorld();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius / 2; y <= radius / 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Block b = w.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z);
                    if (b.getType() == Material.TORCH) {
                        torches.add(new PendingTorch(b.getState(), tick + 20 * (5 + rnd.nextInt(8))));
                        b.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    private void restoreTorch(PendingTorch t) {
        Block b = t.state.getBlock();
        if (b.getType() == Material.AIR && b.getWorld().isChunkLoaded(b.getX() >> 4, b.getZ() >> 4)) {
            t.state.update(true, false);
        }
    }

    // ---------------------------------------------------------- the Demon

    private void manageDemon(VerityState s, Player p) {
        if (!plugin.getConfig().getBoolean("demon.enabled", true)
                || p.getGameMode() == org.bukkit.GameMode.SPECTATOR) {
            return;
        }
        Spider d = s.demon;
        if (d == null || !d.isValid()) {
            if (d != null) {
                // The Demon can be killed, but it always comes back.
                s.demon = null;
                s.demonRespawnAt = tick + 20 * plugin.getConfig().getInt("demon.respawn-seconds", 60);
            }
            if (tick >= s.demonRespawnAt) {
                spawnDemon(s, p);
            }
            return;
        }
        if (d.getWorld() != p.getWorld() || d.getLocation().distanceSquared(p.getLocation()) > 64 * 64) {
            Location spot = findSpawn(p, 24, 32);
            if (spot != null) {
                d.teleport(spot);
            }
        }
        d.setTarget(p);
    }

    private void spawnDemon(VerityState s, Player p) {
        Location spot = findSpawn(p, 22, 30);
        if (spot == null) {
            return;
        }
        Spider d = spot.getWorld().spawn(spot, Spider.class);
        d.setCustomName("§4The Demon");
        d.setCustomNameVisible(false);
        d.setRemoveWhenFarAway(false);
        d.setCanPickupItems(false);
        d.addScoreboardTag(TAG_DEMON);
        double hp = plugin.getConfig().getDouble("demon.health", 120.0);
        setAttr(d, Attribute.GENERIC_MAX_HEALTH, hp);
        d.setHealth(Math.min(hp, d.getMaxHealth()));
        setAttr(d, Attribute.GENERIC_ATTACK_DAMAGE, plugin.getConfig().getDouble("demon.damage", 6.0));
        setAttr(d, Attribute.GENERIC_MOVEMENT_SPEED, plugin.getConfig().getDouble("demon.speed", 0.42));
        setAttr(d, Attribute.GENERIC_FOLLOW_RANGE, 80);
        d.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        d.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, Integer.MAX_VALUE, 0, false, false));
        d.setTarget(p);
        s.demon = d;
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.5f);
        say(p, s, "HE'S HERE.");
    }

    private void setAttr(LivingEntity e, Attribute a, double v) {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null) {
            inst.setBaseValue(v);
        }
    }

    private Location findSpawn(Player p, int min, int max) {
        World w = p.getWorld();
        Location base = p.getLocation();
        for (int i = 0; i < 24; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            double dist = min + rnd.nextDouble() * (max - min);
            int x = (int) Math.floor(base.getX() + Math.cos(ang) * dist);
            int z = (int) Math.floor(base.getZ() + Math.sin(ang) * dist);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            for (int y = base.getBlockY() + 6; y >= Math.max(1, base.getBlockY() - 10); y--) {
                Block b = w.getBlockAt(x, y, z);
                if (b.getType().isSolid() && !b.getRelative(0, 1, 0).getType().isSolid()
                        && !b.getRelative(0, 2, 0).getType().isSolid()
                        && !b.isLiquid() && !b.getRelative(0, 1, 0).isLiquid()) {
                    return new Location(w, x + 0.5, y + 1, z + 0.5);
                }
            }
        }
        return null;
    }

    private void removeDemon(VerityState s) {
        if (s.demon != null) {
            s.demon.remove();
            s.demon = null;
        }
    }

    // ------------------------------------------------------ Verity's body

    private void ensureStand(VerityState s, Player p) {
        if (s.stand != null && s.stand.isValid()) {
            return;
        }
        boolean respawn = s.stand != null;
        Location l = p.getLocation().add(0, 1.2, 0);
        ArmorStand st = l.getWorld().spawn(l, ArmorStand.class);
        st.setVisible(false);
        st.setGravity(false);
        st.setSmall(true);
        st.setBasePlate(false);
        st.setArms(false);
        st.setInvulnerable(true);
        st.setCollidable(false);
        st.setHelmet(new ItemStack(Material.GLOWSTONE));
        st.setCustomNameVisible(true);
        st.addScoreboardTag(TAG_VERITY);
        s.stand = st;
        s.shownName = "";
        updateName(s);
        if (respawn && s.phase >= 3) {
            say(p, s, "I always come back.");
        }
    }

    private void follow(VerityState s, Player p) {
        ArmorStand st = s.stand;
        if (st == null || !st.isValid()) {
            return;
        }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = new Vector(0, 0, 1);
        }
        dir.normalize();
        Vector right = new Vector(-dir.getZ(), 0, dir.getX());
        double bob = Math.sin(tick / 8.0) * 0.12;
        Vector off = offsetFor(s, dir, right, bob);
        // The small stand's head sits roughly 0.75 above its feet.
        Location target = eye.clone().add(off).subtract(0, 0.75, 0);
        Location cur = st.getLocation();
        Location next;
        if (cur.getWorld() != target.getWorld() || cur.distanceSquared(target) > 30 * 30) {
            next = target.clone();
        } else {
            next = cur.clone().add(target.clone().subtract(cur).toVector().multiply(0.3));
        }
        Vector look = eye.toVector().subtract(next.toVector().add(new Vector(0, 0.75, 0)));
        next.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())));
        next.setPitch(0);
        st.teleport(next);
        if (tick % 12 == 0) {
            Location pl = next.clone().add(0, 0.8, 0);
            if (s.phase == 1) {
                pl.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, pl, 1, 0.15, 0.15, 0.15, 0);
            } else if (s.phase == 2) {
                pl.getWorld().spawnParticle(Particle.CRIT_MAGIC, pl, 2, 0.2, 0.2, 0.2, 0);
            } else if (s.phase == 3) {
                pl.getWorld().spawnParticle(Particle.SMOKE_NORMAL, pl, 3, 0.2, 0.2, 0.2, 0.01);
            } else {
                pl.getWorld().spawnParticle(Particle.REDSTONE, pl, 4, 0.25, 0.25, 0.25, 0);
                pl.getWorld().spawnParticle(Particle.SMOKE_LARGE, pl, 1, 0.2, 0.2, 0.2, 0.01);
            }
        }
    }

    /** Where Verity hovers relative to the owner's eyes; requests (mode) override the phase default. */
    private Vector offsetFor(VerityState s, Vector dir, Vector right, double bob) {
        String mode = s.mode;
        if (mode != null && tick > s.modeUntil) {
            s.mode = mode = null;
        }
        if (s.phase >= 4) {
            mode = "front";
        } else if (mode == null) {
            mode = s.phase == 1 ? "front" : s.phase == 2 ? "right" : "behind";
        }
        double dist = s.phase == 3 ? 2.6 : 2.0;
        switch (mode) {
            case "left":
                return right.clone().multiply(-1.5).add(dir.clone().multiply(0.8)).setY(0.2 + bob);
            case "right":
                return right.clone().multiply(1.5).add(dir.clone().multiply(0.8)).setY(0.2 + bob);
            case "behind":
                return dir.clone().multiply(-dist).setY(0.2 + bob);
            case "above":
                return dir.clone().multiply(0.4).setY(1.6 + bob);
            case "circle": {
                double a = tick / 10.0;
                return new Vector(Math.cos(a) * 2.0, 0.2 + bob, Math.sin(a) * 2.0);
            }
            default:
                return dir.clone().multiply(dist).setY(0.2 + bob);
        }
    }

    /** Applies a movement request from chat. Returns the reply or null if nothing matched. */
    String requestMove(VerityState s, String msg) {
        String m = null;
        String reply = null;
        if (msg.contains("circle") || msg.contains("orbit") || msg.contains("spin")) {
            m = "circle";
            reply = "Wheee! Around and around!";
        } else if (msg.contains("behind") || msg.contains("back of me")) {
            m = "behind";
            reply = "Okay, I'll stay behind you.";
        } else if (msg.contains("above") || msg.contains("over my head") || msg.contains("up here") || msg.contains("hover")) {
            m = "above";
            reply = "Floating above you!";
        } else if (msg.matches(".*\\bleft\\b.*")) {
            m = "left";
            reply = "I'll float on your left.";
        } else if (msg.matches(".*\\bright\\b.*")) {
            m = "right";
            reply = "I'll float on your right.";
        } else if (msg.contains("front") || msg.contains("come here") || msg.contains("come to me")
                || msg.contains("follow") || msg.contains("closer")) {
            m = "front";
            reply = "Right in front of you!";
        }
        if (m == null) {
            return null;
        }
        s.mode = m;
        s.modeUntil = Long.MAX_VALUE;
        return reply;
    }

    void removeEntities(VerityState s) {
        if (s.stand != null) {
            s.stand.remove();
            s.stand = null;
        }
        removeDemon(s);
        s.shownName = "";
    }

    void setFace(VerityState s, String face, int ticks) {
        s.faceOverride = face;
        s.faceUntil = tick + ticks;
        updateName(s);
    }

    private String baseFace(VerityState s) {
        switch (s.phase) {
            case 1:
                return "§e(^_^)";
            case 2:
                return "§e(^_^)";
            case 3:
                return "§6(-_-)";
            default:
                return "§4(>:D)";
        }
    }

    private void updateName(VerityState s) {
        if (s.stand == null || !s.stand.isValid()) {
            return;
        }
        String face;
        if (s.faceOverride != null && tick < s.faceUntil) {
            face = (s.phase >= 4 ? "§4" : s.phase == 3 ? "§6" : "§e") + s.faceOverride;
        } else {
            face = baseFace(s);
        }
        String name = (s.phase >= 4 ? "§4§lVerity " : s.phase == 3 ? "§6Verity " : "§eVerity ") + face;
        if (!name.equals(s.shownName)) {
            s.shownName = name;
            s.stand.setCustomName(name);
        }
    }

    // ----------------------------------------------------------- speaking

    void say(Player p, VerityState s, String text) {
        String prefix = s.phase >= 4 ? "§4§lVerity §8» §c" : s.phase == 3 ? "§6Verity §8» §7" : "§eVerity §8» §f";
        p.sendMessage(prefix + text);
        float pitch = s.phase == 1 ? 1.6f : s.phase == 2 ? 1.2f : s.phase == 3 ? 0.7f : 0.5f;
        p.playSound(p.getLocation(), s.phase >= 3 ? Sound.BLOCK_NOTE_BASS : Sound.BLOCK_NOTE_PLING, 0.8f, pitch);
    }

    /** Replies after a short "typing" delay. */
    void replyLater(final Player p, final VerityState s, final String message) {
        long delay = 12 + rnd.nextInt(20);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (p.isOnline() && states.get(p.getUniqueId()) == s) {
                    brain.respond(p, s, message);
                }
            }
        }, delay);
    }

    String glitch(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (c != ' ' && rnd.nextInt(5) == 0) {
                sb.append("§k").append(c).append("§r");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    String glitchLine(Player p) {
        Location l = p.getLocation();
        int items = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR) {
                items++;
            }
        }
        int mins = p.getStatistic(Statistic.PLAY_ONE_TICK) / 1200;
        String time = new SimpleDateFormat("HH:mm 'on' EEEE, d MMMM yyyy", Locale.ENGLISH).format(new Date());
        String[] lines = {
                "It's " + time + " for you, isn't it?",
                "You've played for " + mins + " minutes. Don't you have somewhere to be?",
                "You're carrying " + items + " items. Slot " + (1 + rnd.nextInt(9)) + " is my favorite.",
                "You're at " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + ". I always know where.",
                "I can see your health: " + (int) Math.ceil(p.getHealth()) + ". So fragile.",
                "Did you notice the glitch? I didn't do that. ...Or did I?"};
        return glitch(pick(lines));
    }

    String clock(World w) {
        long t = (w.getTime() + 6000) % 24000;
        return String.format(Locale.ROOT, "%02d:%02d", t / 1000, (t % 1000) * 60 / 1000);
    }

    void playMelody(final Player p) {
        final float[] notes = {0.8f, 1.0f, 1.19f, 1.5f, 1.19f, 1.0f, 1.26f, 1.5f, 1.68f, 2.0f, 1.68f, 1.5f};
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (!p.isOnline() || i >= notes.length) {
                    cancel();
                    return;
                }
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_HARP, 1f, notes[i]);
                p.getWorld().spawnParticle(Particle.NOTE, p.getLocation().add(0, 2, 0), 1, 0.3, 0.2, 0.3, i / 24.0);
                i++;
            }
        }.runTaskTimer(plugin, 0L, 6L);
    }

    String pick(String... options) {
        return options[rnd.nextInt(options.length)];
    }

    long now() {
        return tick;
    }

    boolean isVerityEntity(Entity e) {
        return e.getScoreboardTags().contains(TAG_VERITY) || e.getScoreboardTags().contains(TAG_BOX)
                || e.getScoreboardTags().contains(TAG_DEMON);
    }

    /** Removes tagged leftovers (e.g. from a crash) that no state is tracking. */
    void cleanupStray(Entity e) {
        if (!isVerityEntity(e)) {
            return;
        }
        for (VerityState s : states.values()) {
            if (s.stand == e || s.demon == e) {
                return;
            }
        }
        if (e instanceof ArmorStand && boxes.contains(e)) {
            return;
        }
        e.remove();
    }

    VerityState ownerOfStand(Entity e) {
        for (VerityState s : states.values()) {
            if (s.stand == e) {
                return s;
            }
        }
        return null;
    }

    VerityState ownerOfDemon(Entity e) {
        for (VerityState s : states.values()) {
            if (s.demon == e) {
                return s;
            }
        }
        return null;
    }

    boolean isBox(Entity e) {
        return e instanceof ArmorStand && boxes.contains(e);
    }
}
