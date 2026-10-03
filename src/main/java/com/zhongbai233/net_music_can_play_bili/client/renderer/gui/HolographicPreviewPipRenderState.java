package com.zhongbai233.net_music_can_play_bili.client.renderer.gui;

import com.zhongbai233.net_music_can_play_bili.client.terrain.TerrainPreviewFrame;
import com.zhongbai233.scene_editor.core.camera.CameraFrame;
import java.util.Arrays;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3f;

public record HolographicPreviewPipRenderState(
   LivingEntity playerEntity,
   Vector3f playerTranslation,
   float playerScale,
   float previewYaw,
   float previewPitch,
   boolean firstPerson,
   float fovDegrees,
   boolean playerGlowing,
   float screenDistance,
   float screenOffsetX,
   float screenOffsetY,
   float screenHeight,
   float screenAspect,
   float screenRoll,
   int gizmoTool,
   int gizmoHandle,
   boolean localSpace,
   int selectedScreen,
   float[] screenDistances,
   float[] screenOffsetXs,
   float[] screenOffsetYs,
   float[] screenHeights,
   float[] screenAspects,
   float[] screenYaws,
   float[] screenPitches,
   float[] screenRolls,
   float[] screenScaleXs,
   float[] screenScaleYs,
   float[] screenScaleZs,
   float[] screenPivotXs,
   float[] screenPivotYs,
   float[] screenPivotZs,
   float[] screenSkewXByYs,
   float[] screenSkewYByXs,
   int[] elementTypes,
   boolean controlConsoleModel,
   boolean renderWorldTerrain,
   int worldOriginX,
   int worldOriginY,
   int worldOriginZ,
   float worldRangeX,
   float worldRangeY,
   float worldRangeZ,
   TerrainPreviewFrame terrainFrame,
   CameraFrame cameraFrame,
   int x0,
   int y0,
   int x1,
   int y1,
   float scale
) {
   public HolographicPreviewPipRenderState(
      LivingEntity playerEntity,
      Vector3f playerTranslation,
      float playerScale,
      float previewYaw,
      float previewPitch,
      boolean firstPerson,
      float fovDegrees,
      boolean playerGlowing,
      float screenDistance,
      float screenOffsetX,
      float screenOffsetY,
      float screenHeight,
      float screenAspect,
      float screenRoll,
      int gizmoTool,
      int gizmoHandle,
      boolean localSpace,
      int selectedScreen,
      float[] screenDistances,
      float[] screenOffsetXs,
      float[] screenOffsetYs,
      float[] screenHeights,
      float[] screenAspects,
      float[] screenYaws,
      float[] screenPitches,
      float[] screenRolls,
      float[] screenScaleXs,
      float[] screenScaleYs,
      float[] screenScaleZs,
      float[] screenPivotXs,
      float[] screenPivotYs,
      float[] screenPivotZs,
      float[] screenSkewXByYs,
      float[] screenSkewYByXs,
      int[] elementTypes,
      boolean controlConsoleModel,
      boolean renderWorldTerrain,
      int worldOriginX,
      int worldOriginY,
      int worldOriginZ,
      float worldRangeX,
      float worldRangeY,
      float worldRangeZ,
      TerrainPreviewFrame terrainFrame,
      CameraFrame cameraFrame,
      int x0,
      int y0,
      int x1,
      int y1,
      float scale
   ) {
      terrainFrame = terrainFrame != null ? terrainFrame : TerrainPreviewFrame.empty();
      playerTranslation = new Vector3f(playerTranslation);
      screenDistances = copy(screenDistances);
      screenOffsetXs = copy(screenOffsetXs);
      screenOffsetYs = copy(screenOffsetYs);
      screenHeights = copy(screenHeights);
      screenAspects = copy(screenAspects);
      screenYaws = copy(screenYaws);
      screenPitches = copy(screenPitches);
      screenRolls = copy(screenRolls);
      screenScaleXs = copy(screenScaleXs);
      screenScaleYs = copy(screenScaleYs);
      screenScaleZs = copy(screenScaleZs);
      screenPivotXs = copy(screenPivotXs);
      screenPivotYs = copy(screenPivotYs);
      screenPivotZs = copy(screenPivotZs);
      screenSkewXByYs = copy(screenSkewXByYs);
      screenSkewYByXs = copy(screenSkewYByXs);
      elementTypes = copy(elementTypes);
      this.playerEntity = playerEntity;
      this.playerTranslation = playerTranslation;
      this.playerScale = playerScale;
      this.previewYaw = previewYaw;
      this.previewPitch = previewPitch;
      this.firstPerson = firstPerson;
      this.fovDegrees = fovDegrees;
      this.playerGlowing = playerGlowing;
      this.screenDistance = screenDistance;
      this.screenOffsetX = screenOffsetX;
      this.screenOffsetY = screenOffsetY;
      this.screenHeight = screenHeight;
      this.screenAspect = screenAspect;
      this.screenRoll = screenRoll;
      this.gizmoTool = gizmoTool;
      this.gizmoHandle = gizmoHandle;
      this.localSpace = localSpace;
      this.selectedScreen = selectedScreen;
      this.screenDistances = screenDistances;
      this.screenOffsetXs = screenOffsetXs;
      this.screenOffsetYs = screenOffsetYs;
      this.screenHeights = screenHeights;
      this.screenAspects = screenAspects;
      this.screenYaws = screenYaws;
      this.screenPitches = screenPitches;
      this.screenRolls = screenRolls;
      this.screenScaleXs = screenScaleXs;
      this.screenScaleYs = screenScaleYs;
      this.screenScaleZs = screenScaleZs;
      this.screenPivotXs = screenPivotXs;
      this.screenPivotYs = screenPivotYs;
      this.screenPivotZs = screenPivotZs;
      this.screenSkewXByYs = screenSkewXByYs;
      this.screenSkewYByXs = screenSkewYByXs;
      this.elementTypes = elementTypes;
      this.controlConsoleModel = controlConsoleModel;
      this.renderWorldTerrain = renderWorldTerrain;
      this.worldOriginX = worldOriginX;
      this.worldOriginY = worldOriginY;
      this.worldOriginZ = worldOriginZ;
      this.worldRangeX = worldRangeX;
      this.worldRangeY = worldRangeY;
      this.worldRangeZ = worldRangeZ;
      this.terrainFrame = terrainFrame;
      this.cameraFrame = cameraFrame;
      this.x0 = x0;
      this.y0 = y0;
      this.x1 = x1;
      this.y1 = y1;
      this.scale = scale;
   }

   public HolographicPreviewPipRenderState(
      LivingEntity playerEntity,
      Vector3f playerTranslation,
      float playerScale,
      float previewYaw,
      float previewPitch,
      boolean firstPerson,
      float fovDegrees,
      boolean playerGlowing,
      float screenDistance,
      float screenOffsetX,
      float screenOffsetY,
      float screenHeight,
      float screenAspect,
      float screenRoll,
      int gizmoTool,
      int gizmoHandle,
      boolean localSpace,
      int x0,
      int y0,
      int x1,
      int y1,
      float scale
   ) {
      this(
         playerEntity,
         playerTranslation,
         playerScale,
         previewYaw,
         previewPitch,
         firstPerson,
         fovDegrees,
         playerGlowing,
         screenDistance,
         screenOffsetX,
         screenOffsetY,
         screenHeight,
         screenAspect,
         screenRoll,
         gizmoTool,
         gizmoHandle,
         localSpace,
         0,
         new float[]{screenDistance},
         new float[]{screenOffsetX},
         new float[]{screenOffsetY},
         new float[]{screenHeight},
         new float[]{screenAspect},
         new float[]{0.0F},
         new float[]{0.0F},
         new float[]{screenRoll},
         new float[]{1.0F},
         new float[]{1.0F},
         new float[]{1.0F},
         new float[]{0.0F},
         new float[]{0.0F},
         new float[]{0.0F},
         new float[]{0.0F},
         new float[]{0.0F},
         new int[]{0},
         false,
         false,
         0,
         0,
         0,
         0.0F,
         0.0F,
         0.0F,
         TerrainPreviewFrame.empty(),
         null,
         x0,
         y0,
         x1,
         y1,
         scale
      );
   }

   public HolographicPreviewPipRenderState(
      LivingEntity playerEntity,
      Vector3f playerTranslation,
      float playerScale,
      float previewYaw,
      float previewPitch,
      boolean firstPerson,
      float fovDegrees,
      boolean playerGlowing,
      int selectedScreen,
      float[] screenDistances,
      float[] screenOffsetXs,
      float[] screenOffsetYs,
      float[] screenHeights,
      float[] screenAspects,
      float[] screenYaws,
      float[] screenPitches,
      float[] screenRolls,
      int[] elementTypes,
      float[] screenScaleXs,
      float[] screenScaleYs,
      float[] screenScaleZs,
      float[] screenPivotXs,
      float[] screenPivotYs,
      float[] screenPivotZs,
      float[] screenSkewXByYs,
      float[] screenSkewYByXs,
      int gizmoTool,
      int gizmoHandle,
      boolean localSpace,
      boolean controlConsoleModel,
      boolean renderWorldTerrain,
      int worldOriginX,
      int worldOriginY,
      int worldOriginZ,
      float worldRangeX,
      float worldRangeY,
      float worldRangeZ,
      TerrainPreviewFrame terrainFrame,
      CameraFrame cameraFrame,
      int x0,
      int y0,
      int x1,
      int y1,
      float scale
   ) {
      this(
         playerEntity,
         playerTranslation,
         playerScale,
         previewYaw,
         previewPitch,
         firstPerson,
         fovDegrees,
         playerGlowing,
         valueAt(screenDistances, selectedScreen, 2.2F),
         valueAt(screenOffsetXs, selectedScreen, 0.0F),
         valueAt(screenOffsetYs, selectedScreen, 0.05F),
         valueAt(screenHeights, selectedScreen, 0.75F),
         valueAt(screenAspects, selectedScreen, 1.7777778F),
         valueAt(screenRolls, selectedScreen, 0.0F),
         gizmoTool,
         gizmoHandle,
         localSpace,
         selectedScreen,
         screenDistances,
         screenOffsetXs,
         screenOffsetYs,
         screenHeights,
         screenAspects,
         screenYaws,
         screenPitches,
         screenRolls,
         screenScaleXs,
         screenScaleYs,
         screenScaleZs,
         screenPivotXs,
         screenPivotYs,
         screenPivotZs,
         screenSkewXByYs,
         screenSkewYByXs,
         elementTypes,
         controlConsoleModel,
         renderWorldTerrain,
         worldOriginX,
         worldOriginY,
         worldOriginZ,
         worldRangeX,
         worldRangeY,
         worldRangeZ,
         terrainFrame,
         cameraFrame,
         x0,
         y0,
         x1,
         y1,
         scale
      );
   }

   public HolographicPreviewPipRenderState(
      LivingEntity playerEntity,
      Vector3f playerTranslation,
      float playerScale,
      float previewYaw,
      float previewPitch,
      boolean firstPerson,
      float fovDegrees,
      boolean playerGlowing,
      int selectedScreen,
      float[] screenDistances,
      float[] screenOffsetXs,
      float[] screenOffsetYs,
      float[] screenHeights,
      float[] screenAspects,
      float[] screenRolls,
      int gizmoTool,
      int gizmoHandle,
      boolean localSpace,
      int x0,
      int y0,
      int x1,
      int y1,
      float scale
   ) {
      this(
         playerEntity,
         playerTranslation,
         playerScale,
         previewYaw,
         previewPitch,
         firstPerson,
         fovDegrees,
         playerGlowing,
         valueAt(screenDistances, selectedScreen, 2.2F),
         valueAt(screenOffsetXs, selectedScreen, 0.0F),
         valueAt(screenOffsetYs, selectedScreen, 0.05F),
         valueAt(screenHeights, selectedScreen, 0.75F),
         valueAt(screenAspects, selectedScreen, 1.7777778F),
         valueAt(screenRolls, selectedScreen, 0.0F),
         gizmoTool,
         gizmoHandle,
         localSpace,
         selectedScreen,
         screenDistances,
         screenOffsetXs,
         screenOffsetYs,
         screenHeights,
         screenAspects,
         new float[screenRolls.length],
         new float[screenRolls.length],
         screenRolls,
         ones(screenRolls.length),
         ones(screenRolls.length),
         ones(screenRolls.length),
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new int[screenRolls.length],
         false,
         false,
         0,
         0,
         0,
         0.0F,
         0.0F,
         0.0F,
         TerrainPreviewFrame.empty(),
         null,
         x0,
         y0,
         x1,
         y1,
         scale
      );
   }

   public HolographicPreviewPipRenderState(
      LivingEntity playerEntity,
      Vector3f playerTranslation,
      float playerScale,
      float previewYaw,
      float previewPitch,
      boolean firstPerson,
      float fovDegrees,
      boolean playerGlowing,
      int selectedScreen,
      float[] screenDistances,
      float[] screenOffsetXs,
      float[] screenOffsetYs,
      float[] screenHeights,
      float[] screenAspects,
      float[] screenRolls,
      int gizmoTool,
      int gizmoHandle,
      boolean localSpace,
      CameraFrame cameraFrame,
      int x0,
      int y0,
      int x1,
      int y1,
      float scale
   ) {
      this(
         playerEntity,
         playerTranslation,
         playerScale,
         previewYaw,
         previewPitch,
         firstPerson,
         fovDegrees,
         playerGlowing,
         valueAt(screenDistances, selectedScreen, 2.2F),
         valueAt(screenOffsetXs, selectedScreen, 0.0F),
         valueAt(screenOffsetYs, selectedScreen, 0.05F),
         valueAt(screenHeights, selectedScreen, 0.75F),
         valueAt(screenAspects, selectedScreen, 1.7777778F),
         valueAt(screenRolls, selectedScreen, 0.0F),
         gizmoTool,
         gizmoHandle,
         localSpace,
         selectedScreen,
         screenDistances,
         screenOffsetXs,
         screenOffsetYs,
         screenHeights,
         screenAspects,
         new float[screenRolls.length],
         new float[screenRolls.length],
         screenRolls,
         ones(screenRolls.length),
         ones(screenRolls.length),
         ones(screenRolls.length),
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new float[screenRolls.length],
         new int[screenRolls.length],
         false,
         false,
         0,
         0,
         0,
         0.0F,
         0.0F,
         0.0F,
         TerrainPreviewFrame.empty(),
         cameraFrame,
         x0,
         y0,
         x1,
         y1,
         scale
      );
   }

   private static float[] copy(float[] values) {
      return values != null ? (float[])values.clone() : null;
   }

   private static int[] copy(int[] values) {
      return values != null ? (int[])values.clone() : null;
   }

   private static float[] ones(int length) {
      float[] values = new float[Math.max(0, length)];
      Arrays.fill(values, 1.0F);
      return values;
   }

   private static float valueAt(float[] values, int index, float fallback) {
      return values != null && index >= 0 && index < values.length ? values[index] : fallback;
   }
}
