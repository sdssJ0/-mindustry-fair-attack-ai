package fairai;

import arc.struct.*;
import mindustry.gen.Building;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;

/** Read-only evidence of real production; inventories and elapsed time alone do not prove commissioning. */
public final class ProductionLedger{
    private final IntMap<Sample> samples = new IntMap<>();
    public void update(Iterable<Building> buildings, float clock){
        for(Building b : buildings){
            if(!(b instanceof DrillBuild) && !(b instanceof GenericCrafterBuild)) continue;
            float progress = b instanceof DrillBuild ? ((DrillBuild)b).progress : ((GenericCrafterBuild)b).progress;
            Sample s = samples.get(b.id);
            if(s == null){ s = new Sample(); s.started = clock; samples.put(b.id, s); }
            if(progress + 0.0001f < s.previous && b.enabled && b.efficiency > 0.001f) s.cycles++;
            s.previous = progress;
        }
    }
    public boolean commissioned(Building b, float clock){
        Sample s = b == null ? null : samples.get(b.id);
        return s != null && s.cycles >= 2 && clock - s.started >= 1800f && b.isValid() && b.enabled &&
            (b.power == null || !b.block.consumesPower || b.power.status >= 0.95f);
    }
    public boolean observed(Building b, float clock){
        Sample s = b == null ? null : samples.get(b.id);
        return s != null && s.cycles>=2 && clock-s.started>=1800f && b.isValid();
    }
    public int cycles(Building b){ Sample s = samples.get(b.id); return s == null ? 0 : s.cycles; }
    private static final class Sample{ float previous, started; int cycles; }
}
