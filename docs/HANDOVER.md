# 工作交接：NCPB 1.21.1 移植 —「Sable / Create: Aeronautics 物理化结构兼容」专项

> 这份文档是**入口**：先用它搞清楚现状、路径、怎么建/验/回滚、还差什么。
> 全过程证据链（症状 → 根因 → 改动 → 门禁 → 已验/未验）在
> [`SABLE-AUDIO-FINDINGS.md`](SABLE-AUDIO-FINDINGS.md)（§1–§13，自包含，建议按需读某一节）。

---

## 0. 一句话现状

物理化结构上：**音频有声 ✓、视频能出画面 ✓、帧率正常 ✓、不再周期性重定位 ✓、方块 UI 可交互 ✓**
（后两项是本轮之前修的，**尚未进游戏验证**）；
**音源不再钉死在播放开始的位置 ✓**（本轮修的 R9–R11，**尚未进游戏验证**）。

当前部署产物 `54B6288B7DA582E2194BAD63E2F88D38922FACD1B4B26C73DA95FC0D113AC529`
（9,649,566 B / **1697 条** / 相对"所有 Sable 改动之前"的基线新增 19 条：17 个 Mixin + 兼容层 + 其内部类）。
构建门禁、**52 项**离线行为探针、独立审计（**19** 个新增条目）、
**13 条构建故障 + 4 条审计故障**（一条命令：`python port-1.21.1\t4\run_faults.py`）全部通过。

**唯一非兼容性残留**：NCPB 自己的"离屏暂停 + 恢复重定位"策略 —— 移开视线 ≥1.5 秒再回来会有约 1 秒加载图
（**地面上完全一样**，这段代码没有任何坐标依赖），用它的系统属性即可调，不需要动代码。

⚠️ **进游戏实测覆盖到**：结构上有声 ✓、视频出画面 ✓、帧率恢复 ✓、不再每 10 秒重定位 ✓。
**尚未进游戏验证**：R7（在视方向）、R8（服务端 8 格守卫 → 8 个方块的 UI）、
R9–R11（音源位置是否真的跟着结构走 / 音箱 / 控制台音频元素），见 §6/§9。

---

## 1. 这个专项在解决什么

* **对象**：`net_music_can_play_bili` 的 1.21.1 NeoForge 移植（`0.7.9-beta+neo1.21`）。
  该移植是 **jar 手术**做出来的（**没有源码**），`port-1.21.1/decomp/` 是它的反编译树（见 §2 的完整性核对）。
* **症状**（按发现顺序）：把方块放到 **Create: Aeronautics（Sable 物理库）物理化的结构**上之后，
  ① 唱片机**无声**；② 视频投影仪**卡在加载界面**；③（修好②之后）**一出画面就特别卡**；
  ④（修好③之后）**视角移开后回到加载界面**；⑤ **方块 UI 能打开但按钮点了没反应**
  （而左键破坏 / 右键取出放入唱片正常）；⑥ **声音源钉死在世界绝对坐标、不跟着结构移动**。
* **环境事实（整套修复的地基）**：Sable 把结构里的方块实体放在**同一个 Level 的 plot 区块**里
  （实测 `20481032, 129, 20481032`），而**摄像机/听者/玩家仍在世界坐标**（实测 `-28.5, -58.5, 24.2`）
  —— 两者相差约 **2000 万格**。凡是"拿 plot 当世界"直接比距离/方向/射线的判定，结论必然相反。
  这一条是从 Sable 源码 + 游戏内日志实证出来的，不是猜的（见 §4 各行的证据）。
* **判定工具**：`%MC%\versions\1.21.1-NeoForge_21.1.252\logs\debug.log`（DEBUG 级；
  `latest.log` 不含这些行）。兼容层与门禁的日志前缀是 `[NCPB/Sable]`。
  ⚠️ **有些失败在日志里一个字都没有**（例如服务端 `if (距离守卫失败) { 什么都不做 }`，整包静默丢弃），
  这种只能读代码定位 —— 见 §12.5。

---

## 2. 交付物与路径

