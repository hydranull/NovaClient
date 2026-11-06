package nova.nova_client.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.OptionalDouble;
import java.util.function.Supplier;
//? if >=26.1 {
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import org.joml.Vector4f;

import java.util.Optional;
//?} else {
/*import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.CachedOrthoProjectionMatrixBuffer;

import java.util.OptionalInt;
*///?}

/**
 * Eigene GPU-Durchgaenge (Sky VFX, VR Menu) fuer beide Minecraft-Versionen: 26.x hat Bind-Gruppen, Farbziel-Zustaende
 * und Primitiv-Topologien, 1.21.11 noch Uniform-/Sampler-Listen, Blend-Funktionen und Vertex-Modi. Die Aufrufer
 * beschreiben nur, was sie brauchen; hier steht, wie es in der jeweiligen Version heisst.
 */
public final class RenderCompat {
    private RenderCompat() {
    }

    /**
     * Pipeline mit DynamicTransforms + Projection, ohne Tiefentest, ohne Culling.
     *
     * @param vertexFormat Vertex-Format, oder null (Shader erzeugt die Ecken selbst aus gl_VertexID)
     * @param quads        Vierecke (mit Index-Puffer, siehe {@link #drawQuads}) statt Dreiecke
     * @param blend        Mischung, oder null = undurchsichtig ueberschreiben
     * @param samplers     Namen der Textur-Sampler im Shader
     */
    public static RenderPipeline pipeline(Identifier location, Identifier vertexShader, Identifier fragmentShader,
                                          @Nullable VertexFormat vertexFormat, boolean quads, @Nullable BlendFunction blend,
                                          String... samplers) {
        return builder(location, vertexShader, fragmentShader, vertexFormat, quads, blend, samplers).build();
    }

    /** Wie {@link #pipeline}, aber noch nicht gebaut (fuer Shader-Defines oder einen Tiefentest). */
    public static RenderPipeline.Builder builder(Identifier location, Identifier vertexShader, Identifier fragmentShader,
                                                 @Nullable VertexFormat vertexFormat, boolean quads, @Nullable BlendFunction blend,
                                                 String... samplers) {
        //? if >=26.1 {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(vertexShader)
                .withFragmentShader(fragmentShader)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(BindGroupLayouts.PROJECTION);
        if (samplers.length > 0) {
            BindGroupLayout.Builder layout = BindGroupLayout.builder();
            for (String sampler : samplers) {
                layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
            }
            builder.withBindGroupLayout(layout.build());
        }
        builder.withColorTargetState(blend == null ? ColorTargetState.DEFAULT : new ColorTargetState(blend));
        if (vertexFormat != null) {
            builder.withVertexBinding(0, vertexFormat);
        }
        return builder.withPrimitiveTopology(quads ? PrimitiveTopology.QUADS : PrimitiveTopology.TRIANGLES)
                .withDepthStencilState(Optional.empty())
                .withCull(false);
        //?} else {
        /*RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(location)
                .withVertexShader(vertexShader)
                .withFragmentShader(fragmentShader)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER);
        for (String sampler : samplers) {
            builder.withSampler(sampler);
        }
        if (blend == null) {
            builder.withoutBlend();
        } else {
            builder.withBlend(blend);
        }
        return builder.withVertexFormat(vertexFormat != null ? vertexFormat : DefaultVertexFormat.EMPTY,
                        quads ? VertexFormat.Mode.QUADS : VertexFormat.Mode.TRIANGLES)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false)
                .withCull(false);
        *///?}
    }

