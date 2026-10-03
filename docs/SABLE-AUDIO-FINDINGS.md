# Sable 物理化结构上「现代化唱片机无声」的排查与修复

生成时间：2026-10-03（部署产物 `54B6288B…`，见 §13）。范围：**Create: Aeronautics / Sable 的物理化结构**上
NCPB 现代化唱片机的音频/视频/UI。本文自包含：症状、证据链、改动、门禁、已验/未验。

---

## 1. 症状与关键观察（用户提供）

| 观察 | 值 |
| --- | --- |
| 物理化**前**唱片机 F3 坐标 | `-26, -60, 26` |
| 物理化**后** F3 坐标 | **`20481032, 128, 20481032`**（Sable 的 plot chunk 坐标） |
| 起播后约 2 秒 | 音符粒子**继续刷**（说明声音实例活着） |
| 拖动结构（尤其快拖后急停） | NCPB 的粒子被**抛射**出去；NetMusic 普通唱片机的粒子不这样 |
| 对照 | NetMusic 自己的普通唱片机在**同一物理结构**上有声 |

## 2. 根因（两条 NCPB 自己的「主世界坐标」假设 × Sable 的坐标口径）

Sable 的要求（源码 `D:\work\mc\sable-main`）：

- `mixin/sublevel_sounds/SoundEngineMixin.java:34-39`：`getContaining(level, instance.getX(), instance.getZ())`
  命中才把声音包成 `MovingSoundInstanceDelegate`。
- `sound/MovingSoundInstanceDelegate.java:127-142`：`getX() = subLevel.logicalPose().transformPosition(instance.getX(), …)`
  ⇒ **被包住的声音，其 x/y/z 必须是 plot 坐标**。
- `ActiveSableCompanion.java:273-310`：`distanceSquaredWithSubLevels(...)` = 两边各自 `projectOutOfSubLevel(…)`（plot → 世界）后再算距离。
- `ActiveSableCompanion.java:67-79`：`getContaining(level, chunkX, chunkZ)` 就是「这个 chunk 属于哪个 plot」。

NCPB 原来的两处：

| # | 位置 | 原来的做法 | 物理化后的后果 |
| --- | --- | --- | --- |
| R1 | `AudioEndpointSubscriptionTracker.update`（`distanceSquared` 调用，偏移 160） | 普通 level 欧氏距离 ≤ 96+48 格 | 音源一跳进 plot（约 2000 万格）→ 永远判「太远」→ **不再给玩家发包**，客户端永远拿不到 plot 坐标 |
| R2 | `ClientAudioOutputRegistry.hasGeometricAudioDemand` / `hasPreparationDemand`（`AudioUtils.distance`） | 普通欧氏距离 + 64 格球 | 距离被算成 2000 万格 → `presentationEnvelope.gain(false)` → **音量 0** |
| R3 | `ModernTurntableSound.fixedSourceAvailable` | 主世界 `getBlockEntity(pos)` 找不到就用 `ClientAudioEndpointIndex` 兜底 | 兜底让这个「停在旧世界坐标、Sable 又没包」的声音继续 tick（粒子一直在刷），但音频源钉在物理化前的位置 → 听众在结构上听不到 |

三者叠加 = **「声音还在放，但放给了一个已经不存在的世界位置」**：粒子在旧坐标刷（结构一拖就被甩/抛射），音频在旧坐标响（结构上听不到）。
NetMusic 普通唱片机之所以没事：它是在**物理化之后**起播的，包带的是 plot 坐标 → Sable 包住 → plot→world 换算正确；粒子在 plot 里出生 → 被 `ParticleMixin` 跟踪 → 粘在结构上。

## 3. 改动（1 个软兼容层 + 4 个 Mixin）

| 文件 | 作用 |
| --- | --- |
| `compat/sable/SableGeometryCompat`（新增普通类） | 反射探测 `Sable.HELPER`（字符串类名 + 名字/参数形状匹配，**不引编译期依赖、不加载 Sable 的类**）；提供 `distanceSquared(level,…)` / `distance(level, float[], float[])` / `isInsideSubLevel(level,x,z)` / `pushSourceLevel·popSourceLevel`（作用域）；**没有 Sable 时静默退化为普通欧氏距离**，任何异常也只退不禁 |
| `mixin/AudioSubscriptionSubLevelDistanceMixin`（**common**） | `@Redirect` `AudioEndpointSubscriptionTracker.update(…SpatialSnapshot)` 里那次 `distanceSquared(DDDDDD)D` → 走兼容层（修 R1）。用**完整描述符**定位该方法（同类还有 `(…, List)` 重载，只写名字会往两个方法都注入而崩） |
| `mixin/AudienceSyncLevelScopeMixin`（**common**） | 在 `ModernTurntableAudienceSync.syncNearbyPlayers` / `syncNearbySpectators` 的 HEAD/RETURN 压栈/出栈音源 Level —— `update(...)` 自己没有 Level 参数，而判定 plot 距离必须有 Level |
| `mixin/ClientAudioDemandSubLevelDistanceMixin`（client） | `@Redirect` `hasGeometricAudioDemand`/`hasPreparationDemand` 里的 `AudioUtils.distance([F[F)F` → 兼容层（修 R2） |
| `mixin/ModernTurntableSoundSubLevelSourceMixin`（client） | `fixedSourceAvailable` RETURN 处兜底：该 pos 落在某个 sub-level 内即认为源可用（修 R3 的「切换瞬间被判死」） |

粒子和音频定位**不需要单独改**：坐标口径一正确，Sable 会自动跟踪粒子并包住声音。

## 4. 门禁与故障注入（每条都实测能说"不"）

`python t4/build_mixins.py --fault=<case>`，六个 case 全部退出码 1 且失败信息就是对应门禁：

| fault | 触发的门禁 |
| --- | --- |
| `at` | `@At` 目标串未写入 mixin class + 目标类字节码里没有该调用点 |
| `descriptor` | 目标类里不存在该参数描述符（handler 会不匹配而崩） |
| `musthave` | `SableGeometryCompat` 字节码里找不到反射目标字符串 |
| `register` | Mixin 未注册进 mixins.json（= 死代码，静默失效） |
| `jsonenv` | Mixin 注册进了**错的环境列表**（服务端逻辑混进 client → 专用服务器不生效） |
| `probe` | 离屏行为探针：假 helper 改成「不投影」后，投影断言必须失败 |

行为探针 `t4/test/.../SableGeometryCompatProbe.java`（`t4/build_mixins.py` 的 [2d] 步，部署前跑）覆盖五组：
无 Sable 退化 / 接上 Sable 后按 plot 投影 / 作用域路径 / helper 抛异常静默退化 / helper 形状不认识退化。

**故障注入实际抓到的两个真错**（都在这轮内修掉）：
1. 兼容层最初用 `com.mojang.logging.LogUtils`，而构建类路径里没有它 → 编译不过，六个 fault 全是假阳性。改用 slf4j 直连。
2. `jsonenv` 故障最初把「期望值」和「注册去向」一起改了 → 错环境注册的 jar 被**真的部署出去**。已把期望值与注册去向分离，并回滚了那次误部署。

## 5. 部署产物

