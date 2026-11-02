"""Package the built desktop mod and the generated test maps for importing in-game."""
from pathlib import Path
import re
import zipfile

root = Path(__file__).resolve().parents[1]
files = [(root / 'build/fair-attack-ai.jar', 'fair-attack-ai.jar'),
         (root / 'README.md', 'README.md')]
files += [(root / 'assets/blueprints' / name, 'blueprints/' + name)
          for name in ('ATTRIBUTION.txt', 'LICENSE-CC-BY-SA-4.0.txt')]
files += [(root / f'build/test-maps/fair-ai-{i}.msav', f'test-maps/fair-ai-{i}.msav') for i in range(3)]
for source, _ in files:
    if not source.is_file():
        raise SystemExit('Run tools/build.py --test first: missing ' + str(source))
version = re.search(r'^version:\s*(\S+)', (root / 'mod.hjson').read_text(encoding='utf-8'), re.MULTILINE).group(1)
output = root / f'build/fair-attack-ai-{version}.zip'
with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
    for source, name in files:
        archive.write(source, name)
print(output)
