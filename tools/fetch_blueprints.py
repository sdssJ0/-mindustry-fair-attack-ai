"""Fetch pinned, attributed CC BY-SA schematic assets; the game never downloads code."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import json
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = '9d44f5fe0e71bb419ca0dca41c96acef9a0beb00'
REPOSITORY = 'holmes-g/schematics'
PATHS = [
    'Serpulo/crafting/graphite-press-6-mechanical-drill-23.msch',
    'Serpulo/crafting/graphite-press-7-pneumatic-drill-18.msch',
    'Serpulo/crafting/kiln-5-pneumatic-drill-17-sand.msch',
    'Serpulo/crafting/kiln-6-core.msch',
    'Serpulo/crafting/silicon-smelter-7-pneumatic-drill-35.msch',
    'Serpulo/crafting/silicon-smelter-7-pneumatic-drill-14-boosted.msch',
    'Serpulo/crafting/phase-weaver-6-core.msch',
    'Serpulo/crafting/surge-smelter-3-core.msch',
    'Serpulo/power/thorium-reactor-5-water-extractor.msch',
    'Serpulo/production/airblast-drill-6-double.msch',
    'Serpulo/production/airblast-drill-2-mass-driver.msch',
    'Serpulo/turret/scatter-4-wireless.msch',
    'Serpulo/turret/salvo-4-wireless.msch',
    'Serpulo/turret/cyclone-3-wireless.msch',
    'Serpulo/turret/swarmer-4-wireless.msch',
    'Serpulo/turret/foreshadow-3-wireless.msch',
    'Serpulo/units/multiplicative-reconstructor-2-wireless.msch',
]

def download(path):
    base = f'https://raw.githubusercontent.com/{REPOSITORY}/{REVISION}/'
    data = urllib.request.urlopen(base + urllib.parse.quote(path), timeout=30).read()
    target = ROOT / 'assets/blueprints' / path.split('/')[-1]
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    return dict(file=target.name, author='holmes-g', repository=REPOSITORY, revision=REVISION,
                path=path, url=f'https://github.com/{REPOSITORY}/blob/{REVISION}/' + path,
                license='CC-BY-SA-4.0', sha256=hashlib.sha256(data).hexdigest())

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=5) as pool:
        entries = list(pool.map(download, PATHS))
    root = ROOT / 'assets/blueprints'
    (root / 'catalog.json').write_text(json.dumps(entries, indent=2), encoding='utf-8')
    license_data = urllib.request.urlopen(
        f'https://raw.githubusercontent.com/{REPOSITORY}/{REVISION}/LICENSE', timeout=30).read()
    (root / 'LICENSE-CC-BY-SA-4.0.txt').write_bytes(license_data)
    (root / 'ATTRIBUTION.txt').write_text(
        f'Source: https://github.com/{REPOSITORY}\nAuthor: holmes-g\nRevision: {REVISION}\n'
        'Schematics: CC BY-SA 4.0. Original files are unmodified. Runtime layout adaptations '
        'are derived from these schematics, with attribution retained in the AI status.\n'
        'License: https://creativecommons.org/licenses/by-sa/4.0/\n', encoding='utf-8')
    print(f'Downloaded {len(entries)} pinned schematic assets with source and license.')
