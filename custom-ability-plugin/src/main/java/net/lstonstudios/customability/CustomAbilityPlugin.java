package net.lstonstudios.customability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Vector;

public final class CustomAbilityPlugin extends JavaPlugin implements Listener {

    private static final String MARKER_PREFIX = "CustomAbility:";
    private static final String LEGACY_MARKER_PREFIX = ChatColor.BLACK + "CustomAbility:";

    private enum Ability {
        PYROCLASTIC_SMASH("pyroclastic", Material.MAGMA_CREAM, ChatColor.RED, "Pyroclastic Smash",
                "Fire wave: 3 hearts of damage and 4s of burning.", 8),
        VOID_TETHER("void", Material.NETHER_STAR, ChatColor.DARK_PURPLE, "Void Tether",
                "Hit a player to pull them 5 blocks closer and slow them (Slowness II, 3s).", 12),
        REAPERS_HARVEST("reaper", Material.FLINT, ChatColor.DARK_RED, "Reaper's Harvest",
                "Strength II for 6s, but Hunger III while it lasts.", 25),
        SONIC_BOOM("sonic", Material.PRISMARINE_SHARD, ChatColor.AQUA, "Sonic Boom",
                "Knocks back everything within 4 blocks by 5 blocks and inflicts Nausea (5s).", 10),
        QUANTUM_SHIFT("quantum", Material.CHORUS_FRUIT, ChatColor.LIGHT_PURPLE, "Quantum Shift",
                "Teleports you 6 blocks forward, even through thin walls.", 8),
        CHITINOUS_SHIELD("chitinous", Material.IRON_BARDING, ChatColor.GRAY, "Chitinous Shield",
                "Resistance III and Slowness I for 5s.", 20),
        OVERCLOCK("overclock", Material.BLAZE_POWDER, ChatColor.GOLD, "Overclock",
                "Speed III and Haste II for 10s.", 25),
        AEGIS_BURST("aegis", Material.GHAST_TEAR, ChatColor.YELLOW, "Aegis Burst",
                "Clears negative effects and grants 2 absorption hearts.", 30),
        GALE_FORCE("gale", Material.FEATHER, ChatColor.WHITE, "Gale Force",
                "Launches you 10 blocks up with fall damage immunity for 4s.", 15),
        SHADOW_STEP("shadow", Material.FERMENTED_SPIDER_EYE, ChatColor.DARK_GRAY, "Shadow Step",
                "Invisible, with armor and held items hidden, for 4s.", 20),
        TAILWIND("tailwind", Material.RABBIT_FOOT, ChatColor.GREEN, "Tailwind",
                "Speed II for 8s for you and teammates within 8 blocks.", 20);

        final String key;
        final Material material;
        final ChatColor color;
        final String name;
        final String description;
        final int cooldownSeconds;

        Ability(String key, Material material, ChatColor color, String name, String description, int cooldownSeconds) {
            this.key = key;
            this.material = material;
            this.color = color;
            this.name = name;
            this.description = description;
            this.cooldownSeconds = cooldownSeconds;
        }

        static Ability fromKey(String key) {
            for (Ability a : values()) {
                if (a.key.equalsIgnoreCase(key)) {
                    return a;
                }
            }
            return null;
        }
    }

