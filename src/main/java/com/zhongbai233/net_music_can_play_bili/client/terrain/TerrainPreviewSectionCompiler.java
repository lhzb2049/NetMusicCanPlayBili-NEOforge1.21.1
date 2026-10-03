package com.zhongbai233.net_music_can_play_bili.client.terrain;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.MeshData.SortState;
import com.mojang.blaze3d.vertex.VertexFormat.Mode;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainFluidVertexCoordinates;
import com.zhongbai233.net_music_can_play_bili.terrain.core.TerrainPackedLight;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.LiquidBlockRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.FastColor.ARGB32;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TerrainPreviewSectionCompiler {
   private static final Logger LOGGER = LoggerFactory.getLogger(TerrainPreviewSectionCompiler.class);
   private static final int INITIAL_LAYER_BUFFER_BYTES = 262144;
   private static final RenderType[] LAYERS = new RenderType[]{RenderType.solid(), RenderType.cutoutMipped(), RenderType.cutout(), RenderType.translucent()};

   private TerrainPreviewSectionCompiler() {
   }

   public static TerrainPreviewSectionCompiler.CompiledCpuSection compile(
      TerrainPreviewFrame frame, TerrainBlockSectionSnapshot snapshot, BlockModelShaper modelShaper, BlockColors blockColors
   ) {
      Objects.requireNonNull(modelShaper, "modelShaper");
      Objects.requireNonNull(blockColors, "blockColors");
      TerrainPreviewBlockAndTintGetter snapshotView = new TerrainPreviewBlockAndTintGetter(snapshot);
      ModelBlockRenderer blockRenderer = new ModelBlockRenderer(new TerrainPreviewSectionCompiler.FrozenTerrainBlockColors(snapshotView));
      LiquidBlockRenderer fluidRenderer = new LiquidBlockRenderer();
      ByteBufferBuilder[] storageByLayer = new ByteBufferBuilder[LAYERS.length];
      BufferBuilder[] buildersByLayer = new BufferBuilder[LAYERS.length];
      Map<RenderType, MeshData> meshes = new HashMap<>();
      Map<RenderType, SortState> sortStates = new HashMap<>();
      List<ByteBufferBuilder> auxiliaryStorage = new ArrayList<>();
      PoseStack poseStack = new PoseStack();
      RandomSource random = RandomSource.create();
      MutableBlockPos worldPos = new MutableBlockPos();
      boolean[] occupied = occupiedVoxels(snapshot);

      try {
         for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
            int worldX = snapshot.section().minBlockX() + block.localX();
            int worldY = snapshot.section().minBlockY() + block.localY();
            int worldZ = snapshot.section().minBlockZ() + block.localZ();
            if (worldX != frame.originX() || worldY != frame.originY() || worldZ != frame.originZ()) {
               worldPos.set(worldX, worldY, worldZ);

               try {
                  if (block.cellSize() > 1) {
                     tesselateMaterialCell(block, snapshotView, modelShaper, occupied, storageByLayer, buildersByLayer);
                  } else {
                     FluidState fluidState = block.state().getFluidState();
                     if (!fluidState.isEmpty()) {
                        fluidRenderer.tesselate(
                           snapshotView,
                           worldPos,
                           sectionLocalFluidOutput(builderFor(RenderType.translucent(), storageByLayer, buildersByLayer)),
                           block.state(),
                           fluidState
                        );
                     }

                     if (block.state().getRenderShape() == RenderShape.MODEL) {
                        BakedModel model = modelShaper.getBlockModel(block.state());

                        for (RenderType layer : model.getRenderTypes(block.state(), random, ModelData.EMPTY)) {
                           RenderType target = compileLayer(layer);
                           if (target == null) {
                              if (layer != RenderType.tripwire()) {
                                 continue;
                              }

                              target = RenderType.translucent();
                           }

                           poseStack.pushPose();
                           poseStack.translate(block.localX() - 0.5F, block.localY(), block.localZ() - 0.5F);

                           try {
                              blockRenderer.tesselateBlock(
                                 snapshotView,
                                 model,
                                 block.state(),
                                 worldPos,
                                 poseStack,
                                 builderFor(target, storageByLayer, buildersByLayer),
                                 true,
                                 random,
                                 block.state().getSeed(worldPos),
                                 OverlayTexture.NO_OVERLAY,
                                 ModelData.EMPTY,
                                 target
                              );
                           } finally {
                              poseStack.popPose();
                           }
                        }
                     }
                  }
               } catch (Throwable var32) {
                  if (var32 instanceof VirtualMachineError fatal) {
                     throw fatal;
                  }

                  if (var32 instanceof Error fatal) {
                     throw fatal;
                  }

                  LOGGER.warn(
                     "Skipping incompatible terrain preview block at ({}, {}, {}) in section {}",
                     new Object[]{worldX, worldY, worldZ, snapshot.section(), var32}
                  );
               }
            }
         }

         Map<RenderType, ByteBufferBuilder> storage = new HashMap<>();

         for (int i = 0; i < LAYERS.length; i++) {
            BufferBuilder builder = buildersByLayer[i];
            if (builder != null) {
               MeshData mesh = builder.build();
               if (mesh != null) {
                  if (LAYERS[i] == RenderType.translucent()) {
                     int initialIndexBytes = Math.max(256, mesh.drawState().indexCount() * mesh.drawState().indexType().bytes);
                     ByteBufferBuilder indexStorage = new ByteBufferBuilder(initialIndexBytes);
                     SortState sortState = mesh.sortQuads(indexStorage, VertexSorting.DISTANCE_TO_ORIGIN);
                     if (sortState != null) {
                        sortStates.put(LAYERS[i], sortState);
                        auxiliaryStorage.add(indexStorage);
                     } else {
                        indexStorage.close();
                     }
                  }

                  meshes.put(LAYERS[i], mesh);
               }

               storage.put(LAYERS[i], storageByLayer[i]);
            }
         }

         return new TerrainPreviewSectionCompiler.CompiledCpuSection(snapshot, meshes, storage, sortStates, auxiliaryStorage);
      } catch (Throwable var33) {
         meshes.values().forEach(meshx -> meshx.close());
         closeStorage(storageByLayer);
         auxiliaryStorage.forEach(storageBuffer -> storageBuffer.close());
         throw var33;
      }
   }

   private static RenderType compileLayer(RenderType layer) {
      for (RenderType candidate : LAYERS) {
         if (candidate == layer) {
            return candidate;
         }
      }

      return null;
   }

   private static int layerIndex(RenderType layer) {
      for (int i = 0; i < LAYERS.length; i++) {
         if (LAYERS[i] == layer) {
            return i;
         }
      }

      throw new IllegalArgumentException("unmapped terrain render layer: " + layer);
   }

   private static boolean[] occupiedVoxels(TerrainBlockSectionSnapshot snapshot) {
      boolean[] occupied = new boolean[4096];

      for (TerrainBlockSectionSnapshot.VisibleBlock block : snapshot.blocks()) {
         for (int y = block.localY(); y < block.localY() + block.cellSize(); y++) {
            for (int z = block.localZ(); z < block.localZ() + block.cellSize(); z++) {
               for (int x = block.localX(); x < block.localX() + block.cellSize(); x++) {
                  occupied[(y * 16 + z) * 16 + x] = true;
               }
            }
         }
      }

      return occupied;
   }

   private static void tesselateMaterialCell(
      TerrainBlockSectionSnapshot.VisibleBlock block,
      TerrainPreviewBlockAndTintGetter snapshotView,
      BlockModelShaper modelShaper,
      boolean[] occupied,
      ByteBufferBuilder[] storageByLayer,
      BufferBuilder[] buildersByLayer
   ) {
      BakedModel model = modelShaper.getBlockModel(block.state());
      TextureAtlasSprite sprite = model.getParticleIcon(ModelData.EMPTY);
      RenderType layer;
      if (!block.state().getFluidState().isEmpty()) {
         layer = RenderType.translucent();
      } else {
         ChunkRenderTypeSet renderTypes = model.getRenderTypes(block.state(), RandomSource.create(), ModelData.EMPTY);
         if (renderTypes.isEmpty() || renderTypes.contains(RenderType.translucent())) {
            layer = RenderType.translucent();
         } else if (renderTypes.contains(RenderType.solid())) {
            layer = RenderType.solid();
         } else if (renderTypes.contains(RenderType.cutoutMipped())) {
            layer = RenderType.cutoutMipped();
         } else {
            layer = RenderType.cutout();
         }
      }

      BufferBuilder output = builderFor(layer, storageByLayer, buildersByLayer);
      int tint = block.tintLayers().isEmpty() ? -1 : block.tintLayers().getFirst();
      if (tint != -1 && (tint & 0xFF000000) == 0) {
         tint |= -16777216;
      }

      int light = LightTexture.pack(TerrainPackedLight.block(block.packedLight()), TerrainPackedLight.sky(block.packedLight()));

      for (Direction direction : Direction.values()) {
         if (faceExposed(block, direction, occupied)) {
            emitCellFace(output, block, direction, sprite, shade(tint, direction), light);
         }
      }
   }

   private static boolean faceExposed(TerrainBlockSectionSnapshot.VisibleBlock block, Direction direction, boolean[] occupied) {
      int x = block.localX() + direction.getStepX() * block.cellSize();
      int y = block.localY() + direction.getStepY() * block.cellSize();
      int z = block.localZ() + direction.getStepZ() * block.cellSize();
      return x >= 0 && x < 16 && y >= 0 && y < 16 && z >= 0 && z < 16 ? !occupied[(y * 16 + z) * 16 + x] : true;
   }

   private static int shade(int color, Direction direction) {
      float factor = switch (direction) {
         case DOWN -> 0.5F;
         case UP -> 1.0F;
         case NORTH, SOUTH -> 0.8F;
         case WEST, EAST -> 0.6F;
         default -> throw new MatchException(null, null);
      };
      int packedFactor = (int)(factor * 255.0F);
      return ARGB32.multiply(color, ARGB32.color(packedFactor, packedFactor, packedFactor, 255));
   }

   private static void emitCellFace(
      BufferBuilder output, TerrainBlockSectionSnapshot.VisibleBlock block, Direction direction, TextureAtlasSprite sprite, int color, int light
   ) {
      float x0 = block.localX() - 0.5F;
      float y0 = block.localY();
      float z0 = block.localZ() - 0.5F;
      float x1 = x0 + block.cellSize();
      float y1 = y0 + block.cellSize();
      float z1 = z0 + block.cellSize();

      float[][] vertices = switch (direction) {
         case DOWN -> new float[][]{{x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}, {x0, y0, z1}};
         case UP -> new float[][]{{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
         case NORTH -> new float[][]{{x0, y0, z0}, {x0, y1, z0}, {x1, y1, z0}, {x1, y0, z0}};
         case SOUTH -> new float[][]{{x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}, {x0, y1, z1}};
         case WEST -> new float[][]{{x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}, {x0, y1, z0}};
         case EAST -> new float[][]{{x1, y0, z0}, {x1, y1, z0}, {x1, y1, z1}, {x1, y0, z1}};
         default -> throw new MatchException(null, null);
      };
      float[] u = new float[]{sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
      float[] v = new float[]{sprite.getV1(), sprite.getV1(), sprite.getV0(), sprite.getV1()};
      Vector3f normal = direction.step();

      for (int index = 0; index < 4; index++) {
         output.addVertex(vertices[index][0], vertices[index][1], vertices[index][2], color, u[index], v[index], 0, light, normal.x(), normal.y(), normal.z());
      }
   }

   private static BufferBuilder builderFor(RenderType layer, ByteBufferBuilder[] storage, BufferBuilder[] builders) {
      int index = layerIndex(layer);
      BufferBuilder builder = builders[index];
      if (builder == null) {
         ByteBufferBuilder bytes = new ByteBufferBuilder(262144);
         storage[index] = bytes;
         builder = new BufferBuilder(bytes, Mode.QUADS, layer.format);
         builders[index] = builder;
      }

      return builder;
   }

   static VertexConsumer sectionLocalFluidOutput(VertexConsumer delegate) {
      return new TerrainPreviewSectionCompiler.TranslatedVertexConsumer(
         delegate, TerrainFluidVertexCoordinates.offsetX(), TerrainFluidVertexCoordinates.offsetY(), TerrainFluidVertexCoordinates.offsetZ()
      );
   }

   private static void closeStorage(ByteBufferBuilder[] storage) {
      for (ByteBufferBuilder bytes : storage) {
         if (bytes != null) {
            bytes.close();
         }
      }
   }

   public record CompiledCpuSection(
      TerrainBlockSectionSnapshot source,
      Map<RenderType, MeshData> layers,
      Map<RenderType, ByteBufferBuilder> storage,
      Map<RenderType, SortState> sortStates,
      List<ByteBufferBuilder> auxiliaryStorage
   ) implements AutoCloseable {
      public CompiledCpuSection(
         TerrainBlockSectionSnapshot source,
         Map<RenderType, MeshData> layers,
         Map<RenderType, ByteBufferBuilder> storage,
         Map<RenderType, SortState> sortStates,
         List<ByteBufferBuilder> auxiliaryStorage
      ) {
         Objects.requireNonNull(source, "source");
         Map<RenderType, MeshData> var6 = new HashMap(Objects.requireNonNull(layers, "layers"));
         Map<RenderType, ByteBufferBuilder> var7 = new HashMap(Objects.requireNonNull(storage, "storage"));
         Map<RenderType, SortState> var8 = new HashMap(Objects.requireNonNull(sortStates, "sortStates"));
         List<ByteBufferBuilder> var9 = new ArrayList(Objects.requireNonNull(auxiliaryStorage, "auxiliaryStorage"));
         this.source = source;
         this.layers = var6;
         this.storage = var7;
         this.sortStates = var8;
         this.auxiliaryStorage = var9;
      }

      @Override
      public void close() {
         this.layers.values().forEach(mesh -> mesh.close());
         this.layers.clear();
         this.storage.values().forEach(bytes -> bytes.close());
         this.storage.clear();
         this.sortStates.clear();
         this.auxiliaryStorage.forEach(storageBuffer -> storageBuffer.close());
         this.auxiliaryStorage.clear();
      }
   }

   private static final class FrozenTerrainBlockColors extends BlockColors {
      private final TerrainPreviewBlockAndTintGetter view;

      private FrozenTerrainBlockColors(TerrainPreviewBlockAndTintGetter view) {
         this.view = view;
      }

      public int getColor(BlockState state, BlockAndTintGetter level, BlockPos pos, int tintIndex) {
         if (level == this.view && pos != null) {
            int tint = this.view.precomputedTint(pos, tintIndex);
            if (tint != -1) {
               return tint;
            }
         }

         return -1;
      }
   }

   private static final class TranslatedVertexConsumer implements VertexConsumer {
      private final VertexConsumer delegate;
      private final float offsetX;
      private final float offsetY;
      private final float offsetZ;

      private TranslatedVertexConsumer(VertexConsumer delegate, float offsetX, float offsetY, float offsetZ) {
         this.delegate = Objects.requireNonNull(delegate, "delegate");
         this.offsetX = offsetX;
         this.offsetY = offsetY;
         this.offsetZ = offsetZ;
      }

      public VertexConsumer addVertex(float x, float y, float z) {
         this.delegate.addVertex(x + this.offsetX, y + this.offsetY, z + this.offsetZ);
         return this;
      }

      public VertexConsumer setColor(int red, int green, int blue, int alpha) {
         this.delegate.setColor(red, green, blue, alpha);
         return this;
      }

      public VertexConsumer setUv(float u, float v) {
         this.delegate.setUv(u, v);
         return this;
      }

      public VertexConsumer setUv1(int u, int v) {
         this.delegate.setUv1(u, v);
         return this;
      }

      public VertexConsumer setUv2(int u, int v) {
         this.delegate.setUv2(u, v);
         return this;
      }

      public VertexConsumer setNormal(float x, float y, float z) {
         this.delegate.setNormal(x, y, z);
         return this;
      }
   }
}
