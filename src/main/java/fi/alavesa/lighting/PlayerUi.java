package fi.alavesa.lighting;

import fi.alavesa.lighting.pack.PackService;
import fi.alavesa.lighting.render.Palette;
import fi.alavesa.lighting.render.PlayerSettings;
import fi.alavesa.lighting.render.Quality;
import fi.alavesa.lighting.render.RenderEngine;
import fi.alavesa.lighting.render.ViewerState;
import fi.alavesa.lighting.source.BlockEmitters;
import fi.alavesa.lighting.source.FirearmsHook;
import fi.alavesa.lighting.source.Flashlights;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.DyedItemColor;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerResourcePackStatusEvent.Status;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** What players see of the plugin itself: the join status line, /lighting settings and /lighting diagnose. */
public final class PlayerUi {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final Plugin plugin;
    private final RenderEngine engine;
    private final PackService pack;
    private final BlockEmitters blocks;
    private final Flashlights flashlights;
    private final FirearmsHook firearms;
    private LightingConfig cfg;

    PlayerUi(Plugin plugin, LightingConfig cfg, RenderEngine engine, PackService pack, BlockEmitters blocks,
             Flashlights flashlights, FirearmsHook firearms) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.engine = engine;
        this.pack = pack;
        this.blocks = blocks;
        this.flashlights = flashlights;
        this.firearms = firearms;
    }

    void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    /** True when this player's client has our textures (or we can't know and assume a merged pack). */
    public boolean packActive(Player p) {
        return pack.loaded(p);
    }

    // ------------------------------------------------------------------ join status

    void status(Player p, Status s) {
        if (!cfg.joinMessage) return;
        switch (s) {
            case SUCCESSFULLY_LOADED -> p.sendMessage(MM.deserialize(
                    "<gold>Lighting</gold> <gray>» <green>Resource Pack Lighting is active</green> (quality <white>"
                            + engine.viewer(p).settings.quality.label() + "</white>). <dark_gray>/lighting settings"));
            case DECLINED, FAILED_DOWNLOAD, INVALID_URL, FAILED_RELOAD, DISCARDED -> p.sendMessage(MM.deserialize(
                    "<gold>Lighting</gold> <gray>» <yellow>Fallback mode</yellow>: the Lighting resource pack "
                            + (s == Status.DECLINED ? "was declined" : "didn't load (" + s.name().toLowerCase() + ")")
                            + ". You still get light from held items and flashlights, but no coloured light."
                            + "<newline><gray>To enable it: <white>Multiplayer → select the server → Edit → Server Resource"
                            + " Packs: Enabled</white>, then rejoin. <dark_gray>/lighting diagnose"));
            default -> {
            }
        }
    }

    /** delivery none (pack merged elsewhere): there is no status for our pack, so say it is assumed. */
    void joinedWithoutOwnPack(Player p) {
        if (!cfg.joinMessage) return;
        p.sendMessage(MM.deserialize("<gold>Lighting</gold> <gray>» Resource Pack Lighting expected from the server pack."
                + " If lights look like purple-black squares, run <white>/lighting diagnose</white>."));
    }

    // ------------------------------------------------------------------ settings

    void openSettings(Player p) {
        PlayerSettings s = engine.viewer(p).settings;
        List<SingleOptionDialogInput.OptionEntry> qs = new ArrayList<>();
        for (Quality q : Quality.values()) qs.add(SingleOptionDialogInput.OptionEntry.create(q.name(), Component.text(q.label()), q == s.quality));
        List<SingleOptionDialogInput.OptionEntry> ps = new ArrayList<>();
        for (Palette pa : Palette.values()) ps.add(SingleOptionDialogInput.OptionEntry.create(pa.name(), Component.text(pa.label()), pa == s.palette));
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<gold>Lighting settings"))
                        .canCloseWithEscape(true).pause(false)
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(
                                "<gray>Low = no coloured light, only brightness (cheapest). Medium/High/Ultra draw more "
                                        + "and nicer light layers. Colour vision modes change light colours for you only."), 300)))
                        .inputs(List.of(
                                DialogInput.bool("enabled", Component.text("Custom lighting")).initial(s.enabled).build(),
                                DialogInput.singleOption("quality", Component.text("Quality"), qs).width(300).build(),
                                DialogInput.bool("anim", Component.text("Flicker / pulse / strobe animations")).initial(s.animations).build(),
                                DialogInput.singleOption("palette", Component.text("Colour vision"), ps).width(300).build(),
                                DialogInput.bool("flash", Component.text("My flashlight on")).initial(s.flashlightOn).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(MM.deserialize("<green>Save"), null, 120, DialogAction.customClick((view, who) ->
                                Bukkit.getScheduler().runTask(plugin, () -> {
                                    Boolean en = view.getBoolean("enabled"), an = view.getBoolean("anim"), fl = view.getBoolean("flash");
                                    Quality q = Quality.parse(String.valueOf(view.getText("quality")));
                                    Palette pa = Palette.parse(String.valueOf(view.getText("palette")));
                                    if (en != null) s.enabled = en;
                                    if (an != null) s.animations = an;
                                    if (fl != null) s.flashlightOn = fl;
                                    if (q != null) s.quality = q;
                                    if (pa != null) s.palette = pa;
                                    engine.settingsChanged(p);
                                    p.sendMessage(MM.deserialize("<gold>Lighting</gold> <gray>» saved: " + summary(s)));
                                }), ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build())),
                        ActionButton.create(MM.deserialize("<gray>Cancel"), null, 120, null))));
        p.showDialog(d);
    }

    /** /lighting settings &lt;key&gt; &lt;value&gt; for players who prefer commands (and for the console). */
    boolean setting(Player p, String key, String value) {
        PlayerSettings s = engine.viewer(p).settings;
        switch (key.toLowerCase()) {
            case "enabled", "lighting" -> s.enabled = !value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("false");
            case "quality" -> {
                Quality q = Quality.parse(value);
                if (q == null) return false;
                s.quality = q;
            }
            case "animations", "animation" -> s.animations = !value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("false");
            case "palette", "colorblind", "colourblind" -> {
                Palette pa = Palette.parse(value);
                if (pa == null) return false;
                s.palette = pa;
            }
            case "flashlight" -> s.flashlightOn = !value.equalsIgnoreCase("off") && !value.equalsIgnoreCase("false");
            default -> {
                return false;
            }
        }
        engine.settingsChanged(p);
        return true;
    }

    static String summary(PlayerSettings s) {
        return "<white>" + (s.enabled ? "on" : "off") + "</white>, quality <white>" + s.quality.label()
                + "</white>, animations <white>" + (s.animations ? "on" : "off") + "</white>, colours <white>"
                + s.palette.label() + "</white>, flashlight <white>" + (s.flashlightOn ? "on" : "off") + "</white>";
    }

    // ------------------------------------------------------------------ diagnose

    void diagnose(CommandSender to, Player p) {
        ViewerState v = engine.viewer(p);
        PlayerSettings s = v.settings;
        Status st = pack.status(p);
        boolean active = packActive(p);
        to.sendMessage(MM.deserialize("<gold>Lighting diagnose: <white>" + p.getName()));
        to.sendMessage(MM.deserialize("<gray> pack: delivery <white>" + pack.delivery() + "</white>, sha1 <white>"
                + shortSha() + "</white>, client status <white>" + (st == null ? "none reported" : st.name()) + "</white>"));
        to.sendMessage(MM.deserialize("<gray> mode: " + (!s.enabled ? "<red>disabled by the player"
                : active ? (s.quality == Quality.LOW ? "<yellow>Low (fallback by choice)" : "<green>Resource Pack Lighting")
                : "<yellow>Fallback (no pack): brightness only")));
        to.sendMessage(MM.deserialize("<gray> settings: " + summary(s)));
        for (String line : v.describe()) to.sendMessage(MM.deserialize("<gray> now: <white>" + line));
        StringBuilder src = new StringBuilder();
        engine.sourceCounts(p).forEach((k, n) -> src.append(src.isEmpty() ? "" : ", ").append(k).append(" ").append(n));
        to.sendMessage(MM.deserialize("<gray> lights in range: <white>" + src + "</white>; emissive cells tracked <white>"
                + blocks.trackedCells()));
        String fk = flashlights.kind(p);
        to.sendMessage(MM.deserialize("<gray> Firearms: " + (firearms.present() ? "<green>found" : "<yellow>not installed")
                + "<gray>, held gun <white>" + (firearms.gunId(p.getInventory().getItemInMainHand()) == null ? "-"
                : firearms.gunId(p.getInventory().getItemInMainHand())) + "</white>, flashlight <white>"
                + (fk == null ? "none" : fk + (s.flashlightOn ? " (on)" : " (off)"))));
        if (st != Status.SUCCESSFULLY_LOADED && !pack.delivery().equals("none")) {
            to.sendMessage(MM.deserialize("<yellow> → The client hasn't loaded the Lighting pack. Check that server resource "
                    + "packs are enabled for this server, and that the download port " + cfg.packHttpPort
                    + " is reachable (or use pack.delivery url / none)."));
        }
        canary(p);
        to.sendMessage(MM.deserialize("<gray> A test light appears in front of " + p.getName() + " for 10 seconds. "
                + "<green>Cyan ring with a check mark</green> = the pack's models and textures work. "
                + "<light_purple>Purple-black squares</light_purple> = textures missing or overridden. "
                + "<white>A paper sheet</white> or nothing = lighting:canary is overridden or the pack isn't loaded. "
                + "Another resource pack ABOVE the Lighting pack wins: move Lighting up in the list."));
    }

    private String shortSha() {
        String s = pack.sha1();
        return s == null ? "none" : s.substring(0, 10);
    }

    /** A decal only the player sees, facing them: shows whether the pack's assets actually render. */
    private void canary(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().setY(0);
        if (dir.lengthSquared() < 1e-4) dir = new Vector(0, 0, 1);
        dir.normalize();
        Location at = eye.clone().add(dir.clone().multiply(2.5));
        ItemStack it = ItemStack.of(Material.PAPER);
        it.setData(DataComponentTypes.ITEM_MODEL, Key.key("lighting", "canary"));
        it.setData(DataComponentTypes.DYED_COLOR, DyedItemColor.dyedItemColor(Color.fromRGB(0x40E0FF)));
        float yaw = (float) Math.atan2(-dir.getX(), dir.getZ());
        ItemDisplay d = p.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setVisibleByDefault(false);
            x.setItemStack(it);
            x.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            x.setBrightness(new Display.Brightness(15, 15));
            x.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotationY(-yaw),
                    new Vector3f(1.2f, 1.2f, 1f), new Quaternionf()));
        });
        p.showEntity(plugin, d);
        Bukkit.getScheduler().runTaskLater(plugin, d::remove, 200L);
    }
}
