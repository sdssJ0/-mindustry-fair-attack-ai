"""Build against the installed v146 game; output targets Java 8 bytecode."""
from pathlib import Path
import os
import hashlib
import json
import shutil
import subprocess
import sys
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT.parent
JDK = Path(os.environ.get('FAIR_AI_JDK', os.environ.get('JAVA_HOME',
           'D:/MCLDownload/ext/jre-v64-220420/jdk17')))
JAVA = JDK / 'bin/java.exe'
JAVAC = JDK / 'bin/javac.exe'
GAME_JAR = GAME / 'jre/desktop.jar'
ECJ = ROOT / '.tools/ecj-3.26.0.jar'

def compile_sources(sources, dest, extra=()):
    dest.mkdir(parents=True, exist_ok=True)
    command = [str(JAVA), '-Dfile.encoding=UTF-8', '-jar', os.path.relpath(ECJ, ROOT),
               '-source', '8', '-target', '8', '-warn:none', '-encoding', 'UTF-8', '-proc:none',
               '-classpath', ';'.join(os.path.relpath(p, ROOT) for p in [GAME_JAR, *extra]),
               '-d', os.path.relpath(dest, ROOT),
               *(os.path.relpath(p, ROOT) for p in sources)]
    subprocess.run(command, check=True, cwd=ROOT)

def main():
    if not JAVAC.exists():
        raise SystemExit('Set FAIR_AI_JDK to a JDK 17+ folder (not the game JRE).')
    #ECJ avoids JDK 17 javac's zipfs realpath failure inside this Windows sandbox.
    if not ECJ.exists():
        url = 'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.26.0/ecj-3.26.0.jar'
        data = urllib.request.urlopen(url, timeout=60).read()
        expected = urllib.request.urlopen(url + '.sha256', timeout=30).read().decode().strip().split()[0]
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('Compiler checksum mismatch')
        ECJ.parent.mkdir(parents=True, exist_ok=True)
        ECJ.write_bytes(data)
    classes = (ROOT / 'build/classes').resolve()
    if classes.parent != (ROOT / 'build').resolve():
        raise RuntimeError('Refusing to clean a class output outside the build directory')
    if classes.exists():
        shutil.rmtree(classes)
    compile_sources(sorted((ROOT / 'src').rglob('*.java')), classes)
    if not (classes / 'fairai/FairAiMod.class').exists():
        raise RuntimeError('Compiler did not produce the mod entry point')
    output = ROOT / 'build/fair-attack-ai.jar'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(ROOT / 'mod.hjson', 'mod.hjson')
        for file in sorted(classes.rglob('*.class')):
            archive.write(file, file.relative_to(classes).as_posix())
        for file in sorted((ROOT / 'assets').rglob('*')):
            if file.is_file():
                archive.write(file, file.relative_to(ROOT / 'assets').as_posix())
    print('Built:', output)
    if any(flag in sys.argv for flag in ['--test','--automation-test','--development-test','--utilities-test','--t3-test','--bridge-test']):
        tests = ROOT / 'build/tests'
        compile_sources(sorted((ROOT / 'test').rglob('*.java')), tests, [classes])
        runtime = Path(sys.argv[sys.argv.index('--runtime')+1]) if '--runtime' in sys.argv else GAME / 'jre/bin/java.exe'
        result = subprocess.run([str(runtime), '-Dfile.encoding=UTF-8', '-ea', '-cp',
                                 ';'.join(os.path.relpath(p, ROOT) for p in [GAME_JAR, classes, tests]),
                                 'fairai.BridgeTest' if '--bridge-test' in sys.argv else
                                 'fairai.UtilitiesTest' if '--utilities-test' in sys.argv else
                                 'fairai.TertiaryTest' if '--t3-test' in sys.argv else
                                 'fairai.DevelopmentTest' if '--development-test' in sys.argv else
                                 'fairai.AutomationTest' if '--automation-test' in sys.argv else 'fairai.SimulationTest',
                                 *(['processed'] if '--processed-ammo' in sys.argv else [])], cwd=ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, text=True, encoding='utf-8', errors='replace')
        logname='bridge-verification.log' if '--bridge-test' in sys.argv else 'utilities-verification.log' if '--utilities-test' in sys.argv else 't3-verification.log' if '--t3-test' in sys.argv else 'development-verification.log' if '--development-test' in sys.argv else 'automation-verification.log' if '--automation-test' in sys.argv else 'verification.log'
        (ROOT / 'build' / logname).write_text(result.stdout, encoding='utf-8')
        version=subprocess.run([str(runtime),'-version'],capture_output=True,text=True,encoding='utf-8',errors='replace',check=True)
        (ROOT/'build'/logname.replace('.log','-runtime.json')).write_text(json.dumps({'runtime':str(runtime.resolve()),'runtimeVersion':version.stdout+version.stderr,
            'gameJarSha256':hashlib.sha256(GAME_JAR.read_bytes()).hexdigest(),'exitCode':result.returncode},indent=2),encoding='utf-8')
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.returncode)

if __name__ == '__main__':
    main()
