package fi.alavesa.lighting.render;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix4f;

/**
 * Everything one player currently sees from Lighting: a pool of display entities only they can see, and the fake
 * LIGHT blocks sent only to them. Each render pass hands over the wanted decals and fake lights; this diffs them
 * against what is already out there.
 *
 * <p>Decals are packet-only item_displays ({@link PacketDisplays}): never in the world, never ticked by the server.
 * A decal keeps a fixed anchor and carries its real position in the transformation matrix, so moving it (a
 * flashlight following the aim) is ONE interpolated metadata change: no teleport packet that could land in a
 * different frame than the rotation/scale. All changes of a pass go out as one ClientboundBundlePacket. Freed
 * entities stay spawned on the client with no item (draw nothing) and are reused. If the server's classes ever don't
 * match, real (hidden, non-persistent) entities are used instead.
 */
public final class DisplayEntityPoolManager {

    private static final int LEVELS = 8;

    private final Plugin plugin;
    private final Player player;
    private final Map<String, ItemDisplay> active = new HashMap<>();
    private final Map<String, String> shown = new HashMap<>();
    private final Map<String, Matrix4f> matrices = new HashMap<>();
    private final Deque<ItemDisplay> free = new ArrayDeque<>();
    private final Map<String, TextDisplay> glyphs = new HashMap<>();
    private final Map<Long, Integer> fakes = new HashMap<>();
    // packet backend
    private final Map<String, PacketDisplays.Part> parts = new HashMap<>();
    private final Deque<PacketDisplays.Part> freeParts = new ArrayDeque<>();
    private World partWorld;
    private boolean packetsBroken;
    private World fakeWorld;
    public PlayerSettings settings;

    DisplayEntityPoolManager(Plugin plugin, Player player, PlayerSettings settings) {
        this.plugin = plugin;
        this.player = player;
        this.settings = settings;
    }

    public Player player() {
        return player;
    }

    public int decalCount() {
        return active.size() + parts.size();
    }

    public int fakeCount() {
        return fakes.size();
    }

    // ------------------------------------------------------------------ decals

    /**
     * @param minAlpha decals fainter than this are dropped (the lowest opacity step is 1/8)
     * @param poolMax  idle entities kept for reuse
     */
    void apply(List<Decal> decals, Map<String, Integer> colours, double minAlpha, int poolMax) {
        if (PacketDisplays.available() && !packetsBroken) {
            try {
                applyPackets(decals, colours, minAlpha, poolMax);
                return;
            } catch (ReflectiveOperationException | RuntimeException e) {
                packetsBroken = true;
                plugin.getLogger().warning("packet decals failed for " + player.getName() + " (" + e
                        + "); using hidden entities instead");
                parts.clear();
                freeParts.clear();
            }
        }
        applyEntities(decals, colours, minAlpha, poolMax);
    }

    /** How far a decal may sit from its entity's anchor before the entity is re-spawned closer. */
    private static final double MAX_OFFSET = 24;

