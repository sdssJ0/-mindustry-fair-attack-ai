"""Compile an isolated engine audit; do not alter the distributed mod or its AI policy."""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from concurrent.futures import ThreadPoolExecutor
import build


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser()
    parser.add_argument('--scenarios', default='flat,choke,lake,rich')
    parser.add_argument('--seeds', default='17,43')
    parser.add_argument('--minutes', type=int, default=30)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--profile', action='store_true')
    parser.add_argument('--runtime', type=Path, default=build.GAME / 'jre/bin/java.exe')
    parser.add_argument('--jobs', type=int, choices=range(1,5), default=1)
    args = parser.parse_args()
    output = args.output or build.ROOT / 'test-output' / 'benchmark' / datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted((build.ROOT / 'src').rglob('*.java'))
    # Each run keeps immutable class files: another benchmark must never replace
    # classes that a still-running JVM may not have loaded yet.
    classes = output / 'compiled/classes'
    tests = output / 'compiled/tests'
    build.compile_sources(sources, classes)
    build.compile_sources(sorted((build.ROOT / 'test').rglob('*.java')), tests, [classes])
    runtime=args.runtime.resolve()
    runtime_version=subprocess.run([str(runtime),'-version'],capture_output=True,text=True,encoding='utf-8',errors='replace',check=True)
    (output / 'manifest.json').write_text(json.dumps({
        'modVersion': re.search(r'^version:\s*(\S+)', (build.ROOT/'mod.hjson').read_text(encoding='utf-8'), re.MULTILINE).group(1), 'minutesPerRun': args.minutes, 'scenarios': args.scenarios.split(','),
        'runtime':str(runtime),'runtimeVersion':runtime_version.stdout+runtime_version.stderr,
        'jobs':args.jobs,'scenarioIsolation':'Separate JVM and data directory per scenario' if args.jobs>1 else 'Sequential scenarios in one JVM',
        'compiledClasses':'compiled/classes','compiledTests':'compiled/tests',
        'seeds': args.seeds.split(','), 'gameJarSha256': hashlib.sha256(build.GAME_JAR.read_bytes()).hexdigest(),
        'sourceSha256': {p.relative_to(build.ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'sourceCanonicalSha256': {p.relative_to(build.ROOT).as_posix(): hashlib.sha256(p.read_bytes().replace(b'\r\n', b'\n')).hexdigest() for p in sources},
        'sourceHashPolicy': 'Canonical source hashes normalize CRLF to LF; original byte hashes are also retained.',
        'method': 'Actual v146 Groups.update at fixed 60 ticks per simulated second; AI policy unchanged. Craft cycles detected from real progress rollover, units from UnitCreateEvent. Late window is final 10 planned minutes. No runtime resource grants.',
        'diagnosticScenarios': {'rich-start': 'All items 1000 in each core at setup only; not an autonomous bootstrap result.',
                                'attrition': 'Manufactured units killed after 120-150 game seconds to separate cap from production limits.',
                                'duel': 'Both teams run the same controller; stops on either core destruction.'}
    }, indent=2), encoding='utf-8')
    base = [str(runtime), '-Dfile.encoding=UTF-8', '-Dfairai.profile='+str(args.profile).lower(), '-ea', '-cp',
            ';'.join(os.path.relpath(p, build.ROOT) for p in [build.GAME_JAR, classes, tests])]
    print('Benchmark output:', output, flush=True)
    groups=args.scenarios.split(',') if args.jobs>1 else [args.scenarios]
    def launch(group):
        data=output/('data-'+group.replace(',','-'))
        command=base[:1]+['-Dfairai.testData='+str(data)]+base[1:]+['fairai.EconomyBenchmark',group,args.seeds,str(args.minutes),str(output)]
        log_path=output/('run-'+group.replace(',','-')+'.log')
        with log_path.open('w',encoding='utf-8') as log:
            process=subprocess.Popen(command,cwd=build.ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,encoding='utf-8',errors='replace')
            for line in process.stdout:
                log.write(line);log.flush();print(line,end='',flush=True)
            return process.wait()
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        statuses=list(pool.map(launch,groups))
    with (output/'run.log').open('w',encoding='utf-8') as log:
        for group in groups:log.write((output/('run-'+group.replace(',','-')+'.log')).read_text(encoding='utf-8'))
        status=next((value for value in statuses if value),0)
        if not status:
            inspection=subprocess.run(base+['fairai.BenchmarkSnapshotAudit',str(output)],cwd=build.ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace')
            log.write(inspection.stdout+inspection.stderr);print(inspection.stdout+inspection.stderr,end='',flush=True);status=inspection.returncode
    raise SystemExit(status)


if __name__ == '__main__':
    main()
