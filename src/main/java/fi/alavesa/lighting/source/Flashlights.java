package fi.alavesa.lighting.source;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.render.Anim;
import fi.alavesa.lighting.render.Emitter;
import fi.alavesa.lighting.render.EmitterSource;
import fi.alavesa.lighting.render.RenderEngine;
import java.util.List;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Flashlights: our flashlight item in either hand, a headlamp (any helmet tagged {@code lighting:flashlight}), or a
 * Firearms gun with a flashlight attachment ({@link FirearmsHook}). Each tick the beam is raycast along the look
 * vector; the hit becomes a spot emitter (hotspot + spill decals, beam cone on Ultra) plus client-side LIGHT
 * blocks at the hit and every few blocks along the beam. Q (drop) while holding one toggles it.
 */
public final class Flashlights implements EmitterSource, Listener {

    private final NamespacedKey tag;
    private final FirearmsHook firearms;
    private final RenderEngine engine;
    private LightingConfig cfg;

    public Flashlights(Plugin plugin, LightingConfig cfg, FirearmsHook firearms, RenderEngine engine) {
        this.tag = new NamespacedKey(plugin, "flashlight");
        this.cfg = cfg;
        this.firearms = firearms;
        this.engine = engine;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    public NamespacedKey tag() {
        return tag;
    }

    @Override
    public String name() {
        return "flashlights";
    }

    /** Where the light comes from, or null when this player has no flashlight. */
    public @Nullable String kind(Player p) {
        ItemStack main = p.getInventory().getItemInMainHand(), off = p.getInventory().getItemInOffHand();
        if (firearms.hasFlashlight(main)) return "gun";
        if (tagged(main) || tagged(off)) return "handheld";
        if (tagged(p.getInventory().getHelmet())) return "headlamp";
        return null;
    }

    private boolean tagged(@Nullable ItemStack it) {
        return it != null && !it.isEmpty() && it.getPersistentDataContainer().has(tag, PersistentDataType.BYTE);
    }

    @Override
    public void collect(World world, double[] viewers, double range, long tick, List<Emitter> out) {
        if (!cfg.flashlightsEnabled) return;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            String kind = kind(p);
            if (kind == null || !engine.viewer(p).settings.flashlightOn) continue;
            Location eye = p.getEyeLocation();
            if (!Sources.near(viewers, eye.getX(), eye.getY(), eye.getZ(), range + cfg.flashRange)) continue;
            Vector dir = eye.getDirection();
            // lamp: a little right of and below the eye for hand-held lights, at the forehead for a headlamp
            Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
            if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
            right.normalize();
            Vector origin = eye.toVector().add(dir.clone().multiply(0.4));
            if (!kind.equals("headlamp")) origin.add(right.multiply(0.28)).add(new Vector(0, -0.22, 0));
            else origin.add(new Vector(0, 0.15, 0));

            RayTraceResult hit = world.rayTraceBlocks(eye, dir, cfg.flashRange, FluidCollisionMode.NEVER, true);
            Emitter.Spot spot;
            int fx, fy, fz;
            double dist;
            if (hit != null && hit.getHitBlockFace() != null) {
                Vector n = hit.getHitBlockFace().getDirection();
                dist = hit.getHitPosition().distance(eye.toVector());
                spot = new Emitter.Spot(origin, dir, cfg.flashCone, cfg.flashRange, hit.getHitPosition(), n, dist);
                Block air = hit.getHitBlock().getRelative(hit.getHitBlockFace());
                fx = air.getX();
                fy = air.getY();
                fz = air.getZ();
            } else {
                dist = cfg.flashRange;
                spot = new Emitter.Spot(origin, dir, cfg.flashCone, cfg.flashRange, null, null, dist);
                Vector end = eye.toVector().add(dir.clone().multiply(dist));
                fx = end.getBlockX();
                fy = end.getBlockY();
                fz = end.getBlockZ();
            }
            int hitLevel = (int) Math.round(15 - 6 * Math.min(1, dist / cfg.flashRange));
            String key = "f:" + p.getUniqueId();
            out.add(new Emitter(key, world, eye.getX(), eye.getY(), eye.getZ(), cfg.flashColor, true, 0, 1.0, false,
                    hit != null ? hitLevel : 0, fx, fy, fz, spot, false, Anim.NONE, 0));
            // light along the beam, dimmer with distance (brightness only: no decals)
            for (double t = cfg.flashBeamStep; t < dist - 1.5; t += cfg.flashBeamStep) {
                Vector pt = eye.toVector().add(dir.clone().multiply(t));
                int lvl = (int) Math.round(cfg.flashBeamLevel * (1 - 0.5 * t / cfg.flashRange));
                out.add(new Emitter(key + ":" + (int) t, world, pt.getX(), pt.getY(), pt.getZ(), cfg.flashColor, false,
                        0, 1, false, lvl, pt.getBlockX(), pt.getBlockY(), pt.getBlockZ(), null, false, Anim.NONE, 0));
            }
        }
    }

    /** Q while holding a flashlight (or a gun with one) switches it on/off instead of dropping the item. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!cfg.flashToggleWithDrop) return;
        Player p = e.getPlayer();
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (!(tagged(dropped) || firearms.hasFlashlight(dropped))) return;
        // only the drop key on the held item (not dragging out of the inventory)
        if (p.getOpenInventory().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING) return;
        e.setCancelled(true);
        var s = engine.viewer(p).settings;
        s.flashlightOn = !s.flashlightOn;
        engine.viewer(p).settings.save(engine.pluginRef(), p);
        p.playSound(p.getLocation(), org.bukkit.Sound.UI_BUTTON_CLICK, 0.6f, s.flashlightOn ? 1.6f : 1.1f);
        p.sendActionBar(net.kyori.adventure.text.Component.text("Flashlight " + (s.flashlightOn ? "on" : "off")));
    }
}
