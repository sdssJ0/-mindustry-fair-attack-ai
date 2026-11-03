"""Package only a verified desktop mod, matching maps and its industrial report."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import sys
import zipfile

root=Path(__file__).resolve().parents[1]
version=re.search(r'^version:\s*(\S+)',(root/'mod.hjson').read_text(encoding='utf-8'),re.MULTILINE).group(1)
jar=root/'build/fair-attack-ai.jar'
report=root/f'build/reports/v{version}-industrial'
proof=json.loads((report/'verification.json').read_text(encoding='utf-8'))
if proof['version']!=version or hashlib.sha256(jar.read_bytes()).hexdigest()!=proof['jarSha256']:
    raise SystemExit('Build differs from the verified industrial report. Re-run verification before packaging.')
files=[(jar,'fair-attack-ai.jar'),(root/'README.md','README.md'),
       (root/'docs/INDUSTRIAL_T3.md','docs/INDUSTRIAL_T3.md'),
       (root/'docs/DEVELOPMENT_AND_RENEWAL.md','docs/DEVELOPMENT_AND_RENEWAL.md'),
       (root/'docs/LATE_GAME_ROADMAP.md','docs/LATE_GAME_ROADMAP.md'),
       (report/'report.md',f'docs/reports/v{version}/report.md'),
       (root/'tools/launch-java17.ps1','launch-java17.ps1'),
       (root/'tools/launch-java17.cmd','launch-java17.cmd'),
       (report/'report.md','verification/report.md'),(report/'verification.json','verification/verification.json')]
startup_note=root/f'docs/reports/v{version}/STARTUP_FIX.md'
if startup_note.exists():files.append((startup_note,f'docs/reports/v{version}/STARTUP_FIX.md'))
for name in ('ATTRIBUTION.txt','LICENSE-CC-BY-SA-4.0.txt'):
    files.append((root/'assets/blueprints'/name,'blueprints/'+name))
for name in ('fair-ai-0.msav','fair-ai-1.msav','fair-ai-2.msav','fair-ai-industry.msav'):
    path=root/'build/test-maps'/name
    if hashlib.sha256(path.read_bytes()).hexdigest()!=proof['mapsSha256'][name]:raise SystemExit('Map differs from verification: '+name)
    files.append((path,'test-maps/'+name))
output=root/f'build/fair-attack-ai-{version}.zip'
with zipfile.ZipFile(output,'w',zipfile.ZIP_DEFLATED) as archive:
    for path,name in files:archive.write(path,name)
print(output)
if '--release' in sys.argv:
    release=root/'releases'/('v'+version);release.mkdir(parents=True,exist_ok=True)
    for path in (jar,output):shutil.copy2(path,release/path.name)
    change='修复开局等待分裂炮建材时阻塞扩矿：尚未凑足新防空炮预算时，先检查基础矿业扩张；现有供弹和上游依赖继续维护。保留真实供水、蒸汽及地面 T3。' if version=='0.5.1' else '新增真实供水、蒸汽供电和 dagger → mace → fortress 地面 T3。矿业按库存与建设预算调节；完整军工物流一起预留、付费施工，不直接发材料。'
    notes=[f'# 公平 AI 进攻 v{version}','',change,'',
           f"验证：{proof['engineChecks']} 项真实引擎检查；{proof.get('runSummary','五场各 60 分钟')}，有限库存开局。",'',
           '| 场景 | T2 重构 | T3 重构 | 末态实际出厂 fortress |','| --- | ---: | ---: | ---: |']
    for result in proof['results']:notes.append(f"| {result['scenario']} | {result['t2']} | {result['t3']} | {result['activeUnits'].get('fortress',0)} |")
    notes += ['', '试玩包 ZIP 先解压，游戏中导入 fair-attack-ai.jar，四张地图从地图编辑器导入，再从自定义游戏开始。测试工业链请选择“公平 AI - 工业发展”。', '',
              '`late-attrition` 由评估工具定期模拟已造部队的战损；模组不会据此删除玩家或 AI 部队。其他场景没有这种测试战损。', '',
              'Java 17 启动脚本调用已有 Java 17 和游戏 JAR，不替换原启动器。旧 Java 16.0.1 开发长测曾出现 C2 编译线程本机崩溃；本包记录 Java 17 对照测试的实际运行版本，不声称修复了 JVM。', '',
              '上限与边界：本版为程序策略原型；当前地面路线到 T3，T4/T5、其他星球及任意拥挤基地的完整重选址尚未实现。完整模板放不下时保留低级产线。蓝图署名及许可随包提供。']
    (release/'RELEASE_NOTES.md').write_text('\n'.join(notes)+'\n',encoding='utf-8')
    sums=[hashlib.sha256((release/name).read_bytes()).hexdigest()+'  '+name for name in (jar.name,output.name,'RELEASE_NOTES.md')]
    (release/'SHA256SUMS').write_text('\n'.join(sums)+'\n',encoding='utf-8')
    print(release)
