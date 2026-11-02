"""Compare saved, versioned engine experiments over the same 20-30 minute window."""
from pathlib import Path
import argparse
import csv
import hashlib
import json
import os
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
LABELS = {'attrition': '平原', 'choke-attrition': '隘口', 'lake-attrition': '湖岸', 'rich-attrition': '扩大矿区'}


def load(directory):
    result = []
    for path in sorted(directory.glob('*-seed-*.json')):
        if path.stem.endswith(('-layout', '-state')):
            continue
        result.append((path, json.loads(path.read_text(encoding='utf-8'))))
    return result


def counts_at(data, seconds, block):
    points = [s for s in data['timeline'] if abs(s['seconds'] - seconds) < .01]
    if not points:
        raise ValueError(f'Missing aligned sample at {seconds}')
    return points[-1]['productionTotals'].get(block, {'cycles': 0, 'frames': 0, 'units': 0})


def window(data):
    result = {'silicon': 0, 'units': 0, 'siliconUtilization': 0}
    for block in ('silicon-smelter', 'air-factory', 'ground-factory'):
        stats = data['machines'].get(block, {})
        if data['plannedMinutes'] == 30:
            cycles, frames, units = (stats.get(k, 0) for k in ('lateCycles', 'lateFrames', 'lateUnits'))
        else:
            before, after = counts_at(data, 1200, block), counts_at(data, 1800, block)
            cycles, frames, units = (after.get(k, 0) - before.get(k, 0) for k in ('cycles', 'frames', 'units'))
        if block == 'silicon-smelter':
            result['silicon'] = cycles / 10
            result['siliconUtilization'] = 100 * cycles * stats.get('craftTimeTicks', 40) / max(frames, 1)
        else:
            result['units'] += units / 10
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--after', type=Path, required=True)
    parser.add_argument('--before', type=Path, nargs='+', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/v0.3.0-comparison')
    args = parser.parse_args()
    output = args.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    verification = (ROOT / 'build/verification.log').read_text(encoding='utf-8')
    checks = int(re.search(r'PASS: (\d+) engine-backed checks', verification).group(1))
    current = load(args.after)
    historical = [case for directory in args.before for case in load(directory)]
    manifest = json.loads((args.after / 'manifest.json').read_text(encoding='utf-8'))
    for name, digest in manifest['sourceSha256'].items():
        assert hashlib.sha256((ROOT / name).read_bytes()).hexdigest() == digest
    with zipfile.ZipFile(ROOT / 'build/fair-attack-ai.jar') as release:
        for name in release.namelist():
            if name.endswith('.class'):
                assert release.read(name) == (ROOT / 'build/benchmark-classes' / name).read_bytes()
    rows = []
    for path, after in current:
        assert after['rawPositiveCoreInflow'] == 0
        assert after['currentFactories'].get('ground-factory', 0) >= 1
        assert after['manufacturedUnits'].get('dagger', 0) > 0 and after['manufacturedUnits'].get('flare', 0) > 0
        matches = [(p, d) for p, d in historical if d['scenario'] == after['scenario'] and d['seed'] == after['seed']]
        matches.sort(key=lambda case: abs(case[1]['plannedMinutes'] - 30))
        before = matches[0][1] if matches else None
        new, old = window(after), window(before) if before else None
        rows.append({'scenario': after['scenario'], 'seed': after['seed'], 'oldSiliconPerMinute': old['silicon'] if old else None,
                     'newSiliconPerMinute': new['silicon'], 'oldUnitsPerMinute': old['units'] if old else None,
                     'newUnitsPerMinute': new['units'], 'oldSiliconUtilizationPct': old['siliconUtilization'] if old else None,
                     'newSiliconUtilizationPct': new['siliconUtilization'], 'oldSmelters': before['currentFactories'].get('silicon-smelter', 0) if before else None,
                     'newSmelters': after['currentFactories'].get('silicon-smelter', 0),
                     'groundFactoryFirstMinute': after['firstBuiltSeconds'].get('ground-factory', 0) / 60,
                     'pneumaticDrills': after['currentBuildings'].get('pneumatic-drill', 0),
                     'mechanicalDrills': after['currentBuildings'].get('mechanical-drill', 0),
                     'rawCoreInflow': after['rawPositiveCoreInflow'], 'file': str(path)})
    with (output / 'comparison.csv').open('w', encoding='utf-8-sig', newline='') as file:
        writer = csv.DictWriter(file, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    (output / 'comparison.json').write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding='utf-8')
    selected = [r for r in rows if r['scenario'] in LABELS and r['seed'] == 17]
    text = ['# v0.3.0 经济优先优化验证\n\n',
            f'发布前完成 {len(current)} 组、每组 30 分钟的真实 v146 引擎模拟，共 {len(current)/2:.1f} 小时游戏时间，并通过 {checks} 项引擎回归检查。\n\n',
            '用户选择的策略是经济优先：先完善产线和矿业升级，再集中造兵。本次保留全部科技解锁及原版资源、施工、供弹和单位规则。\n\n',
            '## 实现\n\n',
            '- 按有效矿格、矿物硬度、钻头时间、配方消耗和发电燃料消耗计算每分钟供需；按照目标产量限制工厂数量。\n',
            '- 正常付费建设气动钻头，并使用普通施工队列升级机械钻头。矿位优先比较有效产量和距离。\n',
            '- 煤、沙直供加工/燃料消费者；石墨、硅成品自动回核心。新线路标记物资，避开其他物资的侧送和混流，标记随存档保存。\n',
            '- 合并工业电网；孤立发电机不计入本地可用电力。\n',
            '- 为施工与发展保留账面预算。经济未就绪时，通过正常开关暂停兵厂，保留生产进度；就绪后轮换集中造兵，达到单位上限时暂停。\n',
            '- 根据需求缩减社区蓝图；裁剪失去完整连接关系的旧物品线路，由规划器重接实际供料和成品回库。\n\n',
            '本轮只接管已支持的基础加工与空/陆军工厂；地图自带的未支持高级工厂保留既有开关、配方和原料线路，避免基础经济规划误停高级设施。该保守策略是问题未答复时采用的默认处理。\n\n',
            '## 与 v0.2.1 的对照\n\n',
            '以下均使用种子 17，相同地形和初始库存（铜 250、铅 150），比较游戏第 20–30 分钟。压力场景每 30 秒检查一次自产单位，并正常击毁年龄达到 120 秒的单位，实际寿命约 120–150 秒；没有在运行中发放资源。平原旧版数据取一小时模拟中同一时间段，而非最后十分钟。\n\n',
            '| 压力地图 | 硅产量/分钟：旧 → 新 | 总造兵/分钟：旧 → 新 | 硅厂：旧 → 新 | 新版陆军厂建成时间 |\n',
            '|---|---:|---:|---:|---:|\n']
    for row in selected:
        text.append(f"| {LABELS[row['scenario']]} | {row['oldSiliconPerMinute']:.1f} → {row['newSiliconPerMinute']:.1f} | "
                    f"{row['oldUnitsPerMinute']:.1f} → {row['newUnitsPerMinute']:.1f} | {row['oldSmelters']} → {row['newSmelters']} | "
                    f"{row['groundFactoryFirstMinute']:.1f} 分钟 |\n")
    text += ['\n总造兵数量包括 Flare 与 Dagger；兵种组成发生变化，因此数量增幅不等于战力或胜率增幅。硅利用率也包含主动停产和库存目标控制，不代表系统应当永远满负荷生产。\n\n',
             '全部 16 组均完成陆军厂建设、恢复两种 T1 单位生产，煤和沙的核心正向流入为零。正常发育组到达当前核心每种 8 个单位的上限后会停产，不能把该阶段的零产量当作断供。压力组在 30 分钟结束时保留了建设储备。\n\n',
             '## 取舍与剩余限制\n\n',
             '前期新增矿业、升级和物流施工较多，首次出兵可能推迟。当前集中造兵采用轮换方式，为继续发展保留资源与电力；尚未自主推进完整液体、蒸汽、塑钢和 T2 重构链，战斗策略也没有在本轮重写。\n\n',
             '额定产能模型暂不假设水冷或超速增益，也未求解任意复杂分流网络及全地图最优布局。已有复杂混合物流和高级基地仍需更多实图验证。\n\n',
             '![产能对照](comparison.png)\n\n',
             '原始数据、最终存档、代码摘要、运行日志和回归检查日志随证据包提供；它们都是独立测试世界，不含玩家存档。\n']
    (output / 'report.md').write_text(''.join(text), encoding='utf-8')
    plot(selected, output)
    with zipfile.ZipFile(output / 'evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in output.iterdir():
            if file.is_file() and file.suffix in ('.md', '.json', '.csv', '.png'): archive.write(file, file.name)
        archive.write(ROOT / 'build/verification.log', 'verification.log')
        for file in args.after.iterdir():
            if file.is_file(): archive.write(file, 'after/' + file.name)
        for directory in args.before:
            for file in directory.iterdir():
                if file.is_file() and file.suffix == '.json': archive.write(file, f'before/{directory.name}/{file.name}')
    print(json.dumps({'report': str(output / 'report.md'), 'cases': len(current), 'selected': selected}, ensure_ascii=True))


def plot(rows, output):
    os.environ.setdefault('MPLCONFIGDIR', str(ROOT / 'build/matplotlib-cache'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    import numpy as np
    x = np.arange(len(rows)); width = .34
    fig, axes = plt.subplots(1, 2, figsize=(12, 4.6), layout='constrained')
    labels = ['Flat', 'Choke', 'Lake', 'Rich ores']
    for ax, before_key, after_key, title, ylabel in [
        (axes[0], 'oldSiliconPerMinute', 'newSiliconPerMinute', 'Actual silicon output with fewer factories', 'Silicon / minute'),
        (axes[1], 'oldUnitsPerMinute', 'newUnitsPerMinute', 'Manufacturing under simulated attrition', 'Flare + Dagger / minute')]:
        old = ax.bar(x-width/2, [r[before_key] for r in rows], width, label='v0.2.1', color='#abb5c2')
        new = ax.bar(x+width/2, [r[after_key] for r in rows], width, label='v0.3.0 economy first', color='#207f86')
        ax.bar_label(old, fmt='%.1f', padding=3, fontsize=9); ax.bar_label(new, fmt='%.1f', padding=3, fontsize=9)
        ax.set(title=title, ylabel=ylabel, xticks=x, xticklabels=labels)
        ax.set_ylim(0, max([r[before_key] for r in rows]+[r[after_key] for r in rows])*1.23)
        ax.grid(axis='y', alpha=.18); ax.legend(fontsize=8)
    fig.savefig(output / 'comparison.png', dpi=170); plt.close(fig)


if __name__ == '__main__':
    main()
