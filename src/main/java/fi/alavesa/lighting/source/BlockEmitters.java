package fi.alavesa.lighting.source;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.LightingConfig.BlockProfile;
import fi.alavesa.lighting.render.Emitter;
import fi.alavesa.lighting.render.EmitterSource;
import fi.alavesa.lighting.render.RenderEngine;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.RespawnAnchor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.plugin.Plugin;

/**
 * Coloured light for emissive blocks that vanilla only lights in warm white: redstone lamps, soul fire, sea
 * lanterns, froglights, copper bulbs... (config blocks.profiles). Torches, glowstone, lanterns, campfires, lava and
 * the like are deliberately not listed: vanilla already gives them warm yellow light.
 *
 * <p>Chunks near players are scanned off the main thread from a {@link ChunkSnapshot} (empty sections skipped).
 * Blocks of one profile inside one 4x4x4 cell become one light at their average position, so a nether portal or an
 * amethyst geode is a handful of lights, not hundreds. Lit/charged state is checked when rendered, so a redstone
 * lamp turning on or off needs no rescan; block place/break/piston/explosion events rescan the chunk, and chunks near
 * players are rescanned every blocks.rescan-seconds anyway (commands and WorldEdit fire no block events).
 */
public final class BlockEmitters implements EmitterSource, Listener {

    /** One merged light: average position of its blocks, one of them kept to check the state. */
    record Cell(double x, double y, double z, int bx, int by, int bz, BlockProfile profile) {
    }

    private final Plugin plugin;
    private final RenderEngine engine;
    private LightingConfig cfg;
    private final Map<UUID, Map<Long, List<Cell>>> cells = new ConcurrentHashMap<>();
    private final ArrayDeque<long[]> queue = new ArrayDeque<>();
    private final Set<String> queued = new HashSet<>();
    private final Map<UUID, List<Emitter>> lastCollect = new HashMap<>();
    private final Map<UUID, Long> lastCollectTick = new HashMap<>();
    /** When each chunk was last scanned (server tick), for the periodic rescan. */
    private final Map<UUID, Map<Long, Long>> scannedAt = new HashMap<>();
    private long now;
    private int inFlight;

