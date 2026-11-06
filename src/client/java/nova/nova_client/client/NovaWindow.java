package nova.nova_client.client;

import net.minecraft.SharedConstants;
//? if >=26.1 {
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Surface;
//?} else {
/*import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
*///?}
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Das Spielfenster gehoert Nova: Nova-Logo statt Grasblock in Taskleiste und Titelleiste, und "Nova Client" im
 * Fenstertitel. Das Logo liegt in mehreren Groessen bei (assets/nova_client/icons), Windows sucht sich die passende aus.
 */
public final class NovaWindow {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/Window");
    private static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    private NovaWindow() {
    }

    /** Fenstertitel, z.B. "Nova Client · Minecraft 26.3". */
    public static String title() {
        return "Nova Client · Minecraft " + SharedConstants.getCurrentVersion().name();
    }

    /**
     * Nach Minecrafts eigenem Symbol: das Nova-Logo setzen. 26.3 hat SDL3 (ein Bild mit allen Groessen als
     * Alternativen, das System sucht sich die passende aus), 1.21.11 GLFW. macOS erlaubt keine Fenster-Symbole.
     */
    public static void applyIcon(long window) {
        //? if >=26.1 {
        applySdl(window);
        //?} else {
        /*applyGlfw(window);
        *///?}
    }

    //? if >=26.1 {
    private static void applySdl(long window) {
        if ("cocoa".equals(SDLVideo.SDL_GetCurrentVideoDriver())) {
            return;
        }
        List<ByteBuffer> loaded = new ArrayList<>();
        List<SDL_Surface> surfaces = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // groesstes Bild zuerst: es ist das Hauptbild, die kleineren haengen als Alternativen daran
            for (int i = SIZES.length - 1; i >= 0; i--) {
                int size = SIZES[i];
                ByteBuffer pixels = read("/assets/nova_client/icons/icon_" + size + ".png", stack);
                if (pixels == null) {
                    continue;
                }
                loaded.add(pixels);
                SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(size, size, SDLPixels.SDL_PIXELFORMAT_ABGR8888,
                        pixels, size * 4);
                if (surface == null) {
                    continue;
                }
                if (!surfaces.isEmpty()) {
                    SDLSurface.SDL_AddSurfaceAlternateImage(surfaces.get(0), surface);
                }
                surfaces.add(surface);
            }
            if (!surfaces.isEmpty()) {
                SDLVideo.SDL_SetWindowIcon(window, surfaces.get(0));
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not set the Nova window icon", e);
        } finally {
            // SDL hat sich eine eigene Kopie gemacht (Alternativen sind mitgezaehlt)
            for (int i = surfaces.size() - 1; i >= 0; i--) {
                SDLSurface.SDL_DestroySurface(surfaces.get(i));
            }
            loaded.forEach(STBImage::stbi_image_free);
        }
    }
    //?} else {
    /*private static void applyGlfw(long window) {
        int platform = GLFW.glfwGetPlatform();
        if (platform != GLFW.GLFW_PLATFORM_WIN32 && platform != GLFW.GLFW_PLATFORM_X11) {
            return;
        }
        List<ByteBuffer> loaded = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GLFWImage.Buffer images = GLFWImage.malloc(SIZES.length, stack);
            int count = 0;
            for (int size : SIZES) {
                ByteBuffer pixels = read("/assets/nova_client/icons/icon_" + size + ".png", stack);
                if (pixels != null) {
                    loaded.add(pixels);
                    images.position(count++);
                    images.width(size).height(size).pixels(pixels);
                }
            }
            if (count > 0) {
                images.position(0);
                images.limit(count);
                GLFW.glfwSetWindowIcon(window, images);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Could not set the Nova window icon", e);
        } finally {
            loaded.forEach(STBImage::stbi_image_free);
        }
    }
    *///?}

    /** PNG aus der Mod lesen und als RGBA-Pixel dekodieren (mit stbi_image_free freigeben), oder null. */
    private static ByteBuffer read(String resource, MemoryStack stack) {
        byte[] bytes;
        try (InputStream in = NovaWindow.class.getResourceAsStream(resource)) {
            if (in == null) {
                return null;
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
        ByteBuffer file = MemoryUtil.memAlloc(bytes.length);
        try {
            file.put(bytes).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            return STBImage.stbi_load_from_memory(file, w, h, channels, 4);
        } finally {
            MemoryUtil.memFree(file);
        }
    }
}
