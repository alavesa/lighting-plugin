package fi.alavesa.lighting.wand;

import com.destroystokyo.paper.ParticleBuilder;
import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.light.LightSource;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

/**
 * Every particle and sound the wand makes. Preview geometry is sent to the holder only; the trail, swing arc and
 * place/remove bursts are seen by everyone nearby.
 *
 * <p>Preview sphere: three great circles (XY, YZ, and one tilted 45 degrees) slowly spinning about Y, plus one
 * latitude ring sweeping pole to pole every 3 s, all at the exact radius, in the light colour (dust 0.6..1.1 px
 * size, scaled by radius). A bright core marks the centre. While resizing, an equator ring eases from the old
 * radius to the new one over 10 ticks with sparks along it.
 */
public final class WandVfx {

    private static final Color WHITE = Color.fromRGB(0xFFFFFF);
    private static final double GOLDEN = Math.PI * (3 - Math.sqrt(5));
    private static final int RESIZE_TICKS = 10;

    private final Plugin plugin;
    private LightingConfig cfg;

    public WandVfx(Plugin plugin, LightingConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------ preview (holder only)

    /**
     * @param resizeT 0..1 progress of the resize animation, or -1 when not resizing
     * @param fromR   radius the animation starts from
     */
    public void sphere(Player viewer, Location c, double r, int rgb, long tick, double resizeT, double fromR,
                       boolean editing) {
        Color col = Color.fromRGB(rgb & 0xFFFFFF);
        float size = (float) Math.min(1.1, 0.6 + r * 0.025);
        Particle.DustOptions dust = new Particle.DustOptions(col, size);
        int n = (int) Math.max(24, Math.min(cfg.previewMaxPoints, 2 * Math.PI * r * cfg.previewPointsPerBlock));
        double spin = tick * 0.02;
        // great circles: normals around the sphere, spun about Y
        Vector[] normals = {new Vector(0, 0, 1), new Vector(1, 0, 0), new Vector(1, 1, 0).normalize()};
        for (Vector n0 : normals) {
            Vector nrm = n0.clone().rotateAroundY(spin);
            ring(viewer, c, nrm, r, n, dust);
        }
        // sweeping latitude ring
        double lat = Math.sin(tick * (2 * Math.PI / 60.0)) * r * 0.95;
        double lr = Math.sqrt(Math.max(0, r * r - lat * lat));
        ring(viewer, c.clone().add(0, lat, 0), new Vector(0, 1, 0), lr, Math.max(12, (int) (n * lr / r)), dust);
        // core
        Particle.DustOptions core = new Particle.DustOptions(editing ? WHITE : col, 1.6f);
        viewer.spawnParticle(Particle.DUST, c, 2, 0.04, 0.04, 0.04, 0, core);
        if (tick % 4 == 0) viewer.spawnParticle(Particle.END_ROD, c, 1, 0.05, 0.05, 0.05, 0.002);

        if (resizeT >= 0) {
            double e = 1 - Math.pow(1 - resizeT, 3); // ease-out cubic
            double rr = fromR + (r - fromR) * e;
            Particle.DustOptions bright = new Particle.DustOptions(mix(col, WHITE, 0.5), size + 0.4f);
            ring(viewer, c, new Vector(0, 1, 0), rr, n * 2, bright);
            for (int i = 0; i < 6; i++) {
                double a = Math.random() * Math.PI * 2;
                viewer.spawnParticle(Particle.ELECTRIC_SPARK, c.clone().add(Math.cos(a) * rr, 0, Math.sin(a) * rr), 1,
                        0, 0, 0, 0);
            }
        }
    }

    /** Small dot on every light near the holder, so lights can be found and aimed at. */
    public void lightDot(Player viewer, LightSource l, Location at) {
        viewer.spawnParticle(Particle.DUST, at, 1, 0.02, 0.02, 0.02, 0,
                new Particle.DustOptions(Color.fromRGB(l.rgb() & 0xFFFFFF), 1.2f));
    }

    private static void ring(Player viewer, Location c, Vector normal, double r, int n, Particle.DustOptions dust) {
        Vector a = normal.clone().crossProduct(Math.abs(normal.getY()) < 0.9 ? new Vector(0, 1, 0) : new Vector(1, 0, 0))
                .normalize();
        Vector b = normal.clone().crossProduct(a).normalize();
        Location p = c.clone();
        for (int i = 0; i < n; i++) {
            double t = i * (2 * Math.PI / n);
            double ca = Math.cos(t) * r, sb = Math.sin(t) * r;
            p.setX(c.getX() + a.getX() * ca + b.getX() * sb);
            p.setY(c.getY() + a.getY() * ca + b.getY() * sb);
            p.setZ(c.getZ() + a.getZ() * ca + b.getZ() * sb);
            viewer.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0, dust);
        }
    }

