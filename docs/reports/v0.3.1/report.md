# v0.3.1 自动矿业与供弹修复验证

本机 Mindustry v146 真实引擎，无图形环境。173 项检查通过；分发 JAR 的 AI 类与下列模拟类逐字节一致，源码摘要匹配。

| 场景（种子 17，各 20 游戏分钟） | 机体采矿秒数 | 核心附近自动供弹炮塔 | 实际弹仓 | 制造部队 |
| --- | ---: | ---: | --- | --- |
| 平原 | 0 | 4 | duo: 30, scatter: 28, duo: 30, scatter: 28 | dagger: 7, flare: 6 |
| 隘口 | 0 | 4 | duo: 30, scatter: 28, duo: 30, scatter: 28 | dagger: 8, flare: 8 |
| 湖岸 | 0 | 4 | duo: 30, scatter: 28, duo: 30, scatter: 28 | dagger: 8, flare: 8 |
| 平原持续战损 | 0 | 4 | duo: 30, scatter: 28, duo: 30, scatter: 28 | dagger: 3, flare: 3 |

四场均建成核心附近的双管炮和分裂炮，所有建成炮塔都有自动供弹链及真实弹药；煤、沙正向入核流量为 0。

定向回归另验证：零库存应急启动仍可恢复矿线；正常开局扩建多座铜铅矿；暂时清空库存而已有自动收入时仍不手挖；供弹带被拆后付费重建；停止 AI 控制器并清空弹仓，矿机和传送带仍能独立补弹；冰雹炮由煤矿、压缩机和输出链自动供弹，核心石墨满库不关停供弹厂。

本轮范围为基础经济与基础弹药。尚未实现所有高级弹药配方；本次验证也不代表任意持续满射速和任意地形下都能保持充足供应。不是胜率评测。

安装时发现同名 0.1.0 与 0.3.0 两份旧档。原版[模组导入和扫描逻辑](https://github.com/Anuken/Mindustry/blob/v146/core/src/mindustry/mod/Mods.java)导入时另取文件名。安装脚本备份并原地更新全部同名归档，确保每份都与已验证 JAR 相同，重启生效。

原始结果：`evidence.zip` 内的 `simulation/`（本地开发结果位于 `test-output/benchmark/v0.3.1-automation-release`）；证据包：`evidence.zip`。
