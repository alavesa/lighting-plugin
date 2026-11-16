package fi.alavesa.lighting.wand;

import fi.alavesa.lighting.LightingConfig;
import fi.alavesa.lighting.light.LightSource;
import fi.alavesa.lighting.render.Anim;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** The two native dialogs: wand settings (next light) and edit (an existing light). */
public final class LightDialogs {

    /** What the inputs of either dialog hold after a click. */
    public record Values(int rgb, double radius, double intensity, boolean vanillaBlock, Anim anim, boolean spot,
                         double cone) {
    }

    public interface EditActions {
        void save(Values v);

        void delete();

        void copyToWand(Values v);

        void moveToAim(Values v);

        void aimSpot(Values v);
    }

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final ClickCallback.Options ONCE = ClickCallback.Options.builder().uses(1)
            .lifetime(Duration.ofMinutes(10)).build();

    private final Plugin plugin;
    private LightingConfig cfg;

    public LightDialogs(Plugin plugin, LightingConfig cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void reload(LightingConfig cfg) {
        this.cfg = cfg;
    }

    public void settings(Player p, Wand.Session s, Consumer<Values> apply) {
        Values init = new Values(s.rgb, s.radius, s.intensity, s.vanillaBlock, s.anim, s.spot, s.cone);
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<gradient:#FFE29A:#B47CFF>Lighting Wand</gradient>"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(
                                "<gray>Settings for the next light you place. A hex colour overrides the sliders. Spotlights point"
                                        + " where you look when you place them.<newline>"
                                        + "<dark_gray>Now: " + swatch(s.rgb) + " <gray>r " + fmt(s.radius) + ", x"
                                        + fmt(s.intensity)), 300)))
                        .inputs(inputs(init))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.create(MM.deserialize("<green>Apply"), null, 120,
                                DialogAction.customClick((view, who) -> later(() -> apply.accept(read(view, init))), ONCE)),
                        ActionButton.create(MM.deserialize("<gray>Cancel"), null, 120, null))));
        p.showDialog(d);
    }

    public void edit(Player p, LightSource l, EditActions actions) {
        Values init = new Values(l.rgb(), l.radius(), l.intensity(), l.vanillaBlock(), l.anim(), l.spotDir() != null,
                l.coneDeg());
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.create(MM.deserialize("<green>Save"), MM.deserialize("<gray>Apply colour and size"), 140,
                DialogAction.customClick((view, who) -> later(() -> actions.save(read(view, init))), ONCE)));
        buttons.add(ActionButton.create(MM.deserialize("<aqua>Move to my aim"),
                MM.deserialize("<gray>Move this light to where the wand points"), 140,
                DialogAction.customClick((view, who) -> later(() -> actions.moveToAim(read(view, init))), ONCE)));
        buttons.add(ActionButton.create(MM.deserialize("<gold>Aim spotlight"),
                MM.deserialize("<gray>Make it a spotlight pointing where you look now"), 140,
                DialogAction.customClick((view, who) -> later(() -> actions.aimSpot(read(view, init))), ONCE)));
        buttons.add(ActionButton.create(MM.deserialize("<yellow>Copy to wand"),
                MM.deserialize("<gray>Use these settings for new lights"), 140,
                DialogAction.customClick((view, who) -> later(() -> actions.copyToWand(read(view, init))), ONCE)));
        buttons.add(ActionButton.create(MM.deserialize("<red>Delete"), MM.deserialize("<gray>Remove this light"), 140,
                DialogAction.customClick((view, who) -> later(actions::delete), ONCE)));
        String where = String.format(java.util.Locale.ROOT, "%.1f %.1f %.1f", l.x(), l.y(), l.z());
        Dialog d = Dialog.create(b -> b.empty()
                .base(DialogBase.builder(MM.deserialize("<gold>Light <white>" + l.shortId()))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .body(List.of(DialogBody.plainMessage(MM.deserialize(
                                swatch(l.rgb()) + " <gray>at <white>" + where + "</white>, <white>"
                                        + (l.spotDir() == null ? "point light" : "spotlight")), 300)))
                        .inputs(inputs(init))
                        .build())
                .type(DialogType.multiAction(buttons).columns(2)
                        .exitAction(ActionButton.create(MM.deserialize("<gray>Close"), null, 140, null)).build()));
        p.showDialog(d);
    }

    private List<DialogInput> inputs(Values v) {
        int r = (v.rgb >> 16) & 255, g = (v.rgb >> 8) & 255, b = v.rgb & 255;
        return List.of(
                DialogInput.numberRange("red", MM.deserialize("<red>Red"), 0, 255).step(1f).initial((float) r).width(300).build(),
                DialogInput.numberRange("green", MM.deserialize("<green>Green"), 0, 255).step(1f).initial((float) g).width(300).build(),
                DialogInput.numberRange("blue", MM.deserialize("<blue>Blue"), 0, 255).step(1f).initial((float) b).width(300).build(),
                DialogInput.text("hex", MM.deserialize("<gray>Hex colour")).initial(LightingConfig.hex(v.rgb)).maxLength(9)
                        .width(120).build(),
                DialogInput.numberRange("radius", MM.deserialize("<yellow>Radius (blocks)"), (float) cfg.radiusMin,
                        (float) cfg.radiusMax).step((float) cfg.radiusStep).initial((float) v.radius).width(300).build(),
                DialogInput.numberRange("intensity", MM.deserialize("<yellow>Intensity"), 0.1f, 3.9f).step(0.1f)
                        .initial((float) LightingConfig.clamp(v.intensity, 0.1, 3.9)).width(300).build(),
                DialogInput.singleOption("anim", MM.deserialize("<gray>Animation"), List.of(
                        opt("none", "None", v.anim == Anim.NONE), opt("pulse", "Pulse", v.anim == Anim.PULSE),
                        opt("flicker", "Flicker", v.anim == Anim.FLICKER), opt("strobe", "Strobe", v.anim == Anim.STROBE)))
                        .width(300).build(),
                DialogInput.bool("spot", MM.deserialize("<gray>Spotlight (directional)")).initial(v.spot).build(),
                DialogInput.numberRange("cone", MM.deserialize("<yellow>Spotlight cone (degrees)"), 10f, 120f).step(5f)
                        .initial((float) LightingConfig.clamp(v.cone, 10, 120)).width(300).build(),
                DialogInput.bool("vanilla", MM.deserialize("<gray>Vanilla light block (brightness for everyone)"))
                        .initial(v.vanillaBlock).build());
    }

    private Values read(DialogResponseView view, Values init) {
        int r = num(view, "red", (init.rgb >> 16) & 255), g = num(view, "green", (init.rgb >> 8) & 255),
                b = num(view, "blue", init.rgb & 255);
        int rgb = (r << 16) | (g << 8) | b;
        String hex = view.getText("hex");
        int parsed = LightingConfig.parseColor(hex, -1);
        // the hex field wins only when it was changed; otherwise the sliders do
        if (parsed >= 0 && parsed != init.rgb) rgb = parsed;
        Float radius = view.getFloat("radius");
        Float intensity = view.getFloat("intensity");
        Boolean vanilla = view.getBoolean("vanilla");
        Anim anim = Anim.parse(view.getText("anim"));
        Boolean spot = view.getBoolean("spot");
        Float cone = view.getFloat("cone");
        return new Values(rgb, radius == null ? init.radius : cfg.clampRadius(radius),
                intensity == null ? init.intensity : intensity, vanilla == null ? init.vanillaBlock : vanilla,
                anim == null ? init.anim : anim, spot == null ? init.spot : spot, cone == null ? init.cone : cone);
    }

    private static SingleOptionDialogInput.OptionEntry opt(String id, String label, boolean initial) {
        return SingleOptionDialogInput.OptionEntry.create(id, MM.deserialize(label), initial);
    }

    private static int num(DialogResponseView v, String key, int fallback) {
        Float f = v.getFloat(key);
        return f == null ? fallback : Math.max(0, Math.min(255, Math.round(f)));
    }

    /** Dialog callbacks may arrive off the main thread; everything they do touches the world. */
    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, r);
    }

    public static String swatch(int rgb) {
        String h = LightingConfig.hex(rgb);
        return "<color:" + h + ">██</color> <white>" + h + "</white>";
    }

    public static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    public static Component mm(String s) {
        return MM.deserialize(s);
    }
}
