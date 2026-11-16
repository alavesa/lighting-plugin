package fi.alavesa.lighting.light;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.render.Anim;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Vector;
import org.jspecify.annotations.Nullable;

/**
 * All lights placed with the wand. A light is a record (lights.yml) plus, in light-block mode "real", a vanilla
 * LIGHT block at its centre. What players see of it (decals, fake light) is drawn per viewer by the render engine
 * through {@link fi.alavesa.lighting.source.WandLights}.
 */
public final class LightRegistry implements Listener {

    private final Map<UUID, LightSource> lights = new LinkedHashMap<>();
    private LightingConfig cfg;
    private Runnable onChange = () -> { };

    public LightRegistry(LightingConfig cfg) {
        this.cfg = cfg;
    }

    public void onChange(Runnable r) {
        this.onChange = r;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        for (LightSource l : lights.values()) syncBlock(l);
    }

    // ------------------------------------------------------------------ queries

    public Collection<LightSource> all() {
        return Collections.unmodifiableCollection(lights.values());
    }

    public @Nullable LightSource get(UUID id) {
        return lights.get(id);
    }

    /** By full id or by the 8-character prefix shown in chat. */
    public @Nullable LightSource find(String id) {
        for (LightSource l : lights.values()) {
            if (l.id.toString().equalsIgnoreCase(id) || l.shortId().equalsIgnoreCase(id)) return l;
        }
        return null;
    }

    public List<LightSource> near(Location at, double within) {
        List<LightSource> out = new ArrayList<>();
        if (at.getWorld() == null) return out;
        double w2 = within * within;
        for (LightSource l : lights.values()) {
            if (!l.worldId.equals(at.getWorld().getUID())) continue;
            if (dist2(l, at.getX(), at.getY(), at.getZ()) <= w2) out.add(l);
        }
        out.sort(Comparator.comparingDouble(l -> dist2(l, at.getX(), at.getY(), at.getZ())));
        return out;
    }

    /** The light whose centre is closest to the ray, within {@code tolerance} blocks of it and {@code maxDist} along it. */
    public @Nullable LightSource raycast(Location eye, double maxDist, double tolerance) {
        if (eye.getWorld() == null) return null;
        var dir = eye.getDirection();
        LightSource best = null;
        double bestScore = Double.MAX_VALUE;
        for (LightSource l : lights.values()) {
            if (!l.worldId.equals(eye.getWorld().getUID())) continue;
            double vx = l.x - eye.getX(), vy = l.y - eye.getY(), vz = l.z - eye.getZ();
            double t = vx * dir.getX() + vy * dir.getY() + vz * dir.getZ();
            if (t < 0 || t > maxDist) continue;
            double px = vx - dir.getX() * t, py = vy - dir.getY() * t, pz = vz - dir.getZ() * t;
            double off = Math.sqrt(px * px + py * py + pz * pz);
            if (off > tolerance) continue;
            double score = off + t * 0.02;
            if (score < bestScore) {
                bestScore = score;
                best = l;
            }
        }
        return best;
    }

    public int count(World w) {
        int n = 0;
        for (LightSource l : lights.values()) if (l.worldId.equals(w.getUID())) n++;
        return n;
    }

    // ------------------------------------------------------------------ changes

    /** Creates a light; null when the world already has max-per-world lights. */
    public @Nullable LightSource create(Location at, int rgb, double radius, double intensity, boolean vanillaBlock,
                                        Anim anim, @Nullable Vector spotDir, double coneDeg, @Nullable UUID owner) {
        World w = at.getWorld();
        if (w == null || count(w) >= cfg.maxLightsPerWorld) return null;
        LightSource l = new LightSource(UUID.randomUUID(), w.getUID(), at.getX(), at.getY(), at.getZ(), rgb,
                cfg.clampRadius(radius), LightingConfig.clamp(intensity, 0.05, 3.9), vanillaBlock, owner,
                System.currentTimeMillis());
        l.anim = anim;
        l.spotDir = spotDir == null ? null : spotDir.clone().normalize();
        l.coneDeg = LightingConfig.clamp(coneDeg, 5, 120);
        add(l);
        onChange.run();
        return l;
    }

