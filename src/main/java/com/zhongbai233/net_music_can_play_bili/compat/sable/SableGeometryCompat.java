package com.zhongbai233.net_music_can_play_bili.compat.sable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;

/**
 * Sable（Create: Aeronautics 的物理库）软兼容层：把「音源/投影仪在 sub-level 里」这件事翻译成
 * 正确的**世界坐标距离 / 包含判定**。
 *
 * <h2>为什么需要它（证据链）</h2>
 * 物理化之后，方块实体住在同一个 {@code Level} 的一片「plot chunk」里（实测坐标
 * {@code 20481032, 128, 20481032}），而不是它物理化之前的世界坐标（{@code -26,-60,26}）。
 * 关键：**摄像机/听者仍在世界坐标系**（约 2000 万格之外），证据是 Sable 自己的
 * {@code MovingSoundInstanceDelegate.getX()} 会把 plot 坐标 {@code logicalPose().transformPosition(…)}
 * 成世界坐标后再交给 MC 的 {@code SoundEngine}，而 NetMusic 普通唱片机在同一结构上有声 ——
 * 只有"听者在世界坐标"这一种解释能同时满足这两点。于是所有「拿 plot 当世界」的距离比较都会
 * 得到约 2000 万格，判定必然相反：
 * <ul>
 *   <li>服务端受众判定（96+48 格）：判「太远」→ 不再给玩家发包；</li>
 *   <li>客户端响度需求（64 格球）：距离 2000 万 → 增益 0 → 结构上无声；</li>
 *   <li>客户端视频可见性（64 格）：判「看不见」→ 永不授予解码准入 → 投影仪永远停在加载占位图。</li>
 * </ul>
 * 本类把这几处换成 Sable 自己的口径，且**不装 Sable 时自动退化为原行为**。
 *
 * <h2>实现约定</h2>
 * 全部走反射（字符串类名 + 名字/参数形状匹配），因此：不引入 Sable 编译期依赖、
 * 不加载 Sable 的类、不解析 MC 类型常量；任何一步失败都会 fail-open 并**留痕**
 * （{@code degradations}/{@code lastDegradation} + 一条日志），绝不静默。
 */
public final class SableGeometryCompat {
    private static final String SABLE_CLASS = "dev.ryanhcode.sable.Sable";
    private static final String HELPER_FIELD = "HELPER";
    private static final String COMPANION_CLASS = "dev.ryanhcode.sable.ActiveSableCompanion";
    private static final String HOLDER_CLASS = "dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder";
    private static final String HOLDER_GETTER = "sable$getPlotContainer";
    private static final String METHOD_DISTANCE = "distanceSquaredWithSubLevels";
    private static final String METHOD_CONTAINING = "getContaining";
    private static final String METHOD_PROJECT = "projectOutOfSubLevel";

    /** 超过这个量级就认为是 plot 坐标（plot 网格在 2×10⁷ 附近，正常世界坐标远小于此）。 */
    private static final double PLOT_SCALE = 100000.0D;

    /**
     * 离线测试钩子：非 null 时直接当作 Sable 的 helper 用（见 t4/test 下的行为探针）。
     * 生产路径永远是 null；包级可见，只有同包的探针能设置。
     */
    static Object testHelper;

    private static final ThreadLocal<Level> SOURCE_LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<Level> PREVIOUS_LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<Integer> SOURCE_DEPTH = new ThreadLocal<>();
    private static final ThreadLocal<double[]> PROJECTOR_POS = new ThreadLocal<>();
    private static final ThreadLocal<double[]> PREVIOUS_PROJECTOR_POS = new ThreadLocal<>();

    private static boolean resolved;
    private static boolean loggedResolve;
    private static boolean loggedFailure;
    private static boolean loggedPlotMiss;
    private static boolean projectionLogged;
    private static boolean liveMoveLogged;
    private static int failures;
    private static String lastFailure;
    private static Object helper;
    private static Method distanceMethod;
    private static Method containingMethod;
    private static Method projectMethod;

    private SableGeometryCompat() {
    }

    // ------------------------------------------------------------------ 作用域（服务端受众判定用）
    // AudioEndpointSubscriptionTracker.update(...) 里没有 Level 参数，而它要拿的
    // sourcePos 是 plot 坐标。调用方 ModernTurntableAudienceSync 手里有 Level，
    // 所以在它 HEAD/RETURN 处压栈/出栈，tracker 里再取。

