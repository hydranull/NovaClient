package nova.nova_client.client.mods;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if >=26.1 {
import net.minecraft.world.level.saveddata.WeatherData;
//?} else {
/*import net.minecraft.world.level.storage.ServerLevelData;
*///?}
import nova.nova_client.client.ModCategory;
import nova.nova_client.client.NovaGui;
import nova.nova_client.client.NovaToasts;
import nova.nova_client.client.NovaShapes;
import nova.nova_client.client.NovaType;

/**
 * Weather Notifier: Toast rechts oben, 20 Sekunden bevor das Wetter wechselt (Regen faengt an / hoert auf,
 * Gewitter beginnt / endet).
 * - Einzelspieler / eigene LAN-Welt: die Wetter-Timer werden direkt vom eingebauten Server gelesen -> echte Vorwarnung.
 * - Multiplayer: der Server schickt keine Timer; dann kommt der Toast, sobald das Wetter umschlaegt.
 */
public class WeatherNotifierMod extends GameTickMod {
    private static final int WARN_TICKS = 20 * 20;
    private static final int TOAST_W = 180;
    private static final int TOAST_H = 44;
    /** Nach dem Wechsel bleibt der Toast noch so lange stehen. */
    private static final long AFTER_MS = 3000L;

    private record Toast(String title, ItemStack icon, int color, long created, long changeAt, boolean countdown) {
    }

    private static boolean enabled;
    private static Toast toast;

    // Einzelspieler: letzte Restzeiten (zum Erkennen, ob der Wetterzyklus laeuft) und schon gewarnt?
    private static int lastRainTicks = -1;
    private static int lastThunderTicks = -1;
    private static boolean warnedRain;
    private static boolean warnedThunder;

    // Multiplayer: letzter bekannter Zustand
    private static ClientLevel lastLevel;
    private static boolean wasRaining;
    private static boolean wasThundering;

    public WeatherNotifierMod(String name, ModCategory category) {
        super(name, category);
    }

    @Override
    public void onStateChanged(Minecraft client, boolean enabled) {
        WeatherNotifierMod.enabled = enabled;
        toast = null;
        lastRainTicks = -1;
        lastThunderTicks = -1;
        warnedRain = false;
        warnedThunder = false;
        lastLevel = null;
    }

    @Override
    public void execute(Minecraft client) {
        ClientLevel level = client.level;
        if (client.player == null || level == null) {
            return;
        }
        MinecraftServer server = client.getSingleplayerServer();
        if (server != null && server.overworld() != null) {
            //? if >=26.1 {
            checkTimers(server.overworld().getWeatherData());
            //?} else {
            /*checkTimers(server.getWorldData().overworldData());
            *///?}
        } else {
            checkChanges(level);
        }
    }

    // ---------------------------------------------------------------- Einzelspieler: Timer lesen

    //? if >=26.1 {
    private static void checkTimers(WeatherData weather) {
    //?} else {
    /*private static void checkTimers(ServerLevelData weather) {
    *///?}
        boolean raining = weather.isRaining();
        int clear = weather.getClearWeatherTime();
        // Nach "/weather clear" laeuft erst die klare Zeit ab, danach setzt Regen ein
        int rainTicks = clear > 0 ? clear + 1 : weather.getRainTime();
        int thunderTicks = clear > 0 ? -1 : weather.getThunderTime();

        // Nur warnen, wenn die Zeit wirklich runterlaeuft (Wetterzyklus an)
        boolean rainRunning = lastRainTicks >= 0 && rainTicks < lastRainTicks;
        if (rainTicks > WARN_TICKS) {
            warnedRain = false;
        } else if (rainRunning && rainTicks > 0 && !warnedRain) {
            warnedRain = true;
            show(raining ? "Rain stops" : "Rain starts", raining ? Items.SUNFLOWER : Items.WATER_BUCKET,
                    raining ? 0xFFFFC53D : 0xFF4DA6FF, rainTicks, true);
        }
        lastRainTicks = rainTicks;

        // Gewitter ist nur bei Regen zu sehen
        boolean thunderRunning = lastThunderTicks >= 0 && thunderTicks >= 0 && thunderTicks < lastThunderTicks;
        if (thunderTicks < 0 || thunderTicks > WARN_TICKS) {
            warnedThunder = false;
        } else if (thunderRunning && thunderTicks > 0 && !warnedThunder && raining) {
            warnedThunder = true;
            boolean thundering = weather.isThundering();
            show(thundering ? "Thunderstorm ends" : "Thunderstorm starts", Items.TRIDENT,
                    0xFFB57CFF, thunderTicks, true);
        }
        lastThunderTicks = thunderTicks;
    }

    // ---------------------------------------------------------------- Multiplayer: Wechsel erkennen

    private static void checkChanges(ClientLevel level) {
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        if (level != lastLevel) {
            lastLevel = level;
            wasRaining = raining;
            wasThundering = thundering;
            return;
        }
        if (raining != wasRaining) {
            show(raining ? "Rain started" : "Rain stopped", raining ? Items.WATER_BUCKET : Items.SUNFLOWER,
                    raining ? 0xFF4DA6FF : 0xFFFFC53D, 0, false);
        } else if (thundering != wasThundering) {
            show(thundering ? "Thunderstorm started" : "Thunderstorm ended", Items.TRIDENT, 0xFFB57CFF, 0, false);
        }
        wasRaining = raining;
        wasThundering = thundering;
    }

    private static void show(String title, net.minecraft.world.item.Item icon, int color, int ticks, boolean countdown) {
        long now = Util.getMillis();
        toast = new Toast(title, new ItemStack(icon), color, now, now + ticks * 50L, countdown);
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING, 1.2F));
    }

    // ---------------------------------------------------------------- Zeichnen

    /** Zeichnet den Toast rechts oben; wird jeden Frame vom HUD aufgerufen. */
    public static void renderToast(GuiGraphicsExtractor g) {
        Minecraft client = Minecraft.getInstance();
        Toast t = toast;
        if (!enabled || t == null || client.player == null) {
            return;
        }
        long now = Util.getMillis();
        long end = Math.max(t.changeAt(), t.created() + 2000L) + AFTER_MS;
        if (now > end) {
            toast = null;
            return;
        }
        Font font = client.font;
        float in = NovaGui.easeOutCubic((now - t.created()) / 300.0F);
        float out = Mth.clamp((end - now) / 300.0F, 0.0F, 1.0F);
        int screenW = client.getWindow().getGuiScaledWidth();
        int x0 = screenW - 8 - TOAST_W + Math.round((1.0F - in * out) * (TOAST_W + 20));
        int x1 = x0 + TOAST_W;
        int y0 = NovaToasts.top();
        int y1 = y0 + TOAST_H;
        NovaToasts.setTop(y1 + NovaToasts.GAP);

        NovaGui.panel(g, x0, y0, x1, y1);

        // Icon-Kachel in Wetterfarbe
        int ix0 = x0 + 8;
        int iy0 = y0 + 10;
        NovaShapes.rect(g, ix0, iy0, ix0 + 24, iy0 + 24, NovaGui.RADIUS, NovaGui.INSET);
        NovaShapes.outline(g, ix0, iy0, ix0 + 24, iy0 + 24, NovaGui.RADIUS, NovaGui.alpha(t.color(), 0.85F));
        g.item(t.icon(), ix0 + 4, iy0 + 4);

        int tx = ix0 + 32;
        int textMax = x1 - tx - 8;
        NovaType.draw(g, font, NovaType.Face.CAPTION, "Weather", tx, y0 + 7, NovaGui.TEXT_MUTED);
        NovaGui.text(g, font, NovaGui.ellipsize(font, t.title(), textMax), tx, y0 + 17, NovaGui.TEXT);

        String info;
        float remaining;
        if (t.countdown() && now < t.changeAt()) {
            long seconds = (t.changeAt() - now + 999L) / 1000L;
            info = "in " + seconds + "s";
            remaining = Mth.clamp((t.changeAt() - now) / (float) (t.changeAt() - t.created()), 0.0F, 1.0F);
        } else {
            info = t.countdown() ? "now" : "just now";
            remaining = Mth.clamp((end - now) / (float) AFTER_MS, 0.0F, 1.0F);
        }
        NovaType.draw(g, font, NovaType.Face.MONO, info, tx, y0 + 28, NovaGui.TEXT_DIM);

        // Restzeit als duenner Balken unten
        int bx0 = x0 + 8;
        int bx1 = x1 - 8;
        NovaShapes.rect(g, bx0, y1 - 5, bx1, y1 - 3, 1, NovaGui.TRACK);
        int fill = bx0 + Math.round((bx1 - bx0) * remaining);
        if (fill > bx0 + 1) {
            NovaShapes.rect(g, bx0, y1 - 5, fill, y1 - 3, 1, NovaGui.BRAND);
        }
    }
}
