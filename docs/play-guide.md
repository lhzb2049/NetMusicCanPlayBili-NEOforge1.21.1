# Net Music Can Play Bili 游玩指南（1.21.1 / NeoForge）

> 适用版本 `0.7.9-beta+neo1.21.1`。
> **本指南的每一条都取自本工程的源码注册表、配置类与命令注册处**，出处见文末「事实来源」。
> 尚未在游戏内实跑复核的内容统一列在「待复核」一节，不做推测性描述。

## 安装要求

| 项目 | 要求 | 出处 |
| --- | --- | --- |
| Minecraft | `1.21.1` | `neoforge.mods.toml` 的 `versionRange = "[1.21.1,1.22)"` |
| NeoForge | `21.1` 或更高 | `neoforge.mods.toml` 的 `versionRange = "[21.1,)"` |
| Java | 21（Minecraft 1.21.1 自身的运行要求） | 游戏本体要求 |
| NetMusic | `1.5.2` 或更高，**必需** | `neoforge.mods.toml` 中 `modId = "netmusic"`，`type = "required"` |
| Curios | `9.4` 或更高，可选 | `type = "optional"`；装了才能把耳机与全息眼镜放进饰品头部槽 |
| AreaControl | 任意版本，可选 | `type = "optional"`；装了之后公共音频会按区域边界淡出 |

本模组 `modId` 为 `net_music_can_play_bili`，作者 `zhongbai233`，许可证 MIT。

## 内容清单

以下全部来自创造模式标签页 `itemGroup.net_music_can_play_bili` 的实际注册顺序。

### 方块（6 个）

| 注册名 | 说明 |
| --- | --- |
| `modern_turntable` | 现代化唱片机 |
| `lyric_projector` | 歌词投影仪 |
| `video_projector` | 视频投影仪 |
| `speaker` | 音响 |
| `live_streamer` | 直播机 |
| `control_console` | 控制台 |

### 物品（6 个）

| 注册名 | 说明 |
| --- | --- |
| `mp4` | MP4 播放器 |
| `pad` | Pad 平板 |
| `media_management_tool` | 媒体管理工具，最大堆叠 1 |
| `invisible_headphones` | 隐形耳机 |
| `cat_headphones` | 猫耳耳机 |
| `holographic_glasses` | 全息眼镜 |

### 附魔（2 个）

创造模式标签页会直接给出这两个附魔的 1 级附魔书：

- 耳机附魔（`HeadphoneAbility.HEADPHONES_KEY`）
- 全息眼镜附魔（`HolographicGlassesAbility.HOLOGRAPHIC_GLASSES_KEY`）

### 属性（2 个）

`headphones` 与 `holographic_glasses` 两个自定义属性，装备在**头部槽**（`EquipmentSlotGroup.HEAD`）时各 `+1`。

## 快捷键

| 默认按键 | 用途 | 前提 |
| --- | --- | --- |
| `H`（KEYSYM 72） | 打开全息眼镜的屏幕配置界面 | 必须已佩戴带附魔的全息眼镜；否则提示需先装备 |

按键分类为 `key.categories.net_music_can_play_bili.main`，可在原版「按键绑定」里改键。

## 命令

客户端与服务端命令各注册了**两个等价名字**，用哪个都行。

### 客户端：`/netmusicbiliclient` 或 `/ncpbc`

| 子命令 | 用途 |
| --- | --- |
| `status` | 显示播放状态 |
| `hologlass config` / `hologlass test` | 全息眼镜配置 / 屏幕配置测试界面 |
| `pad cache status\|save\|refresh` | Pad 地图缓存状态 / 保存 / 刷新 |
| `video status\|placeholders\|retry` | 视频生命周期状态 / 占位符调试 / 重试失败的视频 |
| `debug playback\|audio\|video` | 播放调试开关，各自支持 `on`、`off`、`ui`、`range`、`both`、`toggle`、`status`、`dump` |
| `bench …` | 性能基准（含 `status`、`reset`、`mark`、`perceived`、`video cpu-bars`、`video bili-real`） |
| `dolby joc on\|off\|toggle\|status`、`dolby objects status`、`dolby source status` | 杜比 JOC 与对象数上限的状态与开关 |

