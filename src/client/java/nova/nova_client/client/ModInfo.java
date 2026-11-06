package nova.nova_client.client;

import nova.nova_client.client.mods.*;

import java.util.Map;

import static java.util.Map.entry;

/**
 * Kurze Beschreibung je Modul (eine Zeile, englisch wie die Oberflaeche) - steht im Nova-Menue in der Statusleiste
 * und im Kopf der Einstellungs-Fenster und wird bei der Suche mit durchsucht. Nach Klasse, damit Umbenennungen und
 * die Verschleierung im Release nichts ausmachen.
 */
public final class ModInfo {
    private static final Map<Class<?>, String> DESCRIPTIONS = Map.ofEntries(
            // Combat
            entry(AutoTotemMod.class, "Puts a new totem in your off-hand after a pop"),
            entry(AutoInvTotemMod.class, "Moves a totem to your off-hand while your inventory is open"),
            entry(HoverTotemMod.class, "Hover a totem in your inventory to swap it to the hotbar"),
            entry(AutoDoubleHandMod.class, "Refills emptied totem slots in your hotbar instantly"),
            entry(ReachMod.class, "Hit targets from further away"),
            entry(LegitCrystalMacroMod.class, "Hold right-click to place and break end crystals"),
            entry(AnchorMacroMod.class, "Hold right-click to place, charge and detonate anchors"),
            entry(KeyPearlMod.class, "Throws an ender pearl on a key press"),
            entry(LungeMacroMod.class, "Lunges with your spear on a key press"),
            entry(BowAimbotMod.class, "Aims your bow at the target closest to the crosshair"),
            entry(TriggerBotMod.class, "Attacks as soon as your crosshair is on a player"),
            entry(KillAuraMod.class, "Attacks the best target in range automatically"),
            entry(ShieldBreakerMod.class, "Switches to an axe to disable blocking shields"),
            entry(HitboxesMod.class, "Shows player hitboxes, eye line and view direction"),
            entry(MacroMod.class, "Runs your own sequences of keys, messages and waits"),
            // Misc
            entry(AutoToolMod.class, "Switches to the fastest tool while mining"),
            entry(FastPlaceMod.class, "Places blocks without the usual delay"),
            entry(FastXpMod.class, "Throws experience bottles much faster"),
            entry(FreecamMod.class, "Fly the camera freely while your player stays put"),
            entry(FreelookMod.class, "Turn the camera without turning your player"),
            entry(BoatFlyMod.class, "Lets boats and other vehicles fly"),
            entry(StreamProtectMod.class, "Hides your items while you stream"),
            entry(SprintMod.class, "Sprints automatically when you walk forward"),
            entry(AutoWalkMod.class, "Walks forward without holding a key"),
            entry(FlightMod.class, "Fly like in creative mode"),
            entry(AutoReconnectMod.class, "Rejoins the server after you get kicked"),
            entry(SpotifyMod.class, "Now playing card, lyrics and a full player"),
            entry(NoFallMod.class, "Prevents fall damage"),
            entry(FpsBoostMod.class, "Turns down costly settings for more FPS"),
            entry(PlayerDetectorMod.class, "Disconnects when another player comes close"),
            entry(LootPreviewMod.class, "Shows what is inside shulkers and chests"),
            entry(AssistantMod.class, "Dot commands and the Nova AI: ask in German, Turkish or English"),
            entry(NameProtectMod.class, "Shows a custom name and skin instead of yours"),
            entry(TridentUtilMod.class, "Riptide without water or rain, and faster charging"),
            entry(CoordSnapperMod.class, "Copies the coordinates you are looking at"),
            entry(ChatMacroMod.class, "Sends a message or command on a key press"),
            entry(DiscordPresenceMod.class, "Shows Nova Client in your Discord status"),
            // Donut
            entry(AutoChunkReloadMod.class, "Reloads chunks as you dig down to bedrock level"),
            entry(AuctionSniperMod.class, "Buys auction offers below your maximum price"),
            entry(AutoSellMod.class, "Sells matching items through /sell"),
            entry(SpawnerProtectMod.class, "Secures your spawners when someone comes near"),
            entry(AntiVanishMod.class, "Detects staff and players who vanished"),
            entry(FakeMediaMod.class, "Shows the media badge next to your name"),
            entry(TpaHereMacroMod.class, "Sends /tpahere to a player again and again"),
            entry(ScoreboardMod.class, "Restyles the scoreboard, only on your screen"),
            entry(RegionMapMod.class, "Map of all regions and their server numbers"),
            // Base Finding
            entry(SusChunkFinderMod.class, "Flags chunks where amethyst and plants grew for a long time"),
            entry(HoleEspMod.class, "Finds 1x1 and 1x3 vertical shafts"),
            entry(AdvancedStashFinderMod.class, "Storage ESP that alerts you to chunks full of containers"),
            entry(TunnelBaseFinderMod.class, "Digs a tunnel just above bedrock by itself"),
            entry(SeedFinderMod.class, "Finds the world seed"),
            entry(ActivityInspectorMod.class, "Finds dug out areas from the server's light data"),
            entry(PrimeChunkFinderMod.class, "Finds unexplained light changes below Y 0"),
            entry(PlayerBypassMod.class, "Finds built and dug areas through anti-xray"),
            entry(NetheriteFinderMod.class, "Finds the sections that hide ancient debris"),
            entry(QuantumMod.class, "Compares re-sent chunks to reveal hidden blocks"),
            entry(AmethystBypassMod.class, "Reveals amethyst geodes hidden by anti-xray"),
            entry(BlockEntityDebugMod.class, "Shows hidden chests, spawners and other block entities"),
            // Render
            entry(FullbrightMod.class, "Full brightness everywhere"),
            entry(ClearLiquidsMod.class, "Clear view in water and lava"),
            entry(EspMod.class, "Shows players and mobs through walls"),
            entry(BlockEspMod.class, "Outlines the blocks you choose through walls"),
            entry(StorageEspMod.class, "Outlines chests, barrels, shulkers and more"),
            entry(BlockNotifierMod.class, "Notifies you when the blocks you choose show up"),
            entry(SpawnerNotifierMod.class, "Notifies you when a spawner loads nearby"),
            entry(WeatherNotifierMod.class, "Warns you before the weather changes"),
            entry(TraderEspMod.class, "Marks wandering traders and their llamas"),
            entry(VillagerEspMod.class, "Marks villagers"),
            entry(PillagerEspMod.class, "Marks pillagers and their patrols"),
            entry(ItemFrameEspMod.class, "Marks item frames, optionally only filled ones"),
            entry(VineEspMod.class, "Shows long vines, a sign of player activity"),
            entry(KelpEspMod.class, "Marks chunks with fully grown kelp"),
            entry(NameTagsMod.class, "Name tags for players and dropped items"),
            entry(WaypointsMod.class, "Set waypoints and see them in the world"),
            entry(XrayMod.class, "See ores through blocks"),
            // Visuals
            entry(HudInfoMod.class, "FPS, ping, coordinates and more on screen"),
            entry(AdminListMod.class, "Shows which staff are online, hidden or near you"),
            entry(PotionHudMod.class, "Your active potion effects as cards"),
            entry(HotkeyListMod.class, "Lists all your bound keys"),
            entry(RadarMod.class, "Compass radar with players and mobs nearby"),
            entry(TimeChangerMod.class, "Sets your own time of day"),
            entry(SkyVfxMod.class, "Replaces the sky with an animated Nova sky"),
            entry(CrosshairMod.class, "Design your own crosshair"),
            entry(GlowingOutlinesMod.class, "Glowing outline around the items in your hands"),
            entry(PearlTrajectoryMod.class, "Shows where your ender pearl will land"),
            entry(DamageNumbersMod.class, "Damage numbers pop out of the targets you hit"),
            entry(KillEffectsMod.class, "An effect where your target dies"),
            entry(FirefliesMod.class, "Glowing fireflies circle around you"),
            entry(JumpCirclesMod.class, "A clean Nova circle at your feet when you jump and land"),
            entry(ItemPhysicsMod.class, "Dropped items lie flat on the ground"),
            entry(NovaCapeMod.class, "An animated Nova cape, only on your screen"),
            entry(SwingSpeedMod.class, "Changes the speed of your swing animation"),
            entry(VrMenuMod.class, "Menus float as glass windows in front of the world"),
            entry(MotionBlurMod.class, "Smooth motion blur when you turn"),
            entry(VividMod.class, "Richer, livelier colors and a crisper world"),
            entry(BlockOverlayMod.class, "A clean colored outline on the block you look at"),
            entry(LowHealthWarningMod.class, "Screen edges pulse red when your health is low"),
            entry(TargetHudMod.class, "Health and gear of your target under the crosshair"),
            entry(ZoomMod.class, "Zooms in while you hold the key"),
            entry(HandViewMod.class, "Moves your hands in first person"),
            entry(ArmourViewMod.class, "Armor and held items next to the hotbar"),
            entry(CustomFontMod.class, "Uses a Nova font for chat and HUD"),
            entry(KnivesMod.class, "Swords look like knives"),
            entry(ToolSkinsMod.class, "Every sword becomes a laser sword with combat moves and inspect animations"),
            // Nova
            entry(NovaPlusMod.class, "The Nova+ watermark and a clean info bar with time, FPS and ping"),
            entry(SchematicBuilderMod.class, "Builds schematics for you"),
            entry(NovaSocialMod.class, "Friends, messages and parties with everyone who uses Nova"));

    private ModInfo() {
    }

    /** Beschreibung des Moduls, leer wenn es keine gibt. */
    public static String description(Mod mod) {
        if (mod instanceof nova.nova_client.client.hub.AddonMod addon) {
            return addon.description();
        }
        return DESCRIPTIONS.getOrDefault(mod.getClass(), "");
    }
}
