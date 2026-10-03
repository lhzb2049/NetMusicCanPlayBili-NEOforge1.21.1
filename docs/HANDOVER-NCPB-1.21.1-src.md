# 转交：把 `net_music_can_play_bili-0.7.9-beta+neo1.21.jar` 构造为 1.21.1 NeoForge 源码工程

> 面向**下一个会话**（无上下文）。全部路径相对工作区 `D:\work`。
> 本文件是入口；工程内还有 `tools/` 两个脚本与 `out-srccompile/` 的台账/日志作为证据。

---

## 0. 一句话现状

源码工程已建在 [`mc/NetMusicCanPlayBili-0.7.9-beta+neo1.21/`](mc/NetMusicCanPlayBili-0.7.9-beta+neo1.21)。

| 验收项 | 状态 |
| --- | --- |
| ① 736 个源文件（710 反编译 + 26 我们注入的类）离线 javac 对**真实 1.21.1-NeoForge_21.1.252 类路径**编译 | ✅ **0 错误** |
| ② 编译产物规模 | ✅ **1444 个 .class / 736 个顶层类 —— 与原 jar 的 1444 / 736 完全一致** |
| ③ 打包成 jar + 与原 jar「顶层类清单 / 每类成员签名 / 资源清单与字节」三重比对 | ❌ **未做**（下一会话的主任务） |
| ④ 每条比对配故障注入（证明会说"不"） | ❌ 未做 |
| ⑤ `build.gradle` / `settings.gradle` / `gradle.properties` / wrapper | ⚠️ 文件已按参考工程写好，但**本机无网络、无 Gradle，无法验证** |

---

## 1. 目标与验收判据（原任务原文）

> 模仿 `D:\work\mc\NetMusicCanPlayBili-0.7.10-beta` 中的结构，把
> `D:\work\mc\net_music_can_play_bili-0.7.9-beta+neo1.21.jar`（含本次会话的 Sable 修复）进行源码构造。
> 验收 = 离线 javac 对着游戏真实 1.21.1-NeoForge_21.1.252 classpath 编译 0 错误
> + 产出 jar 与原 jar 做「顶层类清单 / 每类成员签名 / 资源清单与字节」三重比对，
> 且每条比对都要能证明它会说"不"（故障注入）。

参考工程 `mc/NetMusicCanPlayBili-0.7.10-beta` 是作者 **MC 26.1.2** 分支的源码（MDG 2.0.141 + `src/main/{java,resources,templates}` + `tools/` + 自带 4 个 verify 任务）。本次只**模仿它的目录结构**，版本参数换成 1.21.1 / NeoForge 21.1.252。

---

## 2. 交付物与路径

| 类别 | 路径 | 说明 |
| --- | --- | --- |
| 源码工程根 | `mc/NetMusicCanPlayBili-0.7.9-beta+neo1.21/` | 默认 `git` 友好（含 `.gitignore`、`LICENSE` 从参考工程拷来，MIT） |
| 源文件 | `…/src/main/java/` | **736 个 .java**（710 反编译 + 26 注入类），包结构照 jar |
| 资源 | `…/src/main/resources/` | **140 个文件**（assets/data/native/logo.png/mixins.json/META-INF 等；jar 里 253 条"非 class 条目"含目录条目） |
| 离线编译工具 | `…/tools/build_src.py` | 组类路径 → javac → 错误台账（`out-srccompile/errors.{md,json}` + `javac.log`） |
| 离线 AT 工具 | `…/tools/apply_at.py` | 应用 NeoForge 自带 access transformer → `out-srccompile/ated/`（**编译前必须先跑**） |
| 原 jar（对比基准） | `mc/net_music_can_play_bili-0.7.9-beta+neo1.21.jar` | 1697 条 = 1444 class（736 顶层）+ 253 其它；含本会话的 Sable 修复 |
| 反编译树（源） | `port-1.21.1/decomp/` | 710 个 .java，**与原 jar 顶层类一一对应**（差集恰好是 26 个注入类） |
| 注入类的真源码 | `port-1.21.1/t4/src/` | 31 个 .java，其中 26 个在 jar 里（另 5 个是未发布的诊断探针） |
| Sable 修复的完整证据链 | `port-1.21.1/SABLE-AUDIO-FINDINGS.md`（§1–§13）、`port-1.21.1/HANDOVER.md` | 与本次源码构造**不同**的任务，但 26 个注入类的来历在那里 |
| 参考工程结构 | `mc/NetMusicCanPlayBili-0.7.10-beta/` | 只读参考（26.1.2 分支） |