    public static void pushSourceLevel(Level level) {
        int depth = SOURCE_DEPTH.get() == null ? 0 : SOURCE_DEPTH.get();
        if (depth == 0) {
            PREVIOUS_LEVEL.set(SOURCE_LEVEL.get());
            SOURCE_LEVEL.set(level);
        }
        SOURCE_DEPTH.set(depth + 1);
    }

    public static void popSourceLevel() {
        int depth = SOURCE_DEPTH.get() == null ? 0 : SOURCE_DEPTH.get();
        if (depth <= 1) {
            // 深度 0 时被弹出 = 无操作（不去动别人的压栈）；深度 1 才真正恢复上一层
            SOURCE_DEPTH.remove();
            Level previous = PREVIOUS_LEVEL.get();
            PREVIOUS_LEVEL.remove();
            if (previous == null) {
                SOURCE_LEVEL.remove();
            } else {
                SOURCE_LEVEL.set(previous);
            }
        } else {
            SOURCE_DEPTH.set(depth - 1);
        }
    }

    /** 用当前压栈的 Level 算 sub-level 感知的距离平方；没有 Level 时退化为欧氏。 */
    public static double distanceSquaredInScope(double ax, double ay, double az, double bx, double by, double bz) {
        return distanceSquared(SOURCE_LEVEL.get(), ax, ay, az, bx, by, bz);
    }

    // ------------------------------------------------------------------ 距离

    /**
     * sub-level 感知的距离平方：两边各自 projectOutOfSubLevel（plot → 世界）后再算。
     * Sable 缺席 / 出任何异常 → 退化为普通欧氏距离（与修改前行为一致）。
     */
    public static double distanceSquared(Level level, double ax, double ay, double az, double bx, double by, double bz) {
        resolve();
        Method method = distanceMethod;
        if (method != null) {
            try {
                Object value = method.invoke(helper, level, ax, ay, az, bx, by, bz);
                if (value instanceof Number number) {
                    return number.doubleValue();
                }
            } catch (Throwable failure) {
                degradeAll("distanceSquaredWithSubLevels 调用失败，后续改用欧氏距离", failure);
            }
        }
        return euclideanSquared(ax, ay, az, bx, by, bz);
    }

    /** 给 {@code AudioUtils.distance(float[], float[])} 的替换用；数组异常时返回 MAX_VALUE（与旧行为一致：判为听不见）。 */
    public static float distance(Level level, float[] a, float[] b) {
        if (a == null || b == null || a.length < 3 || b.length < 3) {
            return Float.MAX_VALUE;
        }
        return (float) Math.sqrt(distanceSquared(level, a[0], a[1], a[2], b[0], b[1], b[2]));
    }

    // ------------------------------------------------------------------ 视频可见性：投影仪坐标作用域
    // VideoBillboardQuadSupport.isProjectorWithinRenderDistance(...) 的**调用点**只有一个距离比较，
    // 而那次比较的另一个参数（AABB bounds）是用投影仪的 plot 坐标算出来的。@Redirect 的处理器
    // 拿不到投影仪本身，所以在方法 HEAD 把它的 BlockPos 压栈、RETURN 出栈，重定向里再取。

    public static void pushProjectorPos(double x, double y, double z) {
        PREVIOUS_PROJECTOR_POS.set(PROJECTOR_POS.get());
        PROJECTOR_POS.set(new double[]{x, y, z});
    }

    public static void popProjectorPos() {
        double[] previous = PREVIOUS_PROJECTOR_POS.get();
        PREVIOUS_PROJECTOR_POS.remove();
        if (previous == null) {
            PROJECTOR_POS.remove();
        } else {
            PROJECTOR_POS.set(previous);
        }
    }

    /** 压栈的投影仪（= 正在被判定可见性的那个方块实体）是否落在某个 sub-level 的 plot 里。 */
    public static boolean isProjectorInSubLevel(Level level) {
        double[] projector = PROJECTOR_POS.get();
        return projector != null && isInsideSubLevel(level, projector[0], projector[2]);
    }

