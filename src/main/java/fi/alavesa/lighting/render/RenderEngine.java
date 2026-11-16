package fi.alavesa.lighting.render;

import fi.alavesa.lighting.LightingConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * The per-tick loop. Once per tick: every source lists its lights near players (shared work). Then for each viewer
 * whose preset is due this tick: cull (distance, view cone), rank, cap at the preset's light count, project to
 * decals, tint with the viewer's palette, and hand the result to their {@link ViewerState}. Fake LIGHT blocks are
 * worked out the same way but are never capped by the decal budget and also work without the pack.
 */
public final class RenderEngine {

    private final Plugin plugin;
    private final List<EmitterSource> sources = new ArrayList<>();
    private final Projector projector = new Projector();
    private final Map<UUID, ViewerState> viewers = new HashMap<>();
    private final Predicate<Player> packActive;
    private LightingConfig cfg;
    private long tick;

    public RenderEngine(Plugin plugin, LightingConfig cfg, Predicate<Player> packActive) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.packActive = packActive;
        projector.cacheTicks(cfg.projectionCacheTicks);
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        projector.cacheTicks(cfg.projectionCacheTicks);
        projector.clear();
    }

    public void addSource(EmitterSource s) {
        sources.add(s);
    }

    public Projector projector() {
        return projector;
    }

    public ViewerState viewer(Player p) {
        return viewers.computeIfAbsent(p.getUniqueId(),
                k -> new ViewerState(plugin, p, PlayerSettings.load(plugin, p, cfg.defaultQuality)));
    }

    public void quit(Player p) {
        ViewerState v = viewers.remove(p.getUniqueId());
        if (v != null) v.clear(false);
    }

    public void shutdown() {
        for (ViewerState v : viewers.values()) v.clear(true);
        viewers.clear();
    }

    /** Settings changed: drop what the player sees now; the next pass rebuilds it. */
    public void settingsChanged(Player p) {
        ViewerState v = viewer(p);
        v.settings.save(plugin, p);
        v.clear(true);
    }

    /** A real block changed: cached projections near it are stale and fake lights there were overwritten. */
    public void blockChanged(World w, int x, int y, int z) {
        projector.invalidate(w, x + 0.5, y + 0.5, z + 0.5, 1.5);
        long k = ViewerState.key(x, y, z);
        for (ViewerState v : viewers.values()) v.forgetFake(k);
    }

    public Map<String, Integer> sourceCounts(Player p) {
        Map<String, Integer> out = new HashMap<>();
        double[] at = {p.getX(), p.getEyeLocation().getY(), p.getZ()};
        for (EmitterSource s : sources) {
            List<Emitter> list = new ArrayList<>();
            s.collect(p.getWorld(), at, cfg.collectRange, tick, list);
            out.put(s.name(), list.size());
        }
        return out;
    }

    // ------------------------------------------------------------------ tick

    public void tick() {
        tick++;
        Map<World, List<Player>> byWorld = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) byWorld.computeIfAbsent(p.getWorld(), k -> new ArrayList<>()).add(p);
        boolean resend = tick % cfg.fakeResendTicks == 0;
        for (var en : byWorld.entrySet()) {
            World w = en.getKey();
            List<Player> players = en.getValue();
            double[] at = new double[players.size() * 3];
            for (int i = 0; i < players.size(); i++) {
                Location eye = players.get(i).getEyeLocation();
                at[i * 3] = eye.getX();
                at[i * 3 + 1] = eye.getY();
                at[i * 3 + 2] = eye.getZ();
            }
            List<Emitter> all = new ArrayList<>();
            for (EmitterSource s : sources) s.collect(w, at, cfg.collectRange, tick, all);
            for (Player p : players) render(viewer(p), w, all, resend);
        }
    }

    private void render(ViewerState v, World w, List<Emitter> all, boolean resend) {
        Player p = v.player();
        PlayerSettings s = v.settings;
        if (!s.enabled) {
            if (v.decalCount() > 0 || v.fakeCount() > 0) v.clear(true);
            return;
        }
        boolean pack = packActive.test(p);
        Quality q = pack ? s.quality : Quality.LOW;
        Quality.Profile prof = cfg.profile(q);
        if (tick % prof.interval() != 0) return;

        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();

        // fake light blocks: every emitter that wants one, nearest first, uncapped by the decal budget
        Map<Long, Integer> fake = new HashMap<>();
        double fd2 = cfg.fakeLightDistance * cfg.fakeLightDistance;
        for (Emitter e : all) {
            if (e.fakeLevel() <= 0 || e.distanceSq(ex, ey, ez) > fd2) continue;
            int lvl = (int) Math.round(e.fakeLevel() * (s.animations ? Math.max(0.35, e.anim().factor(tick, e.seed())) : 1));
            if (lvl <= 0 || !placeable(w, e.fakeX(), e.fakeY(), e.fakeZ())) continue;
            fake.merge(ViewerState.key(e.fakeX(), e.fakeY(), e.fakeZ()), Math.min(15, lvl), Math::max);
            if (fake.size() >= cfg.maxFakeLights) break;
        }
        v.fakes(w, fake, resend);

        if (!prof.decals()) {
            if (cfg.fallbackGlyphs) {
                List<Emitter> near = cull(all, ex, ey, ez, look, prof, true);
                Map<String, Integer> col = new HashMap<>();
                for (Emitter e : near) col.put(e.key(), s.palette.remap(e.rgb()));
                v.glyphs(near, col);
            }
            if (v.decalCount() > 0) v.apply(List.of(), Map.of(), 1, 0);
            return;
        }
        List<Emitter> visible = cull(all, ex, ey, ez, look, prof, false);
        List<Decal> decals = new ArrayList<>();
        Map<String, Integer> colours = new HashMap<>();
        for (Emitter e : visible) {
            double a = cfg.opacity * Math.min(1.6, e.intensity()) * (s.animations ? e.anim().factor(tick, e.seed()) : 1);
            int start = decals.size();
            if (e.spot() != null) {
                // moving lights interpolate over one pass, so they glide instead of stepping
                projector.spot(e, a, prof.spill(), prof.beams(), Math.max(prof.interval(), prof.flashInterp()),
                        cfg.beamLength, decals);
            } else {
                projector.point(e, tick, a, decals);
                if (prof.halos() && e.halo()) projector.halo(e, a, decals);
            }
            int rgb = s.palette.remap(e.rgb());
            for (int i = start; i < decals.size(); i++) colours.put(decals.get(i).id(), rgb);
        }
        v.apply(decals, colours, cfg.minAlpha, cfg.poolSize);
    }

    /** Coloured lights the viewer could see: within range, roughly inside the view cone, best first, capped. */
    private List<Emitter> cull(List<Emitter> all, double ex, double ey, double ez, Vector look, Quality.Profile prof,
                               boolean glyphs) {
        double cosHalf = Math.cos(Math.toRadians(cfg.frustumDegrees / 2));
        List<Emitter> out = new ArrayList<>();
        for (Emitter e : all) {
            if (!e.colored()) continue;
            if (glyphs && e.spot() != null) continue;
            double reach = e.spot() != null ? (e.spot().hit() != null ? 3 : 0) : e.radius();
            double px = e.spot() != null && e.spot().hit() != null ? e.spot().hit().getX() : e.x();
            double py = e.spot() != null && e.spot().hit() != null ? e.spot().hit().getY() : e.y();
            double pz = e.spot() != null && e.spot().hit() != null ? e.spot().hit().getZ() : e.z();
            double dx = px - ex, dy = py - ey, dz = pz - ez;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d > prof.maxDistance() + reach) continue;
            // inside the light's own reach it can surround the viewer: never cull those by angle
            if (d > reach + 1 && e.spot() == null) {
                double cos = (dx * look.getX() + dy * look.getY() + dz * look.getZ()) / d;
                double grow = Math.asin(Math.min(1, reach / d));
                if (cos < Math.cos(Math.min(Math.PI, Math.acos(cosHalf) + grow))) continue;
            }
            out.add(e);
        }
        // flashlights first (they are what the player is looking at), then by distance relative to size
        out.sort(Comparator.comparingDouble(e -> e.spot() != null ? -1
                : Math.sqrt(e.distanceSq(ex, ey, ez)) / (1 + e.radius() * e.intensity())));
        return out.size() > prof.maxLights() ? out.subList(0, prof.maxLights()) : out;
    }

    private static boolean placeable(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight() || !w.isChunkLoaded(x >> 4, z >> 4)) return false;
        Material m = w.getBlockAt(x, y, z).getType();
        return m == Material.AIR || m == Material.CAVE_AIR || m == Material.LIGHT
                || (m == Material.WATER && w.getBlockAt(x, y, z).getBlockData() instanceof org.bukkit.block.data.Levelled l
                && l.getLevel() == 0);
    }

    public Plugin pluginRef() {
        return plugin;
    }

    public long tickCount() {
        return tick;
    }
}