---

## 3. 怎么跑

```powershell
# 1) 先应用 NeoForge 的 AT（必须先跑；否则会看到一堆假的 protected 访问错误）
python mc\NetMusicCanPlayBili-0.7.9-beta+neo1.21\tools\apply_at.py
#    期望：[3a] 类级/通配 70 个类已登记；成员级命中 673 条；未命中 0；类找不到 0
#          [3b] InnerClasses 同步：改了 ~70 个条目
#          [4] 自检 RenderStateShard / RenderStateShard$ShaderStateShard 均为 public

# 2) 编译（退出码 0 = 0 错误；非 0 = 错误类别数）
python mc\NetMusicCanPlayBili-0.7.9-beta+neo1.21\tools\build_src.py
#    期望：编译通过：736 个源文件 0 错误

# 覆盖游戏目录（本机默认按中文目录名拼，见脚本头注释）
python …\tools\build_src.py --mc "D:\<你的游戏目录>\.minecraft"
```

**类路径怎么来的**（别改成"扫 `libraries/` 目录"）：`build_src.py` 从
`versions/1.21.1-NeoForge_21.1.252/logs/debug.log` 里 `Located paths when launch context was created`
那一行取**本实例真实类路径**（83 条），再补 `neoforge-<ver>-client.jar` / `-universal.jar` / `client-…-srg.jar` /
`mods/*.jar`（13 个）。`libraries/` 与 26.1.2 实例共用，按文件名乱扫会拿到错版本的 FML（`port-1.21.1/t4/build_mixins.py:614` 有同样的注记）。

---

## 4. 环境事实（先知道这些能省一天）

1. **没有网络**：`maven.neoforged.net`、`repo1.maven.org` 都不可达；**没有 gradle**、没有 `~/.gradle`。
   ⇒ Gradle/MDG 构建在本机**一步都跑不动**，所以验收只能靠"离线 javac + 自写比对"。
2. **access transformer 必须自己应用**。真实构建（NeoForm/MDG）在编译前会把 NeoForge 的
   `neoforge-<ver>-universal.jar!META-INF/accesstransformer.cfg`（**519 条规则**，其中 673 条成员级规则命中）
   应用到 Minecraft 类上。不应用就会看到 `ShaderStateShard has protected access in RenderStateShard`、
   `MenuType(...) has private access` 这类**假错误**。
   * 只改类/成员的头标志**不够**：javac 判定 `Outer.Inner` 跨包可访问性读的是 **InnerClasses 属性表**，
     而且读的是**它加载到的那份 class 文件**里的表（外层类、嵌套类自身、任何引用它的类都可能有这张表）。
   * `ATED` 目录会排在编译类路径**最前面**，所以它里面每一份字节都必须来自**优先级最高**的来源：
     `neoforge-<ver>-client.jar`（NeoForge 补丁过的原版）> `-universal.jar` > `srg.jar`。
     反过来做会把 NeoForge 的扩展方法（`BlockGetter.getCapability`、`CreativeModeTab.builder()`）整片遮住。
3. **反编译树是完整的**：`port-1.21.1/decomp/` 710 个 .java，与 jar 里 736 个顶层类相比只缺我们注入的 26 个
   ⇒ "某个方法没有调用方"这类结论可信。