    /** 投影仪中心 ↔ 给定世界坐标的 sub-level 感知距离平方；没有压栈坐标时返回 NaN（调用方用原实现）。 */
    public static double distanceSquaredFromProjector(Level level, double x, double y, double z) {
        double[] projector = PROJECTOR_POS.get();
        if (projector == null) {
            return Double.NaN;
        }
        return distanceSquared(level, projector[0], projector[1], projector[2], x, y, z);
    }

    // ------------------------------------------------------------------ 遮挡射线

    private static final float[] RAY_SKIP = new float[0];

    /** 是否 plot 量级坐标（用于"绝不让射线走 2000 万格"的硬兜底）。 */
    public static boolean isPlotScale(double x, double z) {
        return Math.abs(x) >= PLOT_SCALE || Math.abs(z) >= PLOT_SCALE;
    }

    /**
     * 遮挡射线该往哪射（{@code VideoScreenOcclusion.isOccluded} 那次
     * {@code BlockGetter.traverseBlocks} 用的）。三种结果：
     * <ul>
     *   <li>{@code null} —— 目标本来就是世界坐标（地面场景）：调用方用原样参数射，行为不变；</li>
     *   <li>长度 3 —— 目标在 sub-level 里（plot）：用这个**世界坐标**射。结构在世界里就长在眼前
     *       几格，射线自然短；</li>
     *   <li>长度 0 —— 是 plot 量级坐标却拿不到世界坐标：**别射**。这条是硬兜底，也是本次"特别卡"的
     *       根因封堵：摄像机在世界坐标、目标在 plot 时，这条 DDA 会走约 2000 万格
     *       （实测把渲染线程吃满、游戏掉到几帧）。宁可判"没被遮挡"（fail-open，最多多画一帧视频），
     *       也绝不再走那条路。</li>
     * </ul>
     */
    public static float[] occlusionRayTarget(Level level, double x, double y, double z) {
        float[] projected = projectToWorld(level, x, y, z);
        if (projected != null) {
            return projected;
        }
        return isPlotScale(x, z) ? RAY_SKIP : null;
    }

    // ------------------------------------------------------------------ 网络包"距离守卫"作用域
    // 8 个服务端包处理器里都有同一句守卫（`player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0`）：
    // 玩家在世界坐标、payload.pos() 是 plot 坐标 ⇒ 距离² ≈ 8×10¹⁴ ⇒ 守卫失败 ⇒ **整包被静默丢弃**
    // （表现：方块上的 UI 能打开、按钮点了没反应，而破坏/取放物品照常，因为那些走方块实体自己的路径）。
    // 这里给这些处理器一个**专用**作用域（不共用音频那条 SOURCE_LEVEL，避免误弹出别人的压栈），
    // Mixin 在 handle 的 HEAD 压栈、RETURN 出栈，重定向里按这个 Level 算 sub-level 感知距离。

    private static final ThreadLocal<Level> NETWORK_LEVEL = new ThreadLocal<>();
    private static final ThreadLocal<Integer> NETWORK_DEPTH = new ThreadLocal<>();

    public static void pushNetworkScope(Level level) {
        int depth = NETWORK_DEPTH.get() == null ? 0 : NETWORK_DEPTH.get();
        if (depth == 0) {
            NETWORK_LEVEL.set(level);
        }
        NETWORK_DEPTH.set(depth + 1);
    }

    /** 深度计数：万一某次只压不弹（或只弹不压），也不会污染别人的作用域，更不会把 Level 留成半状态。 */
    public static void popNetworkScope() {
        int depth = NETWORK_DEPTH.get() == null ? 0 : NETWORK_DEPTH.get();
        if (depth <= 1) {
            NETWORK_DEPTH.remove();
            NETWORK_LEVEL.remove();
        } else {
            NETWORK_DEPTH.set(depth - 1);
        }
    }

    /** 用网络包作用域里的 Level 算 sub-level 感知距离²；没压栈时退化为普通欧氏距离。 */
    public static double distanceSquaredInNetworkScope(double ax, double ay, double az,
                                                      double bx, double by, double bz) {
        return distanceSquared(NETWORK_LEVEL.get(), ax, ay, az, bx, by, bz);
    }

    // ------------------------------------------------------------------ 包含判定

