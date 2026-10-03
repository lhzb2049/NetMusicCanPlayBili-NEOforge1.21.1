package com.zhongbai233.net_music_can_play_bili.ncpbmodel;

import com.mojang.math.Transformation;
import net.minecraft.client.renderer.block.model.BlockElement;
import net.minecraft.client.resources.model.ModelState;
import net.neoforged.neoforge.client.model.SimpleModelState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 一个允许「任意角度、三轴同时」旋转的元素。
 *
 * <h2>为什么需要这个类</h2>
 * 1.21.1 的元素旋转有两条硬限制，都写在原版字节码里：
 * <ol>
 *   <li>{@code BlockElementRotation} 的构造器只接受 {@code (origin, Direction.Axis, angle, rescale)} —— <b>单轴</b>；</li>
 *   <li>{@code BlockElement$Deserializer.getAngle} 只放行 {@code 0 / ±22.5 / ±45}，其它值直接抛
 *       {@code JsonParseException}，<b>整个模型加载失败</b>。</li>
 * </ol>
 * 而 26.1.2 的模型用的是逐轴欧拉角（{@code {origin, x, y, z}}），角度是个连续值
 * （例如投影仪挡板 90°、现代唱片机碟片 −67.5/−90/−112.5），一个都写不进去。
 *
 * <h2>绕开的办法</h2>
 * 原版 {@code FaceBakery.bakeQuad(from, to, face, sprite, facing, ModelState, elementRotation, shade)}
 * 会把 {@code ModelState.getRotation()} 这个 {@link Transformation} 作用到该面的每个顶点上
 * （字节码 offset 237 处取 {@code ModelState.getRotation()} 传给 {@code makeVertices}）。
 * 而 {@code ModelState} 是可以自定义的 —— 于是把「绕 origin 的欧拉旋转」塞进 ModelState，
 * 就能得到任意角度、任意轴数，且<b>完全不碰原版类</b>。
 *
 * <p>矩阵口径与 26.1.2 的 {@code CuboidRotation$EulerXYZRotation.transformation()}
 * <b>逐字一致</b>（直接照抄那两行字节码），因此不存在"旋转顺序猜错"的问题：</p>
 * <pre>
 *   new Matrix4f().rotationZYX(z * DEG_TO_RAD, y * DEG_TO_RAD, x * DEG_TO_RAD)
 * </pre>
 * 外层再套 origin 的平移，等价于原版 {@code applyElementRotation} 的"绕 origin 旋转"。
 */
public final class EulerElement {

    /** 26.1.2 用的是硬编码 0.017453292f（不是 Math.PI/180），这里保持一致。 */
    private static final float DEG_TO_RAD = 0.017453292F;

    private final BlockElement element;
    private final Matrix4f rotation;

    /**
     * @param element 已构造好的元素（{@code rotation} 必须为 {@code null}，旋转改由本类提供）
     * @param origin  旋转中心，<b>单位为方块</b>（即 JSON 里的 0~16 值 × 0.0625）
     * @param x       绕 X 轴角度（度）
     * @param y       绕 Y 轴角度（度）
     * @param z       绕 Z 轴角度（度）
     */
    public EulerElement(BlockElement element, Vector3f origin, float x, float y, float z) {
        this.element = element;
        this.rotation = new Matrix4f()
                .translate(origin.x, origin.y, origin.z)
                .mul(new Matrix4f().rotationZYX(z * DEG_TO_RAD, y * DEG_TO_RAD, x * DEG_TO_RAD))
                .translate(-origin.x, -origin.y, -origin.z);
    }

    public BlockElement element() {
        return this.element;
    }

    /**
     * 把本元素的欧拉旋转**叠在方块状态的 {@link ModelState} 之下**，并抵消引擎那层「固定支点」共轭。
     *
     * <p><b>关键事实</b>（`FaceBakery` 字节码：`applyModelRotation` + `rotateVertexBy`）：
     * 顶点在 <b>0~1 空间</b>，而 ModelState 的矩阵**永远绕固定支点 (0.5,0.5,0.5) 施加** ——
     * {@code v' = 0.5 + M·(v − 0.5)}，也就是真正生效的是 {@code T(0.5)·M·T(-0.5)}。
     * 于是直接把 {@code T(origin)·R·T(-origin)} 当 M 交出去，实际支点会变成
     * {@code origin + 0.5}：**每个轴都差半格**，元素会绕着错误的点被甩出去。</p>
     *
     * <p>所以这里先把它共轭回来：交出 {@code M' = state · T(-0.5) · Euler · T(0.5)}，
     * 引擎再套一层共轭就恰好等于「先绕元素 origin 转，再整体绕方块中心跟方块转」——
     * 与 `bakeVertex` 里 `applyElementRotation` → `applyModelRotation` 的先后顺序一致。
     * 这条等价性由 {@code t5/EulerMathProbe.java} 离线逐顶点验证（不需要启动游戏）。</p>
     */
    public ModelState state(ModelState outer) {
        Matrix4f matrix = new Matrix4f(outer.getRotation().getMatrix())
                .mul(new Matrix4f().translation(-0.5F, -0.5F, -0.5F))
                .mul(this.rotation)
                .mul(new Matrix4f().translation(0.5F, 0.5F, 0.5F));
        // uvLocked = false：与同一模型里其它元素保持一致（方块状态没开 uvlock），
        // 这样作者写好的 uv 会原样保留，不会被 recomputeUVs 按旋转后的朝向重算。
        return new SimpleModelState(new Transformation(matrix), false);
    }
}
