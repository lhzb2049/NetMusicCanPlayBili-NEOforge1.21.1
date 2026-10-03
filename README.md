# NetMusicCanPlayBili 1.21.1（0.7.9-beta+neo1.21）源码构造

把 `../net_music_can_play_bili-0.7.9-beta+neo1.21.jar`（已含 Sable 物理化结构修复）还原成**可编译的源码工程**，
目录结构模仿 `../NetMusicCanPlayBili-0.7.10-beta`（作者 MC 26.1.2 分支）。

> **转交文档在上一级目录**：[`../HANDOVER-NCPB-1.21.1-src.md`](../HANDOVER-NCPB-1.21.1-src.md)
> —— 目标/验收判据、怎么跑、环境事实、这个 jar 的四类坑、工具自身的坑、下一步、已验/未验都在那里。
> 先读它，再动这个工程。

## 现状

| 项 | 状态 |
| --- | --- |
| `src/main/java` | **736 个 .java** = 反编译树 710 + 我们注入的 26（来自 `port-1.21.1/t4/src`） |
| `src/main/resources` | 140 个文件（assets / data / native / mixins.json / META-INF 等，全部从 jar 抽出） |
| 离线编译 | ✅ **0 错误**（`python tools/apply_at.py` → `python tools/build_src.py`） |
| 产物规模 | ✅ 1444 个 .class / 736 顶层类，与原 jar **完全一致** |
| 与原 jar 的三重比对 | ❌ 未做（顶层类清单 / 每类成员签名 / 资源字节） |
| Gradle 构建 | ⚠️ 文件已写好但**无法验证**（本机无网络、无 Gradle） |

## 怎么跑

```powershell
python tools\apply_at.py     # 必须先跑：应用 NeoForge 自带 AT（本机无网络，真实构建的这一步要自己补）
python tools\build_src.py    # 组类路径 → javac → 错误台账（out-srccompile/）
```

两个脚本都支持 `--mc "<游戏目录>\.minecraft"` 或环境变量 `NCPB_MC_DIR` 覆盖默认游戏目录；
类路径一律取自该实例 `logs/debug.log` 里的真实启动类路径（别扫 `libraries/`，那是多版本共用的）。

## 已知与 jar 的唯一差异

`client/renderer/video/VideoBillboardState.java` 声明为 `public`（jar 里是包私有，但它 12 个 public 嵌套类
被 4 个以上**其它包**引用，源码里写不出来）——详见转交文档 §5.1，比对工具需白名单。