4. 这个 jar 里带了**打包进来的 scene-editor**（`com/zhongbai233/scene_editor/**` 39 个类）与 `port/shim/**`，
   都按普通源码还原（不引外部依赖）。
5. **编译产物 1444 个 .class（736 顶层）与原 jar 完全一致** —— 说明反编译→重编译没有丢类/丢内部类。

---

## 5. 这个 jar 的四类"源码写不出来"的坑（都已修，含依据）

### 5.1 `VideoBillboardState`：包私有，却被其它包当公共 API 用

* jar 里它的 `access_flags = 0x0420`（**包私有** abstract）；
* 但它的 12 个嵌套类型都是 public，被 `client`、`client.renderer`、`client.debug`、`client.sync` 等
  **4 个以上不同包**的类引用；
* 字节码层面这些引用指向的是**嵌套类**（public），主类几乎只出现在常量池/`InnerClasses` 属性里，
  所以**运行时没事**（JVM 只按被引用的那个类做访问检查），但**源码写不出来**。
* 修法：源码里声明为 `public abstract class`（文件头有注释说明）。**这是本轮已知的唯一一处与 jar 不同的访问标志**，
  三重比对工具里要把它列入白名单（并解释原因）。

### 5.2 三个 Mixin 类在 jar 里**没有 superclass**

`BigMegaphoneScreenMixin` / `EntityTurnRoamingMixin` / `TileEntityMusicPlayerMixin` 在原 jar 里
`superclass = java/lang/Object`（移植时被剥掉了，原工程应是 `extends <目标类>`），于是源码里
`((Screen)this)`、`this instanceof LocalPlayer`、`(BlockEntity)this` 全都非法（两个类互不为子类型）。

* 修法（**保持字节码逐指令一致**）：经 Object 桥接 —— `((Screen)(Object)this)`、
  `((Object)this) instanceof LocalPlayer`、`(BlockEntity)(Object)this`。
  字节码实证：原方法里就是 `aload_0; checkcast Screen` / `aload_0; instanceof LocalPlayer` / `aload_0; checkcast BlockEntity`。
* 另一种可行修法是补 `extends`，但那会**改动类的 superclass**（和 jar 不一致，且 Mixin 对 mixin 层次有校验），所以没采用。

### 5.3 反编译伪影（7 处，均已按字节码依据修正）

| 文件 | 伪影 | 依据 / 修法 |
| --- | --- | --- |
| `link/AudioPlaybackIndexSavedData.java`、`network/{MP4PlaybackSavedData,PadDocumentSavedData,PadMapScopeSavedData}.java` | `new Factory(…)` 丢了菱形 ⇒ 按 raw 类型检查 ⇒ "Object cannot be converted to CompoundTag" | 补 `new Factory<>(…)`（擦除相同） |
| `client/renderer/video/{LiveVideoPlaybackAnchor,TurntableVideoPlaybackAnchor}.java` | `.<X>map(value -> (X)value)` 非法（X 是自己的类型） | 原 lambda 就是**恒等函数**（字节码 `lambda$replacementOwnerKey$0` 只有 `aload_0/areturn`）⇒ 写成 `.<Object>map(value -> value)` |
| `client/renderer/video/Nv12PboUploader.java:177` | `return (boolean)error;`（int 局部量） | 该 return 对应 `iload 20; ireturn`，而能走到它的唯一路径上 slot 20 只被 `iconst_0; istore 20` 写过 ⇒ `return false;` |
| `client/renderer/video/VideoCandidateDecodeRunner.java:302` | raw `CompletableFuture` ⇒ lambda 参数擦除成 `(Object,Object)` | `whenComplete` 第二参本就是 Throwable ⇒ 加 `(Throwable)error` |
| `NetMusicCanPlayBili.java:76` | `(BlockEntityType)…` 丢了类型参数 ⇒ 推断退化成 `BlockEntity` ⇒ `getItemHandler()` 找不到 | 字节码实证合成 lambda 参数就是 `ModernTurntableBlockEntity` ⇒ 恢复参数化转换 + 补 import |
| `client/renderer/video/VideoBillboardUploadSupport.java:67` | lambda 捕获了**非 effectively final** 的 `process` | 字节码实证合成方法 `lambda$decodeTestPatternLoop$0(Process)` ⇒ 用 final 副本捕获 |

