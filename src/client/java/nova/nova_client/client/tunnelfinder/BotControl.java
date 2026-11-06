package nova.nova_client.client.tunnelfinder;

import net.minecraft.world.entity.player.Input;
import nova.nova_client.client.mods.TunnelBaseFinderMod;

/**
 * Gemeinsamer Zugang fuer die Mixins zum Bot, der mit {@link TunnelBot} graben (Tunnel Base Finder): ob er gerade die
 * Steuerung hat, seine "Tasten" und die fluessige Kamera.
 */
public final class BotControl {
    private BotControl() {
    }

    /** Steuert gerade ein Grabe-Bot (Maus, Angriff, Bewegung gesperrt)? */
    public static boolean locksInput() {
        return TunnelBaseFinderMod.locksInput();
    }

    /** Eingabe fuer KeyboardInputMixin (null = Spieler steuert selbst). */
    public static Input inputOverride() {
        return TunnelBaseFinderMod.inputOverride();
    }

    /** Pro Frame aus dem HUD: Kamera fluessig bewegen. */
    public static void frame() {
        TunnelBaseFinderMod.frame();
    }

    public static boolean wantsGizmos() {
        return TunnelBaseFinderMod.wantsGizmos();
    }

    public static void emitGizmos() {
        TunnelBaseFinderMod.emitGizmos();
    }
}
