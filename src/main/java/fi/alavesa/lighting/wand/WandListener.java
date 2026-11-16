package fi.alavesa.lighting.wand;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightSource;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * Wand input and the per-tick preview.
 * <ul>
 *   <li>right-click: edit the aimed light, else place one at the preview point</li>
 *   <li>sneak + right-click: settings dialog</li>
 *   <li>sneak + scroll: radius (the hotbar slot change is cancelled)</li>
 *   <li>left-click: next colour preset; sneak + left-click: delete the aimed light</li>
 * </ul>
 */
public final class WandListener implements Listener {

    public static final String PERMISSION = "lighting.wand";

    private final Plugin plugin;
    private final LightRegistry registry;
    private final Wand wand;
    private final WandVfx vfx;
    private final LightDialogs dialogs;
    private LightingConfig cfg;
    private long tick;

    public WandListener(Plugin plugin, LightingConfig cfg, LightRegistry registry, Wand wand, WandVfx vfx,
                        LightDialogs dialogs) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.registry = registry;
        this.wand = wand;
        this.vfx = vfx;
        this.dialogs = dialogs;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ ticking

    public void tick() {
        tick++;
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (!wand.holding(p) || !p.hasPermission(PERMISSION)) continue;
            Wand.Session s = wand.session(p);
            vfx.trail(p, s.rgb, tick);
            if (tick % cfg.previewInterval != 0) continue;

            LightSource aimed = registry.raycast(p.getEyeLocation(), cfg.selectDistance, cfg.selectTolerance);
            for (LightSource l : registry.near(p.getLocation(), cfg.previewShowLightsWithin)) {
                Location at = l.location();
                if (at != null && l != aimed) vfx.lightDot(p, l, at);
            }
            if (aimed != null) {
                Location at = aimed.location();
                if (at != null) vfx.sphere(p, at, aimed.radius(), aimed.rgb(), tick, -1, aimed.radius(), true);
                p.sendActionBar(LightDialogs.mm("<gold>Light " + aimed.shortId() + " " + LightDialogs.swatch(aimed.rgb())
                        + " <gray>r " + LightDialogs.fmt(aimed.radius()) + "  <yellow>right-click</yellow> edit  "
                        + "<yellow>sneak+left</yellow> delete"));
                continue;
            }
            Location target = placement(p);
            long since = p.getWorld().getGameTime() - s.changedAt;
            double t = since >= 0 && since < WandVfx.resizeTicks() ? since / (double) WandVfx.resizeTicks() : -1;
            vfx.sphere(p, target, s.radius, s.rgb, tick, t, s.fromRadius, false);
            p.sendActionBar(LightDialogs.mm(LightDialogs.swatch(s.rgb) + " <gray>radius <white>"
                    + LightDialogs.fmt(s.radius) + "</white> intensity <white>" + LightDialogs.fmt(s.intensity)
                    + "</white>  <dark_gray>sneak+scroll resize, sneak+right-click settings"));
        }
    }

    /** Light centre for a new light: the centre of the air block in front of the aimed face, or in mid-air. */
    public Location placement(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), cfg.placeDistance,
                FluidCollisionMode.NEVER, true);
        Block b;
        if (hit != null && hit.getHitBlock() != null && hit.getHitBlockFace() != null) {
            b = hit.getHitBlock().getRelative(hit.getHitBlockFace());
        } else {
            Vector v = eye.toVector().add(eye.getDirection().multiply(cfg.placeDistance));
            b = p.getWorld().getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ());
        }
        return b.getLocation().add(0.5, 0.5, 0.5);
    }

    // ------------------------------------------------------------------ input

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !wand.is(e.getItem())) return;
        Player p = e.getPlayer();
        if (!p.hasPermission(PERMISSION)) return;
        e.setCancelled(true);
        Wand.Session s = wand.session(p);
        long now = p.getWorld().getGameTime();
        if (now < s.busyUntil) return;
        s.busyUntil = now + 4;
        boolean right = e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK;
        LightSource aimed = registry.raycast(p.getEyeLocation(), cfg.selectDistance, cfg.selectTolerance);
        if (right) {
            if (p.isSneaking()) openSettings(p, s);
            else if (aimed != null) openEdit(p, aimed);
            else place(p, s);
        } else {
            if (p.isSneaking()) {
                if (aimed != null) delete(p, aimed);
                else vfx.denied(p);
            } else {
                cyclePreset(p, s);
            }
        }
    }

    /** Creative mode would break the block you left-click to change colour. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (wand.holding(e.getPlayer()) && e.getPlayer().hasPermission(PERMISSION)) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        if (!p.isSneaking() || !wand.is(p.getInventory().getItem(e.getPreviousSlot())) || !p.hasPermission(PERMISSION)) {
            return;
        }
        int diff = e.getNewSlot() - e.getPreviousSlot();
        if (diff > 4) diff -= 9;
        if (diff < -4) diff += 9;
        if (diff == 0) return;
        e.setCancelled(true);
        Wand.Session s = wand.session(p);
        // scrolling up (towards slot 0) grows the light
        double next = cfg.clampRadius(s.radius - Math.signum(diff) * cfg.radiusStep);
        if (next == s.radius) {
            vfx.denied(p);
            return;
        }
        long now = p.getWorld().getGameTime();
        // chain quick scrolls into one smooth animation from wherever the ring currently is
        s.fromRadius = now - s.changedAt < WandVfx.resizeTicks() ? s.fromRadius : s.radius;
        s.radius = next;
        s.changedAt = now;
        vfx.resizeTick(p, next);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        if (wand.holding(p) && p.hasPermission(PERMISSION)) vfx.swing(p, wand.session(p).rgb);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        wand.forget(e.getPlayer());
    }

    // ------------------------------------------------------------------ actions

    private void place(Player p, Wand.Session s) {
        Location at = placement(p);
        LightSource l = registry.create(at, s.rgb, s.radius, s.intensity, s.vanillaBlock, s.anim,
                s.spot ? p.getEyeLocation().getDirection() : null, s.cone, p.getUniqueId());
        if (l == null) {
            vfx.denied(p);
            p.sendMessage(LightDialogs.mm("<red>This world already has " + cfg.maxLightsPerWorld
                    + " lights (lights.max-per-world). Remove one first."));
            return;
        }
        vfx.placed(at, l.radius(), l.rgb());
    }

    private void delete(Player p, LightSource l) {
        Location at = l.location();
        int rgb = l.rgb();
        registry.remove(l);
        if (at != null) vfx.removed(at, rgb);
        p.sendActionBar(LightDialogs.mm("<gray>Removed light " + l.shortId()));
    }

    private void cyclePreset(Player p, Wand.Session s) {
        s.preset = (s.preset + 1) % cfg.presets.size();
        LightingConfig.Preset pr = cfg.presets.get(s.preset);
        s.rgb = pr.rgb();
        p.sendActionBar(LightDialogs.mm(LightDialogs.swatch(s.rgb) + " <gray>" + pr.name()));
        vfx.resizeTick(p, s.radius);
    }

    private void openSettings(Player p, Wand.Session s) {
        dialogs.settings(p, s, v -> {
            s.fromRadius = s.radius;
            s.changedAt = p.getWorld().getGameTime();
            s.rgb = v.rgb();
            s.radius = v.radius();
            s.intensity = v.intensity();
            s.vanillaBlock = v.vanillaBlock();
            s.anim = v.anim();
            s.spot = v.spot();
            s.cone = v.cone();
        });
    }

    private void openEdit(Player p, LightSource l) {
        dialogs.edit(p, l, new LightDialogs.EditActions() {
            @Override
            public void save(LightDialogs.Values v) {
                if (gone(l)) return;
                // turning "spotlight" off makes it a point light; turning it on keeps / picks a direction
                org.bukkit.util.Vector dir = v.spot() ? (l.spotDir() != null ? l.spotDir()
                        : p.getEyeLocation().getDirection()) : null;
                registry.update(l, v.rgb(), v.radius(), v.intensity(), v.vanillaBlock(), v.anim(), dir, v.cone());
                Location at = l.location();
                if (at != null) vfx.edited(at, l.radius(), l.rgb());
            }

            @Override
            public void delete() {
                if (!gone(l)) WandListener.this.delete(p, l);
            }

            @Override
            public void copyToWand(LightDialogs.Values v) {
                Wand.Session s = wand.session(p);
                s.rgb = v.rgb();
                s.radius = v.radius();
                s.intensity = v.intensity();
                s.vanillaBlock = v.vanillaBlock();
                s.anim = v.anim();
                s.spot = v.spot();
                s.cone = v.cone();
                p.sendActionBar(LightDialogs.mm("<gray>Wand now places " + LightDialogs.swatch(s.rgb)));
            }

            @Override
            public void moveToAim(LightDialogs.Values v) {
                if (gone(l)) return;
                Location to = placement(p);
                registry.update(l, v.rgb(), v.radius(), v.intensity(), v.vanillaBlock(), v.anim(),
                        v.spot() ? (l.spotDir() != null ? l.spotDir() : p.getEyeLocation().getDirection()) : null, v.cone());
                registry.move(l, to);
                vfx.placed(to, l.radius(), l.rgb());
            }

            @Override
            public void aimSpot(LightDialogs.Values v) {
                if (gone(l)) return;
                registry.update(l, v.rgb(), v.radius(), v.intensity(), v.vanillaBlock(), v.anim(),
                        p.getEyeLocation().getDirection(), v.cone());
                Location at = l.location();
                if (at != null) vfx.edited(at, Math.min(2, l.radius()), l.rgb());
            }
        });
    }

    private boolean gone(@Nullable LightSource l) {
        return l == null || registry.get(l.id) == null;
    }
}
