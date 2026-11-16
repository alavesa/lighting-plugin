package fi.alavesa.lighting.render;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** One player's lighting preferences, kept in the player's own data (survives restarts, follows the player). */
public final class PlayerSettings {

    public boolean enabled = true;
    public Quality quality;
    public boolean animations = true;
    public Palette palette = Palette.NORMAL;
    public boolean flashlightOn = true;

    private PlayerSettings(Quality defaultQuality) {
        this.quality = defaultQuality;
    }

    /** Stored as "enabled;quality;animations;palette;flashlight". */
    public static PlayerSettings load(Plugin plugin, Player p, Quality defaultQuality) {
        PlayerSettings s = new PlayerSettings(defaultQuality);
        String raw = p.getPersistentDataContainer().get(key(plugin), PersistentDataType.STRING);
        if (raw == null) return s;
        String[] a = raw.split(";");
        try {
            if (a.length > 0) s.enabled = Boolean.parseBoolean(a[0]);
            if (a.length > 1 && Quality.parse(a[1]) != null) s.quality = Quality.parse(a[1]);
            if (a.length > 2) s.animations = Boolean.parseBoolean(a[2]);
            if (a.length > 3 && Palette.parse(a[3]) != null) s.palette = Palette.parse(a[3]);
            if (a.length > 4) s.flashlightOn = Boolean.parseBoolean(a[4]);
        } catch (RuntimeException ignored) {
            // keep defaults for anything unreadable
        }
        return s;
    }

    public void save(Plugin plugin, Player p) {
        p.getPersistentDataContainer().set(key(plugin), PersistentDataType.STRING,
                enabled + ";" + quality.name() + ";" + animations + ";" + palette.name() + ";" + flashlightOn);
    }

    private static NamespacedKey key(Plugin plugin) {
        return new NamespacedKey(plugin, "settings");
    }
}
