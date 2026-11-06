package nova.nova_client.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.mods.FpsBoostMod;
import nova.nova_client.client.mods.GameTickMod;
import nova.nova_client.client.mods.AssistantMod;
import nova.nova_client.client.mods.LootPreviewMod;
import nova.nova_client.client.mods.MacroMod;
import nova.nova_client.client.mods.Mod;
import nova.nova_client.client.mods.NovaPlusMod;
import nova.nova_client.client.mods.RenderMod;
import nova.nova_client.client.mods.SpotifyMod;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public class Nova_clientClient implements ClientModInitializer {

    KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("nova_client", "nova_client")
    );

    private KeyMapping sendToChatKey;
    private KeyMapping spotifyKey;

    // Mods, deren Taste im letzten Tick gedrueckt war (nur Flanke loest aus)
    private final Set<Mod> keysDown = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean updateAnnounced;

    private static final Identifier HUD_ID =
            Identifier.fromNamespaceAndPath("nova_client", "my_hud");

    @Override
    public void onInitializeClient() {
        // Neuanfang: beim ersten Start einer neuen Epoche alle alten Nova-Dateien loeschen (vor dem Laden der Config)
        NovaReset.run();
        // Auto-Update: im Hintergrund nach einer neuen Version suchen (nur offizielle Builds)
        nova.nova_client.client.update.UpdateManager.init();
        Mods.init();
        // Nova Hub: installierte Add-ons melden ihre Module an (vor dem Laden der Config, damit ihre Einstellungen mitkommen)
        nova.nova_client.client.hub.AddonLoader.load();
        ModConfig.load();
        Spotify.init();

        sendToChatKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "Open Nova Client Menu",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.KEY_RSHIFT,
                        this.CATEGORY
                ));

        spotifyKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping(
                        "Open Spotify Player",
                        InputConstants.Type.KEYBOARD,
                        InputConstants.KEY_O,
                        this.CATEGORY
                ));

        // Nova Social: Ort fuer die Party markieren
        nova.nova_client.client.social.SocialKeys.init(KeyMappingHelper.registerKeyMapping(
                new KeyMapping("Party Ping (Nova Social)", InputConstants.Type.KEYBOARD, InputConstants.KEY_Z, this.CATEGORY)));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            FpsBoostMod.checkStartup(client);
            ModStates.tick(client);
            nova.nova_client.client.mods.DiscordPresenceMod.tick(client);
            nova.nova_client.client.mods.AdminListMod.tick(client);
            // Nova Social: Nachrichten vom Server verarbeiten (auch ohne Welt), Ping-Taste
            nova.nova_client.client.social.SocialClient.tick(client);
            nova.nova_client.client.social.SocialKeys.tick(client);
            while (this.sendToChatKey.consumeClick()) {
                if (client.player == null) return;

                Minecraft.getInstance().gui.setScreen(UiSettings.mainMenu());
            }
            while (this.spotifyKey.consumeClick()) {
                if (client.player == null) return;

                Minecraft.getInstance().gui.setScreen(new SpotifyScreen(null));
            }
        });

        // Macros laufen vor Minecrafts Tastenverarbeitung, damit simulierte Tasten sofort wirken
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                return;
            }
            for (Mod m : Mods.mods) {
                if (m instanceof MacroMod macroMod && m.active) {
                    macroMod.startTick(client);
                }
            }
            // Schematic Builder: Spieltasten sperren, solange er baut (vor Minecrafts Tastenverarbeitung)
            nova.nova_client.client.mods.SchematicBuilderMod.startTick(client);
            // Spawner Protect: im Auswahl-Modus waehlt Rechtsklick Spawner/Ender-Kiste aus
            nova.nova_client.client.mods.SpawnerProtectMod.startTick(client);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                return;
            }

            handleModKeybinds(client);
            NovaLinks.welcome(client);
            announceUpdate();
            nova.nova_client.client.mods.SchematicBuilderMod.globalTick(client);
            nova.nova_client.client.mods.FreecamMod.globalTick(client);
            SpotifyMod.handleKeys(client);

            for (Mod m : Mods.mods){
                if(m instanceof GameTickMod && m.active){
                    ((GameTickMod) m).execute(client);
                }
            }
        });

        // Jump Circles, Kill Effects, Fireflies
        NovaFx.register();
        nova.nova_client.client.toolskins.ToolSkins.register();
        // Aktive Module beim Beenden sichern (ModStates)
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ModConfig.save());

        // Loot Preview: eigenes Tooltip-Bild fuer den Inhalt von Shulker-Boxen
        ClientTooltipComponentCallback.EVENT.register(component ->
                component instanceof LootTooltip.Preview preview ? new LootTooltip.Client(preview) : null);
        // Nova Assistant: ".befehle" abfangen statt sie in den Chat zu schicken
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> !AssistantMod.handle(message));
        // Activity Inspector: "/artp start|stop|status" abfangen
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> !nova.nova_client.client.mods.ActivityInspectorMod.handleCommand(command));
        // Freecam: Rueckmeldung des eigenen /attribute-Befehls (Rest der alten Reichweite entfernen) nicht im Chat zeigen
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> !nova.nova_client.client.mods.FreecamMod.hidesFeedback(message));

        // Nova+ Info-Leiste oben mittig: Bossleisten (Name ab y 3) und Tab-Liste (ab y 9) rutschen darunter
        HudElementRegistry.replaceElement(VanillaHudElements.BOSS_BAR, original -> belowInfoBar(original, 3));
        HudElementRegistry.replaceElement(VanillaHudElements.PLAYER_LIST, original -> belowInfoBar(original, 9));

        HudElementRegistry.addLast(HUD_ID, (graphics, deltaTracker) -> {
            HudLayout.beginFrame();
            for (Mod m : Mods.mods){
                if(m instanceof RenderMod && m.active){
                    ((RenderMod) m).execute(graphics, deltaTracker);
                }
            }
            nova.nova_client.client.hub.AddonLoader.renderHud(graphics);
            LootPreviewMod.drawHud(graphics);
            nova.nova_client.client.mods.SchematicBuilderMod.renderHud(graphics, deltaTracker);
            nova.nova_client.client.tunnelfinder.BotControl.frame();
            nova.nova_client.client.mods.SpawnerProtectMod.frame();
            nova.nova_client.client.mods.SpawnerProtectMod.renderHud(graphics);
            nova.nova_client.client.mods.AntiVanishMod.renderHud(graphics);
            // An Dingen in der Welt haengende HUD-Elemente
            WorldToScreen.begin();
            nova.nova_client.client.mods.DamageNumbersMod.drawHud(graphics);
            nova.nova_client.client.mods.WaypointsMod.drawHud(graphics);
            // Toasts rechts oben, untereinander gestapelt
            NovaToasts.beginFrame();
            nova.nova_client.client.mods.BlockNotifierMod.renderToasts(graphics);
            nova.nova_client.client.mods.WeatherNotifierMod.renderToast(graphics);
            nova.nova_client.client.mods.AdvancedStashFinderMod.renderToasts(graphics);
            nova.nova_client.client.mods.ActivityInspectorMod.renderToasts(graphics);
            nova.nova_client.client.mods.SpawnerNotifierMod.renderToasts(graphics);
            nova.nova_client.client.mods.SpawnerProtectMod.renderToasts(graphics);
            nova.nova_client.client.mods.PlayerBypassMod.renderToasts(graphics);
            nova.nova_client.client.mods.QuantumMod.renderToasts(graphics);
            nova.nova_client.client.mods.PrimeChunkFinderMod.renderToasts(graphics);
            nova.nova_client.client.mods.NetheriteFinderMod.renderToasts(graphics);
            nova.nova_client.client.mods.SusChunkFinderMod.renderToasts(graphics);
            // Nova Social: Karten, Pings und Party-Mitglieder
            nova.nova_client.client.social.SocialHud.render(graphics);
            // Module per Taste umgeschaltet: kleine Karten unten rechts
            ToggleToasts.render(graphics);
            // Freecam: Tempo nach dem Scrollen kurz ueber der Hotbar
            nova.nova_client.client.mods.FreecamMod.drawHud(graphics);
            // Anti Vanish: grosse Erscheinung (je Design) ueber allem
            VanishAlert.render(graphics);
        });
    }

    /** Vanilla-Element oben in der Mitte (Inhalt ab firstY) unter die Nova+ Info-Leiste schieben. */
    private static HudElement belowInfoBar(HudElement original, int firstY) {
        return (graphics, deltaTracker) -> {
            int shift = NovaPlusMod.topShift(firstY);
            if (shift > 0) {
                graphics.pose().pushMatrix();
                graphics.pose().translate(0.0F, shift);
            }
            //? if >=26.1 {
            original.extractRenderState(graphics, deltaTracker);
            //?} else {
            /*original.render(graphics, deltaTracker);
            *///?}
            if (shift > 0) {
                graphics.pose().popMatrix();
            }
        };
    }

    /** Einmal pro Spielstart im Chat melden, wenn ein Update bereitliegt (wer gleich joint, sieht das Titel-Banner nie). */
    private void announceUpdate() {
        if (updateAnnounced || !nova.nova_client.client.update.UpdateManager.ready()) {
            return;
        }
        updateAnnounced = true;
        NovaNotify.chat("Nova", "§aUpdate " + nova.nova_client.client.update.UpdateManager.newVersion()
                + " is ready§7 - it installs automatically when you close Minecraft.");
        NovaNotify.sound(net.minecraft.sounds.SoundEvents.PLAYER_LEVELUP, 1.4F);
    }

    /** Schaltet Mods per eigener Taste um (nur im Spiel, nicht in Menues oder im Chat). */
    private void handleModKeybinds(Minecraft client) {
        boolean inGame = client.gui.screen() == null;
        for (Mod mod : Mods.mods) {
            boolean down = mod.hasKey() && InputConstants.isKeyDown(mod.keyCode);
            boolean wasDown = keysDown.contains(mod);
            // Schematic Builder: Stopp-Taste geht auch, waehrend der Bot gerade eine Kiste/das Inventar offen hat
            boolean allowed = inGame || mod instanceof nova.nova_client.client.mods.SchematicBuilderMod
                    && nova.nova_client.client.mods.SchematicBuilderMod.botScreenOpen();
            if (down && !wasDown && allowed) {
                mod.toggle(client);
                ToggleToasts.push(mod.name, mod.active);
            }
            if (down) {
                keysDown.add(mod);
            } else {
                keysDown.remove(mod);
            }
        }
    }
}
