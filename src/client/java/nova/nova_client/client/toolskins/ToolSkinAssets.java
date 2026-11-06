package nova.nova_client.client.toolskins;

import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import nova.nova_client.client.RenderCompat;
import nova.nova_client.mixin.client.RenderPipelinesAccessor;
import nova.nova_client.mixin.client.RenderTypeInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.1 {
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.UniformType;
import net.minecraft.client.renderer.BindGroupLayouts;
//?}

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Hyper Tools: laedt die Laserschwert-Griffe (toolskins/tools.ntm, gebaut mit tools/toolskins/build.py) im Hintergrund
 * und beim ersten Zeichnen die Texturen (Albedo, Material, Studio-Umgebung) und die eigenen Shader (hyper_tool fuer
 * den Griff, saber_blade und saber_trail fuer Klinge und Schweif). Bis alles da ist, bleibt das Vanilla-Modell.
 */
public final class ToolSkinAssets {
    private static final Logger LOGGER = LoggerFactory.getLogger("NovaClient/HyperTools");
    private static final Identifier MESH_FILE = id("toolskins/tools.ntm");
    private static final Identifier ATLAS = id("toolskins/atlas_linear");
    private static final Identifier MATERIAL = id("toolskins/material_linear");
    private static final Identifier ENV = id("toolskins/env_linear");
    private static final Identifier SHADER = id("core/hyper_tool");

    private static volatile Map<String, ToolSkinMesh> meshes;
    private static volatile boolean loading;
    private static boolean failed;
    private static RenderType solid;
    private static RenderType blade;
    private static RenderType trail;

    private ToolSkinAssets() {
    }

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath("nova_client", path);
    }

    /** Modell zu einer Item-ID (z.B. "diamond_sword"), null solange es noch laedt. */
    public static ToolSkinMesh mesh(String itemId) {
        Map<String, ToolSkinMesh> map = meshes;
        if (map == null) {
            startLoading();
            return null;
        }
        return map.get(itemId);
    }

    private static synchronized void startLoading() {
        if (loading || meshes != null || failed) {
            return;
        }
        loading = true;
        Thread thread = new Thread(() -> {
            try {
                meshes = read(Minecraft.getInstance());
            } catch (IOException | RuntimeException e) {
                LOGGER.error("Could not load the tool models", e);
                failed = true;
            } finally {
                loading = false;
            }
        }, "Nova Hyper Tools");
        thread.setDaemon(true);
        thread.start();
    }

    /** Texturen und Shader anlegen (Render-Thread). null = noch nicht moeglich. */
    static RenderType renderType() {
        if (solid != null || failed) {
            return solid;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            register(client, id("toolskins/atlas.png"), ATLAS, false);
            register(client, id("toolskins/material.png"), MATERIAL, false);
            register(client, id("toolskins/env.png"), ENV, true);
            solid = RenderTypeInvoker.nova_client$create("nova_hyper_tool", RenderSetup.builder(pipeline())
                    .withTexture("Sampler0", ATLAS)
                    .withTexture("MaterialSampler", MATERIAL)
                    .withTexture("EnvSampler", ENV)
                    .useLightmap()
                    .useOverlay()
                    .createRenderSetup());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Could not set up the tool shader", e);
            failed = true;
        }
        return solid;
    }

    /**
     * Klinge: vormultipliziertes Alpha (Kern deckt, Schein leuchtet dazu), liest die Tiefe, schreibt sie nicht.
     * Nach renderType() aufrufen.
     */
    static RenderType bladeType() {
        if (blade == null && solid != null) {
            blade = glowType("saber_blade", "core/saber_blade");
        }
        return blade;
    }

    /** Schweif beim Schlagen: wie die Klinge, eigener Fragment-Shader. */
    static RenderType trailType() {
        if (trail == null && solid != null) {
            trail = glowType("saber_trail", "core/saber_trail");
        }
        return trail;
    }

    private static RenderType glowType(String name, String fragment) {
        RenderPipeline pipeline = RenderCompat.glowDepth(RenderCompat.builder(id("pipeline/" + name),
                id("core/saber_blade"), id(fragment), DefaultVertexFormat.POSITION_TEX_COLOR, true,
                BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA), true).build();
        return RenderTypeInvoker.nova_client$create("nova_" + name, RenderSetup.builder(pipeline).sortOnUpload()
                .createRenderSetup());
    }

    /** Wie die Vanilla-Entity-Pipeline, aber mit eigenem Shader und zwei zusaetzlichen Texturen. */
    private static RenderPipeline pipeline() {
        //? if >=26.1 {
        return RenderPipeline.builder(RenderPipelinesAccessor.nova_client$entitySnippet())
                .withLocation(id("pipeline/hyper_tool"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
                .withBindGroupLayout(BindGroupLayout.builder().withUniform("MaterialSampler", UniformType.COMBINED_IMAGE_SAMPLER)
                        .withUniform("EnvSampler", UniformType.COMBINED_IMAGE_SAMPLER).build())
                .withColorTargetState(com.mojang.renderpearl.api.pipeline.ColorTargetState.DEFAULT)
                .withCull(false)
                .build();
        //?} else {
        /*return RenderPipeline.builder(RenderPipelinesAccessor.nova_client$entitySnippet())
                .withLocation(id("pipeline/hyper_tool"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withSampler("Sampler1")
                .withSampler("MaterialSampler")
                .withSampler("EnvSampler")
                .withCull(false)
                .build();
        *///?}
    }

    private static void register(Minecraft client, Identifier file, Identifier id, boolean wrapU) throws IOException {
        NativeImage image;
        try (InputStream in = open(client, file)) {
            image = NativeImage.read(in);
        }
        DynamicTexture texture = new DynamicTexture(id::toString, image) {
            {
                // Glatt statt pixelig; die Umgebung laeuft rundherum (U wiederholt)
                this.sampler = RenderSystem.getSamplerCache().getSampler(wrapU ? AddressMode.REPEAT : AddressMode.CLAMP_TO_EDGE,
                        AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, false);
            }
        };
        client.getTextureManager().register(id, texture);
    }

    private static InputStream open(Minecraft client, Identifier id) throws IOException {
        Optional<Resource> resource = client.getResourceManager().getResource(id);
        if (resource.isEmpty()) {
            throw new IOException("missing " + id);
        }
        return resource.get().open();
    }

    private static Map<String, ToolSkinMesh> read(Minecraft client) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(open(client, MESH_FILE), 1 << 16))) {
            if (in.readInt() != 0x4E544D33) { // "NTM3"
                throw new IOException("bad tool mesh file");
            }
            int materialCount = in.readInt();
            float[] glow = new float[materialCount];
            byte[] pulse = new byte[materialCount];
            for (int i = 0; i < materialCount; i++) {
                glow[i] = in.readFloat();
                pulse[i] = in.readByte();
            }
            int count = in.readInt();
            Map<String, ToolSkinMesh> result = new HashMap<>(count * 2);
            for (int m = 0; m < count; m++) {
                String id = in.readUTF();
                int type = in.readByte();
                float[] bounds = new float[6];
                for (int i = 0; i < 6; i++) {
                    bounds[i] = in.readFloat();
                }
                int vertices = in.readInt();
                int quads = in.readInt();
                int morphCount = in.readInt();
                float[] pos = new float[vertices * 3];
                float[] normal = new float[vertices * 3];
                float[] uv = new float[vertices * 2];
                byte[] mat = new byte[vertices];
                byte[] ao = new byte[vertices];
                for (int v = 0; v < vertices; v++) {
                    pos[v * 3] = in.readShort() / 1000.0F;
                    pos[v * 3 + 1] = in.readShort() / 1000.0F;
                    pos[v * 3 + 2] = in.readShort() / 1000.0F;
                    normal[v * 3] = in.readShort() / 32767.0F;
                    normal[v * 3 + 1] = in.readShort() / 32767.0F;
                    normal[v * 3 + 2] = in.readShort() / 32767.0F;
                    uv[v * 2] = in.readUnsignedShort() / 65535.0F;
                    uv[v * 2 + 1] = in.readUnsignedShort() / 65535.0F;
                    mat[v] = in.readByte();
                    ao[v] = in.readByte();
                }
                float[][] morphPos = new float[morphCount][];
                float[][] morphNormal = new float[morphCount][];
                for (int k = 0; k < morphCount; k++) {
                    morphPos[k] = new float[vertices * 3];
                    morphNormal[k] = new float[vertices * 3];
                    for (int v = 0; v < vertices; v++) {
                        morphPos[k][v * 3] = in.readShort() / 1000.0F;
                        morphPos[k][v * 3 + 1] = in.readShort() / 1000.0F;
                        morphPos[k][v * 3 + 2] = in.readShort() / 1000.0F;
                        morphNormal[k][v * 3] = in.readShort() / 32767.0F;
                        morphNormal[k][v * 3 + 1] = in.readShort() / 32767.0F;
                        morphNormal[k][v * 3 + 2] = in.readShort() / 32767.0F;
                    }
                }
                int[] index = new int[quads * 4];
                for (int i = 0; i < index.length; i++) {
                    index[i] = in.readUnsignedShort();
                }
                float[] energy = {in.readFloat(), in.readFloat(), in.readFloat()};
                ToolSkinMesh.Path[] paths = new ToolSkinMesh.Path[in.readInt()];
                for (int k = 0; k < paths.length; k++) {
                    int points = in.readInt();
                    float intensity = in.readFloat();
                    int show = in.readByte();
                    float[] p = new float[points * 3];
                    float[] d = new float[points * 3];
                    float[] w = new float[points];
                    float[] t = new float[points];
                    for (int i = 0; i < points; i++) {
                        for (int j = 0; j < 3; j++) {
                            p[i * 3 + j] = in.readFloat();
                        }
                        for (int j = 0; j < 3; j++) {
                            d[i * 3 + j] = in.readFloat();
                        }
                        w[i] = in.readFloat();
                        t[i] = in.readFloat();
                    }
                    paths[k] = new ToolSkinMesh.Path(p, d, w, t, intensity, show);
                }
                result.put(id, new ToolSkinMesh(id, type, bounds, pos, normal, uv, mat, ao, index, glow, pulse, morphPos,
                        morphNormal, energy, paths));
            }
            LOGGER.info("Loaded {} tool models", result.size());
            return result;
        }
    }
}
