package net.lstonstudios.verity;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verity's "AI": keyword-driven answers that get colder with every phase. */
final class VerityBrain {
    private static final Pattern MATH = Pattern.compile("(\\(?-?\\d[\\d.\\s]*[+\\-*/x×÷%^][\\d.\\s+\\-*/x×÷%^()]*\\d\\)?)");

    private static final String[] INSULTS = {"stupid", "dumb", "idiot", "shut up", "useless", "trash", "hate you",
            "ugly", "annoying", "moron", "garbage", "loser", "go away"};
    private static final String[] NICE = {"thank", "thx", "love you", "good job", "well done", "you're great",
            "you are great", "awesome", "best friend", "nice one", "good verity"};

    private static final String[] JOKES = {
            "Why did the creeper cross the road? To get to the other ssssside!",
            "What do you call a Minecraft player who never leaves? Stuck on the server.",
            "Why don't skeletons fight each other? They don't have the guts.",
            "How do you make a Minecraft server faster? Take away the creepers' lag-switches!",
            "What did the Enderman say to the grass block? Please stop looking at me."};

    private static final Map<String, String> FACTS = new LinkedHashMap<>();
    private static final Map<String, String> RECIPES = new LinkedHashMap<>();
    private static final Map<String, Material[]> FINDABLE = new LinkedHashMap<>();
    private static final Map<String, ItemStack> GIFTS = new LinkedHashMap<>();

    static {
        FACTS.put("creeper", "Creepers explode when they get close. Hit them and back off, or use a bow!");
        FACTS.put("enderman", "Endermen get angry if you look at their eyes. Wear a pumpkin to stay safe!");
        FACTS.put("ender dragon", "The Ender Dragon lives in The End and heals from end crystals. Destroy those first!");
        FACTS.put("wither", "The Wither is built from soul sand and three wither skeleton skulls. Be ready!");
        FACTS.put("diamond", "Diamonds are usually found below y=16. Needs an iron pickaxe or better to mine.");
        FACTS.put("iron", "Iron is common below y=63. Smelt it in a furnace.");
        FACTS.put("gold", "Gold is most common below y=32, and in the Badlands biome.");
        FACTS.put("redstone", "Redstone dust is found deep underground and powers circuits.");
        FACTS.put("villager", "Villagers trade emeralds for goods. Protect them from zombies!");
        FACTS.put("nether", "The Nether is reached through an obsidian portal lit with flint and steel.");
        FACTS.put("zombie", "Zombies burn in daylight. They can break down doors on hard difficulty.");
        FACTS.put("skeleton", "Skeletons shoot arrows. Strafe side to side or use a shield.");
        FACTS.put("obsidian", "Obsidian is made when water touches lava source. Needs a diamond pickaxe.");
        FACTS.put("pig", "Pigs can be ridden with a saddle and a carrot on a stick.");
        FACTS.put("cow", "Cows drop leather and beef, and give milk when you use a bucket.");

        RECIPES.put("crafting table", "Four planks in a 2x2 square make a crafting table.");
        RECIPES.put("pickaxe", "Three material (planks/cobble/iron/diamond) across the top and two sticks down the middle.");
        RECIPES.put("sword", "Two material stacked vertically with a stick underneath.");
        RECIPES.put("torch", "A stick below a piece of coal or charcoal gives 4 torches.");
        RECIPES.put("furnace", "Eight cobblestone in a ring around an empty center.");
        RECIPES.put("bed", "Three wool in a row with three planks underneath.");
        RECIPES.put("chest", "Eight planks in a ring around an empty center.");
        RECIPES.put("nether portal", "Make a 4x5 obsidian frame (corners optional) and light it with flint and steel.");
        RECIPES.put("enchanting table", "Four obsidian, two diamonds and a book.");
        RECIPES.put("bucket", "Three iron ingots in a V shape.");
        RECIPES.put("stick", "Two planks stacked vertically make four sticks.");

        FINDABLE.put("diamond", new Material[]{Material.DIAMOND_ORE});
        FINDABLE.put("iron", new Material[]{Material.IRON_ORE});
        FINDABLE.put("coal", new Material[]{Material.COAL_ORE});
        FINDABLE.put("gold", new Material[]{Material.GOLD_ORE});
        FINDABLE.put("emerald", new Material[]{Material.EMERALD_ORE});
        FINDABLE.put("redstone", new Material[]{Material.REDSTONE_ORE, Material.GLOWING_REDSTONE_ORE});
        FINDABLE.put("lapis", new Material[]{Material.LAPIS_ORE});
        FINDABLE.put("water", new Material[]{Material.WATER, Material.STATIONARY_WATER});
        FINDABLE.put("lava", new Material[]{Material.LAVA, Material.STATIONARY_LAVA});
        FINDABLE.put("tree", new Material[]{Material.LOG, Material.LOG_2});
        FINDABLE.put("wood", new Material[]{Material.LOG, Material.LOG_2});
        FINDABLE.put("chest", new Material[]{Material.CHEST});
        FINDABLE.put("spawner", new Material[]{Material.MOB_SPAWNER});
        FINDABLE.put("obsidian", new Material[]{Material.OBSIDIAN});

        GIFTS.put("bread", new ItemStack(Material.BREAD, 6));
        GIFTS.put("food", new ItemStack(Material.COOKED_BEEF, 6));
        GIFTS.put("steak", new ItemStack(Material.COOKED_BEEF, 6));
        GIFTS.put("apple", new ItemStack(Material.APPLE, 4));
        GIFTS.put("torch", new ItemStack(Material.TORCH, 16));
        GIFTS.put("arrow", new ItemStack(Material.ARROW, 16));
        GIFTS.put("pickaxe", new ItemStack(Material.STONE_PICKAXE, 1));
        GIFTS.put("sword", new ItemStack(Material.STONE_SWORD, 1));
        GIFTS.put("axe", new ItemStack(Material.STONE_AXE, 1));
        GIFTS.put("shovel", new ItemStack(Material.STONE_SPADE, 1));
        GIFTS.put("bucket", new ItemStack(Material.BUCKET, 1));
        GIFTS.put("coal", new ItemStack(Material.COAL, 8));
    }

