"""Generate an inspectable report and static figures from engine benchmark JSON files."""
from pathlib import Path
import argparse
import csv
import hashlib
import json
import os
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
NAMES = {'flat': '平原', 'choke': '隘口', 'lake': '湖岸', 'rich': '扩大矿区', 'rich-start': '富库存开局',
         'attrition': '平原持续战损', 'duel': '镜像 AI 对战', 'flat-no-blueprints': '平原无蓝图',
         'choke-attrition': '隘口持续战损', 'lake-attrition': '湖岸持续战损', 'rich-attrition': '扩大矿区持续战损',
         'flat-no-blueprints-attrition': '无蓝图持续战损'}


def metrics(d):
    m = d['machines'].get('silicon-smelter', {})
    frames = m.get('lateFrames', 0)
    units = d['machines'].get('air-factory', {})
    valid = d['actualSeconds'] >= d['plannedMinutes'] * 60
    return {'siliconPerMinute': m.get('lateCycles', 0) / 10 if valid else None,
            'siliconUtilizationPct': 100 * m.get('lateCycles', 0) * m.get('craftTimeTicks', 40) / frames if frames else None,
            'flarePerMinute': units.get('lateUnits', 0) / 10 if valid else None,
            'siliconInputShortagePct': 100 * m.get('lateReasons', {}).get('input-shortage', 0) / frames if frames else None}