    /**
     * Tiefe fuer leuchtende Welt-Effekte: lesen, nicht schreiben. depthTest = hinter Bloecken verdeckt, sonst durch
     * Bloecke hindurch sichtbar. (26.x rechnet die Tiefe umgekehrt, deshalb dort "groesser oder gleich".)
     */
    public static RenderPipeline.Builder glowDepth(RenderPipeline.Builder builder, boolean depthTest) {
        //? if >=26.1 {
        return builder.withDepthStencilState(new com.mojang.renderpearl.api.pipeline.DepthStencilState(depthTest
                ? com.mojang.renderpearl.api.pipeline.CompareOp.GREATER_THAN_OR_EQUAL
                : com.mojang.renderpearl.api.pipeline.CompareOp.ALWAYS_PASS, false));
        //?} else {
        /*return builder.withDepthTestFunction(depthTest ? DepthTestFunction.LEQUAL_DEPTH_TEST : DepthTestFunction.NO_DEPTH_TEST)
                .withDepthWrite(false);
        *///?}
    }

    /**
     * Mischung fuer das (einzige) Farbziel. 26.3 verlangt fuer jede Pipeline ausdruecklich ein Farbziel (Snippets
     * bringen keins mehr mit) - ohne passt sie nicht zum Render-Pass. 1.21.11: Mischung wie bisher.
     */
    public static RenderPipeline.Builder blend(RenderPipeline.Builder builder, @Nullable BlendFunction blend) {
        //? if >=26.1 {
        return builder.withColorTargetState(blend == null ? ColorTargetState.DEFAULT : new ColorTargetState(blend));
        //?} else {
        /*return blend == null ? builder.withoutBlend() : builder.withBlend(blend);
        *///?}
    }