| 项 | 值 |
| --- | --- |
| 部署产物 | `mods\net_music_can_play_bili-0.7.9-beta+neo1.21.jar` |
| SHA256（最终） | `1B773E29F716CF73D295A9B79F226B350797CA9300C0BA0D0B6F107102DD5F37` |
| 大小 / 条目 | 9,624,963 B / 1683 条（原来 1678，+5） |
| ⚠️ 已废弃的中间产物 | `FF36890B…`（第一次部署：`AudienceSyncLevelScopeMixin` 的 `@Inject` 只捕获 2 个参数 → **放唱片时崩服**，见 §7） |
| 回滚点（改动前、用户实测通过态） | `backup-jar\pre-mixins-deployed-20261003-005833.jar` = `919053AD…` |
| 独立审计 | `python t4/verify_sable_fix.py`（含 `--fault`）：deployed==out/==dist；**只新增 5 个 class、只改 mixins.json、既有 class 逐字节未动**；注册环境 2 common / 2 client 正确；反射目标齐全 |
| 游戏内证据行 | 日志出现 `[NCPB/Sable] Sable 兼容层已启用`（slf4j logger 名 `net_music_can_play_bili/sable`）即兼容层真的接上了；若 Sable 缺席会打印退化说明 |

## 6. 已验 / 未验

**已验（离线）**：编译；四条 Mixin 的注入目标在**部署产物**里都存在（描述符级）；注册环境正确；兼容层行为五组断言；产物逐条目审计；六条故障注入。

**未验（需要进游戏）**：
1. 物理化结构上起播 → 是否听得到；
2. 拖动/快拖急停 → 声音是否跟着、粒子是否不再被抛射；
3. 服务端是否在物理化后**持续发包**（应能看到 `播放管线关键时间` 与 `索引播放需求重启` 带 plot 坐标）；
4. Mixin 是否全部应用成功（启动不崩；日志无 `Mixin apply failed`）。

**同根因但本轮刻意未改**（留作后续，改动更大）：`AudioEndpointSubscriptionTracker.interestedEndpoints` / `Cell.mayReach`（喇叭中继 endpoint 的距离预筛）、`PlaybackApproachPredictor.willEnterSphere`（预需求预测）、视频投影仪/歌词时间线的 `sourcePos`、MP4/Pad 手持设备的 `ClientMediaPlayback.sourcePosition`。

---

## 7. 第一次部署的崩溃与修复（2026-10-03 01:00:42）

**症状**：往物理化的现代化唱片机里放唱片时崩服（`crash-reports\crash-2026-10-03_01.00.42-server.txt`，
`Description: Ticking block entity`）。

**根因（崩溃报告原文）**：
```
Caused by: InvalidInjectionException: Invalid descriptor on
  net_music_can_play_bili.mixins.json:AudienceSyncLevelScopeMixin -> @Inject::ncpb$pushSourceLevelForPlayers
  (Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/Level;Lorg/.../CallbackInfo;)V!
Expected (…13 个参数…Lorg/.../CallbackInfoReturnable;)V but found (…只 2 个参数…CallbackInfo;)V
```
Mixin 的 `@Inject` 处理器**只接受两种写法：捕获目标的全部参数，或一个都不捕获**（目标有返回值时
末尾必须是 `CallbackInfoReturnable`）。我当初为了省事只捕获了前两个参数（`ServerLevel`/`Level`）。

**为什么表现为"放唱片时崩"**：Mixin 是在**目标类第一次被加载**时才应用的。`AudioEndpointSubscriptionTracker`
恰好是玩家放唱片、服务端第一次做受众同步时才首次加载 —— 所以构建/启动都正常，走到那条路径才炸。

**修复**：`AudienceSyncLevelScopeMixin` 的 4 个处理器改成捕获全部 13 个参数 + `CallbackInfoReturnable<Set<UUID>>`。

**新增门禁（[2e]）**：构建期把每个 `@Inject`/`@Redirect` 处理器的**声明签名**与目标方法的
`javap` 签名逐参数比对（Inject：必须全参数或零参数、回调类型要对；Redirect：参数个数必须等于调用点），
并按 spec 申报的数量核对解析结果 —— **解析到 0 个也算失败**（防止 regex 漂移后变成空门禁）。
`--fault=handler` 会重现当初那个短签名，[2e] 必须报 FAIL。

**这一轮故障注入又抓到两个"假门禁"**（都已修）：
1. `@Inject` 的 regex 少了 `\s*`（源码是 `@Inject(\n method = …`）、`cancellable = true` 让结尾匹配不上；
2. `register` / `jsonenv` 两个故障在"基线 jar 里已经注册过"的情况下会退化成空操作
   （实测第二次跑 `--fault=register` 直接**部署成功**了）→ 现在故障会先把注册从 json 里摘掉，
   并强制把改过的 json 写回，故障才真的能证明门禁有效。

**最终验证**：正式构建（全门禁通过）→ `1B773E29…`；独立审计 `python t4/verify_sable_fix.py`：
deployed==out/==dist、+5 class / 0 移除 / 只有 mixins.json 变、两个服务端 Mixin 在 `mixins`、
两个客户端 Mixin 在 `client`、**部署产物里是全参数处理器签名且不存在崩溃时的短签名**；
审计自身的 `--fault` 与 `--fault=handler` 均能报 FAIL。

---

## 8. 崩溃修复后仍"听不到"：NCPB 的媒体音频不走 SoundEngine（2026-10-03 01:22 那次实测）

**现象**：不崩了，结构上仍然无声。日志证明前四个 Mixin 都生效了：

```
[NCPB/Sable] Sable 兼容层已启用：距离判定走 distanceSquaredWithSubLevels（sub-level 感知）
现代唱片机客户端接管播放: … pos=BlockPos{x=20481032, y=128, z=20481032}   ← 服务端开始发 plot 坐标了
播放管线关键时间: … audio=3702ms audioMain=3702ms …（音频在走）
Stereo OpenAL 预缓冲完成: 383 blocks, 开始播放                              ← NCPB 自己的 OpenAL 在放
```

**根因（第五个、也是真正让声音听得见的那个）**：NCPB 的媒体音频**不走 Minecraft 的
`SoundEngine`** —— 它自己开 OpenAL 源（`StereoOpenALHandler` / `OpenALSpatialAudio`）、自己算空间增益。
Sable 的 `SoundEngineMixin.sable$play` 只接管 Minecraft 自己的声音，因此**对这条路径完全无效**。
而 NCPB 唯一的「BlockPos → 世界坐标」入口是
`AudioUtils.centerFor(BlockPos)`（`{x+0.5, y+0.5, z+0.5}`），它被用在：
`ClientAudioOutputRegistry.register/registerStereo`（存进 `AudioEntry` 当音源位置）、
`audioDemandDebug`、`hasAudibleOutput`、`AudioUtils.distance` 的各处增益计算。
plot 坐标进去 → 距离约 2000 万格 → 增益 0（OpenAL 源也钉在 2000 万格外）→ 结构上无声。

**修复（第 5 个 Mixin）**：`AudioUtilsSubLevelPositionMixin` 在 `AudioUtils.centerFor` 的 HEAD 处
`cancellable` 注入，只有「该 BlockPos 落在某个 sub-level 里」时才把返回值换成
`SableGeometryCompat.projectToWorld(...)`（`projectOutOfSubLevel` 的 plot→世界投影），
其余情况原实现照常返回 —— 地面/普通场景行为不变。这一个入口换掉，位置、距离、增益一起变正确。

