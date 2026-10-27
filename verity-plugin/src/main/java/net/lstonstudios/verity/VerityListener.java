package net.lstonstudios.verity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.command.CommandSender;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.GameMode;
import org.bukkit.Bukkit;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Locale;
import java.util.Random;

final class VerityListener implements Listener {
    private final VerityPlugin plugin;
    private final VerityManager manager;
    private final Random rnd = new Random();
    private static final java.util.regex.Pattern KILL = java.util.regex.Pattern.compile(
            "^/?(?:minecraft:)?kill\\s.*type=(?:minecraft:)?verity(?![\\w:])", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern TYPE = java.util.regex.Pattern.compile(
            "type=(?:minecraft:)?verity(?![\\w:])", java.util.regex.Pattern.CASE_INSENSITIVE);
    private long killUntil;

    VerityListener(VerityPlugin plugin, VerityManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    // ------------------------------------------------------------ the egg

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = e.getItem();
        if (!VerityPlugin.isEgg(item)) {
            return;
        }
        // Never let the underlying item or an old Blaze egg do its vanilla thing.
        e.setUseItemInHand(Event.Result.DENY);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setCancelled(true);
        activateEgg(e.getPlayer(), e.getHand(), item, e.getClickedBlock(), e.getBlockFace());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Player p = e.getPlayer();
        EquipmentSlot hand = e.getHand();
        ItemStack item = hand == EquipmentSlot.OFF_HAND ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();
        Entity target = e.getRightClicked();

        VerityState s = manager.ownerOfStand(target);
        if (s != null || manager.isBox(target)) {
            e.setCancelled(true);
            if (s != null && s.owner.equals(p.getUniqueId()) && hand == EquipmentSlot.HAND) {
                pat(p, s);
            }
            return;
        }
        if (VerityPlugin.isEgg(item)) {
            e.setCancelled(true);
            activateEgg(p, hand, item, null, null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent e) {
        if (VerityPlugin.isEgg(e.getItem())) {
            e.setCancelled(true);
        }
    }

    private void activateEgg(Player p, EquipmentSlot hand, ItemStack item, Block clicked, BlockFace face) {
        if (manager.isActive(p.getUniqueId())) {
            p.sendMessage("§eVerity §8» §fI'm already here, " + p.getName() + ". I always will be. :)");
            return;
        }
        Location where;
        if (clicked != null && face != null) {
            where = clicked.getRelative(face).getLocation().add(0.5, 0, 0.5);
        } else {
            where = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(2));
        }
        if (p.getGameMode() != GameMode.CREATIVE) {
            ItemStack one = item.clone();
            one.setAmount(item.getAmount() - 1);
            ItemStack replacement = one.getAmount() <= 0 ? null : one;
            if (hand == EquipmentSlot.OFF_HAND) {
                p.getInventory().setItemInOffHand(replacement);
            } else {
                p.getInventory().setItemInMainHand(replacement);
            }
        }
        manager.openBox(p, where);
    }

    private void pat(Player p, VerityState s) {
        long now = manager.now();
        if (now - s.lastPat < 100) {
            return;
        }
        s.lastPat = now;
        Location l = s.stand.getLocation().add(0, 0.8, 0);
        switch (s.phase) {
            case 1:
                s.changeKarma(1);
                manager.setFace(s, "(^o^)", 60);
                l.getWorld().spawnParticle(org.bukkit.Particle.HEART, l, 5, 0.3, 0.3, 0.3, 0);
                manager.say(p, s, manager.pick("Hehe, that tickles!", "Aww, thanks " + p.getName() + "!", "You're the best!"));
                break;
            case 2:
                manager.setFace(s, "(^_~)", 60);
                manager.say(p, s, manager.pick("Careful, you might get attached.", "I felt that. Strange.",
                        manager.glitch("Don't stop. Don't stop.")));
                break;
            case 3:
                manager.say(p, s, manager.pick("Don't touch me.", "Stop that.", "Hands off."));
                break;
            default:
                p.damage(1.0);
                manager.say(p, s, "DON'T TOUCH ME.");
                break;
        }
    }

    // --------------------------------------------------- protecting bodies

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        Entity victim = e.getEntity();
        if (!manager.isVerityEntity(victim) || victim.getScoreboardTags().contains(VerityManager.TAG_DEMON)) {
            return;
        }
        if (System.currentTimeMillis() < killUntil && manager.ownerOfStand(victim) != null
                && !(e instanceof EntityDamageByEntityEvent)) {
            return;
        }
        e.setCancelled(true);
        if (e instanceof EntityDamageByEntityEvent) {
            Entity damager = ((EntityDamageByEntityEvent) e).getDamager();
            VerityState s = manager.ownerOfStand(victim);
            if (s != null && damager.getUniqueId().equals(s.owner) && manager.now() - s.lastHit > 20) {
                s.lastHit = manager.now();
                s.changeKarma(-3);
                manager.setFace(s, "(T_T)", 60);
                Player p = (Player) damager;
                if (s.phase <= 2) {
                    manager.say(p, s, manager.pick("Ouch! That wasn't nice.", "Why would you hit me, " + p.getName() + "?"));
                } else {
                    manager.say(p, s, manager.pick("You can't hurt me.", "Try again.", "I'll remember that."));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDemonHurtsOther(EntityDamageByEntityEvent e) {
        VerityState s = manager.ownerOfDemon(e.getDamager());
        if (s != null && !e.getEntity().getUniqueId().equals(s.owner)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDemonTarget(EntityTargetEvent e) {
        VerityState s = manager.ownerOfDemon(e.getEntity());
        if (s != null && e.getTarget() != null && !e.getTarget().getUniqueId().equals(s.owner)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDemonDeath(EntityDeathEvent e) {
        if (e.getEntity().getScoreboardTags().contains(VerityManager.TAG_DEMON)) {
            e.getDrops().clear();
            e.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (manager.isVerityEntity(e.getRightClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (Entity en : e.getChunk().getEntities()) {
            manager.cleanupStray(en);
        }
    }

    // --------------------------------------------------------- the player

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        if (!e.getMessage().toLowerCase(Locale.ROOT).contains("verity")) {
            return;
        }
        final Player p = e.getPlayer();
        final String msg = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                VerityState s = manager.states.get(p.getUniqueId());
                if (s != null && p.isOnline()) {
                    manager.replyLater(p, s, msg);
                }
            }
        });
    }

    /** Makes "/kill @e[type=verity]" work for ops by targeting Verity's stand and Demon via tags. */
    private boolean handleKill(CommandSender sender, String cmd) {
        if (!KILL.matcher(cmd).find()) {
            return false;
        }
        if (!(sender.isOp() || sender.hasPermission("minecraft.command.kill"))) {
            return false;
        }
        killUntil = System.currentTimeMillis() + 2000;
        String body = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        Bukkit.dispatchCommand(sender, TYPE.matcher(body).replaceAll("type=armor_stand,tag=" + VerityManager.TAG_VERITY));
        Bukkit.dispatchCommand(sender, TYPE.matcher(body).replaceAll("type=spider,tag=" + VerityManager.TAG_DEMON));
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                for (VerityState st : new java.util.ArrayList<>(manager.states.values())) {
                    boolean standGone = st.stand == null || !st.stand.isValid();
                    if (st.released && standGone) {
                        manager.remove(st.owner);
                    }
                }
            }
        });
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerKill(ServerCommandEvent e) {
        if (handleKill(e.getSender(), e.getCommand())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerKill(PlayerCommandPreprocessEvent e) {
        if (handleKill(e.getPlayer(), e.getMessage())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        VerityState s = manager.states.get(p.getUniqueId());
        if (s == null || s.phase < 4 || p.isOp()) {
            return;
        }
        String cmd = e.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/verity") || rnd.nextInt(100) >= 12) {
            return;
        }
        e.setCancelled(true);
        manager.say(p, s, manager.pick("NO.", "I DON'T THINK SO.", "YOU DON'T GET TO DO THAT.", "I'M IN CONTROL NOW."));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        final VerityState s = manager.states.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        s.ownerName = p.getName();
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || manager.states.get(p.getUniqueId()) != s) {
                    return;
                }
                switch (s.phase) {
                    case 1:
                        manager.say(p, s, "Welcome back, " + p.getName() + "! I missed you!");
                        break;
                    case 2:
                        manager.say(p, s, "You came back. I knew you would.");
                        break;
                    case 3:
                        manager.say(p, s, "You can leave the game. You can't leave me.");
                        break;
                    default:
                        manager.say(p, s, "WELCOME BACK, " + p.getName().toUpperCase(Locale.ROOT) + ". I NEVER LEFT.");
                        break;
                }
            }
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        VerityState s = manager.states.get(e.getPlayer().getUniqueId());
        if (s != null) {
            manager.removeEntities(s);
            manager.resetEnvironment(e.getPlayer());
            plugin.saveData();
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        final Player p = e.getEntity();
        final VerityState s = manager.states.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (!p.isOnline() || manager.states.get(p.getUniqueId()) != s) {
                    return;
                }
                switch (s.phase) {
                    case 1:
                        manager.say(p, s, "Oh no! Don't worry, " + p.getName() + ", I'll help you get your things back.");
                        break;
                    case 2:
                        manager.say(p, s, "That looked like it hurt. Didn't it?");
                        break;
                    case 3:
                        manager.say(p, s, "You always die. I'm starting to find it funny.");
                        break;
                    default:
                        manager.say(p, s, "DEATH WON'T SAVE YOU.");
                        break;
                }
            }
        }, 40L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        VerityState s = manager.states.get(p.getUniqueId());
        if (s == null || s.phase != 1 || e.getBlock().getType() != Material.DIAMOND_ORE) {
            return;
        }
        manager.setFace(s, "(*o*)", 80);
        manager.say(p, s, "Shiny! You found a diamond, " + p.getName() + "!");
    }
}