### 5.4 其余零星修正

* `VideoBillboardState` 之后，所有 `VideoBillboardState.X` 引用点（30 条错误）随之消失；
* `init/ModMenus.java` 的 `new MenuType(…)` 报"private access" —— 由 AT 的
  `public …MenuType <init>(Lnet/minecraft/world/inventory/MenuType$MenuSupplier;…)V` 规则解决（不需要改源码）。

---

## 6. 工具自身的坑（我踩过的，别重犯）

| # | 坑 | 症状 | 正解 |
| --- | --- | --- | --- |
| 1 | **读错 class 文件偏移**：`access_flags` 在常量池**之后**（变长），我先按固定偏移 6 读，读到的是 `major_version`(=0x41=65) | 得出"反编译器把 public 丢了"的**假结论**，还顺带产出 582 条假不一致 | 必须先解析常量池，再读 `access_flags` |
| 2 | 常量池里 Utf8 条目的 tag 我存成了**字符串** `"Utf8"`，而查找函数按整数 `1` 比对 | 属性名/成员名永远读不出来 ⇒ 673 条成员级 AT 规则全部"未命中" | 统一存整数 tag，并区分 `utf8()`（Class 条目）与 `utf8_name()`（Utf8 条目） |
| 3 | 用 `utf8()`（只认 Class 条目）去读**字段/方法名**（Utf8 条目） | 同上，成员规则全不命中 | 用 `utf8_name()` |
| 4 | `ATED` 的来源优先级写成 `srg` 优先 | NeoForge 补丁过的原版类被遮住 ⇒ `getCapability`/`CreativeModeTab.builder()` 找不到 | `neoforge-client` > `-universal` > `srg` |
| 5 | AT 里**每条规则都重新 `z.read(entry)`** | 同一类的后一条规则用原始字节覆盖前一条的补丁 ⇒ `RenderStateShard.NO_CULL` 仍是 protected | 一个类读一次、所有规则作用在同一份 bytearray、最后统一落盘 |
| 6 | 把"登记需要同步 InnerClasses 的类名"这句话**误缩进**进 `if changed[0]:` 里 | 头标志本来就是 public、只有 InnerClasses 表是 protected 的类（`TexturingStateShard`）永远不被登记 ⇒ 编译一直剩 2 条 protected 错误 | 登记必须在 `if changed[0]` **之外** |
| 7 | `for … else:` 缩进错误 | `else` 变成了 `for` 的 else 子句，`member` 为 None 时崩 | 逐段核对缩进（改完就用 `indent=` 打印核对了一遍） |

**共同点**：这些错都"响亮地错"（脚本报错或编译报错），没有一次把坏结果当成好结果交付；
但第 1 条的错**差点**让我写进文档一个假结论（"反编译器丢了 public"）——**结论必须落在字节码上，不能落在"我以为的偏移"上**。

---

## 7. 下一步（按优先级，可直接开工）

1. **打包**：写 `tools/pack_src.py`，把 `out-srccompile/classes/` + `src/main/resources/` 打成一个 jar
   （`META-INF/MANIFEST.MF` 照原 jar；注意 `META-INF/neoforge.mods.toml`、`mixins.json`、
   `accesstransformer.cfg` 都已在 resources 里）。