**顺带验证了参考实现**：`D:\work\mc\aeronauticscompat-1.1.3.jar`（第三方 Sable 兼容 mod）的做法与本次一致：
`SableBridge` 也是「反射 Sable + `distanceSquaredWithSubLevels` + `getContaining` + `isAvailable()`」，
`WaterFramesSableDistanceMixin` 打的也是**距离**函数；它另有 `DeferredSoundQueue`，提示
「装配当帧可能还查不到 sub-level」这个时序坑（本轮未遇到，留作备用线索）。

**这一轮又抓到三个管线 bug（都是我的门禁自己抓的，不是我"看"出来的）**：
1. 编译我们自己的源码时类路径写成 `-cp CP;mout`，而 `CP` 第一项是**部署中的 jar**（里面已有上一版同名类）
   → javac 拿旧副本解析，报出莫名其妙的"找不到符号 `centerFor`"。已改为 `mout;CP`。
2. 离线行为探针里 `new BlockPos(...)`/`new Vec3(...)` 的静态初始化把 fastutil、DataFixerUpper 一串拖进来
   → 探针在纯类路径下 `NoClassDefFoundError`。已改为：兼容层自己实现 `Position`（record `Point`）、
   返回值用反射读 `getX/getY/getZ`，探针只用 double 进出，**完全不碰 MC 类型**（fastutil 仍留在构建类路径里备用）。
3. 内部类被硬编码注册到 `ncpbmodel/` 前缀下（`PLAIN_PKG`），导致 `SableGeometryCompat$Point.class`
   进了错路径。构建期的「引用自洽」门禁直接报出
   `class 文件引用了但 jar 里不存在的本模组类: [.../SableGeometryCompat$Point.class]`（= NoClassDefFoundError 的前身）。
4. jar 是"先写盘、后过门禁"：一次 `--fault=jsonenv` 之后部署是好的，但 `dist/mixins.jar` 里留的是
   **带错注册的那份**，于是"三方产物一致性"检查被它误导报错。已改成先写 `dist/mixins.jar.part`，
   全部门禁通过后才 `os.replace` 成正式产物 —— `dist` 永远只保留"最后一次全绿"的那份（已实测：故障跑动后 dist 哈希不变）。

**最终产物**：`B6896CE6A03FF4779EFD868B2680127891162E870CB6DC26B8EEBE7139A7EF90`，
9,628,216 B / **1685 条**（+7：5 Mixin + 兼容层 + 内部类）。
独立审计：新增条目恰好 7 个、0 移除、既有条目只有 `mixins.json` 变、注册环境正确、
兼容层含 `projectOutOfSubLevel` 等全部反射目标、`AudienceSyncLevelScopeMixin` 是全参数签名且无短签名。
7 条构建故障 + 2 条审计故障全部 exit=1。

**回滚点**：`backup-jar\pre-mixins-deployed-20261003-005833.jar` = `919053AD…`（本轮所有改动之前）；
`backup-jar\pre-mixins-deployed-20261003-013728.jar` = `1B773E29…`（修好崩溃、但仍无声那一版）。

---

## 9. 「仍无声 + 结构上视频投影仪卡在加载界面」：同一个坐标系错配的第二条链（2026-10-03 02:23）

### 9.1 先纠正上一轮的一个结论：第 5 个 Mixin 其实**从未生效**

`debug.log` 里有 `Mixing AudioUtilsSubLevelPositionMixin … into …media.audio.AudioUtils`（Mixin 确实应用了），
但整份日志里**没有任何** `音源位置投影生效`。查下去是 `readCoordinates` 的 bug：

```java
public class net.minecraft.world.phys.Vec3 implements net.minecraft.core.Position {
   public final double x();  y();  z();        // ← 只有这三个，没有 getX()/getY()/getZ()
}
```

原先只反射找 `getX/getY/getZ` → `NoSuchMethodException` 被 `catch (Throwable) { return null; }` 吞掉
→ `projectToWorld` 永远返回 null → `centerFor` 的替换永远不发生。**离线探针当时也抓不住**，因为探针的
假对象是照着同一个错误假设写的（自带 `getX/getY/getZ`）——「检查跟着错误假设一起通过」的标本。

### 9.2 你报的第二个症状把结论钉死了

「结构上视频播放器卡在加载界面」是**另一条完全独立的链**（客户端视频可见性），与音频链唯一的共同点是
「拿 plot 坐标当世界坐标比距离」：

```
VideoBillboardQuadSupport.computeProjectorScreenRenderable            // :403
  └ isProjectorWithinRenderDistance(cameraPos, projector, cx, cy, cz, aspect)   // :416
      AABB bounds = ProjectorScreenBounds.aroundCenter(cx.., 投影仪 plot 坐标)   // :419
      ProjectorScreenBounds.distanceToSqr(bounds, camera.getPosition()) <= 64²   // :422
```
摄像机在世界坐标、投影仪在 plot（`20481033,129,20481032`）→ 距离约 2000 万格 → 永远判「看不见」
→ `VideoPlaybackPresentation.markVisibility(false, …)`（`:166`）永不调用 `grantDecodeAdmission()`
→ `VideoCandidateDecodeRunner` 根本不启动 → 屏幕上一直只有 `VideoPlaceholderFrames.Kind.LOADING`。
日志佐证：结构会话只有 `视频会话创建`，**没有** `VideoNativeDecoder 已打开`，时间线全程 `video=n/a`。

**顺带排除一个错误假设**：我上一轮以为视频的门是 `TurntableVideoPlaybackAnchor.isWithinAudioRange` /
`LiveVideoPlaybackAnchor.isWithinAudioRange` 那两处距离比较 —— 查调用方才发现整棵树里**没有任何调用方**
（`decomp/` 完整性已核对：1432 个 class 里只有我们自己注入的 14 个顶层类没有 `.java`），是死代码。

**「摄像机在世界坐标」的独立证据**：Sable 的 `MovingSoundInstanceDelegate.getX()` 会把 plot 坐标
`logicalPose().transformPosition(…)` 成世界坐标再交给 MC 的 `SoundEngine`，而 SoundEngine 的衰减是跟
摄像机比的 —— NetMusic 普通唱片机在**同一结构**上有声，只有「听者在世界坐标」这一种解释能同时成立。

### 9.3 本轮改动