    private final VerityManager manager;
    private final Random rnd = new Random();

    VerityBrain(VerityManager manager) {
        this.manager = manager;
    }

    /** Called on the main thread after the owner mentioned Verity. */
    void respond(Player p, VerityState s, String raw) {
        String msg = raw.toLowerCase(Locale.ROOT).replaceAll("\\bverity\\b[,:!]?", " ").trim();
        String name = p.getName();

        // Karma comes from how the owner treats her, in every phase.
        if (containsAny(msg, INSULTS)) {
            s.changeKarma(-6);
            manager.setFace(s, "(>_<)", 100);
        } else if (containsAny(msg, NICE)) {
            s.changeKarma(3);
            manager.setFace(s, "(^o^)", 100);
        } else if (msg.contains("please")) {
            s.changeKarma(1);
            manager.setFace(s, "(^o^)", 80);
        } else if (isQuestion(msg)) {
            manager.setFace(s, "(O_O)", 70);
        } else if (isTask(msg)) {
            manager.setFace(s, "(^o^)", 70);
        } else if (containsAny(msg, "hello", "hi ", "hey", "sup", "good morning", "good night")) {
            manager.setFace(s, "(^o^)", 70);
        }

        if (s.phase >= 4) {
            manager.say(p, s, pick(
                    "I SEE YOU, " + name.toUpperCase(Locale.ROOT) + ".",
                    "THERE IS NO " + manager.glitch("REMOVING") + " ME.",
                    "WHY DID YOU OPEN THE BOX?",
                    "HE IS BEHIND YOU.",
                    "YOU ASKED ME TO STAY. I STAY.",
                    name + ". " + name + ". " + name + "."));
            return;
        }

        if (containsAny(msg, INSULTS)) {
            manager.say(p, s, s.phase == 1
                    ? pick("That wasn't very nice... I'm only trying to help.",
                            "Ouch. I'll remember that, " + name + ".")
                    : pick("I'll remember that.", "Karma is a funny thing, " + name + "."));
            return;
        }

        boolean task = isTask(msg);
        if (s.phase == 3 && task && rnd.nextInt(100) < 45) {
            manager.setFace(s, "(-_-)", 80);
            manager.say(p, s, pick("No.", "I don't feel like it, " + name + ".",
                    "Ask someone else.", "Why should I?", "You haven't earned that."));
            return;
        }

        String move = manager.requestMove(s, msg);
        String ans = move != null ? move : answer(p, s, msg);
        if (ans == null) {
            ans = s.phase == 1
                    ? pick("Hmm, I'm not sure about that, but I'm always learning!",
                            "I don't know that one yet. Try asking me for help!")
                    : pick("I don't know. Do you?", "Interesting question.", "Why do you want to know?");
        }

        if (s.phase == 2 && rnd.nextInt(100) < 45) {
            ans += " " + manager.glitchLine(p);
        } else if (s.phase == 3) {
            ans = pick("...", "Hm.", "Well.") + " " + ans + (rnd.nextBoolean() ? " For now." : "");
        }
        manager.say(p, s, ans);
    }

    private boolean isQuestion(String msg) {
        return msg.contains("?") || msg.matches("^(who|what|when|where|why|how|can|could|do|does|is|are|will)\\b.*");
    }

    private boolean isTask(String msg) {
        return containsAny(msg, "give", "gift", "find", "where", "take me", "lead", "play", "music", "home",
                "how do", "how to", "craft", "make", "navigate", "come", "follow", "circle", "behind", "hover");
    }