    /** Durchgang auf color (und optional depth), ohne vorher zu leeren. */
    public static RenderPass pass(Supplier<String> label, GpuTextureView color, @Nullable GpuTextureView depth) {
        //? if >=26.1 {
        return RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, color, Optional.empty(), depth,
                OptionalDouble.empty());
        //?} else {
        /*return RenderSystem.getDevice().createCommandEncoder().createRenderPass(label, color, OptionalInt.empty(), depth,
                OptionalDouble.empty());
        *///?}
    }

    // ---------------------------------------------------------------- Pipelines, Texturen, Render-Ziele

    /**
     * Shader der Pipeline jetzt uebersetzen. false = Fehler (dann den Effekt weglassen). 26.3 uebersetzt beim ersten
     * Abruf aus dem Pipeline-Cache und gibt bei einem Fehler null.
     */
    public static boolean compiles(RenderPipeline pipeline) {
        //? if >=26.1 {
        try {
            return RenderSystem.getCompiledPipelineNullable(pipeline) != null;
        } catch (RuntimeException e) {
            return false;
        }
        //?} else {
        /*return RenderSystem.getDevice().precompilePipeline(pipeline).isValid();
        *///?}
    }

    /** Pipeline fuer die folgenden Zeichenbefehle (26.3: die uebersetzte Fassung aus dem Cache). */
    public static void setPipeline(RenderPass pass, RenderPipeline pipeline) {
        //? if >=26.1 {
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        //?} else {
        /*pass.setPipeline(pipeline);
        *///?}
    }

    /** Textur an einen Sampler des Shaders binden (26.3: setUniform). */
    public static void texture(RenderPass pass, String name, GpuTextureView view, GpuSampler sampler) {
        //? if >=26.1 {
        pass.setUniform(name, view, sampler);
        //?} else {
        /*pass.bindTexture(name, view, sampler);
        *///?}
    }

    /** Eigenes Render-Ziel, 8 Bit je Farbe, mit oder ohne Tiefe. */
    public static TextureTarget target(String label, int width, int height, boolean depth) {
        //? if >=26.1 {
        return new TextureTarget(label, width, height, GpuFormat.RGBA8_UNORM, depth ? GpuFormat.D32_FLOAT : null);
        //?} else {
        /*return new TextureTarget(label, width, height, depth);
        *///?}
    }

    /** Render-Ziel ohne Tiefe mit 16 Bit je Farbe (keine Stufen in weichen Verlaeufen); 1.21.11 kann nur 8 Bit. */
    public static TextureTarget target16(String label, int width, int height) {
        //? if >=26.1 {
        return new TextureTarget(label, width, height, GpuFormat.RGBA16_UNORM, null);
        //?} else {
        /*return new TextureTarget(label, width, height, false);
        *///?}
    }

    /** Vertex-Puffer an Platz 0. */
    public static void vertices(RenderPass pass, GpuBuffer buffer) {
        //? if >=26.1 {
        pass.setVertexBuffer(0, buffer.slice());
        //?} else {
        /*pass.setVertexBuffer(0, buffer);
        *///?}
    }

    /** Vierecke zeichnen (Index-Puffer kommt von Minecraft). indexCount = 6 je Viereck. */
    public static void drawQuads(RenderPass pass, int indexCount) {
        //? if >=26.1 {
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
        pass.drawIndexed(indexCount, 1, 0, 0, 0);
        //?} else {
        /*RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
        pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
        pass.drawIndexed(0, 0, indexCount, 1);
        *///?}
    }

    /** Ein Dreieck ueber das ganze Ziel (Ecken aus gl_VertexID, z.B. core/screenquad). */
    public static void drawFullscreen(RenderPass pass) {
        //? if >=26.1 {
        pass.draw(3, 1, 0, 0);
        //?} else {
        /*pass.draw(0, 3);
        *///?}
    }

    /** BufferBuilder fuer Vierecke. */
    public static BufferBuilder quads(ByteBufferBuilder bytes, VertexFormat format) {
        //? if >=26.1 {
        return new BufferBuilder(bytes, PrimitiveTopology.QUADS, format);
        //?} else {
        /*return new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
        *///?}
    }

    /** Kopie der aktuellen Model-View-Matrix. */
    public static Matrix4f modelView() {
        //? if >=26.1 {
        return RenderSystem.getModelViewMatrixCopy();
        //?} else {
        /*return new Matrix4f(RenderSystem.getModelViewMatrix());
        *///?}
    }

    /** Farbe durchsichtig und Tiefe auf "ganz hinten" leeren. */
    public static void clearTransparent(GpuTexture color, GpuTexture depth) {
        //? if >=26.1 {
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, new Vector4f(0.0F), depth, 0.0D);
        //?} else {
        /*RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(color, 0, depth, 1.0D);
        *///?}
    }

    /** Farbe durchsichtig leeren. */
    public static void clearTransparent(GpuTexture color) {
        //? if >=26.1 {
        RenderSystem.getDevice().createCommandEncoder().clearColorTexture(color, new Vector4f(0.0F));
        //?} else {
        /*RenderSystem.getDevice().createCommandEncoder().clearColorTexture(color, 0);
        *///?}
    }

    /** Orthografische Projektion fuer eigene Durchgaenge (z.B. das VR-Fenster). */
    public static final class Ortho {
        private final String label;
        //? if >=26.1 {
        private final Projection projection = new Projection();
        private ProjectionMatrixBuffer buffer;
        //?} else {
        /*private CachedOrthoProjectionMatrixBuffer buffer;
        private float near = Float.NaN;
        private float far = Float.NaN;
        private boolean invertY;
        *///?}

        public Ortho(String label) {
            this.label = label;
        }

        public GpuBufferSlice get(float near, float far, float width, float height, boolean invertY) {
            //? if >=26.1 {
            if (buffer == null) {
                buffer = new ProjectionMatrixBuffer(label);
            }
            projection.setupOrtho(near, far, width, height, invertY);
            return buffer.getBuffer(projection);
            //?} else {
            /*if (buffer == null || near != this.near || far != this.far || invertY != this.invertY) {
                if (buffer != null) {
                    buffer.close();
                }
                buffer = new CachedOrthoProjectionMatrixBuffer(label, near, far, invertY);
                this.near = near;
                this.far = far;
                this.invertY = invertY;
            }
            return buffer.getBuffer(width, height);
            *///?}
        }
    }
}