| # | 改动 | 作用 |
| --- | --- | --- |
| 1 | `readCoordinates` 主路径改成 `value instanceof Position → x()/y()/z()`（`Vec3 implements Position` 是**编译期**保证），保留 `x()`/`getX()`/公有字段三条反射兜底 | 修掉 9.1 的静默失效；这类错再也无法静默复发 |
| 2 | 读不出返回值 → **记账 + 一次性日志**（`failureCount`/`lastFailure`），且**不再连能力一起禁用** | 原来 `degrade()` 会把 `helper` 清空：一次读失败 = 整套 Sable 能力永久失效 |
| 3 | 日志分槽（解析 / 失败 / 投影 / plot 未命中各一条一次性） | 原来 `log()` 是全局一次性，"已启用"那条会把后面所有失败原因**吃掉** |
| 4 | 新增诊断行：plot 量级坐标却没判进 sub-level 时，打印 `level 类型 / 是否 SubLevelContainerHolder / plotContainer / getPlot(chunk)` | 万一投影又没生效，日志自己说清断在哪一环，不用再猜（保险） |
| 5 | 新 Mixin `VideoProjectorVisibilityDistanceMixin`：`isProjectorWithinRenderDistance` HEAD/RETURN 压栈投影仪坐标，`@Redirect` 把那次距离比较换成 sub-level 感知（投影仪中心 ↔ 摄像机） | 修 9.2；阈值与公式仍由原方法算，**地面场景走原实现，逐字节不变** |
| 6 | 构建门禁 `[2f]`：javap 真 `Vec3` 证明 `implements Position` + `x()`，并要求兼容层**字节码**里真的调用 `Position.x()`；`--fault=coordshape` 复现旧写法 | 这条门禁在离线就能抓住 9.1 —— 探针抓不住的那一类 |
| 7 | 探针补三组返回值形状（真实 `Position` 形状 / `getX` 形状 / 公有字段形状）+ 「读不出来必须留痕」+ 视频可见性作用域 | 假对象必须与真 `Vec3` **同形**，否则检查无效 |

### 9.4 已验（离线）

| 项 | 结果 |
| --- | --- |
| 构建 | exit 0，全部门禁通过；产物 `27633B02B8A5558F76B631428895F975C6CBB262B4747DE0531EBE9CB319FBC1`，9,632,438 B / **1686 条**（+1 新 Mixin；兼容层是替换） |
| 行为探针 | **34 项断言，0 失败**（`[d2]` 步，附 `检查项 34，失败 0` 进构建日志） |
| `[2f]` | 两条 javap 实证 + 「字节码确实调用 `Position.x()`」 |
| `[2e]` | 新 Mixin 的 3 个处理器签名与目标逐参数一致（2 个 `@Inject` 全捕获 + `CallbackInfoReturnable`，1 个 `@Redirect`） |
| 独立审计 | exit 0：vs 基线新增恰好 **8** 条、只改 `mixins.json`、注册环境（2 common / 4 client）正确、反射目标齐全、`[7]` 部署级复核 `Position.x()` |
| 故障注入 | 8 条构建 + 3 条审计**全部 exit=1，且逐条核对失败理由来自对应门禁**（见 `t4/dist/fault-*.log`、`auditfault-*.log`）；故障跑动后部署产物与 `dist` 哈希未变 |

**故障注入又抓到我自己两个假阳性（如实记录）**：

1. `--fault=coordshape` 第一次以 exit=1 "通过"，但真实原因是**临时源码文件名与 public 类名不一致**，
   javac 直接报错 —— `[2f]` 根本没跑到。已改为按类名命名，并加**故障自检**：
   「降级后的字节码里必须真的没有 `Position.x()`，否则这次 FAIL 不算证据」。
2. 探针只打印最后 N 行，断言变多后日志里只剩一句 `PASS`（SLF4J 噪声把统计挤掉了）。
   已改为打印全部 `[FAIL]` + `检查项 N，失败 M` + `RESULT`。

### 9.5 未验（需要进游戏）与已知取舍

**未验**：
1. 结构上是否**听得到**；
2. 结构上视频是否**出画面**（不再停在加载占位图）；
3. 日志是否出现 `[NCPB/Sable] 音源位置投影生效：plot 坐标 … → 世界坐标 …`（**这一条是本次的关键判据**：
   它同时证明「坐标读取」「getContaining 命中」「投影数值」三件事都对了）；
4. **地面场景不回归**（两个症状在普通场景都应保持原样）；
5. `VideoProjectorRenderer.shouldRender`（`:185`，另一处 64 格比较）拿到的 `cameraPos` 是不是**已经**是
   plot 空间 —— 若是，那处本来就对，**不能**再加一次投影（会重复变换）。如果视频修好后仍不出画面，
   这是下一个要看的地方。

**已知取舍**：视频那处把「到屏幕 AABB 的距离」换成「到投影仪中心的距离」（阈值仍是原来的 64²）。
原因是 `@Redirect` 的处理器拿不到屏幕 AABB 的形状，而「这个 chunk 属于哪个 sub-level」只能从投影仪
**自身**的坐标问出来（投影仪在结构上会被渲染出来，这一点本身证明 `getContaining(level, 它的 plot 坐标)`
非空；屏幕中心的坐标没有这个保证，我们这台的投影仪恰好坐在 plot cell 的第一个 chunk 上）。
在 64 格阈值附近因此有小于屏幕尺寸的差异；结构上玩家就在旁边（几格），不受影响。

**证据文件**：`t4/dist/build-last.log`（全绿构建全量输出）、`t4/dist/fault-*.log`、`t4/dist/audit-ok.log`、`t4/dist/auditfault-*.log`。
**回滚点**：`backup-jar\pre-mixins-deployed-20261003-021505.jar` = `D67A049C…`（本轮之前、1685 条那份）；
本轮所有改动之前的真基线仍是 `backup-jar\pre-mixins-deployed-20261003-005833.jar` = `919053AD…`。

---

## 10. 「功能对了，但画面一出来就特别卡」：混合坐标系的遮挡射线（2026-10-03 02:36）

### 10.1 症状与日志证据

用户实测：功能已对（结构上有声、视频出画面），但**一旦画面出来就特别卡**。日志里两件事：

| 观察 | 日志 |
| --- | --- |
| 视频只以约 **0.23× 实时**推进 | `video=56667ms` →（3.5s 后）`video=57467ms`，而 `expectedVideo` 前进 3500ms |
| 每约 10 秒触发一次重定位 | `视频运行期持续落后，主动重定位: lag=8803ms / 7415ms / 7791ms, master=…, video=…` |
| 每次重定位都重开解码器 | `VideoNativeDecoder 已打开` 于 02:25:47 / 02:25:59 / 02:26:09 / 02:26:23，线程名 `…-resume` |

关键判读：`videoQueued - video ≈ 200ms`（队列被吃空）⇒ **视频落后是结果不是原因**：帧的产出/上传
跟不上，也就是**游戏自身的帧率被拖垮了**。所以"卡"不是视频解码开销，而是每帧某个东西变慢了。

### 10.2 根因：可见性通过之后那条逐格 DDA

```
VideoBillboardQuadSupport.computeProjectorScreenRenderable
  → isProjectorWithinRenderDistance(...)        ← 第 9 节已修（距离口径）
  → for (sample : projectorVisibilitySamples(...)) {
        isScreenInView(camera, sample…) && !isOccluded(minecraft, cameraPos, sample, pos)
    }
  → VideoScreenOcclusion.isOccluded(level, cameraPos, target, projectorPos)
        → BlockGetter.traverseBlocks(cameraPos /* 世界坐标 */, target /* plot 坐标 */, …)   ← 逐格 DDA
```

`traverseBlocks` 是**逐格行走**的 DDA：两点相距约 2×10⁷ 格 ⇒ **每次约 4×10⁷ 个格子**；采样点 5 个；
而遮挡结果只缓存 `ncpb.video.render.occlusion_cache_ms`（默认 **150ms**），并且
`ncpb.video.render.view_dot_threshold` 默认 **0.12**（≈±83°，所以这一步几乎总会往下走）、
`ncpb.video.render.occlusion_check` 默认 **true** ⇒ 渲染线程基本全耗在这条射线上。

