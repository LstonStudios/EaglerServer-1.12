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
import org.bukkit.util.EulerAngle;
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
    static final String TAG_DEMON_BODY = "verity_demon_body";

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
            if (s.phase >= 4) {
                demonTick(s, p);
                followBody(s, p);
            }
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
        } else if (s.phase == 3 && plugin.getConfig().getBoolean("world-decay.enabled", true)) {
            double days = s.ageTicks / dayTicks() * pace(s);
            if (!s.wiped && days >= (phaseDays(3) + phaseDays(4)) / 2) {
                worldWipe(s, p);
            }
            if (s.wiped) {
                p.setPlayerTime(s.frozenTime, false);
            }
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
                say(p, s, "Something is coming. Two days, maybe less.");
                break;
            default:
                setFace(s, "(>:D)", 400);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_THUNDER, 2f, 0.5f);
                p.getWorld().strikeLightningEffect(p.getLocation().add(rnd.nextInt(20) - 10, 0, rnd.nextInt(20) - 10));
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 80, 0));
                say(p, s, "THE BOX IS OPEN. THE DEMON IS FREE.");
                say(p, s, "I TOLD YOU I'D ALWAYS BE HERE, " + p.getName().toUpperCase(Locale.ROOT) + ".");
                s.demonRespawnAt = tick + 60;
                s.apologies = 0;
                s.calmUntil = tick + 20L * plugin.getConfig().getInt("demon.calm-seconds", 60);
                if (s.calmUntil > tick) {
                    p.sendMessage("§8Verity's face has gone completely blank. Stay close, look at it and say \"I came back for you\" - or say sorry three times.");
                }
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
        LivingEntity d = s.demon;
        if (d == null || !d.isValid()) {
            if (d != null) {
                s.demon = null;
                if (d.isDead() && d.getHealth() <= 0) {
                    demonSlain(s, p);
                    return;
                }
                removeBody(s);
                s.demonRespawnAt = tick + 20;
            }
            if (tick >= s.demonRespawnAt && p.getWorld().getDifficulty() != org.bukkit.Difficulty.PEACEFUL) {
                spawnDemon(s, p);
            }
            return;
        }
        if (d.getWorld() != p.getWorld() || d.getLocation().distanceSquared(p.getLocation()) > 96 * 96) {
            Location spot = findSpawn(p, 24, 32);
            if (spot != null) {
                d.teleport(spot);
            }
        }
        boolean calm = tick < s.calmUntil;
        d.setAI(!calm);
        if (calm) {
            return;
        }
        if (d instanceof org.bukkit.entity.Creature) {
            ((org.bukkit.entity.Creature) d).setTarget(demonTarget(d, p));
        }
    }

    /** The Demon goes for villagers before the player. */
    private LivingEntity demonTarget(LivingEntity d, Player p) {
        if (plugin.getConfig().getBoolean("demon.attack-mobs", true)) {
            LivingEntity best = null;
            double bestD = 24 * 24;
            for (Entity e : d.getNearbyEntities(24, 12, 24)) {
                if (e instanceof org.bukkit.entity.Villager && e.isValid()) {
                    double dd = e.getLocation().distanceSquared(d.getLocation());
                    if (dd < bestD) {
                        bestD = dd;
                        best = (LivingEntity) e;
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return p;
    }

    /** Per-tick Demon traits: ignores water, climbs walls, smashes glass and doors, grabs and hurls. */
    private void demonTick(VerityState s, Player p) {
        LivingEntity d = s.demon;
        if (d == null || !d.isValid() || tick < s.calmUntil || !(d instanceof org.bukkit.entity.Creature)) {
            return;
        }
        LivingEntity t = ((org.bukkit.entity.Creature) d).getTarget();
        if (t == null || t.getWorld() != d.getWorld()) {
            return;
        }
        Location dl = d.getLocation();
        Vector to = t.getLocation().toVector().subtract(dl.toVector());
        Vector flat = new Vector(to.getX(), 0, to.getZ());
        if (flat.lengthSquared() < 0.25) {
            return;
        }
        flat.normalize();
        Block front = dl.clone().add(flat.clone().multiply(0.9)).getBlock();
        if (tick % 4 == 0 && plugin.getConfig().getBoolean("demon.break-blocks", true)) {
            for (int y = 0; y <= 2; y++) {
                smash(front.getRelative(0, y, 0));
            }
        }
        Vector v = d.getVelocity();
        if (tick % 10 == 0) {
            // Pathfinding can stall (corners, trees); if he hasn't moved, shove him toward the target.
            if (s.stuckLoc != null && s.stuckLoc.getWorld() == dl.getWorld() && to.lengthSquared() > 3.5 * 3.5
                    && s.stuckLoc.distanceSquared(dl) < 0.15 * 0.15 && d.isOnGround()) {
                d.setVelocity(new Vector(flat.getX() * 0.5, 0.42, flat.getZ() * 0.5));
            }
            s.stuckLoc = dl.clone();
        }
        if (dl.getBlock().isLiquid() || dl.clone().add(0, 1, 0).getBlock().isLiquid()) {
            d.setVelocity(new Vector(flat.getX() * 0.3, Math.max(v.getY(), to.getY() > 0 ? 0.28 : 0.08), flat.getZ() * 0.3));
        } else if (front.getType().isSolid() && to.getY() > -3) {
            d.setVelocity(new Vector(flat.getX() * 0.08, 0.34, flat.getZ() * 0.08));
        }
        if (t == p && to.lengthSquared() < 2.8 * 2.8 && tick - s.lastGrab > 20 * 10
                && plugin.getConfig().getBoolean("demon.grab", true)) {
            s.lastGrab = tick;
            grab(d, p);
        }
    }

    private void smash(Block b) {
        Material m = b.getType();
        String n = m.name();
        if (n.contains("GLASS") || n.contains("DOOR") || m == Material.IRON_FENCE) {
            b.getWorld().playSound(b.getLocation(), Sound.BLOCK_GLASS_BREAK, 1f, 0.6f);
            b.setType(Material.AIR);
        }
    }

    private void grab(final LivingEntity d, final Player p) {
        p.setVelocity(new Vector(0, 1.1, 0));
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 1f, 0.5f);
        final double dmg = plugin.getConfig().getDouble("demon.grab-damage", 4.0);
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || p.isDead() || !d.isValid()) {
                    return;
                }
                Vector away = p.getLocation().toVector().subtract(d.getLocation().toVector()).setY(0);
                if (away.lengthSquared() < 0.01) {
                    away = new Vector(1, 0, 0);
                }
                p.setVelocity(away.normalize().multiply(1.7).setY(0.5));
                p.damage(dmg, d);
            }
        }, 14L);
    }

    // Demon body: five marker armor stands wear the jointed models (see build_demon.py). Every part is
    // authored with its joint at the model centre, so the stand's head pose swings it about that joint.
    // DEMON_PX = blocks per reference pixel = (1/16) * 0.625 (head layer) * display scale 2 * (30/113).
    private static final double DEMON_PX = 0.0625 * 0.625 * 2.0 * 30.0 / 113.0;
    // Height of the helmet model's centre above a normal stand's feet (measured estimate).
    private static final double HEAD_ORIGIN = 1.69;
    private static final double DEMON_HEIGHT_PX = 120;
    // part: body, left arm, right arm, left leg, right leg -> {sideways px, joint px from top}
    private static final double[][] DEMON_JOINTS = {{0, 70}, {10.5, 37}, {-10.5, 37}, {3.75, 92}, {-3.75, 92}};

    private ItemStack demonPartItem(int part) {
        return new ItemStack(part == 0 ? Material.COMMAND : part <= 2 ? Material.BURNING_FURNACE : Material.ENDER_PORTAL_FRAME);
    }

    boolean isDemonPart(VerityState s, Entity e) {
        if (s.demonParts != null) {
            for (ArmorStand a : s.demonParts) {
                if (a == e) {
                    return true;
                }
            }
        }
        return false;
    }

    private ArmorStand newDemonPart(Location l, int part) {
        ArmorStand b = l.getWorld().spawn(l, ArmorStand.class);
        b.setVisible(false);
        b.setGravity(false);
        b.setMarker(true);
        b.setBasePlate(false);
        b.setSilent(true);
        b.addScoreboardTag(TAG_DEMON_BODY);
        b.setHelmet(demonPartItem(part));
        return b;
    }

    /** Poses and moves the Demon's body: walk cycle, hunch, and arms raised when he is about to grab. */
    private void followBody(VerityState s, Player owner) {
        LivingEntity d = s.demon;
        if (d == null || !d.isValid()) {
            removeBody(s);
            return;
        }
        Location l = d.getLocation();
        boolean fresh = s.demonParts == null;
        if (!fresh) {
            for (ArmorStand a : s.demonParts) {
                if (!a.isValid()) {
                    fresh = true;
                }
            }
        }
        if (fresh) {
            removeBody(s);
            s.demonParts = new ArmorStand[5];
            for (int i = 0; i < 5; i++) {
                s.demonParts[i] = newDemonPart(l, i);
            }
        }
        double speed = 0;
        if (s.lastDemonLoc != null && s.lastDemonLoc.getWorld() == l.getWorld()) {
            double dx = l.getX() - s.lastDemonLoc.getX();
            double dz = l.getZ() - s.lastDemonLoc.getZ();
            speed = Math.sqrt(dx * dx + dz * dz);
        }
        s.lastDemonLoc = l.clone();
        s.walkPhase += speed * 2.4;
        boolean calm = tick < s.calmUntil;
        float yaw = l.getYaw();
        if (calm && owner.getWorld() == l.getWorld()) {
            Vector to = owner.getLocation().toVector().subtract(l.toVector());
            yaw = (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
        }
        double dist = owner.getWorld() == l.getWorld() ? owner.getLocation().distance(l) : 99;
        boolean reaching = !calm && dist < 4.5;
        double amp = Math.min(1.0, speed / 0.18);
        double swing = Math.sin(s.walkPhase) * 0.8 * amp;
        double breath = Math.sin(tick * 0.09) * 0.03;
        double hunch = 0.2 + 0.15 * amp + (reaching ? 0.15 : 0);
        double yawRad = Math.toRadians(yaw);
        double lx = Math.cos(yawRad);
        double lz = Math.sin(yawRad);
        for (int i = 0; i < 5; i++) {
            ArmorStand a = s.demonParts[i];
            double side = DEMON_JOINTS[i][0] * DEMON_PX;
            double y = (DEMON_HEIGHT_PX - DEMON_JOINTS[i][1]) * DEMON_PX - HEAD_ORIGIN;
            a.teleport(new Location(l.getWorld(), l.getX() + lx * side, l.getY() + y, l.getZ() + lz * side, yaw, 0));
            EulerAngle pose;
            switch (i) {
                case 0:
                    pose = new EulerAngle(hunch + breath, 0, 0);
                    break;
                case 1:
                case 2: {
                    double sgn = i == 1 ? 1 : -1;
                    double x = reaching ? -1.45 + Math.sin(tick * 0.45) * 0.12 : -sgn * swing * 0.9 + breath * 2;
                    pose = new EulerAngle(x, 0, reaching ? 0 : sgn * 0.07);
                    break;
                }
                default:
                    pose = new EulerAngle((i == 3 ? 1 : -1) * swing, 0, 0);
                    break;
            }
            a.setHeadPose(pose);
        }
    }

    private void removeBody(VerityState s) {
        if (s.demonParts != null) {
            for (ArmorStand a : s.demonParts) {
                a.remove();
            }
            s.demonParts = null;
        }
        s.lastDemonLoc = null;
    }

    private void spawnDemon(VerityState s, Player p) {
        Location spot = findSpawn(p, 22, 30);
        if (spot == null) {
            return;
        }
        org.bukkit.entity.WitherSkeleton d = spot.getWorld().spawn(spot, org.bukkit.entity.WitherSkeleton.class);
        d.setCustomName("§4The Demon");
        d.setCustomNameVisible(false);
        d.setRemoveWhenFarAway(false);
        d.setCanPickupItems(false);
        d.getEquipment().clear();
        d.addScoreboardTag(TAG_DEMON);
        double hp = plugin.getConfig().getDouble("demon.health", 120.0);
        setAttr(d, Attribute.GENERIC_MAX_HEALTH, hp);
        d.setHealth(Math.min(hp, d.getMaxHealth()));
        setAttr(d, Attribute.GENERIC_ATTACK_DAMAGE, plugin.getConfig().getDouble("demon.damage", 6.0));
        setAttr(d, Attribute.GENERIC_MOVEMENT_SPEED, plugin.getConfig().getDouble("demon.speed", 0.42));
        setAttr(d, Attribute.GENERIC_FOLLOW_RANGE, 128);
        d.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, Integer.MAX_VALUE, 0, false, false));
        d.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        s.demon = d;
        d.setAI(tick >= s.calmUntil);
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.8f, 0.5f);
        say(p, s, "HE'S HERE.");
    }

    /** Killing the Demon ends the ordeal: karma snaps to full and Verity is herself again. */
    void demonSlain(VerityState s, Player p) {
        resetAfterDemon(s, p, 100);
        setFace(s, "(^_^)", 200);
        say(p, s, "The darkness... it's gone. Thank you.");
    }

    private void resetAfterDemon(VerityState s, Player p, int karma) {
        s.karma = karma;
        s.ageTicks = 0;
        s.phase = 1;
        s.wiped = false;
        s.calmUntil = 0;
        s.apologies = 0;
        onPhaseChange(p, s, 4);
    }

    /** Called with the owner's chat while Verity's face is blank. */
    void tryCalm(Player p, VerityState s, String msg) {
        if (s.phase < 4 || tick >= s.calmUntil) {
            return;
        }
        String m = msg.toLowerCase(Locale.ROOT).replace("'", "").replace("\u2019", "");
        boolean back = m.contains("i came back for you");
        boolean sorry = m.contains("im sorry") || m.contains("i am sorry") || m.contains("forgive me");
        if (!back && !sorry) {
            return;
        }
        if (s.stand == null || !s.stand.isValid() || s.stand.getWorld() != p.getWorld()
                || s.stand.getLocation().distance(p.getLocation()) > 10) {
            p.sendMessage("§7Verity is too far away to hear you.");
            return;
        }
        Vector look = p.getEyeLocation().getDirection();
        Vector at = s.stand.getLocation().add(0, 0.6, 0).toVector().subtract(p.getEyeLocation().toVector()).normalize();
        if (look.dot(at) < 0.5) {
            p.sendMessage("§7Look at Verity so it knows you mean it.");
            return;
        }
        if (back || ++s.apologies >= 3) {
            resetAfterDemon(s, p, Math.max(s.karma, 60));
            setFace(s, "(^_^)", 200);
            say(p, s, "...you came back. I'm okay now. Please don't leave me.");
        } else {
            p.sendMessage("§7Verity's face flickers... (" + s.apologies + "/3)");
        }
    }

    /** The world dies before the Demon: halfway through phase 3 every nearby creature vanishes. */
    private void worldWipe(VerityState s, Player p) {
        s.wiped = true;
        s.frozenTime = p.getWorld().getTime();
        for (Entity e : p.getNearbyEntities(128, 128, 128)) {
            if (!(e instanceof LivingEntity) || e instanceof Player || isVerityEntity(e)) {
                continue;
            }
            if (e instanceof org.bukkit.entity.Tameable && ((org.bukkit.entity.Tameable) e).isTamed()) {
                continue;
            }
            if (e.getCustomName() != null) {
                continue;
            }
            e.remove();
        }
        say(p, s, "Everything is so quiet now. Do you hear that?");
    }

    /** Newly spawned animals and villagers die while the world is dying (phase 3). */
    boolean decaysSpawn(Entity e) {
        if (!plugin.getConfig().getBoolean("world-decay.enabled", true)) {
            return false;
        }
        boolean anyone = false;
        for (VerityState st : states.values()) {
            Player o = Bukkit.getPlayer(st.owner);
            if (st.phase == 3 && o != null && o.getWorld() == e.getWorld()) {
                anyone = true;
            }
        }
        if (!anyone) {
            return false;
        }
        if (e.getType() == EntityType.COW || e.getType() == EntityType.MUSHROOM_COW) {
            return true;
        }
        return (e.getType() == EntityType.SHEEP || e.getType() == EntityType.PIG
                || e.getType() == EntityType.CHICKEN || e.getType() == EntityType.VILLAGER) && rnd.nextBoolean();
    }

    boolean demonNear(Player p, double radius) {
        for (VerityState st : states.values()) {
            LivingEntity d = st.demon;
            if (d != null && d.isValid() && d.getWorld() == p.getWorld()
                    && d.getLocation().distanceSquared(p.getLocation()) < radius * radius) {
                return true;
            }
        }
        return false;
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
        removeBody(s);
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
        s.shownExpression = -1;
        st.setHelmet(expressionItem(expressionIndex(s)));
        s.shownExpression = expressionIndex(s);
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
        updateFacing(s);
        next.setYaw((float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ())) + s.facing);
        next.setPitch(0);
        st.teleport(next);
        st.setHeadPose(headPose(s));
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

    /** Turns the body toward the wanted angle, or spins it while a spin is running. */
    private void updateFacing(VerityState s) {
        if (tick < s.spinUntil) {
            s.facing += 28f;
            return;
        }
        float diff = ((s.facingTarget - s.facing) % 360f + 540f) % 360f - 180f;
        s.facing += Math.max(-30f, Math.min(30f, diff));
        if (Math.abs(diff) < 0.5f) {
            s.facing = s.facingTarget;
        }
    }

    private EulerAngle headPose(VerityState s) {
        if (s.gesture == null) {
            return EulerAngle.ZERO;
        }
        long left = s.gestureUntil - tick;
        if (left <= 0) {
            s.gesture = null;
            return EulerAngle.ZERO;
        }
        double wave = Math.sin(left * 0.5);
        switch (s.gesture) {
            case "nod":
                return new EulerAngle(wave * 0.45, 0, 0);
            case "shake":
                return new EulerAngle(0, wave * 0.9, 0);
            case "tilt":
                return new EulerAngle(0, 0, 0.4);
            case "up":
                return new EulerAngle(-0.5, 0, 0);
            case "down":
                return new EulerAngle(0.5, 0, 0);
            default:
                return EulerAngle.ZERO;
        }
    }

    /** Body and head requests ("turn around", "spin", "nod"...). Returns the reply or null. */
    private String requestGesture(VerityState s, String msg) {
        if (msg.contains("turn around") || msg.contains("turn away") || msg.contains("face away")
                || msg.contains("look away") || msg.contains("face the other way")) {
            boolean toBack = s.facingTarget % 360f == 0f;
            s.facingTarget = toBack ? 180f : 0f;
            s.spinUntil = 0;
            setFace(s, "(^_~)", 60);
            return toBack ? "Okay, turning around!" : "Okay, facing you again!";
        }
        if (msg.contains("face me") || msg.contains("look at me") || msg.contains("turn back")
                || msg.contains("face forward") || msg.contains("turn to me") || msg.contains("face front")) {
            s.facingTarget = 0f;
            s.spinUntil = 0;
            setFace(s, "(^o^)", 60);
            return "I'm looking right at you!";
        }
        if (msg.contains("spin") || msg.contains("twirl") || msg.contains("rotate") || msg.contains("do a flip")
                || msg.contains("turn in circles")) {
            s.spinUntil = tick + 26;
            setFace(s, "(^o^)", 40);
            return "Wheee! Watch me spin!";
        }
        String gesture = null;
        String reply = null;
        if (msg.contains("nod") || msg.matches(".*\\bsay yes\\b.*")) {
            gesture = "nod";
            reply = "Yes! *nods*";
        } else if (msg.contains("shake your head") || msg.matches(".*\\bsay no\\b.*")) {
            gesture = "shake";
            reply = "No no no! *shakes head*";
        } else if (msg.contains("tilt") || msg.contains("curious")) {
            gesture = "tilt";
            reply = "Hmm? *tilts head*";
        } else if (msg.contains("look up")) {
            gesture = "up";
            reply = "Looking up!";
        } else if (msg.contains("look down")) {
            gesture = "down";
            reply = "Looking down.";
        }
        if (gesture == null) {
            return null;
        }
        setFace(s, "nod".equals(gesture) ? "(^o^)" : "shake".equals(gesture) ? "(T_T)"
                : "tilt".equals(gesture) ? "(O_O)" : "(-_-)", 60);
        s.gesture = gesture;
        s.gestureUntil = tick + ("up".equals(gesture) || "down".equals(gesture) || "tilt".equals(gesture) ? 60 : 40);
        return reply;
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
        String reply = requestGesture(s, msg);
        if (reply != null) {
            return reply;
        }
        if (msg.contains("circle") || msg.contains("orbit")) {
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
        setFace(s, "(^o^)", 50);
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
        int expression = expressionIndex(s);
        if (expression != s.shownExpression) {
            s.shownExpression = expression;
            s.stand.setHelmet(expressionItem(expression));
        }
    }

    // Each expression is a different 3D face. The resource pack retextures these unobtainable
    // items (build_verity.py keeps the order in sync): happy, joy, sad, angry, surprised, neutral, evil.
    private static final int HAPPY = 0, JOY = 1, SAD = 2, ANGRY = 3, SURPRISED = 4, NEUTRAL = 5, EVIL = 6, BLANK = 7;

    private ItemStack expressionItem(int expression) {
        return expression == HAPPY ? new ItemStack(Material.BARRIER)
                : expression == BLANK ? new ItemStack(Material.MOB_SPAWNER)
                : new ItemStack(Material.MONSTER_EGGS, 1, (short) (expression - 1));
    }

    /** Picks the 3D face from the active expression request, bent by how far Verity has fallen. */
    private int expressionIndex(VerityState s) {
        if (s.phase >= 4 && tick < s.calmUntil) {
            return BLANK;
        }
        String f = s.faceOverride != null && tick < s.faceUntil ? s.faceOverride : "";
        int e;
        if (f.contains(">:D")) {
            e = EVIL;
        } else if (f.contains("O_O") || f.contains("*o*")) {
            e = SURPRISED;
        } else if (f.contains("T_T") || f.contains(">_<")) {
            e = SAD;
        } else if (f.contains("-_-") || f.contains(">_>")) {
            e = NEUTRAL;
        } else if (f.contains("^o^") || f.contains("^_~")) {
            e = JOY;
        } else {
            e = HAPPY;
        }
        if (s.phase >= 4) {
            return e == SURPRISED ? SURPRISED : e == SAD ? ANGRY : EVIL;
        }
        if (s.phase == 3) {
            return e == HAPPY || e == JOY ? NEUTRAL : e == SAD ? ANGRY : e;
        }
        return e;
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
                || e.getScoreboardTags().contains(TAG_DEMON) || e.getScoreboardTags().contains(TAG_DEMON_BODY);
    }

    /** Removes tagged leftovers (e.g. from a crash) that no state is tracking. */
    void cleanupStray(Entity e) {
        if (!isVerityEntity(e)) {
            return;
        }
        for (VerityState s : states.values()) {
            if (s.stand == e || s.demon == e || isDemonPart(s, e)) {
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
