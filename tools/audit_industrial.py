"""Verify fair autonomous steam/T3 progression and bind it to the distributed Java classes."""
from pathlib import Path
import argparse
import hashlib
import json
import re
import zipfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('runs', type=Path)
    parser.add_argument('--supplement',type=Path,action='append',default=[])
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    runs = args.runs.resolve()
    manifest = json.loads((runs / 'manifest.json').read_text(encoding='utf-8'))
    version=manifest['modVersion']
    assert re.search(r'^version:\s*(\S+)',(root/'mod.hjson').read_text(encoding='utf-8'),re.MULTILINE).group(1)==version
    hashes = manifest['sourceCanonicalSha256']
    for name, digest in hashes.items():
        assert hashlib.sha256((root / name).read_bytes().replace(b'\r\n', b'\n')).hexdigest() == digest, name
    jar = root / 'build/fair-attack-ai.jar'
    bundles=[(runs,manifest)]
    for extra in args.supplement:
        extra=extra.resolve();other=json.loads((extra/'manifest.json').read_text(encoding='utf-8'))
        assert other['modVersion']==version and other['sourceCanonicalSha256']==hashes, 'Supplement source/version differs'
        assert other['gameJarSha256']==manifest['gameJarSha256'] and other['runtimeVersion']==manifest['runtimeVersion'], 'Supplement engine/runtime differs'
        bundles.append((extra,other))
    with zipfile.ZipFile(jar) as archive:
        assert 'version: '+version in archive.read('mod.hjson').decode('utf-8')
        for bundle,bundle_manifest in bundles:
            classes=bundle / bundle_manifest['compiledClasses']
            assert (classes/'fairai/FairAiMod.class').exists(), 'Compiled simulation classes missing'
            for file in classes.rglob('*.class'):
                assert archive.read(file.relative_to(classes).as_posix()) == file.read_bytes(), file
    log = (root / 'build/verification.log').read_text(encoding='utf-8')
    match = re.search(r'PASS: (\d+) engine-backed checks\s*$', log)
    assert match and int(match.group(1)) >= 296, 'Full engine suite incomplete'
    engine_runtime=json.loads((root/'build/verification-runtime.json').read_text(encoding='utf-8'))
    assert engine_runtime['exitCode']==0 and engine_runtime['gameJarSha256']==manifest['gameJarSha256']
    results = []
    for name in ('late', 'late-attrition', 'choke-late', 'lake-late', 'flat'):
        candidates=[]
        for index,(bundle,bundle_manifest) in enumerate(bundles):
            path=bundle/(name+'-seed-17.json')
            if path.exists():
                value=json.loads(path.read_text(encoding='utf-8'))
                assert value['scenario']==name and value['actualSeconds']>=bundle_manifest['minutesPerRun']*60, (name,'incomplete run')
                candidates.append((value['actualSeconds'],index,value))
        assert candidates,(name,'missing scenario')
        _,bundle_index,data=max(candidates,key=lambda x:x[0])
        selected=bundles[bundle_index][0]
        assert data['actualSeconds'] >= 3600, (name, 'short run')
        assert data['emergencyMiningSeconds'] == 0 and data['rawPositiveCoreInflow'] == 0, (name, 'resource policy')
        assert data['coreAlive'], (name, 'AI core lost')
        assert data['completedT2Upgrades'] >= 2, (name, 'T2 stalled')
        assert data['utilityStations'] >= 1, (name, 'no water/steam infrastructure')
        assert data['commissionedSteam'] >= 1, (name, 'no commissioned steam generation')
        assert len(data['defenses'])>=2 and all(d['automated'] and d['ammo'] > 0 for d in data['defenses']), (name, 'unfed defense')
        if name != 'flat':
            assert data['completedT3Upgrades'] >= 2 and data['manufacturedUnits'].get('fortress', 0) >= 2, (name, 'T3 stalled')
            if name!='late-attrition':assert data['activeUnits'].get('fortress',0)>=2,(name,'fewer than two physically released live T3 units')
        results.append(dict(scenario=name, seconds=data['actualSeconds'], evidenceBundle=bundle_index,t2=data['completedT2Upgrades'], t3=data['completedT3Upgrades'],
                            steam=data['utilityStations'], commissioned=data['commissionedSteam'], retired=data['retiredFacilities'],
                            units=data['manufacturedUnits'],activeUnits=data['activeUnits'], stock=data['coreStock'], utilities=data['utilities']))
        assert (selected / (name + '-seed-17.msav')).stat().st_size > 0
    durations={int(r['seconds']/60) for r in results}
    run_summary='五场各 60 分钟' if durations=={60} else '五个场景：'+ '、'.join(str(sum(int(r['seconds']/60)==minutes for r in results))+' 场 '+str(minutes)+' 分钟' for minutes in sorted(durations))
    proof = dict(version=version, jarSha256=hashlib.sha256(jar.read_bytes()).hexdigest(), engineChecks=int(match.group(1)),
                 sourceSha256=hashes, sourceHashPolicy='CRLF normalized to LF; compiled Java classes match simulation byte-for-byte.', results=results,
                 engineRuntime=engine_runtime,simulationRuntime={'executable':manifest['runtime'],'version':manifest['runtimeVersion'],'scenarioIsolation':manifest.get('scenarioIsolation','Sequential JVM')})
    proof['runSummary']=run_summary
    proof['simulationBundles']=[{'name':b.name,'minutes':m['minutesPerRun'],'scenarios':m['scenarios']} for b,m in bundles]
    maps=[root/'build/test-maps'/('fair-ai-'+name+'.msav') for name in ('0','1','2','industry')]
    proof['mapsSha256']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in maps}
    report = root / ('build/reports/v'+version+'-industrial')
    report.mkdir(parents=True, exist_ok=True)
    (report / 'verification.json').write_text(json.dumps(proof, ensure_ascii=False, indent=2), encoding='utf-8')
    lines = [f'# v{version} 水、蒸汽与 T3 验证', '', f"真实 v146 引擎检查：{match.group(1)} 项通过；{run_summary}、种子 17。", '',
             '| 场景 | 分钟 | T2 重构 | T3 重构 | 水站 | 末次已验收 | 正常拆除 |', '| --- | ---: | ---: | ---: | ---: | ---: | ---: |']
    for r in results:
        lines.append(f"| {r['scenario']} | {int(r['seconds']/60)} | {r['t2']} | {r['t3']} | {r['steam']} | {r['commissioned']} | {r['retired']} |")
    if len(bundles)>1:lines += ['', '发展较慢的场景另用相同源码与编译类运行较长的新局；原始 60 分钟结果仍保留在证据包中。延长测试不修改 AI 策略、不发材料，也不代表其产能或发展速度已得到优化。相同种子仍可能因实体处理顺序等产生进度波动，本表不是发展时限保证。']
    lines += ['', '所有场景以 250 铜、150 铅的有限库存开局；没有运行时发料、物品源或电力源。机体手挖和煤/沙正向入核均为 0，建成炮塔均有自动供弹与实际弹药。', '',
              '完整引擎检查与长测分别记录实际 Java 运行环境。Java 16.0.1 开发长测曾出现 C2 编译线程本机崩溃；本报告列出的 Java 17 对照结果不能证明已修复旧 JVM。', '',
              '含 late 的场景仅在开局扩展镜像矿区、加入钍；隘口和湖岸保留各自地形。flat 不含钍，检查基础经济与水站的回归。late-attrition 定期模拟已造部队的战损，便于观察持续生产；该死亡逻辑只在评估工具中。', '',
              '定向验证另覆盖水量守恒、煤电启动抽水机、备用电力切换、断水修复、桥接物流、核心入口汇流、真实三阶段载荷升级、五种材料精确消费、满库直供、生产事件计数与存档恢复。定向军工计量的电力源属于测试夹具，不属于模组建造计划。', '',
              '当前自主上限为地面 T3 fortress；T4/T5、完整混合液体和任意拥挤基地的重选址尚未实现。本报告不等同于胜率或所有社区地图兼容性评测。']
    (report / 'report.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
    with zipfile.ZipFile(report / 'evidence.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for index,(bundle,_) in enumerate(bundles):
            for file in sorted(bundle.iterdir()):
                if file.is_file(): archive.write(file, 'simulation/bundle-'+str(index)+'/' + file.name)
        archive.write(root / 'build/verification.log', 'verification.log')
        for name in ('verification.json', 'report.md'): archive.write(report / name, name)
    print('VERIFIED', match.group(1), 'checks; autonomous industrial runs:', len(results), '; JAR', proof['jarSha256'])


if __name__ == '__main__':
    main()
