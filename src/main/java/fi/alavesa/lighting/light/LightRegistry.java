package fi.alavesa.lighting.light;

import fi.alavesa.lighting.LightingConfig;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.key.Key;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * All placed lights. Owns the two things a light puts in the world:
 * <ul>
 *   <li>the marker: a non-persistent, tiny item_display whose item (lighting:marker) carries the slot, colour,
 *   radius and intensity to the client shader. Spawned when its chunk loads, gone when it unloads.</li>
 *   <li>optionally a vanilla LIGHT block at the centre, so the light also lights things without the pack.</li>
 * </ul>
 * Slots (0..63) are per world: that is how many lights the shader reads per frame.
 */
public final class LightRegistry implements Listener {

    public static final Key MARKER_MODEL = Key.key("lighting", "marker");

    private final Plugin plugin;
    private final NamespacedKey markerTag;
    private final Map<UUID, LightSource> lights = new LinkedHashMap<>();
    private final Map<UUID, Map<Long, List<LightSource>>> byChunk = new HashMap<>();
    private final Map<UUID, LightSource[]> slots = new HashMap<>();
    private LightingConfig cfg;
    private Runnable onChange = () -> { };

    public LightRegistry(Plugin plugin, LightingConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.markerTag = new NamespacedKey(plugin, "marker");
    }

    public void onChange(Runnable r) {
        this.onChange = r;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        for (LightSource l : lights.values()) refresh(l);
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
                                        @Nullable UUID owner) {
        World w = at.getWorld();
        if (w == null || count(w) >= cfg.maxLightsPerWorld) return null;
        LightSource l = new LightSource(UUID.randomUUID(), w.getUID(), at.getX(), at.getY(), at.getZ(), rgb,
                cfg.clampRadius(radius), LightingConfig.clamp(intensity, 0.05, 3.9), vanillaBlock, owner,
                System.currentTimeMillis());
        add(l);
        onChange.run();
        return l;
    }

    /** Adds a light loaded from storage (no change event). */
    void add(LightSource l) {
        lights.put(l.id, l);
        byChunk.computeIfAbsent(l.worldId, k -> new HashMap<>()).computeIfAbsent(l.chunkKey(), k -> new ArrayList<>()).add(l);
        World w = l.world();
        if (w != null) {
            assignSlot(l);
            if (w.isChunkLoaded((int) Math.floor(l.x) >> 4, (int) Math.floor(l.z) >> 4)) materialize(l);
        }
    }

    public void update(LightSource l, int rgb, double radius, double intensity, boolean vanillaBlock) {
        l.rgb = rgb & 0xFFFFFF;
        l.radius = cfg.clampRadius(radius);
        l.intensity = LightingConfig.clamp(intensity, 0.05, 3.9);
        l.vanillaBlock = vanillaBlock;
        refresh(l);
        onChange.run();
    }

    public void move(LightSource l, Location to) {
        if (to.getWorld() == null || !to.getWorld().getUID().equals(l.worldId)) return;
        dematerialize(l, true);
        unindex(l);
        l.x = to.getX();
        l.y = to.getY();
        l.z = to.getZ();
        byChunk.computeIfAbsent(l.worldId, k -> new HashMap<>()).computeIfAbsent(l.chunkKey(), k -> new ArrayList<>()).add(l);
        if (to.getWorld().isChunkLoaded(to.getBlockX() >> 4, to.getBlockZ() >> 4)) materialize(l);
        onChange.run();
    }

    public void remove(LightSource l) {
        dematerialize(l, true);
        unindex(l);
        lights.remove(l.id);
        LightSource[] s = slots.get(l.worldId);
        if (s != null && l.slot >= 0 && s[l.slot] == l) s[l.slot] = null;
        l.slot = -1;
        // a light that had no slot may get one now
        for (LightSource other : lights.values()) {
            if (other.worldId.equals(l.worldId) && other.slot < 0) {
                assignSlot(other);
                refresh(other);
            }
        }
        onChange.run();
    }

    /** Removes the markers (shutdown). Lights and their light blocks stay. */
    public void shutdown() {
        for (LightSource l : lights.values()) dematerialize(l, false);
    }

    /** Spawns markers / light blocks for every light in a loaded chunk (startup). */
    public void materializeLoaded() {
        for (LightSource l : lights.values()) {
            World w = l.world();
            if (w == null) continue;
            if (l.slot < 0) assignSlot(l);
            if (w.isChunkLoaded((int) Math.floor(l.x) >> 4, (int) Math.floor(l.z) >> 4)) materialize(l);
        }
    }

