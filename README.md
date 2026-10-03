# NetMusicCanPlayBili — Minecraft 1.21.1 / NeoForge 21.1.252（第三方移植）

基于原作者 **zhongbai233** 的 NetMusicCanPlayBili 模组，由第三方**反编译还原并移植**到
Minecraft 1.21.1 / NeoForge 21.1.252 的完整源码工程。

本仓库是**非官方移植版**：源码由原模组的发布 jar 反编译重建（710 个文件）并叠加 26 个移植期
注入类，目标是「能编译、能字节级对齐原 jar」，而**不是**作者本人的开发分支。

## 前置要求

| 项目 | 要求 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1 或更高（开发与验收用 21.1.252） |
| Java | 21 |
| **NetMusic** | **必装**，且必须是**对应 1.21.1 的版本**（本模组是它的附属，缺少则无法解析 B 站音频） |
| Curios | 可选，9.4+；装了才能把耳机与全息眼镜放进饰品头部槽 |
| AreaControl | 可选；装了之后公共音频会按区域边界淡出 |

## 版权声明

- **原模组代码版权归原作者 zhongbai233 所有**，遵循 **MIT** 协议。
- 本仓库是**第三方非官方移植版**，与原作者无隶属或授权关系之外的联系；移植过程中对反编译
  产物做的修改、以及本仓库新增的工具链，同样以 MIT 协议提供。
- 原模组名称、图标与美术资源的使用仅为标识该移植版对应的上游作品。

## 免责声明与问题反馈

- 本移植版由第三方维护，**如遇 Bug，请在本仓库的 Issues 中反馈**（<https://github.com/lhzb2049/NetMusicCanPlayBili-NEOforge1.21.1/issues>）。
- **请勿因本移植版的问题打扰原作者。**
- 使用前请自行备份存档；本移植版未经游戏内长时间验证（见下文「验收状态」）。

## 构建

本工程有两条构建路径：**离线 javac**（无网络也能跑）与 **Gradle/MDG**（标准路径）。

### 离线编译（本仓库的主要验收路径）

```powershell
python tools\apply_at.py     # 必须先跑：把 NeoForge 自带的 access transformer 应用到原版类上
python tools\build_src.py    # 组类路径 -> javac -> 错误台账（out-srccompile/）
```

两个脚本都支持 `--mc "<游戏目录>\.minecraft"` 或环境变量 `NCPB_MC_DIR` 覆盖默认游戏目录；
类路径一律取自该实例 `logs/debug.log` 里的**真实启动类路径**（不要改成扫 `libraries/`，
那里与其它 MC 版本共用，按文件名乱扫会拿到错版本的 FML）。

`build_src.py` 带 `-g`，使离线产物与 Gradle 产物的调试信息一致。预期结果：**736 个源文件 0 错误**。

### Gradle / MDG

```powershell
.\gradlew.bat build
```

首次会下载并生成 Minecraft/NeoForge 构件（本机实测约 8 分 43 秒），之后增量构建是秒级。
产物在 `build/libs/`。

## 验收（字节级比对）

```powershell
# 四项比对：顶层类清单 / 每类成员签名 / 资源清单与字节 / invokedynamic 调用点
python tools\verify_src.py --jar <基线jar> --classes out-srccompile\classes --report out-srccompile\verify_src\report.json

# 故障注入：证明上面每一项都会说「不」
python tools\run_faults.py
```

- `verify_src.py` 的判定数据取自**原始 class 字节**（自行解析常量池/字段/方法/BootstrapMethods），
  javap 只用于类清单与留证。
- 已知且已在报告中单独记录的两类「不计入判定」差异：`VideoBillboardState` 的类级 `public`
  放宽（源码层面无法回避，见 [docs/HANDOVER-NCPB-1.21.1-src.md](docs/HANDOVER-NCPB-1.21.1-src.md) §5.1），
  以及基线 jar 的编译环境与 NeoForge 21.1.252 的 `MenuType$MenuSupplier` 元数差异。
- `run_faults.py` 用 6 组注入（改访问标志、删资源、改描述符、删类、改引导句柄、改合成 lambda
  返回类型）逐项验证门禁的鉴别力。

## 验收状态

| 项 | 状态 |
| --- | --- |
| `src/main/java` | 736 个 `.java`（反编译 710 + 注入 26） |
| `src/main/resources` | 140 个文件 |
| 离线 javac | ✅ 736 个源文件 **0 错误** |
| Gradle `build` | ✅ 通过（产物 1444 个 `.class` / 736 个顶层类，与原 jar 一致） |
| 顶层类清单 / 资源字节 | ✅ 双向差集 0；140/140 资源 SHA256 一致 |
| 每类成员签名 | ✅ 1444 个类全部比对，0 差异（1 条已解释的白名单） |
| invokedynamic 调用点 | ✅ 2577 条 BootstrapMethods，0 差异（2 条已解释的环境差异） |
| 故障注入 | ✅ 6/6 组按预期失败 |
| **游戏内运行** | ❌ **未验证** —— 编译与字节级对齐 ≠ 运行正常 |

## 目录结构

```
src/main/java/         736 个源文件
src/main/resources/    140 个资源文件（含 6 个平台的原生解码库）
tools/                 apply_at.py / build_src.py / verify_src.py / run_faults.py
docs/                  游玩指南与移植证据链
out-srccompile/        离线编译产物与比对报告（不纳入版本控制）
```

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/play-guide.md](docs/play-guide.md) | 1.21.1 游玩指南（事实取自源码注册表与配置类） |
| [docs/HANDOVER-NCPB-1.21.1-src.md](docs/HANDOVER-NCPB-1.21.1-src.md) | 源码构造任务的转交文档：验收判据、环境事实、「源码写不出来」的坑、工具自身的坑、已验/未验 |
| [docs/SABLE-AUDIO-FINDINGS.md](docs/SABLE-AUDIO-FINDINGS.md) | Sable 物理化结构音频修复的完整证据链 |
| [docs/HANDOVER.md](docs/HANDOVER.md) | Sable 修复那一轮的转交文档 |
| [docs/README.md](docs/README.md) | docs 目录索引与免责说明 |