    public BlockEmitters(Plugin plugin, LightingConfig cfg, RenderEngine engine) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.engine = engine;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        cells.clear();
        scannedAt.clear();
        queued.clear();
        queue.clear();
        lastCollect.clear();
    }

    @Override
    public String name() {
        return "emissive blocks";
    }

    public int trackedCells() {
        int n = 0;
        for (var w : cells.values()) for (var l : w.values()) n += l.size();
        return n;
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void collect(World world, double[] viewers, double range, long tick, List<Emitter> out) {
        if (!cfg.blocksEnabled) return;
        // block lights barely change: reuse the list for a few ticks
        Long last = lastCollectTick.get(world.getUID());
        if (last != null && tick - last < cfg.blocksRefreshTicks) {
            out.addAll(lastCollect.getOrDefault(world.getUID(), List.of()));
            return;
        }
        List<Emitter> list = new ArrayList<>();
        Map<Long, List<Cell>> byChunk = cells.get(world.getUID());
        if (byChunk != null) {
            int cr = (int) Math.ceil(range / 16) + 1;
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < viewers.length; i += 3) {
                int pcx = (int) Math.floor(viewers[i]) >> 4, pcz = (int) Math.floor(viewers[i + 2]) >> 4;
                for (int cx = pcx - cr; cx <= pcx + cr; cx++) {
                    for (int cz = pcz - cr; cz <= pcz + cr; cz++) {
                        long k = chunkKey(cx, cz);
                        if (!seen.add(k)) continue;
                        List<Cell> cs = byChunk.get(k);
                        if (cs == null) continue;
                        for (Cell c : cs) {
                            if (!Sources.near(viewers, c.x, c.y, c.z, range + c.profile.radius())) continue;
                            if (!active(world, c)) continue;
                            BlockProfile p = c.profile;
                            list.add(new Emitter("b:" + c.bx + "," + c.by + "," + c.bz, world, c.x, c.y, c.z, p.rgb(),
                                    true, p.radius(), p.intensity(), cfg.blockHalos, 0, 0, 0, 0, null,
                                    true, p.anim(), c.bx * 31L + c.bz * 17L + c.by));
                        }
                    }
                }
            }
        }
        lastCollect.put(world.getUID(), list);
        lastCollectTick.put(world.getUID(), tick);
        out.addAll(list);
    }

    private boolean active(World w, Cell c) {
        Block b = w.getBlockAt(c.bx, c.by, c.bz);
        if (b.getType() != c.profile.material()) return false;
        BlockData d = b.getBlockData();
        return switch (c.profile.condition()) {
            case ALWAYS -> true;
            case LIT -> !(d instanceof Lightable l) || l.isLit();
            case CHARGED -> !(d instanceof RespawnAnchor a) || a.getCharges() > 0;
        };
    }

    // ------------------------------------------------------------------ scanning

    /** Every second: queue unscanned loaded chunks around players; every tick: start a few scans. */
    public void tick(long tick) {
        now = tick;
        if (!cfg.blocksEnabled) return;
        if (tick % 20 == 0) {
            long stale = cfg.blocksRescanTicks;
            for (Player p : Bukkit.getOnlinePlayers()) {
                World w = p.getWorld();
                Map<Long, List<Cell>> byChunk = cells.get(w.getUID());
                Map<Long, Long> at = scannedAt.get(w.getUID());
                int pcx = p.getLocation().getBlockX() >> 4, pcz = p.getLocation().getBlockZ() >> 4;
                for (int dx = -cfg.blocksScanRadius; dx <= cfg.blocksScanRadius; dx++) {
                    for (int dz = -cfg.blocksScanRadius; dz <= cfg.blocksScanRadius; dz++) {
                        int cx = pcx + dx, cz = pcz + dz;
                        long k = chunkKey(cx, cz);
                        // commands and WorldEdit change blocks without events: rescan known chunks now and then
                        boolean known = byChunk != null && byChunk.containsKey(k);
                        if (known && (stale <= 0 || at == null || tick - at.getOrDefault(k, tick) < stale)) continue;
                        if (w.isChunkLoaded(cx, cz)) enqueue(w, cx, cz);
                    }
                }
            }
        }
        int started = 0;
        while (!queue.isEmpty() && started < cfg.blocksScansPerTick && inFlight < 8) {
            long[] job = queue.poll();
            World w = worldOf(job[0], job[1]);
            queued.remove(job[0] + ":" + job[1] + ":" + job[2] + ":" + job[3]);
            if (w == null || !w.isChunkLoaded((int) job[2], (int) job[3])) continue;
            scan(w.getChunkAt((int) job[2], (int) job[3]));
            started++;
        }
    }

    private void enqueue(World w, int cx, int cz) {
        UUID u = w.getUID();
        String key = u.getMostSignificantBits() + ":" + u.getLeastSignificantBits() + ":" + cx + ":" + cz;
        if (queued.add(key)) queue.add(new long[]{u.getMostSignificantBits(), u.getLeastSignificantBits(), cx, cz});
    }

    private static World worldOf(long most, long least) {
        return Bukkit.getWorld(new UUID(most, least));
    }

    private void scan(Chunk chunk) {
        World w = chunk.getWorld();
        ChunkSnapshot snap = chunk.getChunkSnapshot(false, false, false);
        int minY = w.getMinHeight(), maxY = w.getMaxHeight();
        Map<Material, BlockProfile> profiles = cfg.blockProfiles;
        int cx = chunk.getX(), cz = chunk.getZ();
        inFlight++;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // cell key -> [sumX, sumY, sumZ, count, firstX, firstY, firstZ]
            Map<String, double[]> acc = new HashMap<>();
            Map<String, BlockProfile> prof = new HashMap<>();
            for (int sy = minY; sy < maxY; sy += 16) {
                if (snap.isSectionEmpty((sy - minY) >> 4)) continue;
                for (int y = sy; y < sy + 16 && y < maxY; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            BlockProfile p = profiles.get(snap.getBlockType(x, y, z));
                            if (p == null) continue;
                            int wx = (cx << 4) + x, wz = (cz << 4) + z, wy = y;
                            String k = p.material().ordinal() + ":" + (wx >> 2) + ":" + (y >> 2) + ":" + (wz >> 2);
                            double[] a = acc.computeIfAbsent(k, kk -> new double[]{0, 0, 0, 0, wx, wy, wz});
                            a[0] += wx + 0.5;
                            a[1] += y + 0.5;
                            a[2] += wz + 0.5;
                            a[3]++;
                            prof.putIfAbsent(k, p);
                        }
                    }
                }
            }
            List<Cell> found = new ArrayList<>();
            for (var e : acc.entrySet()) {
                double[] a = e.getValue();
                found.add(new Cell(a[0] / a[3], a[1] / a[3], a[2] / a[3], (int) a[4], (int) a[5], (int) a[6],
                        prof.get(e.getKey())));
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                inFlight--;
                if (!w.isChunkLoaded(cx, cz)) return;
                cells.computeIfAbsent(w.getUID(), k -> new ConcurrentHashMap<>()).put(chunkKey(cx, cz), found);
                scannedAt.computeIfAbsent(w.getUID(), k -> new HashMap<>()).put(chunkKey(cx, cz), now);
                lastCollectTick.remove(w.getUID());
            });
        });
    }

    private void rescan(Block b) {
        engine.blockChanged(b.getWorld(), b.getX(), b.getY(), b.getZ());
        if (!cfg.blocksEnabled) return;
        Map<Long, List<Cell>> byChunk = cells.get(b.getWorld().getUID());
        if (byChunk == null || !byChunk.containsKey(chunkKey(b.getX() >> 4, b.getZ() >> 4))) return;
        enqueue(b.getWorld(), b.getX() >> 4, b.getZ() >> 4);
    }

    // ------------------------------------------------------------------ lifecycle events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        rescan(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        // the block is still there during the event: rescan a tick later
        Block b = e.getBlock();
        Bukkit.getScheduler().runTask(plugin, () -> rescan(b));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(BlockRedstoneEvent e) {
        // lit state is read at render time; just make the next collect see it promptly
        lastCollectTick.remove(e.getBlock().getWorld().getUID());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        later(e.getBlocks(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        later(e.getBlocks(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        later(List.of(e.getBlock()), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        later(e.blockList(), e.getLocation().getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        later(e.blockList(), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        later(List.of(e.getBlock()), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        later(List.of(e.getBlock()), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        later(List.of(e.getBlock()), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(BlockFormEvent e) {
        later(List.of(e.getBlock()), e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(PortalCreateEvent e) {
        List<Block> bs = new ArrayList<>();
        for (var st : e.getBlocks()) bs.add(st.getBlock());
        if (!bs.isEmpty()) later(bs, bs.getFirst());
    }

    private void later(List<Block> blocks, Block extra) {
        List<Block> all = new ArrayList<>(blocks);
        all.add(extra);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Set<Long> done = new HashSet<>();
            for (Block b : all) if (done.add(chunkKey(b.getX() >> 4, b.getZ() >> 4))) rescan(b);
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        if (!cfg.blocksEnabled) return;
        Chunk c = e.getChunk();
        for (Player p : e.getWorld().getPlayers()) {
            int dx = Math.abs((p.getLocation().getBlockX() >> 4) - c.getX());
            int dz = Math.abs((p.getLocation().getBlockZ() >> 4) - c.getZ());
            if (dx <= cfg.blocksScanRadius && dz <= cfg.blocksScanRadius) {
                enqueue(e.getWorld(), c.getX(), c.getZ());
                return;
            }
        }
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        Map<Long, List<Cell>> byChunk = cells.get(e.getWorld().getUID());
        if (byChunk != null) byChunk.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
        Map<Long, Long> at = scannedAt.get(e.getWorld().getUID());
        if (at != null) at.remove(chunkKey(e.getChunk().getX(), e.getChunk().getZ()));
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }
}