2. **三重比对门禁** `tools/verify_src.py`（本轮的核心剩余工作）：
   * (a) **顶层类清单**：与原 jar 逐名比对（`$` 内部类也要比，本轮已人工核对 1444/736 一致）；
   * (b) **每类成员签名**：对每个类跑 `javap -p`（两边都是官方映射，可直接比文本），比对字段/方法名+描述符+访问标志；
     已知需白名单：`VideoBillboardState` 的 `ACC_PUBLIC`（§5.1）。其余应当**零差异**；
   * (c) **资源清单与字节**：原 jar 的所有非 class 条目（跳过 `MANIFEST.MF`）逐一比对字节哈希。
   * 每条比对都要有 `--fault=` 故障注入证明它会说"不"（照 `port-1.21.1/t4/run_faults.py` 的写法：
     **同时**要求 `rc != 0` **且**失败理由对得上，并断言产物未被改动）。
3. **Gradle 骨架**：`build.gradle` 里的 MDG 版本（`2.0.78`）与依赖坐标（`maven.modrinth:net-music:…`）
   都是**照参考工程推断**的，联网机器上必须 `gradlew build` 复核一次；`gradle.properties` 里已按
   1.21.1 / 21.1.252 写好，`neoForge.mods.toml` 目前是 jar 里的成品（没用 `src/main/templates` 模板展开，
   参考工程用的是模板 + `processResources` 展开 —— 要改成那样需同步 §2 的资源路径）。
4. **游戏内验证（可选但最有说服力）**：用第 1 步打出的 jar 替换 `mods/` 里的原 jar，
   在游戏里过一遍"结构上有声/有画面 + 本轮 13 条门禁"的效果。注意 `mixins.json` 里 17 个 Sable 修复
   Mixin 的 `@Mixin` 注解都在源码里，Mixin 会照常应用。
5. **补 `docs/`**：参考工程有 `docs/play-guide.md` 等，本工程 `docs/` 是空的。

---

## 8. 已验 / 未验

**已验（离线，可复现）**
* `tools/apply_at.py`：519 条规则 → 类级/通配 70 个类登记、成员级 673 条命中、0 条未命中、0 条类找不到；
  InnerClasses 同步 ~70 个条目；自检 `RenderStateShard` 与 `$ShaderStateShard` 均为 public。
* `tools/build_src.py`：**736 个源文件 0 错误**（rc=0，9.5s，97 个 jar 的类路径）。
* 规模一致：编译产物 **1444 个 .class（736 顶层）** == 原 jar **1444 / 736**。
* 反编译树覆盖：jar 顶层类 736 = decomp 710 + 注入 26，**差集为空**。

**未验**
* 打包后的 jar 与原 jar 的三重比对（顶层类/成员签名/资源字节）——**本轮没做**；
* 每条比对的故障注入；
* Gradle/MDG 构建（本机无网络，做不到）；
* 游戏内运行（编译通过 ≠ 运行正常；尤其 Mixin 的 `@Mixin` 目标与 `mixins.json` 在**重编译产物**里是否仍被 Mixin 接受）；
* `VideoBillboardState` 改成 public 之后**运行时**是否真的无影响（推理：放宽访问只会更宽松，但未实跑）。

---

## 9. 别踩清单（一句话版）

1. 编译前**先跑 `apply_at.py`**；`ATED` 的来源优先级是 `neoforge-client > -universal > srg`。
2. 改 AT 工具时记住：**一个类读一次字节**、**登记不能在 `if changed[0]` 里**、**InnerClasses 表要全量同步**。
3. 类路径一律从 `debug.log` 的启动类路径取，别扫 `libraries/`。
4. 反编译源码里任何"看起来不可能编译"的地方，都先 `javap -c` 看**原字节码**再改，
   并把理由写在该行的注释里（本轮 14 处修复都这么做的）。
5. 交付物里**不要写死绝对路径**：`tools/*.py` 用 `--mc` / `NCPB_MC_DIR` 覆盖，默认值只用码点拼中文目录名。