**为什么以前不卡、且只有结构卡**：这条 DDA 在距离判定为假时**根本走不到** —— 第 9 节修复之前，
结构上的投影仪永远判"太远"（视频压根不启动）。而地面上同一段代码的射线只有**几格**，一直很便宜。
一句话：**这是被我上一轮修复"解锁"的既有隐患**，同源——都是"世界坐标与 plot 坐标混用"。

### 10.3 修复

新增 `mixin/VideoScreenOcclusionSubLevelMixin`（client），`@Redirect` 这一次 `traverseBlocks` 调用，
只换**目标点口径**：

| 情形 | 处理 |
| --- | --- |
| 目标在世界坐标（地面） | 原样调用 ⇒ 行为与改动前逐字节一致 |
| 目标在 sub-level（plot） | 换成它的**世界坐标**再射：结构在世界里就长在眼前几格，射线自然短 |
| plot 量级却拿不到世界坐标 | **直接不射**，判"没被遮挡"（fail-open）——硬兜底，绝不让 20M 格 DDA 再发生 |

兜底逻辑放在兼容层 `SableGeometryCompat.occlusionRayTarget(...)`（三态：`null`＝原样／长度 3＝用这个世界坐标／
**长度 0＝别射**），因此**离线探针可以断言它**，而且这条兜底**独立于投影是否成功**（探针专门用
`ThrowingHelper` + `BrokenHelper` + 无 Sable 三种情况分别断言"必须跳过"）。

**取舍（如实记录）**：结构投影仪的遮挡判定改为对着**世界**里的方块射，所以"被结构自身墙体挡住"
不会被识别（视频会透过结构自己的墙画出来）。这等于回到本轮之前的行为（那时根本没有遮挡判定），
代价远小于渲染线程卡死。

### 10.4 本轮又抓到两个"我自己的错"

1. **Javadoc 里嵌套 `/* … */`**（在块注释里写行内注释）把注释提前闭合，javac 报出一串语法错
   —— 构建中止、**未部署**（先写 `.part` 后落盘的设计在这里救了一次）。
2. **`[2e]` 门禁自己错了**：它用朴素 `split(',')` 切处理器参数，不认泛型里的逗号，
   把 `BiFunction<Object, BlockPos, Object>` 抄成 3 个参数 ⇒ 误报"捕获 8 个参数，被调用点有 5 个"。
   已改成**按泛型深度切逗号**。门禁报错但错在门禁自己，这类必须如实登记，否则下次会为了"让它变绿"去改代码。

### 10.5 已验（离线）

| 项 | 结果 |
| --- | --- |
| 构建 | exit 0；产物 `B781727EB352143602531C423EA15DCC30EF20F3075AB0A722BF24A2E487343B`，9,634,081 B / **1687 条**（+1 新 Mixin） |
| 行为探针 | **42 项断言，0 失败**（新增遮挡射线三态 + "拿不到世界坐标必须跳过" ×3 种 helper） |
| `[2e]` | 9 个 `@Inject` + 4 个 `@Redirect` 处理器签名/参数个数全部与目标一致（含新 Mixin 的 5 参数重定向） |
| 独立审计 | exit 0：新增恰好 **9** 条、只改 `mixins.json`、注册环境（2 common / 5 client）正确、`[7]` 部署级复核 `Position.x()` |
| 故障注入 | 8 条构建 + 3 条审计**全部 exit=1 且理由对应**；故障跑动后部署产物与 `dist` 哈希未变 |

### 10.6 未验（需要进游戏）与遗留

**未验**：① 帧率是否恢复（"特别卡"是否消失）；② 视频是否不再每约 10 秒重定位一次；
③ 地面场景是否仍正常（不应回归）。

**遗留（已定位、本轮刻意未改）**：`isScreenInView` 判的是"摄像机 → **plot 采样点**"的方向，
而 plot 采样点相对摄像机永远在一个**固定的对角线**方向上（与结构在画面里的实际方向无关）；
只是阈值 0.12 很宽松（±83°）所以绝大多数朝向都能通过。严格做法是把采样点投影到世界后再判方向
（那样"背对屏幕"才会正确地不渲染）。影响：转身到某些角度时，可见性判定与"是否真的看得见"不一致，
可能触发一次"可见性恢复重定位"（约 1 秒的顿卡）。**若你实测到"转身再转回来会顿一下"，那就是它。**

**证据文件**：`t4/dist/build-last.log`、`t4/dist/audit-ok.log`、`t4/dist/fault-*.log`、`t4/dist/auditfault-*.log`。
**回滚点**：`backup-jar\pre-mixins-deployed-20261003-023513.jar` = `27633B02…`（上一轮：功能对但仍卡）；
更早的 `backup-jar\pre-mixins-deployed-20261003-021505.jar` = `D67A049C…`（1685 条，结构上无声那一版）。

---

## 11. 「视角移开后回到加载界面」：一半是我的遗留，一半是 NCPB 自己的离屏策略（2026-10-03 11:08）

用户实测：帧率恢复 ✓、不再每 10 秒重定位 ✓，**但视角移开后又会进入投影加载界面**。日志把这件事拆成两半。

### 11.1 属于我的遗留：在视判定用的是"固定 plot 对角线"方向

`computeProjectorScreenRenderable` 里逐采样点判"是否在视野内"用的是
`isScreenInView(camera, 采样点)` = 摄像机朝向 ·（摄像机 → 采样点）。结构上采样点是 **plot 坐标**，
于是这个方向永远是 plot 网格里那条**固定对角线**，与屏幕在画面里的真实方向无关：
* 仍然看着屏幕时可能被判"看不见" → 视频停画 + 开始累计离屏时间；
* 真背对屏幕时可能仍被判"看得见" → 白解码。

**修复**：`VideoProjectorVisibilityDistanceMixin` 增加第二个 `@Redirect`，把采样点先
`projectOutOfSubLevel` 成**世界坐标**再判方向（结构在世界里就画在眼前几格，方向才与画面一致）。
地面场景投影返回 null ⇒ 用原采样点、按与原方法**逐表达式相同**的算式计算 ⇒ 结果不变。
（照抄 6 行算式而不用 `@Shadow`：`@Shadow` 绑错只在运行期暴露，本项目为这类错误付过一次崩溃的代价；
用 `at_targets` 在构建期钉住这个调用点，改名/删除就直接构建失败。）

### 11.2 剩下那一半：NCPB 自己的离屏策略（与地面同源，**不是**兼容 bug）

```
ncpb.video.offscreen.pause_decode          = true     ← 离屏后暂停解码
ncpb.video.offscreen.grace_ms              = 500      ← 离屏超过 500ms 才暂停
ncpb.video.offscreen.resume_restart_lag_ms = 1500     ← 恢复时落后 ≥1500ms 就重定位
```
于是：离屏 > 0.5s → 解码暂停 → **视频时钟冻结**（主时钟继续走）→ 转回来时落后 = 离屏时长
→ 若 ≥1.5s 就 `restartDecoder`（seek 到主时钟位置）→ 首帧到达前画 `LOADING` 占位图（约 1 秒）。

