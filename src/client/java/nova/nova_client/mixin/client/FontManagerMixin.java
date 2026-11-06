package nova.nova_client.mixin.client;

import com.mojang.blaze3d.font.GlyphProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import net.minecraft.client.gui.font.GlyphStitcher;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import nova.nova_client.client.NovaFonts;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Nova-Schriften scharf: statt "poppins"/"clean" die Variante zur echten Pixeldichte liefern (siehe NovaFonts).
 * Krumme Dichten werden beim ersten Gebrauch erzeugt: gleiche Schrift-Datei mit passendem oversample, dahinter die
 * Ersatz-Schriften der fertigen Variante. Sie landen in fontSets und werden beim naechsten Neuladen der Ressourcen
 * wie alle anderen geschlossen.
 */
@Mixin(FontManager.class)
public abstract class FontManagerMixin {
    @Shadow
    @Final
    private Map<Identifier, FontSet> fontSets;
    @Shadow
    @Final
    private TextureManager textureManager;
    @Shadow
    @Final
    private List<GlyphProvider> providersToClose;

    /** Varianten, die sich nicht erzeugen liessen (nicht jeden Frame neu versuchen). */
    @Unique
    private final Set<Identifier> nova_client$failed = new HashSet<>();

    @Inject(method = "getFontSetRaw", at = @At("HEAD"), cancellable = true)
    private void nova_client$sharpFont(Identifier id, CallbackInfoReturnable<FontSet> cir) {
        NovaFonts.Spec spec = NovaFonts.spec(id);
        if (spec == null) {
            // Custom Font: Minecrafts Schrift durch die gewaehlte Nova-Schrift ersetzen (deren Ersatz-Zeichen kommen
            // weiter aus der beim Laden eingebauten Minecraft-Schrift)
            spec = nova.nova_client.client.mods.CustomFontMod.replacement(id);
            if (spec == null) {
                return;
            }
        }
        float density = NovaFonts.density();
        if (Float.isNaN(density)) {
            return;
        }
        FontSet fixed = fontSets.get(NovaFonts.fixedVariant(spec, Math.round(density)));
        boolean whole = NovaFonts.isWhole(density);
        if (whole && fixed != null) {
            cir.setReturnValue(fixed);
            return;
        }
        // Die Custom-Font-Schriften haben nur ihre Grund-Datei (spart Speicher beim Laden): bei ihnen werden auch die
        // ganzen Dichten erst bei Bedarf erzeugt, bis dahin hilft die Grund-Datei aus
        FontSet base = fixed != null ? fixed : fontSets.get(NovaFonts.baseVariant(spec));
        if (base == null) {
            return;
        }
        Identifier variant = NovaFonts.dynamicVariant(spec, density);
        FontSet set = fontSets.get(variant);
        long now = System.nanoTime();
        if (set != null) {
            long[] used = nova_client$used.get(variant);
            if (used != null) {
                used[0] = now;
            }
        } else if (!nova_client$failed.contains(variant)) {
            // Erst erzeugen, wenn die Dichte eine Weile ohne Unterbrechung gebraucht wird: waehrend Einblend- und
            // Zoom-Animationen aendert sie sich jedes Bild - dafuer lohnt keine eigene Schrift (frueher verbrauchten
            // solche Zwischenstufen das Limit, danach blieben HUD und kleine Texte dauerhaft unscharf/abgehackt)
            long[] seen = nova_client$wanted.get(variant);
            if (seen == null || now - seen[1] > NOVA_GAP_NANOS) {
                if (nova_client$wanted.size() > 512) {
                    nova_client$wanted.values().removeIf(s -> now - s[1] > NOVA_GAP_NANOS);
                }
                seen = new long[]{now, now};
                nova_client$wanted.put(variant, seen);
            }
            seen[1] = now;
            // Menue-Schrift in ganzer Dichte sofort (sonst sieht man das erste Bild kurz in der Ersatz-Groesse/-Staerke);
            // alles andere hoechstens alle 40 ms, damit z.B. mehrere HUD-Groessen nicht in einem Bild ruckeln
            boolean allowed = whole && nova.nova_client.client.NovaType.isUi(spec) || now - nova_client$lastCreate > 40_000_000L;
            if ((whole || now - seen[0] >= NOVA_STABLE_NANOS) && allowed && nova_client$makeRoom(now)) {
                nova_client$lastCreate = now;
                nova_client$wanted.remove(variant);
                set = nova_client$create(spec, density, variant, base);
            }
        }
        cir.setReturnValue(set != null ? set : base);
    }

