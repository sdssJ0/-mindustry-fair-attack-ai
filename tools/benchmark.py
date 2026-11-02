"""Compile an isolated engine audit; do not alter the distributed mod or its AI policy."""
from pathlib import Path
from datetime import datetime, timedelta, timezone
import argparse
import hashlib
import json
import os
import re
import subprocess
import build


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--scenarios', default='flat,choke,lake,rich')
    parser.add_argument('--seeds', default='17,43')
    parser.add_argument('--minutes', type=int, default=30)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    output = args.output or build.ROOT / 'test-output' / 'benchmark' / datetime.now(timezone(timedelta(hours=8))).strftime('%Y%m%d-%H%M%S')
    output = output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted((build.ROOT / 'src').rglob('*.java'))
    classes = build.ROOT / 'build/benchmark-classes'
    tests = build.ROOT / 'build/benchmark-tests'
    build.compile_sources(sources, classes)
    build.compile_sources(sorted((build.ROOT / 'test').rglob('*.java')), tests, [classes])
    (output / 'manifest.json').write_text(json.dumps({
        'modVersion': re.search(r'^version:\s*(\S+)', (build.ROOT/'mod.hjson').read_text(encoding='utf-8'), re.MULTILINE).group(1), 'minutesPerRun': args.minutes, 'scenarios': args.scenarios.split(','),
        'seeds': args.seeds.split(','), 'gameJarSha256': hashlib.sha256(build.GAME_JAR.read_bytes()).hexdigest(),
        'sourceSha256': {p.relative_to(build.ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},
        'method': 'Actual v146 Groups.update at fixed 60 ticks per simulated second; AI policy unchanged. Craft cycles detected from real progress rollover, units from UnitCreateEvent. Late window is final 10 planned minutes. No runtime resource grants.',
        'diagnosticScenarios': {'rich-start': 'All items 1000 in each core at setup only; not an autonomous bootstrap result.',
                                'attrition': 'Manufactured units killed after 120-150 game seconds to separate cap from production limits.',
                                'duel': 'Both teams run the same controller; stops on either core destruction.'}
    }, indent=2), encoding='utf-8')
    command = [str(build.GAME / 'jre/bin/java.exe'), '-Dfile.encoding=UTF-8', '-ea', '-cp',
               ';'.join(os.path.relpath(p, build.ROOT) for p in [build.GAME_JAR, classes, tests]),
               'fairai.EconomyBenchmark', args.scenarios, args.seeds, str(args.minutes), str(output)]
    print('Benchmark output:', output, flush=True)
    with (output / 'run.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen(command, cwd=build.ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True, encoding='utf-8')
        for line in process.stdout:
            log.write(line)
            log.flush()
            print(line, end='', flush=True)
        status = process.wait()
        if not status:
            inspection = subprocess.run(command[:5] + ['fairai.BenchmarkSnapshotAudit', str(output)], cwd=build.ROOT,
                                        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding='utf-8')
            log.write(inspection.stdout)
            print(inspection.stdout, end='', flush=True)
            status = inspection.returncode
        raise SystemExit(status)


if __name__ == '__main__':
    main()
