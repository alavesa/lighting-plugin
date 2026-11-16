package fi.alavesa.lighting.source;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.LightingConfig.HeldProfile;
import fi.alavesa.lighting.render.Anim;
import fi.alavesa.lighting.render.Emitter;
import fi.alavesa.lighting.render.EmitterSource;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Dynamic light around players holding (either hand) or wearing (helmet) light items: a client-side LIGHT block at
 * their head for everyone nearby, and coloured pools for non-yellow items (soul torch, sea lantern...).
 *
 * <p>Any item can be made a light with the PDC string {@code lighting:light} = "#RRGGBB;radius;level"
 * (colour "none" = brightness only), e.g. from another plugin or /lighting give.
 */
public final class HeldLights implements EmitterSource {

    private final NamespacedKey tag;
    private LightingConfig cfg;

    public HeldLights(Plugin plugin, LightingConfig cfg) {
        this.tag = new NamespacedKey(plugin, "light");
        this.cfg = cfg;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    public NamespacedKey tag() {
        return tag;
    }

    @Override
    public String name() {
        return "held / worn";
    }

    @Override
    public void collect(World world, double[] viewers, double range, long tick, List<Emitter> out) {
        if (!cfg.heldEnabled) return;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR) continue;
            HeldProfile best = null;
            for (ItemStack it : new ItemStack[]{p.getInventory().getItemInMainHand(), p.getInventory().getItemInOffHand(),
                    p.getInventory().getHelmet()}) {
                HeldProfile h = profile(it);
                if (h != null && (best == null || h.level() > best.level())) best = h;
            }
            if (best == null) continue;
            Location eye = p.getEyeLocation();
            if (!Sources.near(viewers, eye.getX(), eye.getY(), eye.getZ(), range)) continue;
            Block b = eye.getBlock();
            if (!b.getType().isAir()) b = p.getLocation().getBlock();
            out.add(new Emitter("h:" + p.getUniqueId(), world, eye.getX(), eye.getY() - 0.45, eye.getZ(),
                    best.rgb() < 0 ? 0xFFFFFF : best.rgb(), best.rgb() >= 0, best.radius(), 1.0, false, best.level(),
                    b.getX(), b.getY(), b.getZ(), null, false, Anim.NONE, p.getUniqueId().getLeastSignificantBits()));
        }
    }

    private @Nullable HeldProfile profile(@Nullable ItemStack it) {
        if (it == null || it.isEmpty()) return null;
        String custom = it.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        if (custom != null) {
            String[] a = custom.split(";");
            int rgb = a[0].equalsIgnoreCase("none") ? -1 : LightingConfig.parseColor(a[0], -1);
            double r = a.length > 1 ? parse(a[1], 5) : 5;
            int lvl = a.length > 2 ? (int) parse(a[2], 12) : 12;
            return new HeldProfile(it.getType(), rgb, r, Math.max(0, Math.min(15, lvl)));
        }
        Material m = it.getType();
        return cfg.heldProfiles.get(m);
    }

    private static double parse(String s, double d) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