    /** Adds a light loaded from storage (no change event). */
    void add(LightSource l) {
        lights.put(l.id, l);
        World w = l.world();
        if (w != null && w.isChunkLoaded((int) Math.floor(l.x) >> 4, (int) Math.floor(l.z) >> 4)) syncBlock(l);
    }

    public void update(LightSource l, int rgb, double radius, double intensity, boolean vanillaBlock, Anim anim,
                       @Nullable Vector spotDir, double coneDeg) {
        l.rgb = rgb & 0xFFFFFF;
        l.radius = cfg.clampRadius(radius);
        l.intensity = LightingConfig.clamp(intensity, 0.05, 3.9);
        l.vanillaBlock = vanillaBlock;
        l.anim = anim;
        l.spotDir = spotDir == null ? null : spotDir.clone().normalize();
        l.coneDeg = LightingConfig.clamp(coneDeg, 5, 120);
        syncBlock(l);
        onChange.run();
    }

    public void move(LightSource l, Location to) {
        if (to.getWorld() == null || !to.getWorld().getUID().equals(l.worldId)) return;
        removeBlock(l);
        l.x = to.getX();
        l.y = to.getY();
        l.z = to.getZ();
        syncBlock(l);
        onChange.run();
    }

    public void remove(LightSource l) {
        removeBlock(l);
        lights.remove(l.id);
        onChange.run();
    }

    // ------------------------------------------------------------------ the vanilla LIGHT block

    /** True when this light should have a real LIGHT block (config light-block: real and the light wants one). */
    public boolean realBlock(LightSource l) {
        return l.vanillaBlock && cfg.lightBlockMode.equals("real");
    }

    /** True when this light should get a client-side (fake) LIGHT block instead. */
    public boolean packetBlock(LightSource l) {
        return l.vanillaBlock && cfg.lightBlockMode.equals("packet");
    }

    private void syncBlock(LightSource l) {
        Location at = l.location();
        if (at == null || !at.isChunkLoaded()) return;
        if (!realBlock(l)) {
            removeBlock(l);
            return;
        }
        Block b = at.getBlock();
        boolean free = b.getType() == Material.AIR || b.getType() == Material.CAVE_AIR || b.getType() == Material.LIGHT
                || (b.getType() == Material.WATER && b.getBlockData() instanceof Levelled lv && lv.getLevel() == 0);
        if (!free) return;
        boolean water = b.getType() == Material.WATER
                || (b.getType() == Material.LIGHT && b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged());
        BlockData data = Material.LIGHT.createBlockData();
        ((Levelled) data).setLevel(l.vanillaLevel());
        ((Waterlogged) data).setWaterlogged(water);
        if (!b.getBlockData().equals(data)) b.setBlockData(data, false);
        l.ownsBlock = true;
    }

    private void removeBlock(LightSource l) {
        if (!l.ownsBlock) return;
        l.ownsBlock = false;
        Location at = l.location();
        if (at == null) return;
        Block b = at.getBlock();
        if (b.getType() != Material.LIGHT) return;
        boolean water = b.getBlockData() instanceof Waterlogged wl && wl.isWaterlogged();
        b.setType(water ? Material.WATER : Material.AIR, false);
    }

    /** After a restart we don't know who placed a LIGHT block; a stored light with vanillaBlock owns the one at its centre. */
    void adoptBlock(LightSource l) {
        Location at = l.location();
        l.ownsBlock = l.vanillaBlock && at != null && at.isChunkLoaded() && at.getBlock().getType() == Material.LIGHT;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        UUID w = e.getWorld().getUID();
        for (LightSource l : lights.values()) {
            if (!l.worldId.equals(w) || ((int) Math.floor(l.x) >> 4) != e.getChunk().getX()
                    || ((int) Math.floor(l.z) >> 4) != e.getChunk().getZ()) continue;
            if (!l.ownsBlock) adoptBlock(l);
            syncBlock(l);
        }
    }

    private static double dist2(LightSource l, double x, double y, double z) {
        double dx = l.x - x, dy = l.y - y, dz = l.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