    public void resizeTick(Player p, double r) {
        float pitch = (float) LightingConfig.clamp(0.5 + (r - cfg.radiusMin) / Math.max(1, cfg.radiusMax - cfg.radiusMin) * 1.5,
                0.5, 2.0);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_HIT, SoundCategory.PLAYERS, 0.6f, pitch);
    }

    // ------------------------------------------------------------------ trail (everyone nearby)

    /** Where the wand tip roughly is in third person: right hand, a little forward and down from the eyes. */
    public static Location tip(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        return eye.add(dir.multiply(0.55)).add(right.multiply(0.38)).add(0, -0.32, 0);
    }

    public void trail(Player p, int rgb, long tick) {
        if (!cfg.trail) return;
        Location tip = tip(p);
        Color col = Color.fromRGB(rgb & 0xFFFFFF);
        new ParticleBuilder(Particle.DUST).location(tip).count(1).offset(0.04, 0.04, 0.04).extra(0)
                .data(new Particle.DustOptions(col, 0.55f)).receivers((int) cfg.trailVisibleTo).spawn();
        if (tick % 6 == 0) {
            new ParticleBuilder(Particle.WAX_ON).location(tip).count(1).offset(0.05, 0.05, 0.05).extra(0)
                    .receivers((int) cfg.trailVisibleTo).spawn();
        }
    }

    /** A swept arc of light in front of the player, from upper right to lower left. */
    public void swing(Player p, int rgb) {
        if (!cfg.swingArc) return;
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(dir).normalize();
        Color col = Color.fromRGB(rgb & 0xFFFFFF);
        Particle.DustTransition fade = new Particle.DustTransition(col, WHITE, 0.9f);
        World w = p.getWorld();
        int steps = 14;
        for (int i = 0; i < steps; i++) {
            double t = i / (double) (steps - 1);
            double s = 1 - 2 * t; // +1 upper right .. -1 lower left, bowed forward in the middle
            Vector off = right.clone().multiply(s * 0.9).add(up.clone().multiply(s * 0.45 - 0.1));
            Location at = eye.clone().add(dir.clone().multiply(1.0 + Math.sin(t * Math.PI) * 0.3)).add(off);
            new ParticleBuilder(Particle.DUST_COLOR_TRANSITION).location(at).count(1).extra(0).data(fade)
                    .receivers((int) cfg.trailVisibleTo).spawn();
            if (i % 4 == 0) {
                new ParticleBuilder(Particle.ELECTRIC_SPARK).location(at).count(1).extra(0.02)
                        .receivers((int) cfg.trailVisibleTo).spawn();
            }
        }
        w.playSound(eye, Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 0.25f, 1.9f);
    }

    // ------------------------------------------------------------------ bursts

    /**
     * Placement: 28 light rays (trail particles) from the centre to a fibonacci sphere at 60% of the radius, an
     * expanding dust shell over 4 frames (every 2 ticks), 24 fireworks sparks, a glow flash; chime + beacon select.
     */
    public void placed(Location c, double r, int rgb) {
        World w = c.getWorld();
        Color col = Color.fromRGB(rgb & 0xFFFFFF);
        int rays = 28;
        for (int i = 0; i < rays; i++) {
            Vector d = fib(i, rays).multiply(Math.max(1.2, r * 0.6));
            new ParticleBuilder(Particle.TRAIL).location(c).count(1).extra(0)
                    .data(new Particle.Trail(c.clone().add(d), col, 14)).receivers(48).spawn();
        }
        new ParticleBuilder(Particle.FIREWORK).location(c).count(24).offset(0.1, 0.1, 0.1).extra(0.18).receivers(48).spawn();
        new ParticleBuilder(Particle.GLOW).location(c).count(12).offset(0.25, 0.25, 0.25).extra(0).receivers(48).spawn();
        for (int frame = 0; frame < 4; frame++) {
            final double f = (frame + 1) / 4.0;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                double rr = Math.max(0.6, r * 0.75 * f);
                Particle.DustOptions dust = new Particle.DustOptions(mix(col, WHITE, 0.6 * (1 - f)), (float) (1.6 - f * 0.8));
                int pts = 40;
                for (int i = 0; i < pts; i++) {
                    new ParticleBuilder(Particle.DUST).location(c.clone().add(fib(i, pts).multiply(rr))).count(1).extra(0)
                            .data(dust).receivers(48).spawn();
                }
            }, frame * 2L);
        }
        w.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.BLOCKS, 1.0f, 1.3f);
        w.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.BLOCKS, 0.5f, 1.8f);
    }

    /** Edit applied: a quick single shell pulse at the new radius. */
    public void edited(Location c, double r, int rgb) {
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(rgb & 0xFFFFFF), 1.1f);
        int pts = 60;
        for (int i = 0; i < pts; i++) {
            new ParticleBuilder(Particle.DUST).location(c.clone().add(fib(i, pts).multiply(r))).count(1).extra(0)
                    .data(dust).receivers(48).spawn();
        }
        c.getWorld().playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.BLOCKS, 0.8f, 1.5f);
    }

    /** Removal: trails collapse from a 1.5-block shell into the centre, then a puff. */
    public void removed(Location c, int rgb) {
        Color col = Color.fromRGB(rgb & 0xFFFFFF);
        int n = 24;
        for (int i = 0; i < n; i++) {
            Location from = c.clone().add(fib(i, n).multiply(1.5));
            new ParticleBuilder(Particle.TRAIL).location(from).count(1).extra(0)
                    .data(new Particle.Trail(c, col, 10)).receivers(48).spawn();
        }
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> new ParticleBuilder(Particle.SMOKE).location(c)
                .count(10).offset(0.1, 0.1, 0.1).extra(0.02).receivers(48).spawn(), 10L);
        c.getWorld().playSound(c, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.BLOCKS, 0.7f, 1.6f);
    }

    public void denied(Player p) {
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 0.5f);
    }

    private static Vector fib(int i, int n) {
        double y = 1 - (i + 0.5) * 2.0 / n;
        double rad = Math.sqrt(1 - y * y);
        double th = GOLDEN * i;
        return new Vector(Math.cos(th) * rad, y, Math.sin(th) * rad);
    }

    private static Color mix(Color a, Color b, double t) {
        return Color.fromRGB((int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    public static int resizeTicks() {
        return RESIZE_TICKS;
    }
}