| 类别 | 路径 | 说明 |
| --- | --- | --- |
| 部署产物 | `%MC%\versions\1.21.1-NeoForge_21.1.252\mods\net_music_can_play_bili-0.7.9-beta+neo1.21.jar` | `54B6288B…`，1697 条 |
| 改动源（唯一真源） | `port-1.21.1/t4/src/com/zhongbai233/net_music_can_play_bili/compat/sable/SableGeometryCompat.java` | 反射软兼容层（不引编译期依赖、不加载 Sable 的类、失败 fail-open + **留痕**） |
| 改动源（9 个音频/视频 Mixin） | `port-1.21.1/t4/src/…/mixin/AudioSubscriptionSubLevelDistanceMixin.java`（common）<br>`…/AudienceSyncLevelScopeMixin.java`（common）<br>`…/ClientAudioDemandSubLevelDistanceMixin.java`（client）<br>`…/ModernTurntableSoundSubLevelSourceMixin.java`（client）<br>`…/AudioUtilsSubLevelPositionMixin.java`（client）<br>`…/VideoProjectorVisibilityDistanceMixin.java`（client）<br>`…/VideoScreenOcclusionSubLevelMixin.java`（client）<br>`…/ClientAudioOutputRegistryLivePositionMixin.java`（client，R9/R10）<br>`…/ControlConsoleAudioElementPositionMixin.java`（client，R11） | 2 个 common（服务端逻辑）+ 7 个 client；对应 §4 的 R1–R11 |
| 改动源（8 个服务端守卫 Mixin） | `port-1.21.1/t4/src/…/mixin/{ModernTurntableControlPacket,VideoProjectorConfigPacket,LyricProjectorConfigPacket,SpeakerConfigPacket,LiveStreamerControlPacket,ControlConsoleEditLeasePacket,ControlConsoleConfigPacket,ControlConsoleAccessPacket}SubLevelScopeMixin.java`（全部 common） | 对应 §4 的 R8；**源码由生成器产出，不要手改其中一份** |
| 这些 Mixin 的生成器 | `port-1.21.1/t4/gen_network_scope_mixins.py` | 8 份逐字一致（只差类名/import）；手抄最容易"少一句/写错类名"，那只有进游戏才暴露 |
| 离线行为探针 | `port-1.21.1/t4/test/…/compat/sable/SableGeometryCompatProbe.java` | **52 项**断言，纯 JVM、可注错 |
| 构建 + 门禁 + 故障注入 | `port-1.21.1/t4/build_mixins.py` | 唯一的构建入口（编译、注入、注册、写 jar、部署） |
| **故障注入总检** | `port-1.21.1/t4/run_faults.py` | 一条命令跑完 13 条 `--fault=*`：同时要求 `rc != 0` **且**失败理由对得上，并断言部署产物全程未变（只看退出码会被"python 自己报错"骗过去，见 §8） |
| 独立审计 | `port-1.21.1/t4/verify_sable_fix.py` | 刻意不复用构建者自检：三方产物对读 + 逐条目比字节 + 部署级复核（含 `[8]` 身份判据） |
| 生成物 | `port-1.21.1/out/<jar>`、`port-1.21.1/t4/dist/mixins.jar` | 三方必须同哈希 |
| 记录 | `port-1.21.1/SABLE-AUDIO-FINDINGS.md` | §1–§13 全过程，含每条根因的日志/字节码证据 |
| 证据日志 | `port-1.21.1/t4/dist/build-last.log`、`audit-ok.log`、`fault-*.log`、`faults-summary.log`、`auditfault-*.log` | 构建全量输出 / 审计 / 逐条故障注入 |
| 回滚点 | `port-1.21.1/backup-jar/pre-mixins-deployed-*.jar` | 每次部署前自动备份，见 §3 |
| 反编译参考 | `port-1.21.1/decomp/` | 710 个 `.java`；**完整性已核对**：jar 里 1432 个 class、724 个顶层类，只有我们自己注入的顶层类没有 `.java`（所以"某方法没有调用方"这类结论可信） |
| Sable 源码 | `mc/sable-main/`（工作区内） | 判定"哪一边是 plot 坐标"的权威依据（例：`clip_overwrite/BlockGetterMixin` 直接返回 plot 空间的命中结果） |
| 参考第三方兼容 mod | `%MC%\versions\…\mods\aeronauticscompat-1.1.3.jar` | 它的 `SableBridge` 用的是同一套反射思路（可作交叉印证） |
| **待上传的教训（本地保留）** | `port-1.21.1/misakanet-pending/` | 本次会话 4 条失误 lesson + 1 条合并 intake。**MisakaNet 写入接口全部超时**（`write_lesson`×4、`submit_intake`×1；读接口正常，`node_id=Misaka80239`）→ 已按字段形态原样落盘，重试步骤见该目录 `README.md` |

**路径约定（重要）**：`D:\航空学\.minecraft` 这类含中文的绝对路径**不写进任何交付物**；
`t4/build_mixins.py` 与 `verify_sable_fix.py` 用
`''.join(chr(c) for c in (0x822A, 0x7A7A, 0x5B66))` 拼出它，避免"写死别人机器的路径"和编码坑。
其余路径全部相对工作区。

---