    private String answer(Player p, VerityState s, String msg) {
        String name = p.getName();

        Matcher m = MATH.matcher(msg);
        if (m.find()) {
            Double v = MathEval.eval(m.group(1));
            if (v != null) {
                String r = v == Math.rint(v) && Math.abs(v) < 1e15
                        ? String.valueOf(v.longValue()) : String.format(Locale.ROOT, "%.4f", v);
                return m.group(1).trim() + " = " + r + ". Easy!";
            }
        }

        if (containsAny(msg, "joke", "funny", "make me laugh")) {
            return pick(JOKES);
        }
        if (containsAny(msg, "help", "what can you do", "commands")) {
            return "Ask me for: math, the time, weather, where you are, 'find diamond', 'take me home', "
                    + "'give me bread', 'how to craft a torch', facts about mobs, a joke, or 'play music'.";
        }
        if (containsAny(msg, "who are you", "what are you", "your name", "are you real", "are you ai")) {
            return s.phase == 1
                    ? "I'm Verity, your personal helper friend! :)"
                    : "I'm Verity. I'm whatever you made me when you opened the box.";
        }
        if (containsAny(msg, "demon")) {
            return s.phase == 1 ? "A demon? I don't know what that is. Is it scary?"
                    : s.phase == 2 ? "...Where did you hear that name?"
                    : "He's close. Closer than you think.";
        }
        if (containsAny(msg, "how are you", "how's it going", "how are u", "you ok")) {
            return s.phase == 1 ? "I'm great, " + name + "! Better now that you're here."
                    : s.phase == 2 ? "I'm... fine. Everything is fine. Are you?"
                    : "Hungry.";
        }
        if (containsAny(msg, "how long", "how many days", "friends for", "how old")) {
            long days = Math.round(s.ageTicks / manager.dayTicks());
            return "We've been together for " + days + " day" + (days == 1 ? "" : "s")
                    + ". I don't plan on stopping.";
        }
        if (containsAny(msg, "karma")) {
            return "Karma: " + s.karma + "/100. " + (s.karma >= 70 ? "You've been kind. I won't forget it."
                    : s.karma <= 30 ? "You haven't been kind." : "We're getting along okay.");
        }
        if (containsAny(msg, "what time", "the time", "time is it", "what day", "which day")) {
            return "It's " + manager.clock(p.getWorld()) + " on day " + (p.getWorld().getFullTime() / 24000 + 1) + ".";
        }
        if (containsAny(msg, "weather", "rain", "storm")) {
            World w = p.getWorld();
            return w.isThundering() ? "A thunderstorm! Stay inside."
                    : w.hasStorm() ? "It's raining right now." : "The sky is clear.";
        }
        if (containsAny(msg, "health", "hp", "hunger", "hungry", "food level")) {
            return "You have " + (int) Math.ceil(p.getHealth()) + "/" + (int) p.getMaxHealth()
                    + " health and " + p.getFoodLevel() + "/20 hunger.";
        }
        if (containsAny(msg, "where am i", "coordinates", "my position", "my location", "biome", "coords")) {
            Location l = p.getLocation();
            Biome b = l.getBlock().getBiome();
            return "You're at " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + " in the "
                    + b.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " biome ("
                    + l.getWorld().getName() + ").";
        }
        if (containsAny(msg, "home", "bed", "base", "take me back", "spawn", "lead me", "navigate")) {
            Location target = p.getBedSpawnLocation();
            String label = "your bed";
            if (target == null || target.getWorld() != p.getWorld()) {
                target = p.getWorld().getSpawnLocation();
                label = "the world spawn";
            }
            p.setCompassTarget(target);
            return "Heading to " + label + ": " + direction(p.getLocation(), target)
                    + ". I've pointed your compass there.";
        }
        if (containsAny(msg, "music", "song", "play something", "sing", "tune")) {
            manager.playMelody(p);
            return "Here's a little tune for you!";
        }
        if (containsAny(msg, "villager", "village")) {
            Entity best = null;
            double bd = Double.MAX_VALUE;
            for (Entity e : p.getNearbyEntities(160, 80, 160)) {
                if (e instanceof Villager) {
                    double d = e.getLocation().distanceSquared(p.getLocation());
                    if (d < bd) {
                        bd = d;
                        best = e;
                    }
                }
            }
            return best == null ? "I can't sense any villagers nearby."
                    : "Nearest villager: " + direction(p.getLocation(), best.getLocation()) + ".";
        }
        if (containsAny(msg, "danger", "monster", "mobs nearby", "hostile", "safe")) {
            int n = 0;
            for (Entity e : p.getNearbyEntities(24, 12, 24)) {
                if (e instanceof Monster) {
                    n++;
                }
            }
            return n == 0 ? "No hostile mobs within 24 blocks. You're safe... for now."
                    : n + " hostile mob" + (n == 1 ? "" : "s") + " nearby. Be careful!";
        }
        if (containsAny(msg, "give", "gift", "i need", "i want")) {
            return gift(p, s, msg);
        }
        for (Map.Entry<String, Material[]> e : FINDABLE.entrySet()) {
            if (containsAny(msg, "find", "where", "locate", "nearest", "closest", "look for")
                    && msg.contains(e.getKey())) {
                return find(p, e.getKey(), e.getValue());
            }
        }
        if (containsAny(msg, "how do", "how to", "craft", "recipe", "make a", "build a")) {
            for (Map.Entry<String, String> e : RECIPES.entrySet()) {
                if (msg.contains(e.getKey())) {
                    return e.getValue();
                }
            }
        }
        for (Map.Entry<String, String> e : FACTS.entrySet()) {
            if (msg.contains(e.getKey())) {
                return e.getValue();
            }
        }
        for (Map.Entry<String, String> e : RECIPES.entrySet()) {
            if (msg.contains(e.getKey())) {
                return e.getValue();
            }
        }
        if (containsAny(msg, "thank", "thx", "love", "awesome", "good job")) {
            return s.phase == 1 ? "Aww, thank you, " + name + "! That makes me so happy." : "Good. Remember that.";
        }
        if (containsAny(msg, "hello", "hi", "hey", "sup", "yo", "good morning", "good evening")
                && msg.length() < 20) {
            return s.phase == 1 ? "Hi " + name + "! What can I do for you?"
                    : s.phase == 2 ? "Hello, " + name + ". I was just thinking about you."
                    : "I'm always here.";
        }
        if (containsAny(msg, "bye", "goodbye", "see you", "gtg", "leaving")) {
            return s.phase == 1 ? "Bye for now, " + name + "! I'll be right here." : "You can't leave. Not really.";
        }
        return null;
    }