日志实证（正是用户那两次）：
```
视频会话离屏恢复重定位: session=…-19474, offscreen=1667ms, master=17511ms, video=15933ms   → 落后 1578ms ≥1500 → 重定位
视频会话离屏恢复重定位: session=…-19474, offscreen=3196ms, master=46631ms, video=43533ms   → 落后 3098ms → 重定位
```

**这段策略没有任何坐标依赖 ⇒ 地面投影仪"移开视线再回来"的行为完全一样。** 我没有替用户改它，
因为它属于 mod 作者的取舍（省下"没人看时的解码开销"）；要改只需设它们自己的系统属性，
不需要动代码：`ncpb.video.offscreen.resume_restart_lag_ms`（例如 5000 = 5 秒内的移开不重定位）、
`ncpb.video.offscreen.pause_decode=false`（离屏也继续解码，代价是持续开销）、
`ncpb.video.offscreen.grace_ms`。**本轮只修 11.1，即"仍在看却被判看不见"那部分。**

### 11.3 又一个我自己的假阳性（第三个）

`@Shadow` 门禁用 `'@Shadow' in line` **全文匹配**，于是我在 javadoc 里写的
`{@code @Shadow}` 被当成注解行 → 误报"缺少 remap = false"，构建中止（未部署 ✓）。
已改为只认**行首注解**（`^\s*@Shadow\b`），并新增 `--fault=shadow`：
把真实的 `@Shadow(remap = false)` 改成 `@Shadow`，门禁必须报 FAIL —— 证明它仍会说"不"。

### 11.4 已验（离线）

| 项 | 结果 |
| --- | --- |
| 构建 | exit 0；产物 `86778BF151601C60BDC5A7E4ADF8739258F547F925FA96C9ED4C21D9E052CE43`，9,634,788 B / **1687 条**（本轮只替换 class，无新增条目） |
| 行为探针 | 42 项断言 0 失败 |
| `[2e]` | 9 个 `@Inject` + **5** 个 `@Redirect` 处理器签名/参数个数全部与目标一致 |
| 注入点 | 新调用点 `isScreenInView:(Lnet/minecraft/client/Camera;DDDD)Z` 在目标字节码里存在（同类静态调用按短形式核） |
| 独立审计 | exit 0：新增仍恰好 9 条、只改 `mixins.json`、注册环境正确、`[7]` 复核 `Position.x()` |
| 故障注入 | **9 条构建**（新增 `shadow`）+ 3 条审计全部 exit=1 且理由对应；故障跑动后产物哈希未变 |

### 11.5 未验与遗留

**未验**：① 仍在看屏幕时不再被判"看不见"（因此不再无端进加载界面）；② "移开 ≥1.5s 再回来"仍有约 1 秒
加载图（= 11.2 的设计行为，需要用户决定是否调属性）；③ 地面不回归。

**遗留（已定位、未改）**：`isProjectorScreenPredictedVisible` → `VideoVisibilityTrendPredictor.shouldPrewarm`
的 AABB 用 plot 坐标算 ⇒ `distanceToAabb ≈ 2000 万 > maxRenderDistance(64)` ⇒ **结构投影仪的
"预暖"恒为 false**（地面可以为 true）。影响很小：它只在"玩家正持续转向屏幕"的那几帧把视频从
离屏暂停里救回来；真正让视频保持前进的是 11.2 的 grace，所以修不修都改变不了"移开 ≥1.5s 就重定位"。
若要修，得把 AABB 投影到世界再喂给预测器（该方法是包私有的 18 参静态调用，重定向处理器参数很多，
风险与收益不成比例，故留作记录）。

---

## 12. 「UI 能打开但按钮点了没反应」：服务端 8 格守卫（8 处，一次修完）（2026-10-03 11:28）

### 12.1 症状与根因（R8）

用户实测：进入物理化唱片机的 UI 后点不动按钮，**而左键破坏 / 右键取出放入唱片正常**。

这个组合把范围缩得很小：UI 能开（客户端 `setScreen`，没有位置校验）⇒ 客户端把控制包发出去了 ⇒
**服务端收下后静默丢弃**。找到那处丢弃：

```java
// network/ModernTurntableControlPacket.java:41-44
if (context.player() instanceof ServerPlayer player && player.level() instanceof ServerLevel level) {
   if (!(player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0)) {   // ← 8 格守卫
      if (level.getBlockEntity(payload.pos()) instanceof ModernTurntableBlockEntity turntable) { ... }
```

`payload.pos()` 是**plot** 坐标（`20481032,129,20481032`），`player.position()` 是**世界**坐标
（`-28.5,-58.5,24.2`）⇒ 距离² ≈ 8×10¹⁴ > 64 ⇒ 条件为假 ⇒ **整个包被丢掉，而且一声不响**。
破坏 / 取出放入走的是方块实体自己的路径（服务端直接用命中坐标调 BE），没有这道守卫 ⇒ 正常。

**这是 R1/R2 的同族**：拿 plot 坐标当世界坐标做距离比较。

### 12.2 关键决定：不只修用户碰到的那一处

同一形状的守卫在 jar 里**共 8 处**（javap 逐类核实：每类 `handle` 里恰好 1 处）：

| 类 | 影响的方块 |
| --- | --- |
| `ModernTurntableControlPacket` | 现代化唱片机（用户碰到的） |
| `VideoProjectorConfigPacket` | 视频投影仪 |
| `LyricProjectorConfigPacket` | 歌词投影仪 |
| `SpeakerConfigPacket` | 音箱 |
| `LiveStreamerControlPacket` | 直播机 |
| `ControlConsoleAccessPacket` / `ControlConsoleConfigPacket` / `ControlConsoleEditLeasePacket` | 控制台 ×3 |

只修一处，下一个把投影仪/音箱/控制台放上结构的人会再报一次同样的 bug。所以**8 处一起修**。

**改法**（8 份 Mixin，env=`common`：这是服务端逻辑，专用服务器上也要生效）：
* `@Inject(HEAD)` 压入该包的 Level、`@Inject(RETURN)` 弹出 —— 用**专用作用域**
  `pushNetworkScope/popNetworkScope`（带深度计数），刻意与音频那条 `SOURCE_LEVEL` 分开，
  避免"误弹出别人的压栈"；
* `@Redirect` 把这次 `Vec3.distanceToSqr` 换成 `distanceSquaredInNetworkScope(...)`
  （比较的两点各自 plot→世界）。阈值 64.0 与其余逻辑仍是原代码；
* 地面场景 `getContaining` 返回 null ⇒ 退化为普通欧氏距离，行为不变。

8 份源码由 `t4/gen_network_scope_mixins.py` 生成（逐字一致，只有类名/import 不同）：
手抄 8 份最容易出的错是"某一份少写一句/写错类名"，而那只有进游戏才会暴露。

### 12.3 新增门禁 `[2g]`：守卫普查（把"我要记得改几处"变成"机器说了算"）

```python
# 扫描 jar 里 com/.../network/ 下所有类；凡是同时出现 Vec3.atCenterOf 与 Vec3.distanceToSqr 的，
# 都必须在 MIXINS 里有对应名字，否则构建失败。
```
实测：`[2g] jar 里 8 处守卫全部有对应 Mixin（…8 个类名…）`；`--fault=guard` 故意漏掉
`SpeakerConfigPacket` 那一份 ⇒ `[2g]` 报 FAIL（失败信息直接点名那个类）✓。