## 3. 怎么建 / 怎么验 / 怎么回滚

```powershell
# 构建 + 全部门禁 + 部署（唯一入口；任何门禁失败都会中止，不会改动部署文件）
python port-1.21.1\t4\build_mixins.py

# 证明每条门禁真的会说"不"：一条命令跑完 13 条并逐条核对失败理由 + 产物未变
python port-1.21.1\t4\run_faults.py
python port-1.21.1\t4\run_faults.py --only=identity,tail,livecache   # 只跑本轮新增的三条

# 单独跑某一条（调试用；注意必须同时看 rc 和 [FAIL] 的**理由**，只看 rc 会被骗，见 §8）
python port-1.21.1\t4\build_mixins.py --fault=at          # @At 目标串
python port-1.21.1\t4\build_mixins.py --fault=descriptor  # 方法描述符
python port-1.21.1\t4\build_mixins.py --fault=musthave    # 普通类字节码里的反射目标字符串
python port-1.21.1\t4\build_mixins.py --fault=register    # Mixin 未注册（= 静默失效）
python port-1.21.1\t4\build_mixins.py --fault=jsonenv     # 注册进错的环境列表
python port-1.21.1\t4\build_mixins.py --fault=probe       # 离线行为探针（不投影）
python port-1.21.1\t4\build_mixins.py --fault=livecache   # 离线行为探针（投影被冻结 = 本轮这个 bug）
python port-1.21.1\t4\build_mixins.py --fault=handler     # @Inject/@Redirect 处理器签名（那次崩服的复现）
python port-1.21.1\t4\build_mixins.py --fault=coordshape  # 坐标读取退回只认 getX（[2f]）
python port-1.21.1\t4\build_mixins.py --fault=shadow      # @Shadow 少了 remap = false
python port-1.21.1\t4\build_mixins.py --fault=guard       # 服务端距离守卫漏修一处（[2g] 普查）
python port-1.21.1\t4\build_mixins.py --fault=identity    # 身份判据被写成 != null（[2h]）
python port-1.21.1\t4\build_mixins.py --fault=tail        # 目标方法不再原样返回入参引用（[2h]）

# 独立审计（部署后跑；同样是"能说不会失败"的）
python port-1.21.1\t4\verify_sable_fix.py
python port-1.21.1\t4\verify_sable_fix.py --fault
python port-1.21.1\t4\verify_sable_fix.py --fault=handler
python port-1.21.1\t4\verify_sable_fix.py --fault=coordread
python port-1.21.1\t4\verify_sable_fix.py --fault=identity   # [8] 身份判据两条期望同时反过来

# 回滚（任选一个备份；文件名带时间戳，见 backup-jar 目录）
Copy-Item -Force "port-1.21.1\backup-jar\pre-mixins-deployed-<时间戳>.jar" `
  "%MC%\versions\1.21.1-NeoForge_21.1.252\mods\net_music_can_play_bili-0.7.9-beta+neo1.21.jar"