    private void unindex(LightSource l) {
        Map<Long, List<LightSource>> m = byChunk.get(l.worldId);
        if (m == null) return;
        List<LightSource> list = m.get(l.chunkKey());
        if (list != null) {
            list.remove(l);
            if (list.isEmpty()) m.remove(l.chunkKey());
        }
    }

    private void assignSlot(LightSource l) {
        LightSource[] s = slots.computeIfAbsent(l.worldId, k -> new LightSource[64]);
        for (int i = 0; i < cfg.maxLightsPerWorld; i++) {
            if (s[i] == null || s[i] == l) {
                s[i] = l;
                l.slot = i;
                return;
            }
        }
        l.slot = -1;
    }

    // ------------------------------------------------------------------ world objects

    private void refresh(LightSource l) {
        World w = l.world();
        if (w == null) return;
        if (!w.isChunkLoaded((int) Math.floor(l.x) >> 4, (int) Math.floor(l.z) >> 4)) return;
        if (l.marker != null && l.marker.isValid() && l.slot >= 0) {
            l.marker.setItemStack(markerItem(l));
            l.marker.setViewRange(cfg.markerViewRange);
        } else {
            removeMarker(l);
            spawnMarker(l);
        }
        syncBlock(l);
    }

    private void materialize(LightSource l) {
        spawnMarker(l);
        syncBlock(l);
    }

    private void dematerialize(LightSource l, boolean removeBlock) {
        removeMarker(l);
        if (removeBlock) removeBlock(l);
    }

    private void spawnMarker(LightSource l) {
        if (l.slot < 0 || (l.marker != null && l.marker.isValid())) return;
        Location at = l.location();
        if (at == null) return;
        l.marker = at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(markerItem(l));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            // scaled to nothing: without the pack (or on 26.2) the marker is invisible; the shader ignores the scale
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.001f),
                    new AxisAngle4f()));
            // width/height 0 = never frustum-culled, so lights behind the camera still light what is in front
            d.setDisplayWidth(0f);
            d.setDisplayHeight(0f);
            d.setViewRange(cfg.markerViewRange);
            d.setShadowRadius(0f);
            d.getPersistentDataContainer().set(markerTag, PersistentDataType.STRING, l.id.toString());
        });
    }

    private void removeMarker(LightSource l) {
        if (l.marker != null && l.marker.isValid()) l.marker.remove();
        l.marker = null;
    }

    private ItemStack markerItem(LightSource l) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, MARKER_MODEL);
        int r = (int) Math.round(l.radius * 8);
        int g = (int) Math.round(l.intensity * 64);
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData()
                .addFloat(l.slot)
                .addColor(Color.fromRGB(l.rgb & 0xFFFFFF))
                .addColor(Color.fromRGB(Math.max(1, Math.min(255, r)), Math.max(0, Math.min(255, g)), 165))
                .build());
        return it;
    }

    private void syncBlock(LightSource l) {
        Location at = l.location();
        if (at == null) return;
        Block b = at.getBlock();
        if (!(l.vanillaBlock && cfg.vanillaLight)) {
            removeBlock(l);
            return;
        }
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
        l.ownsBlock = l.vanillaBlock && at != null && at.getBlock().getType() == Material.LIGHT;
    }

    // ------------------------------------------------------------------ chunk lifecycle

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        for (LightSource l : inChunk(e.getChunk())) {
            if (!l.ownsBlock) adoptBlock(l);
            materialize(l);
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        for (LightSource l : inChunk(e.getChunk())) removeMarker(l);
    }

    /** Strays from a crash (markers are non-persistent, so this is belt and braces). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof ItemDisplay && en.getPersistentDataContainer().has(markerTag)) {
                String id = en.getPersistentDataContainer().get(markerTag, PersistentDataType.STRING);
                LightSource l = id == null ? null : lights.get(UUID.fromString(id));
                if (l == null || l.marker != en) en.remove();
            }
        }
    }

    @EventHandler
    public void onWorldUnload(WorldUnloadEvent e) {
        for (LightSource l : lights.values()) if (l.worldId.equals(e.getWorld().getUID())) removeMarker(l);
    }

    private List<LightSource> inChunk(Chunk c) {
        Map<Long, List<LightSource>> m = byChunk.get(c.getWorld().getUID());
        if (m == null) return List.of();
        List<LightSource> list = m.get(LightSource.chunkKey(c.getX(), c.getZ()));
        return list == null ? List.of() : List.copyOf(list);
    }

    public Set<UUID> worldsWithLights() {
        Set<UUID> s = new HashSet<>();
        for (LightSource l : lights.values()) s.add(l.worldId);
        return s;
    }

    private static double dist2(LightSource l, double x, double y, double z) {
        double dx = l.x - x, dy = l.y - y, dz = l.z - z;
        return dx * dx + dy * dy + dz * dz;
    }
}
