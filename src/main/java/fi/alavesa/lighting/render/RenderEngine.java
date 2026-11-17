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
import org.joml.Matrix4f;

/**
 * The per-tick loop. Once per tick: every source lists its lights near players (shared work). Then for each viewer
 * whose preset is due this tick: cull (distance, view cone), rank, cap at the preset's light count, project to
 * decals, tint with the viewer's palette, and hand the result to their {@link DisplayEntityPoolManager}. Fake LIGHT blocks are
 * worked out the same way but are never capped by the decal budget and also work without the pack.
 */
public final class RenderEngine {

    private final Plugin plugin;
    private final List<EmitterSource> sources = new ArrayList<>();
    private final SurfaceDecalProjector projector = new SurfaceDecalProjector();
    private final Map<UUID, DisplayEntityPoolManager> viewers = new HashMap<>();
    private final Predicate<Player> packActive;
    private LightingConfig cfg;
    private long tick;

    public RenderEngine(Plugin plugin, LightingConfig cfg, Predicate<Player> packActive) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.packActive = packActive;
        projector.cacheTicks(cfg.projectionCacheTicks);
        projector.shadows(cfg.shadowOptions);
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
        projector.cacheTicks(cfg.projectionCacheTicks);
        projector.shadows(cfg.shadowOptions);
        projector.clear();
    }

    public void addSource(EmitterSource s) {
        sources.add(s);
    }

    public SurfaceDecalProjector projector() {
        return projector;
    }

    public DisplayEntityPoolManager viewer(Player p) {
        return viewers.computeIfAbsent(p.getUniqueId(),
                k -> new DisplayEntityPoolManager(plugin, p, PlayerSettings.load(plugin, p, cfg.defaultQuality)));
    }

    public void quit(Player p) {
        lastEye.remove(p.getUniqueId());
        DisplayEntityPoolManager v = viewers.remove(p.getUniqueId());
        if (v != null) v.clear(false);
    }

    public void shutdown() {
        for (DisplayEntityPoolManager v : viewers.values()) v.clear(true);
        viewers.clear();
    }

    /** The client dropped all entities and fake blocks (world change, respawn). */
    public void clientReset(Player p) {
        DisplayEntityPoolManager v = viewers.get(p.getUniqueId());
        if (v != null) v.clientReset();
    }

    /** Settings changed: drop what the player sees now; the next pass rebuilds it. */
    public void settingsChanged(Player p) {
        DisplayEntityPoolManager v = viewer(p);
        v.settings.save(plugin, p);
        v.clear(true);
    }

    /** A real block changed: cached projections near it are stale and fake lights there were overwritten. */
    public void blockChanged(World w, int x, int y, int z) {
        projector.invalidate(w, x + 0.5, y + 0.5, z + 0.5, 1.5);
        long k = DisplayEntityPoolManager.key(x, y, z);
        for (DisplayEntityPoolManager v : viewers.values()) v.forgetFake(k);
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

    private void render(DisplayEntityPoolManager v, World w, List<Emitter> all, boolean resend) {
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
            fake.merge(DisplayEntityPoolManager.key(e.fakeX(), e.fakeY(), e.fakeZ()), Math.min(15, lvl), Math::max);
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
                // your own beam starts at your eye: its pearls would all stack on the crosshair, so they are fainter
                // and start a little way out
                Vector o = e.spot().origin();
                boolean own = o.distanceSquared(eye.toVector()) < 1.0;
                // the old crossed-plane cone reads as an X from behind your own beam: only for other players' beams
                projector.spot(e, a, prof.spill(), prof.beams() && !own, Math.max(prof.interval(), prof.flashInterp()),
                        cfg.beamLength, prof.beamNodes(), own ? cfg.ownBeam : 1, own ? 2.0 : 0, decals);
            } else {
                projector.point(e, tick, a, decals);
                if (prof.halos() && e.halo()) projector.halo(e, a, Math.max(1, prof.haloLayers()), ex, ey, ez,
                        cfg.haloPull, cfg.haloSpread, prof.interval(), decals);
            }
            int rgb = s.palette.remap(e.rgb());
            for (int i = start; i < decals.size(); i++) {
                Decal d = decals.get(i);
                colours.put(d.id(), d.kind() == Decal.Kind.SHADOW ? 0x000000 : rgb);
            }
        }
        if (prof.ambient()) ambient(v, all, eye, look, s, decals, colours);
        v.apply(decals, colours, cfg.minAlpha, cfg.poolSize);
    }

    private final Map<UUID, Vector> lastEye = new HashMap<>();

    /**
     * Standing inside a coloured light: a faint vignette of its colour right in front of the camera, so the edges of
     * the view stay tinted when the lamp is behind you. It is a camera-facing display anchored at the eye and pushed
     * ahead in camera space, so turning never leaves it behind; the anchor follows the player's movement (led by
     * the last step and the ping, so it stays ahead of the camera when walking).
     */
    private void ambient(DisplayEntityPoolManager v, List<Emitter> all, Location eye, Vector look, PlayerSettings s,
                         List<Decal> decals, Map<String, Integer> colours) {
        Player p = v.player();
        double ex = eye.getX(), ey = eye.getY(), ez = eye.getZ();
        Emitter best = null;
        double bestW = 0;
        for (Emitter e : all) {
            if (!e.colored() || e.spot() != null || e.radius() <= 0) continue;
            double d = Math.sqrt(e.distanceSq(ex, ey, ez));
            if (d >= e.radius()) continue;
            double w = (1 - d / e.radius()) * Math.min(1.6, e.intensity());
            if (w > bestW) { bestW = w; best = e; }
        }
        Vector prev = lastEye.put(p.getUniqueId(), eye.toVector());
        if (best == null) return;
        double lead = 1 + Math.min(6, p.getPing() / 50.0);
        Vector at = eye.toVector();
        if (prev != null && prev.distanceSquared(at) < 4) at.add(at.clone().subtract(prev).multiply(lead));
        double dist = cfg.ambientDistance;
        // the quad's own edges stay off-screen at normal FOV (left/right screen edges at r ~ 0.8, where the tint is
        // nearly full; top/bottom about halfway): clear in the middle, tinted at the sides (26.3 client, FOV 70, 16:9)
        float size = (float) (dist * 2.9);
        Matrix4f m = new Matrix4f().translate(0, 0, (float) -dist).scale(size, size, 1);
        Decal d = new Decal("ambient", Decal.Kind.AMBIENT, at.getX(), at.getY(), at.getZ(), m,
                cfg.ambientAlpha * Math.min(1, bestW) * (s.animations ? best.anim().factor(tick, best.seed()) : 1), true,
                Math.max(1, cfg.profile(s.quality).interval()));
        decals.add(d);
        colours.put(d.id(), s.palette.remap(best.rgb()));
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