    /** 该 plot 坐标是否落在某个 sub-level 内（= 该位置属于物理化结构）。 */
    public static boolean isInsideSubLevel(Level level, double x, double z) {
        resolve();
        Method method = containingMethod;
        if (method == null) {
            diagnosePlotMiss(level, x, z);
            return false;
        }
        try {
            Object contained = method.invoke(helper, level, x, z);
            if (contained == null) {
                // 不在这里诊断：世界坐标本来就该没命中，诊断只在"plot 量级却没命中"时才说话
                diagnosePlotMiss(level, x, z);
            }
            return contained != null;
        } catch (Throwable failure) {
            degradeAll("getContaining 调用失败，后续改用欧氏距离", failure);
            return false;
        }
    }

    /** 兼容层是否真的接上了 Sable（供日志/诊断用）。 */
    public static boolean isAvailable() {
        resolve();
        return helper != null && distanceMethod != null;
    }

    // ------------------------------------------------------------------ 音源世界位置

    /**
     * 给 {@code AudioUtils.centerFor(BlockPos)} 用的替换：如果这个 BlockPos 落在某个 sub-level 的
     * plot 里，返回它**投影到世界**之后的中心点（float[3]）；否则返回 {@code null}，让原方法照常返回
     * {@code {x+0.5, y+0.5, z+0.5}}。
     *
     * <p>为什么必须在这一层做：NCPB 的媒体音频不走 Minecraft 的 {@code SoundEngine}（Sable 的
     * {@code SoundEngineMixin} 只接管那里），而是自己开 OpenAL 源、自己算空间增益；它把
     * {@code AudioUtils.centerFor(sourcePos)} 当作「音源世界位置」存进 {@code AudioEntry}、
     * 用于 {@code audioDemandDebug} / {@code hasAudibleOutput} / 空间增益。
     * 物理化后那是个 plot 坐标（约 2000 万格），距离算出来是 2000 万 → 增益 0 → 结构上无声。
     * 换掉这一个入口，所有下游（位置、距离、增益）一起变正确。
     */
    public static float[] centerFor(Level level, BlockPos pos) {
        if (pos == null) {
            return null;
        }
        return projectToWorld(level, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /**
     * 「结构真的动了」的一次性证据行：逐帧重投影时，若现算的世界坐标与注册时冻结的那个
     * 相差超过 0.1 格，就打一条日志（每个进程只打一条）。
     *
     * <p>为什么要有它：这个修复的效果是"听起来对"，而声音没法自动断言。有了这一行，
     * 游戏内就能区分三种情况——没这行说明结构根本没动（或这条路没被走到）；有这行说明
     * 重投影确实生效并跟着结构走了；数字就是移动了多少。
     * 只比阈值、不做任何其它副作用，所以逐帧调用的成本可以忽略。
     */
    public static void noteLivePositionRefreshed(float[] frozen, float[] live) {
        if (liveMoveLogged || frozen == null || live == null || frozen.length < 3 || live.length < 3) {
            return;
        }
        double dx = live[0] - frozen[0];
        double dy = live[1] - frozen[1];
        double dz = live[2] - frozen[2];
        if (dx * dx + dy * dy + dz * dz < 0.01D) {
            return;
        }
        liveMoveLogged = true;
        emit("音源世界位置已随结构刷新：注册时 (%.1f, %.1f, %.1f) → 现在 (%.1f, %.1f, %.1f)"
                .formatted(frozen[0], frozen[1], frozen[2], live[0], live[1], live[2]));
    }

    /**
     * plot 坐标 → 世界坐标的原始投影；不在 sub-level 内时返回 {@code null}。
     *
     * <p>刻意只用 double 进出、且**不构造任何 MC 类型**（参数用本类自己的 {@link Point}
     * 实现 {@code Position}，返回值用 {@link #readCoordinates(Object)} 读）——这样离线探针
     * 可以在不含 MC 依赖的 JVM 里跑这条路径（踩过：`new Vec3(...)`/`new BlockPos(...)`
     * 的静态初始化会把 fastutil、DataFixerUpper 一串拖进来）。
     */
    public static float[] projectToWorld(Level level, double x, double y, double z) {
        if (!isInsideSubLevel(level, x, z)) {
            return null;
        }
        resolve();
        Method method = projectMethod;
        if (method == null) {
            return null;
        }
        try {
            float[] projected = readCoordinates(method.invoke(helper, level, new Point(x, y, z)));
            if (projected != null && !projectionLogged) {
                projectionLogged = true;
                // 一次性证据行：结构上"听不到"时，先看有没有这一行——
                // 没有它说明这次调用根本没落进 sub-level（坐标口径仍然不对）。
                emit("音源位置投影生效：plot 坐标 (%.1f, %.1f, %.1f) → 世界坐标 (%.1f, %.1f, %.1f)"
                        .formatted(x, y, z, projected[0], projected[1], projected[2]));
            }
            return projected;
        } catch (Throwable failure) {
            degradeAll("projectOutOfSubLevel 调用失败，后续改用欧氏距离", failure);
            return null;
        }
    }

    /**
     * 把 Sable 返回的坐标对象读成 float[3]（{@code Vec3} / JOML {@code Vector3d} / 任何带
     * x()/getX()/公有 x 字段的对象）。
     *
     * <p><b>这里踩过一次真坑，务必保留首选路径</b>：{@code net.minecraft.world.phys.Vec3}
     * 实现的接口是 {@code net.minecraft.core.Position}，而它**只有 {@code x()/y()/z()}，
     * 没有 {@code getX()/getY()/getZ()}**（javap 实证，构建门禁 [2f] 每次都会复查）。
     * 最初这里只反射找 {@code getX/getY/getZ} → {@code NoSuchMethodException} 被下面的
     * catch 吞掉 → 投影永远返回 null → 整个 centerFor Mixin 静默失效，而离线探针的假对象
     * 恰好自带 getX，所以检查跟着错误假设一起"通过"了。现在首选路径由编译器保证，
     * 读不出来还会记账留痕（{@link #failureCount()}）。
     */
    private static float[] readCoordinates(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Position position) {
            // Vec3 implements Position（编译期可证）→ 这条路径不可能再因为访问器名字而静默失效
            return new float[]{(float) position.x(), (float) position.y(), (float) position.z()};
        }
        float[] viaMethods = readViaMethods(value, "x", "y", "z");
        if (viaMethods == null) {
            viaMethods = readViaMethods(value, "getX", "getY", "getZ");
        }
        if (viaMethods != null) {
            return viaMethods;
        }
        float[] viaFields = readViaFields(value);
        if (viaFields != null) {
            return viaFields;
        }
        // 读不出来必须留痕：静默返回 null 会让"投影失效"和"本来就不在 sub-level 里"长得一模一样
        recordFailure("无法从 %s 读取坐标（既不是 Position，也没有 x()/getX()/公有 x 字段）"
                .formatted(value.getClass().getName()), null);
        return null;
    }

    private static float[] readViaMethods(Object value, String xName, String yName, String zName) {
        try {
            Class<?> type = value.getClass();
            Number x = (Number) type.getMethod(xName).invoke(value);
            Number y = (Number) type.getMethod(yName).invoke(value);
            Number z = (Number) type.getMethod(zName).invoke(value);
            if (x == null || y == null || z == null) {
                return null;
            }
            return new float[]{x.floatValue(), y.floatValue(), z.floatValue()};
        } catch (Throwable missing) {
            return null;
        }
    }

    private static float[] readViaFields(Object value) {
        try {
            Class<?> type = value.getClass();
            Field x = type.getField("x");
            Field y = type.getField("y");
            Field z = type.getField("z");
            return new float[]{((Number) x.get(value)).floatValue(),
                    ((Number) y.get(value)).floatValue(),
                    ((Number) z.get(value)).floatValue()};
        } catch (Throwable missing) {
            return null;
        }
    }

    /** 传给 Sable 的最小 {@code Position} 实现（不依赖 world.phys.Vec3）。 */
    private record Point(double x, double y, double z) implements Position {
    }

    // ------------------------------------------------------------------ 内部

    private static double euclideanSquared(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 只记账 + 一次性日志：某个**返回值读不出来**时不该把整套 Sable 能力一起废掉。 */
    private static void recordFailure(String message, Throwable failure) {
        failures++;
        lastFailure = failure == null ? message : (message + "：" + failure);
        if (!loggedFailure) {
            loggedFailure = true;
            emit("兼容层异常（本次按原实现继续，不再静默）：" + lastFailure);
        }
    }

    /** 记账 + 禁用全部 Sable 能力：调用本身失败时（Sable 内部抛错）继续用反射只会连环失败。 */
    private static void degradeAll(String message, Throwable failure) {
        recordFailure(message, failure);
        helper = null;
        distanceMethod = null;
        containingMethod = null;
        projectMethod = null;
    }

    /**
     * plot 量级坐标却没被判进 sub-level 时打一条诊断行，直接说明断在哪一环
     * （level 不是 SubLevelContainerHolder / plotContainer 为 null / 那个 chunk 没有 plot）。
     * 这是保险：万一投影又没生效，日志能自己说清原因，不用再猜。
     */
    private static void diagnosePlotMiss(Level level, double x, double z) {
        if (Math.abs(x) < PLOT_SCALE && Math.abs(z) < PLOT_SCALE) {
            return;
        }
        if (loggedPlotMiss) {
            return;
        }
        loggedPlotMiss = true;
        int chunkX = (int) Math.floor(x) >> 4;
        int chunkZ = (int) Math.floor(z) >> 4;
        StringBuilder message = new StringBuilder("plot 量级坐标 (%.1f, %.1f) 未判进 sub-level：level=%s"
                .formatted(x, z, level == null ? "null" : level.getClass().getName()));
        try {
            Class<?> holder = Class.forName(HOLDER_CLASS);
            message.append("，isSubLevelContainerHolder=").append(holder.isInstance(level));
            if (holder.isInstance(level)) {
                Object container = holder.getMethod(HOLDER_GETTER).invoke(level);
                message.append("，plotContainer=")
                        .append(container == null ? "null" : container.getClass().getSimpleName());
                if (container != null) {
                    Object plot = container.getClass().getMethod("getPlot", int.class, int.class)
                            .invoke(container, chunkX, chunkZ);
                    message.append("，getPlot(chunk ").append(chunkX).append(", ").append(chunkZ).append(")=")
                            .append(plot == null ? "null" : plot.getClass().getSimpleName());
                }
            }
        } catch (Throwable diagnostic) {
            message.append("，诊断本身失败：").append(diagnostic);
        }
        emit(message.toString());
    }

    /**
     * 仅供离线行为测试重置缓存（t4 的 SableGeometryCompatProbe 用；生产路径不会调用）。
     * 没有它就没法在同一个 JVM 里先后验证「无 Sable 退化」与「接上 Sable 投影」两条路径。
     */
    static void resetForTest() {
        resolved = false;
        loggedResolve = false;
        loggedFailure = false;
        loggedPlotMiss = false;
        projectionLogged = false;
        liveMoveLogged = false;
        failures = 0;
        lastFailure = null;
        helper = null;
        distanceMethod = null;
        containingMethod = null;
        projectMethod = null;
        PROJECTOR_POS.remove();
        PREVIOUS_PROJECTOR_POS.remove();
        NETWORK_LEVEL.remove();
        NETWORK_DEPTH.remove();
        SOURCE_LEVEL.remove();
        PREVIOUS_LEVEL.remove();
        SOURCE_DEPTH.remove();
    }

    /** 探针断言用：兼容层有没有"悄悄失败过"。正常路径应该永远是 0。 */
    static int failureCount() {
        return failures;
    }

    /** 探针断言用：最近一次失败的原文（必须能说明是谁读不出来/哪个调用失败）。 */
    static String lastFailure() {
        return lastFailure;
    }

    /** 探针断言用：投影仪坐标作用域是否已出栈（不能泄漏到下一次可见性判定）。 */
    static boolean hasProjectorPos() {
        return PROJECTOR_POS.get() != null;
    }

    /** 探针断言用：网络包作用域的深度（必须回到 0，不能泄漏到下一个包）。 */
    static int networkScopeDepth() {
        return NETWORK_DEPTH.get() == null ? 0 : NETWORK_DEPTH.get();
    }

    /** 探针断言用：音频/受众作用域是否有压栈（用于证明"弹网络作用域"不会把它一起弹掉）。 */
    static boolean hasSourceLevel() {
        // 刻意用**深度**而不是"Level != null"：作用域里压进去的 Level 本身可以是 null
        // （探针里就没有真的 Level 可用），用 null 判断会把"压了 null"和"没压"混为一谈。
        Integer depth = SOURCE_DEPTH.get();
        return depth != null && depth > 0;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        Object candidate = testHelper;
        Class<?> type;
        if (candidate != null) {
            type = candidate.getClass();
        } else {
            try {
                Class<?> sable = Class.forName(SABLE_CLASS);
                candidate = sable.getField(HELPER_FIELD).get(null);
                type = Class.forName(COMPANION_CLASS);
            } catch (Throwable absent) {
                emit("Sable 不在场（%s 读不到），几何判定使用普通欧氏距离".formatted(SABLE_CLASS));
                return;
            }
        }
        if (candidate == null) {
            emit("Sable.HELPER 为 null，几何判定使用普通欧氏距离");
            return;
        }

        helper = candidate;
        distanceMethod = findMethod(type, METHOD_DISTANCE, 7);
        containingMethod = findMethod(type, METHOD_CONTAINING, 3);
        // projectOutOfSubLevel(Level, Position|Vec3) → Vec3：按"名字 + 2 参 + 末参是对象"找，
        // 优先 (Level, Position) 那个重载（Vec3 也是 Position，两个都能用）
        projectMethod = findObjectMethod(type, METHOD_PROJECT, 2, ".Position");
        if (distanceMethod == null || containingMethod == null) {
            helper = null;
            distanceMethod = null;
            containingMethod = null;
            projectMethod = null;
            emit("Sable 的 API 形状不认识（%s / %s），几何判定使用普通欧氏距离"
                    .formatted(METHOD_DISTANCE, METHOD_CONTAINING));
            return;
        }
        if (!loggedResolve) {
            loggedResolve = true;
            emit("Sable 兼容层已启用：距离判定走 distanceSquaredWithSubLevels（sub-level 感知）"
                    + (projectMethod != null ? "，音源位置走 projectOutOfSubLevel" : "（projectOutOfSubLevel 不可用）"));
        }
    }

    /**
     * 按「名字 + 参数个数 + 除第一个参数外全是 double」找方法。
     * 刻意不解析参数类型（不加载 Level / JOML 类），只靠形状匹配：
     * {@code ActiveSableCompanion} 里 distanceSquaredWithSubLevels 有 5 个重载，
     * 唯一满足「7 参、后 6 个都是 double」的就是 {@code (Level,double×6)}；
     * getContaining 的「3 参、后 2 个都是 double」唯一对应 {@code (Level,double,double)}。
     */
    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        if (type == null) {
            return null;
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            boolean doubles = true;
            for (int i = 1; i < types.length; i++) {
                if (types[i] != double.class) {
                    doubles = false;
                    break;
                }
            }
            if (doubles) {
                return method;
            }
        }
        return null;
    }

    /**
     * 按「名字 + 参数个数 + 末参是对象类型」找方法，优先末参类型名以 {@code preferredSuffix} 结尾的那个。
     * 只比较类型**名字**，不加载那些类（离线探针里也能跑）。
     */
    private static Method findObjectMethod(Class<?> type, String name, int parameterCount, String preferredSuffix) {
        if (type == null) {
            return null;
        }
        Method fallback = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) {
                continue;
            }
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            Class<?>[] types = method.getParameterTypes();
            Class<?> last = types[types.length - 1];
            if (last.isPrimitive()) {
                continue;
            }
            if (last.getName().endsWith(preferredSuffix)) {
                return method;
            }
            if (fallback == null) {
                fallback = method;
            }
        }
        return fallback;
    }

    private static void emit(String message) {
        try {
            // 刻意用 slf4j 直连而不是 com.mojang.logging.LogUtils：
            // LogUtils 只在原版客户端 jar 里，构建类路径（srg + neoforge + 启动库）里没有它，
            // 用它会让这个类编译不过。slf4j-api 在启动库里，游戏内由加载器提供。
            org.slf4j.LoggerFactory.getLogger("net_music_can_play_bili/sable").info(message);
        } catch (Throwable ignored) {
            // 没有日志后端也不能影响主流程
        }
    }
}
