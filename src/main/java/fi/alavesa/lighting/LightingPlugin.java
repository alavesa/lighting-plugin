package fi.alavesa.lighting;

import fi.alavesa.lighting.light.LightRegistry;
import fi.alavesa.lighting.light.LightStore;
import fi.alavesa.lighting.pack.PackService;
import fi.alavesa.lighting.render.RenderEngine;
import fi.alavesa.lighting.source.BlockEmitters;
import fi.alavesa.lighting.source.FirearmsHook;
import fi.alavesa.lighting.source.Flashlights;
import fi.alavesa.lighting.source.HeldLights;
import fi.alavesa.lighting.source.WandLights;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Coloured lights for vanilla clients, without shaders.
 *
 * <p>Sources (wand lights, emissive blocks, held/worn items, flashlights) list their lights each tick. The
 * {@link RenderEngine} turns them, per player, into soft tinted decals (item_display entities only that player
 * sees, using the pack's alpha-gradient models) projected onto the surrounding surfaces, plus client-side LIGHT
 * blocks for real brightness. Players without the pack get the brightness only.
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
    private RenderEngine engine;
    private BlockEmitters blocks;
    private HeldLights held;
    private Flashlights flashlights;
    private FirearmsHook firearms;
    private PlayerUi ui;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migratePackDelivery();
        migrateConfig();
        cfg = new LightingConfig(getConfig(), getLogger());

        registry = new LightRegistry(cfg);
        store = new LightStore(this);
        int n = store.load(registry, cfg);
        registry.onChange(() -> store.markDirty(registry));

        pack = new PackService(getLogger(), getFile(), getDataFolder().toPath(), cfg, (p, st) -> ui.status(p, st));
        pack.start();

        engine = new RenderEngine(this, cfg, p -> ui.packActive(p));
        firearms = new FirearmsHook(cfg);
        blocks = new BlockEmitters(this, cfg, engine);
        held = new HeldLights(this, cfg);
        flashlights = new Flashlights(this, cfg, firearms, engine);
        engine.addSource(new WandLights(registry));
        engine.addSource(blocks);
        engine.addSource(held);
        engine.addSource(flashlights);
        ui = new PlayerUi(this, cfg, engine, pack, blocks, flashlights, firearms);

        wand = new Wand(this, cfg);
        vfx = new WandVfx(this, cfg);
        dialogs = new LightDialogs(this, cfg);
        wandListener = new WandListener(this, cfg, registry, wand, vfx, dialogs);

        var pm = getServer().getPluginManager();
        pm.registerEvents(registry, this);
        pm.registerEvents(wandListener, this);
        pm.registerEvents(pack, this);
        pm.registerEvents(blocks, this);
        pm.registerEvents(flashlights, this);
        pm.registerEvents(this, this);

        LightingCommand cmd = new LightingCommand(this);
        Objects.requireNonNull(getCommand("lighting")).setExecutor(cmd);
        Objects.requireNonNull(getCommand("lighting")).setTabCompleter(cmd);

        getServer().getScheduler().runTaskTimer(this, () -> {
            wandListener.tick();
            engine.tick();
            blocks.tick(engine.tickCount());
        }, 1L, 1L);

        getLogger().info("Loaded " + n + " lights, " + cfg.blockProfiles.size() + " emissive block profiles, "
                + cfg.heldProfiles.size() + " held light items. Firearms "
                + (firearms.present() ? "found: flashlight attachments enabled" : "not installed") + ".");
    }

    @Override
    public void onDisable() {
        if (engine != null) engine.shutdown();
        if (registry != null) store.saveNow(registry);
        if (pack != null) pack.stop();
    }

    void reloadAll() {
        reloadConfig();
        cfg = new LightingConfig(getConfig(), getLogger());
        registry.reload(cfg);
        engine.reload(cfg);
        blocks.reload(cfg);
        held.reload(cfg);
        flashlights.reload(cfg);
        firearms.reload(cfg);
        ui.reload(cfg);
        wand.reload(cfg);
        vfx.reload(cfg);
        dialogs.reload(cfg);
        wandListener.reload(cfg);
        pack.reload(cfg);
        for (Player p : getServer().getOnlinePlayers()) engine.settingsChanged(p);
    }

    /**
     * 0.2: configs written by 0.1 say pack.delivery: http on port 8165, which players behind a firewall never reach
     * (no coloured light). An untouched default is switched to auto (served on the Minecraft port) once.
     */
    private void migratePackDelivery() {
        var c = getConfig();
        if ("http".equalsIgnoreCase(c.getString("pack.delivery")) && c.getInt("pack.http.port", 8165) == 8165
                && !c.getBoolean("pack.delivery-migrated", false)) {
            c.set("pack.delivery", "auto");
            c.set("pack.delivery-migrated", true);
            saveConfig();
            getLogger().info("pack.delivery: http -> auto: the Lighting pack is now served on the Minecraft port. "
                    + "Set pack.delivery: http to go back to port 8165.");
        }
    }

    /**
     * 0.3: configs from 0.1/0.2 lack the block/held/flashlight sections (Bukkit then hands out EMPTY sections, not the
     * jar's defaults), and still have the shader section. Copy every missing default in, drop what is obsolete.
     */
    private void migrateConfig() {
        var c = getConfig();
        boolean old = c.contains("shader", true) || !c.contains("blocks.profiles", true);
        if (!old) return;
        c.set("shader", null);
        c.set("lights.vanilla-light-block", null);
        c.set("lights.marker-view-range", null);
        c.set("pack.required", null);
        c.options().copyDefaults(true);
        saveConfig();
        getLogger().info("config.yml updated for 0.3 (no shaders): added emissive blocks, held lights, flashlights, "
                + "quality presets; removed the shader section.");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        engine.viewer(e.getPlayer());
        if (pack.delivery().equals("none")) ui.joinedWithoutOwnPack(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        engine.quit(e.getPlayer());
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

    RenderEngine engine() {
        return engine;
    }

    Flashlights flashlights() {
        return flashlights;
    }

    PlayerUi ui() {
        return ui;
    }
}
