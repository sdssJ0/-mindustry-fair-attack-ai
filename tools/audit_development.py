"""Bind autonomous T2/renewal evidence and the full engine suite to the actual release JAR."""
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
    source_hashes = manifest.get('sourceCanonicalSha256', manifest['sourceSha256'])
    for name, digest in source_hashes.items():
        data = (root / name).read_bytes()
        if 'sourceCanonicalSha256' in manifest: data = data.replace(b'\r\n', b'\n')
        assert hashlib.sha256(data).hexdigest() == digest, name
    jar = root / 'build/fair-attack-ai.jar'
    with zipfile.ZipFile(jar) as archive:
        for file in (root / 'build/benchmark-classes').rglob('*.class'):
            assert archive.read(file.relative_to(root / 'build/benchmark-classes').as_posix()) == file.read_bytes(), file
        assert 'version: 0.4.0' in archive.read('mod.hjson').decode('utf-8')
    log = (root / 'build/verification.log').read_text(encoding='utf-8')
    match = re.search(r'PASS: (\d+) engine-backed checks\s*$', log)
    assert match and int(match.group(1)) >= 202, 'Full engine suite is incomplete'
    results = []
    for name in ('flat', 'choke', 'lake', 'late', 'attrition'):
        data = json.loads((runs / (name + '-seed-17.json')).read_text(encoding='utf-8'))
        assert data['actualSeconds'] >= 2400, (name, 'short simulation')
        assert data['emergencyMiningSeconds'] == 0 and data['rawPositiveCoreInflow'] == 0, (name, 'mining/raw policy')
        assert data['completedT2Upgrades'] >= 2 and data['manufacturedUnits'].get('mace', 0) >= 2, (name, 'T2 not commissioned')
        assert all(b['automated'] and b['ammo'] > 0 for b in data['defenses']), (name, 'unfed defense')
        assert any(b['block'] == 'duo' for b in data['defenses']) and any(b['block'] == 'scatter' for b in data['defenses'])
        assert data['coreAlive'], (name, 'AI core destroyed')
        assert (runs / (name + '-seed-17.msav')).stat().st_size > 0
        results.append({
            'scenario': name, 'seconds': data['actualSeconds'], 't2': data['completedT2Upgrades'],
            'retired': data['retiredFacilities'], 'laserDrills': data['currentBuildings'].get('laser-drill', 0),
            'stock': data['coreStock'], 'units': data['manufacturedUnits'], 'renewal': data['retirement'],
        })
    assert sum(r['laserDrills'] for r in results) > 0, 'No autonomous advanced mining'
    assert sum(r['retired'] for r in results) > 0, 'No autonomous retirement'
    report = root / 'build/reports/v0.4.0-development'
    report.mkdir(parents=True, exist_ok=True)
    proof = dict(version='0.4.0', jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                 engineChecks=int(match.group(1)), sourceSha256=source_hashes,
                 sourceHashPolicy='CRLF normalized to LF; original byte hashes retained in the simulation manifest.', results=results)
    (report / 'verification.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = ['# v0.4.0 发展与拆改验证', '', f"真实 v146 引擎检查：{match.group(1)} 项通过；5 场各 40 分钟，种子 17。", '',
             '| 场景 | 完成 T2 重构 | 正常拆除设施 | 存活激光钻头 |', '| --- | ---: | ---: | ---: |']
    for r in results:
        lines.append(f"| {r['scenario']} | {r['t2']} | {r['retired']} | {r['laserDrills']} |")
    lines += ['', '所有场景均由普通铜铅开局发展，模拟中不发放资源，不放置资源源或电力源。煤/沙正向入核及机体手挖均为 0，建成炮塔均有自动供弹与实际弹药。', '',
              'attrition 每隔一段时间模拟部队战损，用于观察持续产能；定期死亡只存在于评估工具，不在模组中。其余场景无人工清理单位上限。', '',
              '定向测试另验证：T1+T2 配方逐项材料守恒、停止规划后卸载器自动供料、正常载荷对接并实际释放 T2、原版耗时拆厂、不自动重建已退役设施、替代激光钻头未供电时保留旧矿、真实产出验收、拆除开始前替代设备失效时拒绝拆除，以及大规模规划记录实际存档读写。定向测试中的电力源只用于隔离计量，不作为自主开局结果。', '',
              '边界：自主重构当前为地面 T2。T3～T5、液体管网和蒸汽发电尚未实现；不能将本报告当作胜率或任意社区地图兼容性评估。', '',
              '证据与源码、分发 JAR 的逐项摘要绑定。']
    (report / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    with zipfile.ZipFile(report / 'evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(runs.iterdir()):
            if file.is_file(): archive.write(file, 'simulation/' + file.name)
        archive.write(root / 'build/verification.log', 'verification.log')
        archive.write(report / 'verification.json', 'verification.json')
        archive.write(report / 'report.md', 'report.md')
    print('VERIFIED', match.group(1), 'checks;', len(results), 'autonomous runs; JAR', proof['jarSha256'])


if __name__ == '__main__':
    main()
