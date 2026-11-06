# 构建与测试

## 依赖

`build.ps1` 需要本机已有以下内容：

- Slay the Spire 的 `desktop-1.0.jar`；
- Workshop 中的 BaseMod 和 ModTheSpire JAR；
- 能运行 `--release 8` 的 JDK；
- 如果运行测试，需要 PATH 中的 `ffmpeg.exe` 和游戏目录中的 Java 运行时。

这些依赖不会进入 Git 仓库。脚本没有个人目录默认值，使用 `-GameDir`、`-WorkshopDir` 和 `-JdkDir` 显式指定本机位置，也可以通过对应的 `BATTLEAUDIO_*` 环境变量提供默认值。

## 构建模式

仅编译和打包：

```powershell
./scripts/build.ps1 -GameDir $game -WorkshopDir $workshop -JdkDir $jdk -SkipTests
```

完整测试还会编译 `src/test/java`，运行配置、规则、OpenAL、ModTheSpire 补丁及音乐包直接读取/编辑/更新/复制/退订检查，并用临时工作区验证 Workshop 发布 Skill 的仅准备模式；不会调用上传命令。桌面音频烟测使用脚本生成的短 PCM 测试音调；不会读取仓库中的真实音乐。真实游戏烟测需要额外的游戏安装、Workshop 依赖和可用显示/音频环境。默认工坊包扫描使用临时 `appworkshop_646570.acf` 与合成媒体进行隔离测试；只有显式选择私人订阅测试时才读取指定的真实工坊内容。

发布向导测试使用合成导出包和模拟 Steam 回调，覆盖创建、协议确认、失败恢复、已有项目关联和更新，不创建或修改真实工坊项目。游戏烟测可只读检查已初始化的 Steam 会话；端到端真实上传仍需玩家明确选择目标并确认。

游戏内音乐包操作和发布向导均随游戏的 24 种语言切换。构建测试检查各语言资源、关键操作文字、数量占位符、发布错误和路径保留；真实游戏语言烟测另行检查界面与字体。玩家已有的音乐包名、规则名和文件名不会随语言切换被改写。

精英及首领筛选、旧音乐包默认行为、淡入淡出与画面范围包含在自动测试中。游戏烟测的 `-EncounterSmoke` 使用隔离配置逐一检查原版战斗组合；`-KillFilterSmoke` 检查普通／中毒击杀、最后一只、错误目标与重复死亡；`-FilterSmoke` 检查两个设置入口的界面与画面范围，必要时可加 `-Fullscreen` 验证全屏。测试不读取正式音乐包或写入正式存档。

`-BundleDecoder` 只适合在本机已有合规 FFmpeg 时使用。公开源代码和默认构建不会携带解码器。

仓库不包含私人封面图片。构建时若没有 `src/main/resources/battleaudio/workshop-default-cover.jpg`，发布向导会生成简洁的备用封面；玩家仍可自行选择图片。提供该资源的正式构建会使用指定图片。

## 输出

输出位于 `build/` 和 `dist/`，均被 `.gitignore` 排除。发布 JAR 应作为 GitHub Release 附件单独审核第三方许可，不要把本地 JAR、游戏资源或音频提交进源码仓库。
