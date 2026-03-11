package net.minecraft.client.renderer;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import com.mojang.blaze3d.framegraph.FramePass;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.blaze3d.resource.RenderTargetDescriptor;
import com.mojang.blaze3d.resource.ResourceHandle;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.SortedSet;
import net.minecraft.SharedConstants;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionBuffers;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.client.renderer.debug.GameTestBlockHighlightRenderer;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.BlockOutlineRenderState;
import net.minecraft.client.renderer.state.CameraRenderState;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.client.renderer.state.ParticlesRenderState;
import net.minecraft.client.renderer.state.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.SimpleGizmoCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.ARGB;
import net.minecraft.util.Brightness;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.util.VisibleForDebug;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class LevelRenderer implements ResourceManagerReloadListener, AutoCloseable {
    private static final Identifier TRANSPARENCY_POST_CHAIN_ID = Identifier.withDefaultNamespace("transparency");
    private static final Identifier ENTITY_OUTLINE_POST_CHAIN_ID = Identifier.withDefaultNamespace("entity_outline");
    public static final int SECTION_SIZE = 16;
    public static final int HALF_SECTION_SIZE = 8;
    public static final int NEARBY_SECTION_DISTANCE_IN_BLOCKS = 32;
    private static final int MINIMUM_TRANSPARENT_SORT_COUNT = 15;
    private static final float CHUNK_VISIBILITY_THRESHOLD = 0.3F;
    private final Minecraft minecraft;
    private final EntityRenderDispatcher entityRenderDispatcher;
    private final BlockEntityRenderDispatcher blockEntityRenderDispatcher;
    private final RenderBuffers renderBuffers;
    private @Nullable SkyRenderer skyRenderer;
    private final CloudRenderer cloudRenderer = new CloudRenderer();
    private final WorldBorderRenderer worldBorderRenderer = new WorldBorderRenderer();
    private final WeatherEffectRenderer weatherEffectRenderer = new WeatherEffectRenderer();
    private final ParticlesRenderState particlesRenderState = new ParticlesRenderState();
    public final DebugRenderer debugRenderer = new DebugRenderer();
    public final GameTestBlockHighlightRenderer gameTestBlockHighlightRenderer = new GameTestBlockHighlightRenderer();
    private @Nullable ClientLevel level;
    private final SectionOcclusionGraph sectionOcclusionGraph = new SectionOcclusionGraph();
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections = new ObjectArrayList<>(10000);
    private final ObjectArrayList<SectionRenderDispatcher.RenderSection> nearbyVisibleSections = new ObjectArrayList<>(50);
    private @Nullable ViewArea viewArea;
    private int ticks;
    private final Int2ObjectMap<BlockDestructionProgress> destroyingBlocks = new Int2ObjectOpenHashMap<>();
    private final Long2ObjectMap<SortedSet<BlockDestructionProgress>> destructionProgress = new Long2ObjectOpenHashMap<>();
    private @Nullable RenderTarget entityOutlineTarget;
    private final LevelTargetBundle targets = new LevelTargetBundle();
    private int lastCameraSectionX = Integer.MIN_VALUE;
    private int lastCameraSectionY = Integer.MIN_VALUE;
    private int lastCameraSectionZ = Integer.MIN_VALUE;
    private double prevCamX = Double.MIN_VALUE;
    private double prevCamY = Double.MIN_VALUE;
    private double prevCamZ = Double.MIN_VALUE;
    private double prevCamRotX = Double.MIN_VALUE;
    private double prevCamRotY = Double.MIN_VALUE;
    private @Nullable SectionRenderDispatcher sectionRenderDispatcher;
    private int lastViewDistance = -1;
    private boolean captureFrustum;
    private @Nullable Frustum capturedFrustum;
    private @Nullable BlockPos lastTranslucentSortBlockPos;
    private int translucencyResortIterationIndex;
    final LevelRenderState levelRenderState;
    private final SubmitNodeStorage submitNodeStorage;
    private final FeatureRenderDispatcher featureRenderDispatcher;
    private @Nullable GpuSampler chunkLayerSampler;
    private final SimpleGizmoCollector collectedGizmos = new SimpleGizmoCollector();
    private LevelRenderer.FinalizedGizmos finalizedGizmos = new LevelRenderer.FinalizedGizmos(new DrawableGizmoPrimitives(), new DrawableGizmoPrimitives());

    public LevelRenderer(
        Minecraft minecraft,
        EntityRenderDispatcher entityRenderDispatcher,
        BlockEntityRenderDispatcher blockEntityRenderDispatcher,
        RenderBuffers renderBuffers,
        LevelRenderState levelRenderState,
        FeatureRenderDispatcher featureRenderDispatcher
    ) {
        this.minecraft = minecraft;
        this.entityRenderDispatcher = entityRenderDispatcher;
        this.blockEntityRenderDispatcher = blockEntityRenderDispatcher;
        this.renderBuffers = renderBuffers;
        this.submitNodeStorage = featureRenderDispatcher.getSubmitNodeStorage();
        this.levelRenderState = levelRenderState;
        this.featureRenderDispatcher = featureRenderDispatcher;
    }

    @Override
    public void close() {
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
        }

        if (this.skyRenderer != null) {
            this.skyRenderer.close();
        }

        if (this.chunkLayerSampler != null) {
            this.chunkLayerSampler.close();
        }

        this.cloudRenderer.close();
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        this.initOutline();
        if (this.skyRenderer != null) {
            this.skyRenderer.close();
        }

        this.skyRenderer = new SkyRenderer(this.minecraft.getTextureManager(), this.minecraft.getAtlasManager());
    }

    public void initOutline() {
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.destroyBuffers();
        }

        this.entityOutlineTarget = new TextureTarget("Entity Outline", this.minecraft.getWindow().getWidth(), this.minecraft.getWindow().getHeight(), true);
    }

    private @Nullable PostChain getTransparencyChain() {
        if (!Minecraft.useShaderTransparency()) {
            return null;
        } else {
            PostChain postchain = this.minecraft.getShaderManager().getPostChain(TRANSPARENCY_POST_CHAIN_ID, LevelTargetBundle.SORTING_TARGETS);
            if (postchain == null) {
                this.minecraft.options.improvedTransparency().set(false);
                this.minecraft.options.save();
            }

            return postchain;
        }
    }

    public void doEntityOutline() {
        if (this.shouldShowEntityOutlines()) {
            this.entityOutlineTarget.blitAndBlendToTexture(this.minecraft.getMainRenderTarget().getColorTextureView());
        }
    }

    public boolean shouldShowEntityOutlines() {
        return !this.minecraft.gameRenderer.isPanoramicMode() && this.entityOutlineTarget != null && this.minecraft.player != null;
    }

    /**
     * @param level the level to set, or {@code null} to clear
     */
    public void setLevel(@Nullable ClientLevel level) {
        this.lastCameraSectionX = Integer.MIN_VALUE;
        this.lastCameraSectionY = Integer.MIN_VALUE;
        this.lastCameraSectionZ = Integer.MIN_VALUE;
        this.level = level;
        if (level != null) {
            this.allChanged();
        } else {
            this.entityRenderDispatcher.resetCamera();
            if (this.viewArea != null) {
                this.viewArea.releaseAllBuffers();
                this.viewArea = null;
            }

            if (this.sectionRenderDispatcher != null) {
                this.sectionRenderDispatcher.dispose();
            }

            this.sectionRenderDispatcher = null;
            this.sectionOcclusionGraph.waitAndReset(null);
            this.clearVisibleSections();
        }

        this.gameTestBlockHighlightRenderer.clear();
    }

    private void clearVisibleSections() {
        this.visibleSections.clear();
        this.nearbyVisibleSections.clear();
    }

    public void allChanged() {
        if (this.level != null) {
            this.level.clearTintCaches();
            if (this.sectionRenderDispatcher == null) {
                this.sectionRenderDispatcher = new SectionRenderDispatcher(
                    this.level,
                    this,
                    Util.backgroundExecutor(),
                    this.renderBuffers,
                    this.minecraft.getBlockRenderer(),
                    this.minecraft.getBlockEntityRenderDispatcher()
                );
            } else {
                this.sectionRenderDispatcher.setLevel(this.level);
            }

            this.cloudRenderer.markForRebuild();
            ItemBlockRenderTypes.setCutoutLeaves(this.minecraft.options.cutoutLeaves().get());
            LeavesBlock.setCutoutLeaves(this.minecraft.options.cutoutLeaves().get());
            this.lastViewDistance = this.minecraft.options.getEffectiveRenderDistance();
            if (this.viewArea != null) {
                this.viewArea.releaseAllBuffers();
            }

            this.sectionRenderDispatcher.clearCompileQueue();
            this.viewArea = new ViewArea(this.sectionRenderDispatcher, this.level, this.minecraft.options.getEffectiveRenderDistance(), this);
            this.sectionOcclusionGraph.waitAndReset(this.viewArea);
            this.clearVisibleSections();
            Camera camera = this.minecraft.gameRenderer.getMainCamera();
            this.viewArea.repositionCamera(SectionPos.of(camera.position()));
        }
    }

    public void resize(int width, int height) {
        this.needsUpdate();
        if (this.entityOutlineTarget != null) {
            this.entityOutlineTarget.resize(width, height);
        }
    }

    public @Nullable String getSectionStatistics() {
        if (this.viewArea == null) {
            return null;
        } else {
            int i = this.viewArea.sections.length;
            int j = this.countRenderedSections();
            return String.format(
                Locale.ROOT,
                "C: %d/%d %sD: %d, %s",
                j,
                i,
                this.minecraft.smartCull ? "(s) " : "",
                this.lastViewDistance,
                this.sectionRenderDispatcher == null ? "null" : this.sectionRenderDispatcher.getStats()
            );
        }
    }

    public @Nullable SectionRenderDispatcher getSectionRenderDispatcher() {
        return this.sectionRenderDispatcher;
    }

    public double getTotalSections() {
        return this.viewArea == null ? 0.0 : this.viewArea.sections.length;
    }

    public double getLastViewDistance() {
        return this.lastViewDistance;
    }

    public int countRenderedSections() {
        int i = 0;

        for (SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection : this.visibleSections) {
            if (sectionrenderdispatcher$rendersection.getSectionMesh().hasRenderableLayers()) {
                i++;
            }
        }

        return i;
    }

    public void resetSampler() {
        if (this.chunkLayerSampler != null) {
            this.chunkLayerSampler.close();
        }

        this.chunkLayerSampler = null;
    }

    public @Nullable String getEntityStatistics() {
        return this.level == null
            ? null
            : "E: " + this.levelRenderState.entityRenderStates.size() + "/" + this.level.getEntityCount() + ", SD: " + this.level.getServerSimulationDistance();
    }

    private void cullTerrain(Camera camera, Frustum frustum, boolean spectator) {
        Vec3 vec3 = camera.position();
        if (this.minecraft.options.getEffectiveRenderDistance() != this.lastViewDistance) {
            this.allChanged();
        }

        ProfilerFiller profilerfiller = Profiler.get();
        profilerfiller.push("repositionCamera");
        int i = SectionPos.posToSectionCoord(vec3.x());
        int j = SectionPos.posToSectionCoord(vec3.y());
        int k = SectionPos.posToSectionCoord(vec3.z());
        if (this.lastCameraSectionX != i || this.lastCameraSectionY != j || this.lastCameraSectionZ != k) {
            this.lastCameraSectionX = i;
            this.lastCameraSectionY = j;
            this.lastCameraSectionZ = k;
            this.viewArea.repositionCamera(SectionPos.of(vec3));
            this.worldBorderRenderer.invalidate();
        }

        this.sectionRenderDispatcher.setCameraPosition(vec3);
        double d0 = Math.floor(vec3.x / 8.0);
        double d1 = Math.floor(vec3.y / 8.0);
        double d2 = Math.floor(vec3.z / 8.0);
        if (d0 != this.prevCamX || d1 != this.prevCamY || d2 != this.prevCamZ) {
            this.sectionOcclusionGraph.invalidate();
        }

        this.prevCamX = d0;
        this.prevCamY = d1;
        this.prevCamZ = d2;
        profilerfiller.pop();
        if (this.capturedFrustum == null) {
            boolean flag = this.minecraft.smartCull;
            if (spectator && this.level.getBlockState(camera.blockPosition()).isSolidRender()) {
                flag = false;
            }

            profilerfiller.push("updateSOG");
            this.sectionOcclusionGraph.update(flag, camera, frustum, this.visibleSections, this.level.getChunkSource().getLoadedEmptySections());
            profilerfiller.pop();
            double d3 = Math.floor(camera.xRot() / 2.0F);
            double d4 = Math.floor(camera.yRot() / 2.0F);
            if (this.sectionOcclusionGraph.consumeFrustumUpdate() || d3 != this.prevCamRotX || d4 != this.prevCamRotY) {
                profilerfiller.push("applyFrustum");
                this.applyFrustum(offsetFrustum(frustum));
                profilerfiller.pop();
                this.prevCamRotX = d3;
                this.prevCamRotY = d4;
            }
        }
    }

    public static Frustum offsetFrustum(Frustum frustum) {
        return new Frustum(frustum).offsetToFullyIncludeCameraCube(8);
    }

    private void applyFrustum(Frustum frustum) {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("applyFrustum called from wrong thread: " + Thread.currentThread().getName());
        } else {
            this.clearVisibleSections();
            this.sectionOcclusionGraph.addSectionsInFrustum(frustum, this.visibleSections, this.nearbyVisibleSections);
        }
    }

    public void addRecentlyCompiledSection(SectionRenderDispatcher.RenderSection renderSection) {
        this.sectionOcclusionGraph.schedulePropagationFrom(renderSection);
    }

    private Frustum prepareCullFrustum(Matrix4f frustumMatrix, Matrix4f projectionMatrix, Vec3 cameraPosition) {
        Frustum frustum;
        if (this.capturedFrustum != null && !this.captureFrustum) {
            frustum = this.capturedFrustum;
        } else {
            frustum = new Frustum(frustumMatrix, projectionMatrix);
            frustum.prepare(cameraPosition.x(), cameraPosition.y(), cameraPosition.z());
        }

        if (this.captureFrustum) {
            this.capturedFrustum = frustum;
            this.captureFrustum = false;
        }

        return frustum;
    }

    public void renderLevel(
        GraphicsResourceAllocator graphicsResourceAllocator,
        DeltaTracker deltaTracker,
        boolean renderBlockOutline,
        Camera camera,
        Matrix4f frustumMatrix,
        Matrix4f projectionMatrix,
        Matrix4f cullingProjectionMatrix,
        GpuBufferSlice shaderFog,
        Vector4f fogColor,
        boolean renderSky
    ) {
        float f = deltaTracker.getGameTimeDeltaPartialTick(false);
        this.levelRenderState.gameTime = this.level.getGameTime();
        this.blockEntityRenderDispatcher.prepare(camera);
        this.entityRenderDispatcher.prepare(camera, this.minecraft.crosshairPickEntity);
        final ProfilerFiller profilerfiller = Profiler.get();
        profilerfiller.push("populateLightUpdates");
        this.level.pollLightUpdates();
        profilerfiller.popPush("runLightUpdates");
        this.level.getChunkSource().getLightEngine().runLightUpdates();
        profilerfiller.popPush("prepareCullFrustum");
        Vec3 vec3 = camera.position();
        Frustum frustum = this.prepareCullFrustum(frustumMatrix, cullingProjectionMatrix, vec3);
        profilerfiller.popPush("cullTerrain");
        this.cullTerrain(camera, frustum, this.minecraft.player.isSpectator());
        profilerfiller.popPush("compileSections");
        this.compileSections(camera);
        profilerfiller.popPush("extract");
        profilerfiller.push("entities");
        this.extractVisibleEntities(camera, frustum, deltaTracker, this.levelRenderState);
        profilerfiller.popPush("blockEntities");
        this.extractVisibleBlockEntities(camera, f, this.levelRenderState, frustum);
        profilerfiller.popPush("blockOutline");
        this.extractBlockOutline(camera, this.levelRenderState);
        profilerfiller.popPush("blockBreaking");
        this.extractBlockDestroyAnimation(camera, this.levelRenderState);
        profilerfiller.popPush("weather");
        this.weatherEffectRenderer.extractRenderState(this.level, this.ticks, f, vec3, this.levelRenderState.weatherRenderState);
        profilerfiller.popPush("sky");
        this.skyRenderer.extractRenderState(this.level, f, camera, this.levelRenderState.skyRenderState);
        profilerfiller.popPush("border");
        this.worldBorderRenderer
            .extract(
                this.level.getWorldBorder(), f, vec3, this.minecraft.options.getEffectiveRenderDistance() * 16, this.levelRenderState.worldBorderRenderState
            );
        profilerfiller.popPush("neoforge_custom");
        this.levelRenderState.customWeatherEffectRenderer = net.neoforged.neoforge.client.CustomEnvironmentEffectsRendererManager.getCustomWeatherEffectRenderer(this.level, camera.position());
        this.levelRenderState.customSkyboxRenderer = net.neoforged.neoforge.client.CustomEnvironmentEffectsRendererManager.getCustomSkyboxRenderer(this.level, camera.position());
        this.levelRenderState.customCloudsRenderer = net.neoforged.neoforge.client.CustomEnvironmentEffectsRendererManager.getCustomCloudsRenderer(this.level, camera.position());
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent(
                this, this.levelRenderState, this.level, camera, frustum, deltaTracker, this.ticks
        ));
        profilerfiller.pop();
        profilerfiller.popPush("debug");
        this.debugRenderer.emitGizmos(frustum, vec3.x, vec3.y, vec3.z, deltaTracker.getGameTimeDeltaPartialTick(false));
        this.gameTestBlockHighlightRenderer.emitGizmos();
        profilerfiller.popPush("setupFrameGraph");
        Matrix4fStack matrix4fstack = RenderSystem.getModelViewStack();
        matrix4fstack.pushMatrix();
        matrix4fstack.mul(frustumMatrix);
        FrameGraphBuilder framegraphbuilder = new FrameGraphBuilder();
        this.targets.main = framegraphbuilder.importExternal("main", this.minecraft.getMainRenderTarget());
        int i = this.minecraft.getMainRenderTarget().width;
        int j = this.minecraft.getMainRenderTarget().height;
        RenderTargetDescriptor rendertargetdescriptor = new RenderTargetDescriptor(i, j, true, 0, this.minecraft.getMainRenderTarget().useStencil);
        PostChain postchain = this.getTransparencyChain();
        if (postchain != null) {
            this.targets.translucent = framegraphbuilder.createInternal("translucent", rendertargetdescriptor);
            this.targets.itemEntity = framegraphbuilder.createInternal("item_entity", rendertargetdescriptor);
            this.targets.particles = framegraphbuilder.createInternal("particles", rendertargetdescriptor);
            this.targets.weather = framegraphbuilder.createInternal("weather", rendertargetdescriptor);
            this.targets.clouds = framegraphbuilder.createInternal("clouds", rendertargetdescriptor);
        }

        if (this.entityOutlineTarget != null) {
            this.targets.entityOutline = framegraphbuilder.importExternal("entity_outline", this.entityOutlineTarget);
        }

        var setupEvent = net.neoforged.neoforge.client.ClientHooks.fireFrameGraphSetup(framegraphbuilder, this.targets, rendertargetdescriptor, frustum, camera, frustumMatrix, projectionMatrix, deltaTracker, profilerfiller);
        this.levelRenderState.haveGlowingEntities |= setupEvent.isOutlineProcessingEnabled();

        FramePass framepass = framegraphbuilder.addPass("clear");
        this.targets.main = framepass.readsAndWrites(this.targets.main);
        framepass.executes(
            () -> {
                RenderTarget rendertarget = this.minecraft.getMainRenderTarget();
                RenderSystem.getDevice()
                    .createCommandEncoder()
                    .clearColorAndDepthTextures(
                        rendertarget.getColorTexture(), ARGB.colorFromFloat(0.0F, fogColor.x, fogColor.y, fogColor.z), rendertarget.getDepthTexture(), 1.0
                    );
            }
        );
        if (renderSky) {
            this.addSkyPass(framegraphbuilder, camera, shaderFog, frustumMatrix);
        }

        this.addMainPass(framegraphbuilder, frustum, frustumMatrix, shaderFog, renderBlockOutline, this.levelRenderState, deltaTracker, profilerfiller);
        PostChain postchain1 = this.minecraft.getShaderManager().getPostChain(ENTITY_OUTLINE_POST_CHAIN_ID, LevelTargetBundle.OUTLINE_TARGETS);
        if (this.levelRenderState.haveGlowingEntities && postchain1 != null) {
            postchain1.addToFrame(framegraphbuilder, i, j, this.targets);
        }

        this.minecraft.particleEngine.extract(this.particlesRenderState, new Frustum(frustum).offset(-3.0F), camera, f);
        this.addParticlesPass(framegraphbuilder, shaderFog, frustumMatrix);
        CloudStatus cloudstatus = this.minecraft.options.getCloudsType();
        if (cloudstatus != CloudStatus.OFF) {
            int k = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_COLOR, f);
            if (ARGB.alpha(k) > 0) {
                float f1 = camera.attributeProbe().getValue(EnvironmentAttributes.CLOUD_HEIGHT, f);
                this.addCloudsPass(framegraphbuilder, cloudstatus, this.levelRenderState.cameraRenderState.pos, this.levelRenderState.gameTime, f, k, f1, frustumMatrix);
            }
        }

        this.addWeatherPass(framegraphbuilder, shaderFog, frustumMatrix);
        if (postchain != null) {
            postchain.addToFrame(framegraphbuilder, i, j, this.targets);
        }

        this.addLateDebugPass(framegraphbuilder, this.levelRenderState.cameraRenderState, shaderFog, frustumMatrix);
        profilerfiller.popPush("executeFrameGraph");
        framegraphbuilder.execute(graphicsResourceAllocator, new FrameGraphBuilder.Inspector() {
            @Override
            public void beforeExecutePass(String p_363206_) {
                profilerfiller.push(p_363206_);
            }

            @Override
            public void afterExecutePass(String p_362054_) {
                profilerfiller.pop();
            }
        });
        this.targets.clear();
        matrix4fstack.popMatrix();
        profilerfiller.pop();
        this.levelRenderState.reset();
    }

    private void addMainPass(
        FrameGraphBuilder frameGraphBuilder,
        Frustum frustum,
        Matrix4f frustumMatrix,
        GpuBufferSlice shaderFog,
        boolean renderBlockOutline,
        LevelRenderState renderState,
        DeltaTracker deltaTracker,
        ProfilerFiller profier
    ) {
        FramePass framepass = frameGraphBuilder.addPass("main");
        this.targets.main = framepass.readsAndWrites(this.targets.main);
        if (this.targets.translucent != null) {
            this.targets.translucent = framepass.readsAndWrites(this.targets.translucent);
        }

        if (this.targets.itemEntity != null) {
            this.targets.itemEntity = framepass.readsAndWrites(this.targets.itemEntity);
        }

        if (this.targets.weather != null) {
            this.targets.weather = framepass.readsAndWrites(this.targets.weather);
        }

        if (renderState.haveGlowingEntities && this.targets.entityOutline != null) {
            this.targets.entityOutline = framepass.readsAndWrites(this.targets.entityOutline);
        }

        ResourceHandle<RenderTarget> resourcehandle = this.targets.main;
        ResourceHandle<RenderTarget> resourcehandle1 = this.targets.translucent;
        ResourceHandle<RenderTarget> resourcehandle2 = this.targets.itemEntity;
        ResourceHandle<RenderTarget> resourcehandle3 = this.targets.entityOutline;
        framepass.executes(
            () -> {
                RenderSystem.setShaderFog(shaderFog);
                Vec3 vec3 = renderState.cameraRenderState.pos;
                double d0 = vec3.x();
                double d1 = vec3.y();
                double d2 = vec3.z();
                profier.push("terrain");
                if (this.chunkLayerSampler == null) {
                    int i = this.minecraft.options.textureFiltering().get() == TextureFilteringMethod.ANISOTROPIC
                        ? this.minecraft.options.maxAnisotropyValue()
                        : 1;
                    this.chunkLayerSampler = RenderSystem.getDevice()
                        .createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE, FilterMode.LINEAR, FilterMode.LINEAR, i, OptionalDouble.empty());
                }

                ChunkSectionsToRender chunksectionstorender = this.prepareChunkRenders(frustumMatrix, d0, d1, d2);
                chunksectionstorender.renderGroup(ChunkSectionLayerGroup.OPAQUE, this.chunkLayerSampler);
                profier.push("neoforge_render_after_opaque_blocks");
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterOpaqueBlocks(this, this.levelRenderState, null, frustumMatrix, this.getRenderableSections()));
                profier.pop();
                this.minecraft.gameRenderer.getLighting().setupFor(Lighting.Entry.LEVEL);
                if (resourcehandle2 != null) {
                    resourcehandle2.get().copyDepthFrom(this.minecraft.getMainRenderTarget());
                }

                if (this.shouldShowEntityOutlines() && resourcehandle3 != null) {
                    RenderTarget rendertarget = resourcehandle3.get();
                    RenderSystem.getDevice()
                        .createCommandEncoder()
                        .clearColorAndDepthTextures(rendertarget.getColorTexture(), 0, rendertarget.getDepthTexture(), 1.0);
                }

                PoseStack posestack = new PoseStack();
                MultiBufferSource.BufferSource multibuffersource$buffersource = this.renderBuffers.bufferSource();
                MultiBufferSource.BufferSource multibuffersource$buffersource1 = this.renderBuffers.crumblingBufferSource();
                profier.popPush("submitEntities");
                this.submitEntities(posestack, renderState, this.submitNodeStorage);
                profier.popPush("submitBlockEntities");
                this.submitBlockEntities(posestack, renderState, this.submitNodeStorage);
                profier.popPush("renderFeatures");
                this.featureRenderDispatcher.renderAllFeatures();
                multibuffersource$buffersource.endLastBatch();
                this.checkPoseStack(posestack);
                multibuffersource$buffersource.endBatch(RenderTypes.solidMovingBlock());
                multibuffersource$buffersource.endBatch(RenderTypes.endPortal());
                multibuffersource$buffersource.endBatch(RenderTypes.endGateway());
                multibuffersource$buffersource.endBatch(Sheets.solidBlockSheet());
                multibuffersource$buffersource.endBatch(Sheets.cutoutBlockSheet());
                multibuffersource$buffersource.endBatch(Sheets.bedSheet());
                multibuffersource$buffersource.endBatch(Sheets.shulkerBoxSheet());
                multibuffersource$buffersource.endBatch(Sheets.signSheet());
                multibuffersource$buffersource.endBatch(Sheets.hangingSignSheet());
                multibuffersource$buffersource.endBatch(Sheets.chestSheet());
                this.renderBuffers.outlineBufferSource().endOutlineBatch();
                profier.popPush("neoforge_render_after_entities");
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterEntities(this, this.levelRenderState, posestack, frustumMatrix, this.getRenderableSections()));
                if (renderBlockOutline) {
                    this.renderBlockOutline(multibuffersource$buffersource, posestack, false, renderState);
                }

                profier.pop();
                this.finalizeGizmoCollection();
                this.finalizedGizmos.standardPrimitives().render(posestack, multibuffersource$buffersource, renderState.cameraRenderState, frustumMatrix);
                multibuffersource$buffersource.endLastBatch();
                this.checkPoseStack(posestack);
                multibuffersource$buffersource.endBatch(Sheets.translucentItemSheet());
                multibuffersource$buffersource.endBatch(Sheets.bannerSheet());
                multibuffersource$buffersource.endBatch(Sheets.shieldSheet());
                multibuffersource$buffersource.endBatch(RenderTypes.armorEntityGlint());
                multibuffersource$buffersource.endBatch(RenderTypes.glint());
                multibuffersource$buffersource.endBatch(RenderTypes.glintTranslucent());
                multibuffersource$buffersource.endBatch(RenderTypes.entityGlint());
                profier.push("destroyProgress");
                this.renderBlockDestroyAnimation(posestack, multibuffersource$buffersource1, renderState);
                multibuffersource$buffersource1.endBatch();
                profier.pop();
                this.checkPoseStack(posestack);
                multibuffersource$buffersource.endBatch(RenderTypes.waterMask());
                multibuffersource$buffersource.endBatch();
                if (resourcehandle1 != null) {
                    resourcehandle1.get().copyDepthFrom(resourcehandle.get());
                }

                profier.push("translucent");
                chunksectionstorender.renderGroup(ChunkSectionLayerGroup.TRANSLUCENT, this.chunkLayerSampler);
                profier.popPush("neoforge_render_after_translucent_blocks");
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterTranslucentBlocks(this, this.levelRenderState, null, frustumMatrix, this.getRenderableSections()));
                profier.popPush("string");
                chunksectionstorender.renderGroup(ChunkSectionLayerGroup.TRIPWIRE, this.chunkLayerSampler);
                profier.popPush("neoforge_render_after_tripwire_blocks");
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterTripwireBlocks(this, this.levelRenderState, null, frustumMatrix, this.getRenderableSections()));
                if (renderBlockOutline) {
                    this.renderBlockOutline(multibuffersource$buffersource, posestack, true, renderState);
                }

                multibuffersource$buffersource.endBatch();
                profier.pop();
            }
        );
    }

    /**
     * @deprecated Neo: use {@link #addParticlesPass(FrameGraphBuilder, GpuBufferSlice
     *             , Matrix4f)} instead
     */
    @Deprecated
    private void addParticlesPass(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice shaderFog) {
        addParticlesPass(frameGraphBuilder, shaderFog, RenderSystem.getModelViewMatrix());
    }

    private void addParticlesPass(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice shaderFog, Matrix4f modelViewMatrix) {
        FramePass framepass = frameGraphBuilder.addPass("particles");
        if (this.targets.particles != null) {
            this.targets.particles = framepass.readsAndWrites(this.targets.particles);
            framepass.reads(this.targets.main);
        } else {
            this.targets.main = framepass.readsAndWrites(this.targets.main);
        }

        ResourceHandle<RenderTarget> resourcehandle = this.targets.main;
        ResourceHandle<RenderTarget> resourcehandle1 = this.targets.particles;
        framepass.executes(() -> {
            RenderSystem.setShaderFog(shaderFog);
            if (resourcehandle1 != null) {
                resourcehandle1.get().copyDepthFrom(resourcehandle.get());
            }

            this.particlesRenderState.submit(this.submitNodeStorage, this.levelRenderState.cameraRenderState);
            this.featureRenderDispatcher.renderAllFeatures();
            this.particlesRenderState.reset();

            Profiler.get().push("neoforge_render_after_particles");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterParticles(this, this.levelRenderState, null, modelViewMatrix, this.getRenderableSections()));
            Profiler.get().pop();
        });
    }

    /**
     * @deprecated Neo: use {@link #addCloudsPass(FrameGraphBuilder, CloudStatus, Vec3
     *             , long, float, int, float, Matrix4f)} instead
     */
    @Deprecated
    private void addCloudsPass(
        FrameGraphBuilder frameGraphBuilder, CloudStatus cloudStatus, Vec3 cameraPosition, long ticks, float partialTick, int cloudColor, float cloudHeight
    ) {
        addCloudsPass(frameGraphBuilder, cloudStatus, cameraPosition, ticks, partialTick, cloudColor, cloudHeight, RenderSystem.getModelViewMatrix());
    }

    private void addCloudsPass(FrameGraphBuilder frameGraphBuilder, CloudStatus cloudStatus, Vec3 cameraPosition, long ticks, float partialTick, int cloudColor, float cloudHeight, Matrix4f modelViewMatrix) {
        FramePass framepass = frameGraphBuilder.addPass("clouds");
        if (this.targets.clouds != null) {
            this.targets.clouds = framepass.readsAndWrites(this.targets.clouds);
        } else {
            this.targets.main = framepass.readsAndWrites(this.targets.main);
        }

        framepass.executes(() -> {
            if (this.levelRenderState.customCloudsRenderer == null || !this.levelRenderState.customCloudsRenderer.renderClouds(this.levelRenderState, cameraPosition, cloudStatus, cloudColor, cloudHeight, modelViewMatrix)) {
                this.cloudRenderer.render(cloudColor, cloudStatus, cloudHeight, cameraPosition, ticks, partialTick);
            }
        });
    }

    /**
     * @deprecated Neo: use {@link #addWeatherPass(FrameGraphBuilder, GpuBufferSlice,
     *             Matrix4f)} instead
     */
    @Deprecated
    private void addWeatherPass(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice shaderFog) {
        addWeatherPass(frameGraphBuilder, shaderFog, RenderSystem.getModelViewMatrix());
    }

    private void addWeatherPass(FrameGraphBuilder frameGraphBuilder, GpuBufferSlice shaderFog, Matrix4f modelViewMatrix) {
        int i = this.minecraft.options.getEffectiveRenderDistance() * 16;
        float f = this.minecraft.gameRenderer.getDepthFar();
        FramePass framepass = frameGraphBuilder.addPass("weather");
        if (this.targets.weather != null) {
            this.targets.weather = framepass.readsAndWrites(this.targets.weather);
        } else {
            this.targets.main = framepass.readsAndWrites(this.targets.main);
        }

        framepass.executes(() -> {
            RenderSystem.setShaderFog(shaderFog);
            MultiBufferSource.BufferSource multibuffersource$buffersource = this.renderBuffers.bufferSource();
            CameraRenderState camerarenderstate = this.levelRenderState.cameraRenderState;
            this.weatherEffectRenderer.render(multibuffersource$buffersource, camerarenderstate.pos, this.levelRenderState.weatherRenderState);
            Profiler.get().push("neoforge_render_after_weather");
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterWeather(this, this.levelRenderState, null, modelViewMatrix, this.getRenderableSections()));
            Profiler.get().pop();
            this.worldBorderRenderer.render(this.levelRenderState.worldBorderRenderState, camerarenderstate.pos, i, f);
            multibuffersource$buffersource.endBatch();
        });
    }

    private void addLateDebugPass(FrameGraphBuilder frameGraphBuilder, CameraRenderState cameraRenderState, GpuBufferSlice shaderFog, Matrix4f frustumMatrix) {
        FramePass framepass = frameGraphBuilder.addPass("late_debug");
        this.targets.main = framepass.readsAndWrites(this.targets.main);
        if (this.targets.itemEntity != null) {
            this.targets.itemEntity = framepass.readsAndWrites(this.targets.itemEntity);
        }

        ResourceHandle<RenderTarget> resourcehandle = this.targets.main;
        framepass.executes(() -> {
            RenderSystem.setShaderFog(shaderFog);
            PoseStack posestack = new PoseStack();
            MultiBufferSource.BufferSource multibuffersource$buffersource = this.renderBuffers.bufferSource();
            RenderSystem.outputColorTextureOverride = resourcehandle.get().getColorTextureView();
            RenderSystem.outputDepthTextureOverride = resourcehandle.get().getDepthTextureView();
            if (!this.finalizedGizmos.alwaysOnTopPrimitives().isEmpty()) {
                RenderTarget rendertarget = Minecraft.getInstance().getMainRenderTarget();
                RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(rendertarget.getDepthTexture(), 1.0);
                this.finalizedGizmos.alwaysOnTopPrimitives().render(posestack, multibuffersource$buffersource, cameraRenderState, frustumMatrix);
                multibuffersource$buffersource.endLastBatch();
            }

            RenderSystem.outputColorTextureOverride = null;
            RenderSystem.outputDepthTextureOverride = null;
            this.checkPoseStack(posestack);
        });
    }

    private void extractVisibleEntities(Camera camera, Frustum frustum, DeltaTracker deltaTracker, LevelRenderState renderState) {
        Vec3 vec3 = camera.position();
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();
        TickRateManager tickratemanager = this.minecraft.level.tickRateManager();
        boolean flag = this.shouldShowEntityOutlines();
        Entity.setViewScale(
            Mth.clamp(this.minecraft.options.getEffectiveRenderDistance() / 8.0, 1.0, 2.5) * this.minecraft.options.entityDistanceScaling().get()
        );

        for (Entity entity : this.level.entitiesForRendering()) {
            if (this.entityRenderDispatcher.shouldRender(entity, frustum, d0, d1, d2) || entity.hasIndirectPassenger(this.minecraft.player)) {
                BlockPos blockpos = entity.blockPosition();
                if ((this.level.isOutsideBuildHeight(blockpos.getY()) || this.isSectionCompiledAndVisible(blockpos))
                    && (
                        entity != camera.entity()
                            || camera.isDetached()
                            || camera.entity() instanceof LivingEntity && ((LivingEntity)camera.entity()).isSleeping()
                    )
                    && (!(entity instanceof LocalPlayer) || camera.entity() == entity || (entity == minecraft.player && !minecraft.player.isSpectator()))) { // Neo: render local player entity when it is not the camera entity
                    if (entity.tickCount == 0) {
                        entity.xOld = entity.getX();
                        entity.yOld = entity.getY();
                        entity.zOld = entity.getZ();
                    }

                    float f = deltaTracker.getGameTimeDeltaPartialTick(!tickratemanager.isEntityFrozen(entity));
                    EntityRenderState entityrenderstate = this.extractEntity(entity, f);
                    renderState.entityRenderStates.add(entityrenderstate);
                    if (entityrenderstate.appearsGlowing() && flag) {
                        renderState.haveGlowingEntities = true;
                    }
                    else if (flag && entity.hasCustomOutlineRendering(this.minecraft.player)) { // FORGE: allow custom outline rendering
                        renderState.haveGlowingEntities = true;
                    }
                }
            }
        }
    }

    private void submitEntities(PoseStack poseStack, LevelRenderState renderState, SubmitNodeCollector nodeCollector) {
        Vec3 vec3 = renderState.cameraRenderState.pos;
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();

        for (EntityRenderState entityrenderstate : renderState.entityRenderStates) {
            if (!renderState.haveGlowingEntities) {
                entityrenderstate.outlineColor = 0;
            }

            this.entityRenderDispatcher
                .submit(
                    entityrenderstate,
                    renderState.cameraRenderState,
                    entityrenderstate.x - d0,
                    entityrenderstate.y - d1,
                    entityrenderstate.z - d2,
                    poseStack,
                    nodeCollector
                );
        }
    }

    /**
     * @deprecated Neo: use {@link #extractVisibleBlockEntities(Camera, float,
     *             LevelRenderState, Frustum)} instead
     */
    @Deprecated
    private void extractVisibleBlockEntities(Camera camera, float partialTick, LevelRenderState renderState) {
        this.extractVisibleBlockEntities(camera, partialTick, renderState, null);
    }

    private void extractVisibleBlockEntities(Camera camera, float partialTick, LevelRenderState renderState, @Nullable Frustum frustum) {
        Vec3 vec3 = camera.position();
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();
        PoseStack posestack = new PoseStack();
        boolean shouldShowEntityOutlines = this.shouldShowEntityOutlines();

        for (SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection : this.visibleSections) {
            List<BlockEntity> list = sectionrenderdispatcher$rendersection.getSectionMesh().getRenderableBlockEntities();
            if (!list.isEmpty() && !(sectionrenderdispatcher$rendersection.getVisibility(Util.getMillis()) < 0.3F)) {
                for (BlockEntity blockentity : list) {
                    BlockPos blockpos = blockentity.getBlockPos();
                    SortedSet<BlockDestructionProgress> sortedset = this.destructionProgress.get(blockpos.asLong());
                    ModelFeatureRenderer.CrumblingOverlay modelfeaturerenderer$crumblingoverlay;
                    if (sortedset != null && !sortedset.isEmpty()) {
                        posestack.pushPose();
                        posestack.translate(blockpos.getX() - d0, blockpos.getY() - d1, blockpos.getZ() - d2);
                        modelfeaturerenderer$crumblingoverlay = new ModelFeatureRenderer.CrumblingOverlay(sortedset.last().getProgress(), posestack.last());
                        posestack.popPose();
                    } else {
                        modelfeaturerenderer$crumblingoverlay = null;
                    }

                    BlockEntityRenderState blockentityrenderstate = this.blockEntityRenderDispatcher
                        .tryExtractRenderState(blockentity, partialTick, modelfeaturerenderer$crumblingoverlay, frustum);
                    if (blockentityrenderstate != null) {
                        renderState.blockEntityRenderStates.add(blockentityrenderstate);
                        if (shouldShowEntityOutlines && blockentity.hasCustomOutlineRendering(this.minecraft.player)) { // Neo: allow custom outline rendering
                            renderState.haveGlowingEntities = true;
                        }
                    }
                }
            }
        }

        Iterator<BlockEntity> iterator = this.level.getGloballyRenderedBlockEntities().iterator();

        while (iterator.hasNext()) {
            BlockEntity blockentity1 = iterator.next();
            if (blockentity1.isRemoved()) {
                iterator.remove();
            } else {
                BlockEntityRenderState blockentityrenderstate1 = this.blockEntityRenderDispatcher.tryExtractRenderState(blockentity1, partialTick, null, frustum);
                if (blockentityrenderstate1 != null) {
                    renderState.blockEntityRenderStates.add(blockentityrenderstate1);
                    if (shouldShowEntityOutlines && blockentity1.hasCustomOutlineRendering(this.minecraft.player)) { // Neo: allow custom outline rendering
                        renderState.haveGlowingEntities = true;
                    }
                }
            }
        }
    }

    private void submitBlockEntities(PoseStack poseStack, LevelRenderState renderState, SubmitNodeStorage nodeStorage) {
        Vec3 vec3 = renderState.cameraRenderState.pos;
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();

        for (BlockEntityRenderState blockentityrenderstate : renderState.blockEntityRenderStates) {
            BlockPos blockpos = blockentityrenderstate.blockPos;
            poseStack.pushPose();
            poseStack.translate(blockpos.getX() - d0, blockpos.getY() - d1, blockpos.getZ() - d2);
            this.blockEntityRenderDispatcher.submit(blockentityrenderstate, poseStack, nodeStorage, renderState.cameraRenderState);
            poseStack.popPose();
        }
    }

    private void extractBlockDestroyAnimation(Camera camera, LevelRenderState renderState) {
        Vec3 vec3 = camera.position();
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();
        renderState.blockBreakingRenderStates.clear();

        for (Entry<SortedSet<BlockDestructionProgress>> entry : this.destructionProgress.long2ObjectEntrySet()) {
            BlockPos blockpos = BlockPos.of(entry.getLongKey());
            if (!(blockpos.distToCenterSqr(d0, d1, d2) > 1024.0)) {
                SortedSet<BlockDestructionProgress> sortedset = entry.getValue();
                if (sortedset != null && !sortedset.isEmpty()) {
                    int i = sortedset.last().getProgress();
                    renderState.blockBreakingRenderStates.add(new BlockBreakingRenderState(this.level, blockpos, i));
                }
            }
        }
    }

    private void renderBlockDestroyAnimation(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource, LevelRenderState renderState) {
        Vec3 vec3 = renderState.cameraRenderState.pos;
        double d0 = vec3.x();
        double d1 = vec3.y();
        double d2 = vec3.z();

        for (BlockBreakingRenderState blockbreakingrenderstate : renderState.blockBreakingRenderStates) {
            poseStack.pushPose();
            BlockPos blockpos = blockbreakingrenderstate.blockPos;
            poseStack.translate(blockpos.getX() - d0, blockpos.getY() - d1, blockpos.getZ() - d2);
            PoseStack.Pose posestack$pose = poseStack.last();
            VertexConsumer vertexconsumer = new SheetedDecalTextureGenerator(
                bufferSource.getBuffer(ModelBakery.DESTROY_TYPES.get(blockbreakingrenderstate.progress)), posestack$pose, 1.0F
            );
            this.minecraft
                .getBlockRenderer()
                .renderBreakingTexture(blockbreakingrenderstate.blockState, blockpos, blockbreakingrenderstate, poseStack, vertexconsumer);
            poseStack.popPose();
        }
    }

    private void extractBlockOutline(Camera camera, LevelRenderState renderState) {
        renderState.blockOutlineRenderState = null;
        if (this.minecraft.hitResult instanceof BlockHitResult blockhitresult) {
            if (blockhitresult.getType() != HitResult.Type.MISS) {
                BlockPos blockpos = blockhitresult.getBlockPos();
                BlockState blockstate = this.level.getBlockState(blockpos);
                if (!blockstate.isAir() && this.level.getWorldBorder().isWithinBounds(blockpos)) {
                    boolean flag = net.neoforged.neoforge.client.ClientHooks.isInTranslucentBlockOutlinePass(this.level, blockpos, blockstate);
                    boolean flag1 = this.minecraft.options.highContrastBlockOutline().get();
                    CollisionContext collisioncontext = CollisionContext.of(camera.entity());
                    var event = new net.neoforged.neoforge.client.event.ExtractBlockOutlineRenderStateEvent(this, this.level, blockpos, blockstate, blockhitresult, collisioncontext, flag, flag1, camera, renderState);
                    if (net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(event).isCanceled()) {
                        return;
                    }
                    VoxelShape voxelshape = blockstate.getShape(this.level, blockpos, collisioncontext);
                    if (SharedConstants.DEBUG_SHAPES) {
                        VoxelShape voxelshape1 = blockstate.getCollisionShape(this.level, blockpos, collisioncontext);
                        VoxelShape voxelshape2 = blockstate.getOcclusionShape();
                        VoxelShape voxelshape3 = blockstate.getInteractionShape(this.level, blockpos);
                        renderState.blockOutlineRenderState = new BlockOutlineRenderState(
                            blockpos, flag, flag1, voxelshape, voxelshape1, voxelshape2, voxelshape3, event.getCustomRenderers()
                        );
                    } else {
                        renderState.blockOutlineRenderState = new BlockOutlineRenderState(blockpos, flag, flag1, voxelshape, event.getCustomRenderers());
                    }
                }
            }
        }
    }

    private void renderBlockOutline(MultiBufferSource.BufferSource bufferSource, PoseStack poseStack, boolean translucent, LevelRenderState renderState) {
        BlockOutlineRenderState blockoutlinerenderstate = renderState.blockOutlineRenderState;
        if (blockoutlinerenderstate != null) {
            boolean cancel = false;
            for (net.neoforged.neoforge.client.CustomBlockOutlineRenderer customRenderer : blockoutlinerenderstate.customRenderers()) {
                cancel |= customRenderer.render(blockoutlinerenderstate, bufferSource, poseStack, translucent, renderState);
            }
            if (cancel) {
                return;
            }

            if (blockoutlinerenderstate.isTranslucent() == translucent) {
                Vec3 vec3 = renderState.cameraRenderState.pos;
                if (blockoutlinerenderstate.highContrast()) {
                    VertexConsumer vertexconsumer = bufferSource.getBuffer(RenderTypes.secondaryBlockOutline());
                    this.renderHitOutline(poseStack, vertexconsumer, vec3.x, vec3.y, vec3.z, blockoutlinerenderstate, -16777216, 7.0F);
                }

                VertexConsumer vertexconsumer1 = bufferSource.getBuffer(RenderTypes.lines());
                int i = blockoutlinerenderstate.highContrast() ? -11010079 : ARGB.black(102);
                this.renderHitOutline(
                    poseStack, vertexconsumer1, vec3.x, vec3.y, vec3.z, blockoutlinerenderstate, i, this.minecraft.getWindow().getAppropriateLineWidth()
                );
                bufferSource.endLastBatch();
            }
        }
    }

    /**
     * Asserts that the specified {@code poseStack} is {@linkplain com.mojang.blaze3d.vertex.PoseStack#clear() clear}.
     * @throws java.lang.IllegalStateException if the specified {@code poseStack} is not clear
     */
    private void checkPoseStack(PoseStack poseStack) {
        if (!poseStack.isEmpty()) {
            throw new IllegalStateException("Pose stack not empty");
        }
    }

    private EntityRenderState extractEntity(Entity entity, float partialTick) {
        return this.entityRenderDispatcher.extractEntity(entity, partialTick);
    }

    private void scheduleTranslucentSectionResort(Vec3 cameraPosition) {
        if (!this.visibleSections.isEmpty()) {
            BlockPos blockpos = BlockPos.containing(cameraPosition);
            boolean flag = !blockpos.equals(this.lastTranslucentSortBlockPos);
            TranslucencyPointOfView translucencypointofview = new TranslucencyPointOfView();

            for (SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection : this.nearbyVisibleSections) {
                this.scheduleResort(sectionrenderdispatcher$rendersection, translucencypointofview, cameraPosition, flag, true);
            }

            this.translucencyResortIterationIndex = this.translucencyResortIterationIndex % this.visibleSections.size();
            int i = Math.max(this.visibleSections.size() / 8, 15);

            while (i-- > 0) {
                int j = this.translucencyResortIterationIndex++ % this.visibleSections.size();
                this.scheduleResort(this.visibleSections.get(j), translucencypointofview, cameraPosition, flag, false);
            }

            this.lastTranslucentSortBlockPos = blockpos;
        }
    }

    private void scheduleResort(
        SectionRenderDispatcher.RenderSection section, TranslucencyPointOfView pointOfView, Vec3 cameraPosition, boolean force, boolean ignoreAxisAlignment
    ) {
        pointOfView.set(cameraPosition, section.getSectionNode());
        boolean flag = section.getSectionMesh().isDifferentPointOfView(pointOfView);
        boolean flag1 = force && (pointOfView.isAxisAligned() || ignoreAxisAlignment);
        if ((flag1 || flag) && !section.transparencyResortingScheduled() && section.hasTranslucentGeometry()) {
            section.resortTransparency(this.sectionRenderDispatcher);
        }
    }

    private ChunkSectionsToRender prepareChunkRenders(Matrix4fc frustumMatrix, double x, double y, double z) {
        ObjectListIterator<SectionRenderDispatcher.RenderSection> objectlistiterator = this.visibleSections.listIterator(0);
        EnumMap<ChunkSectionLayer, List<RenderPass.Draw<GpuBufferSlice[]>>> enummap = new EnumMap<>(ChunkSectionLayer.class);
        int i = 0;

        for (ChunkSectionLayer chunksectionlayer : ChunkSectionLayer.values()) {
            enummap.put(chunksectionlayer, new ArrayList<>());
        }

        List<DynamicUniforms.ChunkSectionInfo> list = new ArrayList<>();
        GpuTextureView gputextureview = this.minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        int i1 = gputextureview.getWidth(0);
        int j1 = gputextureview.getHeight(0);

        while (objectlistiterator.hasNext()) {
            SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection = objectlistiterator.next();
            SectionMesh sectionmesh = sectionrenderdispatcher$rendersection.getSectionMesh();
            BlockPos blockpos = sectionrenderdispatcher$rendersection.getRenderOrigin();
            long j = Util.getMillis();
            int k = -1;

            for (ChunkSectionLayer chunksectionlayer1 : ChunkSectionLayer.values()) {
                SectionBuffers sectionbuffers = sectionmesh.getBuffers(chunksectionlayer1);
                if (sectionbuffers != null) {
                    if (k == -1) {
                        k = list.size();
                        list.add(
                            new DynamicUniforms.ChunkSectionInfo(
                                new Matrix4f(frustumMatrix),
                                blockpos.getX(),
                                blockpos.getY(),
                                blockpos.getZ(),
                                sectionrenderdispatcher$rendersection.getVisibility(j),
                                i1,
                                j1
                            )
                        );
                    }

                    GpuBuffer gpubuffer;
                    VertexFormat.IndexType vertexformat$indextype;
                    if (sectionbuffers.getIndexBuffer() == null) {
                        if (sectionbuffers.getIndexCount() > i) {
                            i = sectionbuffers.getIndexCount();
                        }

                        gpubuffer = null;
                        vertexformat$indextype = null;
                    } else {
                        gpubuffer = sectionbuffers.getIndexBuffer();
                        vertexformat$indextype = sectionbuffers.getIndexType();
                    }

                    int l = k;
                    enummap.get(chunksectionlayer1)
                        .add(
                            new RenderPass.Draw<>(
                                0,
                                sectionbuffers.getVertexBuffer(),
                                gpubuffer,
                                vertexformat$indextype,
                                0,
                                sectionbuffers.getIndexCount(),
                                (p_428086_, p_428087_) -> p_428087_.upload("ChunkSection", p_428086_[l])
                            )
                        );
                }
            }
        }

        GpuBufferSlice[] agpubufferslice = RenderSystem.getDynamicUniforms().writeChunkSections(list.toArray(new DynamicUniforms.ChunkSectionInfo[0]));
        return new ChunkSectionsToRender(gputextureview, enummap, i, agpubufferslice);
    }

    public void endFrame() {
        this.cloudRenderer.endFrame();
    }

    public void captureFrustum() {
        this.captureFrustum = true;
    }

    public void killFrustum() {
        this.capturedFrustum = null;
    }

    public void tick(Camera camera) {
        if (this.level.tickRateManager().runsNormally()) {
            this.ticks++;
        }

        this.weatherEffectRenderer
            .tickRainParticles(this.level, camera, this.ticks, this.minecraft.options.particles().get(), this.minecraft.options.weatherRadius().get());
        this.removeBlockBreakingProgress();
    }

    private void removeBlockBreakingProgress() {
        if (this.ticks % 20 == 0) {
            Iterator<BlockDestructionProgress> iterator = this.destroyingBlocks.values().iterator();

            while (iterator.hasNext()) {
                BlockDestructionProgress blockdestructionprogress = iterator.next();
                int i = blockdestructionprogress.getUpdatedRenderTick();
                if (this.ticks - i > 400) {
                    iterator.remove();
                    this.removeProgress(blockdestructionprogress);
                }
            }
        }
    }

    private void removeProgress(BlockDestructionProgress progress) {
        long i = progress.getPos().asLong();
        Set<BlockDestructionProgress> set = this.destructionProgress.get(i);
        set.remove(progress);
        if (set.isEmpty()) {
            this.destructionProgress.remove(i);
        }
    }

    /**
     * @deprecated Neo: use {@link #addSkyPass(FrameGraphBuilder, Camera,
     *             GpuBufferSlice, Matrix4f)} instead
     */
    @Deprecated
    private void addSkyPass(FrameGraphBuilder frameGraphBuilder, Camera camera, GpuBufferSlice shaderFog) {
        this.addSkyPass(frameGraphBuilder, camera, shaderFog, RenderSystem.getModelViewMatrix());
    }

    private void addSkyPass(FrameGraphBuilder frameGraphBuilder, Camera camera, GpuBufferSlice shaderFog, Matrix4f modelViewMatrix) {
        FogType fogtype = camera.getFluidInCamera();
        if (fogtype != FogType.POWDER_SNOW && fogtype != FogType.LAVA && !this.doesMobEffectBlockSky(camera)) {
            SkyRenderState skyrenderstate = this.levelRenderState.skyRenderState;
            if (skyrenderstate.skybox != DimensionType.Skybox.NONE) {
                SkyRenderer skyrenderer = this.skyRenderer;
                if (skyrenderer != null) {
                    FramePass framepass = frameGraphBuilder.addPass("sky");
                    this.targets.main = framepass.readsAndWrites(this.targets.main);
                    framepass.executes(
                        () -> {
                            if (this.levelRenderState.customSkyboxRenderer == null || !this.levelRenderState.customSkyboxRenderer.renderSky(levelRenderState, skyrenderstate, modelViewMatrix, () -> RenderSystem.setShaderFog(shaderFog))) {
                            RenderSystem.setShaderFog(shaderFog);
                            if (skyrenderstate.skybox == DimensionType.Skybox.END) {
                                skyrenderer.renderEndSky();
                                if (skyrenderstate.endFlashIntensity > 1.0E-5F) {
                                    PoseStack posestack1 = new PoseStack();
                                    skyrenderer.renderEndFlash(
                                        posestack1, skyrenderstate.endFlashIntensity, skyrenderstate.endFlashXAngle, skyrenderstate.endFlashYAngle
                                    );
                                }
                            } else {
                                PoseStack posestack = new PoseStack();
                                skyrenderer.renderSkyDisc(skyrenderstate.skyColor);
                                skyrenderer.renderSunriseAndSunset(posestack, skyrenderstate.sunAngle, skyrenderstate.sunriseAndSunsetColor);
                                skyrenderer.renderSunMoonAndStars(
                                    posestack,
                                    skyrenderstate.sunAngle,
                                    skyrenderstate.moonAngle,
                                    skyrenderstate.starAngle,
                                    skyrenderstate.moonPhase,
                                    skyrenderstate.rainBrightness,
                                    skyrenderstate.starBrightness
                                );
                                if (skyrenderstate.shouldRenderDarkDisc) {
                                    skyrenderer.renderDarkDisc();
                                }
                            }
                            } // Neo: End of skip if custom sky is in effect
                            Profiler.get().push("neoforge_render_after_sky");
                            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.client.event.RenderLevelStageEvent.AfterSky(this, this.levelRenderState, null, modelViewMatrix, this.getRenderableSections()));
                            Profiler.get().pop();
                        }
                    );
                }
            }
        }
    }

    private boolean doesMobEffectBlockSky(Camera camera) {
        return !(camera.entity() instanceof LivingEntity livingentity)
            ? false
            : livingentity.hasEffect(MobEffects.BLINDNESS) || livingentity.hasEffect(MobEffects.DARKNESS);
    }

    private void compileSections(Camera camera) {
        ProfilerFiller profilerfiller = Profiler.get();
        profilerfiller.push("populateSectionsToCompile");
        RenderRegionCache renderregioncache = new RenderRegionCache();
        BlockPos blockpos = camera.blockPosition();
        List<SectionRenderDispatcher.RenderSection> list = Lists.newArrayList();
        long i = Mth.floor(this.minecraft.options.chunkSectionFadeInTime().get() * 1000.0);

        for (SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection : this.visibleSections) {
            if (sectionrenderdispatcher$rendersection.isDirty()
                && (
                    sectionrenderdispatcher$rendersection.getSectionMesh() != CompiledSectionMesh.UNCOMPILED
                        || sectionrenderdispatcher$rendersection.hasAllNeighbors()
                )) {
                BlockPos blockpos1 = SectionPos.of(sectionrenderdispatcher$rendersection.getSectionNode()).center();
                double d0 = blockpos1.distSqr(blockpos);
                boolean flag = d0 < 768.0;
                boolean flag1 = false;
                if (this.minecraft.options.prioritizeChunkUpdates().get() == PrioritizeChunkUpdates.NEARBY) {
                    flag1 = flag || sectionrenderdispatcher$rendersection.isDirtyFromPlayer();
                } else if (this.minecraft.options.prioritizeChunkUpdates().get() == PrioritizeChunkUpdates.PLAYER_AFFECTED) {
                    flag1 = sectionrenderdispatcher$rendersection.isDirtyFromPlayer();
                }

                if (!flag && !sectionrenderdispatcher$rendersection.wasPreviouslyEmpty()) {
                    sectionrenderdispatcher$rendersection.setFadeDuration(i);
                } else {
                    sectionrenderdispatcher$rendersection.setFadeDuration(0L);
                }

                sectionrenderdispatcher$rendersection.setWasPreviouslyEmpty(false);
                if (flag1) {
                    profilerfiller.push("compileSectionSynchronously");
                    this.sectionRenderDispatcher.rebuildSectionSync(sectionrenderdispatcher$rendersection, renderregioncache);
                    sectionrenderdispatcher$rendersection.setNotDirty();
                    profilerfiller.pop();
                } else {
                    list.add(sectionrenderdispatcher$rendersection);
                }
            }
        }

        profilerfiller.popPush("uploadSectionMeshes");
        this.sectionRenderDispatcher.uploadAllPendingUploads();
        profilerfiller.popPush("scheduleAsyncCompile");

        for (SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection1 : list) {
            sectionrenderdispatcher$rendersection1.rebuildSectionAsync(renderregioncache);
            sectionrenderdispatcher$rendersection1.setNotDirty();
        }

        profilerfiller.popPush("scheduleTranslucentResort");
        this.scheduleTranslucentSectionResort(camera.position());
        profilerfiller.pop();
    }

    private void renderHitOutline(
        PoseStack poseStack,
        VertexConsumer consumer,
        double camX,
        double camY,
        double camZ,
        BlockOutlineRenderState renderState,
        int color,
        float lineWidth
    ) {
        BlockPos blockpos = renderState.pos();
        if (SharedConstants.DEBUG_SHAPES) {
            ShapeRenderer.renderShape(
                poseStack,
                consumer,
                renderState.shape(),
                blockpos.getX() - camX,
                blockpos.getY() - camY,
                blockpos.getZ() - camZ,
                ARGB.colorFromFloat(1.0F, 1.0F, 1.0F, 1.0F),
                lineWidth
            );
            if (renderState.collisionShape() != null) {
                ShapeRenderer.renderShape(
                    poseStack,
                    consumer,
                    renderState.collisionShape(),
                    blockpos.getX() - camX,
                    blockpos.getY() - camY,
                    blockpos.getZ() - camZ,
                    ARGB.colorFromFloat(0.4F, 0.0F, 0.0F, 0.0F),
                    lineWidth
                );
            }

            if (renderState.occlusionShape() != null) {
                ShapeRenderer.renderShape(
                    poseStack,
                    consumer,
                    renderState.occlusionShape(),
                    blockpos.getX() - camX,
                    blockpos.getY() - camY,
                    blockpos.getZ() - camZ,
                    ARGB.colorFromFloat(0.4F, 0.0F, 1.0F, 0.0F),
                    lineWidth
                );
            }

            if (renderState.interactionShape() != null) {
                ShapeRenderer.renderShape(
                    poseStack,
                    consumer,
                    renderState.interactionShape(),
                    blockpos.getX() - camX,
                    blockpos.getY() - camY,
                    blockpos.getZ() - camZ,
                    ARGB.colorFromFloat(0.4F, 0.0F, 0.0F, 1.0F),
                    lineWidth
                );
            }
        } else {
            ShapeRenderer.renderShape(
                poseStack,
                consumer,
                renderState.shape(),
                blockpos.getX() - camX,
                blockpos.getY() - camY,
                blockpos.getZ() - camZ,
                color,
                lineWidth
            );
        }
    }

    public void blockChanged(BlockGetter level, BlockPos pos, BlockState oldState, BlockState newState, @Block.UpdateFlags int flags) {
        this.setBlockDirty(pos, (flags & 8) != 0);
    }

    private void setBlockDirty(BlockPos pos, boolean reRenderOnMainThread) {
        for (int i = pos.getZ() - 1; i <= pos.getZ() + 1; i++) {
            for (int j = pos.getX() - 1; j <= pos.getX() + 1; j++) {
                for (int k = pos.getY() - 1; k <= pos.getY() + 1; k++) {
                    this.setSectionDirty(SectionPos.blockToSectionCoord(j), SectionPos.blockToSectionCoord(k), SectionPos.blockToSectionCoord(i), reRenderOnMainThread);
                }
            }
        }
    }

    /**
     * Re-renders all blocks in the specified range.
     */
    public void setBlocksDirty(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int i = minZ - 1; i <= maxZ + 1; i++) {
            for (int j = minX - 1; j <= maxX + 1; j++) {
                for (int k = minY - 1; k <= maxY + 1; k++) {
                    this.setSectionDirty(SectionPos.blockToSectionCoord(j), SectionPos.blockToSectionCoord(k), SectionPos.blockToSectionCoord(i));
                }
            }
        }
    }

    public void setBlockDirty(BlockPos pos, BlockState oldState, BlockState newState) {
        if (this.minecraft.getModelManager().requiresRender(oldState, newState)) {
            this.setBlocksDirty(pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ());
        }
    }

    public void setSectionDirtyWithNeighbors(int sectionX, int sectionY, int sectionZ) {
        this.setSectionRangeDirty(sectionX - 1, sectionY - 1, sectionZ - 1, sectionX + 1, sectionY + 1, sectionZ + 1);
    }

    public void setSectionRangeDirty(int minY, int minX, int minZ, int maxY, int maxX, int maxZ) {
        for (int i = minZ; i <= maxZ; i++) {
            for (int j = minY; j <= maxY; j++) {
                for (int k = minX; k <= maxX; k++) {
                    this.setSectionDirty(j, k, i);
                }
            }
        }
    }

    public void setSectionDirty(int sectionX, int sectionY, int sectionZ) {
        this.setSectionDirty(sectionX, sectionY, sectionZ, false);
    }

    private void setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread) {
        this.viewArea.setDirty(sectionX, sectionY, sectionZ, reRenderOnMainThread);
    }

    public void onSectionBecomingNonEmpty(long sectionPos) {
        SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection = this.viewArea.getRenderSection(sectionPos);
        if (sectionrenderdispatcher$rendersection != null) {
            this.sectionOcclusionGraph.schedulePropagationFrom(sectionrenderdispatcher$rendersection);
            sectionrenderdispatcher$rendersection.setWasPreviouslyEmpty(true);
        }
    }

    public void destroyBlockProgress(int breakerId, BlockPos pos, int progress) {
        if (progress >= 0 && progress < 10) {
            BlockDestructionProgress blockdestructionprogress1 = this.destroyingBlocks.get(breakerId);
            if (blockdestructionprogress1 != null) {
                this.removeProgress(blockdestructionprogress1);
            }

            if (blockdestructionprogress1 == null
                || blockdestructionprogress1.getPos().getX() != pos.getX()
                || blockdestructionprogress1.getPos().getY() != pos.getY()
                || blockdestructionprogress1.getPos().getZ() != pos.getZ()) {
                blockdestructionprogress1 = new BlockDestructionProgress(breakerId, pos);
                this.destroyingBlocks.put(breakerId, blockdestructionprogress1);
            }

            blockdestructionprogress1.setProgress(progress);
            blockdestructionprogress1.updateTick(this.ticks);
            this.destructionProgress
                .computeIfAbsent(blockdestructionprogress1.getPos().asLong(), p_234254_ -> Sets.newTreeSet())
                .add(blockdestructionprogress1);
        } else {
            BlockDestructionProgress blockdestructionprogress = this.destroyingBlocks.remove(breakerId);
            if (blockdestructionprogress != null) {
                this.removeProgress(blockdestructionprogress);
            }
        }
    }

    public boolean hasRenderedAllSections() {
        return this.sectionRenderDispatcher.isQueueEmpty();
    }

    public void onChunkReadyToRender(ChunkPos chunkPos) {
        this.sectionOcclusionGraph.onChunkReadyToRender(chunkPos);
    }

    public void needsUpdate() {
        this.sectionOcclusionGraph.invalidate();
        this.cloudRenderer.markForRebuild();
    }

    public static int getLightColor(BlockAndTintGetter level, BlockPos pos) {
        return getLightColor(LevelRenderer.BrightnessGetter.DEFAULT, level, level.getBlockState(pos), pos);
    }

    public static int getLightColor(LevelRenderer.BrightnessGetter brightnessGetter, BlockAndTintGetter level, BlockState state, BlockPos pos) {
        if (state.emissiveRendering(level, pos)) {
            return 15728880;
        } else {
            int i = brightnessGetter.packedBrightness(level, pos);
            int j = LightTexture.block(i);
            int k = state.getLightEmission(level, pos);
            if (j < k) {
                int l = LightTexture.sky(i);
                return LightTexture.pack(k, l);
            } else {
                return i;
            }
        }
    }

    public boolean isSectionCompiledAndVisible(BlockPos pos) {
        SectionRenderDispatcher.RenderSection sectionrenderdispatcher$rendersection = this.viewArea.getRenderSectionAt(pos);
        return sectionrenderdispatcher$rendersection != null && sectionrenderdispatcher$rendersection.sectionMesh.get() != CompiledSectionMesh.UNCOMPILED
            ? sectionrenderdispatcher$rendersection.getVisibility(Util.getMillis()) >= 0.3F
            : false;
    }

    public @Nullable RenderTarget entityOutlineTarget() {
        return this.targets.entityOutline != null ? this.targets.entityOutline.get() : null;
    }

    public @Nullable RenderTarget getTranslucentTarget() {
        return this.targets.translucent != null ? this.targets.translucent.get() : null;
    }

    public @Nullable RenderTarget getItemEntityTarget() {
        return this.targets.itemEntity != null ? this.targets.itemEntity.get() : null;
    }

    public @Nullable RenderTarget getParticlesTarget() {
        return this.targets.particles != null ? this.targets.particles.get() : null;
    }

    public @Nullable RenderTarget getWeatherTarget() {
        return this.targets.weather != null ? this.targets.weather.get() : null;
    }

    public @Nullable RenderTarget getCloudsTarget() {
        return this.targets.clouds != null ? this.targets.clouds.get() : null;
    }

    @VisibleForDebug
    public ObjectArrayList<SectionRenderDispatcher.RenderSection> getVisibleSections() {
        return this.visibleSections;
    }

    @VisibleForDebug
    public SectionOcclusionGraph getSectionOcclusionGraph() {
        return this.sectionOcclusionGraph;
    }

    public @Nullable Frustum getCapturedFrustum() {
        return this.capturedFrustum;
    }

    public CloudRenderer getCloudRenderer() {
        return this.cloudRenderer;
    }

    public int getTicks() {
        return this.ticks;
    }

    public void iterateVisibleBlockEntities(java.util.function.Consumer<BlockEntity> blockEntityConsumer) {
        for (var chunkInfo : this.visibleSections) {
            chunkInfo.getSectionMesh().getRenderableBlockEntities().forEach(blockEntityConsumer);
        }
        this.level.getGloballyRenderedBlockEntities().forEach(blockEntityConsumer);
    }

    @org.jetbrains.annotations.ApiStatus.Internal
    public Iterable<? extends net.neoforged.neoforge.client.IRenderableSection> getRenderableSections() {
        return this.visibleSections;
    }

    public Gizmos.TemporaryCollection collectPerFrameGizmos() {
        return Gizmos.withCollector(this.collectedGizmos);
    }

    private void finalizeGizmoCollection() {
        DrawableGizmoPrimitives drawablegizmoprimitives = new DrawableGizmoPrimitives();
        DrawableGizmoPrimitives drawablegizmoprimitives1 = new DrawableGizmoPrimitives();
        this.collectedGizmos.addTemporaryGizmos(this.minecraft.getPerTickGizmos());
        IntegratedServer integratedserver = this.minecraft.getSingleplayerServer();
        if (integratedserver != null) {
            this.collectedGizmos.addTemporaryGizmos(integratedserver.getPerTickGizmos());
        }

        long i = Util.getMillis();

        for (SimpleGizmoCollector.GizmoInstance simplegizmocollector$gizmoinstance : this.collectedGizmos.drainGizmos()) {
            simplegizmocollector$gizmoinstance.gizmo()
                .emit(
                    simplegizmocollector$gizmoinstance.isAlwaysOnTop() ? drawablegizmoprimitives1 : drawablegizmoprimitives,
                    simplegizmocollector$gizmoinstance.getAlphaMultiplier(i)
                );
        }

        this.finalizedGizmos = new LevelRenderer.FinalizedGizmos(drawablegizmoprimitives, drawablegizmoprimitives1);
    }

    @FunctionalInterface
    @OnlyIn(Dist.CLIENT)
    public interface BrightnessGetter {
        LevelRenderer.BrightnessGetter DEFAULT = (p_412971_, p_412968_) -> {
            int i = p_412971_.getBrightness(LightLayer.SKY, p_412968_);
            int j = p_412971_.getBrightness(LightLayer.BLOCK, p_412968_);
            return Brightness.pack(j, i);
        };

        int packedBrightness(BlockAndTintGetter level, BlockPos pos);
    }

    @OnlyIn(Dist.CLIENT)
    record FinalizedGizmos(DrawableGizmoPrimitives standardPrimitives, DrawableGizmoPrimitives alwaysOnTopPrimitives) {
    }
}