    /** Hoechstens so viele erzeugte Varianten gleichzeitig; lange ungenutzte werden dafuer freigegeben. */
    @Unique
    private static final int NOVA_MAX_VARIANTS = 96;
    /** So lange muss eine Dichte ohne Unterbrechung gebraucht werden, bevor sie eine eigene Variante bekommt. */
    @Unique
    private static final long NOVA_STABLE_NANOS = 120_000_000L;
    /** Laengere Pause = die Dichte gilt als neu (zaehlt von vorn). */
    @Unique
    private static final long NOVA_GAP_NANOS = 250_000_000L;
    /** Erst nach so langer Nichtbenutzung darf eine Variante freigegeben werden. */
    @Unique
    private static final long NOVA_IDLE_NANOS = 20_000_000_000L;

    @Unique
    private long nova_client$lastCreate;
    /** Gewuenschte, noch nicht erzeugte Varianten: [zuerst, zuletzt gebraucht]. */
    @Unique
    private final Map<Identifier, long[]> nova_client$wanted = new HashMap<>();
    /** Erzeugte Varianten: [zuletzt gebraucht]. */
    @Unique
    private final Map<Identifier, long[]> nova_client$used = new HashMap<>();
    /** TTF-Quelle je erzeugter Variante (zum Freigeben). */
    @Unique
    private final Map<Identifier, GlyphProvider> nova_client$providers = new HashMap<>();

    // Ressourcen neu geladen (F3+T, Ressourcenpaket): alle Schriften fliegen raus (auch die erzeugten)
    @Inject(method = "apply", at = @At("HEAD"))
    private void nova_client$resetVariants(CallbackInfo ci) {
        nova_client$failed.clear();
        nova_client$wanted.clear();
        nova_client$used.clear();
        nova_client$providers.clear();
        nova.nova_client.client.NovaType.clearCaches();
    }

    /** Platz fuer eine neue Variante? Gibt dafuer notfalls die am laengsten ungenutzte frei. */
    @Unique
    private boolean nova_client$makeRoom(long now) {
        if (nova_client$used.size() < NOVA_MAX_VARIANTS) {
            return true;
        }
        Identifier oldest = null;
        long oldestUse = Long.MAX_VALUE;
        for (Map.Entry<Identifier, long[]> entry : nova_client$used.entrySet()) {
            if (entry.getValue()[0] < oldestUse) {
                oldestUse = entry.getValue()[0];
                oldest = entry.getKey();
            }
        }
        if (oldest == null || now - oldestUse < NOVA_IDLE_NANOS) {
            return false;
        }
        nova_client$used.remove(oldest);
        FontSet set = fontSets.remove(oldest);
        if (set != null) {
            set.close();
        }
        GlyphProvider provider = nova_client$providers.remove(oldest);
        if (provider != null) {
            providersToClose.remove(provider);
            provider.close();
        }
        return true;
    }

    @Unique
    private FontSet nova_client$create(NovaFonts.Spec spec, float density, Identifier variant, FontSet fixed) {
        try {
            // FontWeights liest beim Laden Strichstaerke und optische Groesse aus dem Schnitt
            GlyphProvider ttf;
            NovaFonts.LOADING.set(spec);
            try {
                ttf = spec.definition(density).unpack().left().orElseThrow()
                        .load(Minecraft.getInstance().getResourceManager());
            } finally {
                NovaFonts.LOADING.remove();
            }
            providersToClose.add(ttf);
            // Erste Quelle der fertigen Variante ist die TTF-Datei, danach kommen die Ersatz-Schriften
            List<GlyphProvider.Conditional> base = ((FontSetAccessor) fixed).nova_client$allProviders();
            List<GlyphProvider.Conditional> providers = new ArrayList<>();
            providers.add(new GlyphProvider.Conditional(ttf, FontOption.Filter.ALWAYS_PASS));
            if (base.size() > 1) {
                providers.addAll(base.subList(1, base.size()));
            }
            FontSet set = new FontSet(new GlyphStitcher(textureManager, variant));
            set.reload(providers, Set.of());
            fontSets.put(variant, set);
            nova_client$providers.put(variant, ttf);
            nova_client$used.put(variant, new long[]{System.nanoTime()});
            com.mojang.logging.LogUtils.getLogger().info("[Nova] created font variant {}", variant);
            return set;
        } catch (Exception e) {
            nova_client$failed.add(variant);
            com.mojang.logging.LogUtils.getLogger().warn("[Nova] could not create font variant {}", variant, e);
            return null;
        }
    }
}