### 12.4 我自己的第 4 个假阳性（这次是 `[2e]` 的模型不对）

`[2e]` 原来按"目标描述符的**实参个数**"核对 `@Redirect` 处理器的参数个数。
但 **`Vec3.distanceToSqr` 是实例方法**：Mixin 的处理器必须多接一个**接收者**
（`list.add(x)` 的处理器是 `(List list, Object x)`），于是 8 个新重定向全被误判成"多写了一个参数"。
已改为从目标类的 `javap -c` 读出那次调用点的 opcode：`invokevirtual`/`invokeinterface` ⇒ +1。
（现在输出会写明"实例调用 1 实参 + 接收者"，以后一眼能看出来。）

**这一类"门禁自己错"本次共 4 个**（§8.1 / §9.4 / §10.4 / §11.3 / 本节），共同点是
**都是响亮地错**（构建中止、绝不部署），所以从没有把坏产物放出去 —— 这正是门禁该有的失败方式。

### 12.5 教训：这次没有异常、没有报错

服务端是 `if (守卫失败) { 什么都不做 }` —— 日志里一行都不会有。所以这一轮**不能靠日志**，
只能读代码找守卫。**"没有报错"不等于"没发生"**；而对"UI 点了没反应"这种症状，
正确的第一步是先把范围缩到"客户端发没发 / 服务端收没收到"这一层（本例：UI 能开 ⇒ 客户端发了 ⇒
服务端丢了 ⇒ 去找服务端的守卫），而不是从 UI 的渲染层找。

### 12.6 已验（离线）

| 项 | 结果 |
| --- | --- |
| 构建 | exit 0；产物 `30BE526452C61E1E313BF8A4AFC2865D6237189111A91D3853F9C5B197CD9327`，9,646,060 B / **1695 条**（+8 Mixin） |
| 行为探针 | **50 项断言 0 失败**（新增网络作用域：深度计数、多余弹出是无操作、**不会弹掉音频作用域**） |
| `[2g]` | 8 处守卫全部有对应 Mixin；`--fault=guard` 会报 FAIL |
| `[2e]` | 25 个 `@Inject` + 13 个 `@Redirect` 全部与目标一致（含 8 个"实例调用 + 接收者"） |
| 独立审计 | exit 0：新增恰好 17 条、只改 `mixins.json`（mixins 12 / client 24）、`[7]` 复核 `Position.x()` |
| 故障注入 | **10 条构建** + 3 条审计全部 exit=1 且理由对应；故障跑动后产物哈希未变 |

### 12.7 未验与同族遗留

**未验（进游戏）**：8 个方块的 UI 是否都能点；地面不回归。
另：UI 里"播放/暂停"按钮还依赖客户端 `ModernTurntableScreen.turntable()` 能找到方块实体
（结构上 `pos` 是 plot 坐标、客户端 BE 也在 plot ⇒ 应当没问题），若个别按钮仍无效，下一步看这里。

**同族但本轮未改（客户端侧，属"全息眼镜 / 控制台"那套，不是唱片机）**：
* `gui/HolographicEditorLifecycleScreen.java:284`
* `client/ControlConsoleRoamingSession.java:439` 与 `:460`
这 3 处也是"玩家(世界) ↔ 方块(plot)"的距离比较（`distanceToSqr(...) > 64.0`）。
`[2g]` 目前只普查 `network/**`（服务端包）；若要覆盖客户端，需要再加一条同形状的普查规则
（建议做成同一套"扫描 + 必须被覆盖"的形式，而不是靠人记）。

---

## 13. 「声音源钉死在世界绝对坐标、不跟着结构走」：注册时算一次的世界位置（2026-10-03 18:18）

### 13.1 症状（用户报告）

> 在物理结构上播放时，声音源是直接写死设定在世界的绝对坐标，而不会随着物理结构移动。

用户的假设是"代码里硬编码了一个常量"。**结论：不是**。没有任何魔数——是"注册那一刻算一次，
之后每帧复用同一个数组"，所以表现出来就像被钉死。

### 13.2 根因（不是硬编码，是"只算一次"）

```
播放开始 → DolbyEc3Pipeline:61 / AacOpenALPipeline:49 / FlacOpenALPipeline:56
           → ClientAudioOutputRegistry.register(handler, plotPos, …)
           → new AudioEntry(key, centerFor(plotPos), …)      ← 唯一的投影，只做这一次
每帧     → ClientMediaLifecycleHandler.updateListener（RenderFrameEvent.Pre + ClientTickEvent.Post）
           → ClientAudioOutputRegistry.updatePositions(listenerPos)
           → resolveMachinePos(entry) → resolveMachinePos(pos, machinePos, ownerId)
           → return originalPos;                             ← 之后一直复用同一个 float[]
           → entry.output().tick(pos, listenerPos, …)
```

`machinePos` 是 `AudioEntry`（record）里的 `float[]`，`register()` 里由 `centerFor(plotPos)`
算出来存进去（`ClientAudioOutputRegistry.java:71-80` / stereo `:121-130`）。
**这次投影本身是对的**（§9 修的就是它），但只有一次。

那份世界坐标喂进 `DolbyAudioHandler.updateSpatialState`（`:906-946`）/ `StereoOpenALHandler`
（`:263-281`）后，被用来算三件互相独立的事：

| 用途 | 代码 |
| --- | --- |
| 声场朝向 | `Math.atan2(mp[0]-lp[0], mp[2]-lp[2])` + `forwardToMachine(mp, lp)` + front 平滑器 |
| 空间增益 | `distance(lp, mp)` → `spatialGainForDistance` |
| OpenAL 源/听者姿态 | `sa.updatePositions(bedPositions, objectPositions, lp, forward)` |

地面上方块不动，"算一次"没有问题；物理化结构会整体平移/旋转，于是这三件事**全部**按
"播放开始瞬间的位置"算 ⇒ 听起来就是"音源钉在世界绝对坐标上"。

**字节码实证**（`javap -p -c`，`resolveMachinePos(BlockPos,float[],UUID)` 的两条出口）：

```
     109: areturn                // 有 MP4 位置 / 找到拥有者玩家 → 现算的新数组
     110: aload_1
     111: areturn                // ← fallthrough：原样返回**入参那同一个引用**（注册时冻结的）
```

### 13.3 同一根因的另外两处（症状不同，一起修）

| 站点 | 冻结方式 | 症状 |
| --- | --- | --- |
| 主输出 `AudioEntry.machinePos` | 注册时算一次 | **用户报的这个**：音源不跟随 |
| 音箱中继 `SpeakerAudioRelay.speakerPos`（`:28`，由 `registerRelay:363` 的 `AudioUtils.centerFor` 设置；调用者 `SpeakerBlockEntity:176` / `ClientAudioEndpointIndex:189`） | 中继注册时算一次 | 结构上的**音箱**同样不跟随（每帧只用 `forward(speakerPos, listenerPos)` 与 `rangeAt`） |
| 控制台音频元素 | 每帧都刷新，但**坐标口径从没投影过**：`ControlConsoleRenderer:1040-1041` → `registerConsoleRelay:334`，而 `ControlConsoleElementPosition.worldPosition` 只做 `consoleX+0.5+local` | 恒定约 2000 万格 ⇒ 几何增益恒 0 ⇒ **静音**（R8 把 UI 守卫修好之后这条路才真正可达） |

