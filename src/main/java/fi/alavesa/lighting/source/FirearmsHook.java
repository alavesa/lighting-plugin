package fi.alavesa.lighting.source;

import fi.alavesa.lighting.LightingConfig;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Read-only bridge to the Firearms plugin (soft-depend; nothing in Firearms changes). A Firearms gun is an item with
 * the PDC string {@code firearms:gun} = gun id. A gun "has a flashlight attachment" when its id is listed in
 * {@code firearms.flashlight-guns} ("*" = every gun), or when the item carries the flag key
 * {@code firearms.flashlight-flag} (any value; a byte/int 0 means detached).
 */
public final class FirearmsHook {

    private final NamespacedKey gunKey = new NamespacedKey("firearms", "gun");
    private LightingConfig cfg;
    private @Nullable NamespacedKey flagKey;

    public FirearmsHook(LightingConfig cfg) {
        reload(cfg);
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        this.flagKey = cfg.firearmsFlag.isBlank() ? null : NamespacedKey.fromString(cfg.firearmsFlag.toLowerCase(Locale.ROOT));
    }

    public boolean present() {
        Plugin p = Bukkit.getPluginManager().getPlugin("Firearms");
        return p != null && p.isEnabled();
    }

    public @Nullable String gunId(@Nullable ItemStack it) {
        if (it == null || it.isEmpty()) return null;
        return it.getPersistentDataContainer().get(gunKey, PersistentDataType.STRING);
    }

    /** True when this item is a Firearms gun with a flashlight attachment. */
    public boolean hasFlashlight(@Nullable ItemStack it) {
        if (!cfg.firearmsEnabled) return false;
        String id = gunId(it);
        if (id == null) return false;
        if (flagKey != null && it.getPersistentDataContainer().has(flagKey)) {
            Byte b = it.getPersistentDataContainer().get(flagKey, PersistentDataType.BYTE);
            Integer i = b == null ? it.getPersistentDataContainer().get(flagKey, PersistentDataType.INTEGER) : null;
            return (b == null || b != 0) && (i == null || i != 0);
        }
        return cfg.firearmsFlashlightGuns.contains("*") || cfg.firearmsFlashlightGuns.contains(id.toLowerCase(Locale.ROOT));
    }
}
