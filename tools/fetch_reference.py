"""Download pinned upstream source for interface inspection; never bundled in the mod."""
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
PATHS = [
    'ai/BaseBuilderAI.java', 'ai/RtsAI.java', 'ai/types/BuilderAI.java',
    'ai/types/MinerAI.java', 'ai/types/CommandAI.java', 'ai/AIController.java',
    'entities/comp/BuilderComp.java', 'entities/comp/MinerComp.java',
    'entities/comp/PlayerComp.java', 'entities/comp/UnitComp.java',
    'input/InputHandler.java', 'world/blocks/units/UnitFactory.java',
    'world/blocks/production/GenericCrafter.java', 'world/blocks/production/Drill.java',
    'world/blocks/storage/CoreBlock.java', 'world/blocks/storage/Unloader.java',
    'world/consumers/ConsumeItems.java', 'world/consumers/ConsumeLiquids.java',
    'world/consumers/ConsumePower.java', 'world/Block.java', 'world/Tile.java',
    'game/Rules.java', 'game/Team.java', 'game/Teams.java', 'game/EventType.java',
    'core/Logic.java', 'core/World.java', 'core/NetServer.java', 'core/GameState.java',
    'content/Blocks.java', 'content/UnitTypes.java', 'io/SaveIO.java', 'io/MapIO.java',
    'graphics/FogRenderer.java', 'game/Gamemode.java', 'Vars.java',
]

def fetch(path):
    target = ROOT / '.reference' / path
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        url = 'https://raw.githubusercontent.com/Anuken/Mindustry/v146/core/src/mindustry/' + path
        try:
            target.write_bytes(urllib.request.urlopen(url, timeout=30).read())
        except Exception as error:
            return f'{path}: {error}'
    return path

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=6) as pool:
        for result in pool.map(fetch, PATHS):
            print(result)
