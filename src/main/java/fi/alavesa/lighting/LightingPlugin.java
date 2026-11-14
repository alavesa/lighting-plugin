package fi.alavesa.lighting;

import fi.alavesa.lighting.effect.LightEffect;
import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightStore;
import fi.alavesa.lighting.pack.PackService;
import fi.alavesa.lighting.wand.LightDialogs;
import fi.alavesa.lighting.wand.Wand;
import fi.alavesa.lighting.wand.WandListener;
import fi.alavesa.lighting.wand.WandVfx;
import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coloured lights for vanilla clients.
 *
 * <p>Server side a light is a record in lights.yml plus two world objects: a vanilla LIGHT block (real block light,
 * works for everyone) and a tiny item_display "marker" whose item carries the light's slot, colour, radius and
 * intensity. Client side the pack's core/item shader recognises the marker, and instead of drawing it writes that
 * data into a few pixels of the frame; the post effect lighting:colored_lights (Player.postEffects(), 26.3+) reads
 * them back, rebuilds each pixel's position from depth and adds the coloured light.
 */
public final class LightingPlugin extends JavaPlugin implements Listener {

    private LightingConfig cfg;
    private LightRegistry registry;
    private LightStore store;
    private Wand wand;
    private WandVfx vfx;
    private LightDialogs dialogs;
    private WandListener wandListener;
    private PackService pack;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        cfg = new LightingConfig(getConfig());

        registry = new LightRegistry(this, cfg);
        store = new LightStore(this);
        int n = store.load(registry, cfg);
        registry.onChange(() -> store.markDirty(registry));

        wand = new Wand(this, cfg);
        vfx = new WandVfx(this, cfg);
        dialogs = new LightDialogs(this, cfg);
        wandListener = new WandListener(this, cfg, registry, wand, vfx, dialogs);

        pack = new PackService(getLogger(), getFile(), getDataFolder().toPath(), cfg, this::packLoaded);
        pack.start();

        var pm = getServer().getPluginManager();
        pm.registerEvents(registry, this);
        pm.registerEvents(wandListener, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(this, this);

        LightingCommand cmd = new LightingCommand(this);
        Objects.requireNonNull(getCommand("lighting")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("lighting")).setTabCompleter(cmd);

        getServer().getScheduler().runTaskTimer(this, wandListener::tick, 1L, 1L);
        // markers for lights in chunks that are already loaded (spawn chunks, /reload)
        getServer().getScheduler().runTask(this, registry::materializeLoaded);

        getLogger().info("Loaded " + n + " lights. Coloured shading: "
                + (LightEffect.available() ? "post effects available (26.3+)" : "NOT available on this version, lights are "
                + "vanilla light + particles only"));
    }

    @Override
    public void onDisable() {
        if (registry != null) {
            registry.shutdown();
            store.saveNow(registry);
        }
        if (pack != null) pack.stop();
        for (Player p : getServer().getOnlinePlayers()) LightEffect.disable(p);
    }

    void reloadAll() {
        reloadConfig();
        cfg = new LightingConfig(getConfig());
        registry.reload(cfg);
        wand.reload(cfg);
        vfx.reload(cfg);
        dialogs.reload(cfg);
        wandListener.reload(cfg);
        pack.reload(cfg);
        for (Player p : getServer().getOnlinePlayers()) {
            if (shadingWanted(p)) LightEffect.enable(p);
            else LightEffect.disable(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        // a merged pack (delivery none) never reports our pack id: assume it is there
        if (cfg.packDelivery.equals("none") || !cfg.requirePackForEffect) {
            if (shadingWanted(e.getPlayer())) LightEffect.enable(e.getPlayer());
        }
    }

    private void packLoaded(Player p) {
        if (shadingWanted(p)) LightEffect.enable(p);
    }

    private boolean shadingWanted(Player p) {
        return cfg.effectEnabled && (!cfg.requirePackForEffect || pack.loaded(p));
    }

    LightRegistry registry() {
        return registry;
    }

    Wand wand() {
        return wand;
    }

    WandVfx vfx() {
        return vfx;
    }

    PackService pack() {
        return pack;
    }
}
