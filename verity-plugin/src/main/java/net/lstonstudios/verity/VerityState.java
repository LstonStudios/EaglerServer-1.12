package net.lstonstudios.verity;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Spider;

import java.util.UUID;

/** Everything Verity knows about one owner. */
final class VerityState {
    final UUID owner;
    String ownerName;
    double ageTicks;
    int karma;
    int phase = 1;
    boolean greeted;
    boolean released;
    String mode;
    long modeUntil;

    // Runtime-only fields.
    ArmorStand stand;
    Spider demon;
    String shownName = "";
    String faceOverride;
    long faceUntil;
    long nextEvent;
    long demonRespawnAt;
    long lastPat;
    long lastHit;
    long lastGift;
    long lastWarn;

    VerityState(UUID owner, String ownerName, int karma) {
        this.owner = owner;
        this.ownerName = ownerName;
        this.karma = karma;
    }

    static VerityState load(UUID id, ConfigurationSection c) {
        VerityState s = new VerityState(id, c.getString("name", "?"), c.getInt("karma", 50));
        s.ageTicks = c.getDouble("age", 0);
        s.phase = Math.max(1, Math.min(4, c.getInt("phase", 1)));
        s.greeted = c.getBoolean("greeted", true);
        s.released = true;
        return s;
    }

    void save(ConfigurationSection c) {
        c.set("name", ownerName);
        c.set("age", ageTicks);
        c.set("karma", karma);
        c.set("phase", phase);
        c.set("greeted", greeted);
    }

    void changeKarma(int delta) {
        karma = Math.max(0, Math.min(100, karma + delta));
    }
}
