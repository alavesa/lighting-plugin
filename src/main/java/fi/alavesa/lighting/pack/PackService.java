package fi.alavesa.lighting.pack;

import fi.alavesa.lighting.LightingConfig;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jspecify.annotations.Nullable;

/**
 * Builds, hosts and sends the Lighting pack as an ADDITIONAL server resource pack (Paper multi-pack, replace=false), so
 * the server's own pack keeps working. Tracks, per player, whether our pack (by its fixed UUID) loaded.
 *
 * <p>pack.delivery:
 * <ul>
 *   <li>http: served by the built-in HTTP server on pack.http.port</li>
 *   <li>url: you upload plugins/Lighting/lighting-pack.zip yourself; pack.url points at it</li>
 *   <li>none: the pack is merged into another pack; nothing is sent here</li>
 * </ul>
 */
public final class PackService implements Listener {

    /** Fixed id: clients and the status event identify our pack by it across restarts. */
    public static final UUID PACK_ID = UUID.nameUUIDFromBytes("lighting:pack".getBytes(StandardCharsets.UTF_8));

    private final Logger log;
    private final File pluginJar;
    private final Path dataFolder;
    private final Map<UUID, PlayerResourcePackStatusEvent.Status> status = new HashMap<>();
    private LightingConfig cfg;
    private PackBuilder.@Nullable Built built;
    private @Nullable PackHttpServer http;

    private final java.util.function.Consumer<Player> onLoaded;

    /** @param onLoaded called on the main thread when a player's client reports our pack loaded */
    public PackService(Logger log, File pluginJar, Path dataFolder, LightingConfig cfg,
                       java.util.function.Consumer<Player> onLoaded) {
        this.onLoaded = onLoaded;
        this.log = log;
        this.pluginJar = pluginJar;
        this.dataFolder = dataFolder;
        this.cfg = cfg;
    }

    public void start() {
        rebuild();
    }

    public void stop() {
        if (http != null) http.stop();
        http = null;
    }

    public void reload(LightingConfig cfg) {
        boolean portChanged = this.cfg.packHttpPort != cfg.packHttpPort || !this.cfg.packHttpBind.equals(cfg.packHttpBind)
                || !cfg.packDelivery.equals("http");
        this.cfg = cfg;
        if (portChanged) stop();
        String before = built == null ? "" : built.sha1();
        rebuild();
        if (built != null && !built.sha1().equals(before)) {
            for (Player p : Bukkit.getOnlinePlayers()) send(p);
        }
    }

    private void rebuild() {
        try {
            Path folder = cfg.packSourceFolder.isBlank() ? null : dataFolder.resolve(cfg.packSourceFolder);
            built = folder != null && Files.isDirectory(folder) ? PackBuilder.fromFolder(folder, Map.of())
                    : PackBuilder.fromJar(pluginJar, Map.of());
            Files.createDirectories(dataFolder);
            Files.write(dataFolder.resolve("lighting-pack.zip"), built.zip());
            log.info("Lighting pack: " + built.files() + " files, " + built.zip().length / 1024 + " KB, sha1 " + built.sha1()
                    + (folder != null && Files.isDirectory(folder) ? " (from " + folder + ")" : ""));
        } catch (IOException e) {
            log.log(Level.SEVERE, "could not build the Lighting pack", e);
            built = null;
            return;
        }
        if (cfg.packDelivery.equals("http")) {
            try {
                if (http == null) http = new PackHttpServer(cfg.packHttpBind, cfg.packHttpPort);
                http.serve(built.zip(), built.sha1());
            } catch (IOException e) {
                log.log(Level.SEVERE, "could not start the pack HTTP server on " + cfg.packHttpBind + ":"
                        + cfg.packHttpPort + " (port in use? set pack.http.port or pack.delivery: url)", e);
            }
        }
    }

    // ------------------------------------------------------------------ sending

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        status.remove(e.getPlayer().getUniqueId());
        send(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        status.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        if (!e.getID().equals(PACK_ID)) return;
        status.put(e.getPlayer().getUniqueId(), e.getStatus());
        if (e.getStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) onLoaded.accept(e.getPlayer());
    }

    public void send(Player p) {
        if (built == null || cfg.packDelivery.equals("none")) return;
        URI uri = uri(p);
        if (uri == null) return;
        ResourcePackRequest.Builder req = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, built.sha1()))
                .replace(false)
                .required(cfg.packRequired);
        if (!cfg.packPrompt.isBlank()) req.prompt(MiniMessage.miniMessage().deserialize(cfg.packPrompt));
        p.sendResourcePacks(req.build());
    }

    private @Nullable URI uri(Player p) {
        if (cfg.packDelivery.equals("url")) {
            if (cfg.packUrl.isBlank()) {
                log.warning("pack.delivery is url but pack.url is empty; not sending the pack");
                return null;
            }
            return URI.create(cfg.packUrl);
        }
        String host = cfg.packPublicHost;
        if (host.isBlank()) {
            // the host name the player typed to connect is the one that reaches this machine
            InetSocketAddress vh = p.getConnection().getVirtualHost();
            host = vh == null ? "" : vh.getHostString();
            int nul = host.indexOf('\0'); // Forge/FML markers
            if (nul >= 0) host = host.substring(0, nul);
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        }
        if (host.isBlank()) host = Bukkit.getIp().isBlank() ? "localhost" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]"; // IPv6 literal
        return URI.create("http://" + host + ":" + cfg.packHttpPort + "/lighting/" + built.sha1() + ".zip");
    }

    // ------------------------------------------------------------------ status

    /** True once the client reported our pack as successfully loaded. */
    public boolean loaded(Player p) {
        if (cfg.packDelivery.equals("none")) return true; // merged: assume the owner ships it
        return status.get(p.getUniqueId()) == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED;
    }

    public PlayerResourcePackStatusEvent.@Nullable Status status(Player p) {
        return status.get(p.getUniqueId());
    }

    public @Nullable String sha1() {
        return built == null ? null : built.sha1();
    }
}