def windows(d, name):
    result = []
    previous = {'seconds': 0, 'productionTotals': {}}
    for sample in d['timeline']:
        seconds = sample['seconds'] - previous['seconds']
        if seconds <= 0:
            continue
        after = sample.get('productionTotals', {}).get(name)
        before = previous.get('productionTotals', {}).get(name, {})
        if after is not None:
            cycles = after['cycles'] - before.get('cycles', 0)
            frames = after['frames'] - before.get('frames', 0)
            result.append({'start': previous['seconds'], 'end': sample['seconds'], 'rate': cycles * 60 / seconds,
                           'utilization': 100 * cycles * d['machines'].get(name, {}).get('craftTimeTicks', 1) / frames if frames else 0})
        previous = sample
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('directories', nargs='+', type=Path)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/reports/v0.2.1-ceiling')
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    cases = []
    for directory in args.directories:
        manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf-8'))
        for filename, digest in manifest['sourceSha256'].items():
            assert hashlib.sha256((ROOT / filename).read_bytes()).hexdigest() == digest, 'AI policy changed during audit'
        for path in sorted(directory.glob('*-seed-*.json')):
            if path.stem.endswith(('-layout', '-state')):
                continue
            d = json.loads(path.read_text(encoding='utf-8'))
            state_path = path.with_name(path.stem + '-state.json')
            state = json.loads(state_path.read_text(encoding='utf-8')) if state_path.exists() else {}
            cases.append({'path': path, 'data': d, 'state': state, 'metrics': metrics(d), 'id': directory.name + '/' + path.stem})
    assert cases and all(c['data']['rawPositiveCoreInflow'] == 0 for c in cases)
    with zipfile.ZipFile(ROOT / 'build/fair-attack-ai.jar') as release:
        release_classes = [name for name in release.namelist() if name.endswith('.class')]
        for name in release_classes:
            assert release.read(name) == (ROOT / 'build/benchmark-classes' / name).read_bytes(), 'Simulated AI differs from the released mod'
    pressure = [c for c in cases if 'attrition' in c['data']['scenario']]
    best = max(pressure, key=lambda c: c['metrics']['siliconPerMinute'])
    base = next(c for c in cases if c['data']['scenario'] == 'attrition')
    total_hours = sum(c['data']['actualSeconds'] for c in cases) / 3600
    summary = {'cases': len(cases), 'simulatedHours': total_hours, 'bestSustainedPressureCase': best['id'],
               'policyUnchanged': True, 'releaseClassesVerified': len(release_classes), 'rawCoreInflowAllCases': 0,
               'results': [{'id': c['id'], 'scenario': c['data']['scenario'], 'seed': c['data']['seed'],
                            'minutes': c['data']['actualSeconds'] / 60, 'factories': c['data']['currentFactories'],
                            **c['metrics']} for c in cases]}
    (output / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    with (output / 'metrics.csv').open('w', newline='', encoding='utf-8-sig') as file:
        rows = [{'case': c['id'], 'minutes': c['data']['actualSeconds'] / 60, **c['metrics']} for c in cases]
        writer = csv.DictWriter(file, fieldnames=rows[0].keys()); writer.writeheader(); writer.writerows(rows)
    report = [f'# v0.2.1 AI 工业与产能上限实测\n',
              f'共 {len(cases)} 组引擎模拟，累计 {total_hours:.2f} 小时游戏时间，最长单场 60 分钟。AI 决策源码的 SHA-256 在运行前后逐文件一致；模拟所用的 {len(release_classes)} 个 AI 类也与当前分发 JAR 逐字节一致。没有修改生产、建造、资源或部队决策。\n',
              '结论：当前 AI 自主发展停留在基础石墨、普通硅和 T1 造兵；本次没有自主建出高级硅厂、塑钢/合金工厂或单位重构厂。开局给足高级材料并继续运行一小时，也没有突破这个策略层级。\n',
              '## 方法与适用范围\n',
              '- 使用本机真实 v146 引擎，以 60 次固定步长更新代表一秒游戏时间；使用真实施工、材料扣除、采矿、物品运输和工厂更新。无图形界面，不操作玩家存档。\n',
              '- 普通开局：双方核心只有铜 250、铅 150，其余物品为零。平原、隘口、湖岸和扩大矿区各运行两个种子，每场 30 分钟。扩大矿区额外提供钛和钍矿，但不发放对应材料。\n',
              '- 富库存对照仅在开局给每种物品 1000，用于排除建造材料不足；不算从零发展结果。运行中没有补发材料。\n',
              '- 持续战损是单独的产能诊断：每 30 秒检查并通过正常死亡机制击毁年龄达到 120 秒的自产部队，实际寿命约 120–150 秒，用于减轻单位上限影响。它不是一次真实人类对战。\n',
              '- 另有关闭蓝图的对照和一场双方运行同一 AI 的镜像对战。单场镜像胜负不能代表对玩家的胜率。\n',
              '- 实际物品产量按工厂真实生产进度回绕次数 × 配方产出计数；单位按真实 UnitCreateEvent 计数，包含已完成但还停在工厂内的载荷。库存净变化不当作产量。\n',
              '- 稳态指标使用计划结束前的最后 10 分钟。利用率 = 实际完成周期 / 建成机器在该时间段的额定可完成周期。提前结束的对战不计算末段稳态指标。\n',
              '- 缺料/缺电/满输出等原因来自生产建筑每帧状态的分类；是状态统计，不是已完成的因果干预实验。所有模拟的煤、沙核心正向流入都为零。\n',
              '## 实际建造结果\n',
              '\n| 场景 | 种子 | 时长/分 | 石墨厂 | 硅厂 | 空军厂 | 陆军厂 | 最后10分硅/分 |\n',
              '|---|---:|---:|---:|---:|---:|---:|---:|\n']
    for c in cases:
        d, m = c['data'], c['metrics']; f = d['currentFactories']
        rate = '—' if m['siliconPerMinute'] is None else f"{m['siliconPerMinute']:.1f}"
        report.append(f"| {NAMES.get(d['scenario'],d['scenario'])} | {d['seed']} | {d['actualSeconds']/60:.1f} | {f.get('graphite-press',0)} | {f.get('silicon-smelter',0)} | {f.get('air-factory',0)} | {f.get('ground-factory',0)} | {rate} |\n")
    d, m, state = base['data'], base['metrics'], base['state']
    sand = state['ratedUnboostedSupplyPerMinute']['sand']
    coal = state['ratedUnboostedSupplyPerMinute']['coal']
    sand_need = state['crafterFullLoadDemandPerMinute']['sand']
    report += ['\n## 持续出兵下的瓶颈\n',
               f"平原持续战损对照运行 60 分钟，最终是 {d['currentFactories']}，累计制造 {d['manufacturedUnits']}；第 {d['lastCompletedProjectSeconds']/60:.1f} 分钟后没有新的完整建设项目。\n",
               f"最后 10 分钟实际硅产量 **{m['siliconPerMinute']:.1f}/分**，三台普通硅厂额定合计 **270/分**，产能利用率 **{m['siliconUtilizationPct']:.1f}%**。空军工厂实际 **{m['flarePerMinute']:.1f} 架 Flare/分**，单厂满负荷为 4 架/分。\n",
               f"最终沙钻头未加水、未计运输损失的额定供给合计 **{sand:.1f} 沙/分**；三台硅厂满负荷需要 **{sand_need:.1f} 沙/分**。普通硅配方每个硅消耗两份沙，因此现有开采布局给出的硅上界为 **{sand/2:.1f}/分**，恰好与本次最后 10 分钟的实际产量相符。\n",
               f"煤的开采额定供给为 {coal:.1f}/分，而现有加工厂同时满载需要 {state['crafterFullLoadDemandPerMinute']['coal']:.1f}/分；这个需求还没有计入燃烧发电机。矿点总供给只是潜在上界，远距离运输和分流还可能继续减少送达量。\n",
               f"硅厂最后 10 分钟的机器帧中，缺料约占 {m['siliconInputShortagePct']:.1f}%。当前计划优先保证硅库存阈值；持续造兵使库存停留在 10，后面的陆军工厂建设长期不能推进。\n",
               f"本组压力对照中，观测到的最高末段持续硅产量为 **{best['metrics']['siliconPerMinute']:.1f}/分**（{NAMES[best['data']['scenario']]}）。这是这些地图、布局和需求下的观测结果，没有证明全局最优。\n",
               '有限时间窗口可能消耗上一个窗口留下的原料或成品缓存，因此测得的短期平均值可以略高于当时矿点额定供给折算值；矿点计算给出的是不计缓存释放的持续供给上界。\n',
               '## 策略上限与停止发展的原因\n',
               '1. 主动目标仍是基础石墨厂、硅厂、燃烧发电机、普通钻头和初级造兵，没有高级生产链与单位重构的战略目标。全部科技解锁不会自动补上这些决策。\n',
               '2. 目前矿物硬度决定默认钻头类型，不会因需求变大就自动将已有煤/沙矿业升级。选址还会出现少量矿格覆盖及远距离矿点，尚未统一比较有效产量、运费和施工成本。\n',
               '3. 缺硅会扩硅厂，但没有按照沙、煤和电力的吞吐反推机器数量。此处首先需要补上游，而不是继续增加硅厂。\n',
               '4. 同一建造机还负责取成品、补给和采矿。后续陆军、扩张等任务受固定优先级和核心库存阈值影响，持续消费会造成长期饥饿。\n',
               '5. 本测试核心的每种单位上限是 8。和平发展达到上限后，工厂中还可能有已完成的单位载荷，因此累计制造数可能大于场上单位数。不能把此时的零出兵率当成纯生产能力不足。\n',
               '6. 石墨后期经常因输出堆满而停产，原因包括库存目标已达到、没有高级消费链、回收依赖核心机体；不应把这种需求已满足的停机直接解释成供料失败。\n',
               '7. 蓝图文件名不能代表实际科技：例如高级重构蓝图可能被缩减为其中的一座普通空军工厂，实际没有建造对应的重构厂。\n',
               '## 建议的改进顺序\n',
               '先做按每分钟吞吐计算的生产依赖图，补足沙、煤与电力并选择钻头升级；再加入建设预算、生产预算和发展任务保留，解决持续出兵时的任务饥饿；之后接入钛、液体、塑钢和 T2 重构链。学习模块应在这些可执行方案上选择策略。\n',
               '## 产量曲线与最终布局\n',
               '![产能对照](throughput.png)\n\n![平原压力对照最终布局](final-base.png)\n',
               '原始数据、固定种子、逐文件源码校验值、最终存档和布局位于附件 ZIP 的 raw/ 中。存档是无玩家操作的评估世界，关闭了自动结束对局；如要继续 AI，应在模组设置中启用本局。\n']
    markdown = re.sub(r'^(#{1,6} .+)\n', r'\1\n\n', ''.join(report), flags=re.MULTILINE)
    (output / 'report.md').write_text(markdown, encoding='utf-8')
    draw_figures(cases, base, output)
    with zipfile.ZipFile(output / 'benchmark-evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in ('report.md', 'summary.json', 'metrics.csv', 'throughput.png', 'final-base.png'):
            archive.write(output / name, name)
        for directory in args.directories:
            for file in sorted(directory.iterdir()):
                if file.is_file(): archive.write(file, f'raw/{directory.name}/{file.name}')
        for name in ('test/fairai/EconomyBenchmark.java', 'test/fairai/BenchmarkSnapshotAudit.java',
                     'tools/benchmark.py', 'tools/summarize_benchmark.py'):
            archive.write(ROOT / name, 'audit-code/' + name)
    print(json.dumps({'report': str(output / 'report.md'), 'cases': len(cases), 'hours': total_hours,
                      'bestPressure': best['id'], 'bestSiliconRate': best['metrics']['siliconPerMinute']}, ensure_ascii=True))


def draw_figures(cases, base, output):
    os.environ.setdefault('MPLCONFIGDIR', str(ROOT / 'build/matplotlib-cache'))
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.patches import Rectangle, Patch
    import numpy as np
    pressure = [c for c in cases if 'attrition' in c['data']['scenario']]
    fig, axes = plt.subplots(1, 2, figsize=(13, 4.7), layout='constrained')
    colors = ['#177e89', '#df7b32', '#7658ac', '#498b4d', '#be4562']
    for color, case in zip(colors, pressure):
        points = windows(case['data'], 'silicon-smelter')
        axes[0].plot([p['end']/60 for p in points], [p['rate'] for p in points], marker='o', ms=3,
                     label=case['data']['scenario'], color=color)
    axes[0].set(xlabel='Simulated minutes', ylabel='Actual silicon / minute', title='Silicon output under sustained unit losses')
    axes[0].grid(alpha=.2); axes[0].legend(fontsize=8)
    measured = base['metrics']['siliconPerMinute']
    rated = base['state']['ratedUnboostedSupplyPerMinute']['sand'] / 2
    bars = axes[1].bar(['3 smelters\nfull load', 'Current sand\nsupply / 2', 'Measured\nlast 10 min'], [270, rated, measured],
                       color=['#bac2cb', '#df7b32', '#177e89'])
    axes[1].bar_label(bars, fmt='%.1f', padding=4)
    axes[1].set(ylabel='Silicon / minute', title='Flat attrition: upstream supply sets the ceiling', ylim=(0, 310))
    axes[1].grid(axis='y', alpha=.2)
    fig.savefig(output / 'throughput.png', dpi=170); plt.close(fig)

    layout = json.loads(base['path'].with_name(base['path'].stem + '-layout.json').read_text(encoding='utf-8'))
    fig, ax = plt.subplots(figsize=(12, 8.6), layout='constrained')
    ground = np.ones((layout['height'], layout['width'], 3)) * .95
    ores = {'copper': '#dda072', 'lead': '#afa5c5', 'coal': '#b0aca6', 'sand': '#ead68e', 'titanium': '#99bdd8', 'thorium': '#d8a4cd'}
    from matplotlib.colors import to_rgb
    for t in layout['terrain']:
        color = '#b8d5e7' if t['liquid'] else '#62686e' if t['solid'] else ores.get(t['ore'], '#eeeeee')
        ground[t['y'], t['x']] = to_rgb(color)
    ax.imshow(ground, origin='lower', extent=(-.5, layout['width']-.5, -.5, layout['height']-.5), interpolation='nearest')
    palette = {'core-shard': '#287859', 'silicon-smelter': '#b54c8a', 'graphite-press': '#4e555b',
               'air-factory': '#3266ac', 'ground-factory': '#b74c42', 'combustion-generator': '#ca933b',
               'mechanical-drill': '#618581', 'solar-panel': '#746caa', 'power-node': '#ac9560'}
    labels = {'core-shard': 'C', 'silicon-smelter': 'S', 'graphite-press': 'G', 'air-factory': 'A', 'ground-factory': 'U', 'combustion-generator': 'E'}
    numbered = {}
    for b in layout['buildings']:
        size = b['size']; offset = -((size-1)//2)
        color = palette.get(b['block'], '#a8afb5')
        ax.add_patch(Rectangle((b['x']+offset-.5, b['y']+offset-.5), size, size, facecolor=color, edgecolor='white', linewidth=.3))
        if b['block'] in labels:
            numbered[b['block']] = numbered.get(b['block'], 0) + 1
            ax.text(b['x']+offset+size/2-.5, b['y']+offset+size/2-.5, labels[b['block']]+str(numbered[b['block']]),
                    color='white', ha='center', va='center', fontsize=7, fontweight='bold')
    ax.set(xlim=(-1,112), ylim=(-1,88), xlabel='Tile X', ylabel='Tile Y', title='Final AI base: flat attrition, seed 17, 60 simulated minutes')
    ax.legend(handles=[Patch(color=palette[n], label=n) for n in labels], loc='upper left', fontsize=8)
    ax.text(2, 3, 'C core | S silicon | G graphite | A air factory | E combustion\nOre colors show mineable ground; this is a schematic, not an in-game screenshot.', fontsize=8,
            bbox={'facecolor': 'white', 'alpha': .9, 'edgecolor': '#cccccc'})
    fig.savefig(output / 'final-base.png', dpi=160); plt.close(fig)


if __name__ == '__main__':
    main()