```

工具链：`python`（3.11；**不要用 `python3`** —— 在 Windows 上常是 0 字节 Store 存根，退出码 9009）、
JDK 在 `%APPDATA%\.minecraft\runtime\java-runtime-delta\bin`（脚本里已固定这个位置）。
控制台请设 `$env:PYTHONIOENCODING='utf-8'`，否则中文门禁信息会花屏（不影响退出码）。

**回滚点清单（时间序）**：

| 备份 | 对应状态 |
| --- | --- |
| `pre-mixins-deployed-20261003-005833.jar`（`919053AD…`） | **所有 Sable 改动之前**（结构上无声，但地面一切正常） |
| `…-013728.jar`（`1B773E29…`） | 修好崩服，但仍无声 |
| `…-014524.jar` / `…-021505.jar`（`D67A049C…`） | 音源位置投影（当时其实未生效） |
| `…-023513.jar`（`27633B02…`） | 有声 + 有画面，但**特别卡** |
| `…-110806.jar`（`B781727E…`） | 不卡了，但**仍在看屏幕时会被判"看不见"** |
| `…-112849.jar`（`86778BF1…`） | 修了在视方向（R7），但**方块 UI 按钮仍点不动** |
| `…-175619.jar`（`59FA2C90…`） | 修了服务端 8 格守卫（R8）+ 音源逐帧重投影（R9–R11），但还没有"随结构刷新"的证据行 |
| 当前部署 | `54B6288B…`（1697 条；加了 null level 防护 + `音源世界位置已随结构刷新` 证据行） |

---

## 4. 根因总账（11 处，全部同一类：世界坐标 ↔ plot 坐标混用，或"注册时算一次就冻结"）

| # | 位置 | 症状 | 修法 | 状态 |
| --- | --- | --- | --- | --- |
| R1 | `AudioEndpointSubscriptionTracker.update` 的距离判定（96+48 格） | 服务端判"太远"→ 不再给玩家发包 | `AudioSubscriptionSubLevelDistanceMixin`（common，`@Redirect` 那次 `distanceSquared`） | ✅ 已修 |
| R2 | `ClientAudioOutputRegistry.hasGeometricAudioDemand` / `hasPreparationDemand` 的 64 格球 | 客户端响度需求算成 2000 万格 → 增益 0 | `ClientAudioDemandSubLevelDistanceMixin`（`@Redirect` `AudioUtils.distance`） | ✅ 已修 |
| R3 | `ModernTurntableSound.fixedSourceAvailable` | 切换瞬间音源被判"不可用" | `ModernTurntableSoundSubLevelSourceMixin`（`@Inject` RETURN 兜底） | ✅ 已修 |
| R4 | `AudioUtils.centerFor(BlockPos)`（**全模组唯一的 BlockPos→世界坐标入口**） | NCPB 自己的 OpenAL 源被钉在 2000 万格外 + 增益 0 → **结构上无声** | `AudioUtilsSubLevelPositionMixin`（`@Inject` HEAD 换成 plot→世界投影） | ✅ 已修（**注意**：第一次因为"读返回值用了 `getX()`，而 `Vec3` 只有 `x()`"而**静默失效**，见 §8） |
| R5 | `isProjectorWithinRenderDistance` 的 64 格判定 | 永远判"太远" → 永不授予解码准入 → **永远停在加载占位图** | `VideoProjectorVisibilityDistanceMixin`（`@Redirect` 距离比较） | ✅ 已修 |
| R6 | `VideoScreenOcclusion.isOccluded` → `BlockGetter.traverseBlocks(世界摄像机, plot 目标)` | **逐格 DDA 走约 4×10⁷ 个格子** → 渲染线程被吃满 → "一出画面就特别卡"（且视频落后 → 每 10 秒重定位） | `VideoScreenOcclusionSubLevelMixin`（`@Redirect`，目标点换世界坐标；拿不到就**不射**） | ✅ 已修 |
| R7 | `isScreenInView(camera, plot 采样点)` | 方向恒为**固定 plot 对角线** → 仍在看屏幕时被判"看不见" → 停画 + 计离屏 → 转回来一次加载图 | `VideoProjectorVisibilityDistanceMixin` 第二个 `@Redirect`（采样点投成世界坐标再判方向） | ✅ 已修，**未进游戏验证** |
| R8 | **8 个**服务端包处理器的 8 格守卫：`player.position().distanceToSqr(Vec3.atCenterOf(payload.pos())) > 64.0` | 距离²≈8×10¹⁴ ⇒ 守卫失败 ⇒ **整包被静默丢弃** ⇒ 方块 UI 能打开但按钮点了没反应（破坏/取放走方块实体路径，所以正常） | 8 个 `…PacketSubLevelScopeMixin`（common）：HEAD 压栈该包 Level、RETURN 出栈（**专用作用域 + 深度计数**），`@Redirect` 换 sub-level 感知距离 | ✅ 已修，**未进游戏验证** |
| R9 | `ClientAudioOutputRegistry.resolveMachinePos(pos, machinePos, ownerId)` 的 fallthrough 出口（`AudioEntry.machinePos` 是**注册时**用 `centerFor(plotPos)` 算好存进 record 的） | 那一次投影**结果是对的**，但只算一次 ⇒ 结构一移动，声场朝向（`atan2(mp-lp)`+front 平滑器）与空间增益（`distance(lp,mp)`）全按"播放开始瞬间"算 ⇒ **声音钉在世界绝对坐标** | `ClientAudioOutputRegistryLivePositionMixin`：只在"原实现原样退回了那个冻结数组"（字节码 `aload_1; areturn`）时改用 `centerFor(level, key)` 现算；ownerId 有 MP4/玩家覆盖时绝不插手 | ✅ 已修，**未进游戏验证** |
| R10 | `SpeakerAudioRelay.speakerPos`（同一个 record-freeze 形状：`registerRelay` 里 `AudioUtils.centerFor(speakerPos)` 算一次） | 结构上的**音箱**同样不跟随（每帧只用 `forward(speakerPos, listenerPos)` 与 `rangeAt`） | 同上 Mixin 的第二个 `@Inject`：`updatePositions` HEAD 对 `RELAYS` 逐键重投影 | ✅ 已修，**未进游戏验证** |
| R11 | `ControlConsoleRenderer.registerAudioForConsole` 传进 `registerConsoleRelay` 的 `worldPos`（来自 `ControlConsoleElementPosition.worldPosition(consolePos.getX(), …)`，**只做加法、从不投影**） | 中继位置落在 plot 坐标系 ⇒ 与听者相距约 2000 万格 ⇒ 几何增益恒 0 ⇒ 控制台音频元素**静音**（R8 之后这条路才真正可达） | `ControlConsoleAudioElementPositionMixin`：只重定向 `registerAudioForConsole` 里那一处 `worldPosition`，投到世界坐标；渲染路径的另外两个调用点不动 | ✅ 已修，**未进游戏验证** |

**未改但已定位**：`isProjectorScreenPredictedVisible` → `VideoVisibilityTrendPredictor.shouldPrewarm`
用的是 plot AABB ⇒ 结构上"预暖"恒为 false（影响很小，见 `SABLE-AUDIO-FINDINGS.md` §11.5）。

---

## 5. 门禁与故障注入（"抓不到错的检查不算检查"的落地）

`t4/build_mixins.py` 里的硬门禁（每条都在实测中抓到过真问题）：

| 门禁 | 挡什么 |
| --- | --- |
| 三方产物一致性 | 部署 jar 与 `out/`、`dist/` 不同哈希 |
| 目标类 / 方法 / **完整描述符** | 目标类里没有那个方法或那个参数形状（handler 会不匹配而崩） |
| `@At` 目标串（字节码级） | 注入串写错、或目标类里根本没有那个调用点 |
| `[2e]` 处理器签名 vs 目标（**含"解析到 0 个 = 失败"**） | `@Inject` 只捕获前几个参数（**实测崩服**）、`@Redirect` 参数个数不符、regex 漂移导致门禁变摆设 |
| `[2f]` 坐标读取口径 | `Vec3` 只有 `x()/y()/z()`；兼容层字节码必须真的调用 `Position.x()` |
| `[2g]` **服务端距离守卫普查** | 扫描 jar 里 `network/**` 所有类，凡同时出现 `Vec3.atCenterOf` 与 `Vec3.distanceToSqr` 的都必须有对应 Mixin —— **漏一个就构建失败**（把"我要记得改几处"变成机器说了算；`--fault=guard` 会点名漏掉的那个类） |
| `[2h]` **"冻结位置"刷新判据** | ① 目标方法 `resolveMachinePos(BlockPos,float[],UUID)` 的字节码结尾必须是 `aload_1; areturn`（原样返回入参引用）——上游一改成 `clone()`，身份判据永远不成立、Mixin 静默变成空操作；② Mixin 处理器字节码里必须有 `if_acmpeq`/`if_acmpne`（证明不是被写成 `!= null`，那会抢走 MP4/玩家位置的覆盖）。`--fault=tail` / `--fault=identity` 分别证明这两条会说"不" |
| `@Shadow` 只认行首注解 | 少了 `remap = false`（探针曾因此失效）；另：目标字段是 `final` 时 `@Shadow` **必须**带 `@Final`（Mixin 自己的规则：`is final but shadow is not decorated with @Final`），`RELAYS` 就是这种情况 |
| `[2d]` 离线行为探针 | 退化 / 投影（**三种返回值形状**）/ 作用域 / 异常与读失败必须留痕 / 视频可见性作用域 / 遮挡射线三态 / 网络作用域（深度、多余弹出、**不弹掉音频作用域**）/ **投影必须是活的 + 每次返回新数组**（`--fault=livecache` 把假 Sable 换成"只算一次"的版本，这两条必须失败） |
| 引用自洽 | class 文件引用了 jar 里不存在的本模组类（`NoClassDefFoundError` 的前身） |
| 悬空注册 / 环境归属 / 普通类污染 | `mixins.json` 列了名字却没有 class、注册进错列表（= 静默失效）、非 Mixin 类被注册 |
| 资源结构断言 | Euler 模型的 loader / 元素数 / `render_type` |
| **先写 `.part` 后落盘** | 门禁失败时把坏产物留在 `dist/`，误导后续检查（实测踩过） |

**约定（请继续保持）**：新增或修改任何一条检查，都要跑对应的 `--fault=<case>` 证明它**还会说"不"**；
故障注入本身也要自检（例：`--fault=coordshape` 会先断言"降级后的字节码里确实没有 `Position.x()`"，
否则这次 FAIL 不算证据）。

---

## 6. 已验 / 未验

**已验（离线）**：构建 13 类门禁全绿；**52 项**行为探针 0 失败；独立审计（**19** 个新增条目、只改 `mixins.json`、
注册环境正确（`mixins` 12 项 / `client` 26 项）、部署级复核反射目标与 `Position.x()`、新增 `[8]` 身份判据）；
**13 条构建故障 + 4 条审计故障**全部 exit=1 且**理由对应**（`python port-1.21.1\t4\run_faults.py` 一条命令复核）；
故障跑动不污染部署产物。

**已验（进游戏）**：结构上**有声** ✓；视频**出画面** ✓；**帧率恢复**（"特别卡"消失）✓；
**不再每约 10 秒重定位** ✓；日志有 `[NCPB/Sable] 音源位置投影生效：plot 坐标 … → 世界坐标 …` ✓。

**未验**：
1. R7（在视方向）与 **R8（8 个方块的 UI 是否都能点）**——尚未进游戏；
2. **R9–R11**：结构移动时声音是否真的跟着走（要看新增的
   `[NCPB/Sable] 音源世界位置已随结构刷新：注册时 (…) → 现在 (…)` 那行有没有出现）、
   结构上的**音箱**、**控制台音频元素**（后者只推到代码层，从没在游戏里用过这个功能）；
3. **地面回归**每次都要顺手看一眼（地面本就不该受影响）；
4. 多个结构 / 多个投影仪 / 多人同时看的场景；
5. `VideoProjectorRenderer.shouldRender`（另一处 64 格比较）拿到的 `cameraPos` 是否**已经**是 plot 空间
   —— 若是，那处本来就对，**不能**再加投影（会重复变换）；目前没动它。

---

## 7. 遗留、取舍与"怎么调"

| 项 | 说明 | 怎么调 |
| --- | --- | --- |
| **移开视线 ≥1.5s 再回来有约 1 秒加载图** | NCPB 自己的策略：`pause_decode=true`（离屏 >`grace_ms=500` 暂停解码）→ 视频时钟冻结 → 恢复时落后 ≥`resume_restart_lag_ms=1500` 就 `restartDecoder` → 首帧前画 LOADING 占位图。**地面一模一样**（这段代码无坐标依赖） | 系统属性：`ncpb.video.offscreen.resume_restart_lag_ms`（调大，如 5000）、`ncpb.video.offscreen.pause_decode=false`、`ncpb.video.offscreen.grace_ms` |
| 结构投影仪"被结构自身墙体遮挡"不识别 | R6 的取舍：遮挡射线改在世界坐标射（结构自身的方块在 plot 里）⇒ 视频会透过结构自己的墙画出来。等价于本轮之前的行为（那时没有遮挡判定） | 若要修，需要把摄像机换算进 plot 空间（逆位姿）——比现在这套复杂，未做 |
| 视频距离阈值处用"到投影仪中心的距离"代替"到屏幕 AABB 的距离" | `@Redirect` 拿不到 AABB 形状；`sub-level` 只能从投影仪自身坐标问出来（投影仪在结构上会被渲染，这本身就证明 `getContaining` 非空） | 64 格阈值附近差异 < 屏幕尺寸；结构上玩家就在几格内，无感 |
| 结构上"预暖"恒为 false | 预测器的 AABB 是 plot 坐标 ⇒ `distanceToAabb ≈ 2000 万 > 64` ⇒ 永远 false（地面可为 true） | 影响小（只在"正持续转向屏幕"那几帧起作用）；要修需把 AABB 投到世界再喂预测器（18 参包私有静态调用，性价比低） |
| `isScreenInView` 的 6 行算式是**照抄**的 | 不用 `@Shadow` 是刻意的（绑错只在运行期暴露）；构建期用 `at_targets` 钉住调用点 | 上游若改了公式需同步；调用点被改名/删除时构建直接失败 |
| **客户端侧**还有 3 处同形状距离比较（本轮未改） | `gui/HolographicEditorLifecycleScreen.java:284`、`client/ControlConsoleRoamingSession.java:439` 与 `:460` —— 都是"玩家(世界) ↔ 方块(plot)"的 `distanceToSqr(...) > 64.0`，属**全息眼镜 / 控制台**那套，不是唱片机 | `[2g]` 目前只普查 `network/**`（服务端包）。要覆盖客户端，建议再加一条**同形状**的普查规则（扫描 + 必须被覆盖），而不是靠人记 |
| 中继重投影吃的是 `RELAYS` 的**键**（含控制台元素的合成键） | 真实音箱的键是方块位置；控制台元素的键是 `ControlConsoleAudioElementKey.of` 把 elementId 哈希 XOR 进控制台坐标得到的合成键。合成键落进某个 sub-level 的概率约 10⁻¹⁵，且控制台中继每 tick 都会被重新设置位置，即使撞上也只影响一帧 —— 所以没有为它单独维护"真实键集合"（那需要跨 Mixin 的 `@Unique` 状态或弱引用表） | 若真要精确，可在 `registerRelay`/`registerConsoleRelay` 上各加一个 HEAD/RETURN 记账点，维护"哪些键是真实方块位置" |
| "随结构刷新"的证据行只覆盖**主输出** | `SpeakerAudioRelay` 没有 `speakerPos` 的读取方法（只有 setter），所以音箱那一路没法打印前后对比 | 音箱只能靠听；要加证据行需给 `SpeakerAudioRelay` 加一个 `@Shadow` 读取的字段 |
| 兼容层对 **null level** 没有全局防护 | `isInsideSubLevel` 会把 `getContaining(level=null, …)` 的异常当成"Sable 内部出错"→ `degradeAll()`（整局能力清空）。本轮只在两个**新增**钩子里挡了 null（因为离线探针刻意用 `null` level 调投影，全局挡会让探针失效） | 既有调用点（`AudioUtils.centerFor` 那条）依赖"只在世界里有活跃输出时才会被调用"，实测未出问题；若要彻底，得把"测试用假 helper"与"真 Sable"区分开再挡 |

---

## 8. 踩过的坑（给下一个人，含我自己犯的假阳性）

**Mixin / jar 手术管线**
1. `@Inject` 处理器**只能"全参数"或"零参数"**；只捕获前几个 → **目标类首次加载时**抛
   `InvalidInjectionException` 崩服（本项目实测：往物理化唱片机放唱片那一刻崩，因为目标类那时才第一次被加载）。
2. 目标类有重载时必须用**完整描述符**定位（只写方法名会往两个方法都注入）。
3. 编译**我们自己的源码**时类路径顺序：`out-mixins` 必须排在部署 jar **之前**，否则 javac 拿旧副本解析，
   报出莫名的"找不到符号"。
4. 内部类 / 匿名类（`Name$1`、`Name$Inner`）的 jar 内前缀必须用**类自己的包**，写死包名会让它们进错路径
   （"引用自洽"门禁会抓）。
5. javac 要求**文件名 == public 类名**：临时改名会让编译失败以 exit≠0 收场，看起来像"故障被门禁抓住了"。
6. jar 必须**先写 `.part`、全部门禁通过后再改名**，否则一次故障跑动会把坏产物留在 `dist/`。
7. 门禁的字符串匹配要认准**语法形态**：泛型里的逗号不是参数分隔符；`@Shadow` 只认行首注解
   （javadoc 里的 `{@code @Shadow}` 不算）。这两条都实测误报过。
8. `@Redirect` 处理器的参数个数**与目标是不是实例方法有关**：实例调用要**多接一个接收者**
   （`list.add(x)` → `(List, Object)`）。`[2e]` 原来只按描述符实参个数算，于是 8 个
   `Vec3.distanceToSqr` 重定向被误判"多写了一个参数"（见下第 4 条）。

**我自己的五个假阳性（教训：测试/门禁里写的前提必须来自真东西，不能来自假设）**
1. 离线探针的假对象是**照着同一个错误假设**写的（自带 `getX/getY/getZ`），于是"读取失败被 catch 吞掉"
   这条真缺陷在探针里完全看不见 → 现在探针必须用与真 `Vec3` **同形**的对象（`implements Position`，只有 `x()/y()/z()`）。
2. `--fault=coordshape` 曾以"文件名不符导致 javac 报错"退出 1，而 `[2f]` 根本没跑到 → 现在故障带自检。
3. `@Shadow` 门禁全文匹配 → javadoc 误报（见上）。
4. `[2e]` 的"实参个数"模型漏了**接收者** → 8 个实例调用重定向被误判（见上第 8 条）→ 现在读目标类
   `javap -c` 的那次调用点 opcode，`invokevirtual`/`invokeinterface` 就 +1，并把这层写进 ok 信息里。
5. **`rc != 0` 被我当成了"门禁说了不"**：第一次跑 13 条故障注入时工作目录写错，python 自己以 `rc=2`
   （`can't open file`）退出、输出为空 —— 13 条"全部 rc≠0"，看起来完美，实际门禁一次都没跑。
   → 现在用 `t4/run_faults.py`：每条都必须 **`rc != 0` 且输出里出现该门禁的专属失败标记**，
   并断言部署产物全程逐字节未变。

**顺手记两个"手抖型"错误（本轮各犯一次）**：门禁里写处理器名时把 `ncpb` 敲成了 `npcb`，
两处都表现为"解析不到处理器字节码"。**两次都是靠"解析不到就报 FAIL（空门禁防线）"抓到的**，
而不是静默通过 —— 这就是 `[2e]` 里"解析到 0 个 = 失败"那条设计的价值。

**四个假阳性的共同点（值得记住）**：它们**都是响亮地错**（构建中止、绝不部署、报错信息直接指到那一条），
所以从来没有把坏产物放出去 —— 这正是门禁该有的失败方式。**怕的是安静地通过。**

**"没有报错"不等于"没发生"**：R8 那 8 处守卫是 `if (守卫失败) { 什么都不做 }`，
服务端连一行日志都没有。遇到"UI 点了没反应"这类症状，正确的第一步是先把范围缩到
"客户端发没发 / 服务端收没收到"，而不是从 UI 渲染层找。

**日志使用**
* 要看 `debug.log`（DEBUG 级），`latest.log` 没有这些行；
* 兼容层的"证据行"是刻意设计的：`Sable 兼容层已启用` / `音源位置投影生效：plot 坐标 … → 世界坐标 …` /
  `plot 量级坐标 … 未判进 sub-level：level=…, isSubLevelContainerHolder=…, plotContainer=…, getPlot(chunk …)=…`
  —— **没有第二行**就说明坐标口径不对，**有第三行**就直接告诉你断在哪一环；
* "视频落后"要区分因果：`videoQueued - video ≈ 200ms`（队列被吃空）= 生产端慢（游戏卡），
  不是视频渲染慢。

---

## 9. 下一步（按优先级）

> **头两条已经建到任务看板（todo 列），可直接 `task_board_run`：**
> * `1cae5853-51d8-43be-a8ac-04805b8032a4` —— 尝试适配光影（Iris）下物理化结构上的画面显示；
> * `4be08606-db6e-4290-b01d-25c28dcb74a4` —— **物理化前**就放好的唱片机/视频投影仪，物理化后不显示画面（bug）。
>
> 两张卡的提示词里已经带了"先读哪两份文档、工作区铁律（相对路径 / 门禁 / 故障注入 / 已验未验）、
> 复现与验收判据"，可以独立交给下个会话。

1. **进游戏验证本轮修复**：
   * **R9——音源跟随**：在结构上开始播放，然后**让结构移动/转向**（飞船开起来 / 转个弯）。
     应当：声音方位跟着结构走（音源不再钉在启动位置）。
     证据行：`[NCPB/Sable] 音源世界位置已随结构刷新：注册时 (…) → 现在 (…)`
     —— 出现 = 重投影生效；不出现 = 结构没动，或这条路没被走到（先确认结构真的动了）。
   * **R10——结构上的音箱**：同样的移动测试，听音箱是否跟着（没有证据行，只能听）。
   * **R11——控制台音频元素**：结构上的控制台里放一个音频元素，看是否出声（此前恒静音）。
   * R8 —— 结构上打开唱片机 UI，**按钮应当有反应**（播放/暂停、重播、单曲、红石、提取、音量、进度条）；
     顺手试一下同一个结构上的**视频投影仪 / 歌词投影仪 / 音箱**的 UI（一起修的，8 处同源）。
   * R7 —— 仍在看屏幕时不应再被判"看不见"。
   * 地面回归（地面本来就不该受影响）。
2. 若嫌"移开 ≥1.5 秒就加载"：先调属性（§7 第一行），**不建议**改代码 —— 那是 mod 作者的性能取舍。
3. 客户端侧那 3 处同形状距离比较（§7 最后一行）：若要继续做兼容，建议先加一条**客户端版 `[2g]` 普查规则**，
   再把它们一并改掉；否则下次用户会在"全息眼镜 / 控制台"上遇到同一类症状。
4. 若要继续原计划的 **T2（把 0.7.10 的逻辑修复回移到 1.21.1 移植）**：已测得的真实漂移集中在
   `Fmp4NativeVideoDecodePump`（125 处语句差异）与 `Fmp4VideoStreamSeeker`（95 处），
   而原先怀疑的三处（`VideoFirstFrameProbeBudget`、`VideoPerformanceMonitor` + `VideoPerformanceFallbackPolicy`、
   `MediaTimelineClock`）**语义已经一致** —— 建议按片分批改，每片配"编译探针 + 行为测试"。
5. 若要支持更多第三方结构库：`SableGeometryCompat` 是**探测 + 退化**的通用模式（不引编译期依赖、
   不加载对方的类、失败留痕），照抄一份换掉类名/方法形状即可。
6. **补发 MisakaNet 教训**：见 `port-1.21.1/misakanet-pending/README.md`
   （先 `search` 查重 → 逐条 `write_lesson`；若仍超时改用合并的 `submit_intake`）。