第三处与"冻结"不同：它每 client tick 由 `tickConsumers → reconcileConsumer → registerAudioForConsole`
重设一次位置，缺的只是 plot → 世界的换算。

### 13.4 为什么"每帧重投影"成立（不是猜的）

`ActiveSableCompanion.projectOutOfSubLevel(Level, Position)` 的字节码每次调用都：

```
getContaining(level, position)                                  ← 当场重新查
  → LevelPoseProviderExtension.sable$getPose(subLevel)          ← 当场读当前姿态（优先用插值后的渲染姿态）
     （level 不支持该接口时回退 subLevel.logicalPose()）
  → pose.transformPosition(JOMLConversion.toJOML(position))     ← 当场变换
```

即"每次都是现算"，所以把 plot 坐标留在记录里、每帧重投即可跟着结构走。

### 13.5 改动

| 文件 | 内容 |
| --- | --- |
| `mixin/ClientAudioOutputRegistryLivePositionMixin.java`（client） | ① `@Inject` 到 `resolveMachinePos(BlockPos,float[],UUID)` 的 `RETURN`：**仅当** `getReturnValue() == 传入的冻结数组`（即上文 `aload_1; areturn` 那条路）时改用 `SableGeometryCompat.centerFor(level, handlerKey)` 现算；ownerId 有 MP4/玩家覆盖时绝不插手。② `@Inject` 到 `updatePositions` 的 `HEAD`：对 `RELAYS` 逐键重投影并 `setSpeakerPos` |
| `mixin/ControlConsoleAudioElementPositionMixin.java`（client） | 在 `registerAudioForConsole` 内 `@Redirect` `ControlConsoleElementPosition.worldPosition`，把元素位置投到世界坐标（不在 sub-level 里 ⇒ 原样返回）。**只重定向音频这一处**，同一方法在渲染路径上的另外两个调用点不动 |
| `compat/sable/SableGeometryCompat.java` | 新增 `noteLivePositionRefreshed(frozen, live)`：与冻结值相差 >0.1 格时打**一条**日志（每个进程一条）。声音没法自动断言，这行就是游戏内证据 |

两处新钩子各自挡住 `Minecraft.level == null`：真实 Sable 不接受 null level，
而兼容层把 `getContaining` 的**调用异常**当成"Sable 内部出错"→ `degradeAll()`，
那会让这一局的音频距离与投影**全部**失效。挡在调用之前是唯一的低成本做法。

### 13.6 新增门禁：`[2h]` + 探针"投影必须是活的"

身份判据依赖目标方法的一个**字节码不变量**，所以不能靠注释声明：

* `[2h]`(a)：`resolveMachinePos(BlockPos,float[],UUID)` 的结尾必须是 `aload_1; areturn`
  （原样返回入参引用）。上游一旦改成 `clone()` / 复制成新数组，身份比较永远不成立、
  Mixin 静默变成空操作、声音又钉回启动位置，**而且不会有任何报错**。
* `[2h]`(b)：Mixin 源码必须含引用相等判据，且编译后的处理器字节码里必须有
  `if_acmpeq`/`if_acmpne`（证明不是被写成了 `!= null` —— 那会抢走 MP4/玩家位置的覆盖）。
* 探针 K 组（第 51、52 项）：假 Sable 的投影随 `offsetX` 变化（等价于结构移动），
  断言"同一个 plot 坐标必须投出移动了 20 格的世界坐标" + "每次必须返回新数组（复用同一个
  `float[]` 就等于把位置冻结了）"。

三条新故障各自证明上面某一条会说"不"：`--fault=tail`、`--fault=identity`、`--fault=livecache`。

### 13.7 我自己的第 5 个假阳性：`rc≠0` 不等于门禁说了"不"

第一次跑 13 条故障注入时，我在**错误的工作目录**下执行脚本，python 自己以 `rc=2`
（`can't open file`）退出、输出为空 —— 13 条"全部 rc≠0"，看起来完美，实际上**门禁一次都没跑**。
（这个坑的形状与 §9.1/§12.4 完全一样：检查脚本坏了，检查结论就成了噪音。）

修法是一条命令：`t4/run_faults.py` 对每个故障同时要求
**①`rc != 0` ②输出里出现该门禁的专属失败标记**，并断言部署产物全程逐字节未变；
逐条日志落在 `t4/dist/fault-<名字>.log`，汇总在 `t4/dist/faults-summary.log`。

### 13.8 已验（离线）

| 项 | 结果 |
| --- | --- |
| 构建 | exit 0；产物 `54B6288B7DA582E2194BAD63E2F88D38922FACD1B4B26C73DA95FC0D113AC529`，9,649,566 B / **1697 条**（相对上一轮 +2 Mixin） |
| 行为探针 | **52 项断言 0 失败**（新增"投影必须是活的" + "每次返回新数组"） |
| `[2h]` | 目标出口形状 + Mixin 引用比较 + 源码判据 三条全绿；`--fault=tail`/`--fault=identity` 会报 FAIL |
| `[2e]` | 27 个 `@Inject` + 14 个 `@Redirect` 全部与目标一致（新增 3 个） |
| 独立审计 | exit 0：新增恰好 **19** 条、只改 `mixins.json`（mixins 12 / client 26）、新增 `[8]` 部署级复核身份判据 |
| 故障注入 | **13 条构建 + 4 条审计**全部 exit=1 且失败理由与故障一一对应；故障跑动后产物哈希未变（`t4/run_faults.py` 是这条结论的唯一入口） |
| 地面不回归（推理层） | 所有新路径都以"该坐标是否在某个 sub-level 里"为闸门；不在 ⇒ `centerFor`/`projectToWorld` 返回 `null` ⇒ 一律走原实现 |

### 13.9 未验（进游戏）与取舍

**未验**：
1. 结构移动时声音是否真的跟着走（只能听 + 看第 3 条日志行）。
2. 控制台音频元素在结构上是否真的出声（只推到代码层，从没在游戏里用过这个功能）。
3. 结构上的音箱是否跟着走。
4. 日志阈值逻辑（0.1 格）没有独立断言：它只在游戏里"出不出现"这一层被观察。

**取舍**：
* 中继重投影的键来自 `RELAYS` 的键。真实音箱键是方块位置；控制台元素的键是
  `ControlConsoleAudioElementKey.of` 把 elementId 哈希 XOR 进控制台坐标得到的**合成键**。
  合成键落进某个 sub-level 的概率约 10⁻¹⁵ 量级，且控制台中继每 tick 都会被重新设置位置，
  即使撞上也只影响一帧 —— 所以没有为它单独维护"真实键集合"。
* `SpeakerAudioRelay` 没有 `speakerPos` 的读取方法，所以"随结构刷新"的证据行只覆盖主输出；
  音箱只能靠听。
* `centerFor` 逐帧对每个中继键调用（一次反射 `invoke`），量级是每帧几十次 —— 与原先
  `centerFor` 已有的调用频率同阶，没有额外缓存。
