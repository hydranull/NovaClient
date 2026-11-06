package nova.nova_client.client;

//? if >=26.1 {
import net.minecraft.client.Minecraft;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * Datei-Dialog des Systems ueber SDL (ab 26.3 hat Minecraft SDL statt GLFW und kein tinyfd mehr). Der Dialog laeuft
 * asynchron: das Spiel bleibt bedienbar, das Ergebnis kommt spaeter in einem Rueckruf.
 */
public final class SdlFileDialog {
    /** Rueckruf des letzten Dialogs: wird erst beim naechsten Dialog freigegeben (nie waehrend er noch laeuft). */
    private static SDL_DialogFileCallback previous;

    private SdlFileDialog() {
    }

    /**
     * Eine PNG-Datei waehlen. done bekommt den Pfad oder null (abgebrochen oder Fehler) - im Thread des Dialogs, also
     * fuer alles am Spiel selbst Minecraft.execute benutzen. Muss im Render-Thread aufgerufen werden.
     */
    public static synchronized void openPng(String filterName, Consumer<String> done) {
        if (previous != null) {
            previous.free();
            previous = null;
        }
        ByteBuffer name = MemoryUtil.memUTF8(filterName);
        ByteBuffer pattern = MemoryUtil.memUTF8("png");
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
        filters.get(0).name(name).pattern(pattern);
        SDL_DialogFileCallback callback = SDL_DialogFileCallback.create((userdata, fileList, filter) -> {
            String chosen = null;
            try {
                if (fileList != 0L) {
                    long first = MemoryUtil.memGetAddress(fileList);
                    if (first != 0L) {
                        chosen = MemoryUtil.memUTF8(first);
                    }
                }
            } finally {
                // SDL liest die Filter nur bis hier
                filters.free();
                MemoryUtil.memFree(name);
                MemoryUtil.memFree(pattern);
            }
            done.accept(chosen);
        });
        previous = callback;
        long window = Minecraft.getInstance().getWindow().handle();
        SDLDialog.SDL_ShowOpenFileDialog(callback, 0L, window, filters, (CharSequence) null, false);
    }
}
//?}
