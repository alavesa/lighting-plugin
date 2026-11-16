package fi.alavesa.lighting.wand;

import fi.alavesa.lighting.LightingConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/** The Lighting Wand item, and each player's wand settings (what the next placed light looks like). */
public final class Wand {

    public static final Key MODEL = Key.key("lighting", "wand");

    /** Per-player wand state. Settings live here, not on the item, so every wand you hold behaves the same. */
    public static final class Session {
        public int rgb;
        public double radius;
        public double intensity;
        public boolean vanillaBlock;
        public fi.alavesa.lighting.render.Anim anim = fi.alavesa.lighting.render.Anim.NONE;
        /** Place spotlights (aimed where you look when placing) instead of point lights. */
        public boolean spot;
        public double cone = 40;
        public int preset;
        /** For the resize animation: the radius before the last change and the tick it changed. */
        public double fromRadius;
        public long changedAt = Long.MIN_VALUE;
        /** Small lockout so a dialog click or a placement doesn't also fire a second action. */
        public long busyUntil;

        Session(LightingConfig cfg) {
            rgb = cfg.defaultColor;
            radius = cfg.defaultRadius;
            fromRadius = radius;
            intensity = cfg.defaultIntensity;
            vanillaBlock = cfg.vanillaLight;
        }
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final NamespacedKey tag;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private LightingConfig cfg;

    public Wand(Plugin plugin, LightingConfig cfg) {
        this.tag = new NamespacedKey(plugin, "wand");
        this.cfg = cfg;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        for (Session s : sessions.values()) s.radius = cfg.clampRadius(s.radius);
    }

    public Session session(Player p) {
        return sessions.computeIfAbsent(p.getUniqueId(), k -> new Session(cfg));
    }

    public void forget(Player p) {
        sessions.remove(p.getUniqueId());
    }

    public ItemStack create() {
        ItemStack it = ItemStack.of(Material.BLAZE_ROD);
        it.setData(DataComponentTypes.ITEM_MODEL, MODEL);
        it.setData(DataComponentTypes.ITEM_NAME, MM.deserialize("<gradient:#FFE29A:#FF9E5E:#B47CFF>Lighting Wand</gradient>"));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(
                line("<gray>Paints coloured light into the world."),
                Component.empty(),
                line("<yellow>Right-click</yellow> <gray>place a light / edit the one you aim at"),
                line("<yellow>Sneak + scroll</yellow> <gray>resize the light"),
                line("<yellow>Left-click</yellow> <gray>next colour preset"),
                line("<yellow>Sneak + right-click</yellow> <gray>colour, radius, intensity, animation, spotlight"),
                line("<yellow>Sneak + left-click</yellow> <gray>delete the light you aim at"))));
        it.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        it.editPersistentDataContainer(pdc -> pdc.set(tag, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    /** Hand-held flashlight: Q toggles it, the beam follows your view. */
    public ItemStack flashlight(NamespacedKey flashTag) {
        ItemStack it = ItemStack.of(Material.STICK);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key("lighting", "flashlight"));
        it.setData(DataComponentTypes.ITEM_NAME, MM.deserialize("<white>Flashlight"));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(line("<gray>Hold to light the way."),
                line("<yellow>Q</yellow> <gray>switch on/off"))));
        it.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        it.editPersistentDataContainer(pdc -> pdc.set(flashTag, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    /** Helmet-mounted flashlight. */
    public ItemStack headlamp(NamespacedKey flashTag) {
        ItemStack it = ItemStack.of(Material.LEATHER_HELMET);
        it.setData(DataComponentTypes.ITEM_NAME, MM.deserialize("<white>Headlamp"));
        it.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(line("<gray>Wear it: the beam follows your view."),
                line("<dark_gray>/lighting settings to switch it off"))));
        it.editPersistentDataContainer(pdc -> pdc.set(flashTag, PersistentDataType.BYTE, (byte) 1));
        return it;
    }

    public boolean is(@Nullable ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    public boolean holding(Player p) {
        return is(p.getInventory().getItemInMainHand());
    }

    private static Component line(String mm) {
        return MM.deserialize(mm).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}