    private final Map<UUID, Map<Ability, Long>> cooldowns = new HashMap<UUID, Map<Ability, Long>>();
    private final Map<UUID, Long> fallImmunity = new HashMap<UUID, Long>();
    private final Map<UUID, ItemStack[]> hiddenArmor = new HashMap<UUID, ItemStack[]>();
    private final Map<UUID, ItemStack[]> hiddenHands = new HashMap<UUID, ItemStack[]>();

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        for (org.bukkit.entity.Player online : getServer().getOnlinePlayers()) {
            refreshItems(online);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreGear(p);
        }
    }

    // ---------- items & command ----------

    private void refreshItems(org.bukkit.entity.Player player) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack old = inv.getItem(i);
            if (old == null) {
                continue;
            }
            Ability a = getAbility(old);
            if (a != null) {
                inv.setItem(i, createItem(a, old.getAmount()));
            }
        }
    }

    @EventHandler
    public void onJoinRefresh(org.bukkit.event.player.PlayerJoinEvent event) {
        refreshItems(event.getPlayer());
    }

    private ItemStack createItem(Ability a, int amount) {
        ItemStack item = new ItemStack(a.material, amount);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(a.color + "" + ChatColor.BOLD + "\u2726 " + a.name + " \u2726");
        List<String> lore = new ArrayList<String>();
        for (String line : wrap(a.description, 14)) {
            lore.add(ChatColor.GRAY + line);
        }
        lore.add(ChatColor.DARK_GRAY + "Right-click to");
        lore.add(ChatColor.DARK_GRAY + "use. Cooldown:");
        lore.add(ChatColor.DARK_GRAY + "" + a.cooldownSeconds + "s" + hide(MARKER_PREFIX + a.key));
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        item.setItemMeta(meta);
        return item;
    }

    private static String hide(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            sb.append('\u00a7').append(c);
        }
        return sb.toString();
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.split(" ")) {
            if (cur.length() > 0 && cur.length() + 1 + word.length() > width) {
                lines.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(word);
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }

    private Ability getAbility(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return null;
        }
        for (String line : item.getItemMeta().getLore()) {
            if (line.startsWith(LEGACY_MARKER_PREFIX)) {
                return Ability.fromKey(line.substring(LEGACY_MARKER_PREFIX.length()));
            }
            int idx = line.indexOf(hide(MARKER_PREFIX));
            if (idx >= 0) {
                return Ability.fromKey(line.substring(idx + hide(MARKER_PREFIX).length()).replace("\u00a7", ""));
            }
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("customability.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player|@a|@s|@p> <ability|all> [amount]");
            sender.sendMessage(ChatColor.GRAY + "Abilities: " + abilityKeys());
            return true;
        }
        boolean all = args[1].equalsIgnoreCase("all");
        Ability ability = all ? null : Ability.fromKey(args[1]);
        if (!all && ability == null) {
            sender.sendMessage(ChatColor.RED + "Unknown ability. Choose: " + abilityKeys() + ", all");
            return true;
        }
        int amount = 1;
        if (args.length > 2) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[2])));
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Amount must be a number.");
                return true;
            }
        }
        List<Player> targets = resolveTargets(sender, args[0]);
        if (targets.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No matching players found.");
            return true;
        }
        for (Player target : targets) {
            for (Ability a : all ? Ability.values() : new Ability[] { ability }) {
                for (ItemStack leftover : target.getInventory().addItem(createItem(a, amount)).values()) {
                    target.getWorld().dropItemNaturally(target.getLocation(), leftover);
                }
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Gave " + (all ? "all abilities" : ability.name) + " to " + targets.size() + " player(s).");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<String>();
        if (args.length == 1) {
            out.addAll(Arrays.asList("@a", "@s", "@p"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        } else if (args.length == 2) {
            for (Ability a : Ability.values()) {
                out.add(a.key);
            }
            out.add("all");
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase();
        List<String> filtered = new ArrayList<String>();
        for (String s : out) {
            if (s.toLowerCase().startsWith(prefix)) {
                filtered.add(s);
            }
        }
        return filtered;
    }

    private String abilityKeys() {
        StringBuilder sb = new StringBuilder();
        for (Ability a : Ability.values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(a.key);
        }
        return sb.toString();
    }

    private List<Player> resolveTargets(CommandSender sender, String arg) {
        List<Player> list = new ArrayList<Player>();
        if (arg.equals("@a")) {
            list.addAll(Bukkit.getOnlinePlayers());
        } else if (arg.equals("@s") || arg.equals("@p")) {
            if (sender instanceof Player) {
                list.add((Player) sender);
            }
        } else {
            Player p = Bukkit.getPlayerExact(arg);
            if (p != null) {
                list.add(p);
            }
        }
        return list;
    }

    // ---------- activation ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        Ability ability = getAbility(event.getItem());
        if (ability == null) {
            return;
        }
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setCancelled(true);

        Player player = event.getPlayer();
        Map<Ability, Long> map = cooldowns.get(player.getUniqueId());
        if (map == null) {
            map = new HashMap<Ability, Long>();
            cooldowns.put(player.getUniqueId(), map);
        }
        long now = System.currentTimeMillis();
        Long ready = map.get(ability);
        if (ready != null && ready > now) {
            player.sendMessage(ChatColor.RED + ability.name + " is on cooldown for " + ((ready - now + 999) / 1000) + "s.");
            return;
        }
        if (activate(player, ability)) {
            map.put(ability, now + ability.cooldownSeconds * 1000L);
        }
    }

    private boolean activate(Player p, Ability a) {
        switch (a) {
            case PYROCLASTIC_SMASH:
                pyroclasticSmash(p);
                return true;
            case VOID_TETHER:
                voidTether(p);
                return true;
            case REAPERS_HARVEST:
                p.addPotionEffect(new PotionEffect(PotionEffectType.INCREASE_DAMAGE, 120, 1), true);
                p.addPotionEffect(new PotionEffect(PotionEffectType.HUNGER, 120, 2), true);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 1f, 1.5f);
                p.getWorld().spawnParticle(Particle.SMOKE_LARGE, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.02);
                return true;
            case SONIC_BOOM:
                sonicBoom(p);
                return true;
            case QUANTUM_SHIFT:
                return quantumShift(p);
            case CHITINOUS_SHIELD:
                p.addPotionEffect(new PotionEffect(PotionEffectType.DAMAGE_RESISTANCE, 100, 2), true);
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 100, 0), true);
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.8f);
                p.getWorld().spawnParticle(Particle.CRIT_MAGIC, p.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.1);
                return true;
            case OVERCLOCK:
                p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 2), true);
                p.addPotionEffect(new PotionEffect(PotionEffectType.FAST_DIGGING, 200, 1), true);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FIREWORK_LAUNCH, 1f, 1.4f);
                p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0), 25, 0.3, 0.1, 0.3, 0.05);
                return true;
            case AEGIS_BURST:
                for (PotionEffect e : p.getActivePotionEffects()) {
                    if (isNegative(e.getType())) {
                        p.removePotionEffect(e.getType());
                    }
                }
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 1200, 0), true);
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.8f);
                p.getWorld().spawnParticle(Particle.VILLAGER_HAPPY, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0);
                return true;
            case GALE_FORCE:
                p.setVelocity(new Vector(0, 1.36, 0));
                fallImmunity.put(p.getUniqueId(), System.currentTimeMillis() + 4000L);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERDRAGON_FLAP, 1f, 1.2f);
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 40, 0.4, 0.1, 0.4, 0.1);
                return true;
            case SHADOW_STEP:
                shadowStep(p);
                return true;
            case TAILWIND:
                tailwind(p);
                return true;
            default:
                return false;
        }
    }

    private boolean isNegative(PotionEffectType t) {
        return t.equals(PotionEffectType.POISON) || t.equals(PotionEffectType.WITHER)
                || t.equals(PotionEffectType.SLOW) || t.equals(PotionEffectType.SLOW_DIGGING)
                || t.equals(PotionEffectType.WEAKNESS) || t.equals(PotionEffectType.CONFUSION)
                || t.equals(PotionEffectType.BLINDNESS) || t.equals(PotionEffectType.HUNGER)
                || t.equals(PotionEffectType.HARM) || t.equals(PotionEffectType.LEVITATION)
                || t.equals(PotionEffectType.UNLUCK);
    }

    // ---------- abilities ----------

    private void pyroclasticSmash(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        p.getWorld().playSound(eye, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f);
        List<Entity> hit = new ArrayList<Entity>();
        for (double d = 1; d <= 12; d += 0.5) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (point.getBlock().getType().isSolid()) {
                break;
            }
            p.getWorld().spawnParticle(Particle.FLAME, point, 8, 0.4, 0.4, 0.4, 0.02);
            p.getWorld().spawnParticle(Particle.LAVA, point, 1, 0.3, 0.3, 0.3, 0);
            for (Entity e : p.getWorld().getNearbyEntities(point, 1.2, 1.2, 1.2)) {
                if (e instanceof LivingEntity && e != p && !hit.contains(e)) {
                    hit.add(e);
                    ((LivingEntity) e).damage(6.0, p);
                    e.setFireTicks(80);
                }
            }
        }
    }

    private void voidTether(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        p.getWorld().playSound(eye, Sound.ENTITY_ENDERPEARL_THROW, 1f, 0.7f);
        for (double d = 1; d <= 25; d += 0.5) {
            Location point = eye.clone().add(dir.clone().multiply(d));
            if (point.getBlock().getType().isSolid()) {
                break;
            }
            p.getWorld().spawnParticle(Particle.PORTAL, point, 6, 0.15, 0.15, 0.15, 0.3);
            for (Entity e : p.getWorld().getNearbyEntities(point, 1, 1, 1)) {
                if (e instanceof Player && e != p) {
                    Player target = (Player) e;
                    Vector toUser = p.getLocation().toVector().subtract(target.getLocation().toVector());
                    double dist = toUser.length();
                    if (dist > 0.01) {
                        Location dest = target.getLocation().add(toUser.normalize().multiply(Math.min(5.0, Math.max(0, dist - 1.5))));
                        if (isPassable(dest) && isPassable(dest.clone().add(0, 1, 0))) {
                            target.teleport(dest);
                        }
                    }
                    target.addPotionEffect(new PotionEffect(PotionEffectType.SLOW, 60, 1), true);
                    p.getWorld().playSound(target.getLocation(), Sound.ENTITY_ENDERMEN_TELEPORT, 1f, 0.8f);
                    return;
                }
            }
        }
    }

    private void sonicBoom(Player p) {
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.5f);
        p.getWorld().spawnParticle(Particle.EXPLOSION_LARGE, p.getLocation().add(0, 1, 0), 1);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 60, 2, 0.3, 2, 0.15);
        for (Entity e : p.getNearbyEntities(4, 4, 4)) {
            if (e.getLocation().distanceSquared(p.getLocation()) > 16) {
                continue;
            }
            Vector away = e.getLocation().toVector().subtract(p.getLocation().toVector()).setY(0);
            away = away.lengthSquared() < 0.0001 ? new Vector(1, 0, 0) : away.normalize();
            e.setVelocity(away.multiply(1.5).setY(0.4));
            if (e instanceof LivingEntity) {
                ((LivingEntity) e).addPotionEffect(new PotionEffect(PotionEffectType.CONFUSION, 100, 0), true);
            }
        }
    }

    private boolean quantumShift(Player p) {
        Location start = p.getLocation();
        Vector dir = start.getDirection().normalize();
        for (double d = 6; d >= 1; d -= 0.5) {
            Location dest = start.clone().add(dir.clone().multiply(d));
            if (isPassable(dest) && isPassable(dest.clone().add(0, 1, 0))) {
                p.getWorld().spawnParticle(Particle.PORTAL, start.clone().add(0, 1, 0), 50, 0.3, 0.6, 0.3, 0.5);
                p.teleport(dest);
                p.getWorld().spawnParticle(Particle.PORTAL, dest.clone().add(0, 1, 0), 50, 0.3, 0.6, 0.3, 0.5);
                p.getWorld().playSound(dest, Sound.ENTITY_ENDERMEN_TELEPORT, 1f, 1.2f);
                return true;
            }
        }
        p.sendMessage(ChatColor.RED + "No room to shift there.");
        return false;
    }

    private boolean isPassable(Location loc) {
        return !loc.getBlock().getType().isSolid();
    }

    // Spigot 1.12 has no per-viewer equipment API, so gear is stashed for the duration.
    private void shadowStep(Player p) {
        restoreGear(p);
        PlayerInventory inv = p.getInventory();
        hiddenArmor.put(p.getUniqueId(), inv.getArmorContents());
        hiddenHands.put(p.getUniqueId(), new ItemStack[] { inv.getItemInMainHand(), inv.getItemInOffHand() });
        inv.setArmorContents(new ItemStack[4]);
        inv.setItemInMainHand(null);
        inv.setItemInOffHand(null);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 80, 0, false, false), true);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMEN_TELEPORT, 0.6f, 0.5f);
        p.getWorld().spawnParticle(Particle.SMOKE_LARGE, p.getLocation().add(0, 1, 0), 30, 0.4, 0.8, 0.4, 0.02);
        final UUID id = p.getUniqueId();
        new BukkitRunnable() {
            @Override
            public void run() {
                Player online = Bukkit.getPlayer(id);
                if (online != null) {
                    restoreGear(online);
                }
            }
        }.runTaskLater(this, 80L);
    }

    private void restoreGear(Player p) {
        ItemStack[] armor = hiddenArmor.remove(p.getUniqueId());
        ItemStack[] hands = hiddenHands.remove(p.getUniqueId());
        if (armor == null || hands == null) {
            return;
        }
        PlayerInventory inv = p.getInventory();
        inv.setArmorContents(armor);
        inv.setItemInMainHand(hands[0]);
        inv.setItemInOffHand(hands[1]);
    }

    private void tailwind(Player p) {
        Team team = p.getScoreboard().getEntryTeam(p.getName());
        PotionEffect speed = new PotionEffect(PotionEffectType.SPEED, 160, 1);
        p.addPotionEffect(speed, true);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERDRAGON_FLAP, 1f, 1.6f);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
        if (team == null) {
            return;
        }
        for (Entity e : p.getNearbyEntities(8, 8, 8)) {
            if (e instanceof Player && e.getLocation().distanceSquared(p.getLocation()) <= 64
                    && team.hasEntry(((Player) e).getName())) {
                ((Player) e).addPotionEffect(speed, true);
                e.getWorld().spawnParticle(Particle.CLOUD, e.getLocation().add(0, 1, 0), 20, 0.4, 0.5, 0.4, 0.05);
            }
        }
    }

    // ---------- protection / cleanup ----------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player)) {
            return;
        }
        Long until = fallImmunity.get(event.getEntity().getUniqueId());
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                event.setCancelled(true);
            } else {
                fallImmunity.remove(event.getEntity().getUniqueId());
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (hiddenArmor.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (hiddenArmor.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        ItemStack[] armor = hiddenArmor.remove(p.getUniqueId());
        ItemStack[] hands = hiddenHands.remove(p.getUniqueId());
        if (armor == null || hands == null) {
            return;
        }
        if (event.getKeepInventory()) {
            PlayerInventory inv = p.getInventory();
            inv.setArmorContents(armor);
            inv.setItemInMainHand(hands[0]);
            inv.setItemInOffHand(hands[1]);
            return;
        }
        for (ItemStack[] group : new ItemStack[][] { armor, hands }) {
            for (ItemStack i : group) {
                if (i != null && i.getType() != Material.AIR) {
                    event.getDrops().add(i);
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        restoreGear(event.getPlayer());
        cooldowns.remove(event.getPlayer().getUniqueId());
        fallImmunity.remove(event.getPlayer().getUniqueId());
    }
}
