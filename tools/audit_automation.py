"""Verify mining/ammunition outcomes and bind the evidence to the distributed mod."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    runs = args.runs.resolve()
    manifest = json.loads((runs / 'manifest.json').read_text(encoding='utf-8'))
    for path, digest in manifest['sourceSha256'].items():
        assert hashlib.sha256((root / path).read_bytes()).hexdigest() == digest, path
    jar = root / 'build/fair-attack-ai.jar'
    with zipfile.ZipFile(jar) as archive:
        for path in (root / 'build/benchmark-classes').rglob('*.class'):
            assert archive.read(path.relative_to(root / 'build/benchmark-classes').as_posix()) == path.read_bytes(), path
        assert 'version: 0.3.1' in archive.read('mod.hjson').decode('utf-8')
    log = (root / 'build/verification.log').read_text(encoding='utf-8')
    match = re.search(r'PASS: (\d+) engine-backed checks\s*$', log)
    assert match and int(match.group(1)) >= 173, 'Full integration suite has not passed'
    results = []
    for name in ('flat', 'choke', 'lake', 'attrition'):
        data = json.loads((runs / (name + '-seed-17.json')).read_text(encoding='utf-8'))
        assert data['emergencyMiningSeconds'] == 0, (name, 'routine mining')
        assert data['rawPositiveCoreInflow'] == 0, (name, 'coal/sand in core')
        assert any(b['block'] == 'duo' for b in data['defenses']), (name, 'no ground defense')
        assert any(b['block'] == 'scatter' for b in data['defenses']), (name, 'no air defense')
        assert all(b['automated'] and b['ammo'] > 0 for b in data['defenses']), (name, data['defenses'])
        assert sum(data['manufacturedUnits'].values()) >= 4, (name, 'automation stalled before squad production')
        layout = json.loads((runs / (name + '-seed-17-layout.json')).read_text(encoding='utf-8'))
        core = next(b for b in layout['buildings'] if b['block'] == 'core-shard')
        assert all(((b['x'] - core['x']) ** 2 + (b['y'] - core['y']) ** 2) ** .5 * 8 <= 304 for b in data['defenses']), (name, 'remote defense')
        results.append({'scenario': name, 'miningSeconds': data['emergencyMiningSeconds'],
                        'defenses': data['defenses'], 'units': data['manufacturedUnits']})
    report = root / 'build/reports/v0.3.1-fix'
    report.mkdir(parents=True, exist_ok=True)
    proof = {'version': '0.3.1', 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
             'checks': int(match.group(1)), 'sourceAndDistributedClassesMatchBenchmark': True,
             'scenarios': results, 'sourceSha256': manifest['sourceSha256'],
             'runDirectory': runs.relative_to(root).as_posix()}
    (report / 'verification.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
    labels = {'flat': '平原', 'choke': '隘口', 'lake': '湖岸', 'attrition': '平原持续战损'}
    lines = ['# v0.3.1 自动矿业与供弹修复验证', '',
             f'本机 Mindustry v146 真实引擎，无图形环境。{match.group(1)} 项检查通过；分发 JAR 的 AI 类与下列模拟类逐字节一致，源码摘要匹配。', '',
             '| 场景（种子 17，各 20 游戏分钟） | 机体采矿秒数 | 核心附近自动供弹炮塔 | 实际弹仓 | 制造部队 |',
             '| --- | ---: | ---: | --- | --- |']
    for data in results:
        ammo = ', '.join(b['block'] + ': ' + str(b['ammo']) for b in data['defenses'])
        units = ', '.join(k + ': ' + str(v) for k, v in data['units'].items())
        lines.append(f"| {labels[data['scenario']]} | 0 | {len(data['defenses'])} | {ammo} | {units} |")
    lines += ['', '四场均建成核心附近的双管炮和分裂炮，所有建成炮塔都有自动供弹链及真实弹药；煤、沙正向入核流量为 0。', '',
              '定向回归另验证：零库存应急启动仍可恢复矿线；正常开局扩建多座铜铅矿；暂时清空库存而已有自动收入时仍不手挖；供弹带被拆后付费重建；停止 AI 控制器并清空弹仓，矿机和传送带仍能独立补弹；冰雹炮由煤矿、压缩机和输出链自动供弹，核心石墨满库不关停供弹厂。', '',
              '本轮范围为基础经济与基础弹药。尚未实现所有高级弹药配方；本次验证也不代表任意持续满射速和任意地形下都能保持充足供应。不是胜率评测。', '',
              '安装时发现同名 0.1.0 与 0.3.0 两份旧档。原版[模组导入和扫描逻辑](https://github.com/Anuken/Mindustry/blob/v146/core/src/mindustry/mod/Mods.java)导入时另取文件名。安装脚本备份并原地更新全部同名归档，确保每份都与已验证 JAR 相同，重启生效。', '',
              '原始结果：`' + runs.relative_to(root).as_posix() + '`；证据包：`evidence.zip`。']
    (report / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    with zipfile.ZipFile(report / 'evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(runs.iterdir()):
            if path.is_file(): archive.write(path, 'simulation/' + path.name)
        archive.write(root / 'build/verification.log', 'verification.log')
        archive.write(report / 'verification.json', 'verification.json')
        archive.write(report / 'report.md', 'report.md')
    print('VERIFIED', match.group(1), 'checks;', len(results), 'simulations; routine mining 0; every defense supplied near core')
    print('JAR SHA256', proof['jarSha256'])


if __name__ == '__main__':
    main()
