package com.zhongbai233.net_music_can_play_bili.ncpbmodel;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.renderer.block.model.BlockElementFace;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBaker;
import net.minecraft.client.resources.model.ModelState;
import net.minecraft.core.Direction;
import net.neoforged.neoforge.client.model.IModelBuilder;
import net.neoforged.neoforge.client.model.geometry.BlockGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.IGeometryBakingContext;
import net.neoforged.neoforge.client.model.geometry.SimpleUnbakedGeometry;
import net.neoforged.neoforge.client.model.geometry.UnbakedGeometryHelper;

/**
 * 带「任意角度 / 三轴」元素支持的模型几何。
 *
 * <h2>元素与贴图来源</h2>
 * <ul>
 *   <li><b>贴图</b>：一律走 {@code IGeometryBakingContext.getMaterial(...)}，
 *       它会沿 {@code BlockModel} 父链解析，因此子模型（例如 {@code item/modern_turntable}）
 *       无需自己重复声明贴图。</li>
 *   <li><b>普通元素</b>：若模型自己没有 {@code elements}（纯 parent 模型），
 *       这里会像原版一样把父链上的元素收集过来。原因是
 *       {@code UnbakedGeometryHelper.bake} 只认 <b>模型自己</b> 的几何，
 *       挂了 {@code loader} 的模型就不会再走原版那条"继承父模型元素"的路径了。</li>
 *   <li><b>欧拉元素</b>：来自 {@code ncpb_euler_elements}，由本模组自己烘焙。</li>
 * </ul>
 *
 * <p>所有面都交给 {@link UnbakedGeometryHelper#bakeElementFace} 烘焙，只有传给它的
 * {@link ModelState} 不同 —— 欧拉元素用携带自定义旋转的那个。这样顶点格式、法线、
 * cullface 处理都与同文件其它元素<b>同构</b>。</p>
 */
public final class EulerElementsGeometry extends SimpleUnbakedGeometry<EulerElementsGeometry> {

    private final List<BlockElement> ownElements;
    private final List<EulerElement> eulerElements;

    public EulerElementsGeometry(List<BlockElement> ownElements, List<EulerElement> eulerElements) {
        this.ownElements = List.copyOf(ownElements);
        this.eulerElements = List.copyOf(eulerElements);
    }

    public List<BlockElement> ownElements() {
        return this.ownElements;
    }

    public List<EulerElement> eulerElements() {
        return this.eulerElements;
    }

    @Override
    protected void addQuads(IGeometryBakingContext context, IModelBuilder<?> builder, ModelBaker baker,
                            Function<Material, TextureAtlasSprite> sprites, ModelState state) {
        List<BlockElement> plain = resolveInheritedElements(context, this.ownElements);
        if (!plain.isEmpty()) {
            bakeResolvingTextures(context, builder, plain, sprites, state);
        }
        for (EulerElement euler : this.eulerElements) {
            bakeResolvingTextures(context, builder, List.of(euler.element()), sprites, euler.state(state));
        }
    }

    /**
     * 与 {@link UnbakedGeometryHelper#bakeElements} 同构，但**先解析 {@code #} 贴图引用**。
     *
     * <p>为什么不能直接用 {@code bakeElements}：它的每个面做的是
     * {@code sprites.apply(new Material(LOCATION_BLOCKS, ResourceLocation.parse(face.texture())))}
     * （见 NeoForge 的 {@code lambda$bakeElements$3} 字节码，偏移 0–19）——
     * 对 {@code "#0"} 就是拿一个含 {@code #} 的字符串去 {@code ResourceLocation.parse}，
     * 而它只接受 {@code [a-z0-9/._-]}，于是整个模型烘焙失败。
     * NeoForge 给 {@code #} 准备的入口是 {@link UnbakedGeometryHelper#resolveDirtyMaterial}
     * （它 {@code startsWith("#")} 时改走 {@code context.getMaterial(...)}，会沿父链解析，
     * 所以 {@code "#0" -> "#projector" -> 叶子自己的贴图} 这条链才通）。
     * 本模组的模型（及其父链）一律用这种写法，因此必须自己走这一步。</p>
     */
    private static void bakeResolvingTextures(IGeometryBakingContext context, IModelBuilder<?> builder,
                                              List<BlockElement> elements,
                                              Function<Material, TextureAtlasSprite> sprites,
                                              ModelState state) {
        for (BlockElement element : elements) {
            // 1.21.1 的 BlockElement.faces 是 public final 字段（不是 record 访问器）
            for (Map.Entry<Direction, BlockElementFace> entry : element.faces.entrySet()) {
                Direction direction = entry.getKey();
                BlockElementFace face = entry.getValue();
                Material material = UnbakedGeometryHelper.resolveDirtyMaterial(face.texture(), context);
                BakedQuad quad = UnbakedGeometryHelper.bakeElementFace(
                        element, face, sprites.apply(material), direction, state);
                Direction cull = face.cullForDirection();
                if (cull == null) {
                    builder.addUnculledFace(quad);
                } else {
                    builder.addCulledFace(Direction.rotate(state.getRotation().getMatrix(), cull), quad);
                }
            }
        }
    }

    /**
     * 模拟原版「子模型没有 elements 时继承父模型 elements」的行为。
     *
     * <p>原版那条路径在 {@code BlockModel.bakeVanilla} 里；一旦模型挂了自定义 {@code loader}，
     * 就会改走 {@code IUnbakedGeometry.bake}，父链继承就断了。所以在这里补回来。</p>
     */
    private static List<BlockElement> resolveInheritedElements(IGeometryBakingContext context,
                                                               List<BlockElement> own) {
        if (!own.isEmpty()) {
            return own;
        }
        if (!(context instanceof BlockGeometryBakingContext blockContext)) {
            return own;
        }
        List<BlockElement> inherited = new java.util.ArrayList<>();
        for (net.minecraft.client.renderer.block.model.BlockModel model = blockContext.owner;
             model != null;
             model = model.parent) {
            inherited.addAll(model.getElements());
        }
        return inherited;
    }
}