### 服务端：`/netmusicbiliserver` 或 `/ncpbs`

| 子命令 | 用途 | 权限 |
| --- | --- | --- |
| `sources [limit <1–100>]` | 列出音频来源 | 需审计权限 |
| `pad refresh` | 刷新 Pad 服务端状态 | 需刷新权限 |
| `whitelist add <id 或链接>`、`list`、`remove`、`comment`、`export`、`review` | 链接白名单的增删查、备注、导出与复核 | 需白名单管理权限 |

## 配置文件

三项配置位于本模组的 `config` 文件（`enableDebugLog` / `enableLinkWhitelist` / `linkWhitelistContactPlaceholder`）：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `enableDebugLog` | `false` | 是否启用详细的 B 站 API 调试日志 |
| `enableLinkWhitelist` | `false` | 是否启用服务端 BV/av 号与第三方链接白名单 |
| `linkWhitelistContactPlaceholder` | `"OP4"` | 玩家无权自行添加白名单时，拒绝提示里显示的联系人名称 |

## 高级启动参数（`-D...`）

除配置文件外，模组还读取一整套 `ncpb.*` 系统属性，**必须写在 JVM 启动参数里**（游戏内改不了）。常用几个：

| 属性 | 默认值 | 用途 |
| --- | --- | --- |
| `ncpb.bili.sessdata` / `ncpb.bili.cookie` | 空 | 直接注入 B 站登录凭据，跳过扫码 |
| `ncpb.bili.audio.preference` | `auto` | 音频流偏好 |
| `ncpb.bili.live.offline_retry_seconds` | `60` | 直播未开播时的自动探测间隔（秒） |
| `ncpb.video.advanced_features` | `false` | 打开高级视频特性开关 |
| `ncpb.video.turntable.enabled` | `true` | 唱片机视频输出总开关 |
| `ncpb.video.native.hwaccel` | `auto` | 原生解码硬件加速策略 |
| `ncpb.pad.offscreen_scale` | `2` | Pad 离屏渲染倍率 |
| `ncpb.memory.diagnostics` | `false` | 内存诊断报告 |
| `ncpb.memory.protection` | `true` | 原生内存超额保护 |

完整清单见源码中 `util/diagnostics/MemoryProperties`、`bili/BiliApiProperties`、`media/stream/*Properties`、`client/*Properties` 等类，以及 `port` 文档中关于视频管线的记录。

## 待复核

以下内容**本轮没有验证**，不要在文档或对外说明里当成已确认的事实：

- 唱片刻录、B 站扫码登录的具体交互流程：入口由 NetMusic 提供，本模组负责解析与播放；该流程未在本工程实跑。
- 各界面（控制台、Pad、唱片机控制界面）的实际文案与布局。
- 红石与自动化行为。
- 各 Mixin 在**重编译产物**里是否仍被 Mixin 接受（编译通过 ≠ 运行正常）。
- `VideoBillboardState` 改为 `public` 之后的运行时表现（推理上放宽访问只会更宽松，但未实跑）。

## 与 0.7.10（MC 26.1.2 分支）游玩指南的关系

参考工程 `NetMusicCanPlayBili-0.7.10-beta` 自带一份 `docs/play-guide.md`，但那份描述的是 **MC 26.1.2 分支的 0.4.1-beta**（Java 25、Curios 12+、NeoForge 26.x），版本参数与本工程完全不同，**不能直接套用**。本指南以本工程 1.21.1 源码为准，只借用了它的章节结构。

## 事实来源

| 结论 | 来源文件 |
| --- | --- |
| 版本号、依赖范围、许可证、作者 | `src/main/resources/META-INF/neoforge.mods.toml` |
| 方块与物品注册表、创造标签页顺序、附魔书、属性修正 | `src/main/java/.../init/ModBlocks.java`、`init/ModItems.java` |
| 配置三项 | `src/main/java/.../Config.java` |
| 快捷键 | `src/main/java/.../client/HolographicGlassesKeyHandler.java` |
| 客户端命令 | `src/main/java/.../client/NetMusicClientCommands.java` |
| 服务端命令 | `src/main/java/.../server/NetMusicBiliServerCommands.java` |
| `ncpb.*` 启动参数 | 源码中各类 `*Properties.java` |