    private void applyPackets(List<Decal> decals, Map<String, Integer> colours, double minAlpha, int poolMax)
            throws ReflectiveOperationException {
        List<Object> out = new ArrayList<>();
        if (partWorld != player.getWorld()) {
            // a world change wipes every entity on the client: start over without remove packets
            parts.clear();
            freeParts.clear();
            shown.clear();
            matrices.clear();
            partWorld = player.getWorld();
        }
        List<Integer> removed = new ArrayList<>();
        Set<String> keep = new HashSet<>();
        Location eye = player.getEyeLocation();
        for (Decal d : decals) {
            if (d.alpha() < minAlpha) continue;
            int level = (int) Math.max(0, Math.min(LEVELS - 1, Math.ceil(d.alpha() * LEVELS) - 1));
            int rgb = colours.getOrDefault(d.id(), 0xFFFFFF);
            String look = d.kind().model + ":" + level + ":" + rgb;
            keep.add(d.id());
            PacketDisplays.Part p = parts.get(d.id());
            boolean had = p != null;
            if (p != null && !fits(p, d, eye)) {
                removed.add(p.id);
                parts.remove(d.id());
                p = null;
            }
            boolean fresh = false;
            if (p == null) {
                p = takeFree(d, eye, removed);
                if (p == null) {
                    // billboards turn around their anchor, so they are anchored exactly where they are drawn
                    p = PacketDisplays.create(player, d.x(), d.y(), d.z());
                    fresh = true;
                }
                parts.put(d.id(), p);
                shown.remove(d.id());
                matrices.remove(d.id());
                had = false;
            }
            if (d.billboard() && !fresh && (p.x != d.x() || p.y != d.y() || p.z != d.z())) {
                // a billboard is drawn at its anchor: moving lights (beam pearls, the ambient glow) move the entity,
                // gliding when it was already showing this decal, jumping when it was just taken from the pool
                PacketDisplays.move(p, d.x(), d.y(), d.z(), had ? d.interp() : 0, out);
                matrices.remove(d.id());
            }
            if (!look.equals(shown.get(d.id()))) {
                PacketDisplays.item(p, item(d.kind(), level, rgb));
                shown.put(d.id(), look);
            }
            PacketDisplays.billboard(p, d.billboard());
            Matrix4f m = new Matrix4f().translation((float) (d.x() - p.x), (float) (d.y() - p.y), (float) (d.z() - p.z))
                    .mul(d.transform());
            Matrix4f last = matrices.get(d.id());
            if (last == null || !last.equals(m, 1e-4f)) {
                // a decal that just got this entity jumps there; one that was already here glides
                PacketDisplays.transform(p, m, last != null ? d.interp() : 0);
                matrices.put(d.id(), m);
            }
            if (fresh) PacketDisplays.spawnPackets(p, out);
            else PacketDisplays.updatePackets(p, out);
        }
        var it = parts.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (keep.contains(en.getKey())) continue;
            PacketDisplays.Part p = en.getValue();
            it.remove();
            shown.remove(en.getKey());
            matrices.remove(en.getKey());
            if (freeParts.size() < poolMax) {
                PacketDisplays.item(p, ItemStack.empty());
                PacketDisplays.updatePackets(p, out);
                freeParts.push(p);
            } else {
                removed.add(p.id);
            }
        }
        // idle entities far behind the player are dropped rather than kept
        freeParts.removeIf(p -> {
            double dx = p.x - eye.getX(), dy = p.y - eye.getY(), dz = p.z - eye.getZ();
            if (dx * dx + dy * dy + dz * dz < 64 * 64) return false;
            removed.add(p.id);
            return true;
        });
        if (!removed.isEmpty()) PacketDisplays.removePacket(removed.stream().mapToInt(Integer::intValue).toArray(), out);
        PacketDisplays.sendAll(player, out);
    }

    /** Can this entity show the decal without being re-spawned? */
    private boolean fits(PacketDisplays.Part p, Decal d, Location eye) {
        double dx = d.x() - p.x, dy = d.y() - p.y, dz = d.z() - p.z;
        if (d.billboard() && !PacketDisplays.canMove()) return dx * dx + dy * dy + dz * dz < 1e-6;
        double ex = p.x - eye.getX(), ey = p.y - eye.getY(), ez = p.z - eye.getZ();
        return dx * dx + dy * dy + dz * dz < MAX_OFFSET * MAX_OFFSET && ex * ex + ey * ey + ez * ez < 48 * 48;
    }

    private PacketDisplays.Part takeFree(Decal d, Location eye, List<Integer> removed) {
        var it = freeParts.iterator();
        while (it.hasNext()) {
            PacketDisplays.Part p = it.next();
            if (fits(p, d, eye)) {
                it.remove();
                return p;
            }
        }
        return null;
    }

    private void applyEntities(List<Decal> decals, Map<String, Integer> colours, double minAlpha, int poolMax) {
        Set<String> keep = new HashSet<>();
        for (Decal d : decals) {
            if (d.alpha() < minAlpha) continue;
            int level = (int) Math.max(0, Math.min(LEVELS - 1, Math.ceil(d.alpha() * LEVELS) - 1));
            int rgb = colours.getOrDefault(d.id(), 0xFFFFFF);
            String look = d.kind().model + ":" + level + ":" + rgb;
            keep.add(d.id());
            ItemDisplay e = active.get(d.id());
            if (e == null || !e.isValid()) {
                e = obtain(d);
                if (e == null) continue;
                active.put(d.id(), e);
                shown.remove(d.id());
                matrices.remove(d.id());
            } else if (moved(e, d)) {
                e.teleport(new Location(e.getWorld(), d.x(), d.y(), d.z()));
            }
            if (!look.equals(shown.get(d.id()))) {
                e.setItemStack(item(d.kind(), level, rgb));
                shown.put(d.id(), look);
            }
            Matrix4f last = matrices.get(d.id());
            if (last == null || !last.equals(d.transform(), 1e-4f)) {
                if (last != null && d.interp() > 0) {
                    e.setInterpolationDelay(0);
                    e.setInterpolationDuration(d.interp());
                } else {
                    e.setInterpolationDuration(0);
                }
                e.setTransformationMatrix(d.transform());
                matrices.put(d.id(), new Matrix4f(d.transform()));
            }
            e.setBillboard(d.billboard() ? Display.Billboard.CENTER : Display.Billboard.FIXED);
            e.setTeleportDuration(d.interp());
        }
        // what is no longer wanted goes back to the pool, invisible (no item = nothing drawn)
        var it = active.entrySet().iterator();
        while (it.hasNext()) {
            var en = it.next();
            if (keep.contains(en.getKey())) continue;
            ItemDisplay e = en.getValue();
            shown.remove(en.getKey());
            matrices.remove(en.getKey());
            it.remove();
            if (e.isValid() && free.size() < poolMax) {
                e.setItemStack(ItemStack.empty());
                free.push(e);
            } else if (e.isValid()) {
                e.remove();
            }
        }
    }

    private boolean moved(Entity e, Decal d) {
        Location l = e.getLocation();
        if (l.getWorld() != player.getWorld()) return true;
        double dx = l.getX() - d.x(), dy = l.getY() - d.y(), dz = l.getZ() - d.z();
        return dx * dx + dy * dy + dz * dz > 1e-6;
    }

    private ItemDisplay obtain(Decal d) {
        Location at = new Location(player.getWorld(), d.x(), d.y(), d.z());
        if (!at.isChunkLoaded()) return null;
        while (!free.isEmpty()) {
            ItemDisplay e = free.pop();
            if (!e.isValid()) continue;
            e.setTeleportDuration(0);
            if (e.getWorld() != at.getWorld()) {
                e.remove();
                continue;
            }
            e.teleport(at);
            return e;
        }
        ItemDisplay e = at.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBrightness(new Display.Brightness(15, 15));
            x.setShadowRadius(0f);
            x.setShadowStrength(0f);
            // 0 = never culled by the client's frustum test (the decal can be much bigger than the entity box)
            x.setDisplayWidth(0f);
            x.setDisplayHeight(0f);
            x.setViewRange(2.0f);
        });
        player.showEntity(plugin, e);
        return e;
    }

    private static ItemStack item(Decal.Kind kind, int level, int rgb) {
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key("lighting", kind.model));
        it.setData(DataComponentTypes.DYED_COLOR, io.papermc.paper.datacomponent.item.DyedItemColor.dyedItemColor(
                Color.fromRGB(rgb & 0xFFFFFF)));
        it.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addFloat(level).build());
        return it;
    }

    // ------------------------------------------------------------------ text fallback (no pack)

    /** Vanilla-only fallback: a soft coloured "●" floating at each coloured light (no textures needed). */
    void glyphs(List<Emitter> lights, Map<String, Integer> colours) {
        Set<String> keep = new HashSet<>();
        for (Emitter e : lights) {
            keep.add(e.key());
            TextDisplay t = glyphs.get(e.key());
            Location at = new Location(e.world(), e.x(), e.y() - 0.15, e.z());
            if (t == null || !t.isValid()) {
                if (!at.isChunkLoaded()) continue;
                t = at.getWorld().spawn(at, TextDisplay.class, x -> {
                    x.setPersistent(false);
                    x.setVisibleByDefault(false);
                    x.setBillboard(Display.Billboard.CENTER);
                    x.setBrightness(new Display.Brightness(15, 15));
                    x.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                    x.setDefaultBackground(false);
                    x.setShadowed(false);
                    x.setTextOpacity((byte) 170);
                    x.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                            new org.joml.AxisAngle4f(), new org.joml.Vector3f(2.2f), new org.joml.AxisAngle4f()));
                });
                player.showEntity(plugin, t);
                glyphs.put(e.key(), t);
            } else if (t.getLocation().distanceSquared(at) > 1e-4) {
                t.teleport(at);
            }
            t.text(Component.text("●").color(TextColor.color(colours.getOrDefault(e.key(), e.rgb()))));
        }
        glyphs.entrySet().removeIf(en -> {
            if (keep.contains(en.getKey())) return false;
            if (en.getValue().isValid()) en.getValue().remove();
            return true;
        });
    }

    // ------------------------------------------------------------------ fake light blocks

    /** Sends the wanted client-side LIGHT blocks (pos -> level) and restores the real blocks where they went away. */
    void fakes(World world, Map<Long, Integer> want, boolean resendAll) {
        Map<Position, BlockData> changes = new HashMap<>();
        if (fakeWorld != null && fakeWorld != world) {
            fakes.clear(); // the client dropped them with the old world
        }
        fakeWorld = world;
        for (var en : want.entrySet()) {
            Integer had = fakes.get(en.getKey());
            if (!resendAll && had != null && had.equals(en.getValue())) continue;
            long k = en.getKey();
            Block b = world.getBlockAt(bx(k), by(k), bz(k));
            BlockData light = Material.LIGHT.createBlockData();
            ((Levelled) light).setLevel(en.getValue());
            ((Waterlogged) light).setWaterlogged(b.getType() == Material.WATER);
            changes.put(Position.block(bx(k), by(k), bz(k)), light);
            fakes.put(k, en.getValue());
        }
        var it = fakes.entrySet().iterator();
        while (it.hasNext()) {
            long k = it.next().getKey();
            if (want.containsKey(k)) continue;
            it.remove();
            if (!world.isChunkLoaded(bx(k) >> 4, bz(k) >> 4)) continue;
            changes.put(Position.block(bx(k), by(k), bz(k)), world.getBlockAt(bx(k), by(k), bz(k)).getBlockData());
        }
        if (!changes.isEmpty()) player.sendMultiBlockChange(changes);
    }

    /** A real block changed here: our fake is gone on the client. */
    void forgetFake(long key) {
        fakes.remove(key);
    }

    // ------------------------------------------------------------------ teardown

    /** The client dropped every entity (world change, respawn): forget ours without sending removes. */
    void clientReset() {
        parts.clear();
        freeParts.clear();
        shown.clear();
        matrices.clear();
        fakes.clear();
        partWorld = null;
    }

    void clear(boolean restoreFakes) {
        if (!parts.isEmpty() || !freeParts.isEmpty()) {
            List<Object> out = new ArrayList<>();
            int[] ids = java.util.stream.Stream.concat(parts.values().stream(), freeParts.stream()).mapToInt(p -> p.id).toArray();
            try {
                PacketDisplays.removePacket(ids, out);
                if (player.isOnline()) PacketDisplays.sendAll(player, out);
            } catch (ReflectiveOperationException ignored) {
                // the client drops them on leave/world change anyway
            }
            parts.clear();
            freeParts.clear();
        }
        for (ItemDisplay e : active.values()) if (e.isValid()) e.remove();
        for (ItemDisplay e : free) if (e.isValid()) e.remove();
        for (TextDisplay t : glyphs.values()) if (t.isValid()) t.remove();
        active.clear();
        free.clear();
        glyphs.clear();
        shown.clear();
        matrices.clear();
        if (restoreFakes && fakeWorld != null && player.isOnline()) fakes(fakeWorld, Map.of(), false);
        fakes.clear();
    }

    public List<String> describe() {
        List<String> out = new ArrayList<>();
        out.add(decalCount() + " decals, " + (free.size() + freeParts.size()) + " pooled ("
                + (PacketDisplays.available() && !packetsBroken ? "packet entities" + (PacketDisplays.bundles() ? ", bundled" : "") + (PacketDisplays.canMove() ? ", moving billboards" : ", billboards re-spawn when moved")
                : "world entities" + (PacketDisplays.available() ? "" : ": " + PacketDisplays.problem())) + "), "
                + glyphs.size() + " fallback glyphs, " + fakes.size() + " fake light blocks");
        return out;
    }

    // ------------------------------------------------------------------ block keys

    public static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    static int bx(long k) {
        return (int) (k >> 38);
    }

    static int by(long k) {
        return (int) (k << 52 >> 52);
    }

    static int bz(long k) {
        return (int) (k << 26 >> 38);
    }
}
