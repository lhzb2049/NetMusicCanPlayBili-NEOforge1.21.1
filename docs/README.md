# docs/ 索引

本目录存放本工程的游玩说明与**证据链文档**。除 `play-guide.md` 为本工程新写外，其余三份是从上一级工作区**原样拷贝**的历史文档，原文均保留在原位置。

| 文件 | 内容 | 原始位置 |
| --- | --- | --- |
| `play-guide.md` | 1.21.1 游玩指南（本工程新写，事实取自源码注册表） | 本工程新写 |
| `SABLE-AUDIO-FINDINGS.md` | Sable 物理化结构音频修复的完整证据链（§1–§13） | `port-1.21.1/SABLE-AUDIO-FINDINGS.md` |
| `HANDOVER.md` | Sable 修复那一轮的转交文档 | `port-1.21.1/HANDOVER.md` |
| `HANDOVER-NCPB-1.21.1-src.md` | **本轮**源码构造任务的转交文档：目标/验收判据、怎么跑、四类"源码写不出来"的坑、工具自身的坑、下一步、已验/未验 | `HANDOVER-NCPB-1.21.1-src.md` |

## 阅读顺序

1. 想上手玩：`play-guide.md`。
2. 想动这个工程（编译、打包、比对）：`HANDOVER-NCPB-1.21.1-src.md` —— 里面的「别踩清单」和「环境事实」是硬约束。
3. 想知道 26 个注入类与 Sable 修复的来历：`SABLE-AUDIO-FINDINGS.md` 与 `HANDOVER.md`。

## 已知注意点

- 三份拷贝文档里保留了**绝对路径**（形如 `D:\work\...`）与对**仓库外目录**的引用（`port-1.21.1/`、`../net_music_can_play_bili-0.7.9-beta+neo1.21.jar`）。这是原文的取证记录，刻意不做改写；在本仓库内它们只能作为历史线索，不能当作可复现的路径。
- `HANDOVER-NCPB-1.21.1-src.md` 里「本机无网络、无 Gradle」的环境结论**已过期**：实测网络可达（`github.com`、`maven.neoforged.net`、`repo1.maven.org` 的 443 均可连）。Gradle 仍未安装。
- 该文档中「与原 jar 的三重比对」「故障注入」两项验收**尚未完成**。
