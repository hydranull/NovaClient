plugins {
    id("dev.kikugie.stonecutter")
    // Loom fuer alle Versionen bereitstellen; build.gradle waehlt je Version die passende Variante
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT" apply false
}

// Version, die gerade in src/ steht (IntelliJ: Gradle -> stonecutter -> "Set active project to ...")
stonecutter active "26.3"

// Umbenennungen zwischen den Minecraft-Versionen: im Code steht immer der Name der aktiven Version, beim Umschalten
// bzw. beim Bauen der anderen Version tauscht Stonecutter automatisch. Alles mit Wortgrenzen (\b), damit z.B.
// "GuiGraphics" nicht in "GuiGraphicsExtractor" hineinersetzt wird.
stonecutter parameters {
    val mc26 = current.parsed >= "26.1"

    // name in 1.21.11 -> name in 26.x
    fun rename(old: String, new: String) = replacements.regex(mc26) {
        replace("\\b$old\\b", new, "\\b$new\\b", old)
    }

    // Methoden-Umbenennung nur auf dem GUI-Objekt (Variablen heissen im Projekt immer g oder graphics)
    fun gui(old: String, new: String) = replacements.regex(mc26) {
        replace("\\b(g|graphics)\\.$old\\(", "$1.$new(", "\\b(g|graphics)\\.$new\\(", "$1.$old(")
    }

    // freie Regex-Ersetzung: oldPattern (Text in 1.21.11) -> toNew, newPattern (Text in 26.x) -> toOld
    fun swap(oldPattern: String, toNew: String, newPattern: String, toOld: String) = replacements.regex(mc26) {
        replace(oldPattern, toNew, newPattern, toOld)
    }

    // Bildschirme: 26.x verwaltet sie in Minecraft.gui, 1.21.11 direkt in Minecraft
    // nur echte Minecraft-Referenzen, nicht z.B. Paketnamen wie "fabric.api.client.screen.v1"
    val mcRef = "(Minecraft\\.getInstance\\(\\)|(?<![.\\w])client|(?<![.\\w])minecraft|(?<![.\\w])mc|this\\))"
    swap("$mcRef\\.screen\\b(?!\\s*\\()", "$1.gui.screen()", "$mcRef\\.gui\\.screen\\(\\)", "$1.screen")
    swap("$mcRef\\.setScreen\\(", "$1.gui.setScreen(", "$mcRef\\.gui\\.setScreen\\(", "$1.setScreen(")
    swap("$mcRef\\.getOverlay\\(\\)", "$1.gui.overlay()", "$mcRef\\.gui\\.overlay\\(\\)", "$1.getOverlay()")
    swap("$mcRef\\.getChatListener\\(\\)", "$1.gui.chatListener()", "$mcRef\\.gui\\.chatListener\\(\\)", "$1.getChatListener()")

    // Inventar-Klicks
    rename("ClickType", "ContainerInput")
    rename("handleInventoryMouseClick", "handleContainerInput")

    // GameRenderer
    rename("getMainCamera", "mainCamera")

    // ChunkPos
    swap("\\bChunkPos\\.asLong\\(", "ChunkPos.pack(", "\\bChunkPos\\.pack\\(", "ChunkPos.asLong(")

    // Screen/Tooltip-Methoden: render... heisst in 26.x extract... (nur die Overrides mit GUI-Parameter, eigene
    // static render-Methoden bleiben, wie sie sind)
    // Achtung: alle Regeln laufen in einem Durchgang - den Typnamen nur pruefen (Lookahead), nicht ersetzen, sonst
    // greift die GuiGraphics-Umbenennung an dieser Stelle nicht mehr
    val gg = "(?=GuiGraphics(?:Extractor)? )"
    swap("public void render\\($gg", "public void extractRenderState(", "public void extractRenderState\\($gg", "public void render(")
    swap("public void renderBackground\\($gg", "public void extractBackground(", "public void extractBackground\\($gg", "public void renderBackground(")
    swap("super\\.render\\((g|graphics),", "super.extractRenderState($1,", "super\\.extractRenderState\\((g|graphics),", "super.render($1,")
    swap("super\\.renderBackground\\((g|graphics),", "super.extractBackground($1,", "super\\.extractBackground\\((g|graphics),", "super.renderBackground($1,")
    rename("renderImage", "extractImage")

    // Spielerkoepfe im GUI
    rename("PlayerFaceRenderer", "PlayerFaceExtractor")
    swap("\\bPlayerFace(?:Renderer|Extractor)\\.draw\\(", "PlayerFaceExtractor.extractRenderState(",
            "\\bPlayerFace(?:Renderer|Extractor)\\.extractRenderState\\(", "PlayerFaceRenderer.draw(")

    // Welt neu bauen, Chat, Fenster
    swap("\\.levelRenderer\\.allChanged\\(\\)", ".levelExtractor.allChanged()", "\\.levelExtractor\\.allChanged\\(\\)", ".levelRenderer.allChanged()")
    swap("\\.gui\\.getChat\\(\\)", ".gui.hud.getChat()", "\\.gui\\.hud\\.getChat\\(\\)", ".gui.getChat()")
    rename("resizeDisplay", "resizeGui")

    // Bloecke / Eigenschaften
    rename("DRIPSTONE_THICKNESS", "SPELEOTHEM_THICKNESS")
    swap("\\bBlocks\\.BLUE_TERRACOTTA\\b", "Blocks.DYED_TERRACOTTA.blue()", "\\bBlocks\\.DYED_TERRACOTTA\\.blue\\(\\)", "Blocks.BLUE_TERRACOTTA")
    swap("\\bBlocks\\.ORANGE_TERRACOTTA\\b", "Blocks.DYED_TERRACOTTA.orange()", "\\bBlocks\\.DYED_TERRACOTTA\\.orange\\(\\)", "Blocks.ORANGE_TERRACOTTA")

    // Fabric API
    rename("afterRender", "afterExtract")
    rename("beforeRender", "beforeExtract")
    rename("KeyBindingHelper", "KeyMappingHelper")
    rename("registerKeyBinding", "registerKeyMapping")
    swap("\\.client\\.keybinding\\.v1\\.", ".client.keymapping.v1.", "\\.client\\.keymapping\\.v1\\.", ".client.keybinding.v1.")
    rename("TooltipComponentCallback", "ClientTooltipComponentCallback")
    rename("WorldRenderEvents", "LevelRenderEvents")
    rename("WorldRenderContext", "LevelRenderContext")
    swap("\\.rendering\\.v1\\.world\\.", ".rendering.v1.level.", "\\.rendering\\.v1\\.level\\.", ".rendering.v1.world.")
    swap("\\b(?:World|Level)RenderEvents\\.BEFORE_ENTITIES\\b", "LevelRenderEvents.COLLECT_SUBMITS",
            "\\b(?:World|Level)RenderEvents\\.COLLECT_SUBMITS\\b", "WorldRenderEvents.BEFORE_ENTITIES")
    swap("\\bcontext\\.commandQueue\\(\\)", "context.submitNodeCollector()", "\\bcontext\\.submitNodeCollector\\(\\)", "context.commandQueue()")
    swap("\\bcontext\\.matrices\\(\\)", "context.poseStack()", "\\bcontext\\.poseStack\\(\\)", "context.matrices()")

    // Mixin-Ziele (nur exakte Strings in Anfuehrungszeichen, gefolgt von " oder einer Signatur)
    fun target(old: String, new: String) = swap("\"$old(?=[(\"])", "\"$new", "\"$new(?=[(\"])", "\"$old")
    target("renderWithTooltipAndSubtitles", "extractRenderStateWithTooltipAndSubtitles")
    target("renderBlurredBackground", "extractBlurredBackground")
    target("renderMenuBackground", "extractMenuBackground")
    target("renderPanorama", "extractPanorama")
    target("renderBackground", "extractBackground")
    target("pickBlock", "pickBlockOrEntity")
    target("renderWater", "submitWater")
    target("renderFire", "submitFire")
    swap("net/minecraft/client/renderer/state/CameraRenderState;", "net/minecraft/client/renderer/state/level/CameraRenderState;",
            "net/minecraft/client/renderer/state/level/CameraRenderState;", "net/minecraft/client/renderer/state/CameraRenderState;")

    // Klassen, die in 26.x in ein anderes Paket gezogen sind
    swap("\\bnet\\.minecraft\\.world\\.level\\.BlockAndTintGetter\\b", "net.minecraft.client.renderer.block.BlockAndTintGetter",
            "\\bnet\\.minecraft\\.client\\.renderer\\.block\\.BlockAndTintGetter\\b", "net.minecraft.world.level.BlockAndTintGetter")
    swap("\\bnet\\.minecraft\\.client\\.gui\\.render\\.state\\.GuiTextRenderState\\b", "net.minecraft.client.renderer.state.gui.GuiTextRenderState",
            "\\bnet\\.minecraft\\.client\\.renderer\\.state\\.gui\\.GuiTextRenderState\\b", "net.minecraft.client.gui.render.state.GuiTextRenderState")
    swap("\\bnet\\.minecraft\\.client\\.renderer\\.state\\.CameraRenderState\\b", "net.minecraft.client.renderer.state.level.CameraRenderState",
            "\\bnet\\.minecraft\\.client\\.renderer\\.state\\.level\\.CameraRenderState\\b", "net.minecraft.client.renderer.state.CameraRenderState")

    // Haupt-Render-Ziel: 26.x im GameRenderer, 1.21.11 in Minecraft
    swap("$mcRef\\.getMainRenderTarget\\(\\)", "$1.gameRenderer.mainRenderTarget()",
            "$mcRef\\.gameRenderer\\.mainRenderTarget\\(\\)", "$1.getMainRenderTarget()")

    // 26.3: Tasten kommen von SDL statt GLFW - isKeyDown braucht kein Fenster mehr, KEYSYM heisst KEYBOARD
    swap("InputConstants\\.isKeyDown\\((?:\\w+|net\\.minecraft\\.client\\.Minecraft\\.getInstance\\(\\))\\.getWindow\\(\\), ", "InputConstants.isKeyDown(",
            "InputConstants\\.isKeyDown\\((?!(?:\\w+|net\\.minecraft\\.client\\.Minecraft\\.getInstance\\(\\))\\.getWindow\\(\\))", "InputConstants.isKeyDown(net.minecraft.client.Minecraft.getInstance().getWindow(), ")
    swap("\\bInputConstants\\.Type\\.KEYSYM\\b", "InputConstants.Type.KEYBOARD", "\\bInputConstants\\.Type\\.KEYBOARD\\b", "InputConstants.Type.KEYSYM")
    // 26.3: Schwingen auf dem Client heisst "vorhergesagt"
    swap("\\bSwingSource\\.CLIENT\\b", "SwingSource.PREDICTED", "\\bSwingSource\\.PREDICTED\\b", "SwingSource.CLIENT")
    rename("RedStoneWireBlock", "RedstoneWireBlock")
    // 26.3: die Ego-Hand zeichnet FirstPersonHandsAndItemsRenderer (mit Render-States statt Spieler)
    rename("ItemInHandRenderer", "FirstPersonHandsAndItemsRenderer")

    // 26.3: Pakete sind Records (x() statt getX(), lightData() statt getLightData(), ...)
    swap("\\bpacket\\.getX\\(\\)", "packet.x()", "\\bpacket\\.x\\(\\)", "packet.getX()")
    swap("\\bpacket\\.getY\\(\\)", "packet.y()", "\\bpacket\\.y\\(\\)", "packet.getY()")
    swap("\\bpacket\\.getZ\\(\\)", "packet.z()", "\\bpacket\\.z\\(\\)", "packet.getZ()")
    swap("\\bpacket\\.getLightData\\(\\)", "packet.lightData()", "\\bpacket\\.lightData\\(\\)", "packet.getLightData()")
    swap("\\bpacket\\.getChunkData\\(\\)", "packet.chunkData()", "\\bpacket\\.chunkData\\(\\)", "packet.getChunkData()")
    swap("\\bpacket\\.getParticle\\(\\)", "packet.particle()", "\\bpacket\\.particle\\(\\)", "packet.getParticle()")
    swap("\\buse\\.getHitResult\\(\\)", "use.hitResult()", "\\buse\\.hitResult\\(\\)", "use.getHitResult()")
    swap("\\.getSkyYMask\\(\\)", ".skyYMask()", "\\.skyYMask\\(\\)", ".getSkyYMask()")
    swap("\\.getBlockYMask\\(\\)", ".blockYMask()", "\\.blockYMask\\(\\)", ".getBlockYMask()")
    swap("\\.getEmptySkyYMask\\(\\)", ".emptySkyYMask()", "\\.emptySkyYMask\\(\\)", ".getEmptySkyYMask()")
    swap("\\.getEmptyBlockYMask\\(\\)", ".emptyBlockYMask()", "\\.emptyBlockYMask\\(\\)", ".getEmptyBlockYMask()")
    swap("\\.getSkyUpdates\\(\\)", ".skyUpdates()", "\\.skyUpdates\\(\\)", ".getSkyUpdates()")
    swap("\\.getBlockUpdates\\(\\)", ".blockUpdates()", "\\.blockUpdates\\(\\)", ".getBlockUpdates()")

    // 26.3: die Render-Grundlagen (Pipelines, Texturen, Puffer, Render-Paesse) sind aus com.mojang.blaze3d in
    // com.mojang.renderpearl.api gezogen. Im Code steht der 26.3-Name, fuer 1.21.11 wird zurueckgetauscht.
    swap("\\bcom\\.mojang\\.blaze3d\\.GpuFormat\\b", "com.mojang.renderpearl.api.GpuFormat", "\\bcom\\.mojang\\.renderpearl\\.api\\.GpuFormat\\b", "com.mojang.blaze3d.GpuFormat")
    swap("\\bcom\\.mojang\\.blaze3d\\.PrimitiveTopology\\b", "com.mojang.renderpearl.api.pipeline.PrimitiveTopology", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.PrimitiveTopology\\b", "com.mojang.blaze3d.PrimitiveTopology")
    swap("\\bcom\\.mojang\\.blaze3d\\.buffers\\.GpuBuffer\\b", "com.mojang.renderpearl.api.buffers.GpuBuffer", "\\bcom\\.mojang\\.renderpearl\\.api\\.buffers\\.GpuBuffer\\b", "com.mojang.blaze3d.buffers.GpuBuffer")
    swap("\\bcom\\.mojang\\.blaze3d\\.buffers\\.GpuBufferSlice\\b", "com.mojang.renderpearl.api.buffers.GpuBufferSlice", "\\bcom\\.mojang\\.renderpearl\\.api\\.buffers\\.GpuBufferSlice\\b", "com.mojang.blaze3d.buffers.GpuBufferSlice")
    swap("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.BindGroupLayout\\b", "com.mojang.renderpearl.api.pipeline.BindGroupLayout", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.BindGroupLayout\\b", "com.mojang.blaze3d.pipeline.BindGroupLayout")
    swap("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.BlendFunction\\b", "com.mojang.renderpearl.api.pipeline.BlendFunction", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.BlendFunction\\b", "com.mojang.blaze3d.pipeline.BlendFunction")
    swap("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.ColorTargetState\\b", "com.mojang.renderpearl.api.pipeline.ColorTargetState", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.ColorTargetState\\b", "com.mojang.blaze3d.pipeline.ColorTargetState")
    swap("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.DepthStencilState\\b", "com.mojang.renderpearl.api.pipeline.DepthStencilState", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.DepthStencilState\\b", "com.mojang.blaze3d.pipeline.DepthStencilState")
    swap("\\bcom\\.mojang\\.blaze3d\\.pipeline\\.RenderPipeline\\b", "com.mojang.renderpearl.api.pipeline.RenderPipeline", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.RenderPipeline\\b", "com.mojang.blaze3d.pipeline.RenderPipeline")
    swap("\\bcom\\.mojang\\.blaze3d\\.platform\\.CompareOp\\b", "com.mojang.renderpearl.api.pipeline.CompareOp", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.CompareOp\\b", "com.mojang.blaze3d.platform.CompareOp")
    swap("\\bcom\\.mojang\\.blaze3d\\.shaders\\.UniformType\\b", "com.mojang.renderpearl.api.pipeline.UniformType", "\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.UniformType\\b", "com.mojang.blaze3d.shaders.UniformType")
    swap("\\bcom\\.mojang\\.blaze3d\\.systems\\.CommandEncoder\\b", "com.mojang.renderpearl.api.commands.CommandEncoder", "\\bcom\\.mojang\\.renderpearl\\.api\\.commands\\.CommandEncoder\\b", "com.mojang.blaze3d.systems.CommandEncoder")
    swap("\\bcom\\.mojang\\.blaze3d\\.systems\\.RenderPass\\b", "com.mojang.renderpearl.api.commands.RenderPass", "\\bcom\\.mojang\\.renderpearl\\.api\\.commands\\.RenderPass\\b", "com.mojang.blaze3d.systems.RenderPass")
    swap("\\bcom\\.mojang\\.blaze3d\\.textures\\.AddressMode\\b", "com.mojang.renderpearl.api.textures.AddressMode", "\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\.AddressMode\\b", "com.mojang.blaze3d.textures.AddressMode")
    swap("\\bcom\\.mojang\\.blaze3d\\.textures\\.FilterMode\\b", "com.mojang.renderpearl.api.textures.FilterMode", "\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\.FilterMode\\b", "com.mojang.blaze3d.textures.FilterMode")
    swap("\\bcom\\.mojang\\.blaze3d\\.textures\\.GpuSampler\\b", "com.mojang.renderpearl.api.textures.GpuSampler", "\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\.GpuSampler\\b", "com.mojang.blaze3d.textures.GpuSampler")
    swap("\\bcom\\.mojang\\.blaze3d\\.textures\\.GpuTexture\\b", "com.mojang.renderpearl.api.textures.GpuTexture", "\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\.GpuTexture\\b", "com.mojang.blaze3d.textures.GpuTexture")
    swap("\\bcom\\.mojang\\.blaze3d\\.textures\\.GpuTextureView\\b", "com.mojang.renderpearl.api.textures.GpuTextureView", "\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\.GpuTextureView\\b", "com.mojang.blaze3d.textures.GpuTextureView")
    swap("\\bcom\\.mojang\\.blaze3d\\.vertex\\.VertexFormat\\b", "com.mojang.renderpearl.api.vertex.VertexFormat", "\\bcom\\.mojang\\.renderpearl\\.api\\.vertex\\.VertexFormat\\b", "com.mojang.blaze3d.vertex.VertexFormat")

    // 26.3: dieselben Umzuege in Mixin-Zielen (Deskriptoren mit / statt .)
    swap("com/mojang/blaze3d/buffers/GpuBuffer(?=[;$\"])", "com/mojang/renderpearl/api/buffers/GpuBuffer", "com/mojang/renderpearl/api/buffers/GpuBuffer(?=[;$\"])", "com/mojang/blaze3d/buffers/GpuBuffer")
    swap("com/mojang/blaze3d/buffers/GpuBufferSlice(?=[;$\"])", "com/mojang/renderpearl/api/buffers/GpuBufferSlice", "com/mojang/renderpearl/api/buffers/GpuBufferSlice(?=[;$\"])", "com/mojang/blaze3d/buffers/GpuBufferSlice")
    swap("com/mojang/blaze3d/pipeline/RenderPipeline(?=[;$\"])", "com/mojang/renderpearl/api/pipeline/RenderPipeline", "com/mojang/renderpearl/api/pipeline/RenderPipeline(?=[;$\"])", "com/mojang/blaze3d/pipeline/RenderPipeline")
    swap("com/mojang/blaze3d/systems/CommandEncoder(?=[;$\"])", "com/mojang/renderpearl/api/commands/CommandEncoder", "com/mojang/renderpearl/api/commands/CommandEncoder(?=[;$\"])", "com/mojang/blaze3d/systems/CommandEncoder")
    swap("com/mojang/blaze3d/textures/FilterMode(?=[;$\"])", "com/mojang/renderpearl/api/textures/FilterMode", "com/mojang/renderpearl/api/textures/FilterMode(?=[;$\"])", "com/mojang/blaze3d/textures/FilterMode")
    swap("com/mojang/blaze3d/textures/GpuSampler(?=[;$\"])", "com/mojang/renderpearl/api/textures/GpuSampler", "com/mojang/renderpearl/api/textures/GpuSampler(?=[;$\"])", "com/mojang/blaze3d/textures/GpuSampler")
    swap("com/mojang/blaze3d/textures/GpuTexture(?=[;$\"])", "com/mojang/renderpearl/api/textures/GpuTexture", "com/mojang/renderpearl/api/textures/GpuTexture(?=[;$\"])", "com/mojang/blaze3d/textures/GpuTexture")
    swap("com/mojang/blaze3d/vertex/VertexFormat(?=[;$\"])", "com/mojang/renderpearl/api/vertex/VertexFormat", "com/mojang/renderpearl/api/vertex/VertexFormat(?=[;$\"])", "com/mojang/blaze3d/vertex/VertexFormat")

    rename("GuiGraphics", "GuiGraphicsExtractor")
    gui("drawString", "text")
    gui("drawCenteredString", "centeredText")
    gui("renderItem", "item")
    gui("renderItemDecorations", "itemDecorations")
    gui("renderFakeItem", "fakeItem")
    gui("renderOutline", "outline")
    gui("renderTooltip", "tooltip")
    gui("hLine", "horizontalLine")
    gui("vLine", "verticalLine")
    gui("drawStringWithBackdrop", "textWithBackdrop")
    gui("drawWordWrap", "textWithWordWrap")
    gui("submitEntityRenderState", "entity")
}