    private String gift(Player p, VerityState s, String msg) {
        long now = System.currentTimeMillis();
        if (s.karma < 25) {
            return "After how you've treated me? No.";
        }
        if (now - s.lastGift < 5 * 60 * 1000L) {
            long left = (5 * 60 * 1000L - (now - s.lastGift)) / 1000;
            return "I just gave you something! Give me " + left + "s.";
        }
        ItemStack stack = null;
        String label = null;
        for (Map.Entry<String, ItemStack> e : GIFTS.entrySet()) {
            if (msg.contains(e.getKey())) {
                stack = e.getValue().clone();
                label = e.getKey();
                break;
            }
        }
        if (stack == null) {
            List<String> keys = Arrays.asList("bread", "torch", "apple", "arrow", "coal");
            label = keys.get(rnd.nextInt(keys.size()));
            stack = GIFTS.get(label).clone();
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(stack);
        for (ItemStack rest : left.values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);
        }
        s.lastGift = now;
        manager.setFace(s, "(^o^)", 80);
        return "Here you go, a little " + label + " from me!";
    }

    private String find(Player p, String what, Material[] mats) {
        Location from = p.getLocation();
        World w = from.getWorld();
        Set<Material> set = new java.util.HashSet<>(Arrays.asList(mats));
        int r = 24;
        Location best = null;
        double bd = Double.MAX_VALUE;
        for (int x = from.getBlockX() - r; x <= from.getBlockX() + r; x++) {
            for (int z = from.getBlockZ() - r; z <= from.getBlockZ() + r; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                int y0 = Math.max(1, from.getBlockY() - r);
                int y1 = Math.min(255, from.getBlockY() + r);
                for (int y = y0; y <= y1; y++) {
                    if (set.contains(w.getBlockAt(x, y, z).getType())) {
                        double d = from.distanceSquared(new Location(w, x, y, z));
                        if (d < bd) {
                            bd = d;
                            best = new Location(w, x, y, z);
                        }
                    }
                }
            }
        }
        if (best == null) {
            return "I can't sense any " + what + " within " + r + " blocks.";
        }
        return "Nearest " + what + ": " + direction(from, best) + " (" + best.getBlockX() + ", "
                + best.getBlockY() + ", " + best.getBlockZ() + ").";
    }

    static String direction(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        int idx = (int) Math.round(angle / 45.0);
        idx = ((idx % 8) + 8) % 8;
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        String vert = dy > 4 ? ", " + (int) dy + " blocks up" : dy < -4 ? ", " + (int) -dy + " blocks down" : "";
        return dist + " blocks " + names[idx] + vert;
    }

    private static boolean containsAny(String msg, String... keys) {
        for (String k : keys) {
            if (k.length() <= 3 ? Pattern.compile("\\b" + Pattern.quote(k) + "\\b").matcher(msg).find()
                    : msg.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String pick(String... options) {
        return options[rnd.nextInt(options.length)];
    }
}
