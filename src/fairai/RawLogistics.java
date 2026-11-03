package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Coal and sand belong at processing/fuel consumers, never in the core's stock goals. */
public final class RawLogistics{
    private final TeamBrain brain;
    private final LongMap<Float> retryAfter=new LongMap<>();
    private int lastProject=-1;
    RawLogistics(TeamBrain brain){ this.brain = brain; }

    public static boolean processingInput(Item item){ return item == Items.coal || item == Items.sand; }

    public Building consumer(Item item, Position from){
        if(Vars.state.rules.onlyDepositCore) return null;
        return brain.view.buildings.min(b -> b.isValid() && !(b instanceof CoreBuild) && b.items != null &&
            brain.inputs(b.block, b).contains(item, true) && b.acceptStack(item, 1, brain.actor.unit()) > 0,
            b -> b.dst2(from) + b.items.get(item) * 400f);
    }

    /** Prefer adjacency to actual raw sources, allowing the original drill dump machinery to feed them. */
    BuildPlan site(Block block){
        Seq<Item> inputs = brain.inputs(block, null);
        BuildPlan best = null;
        float score = Float.MAX_VALUE;
        for(Building source : brain.view.buildings){
            if(!(source instanceof DrillBuild) || !processingInput(((DrillBuild)source).dominantItem) ||
                !inputs.contains(((DrillBuild)source).dominantItem, true)) continue;
            Item item = ((DrillBuild)source).dominantItem;
            int assigned = brain.view.buildings.count(b -> brain.inputs(b.block, b).contains(item, true) && brain.construction.feeds(source, b));
            int reach = source.block.size + block.size;
            for(int dx = -reach; dx <= reach; dx++) for(int dy = -reach; dy <= reach; dy++){
                int x = source.tileX() + dx, y = source.tileY() + dy;
                if(!brain.construction.place(block, x, y, 0)) continue;
                int offset = -(block.size - 1) / 2;
                boolean adjacent = false;
                for(Point2 edge : Edges.getEdges(source.block.size)){
                    int ex = source.tileX() + edge.x, ey = source.tileY() + edge.y;
                    if(ex >= x + offset && ex < x + offset + block.size && ey >= y + offset && ey < y + offset + block.size){ adjacent = true; break; }
                }
                if(!adjacent) continue;
                int covered = 0;
                for(int ox = 0; ox < block.size; ox++) for(int oy = 0; oy < block.size; oy++){
                    Tile under = Vars.world.tile(x + offset + ox, y + offset + oy);
                    if(under != null && under.drop() != null) covered++;
                }
                float distance = brain.core().dst(x * 8f, y * 8f);
                float value = covered * 2000f + assigned * 100f + distance * distance / 120f + brain.view.danger(x * 8f, y * 8f) * 10000f;
                if(value < score){ score = value; best = new BuildPlan(x, y, 0, block); }
            }
        }
        return best;
    }

    boolean disconnectCoreInputs(){
        //Migrate the simple drill-to-core conveyor paths produced by earlier versions.
        Seq<BuildPlan> disconnect = new Seq<>();
        IntSet seen = new IntSet();
        for(Building source : brain.view.buildings){
            if(!(source instanceof DrillBuild) || !processingInput(((DrillBuild)source).dominantItem)) continue;
            if(source.proximity.contains(b -> b instanceof CoreBuild) && seen.add(source.pos())){
                disconnect.add(new BuildPlan(source.tileX(), source.tileY()));
            }
            for(Building belt : brain.construction.outputBelts(source)){
                if(belt.front() instanceof CoreBuild && seen.add(belt.pos())) disconnect.add(new BuildPlan(belt.tileX(), belt.tileY()));
            }
        }
        if(disconnect.any() && brain.submit(disconnect, "拆除煤/沙通往核心的旧运输入口")) return true;
        return false;
    }

    boolean plan(){
        if(lastProject!=brain.finishedProjects){retryAfter.clear();lastProject=brain.finishedProjects;}
        for(Building target : brain.view.buildings){
            if(!brain.economy.manages(target)) continue;
            for(Item item : brain.inputs(target.block, target)){
                if(!processingInput(item)) continue;
                float needed = brain.economy.inputRate(target, item);
                if(needed <= 0) continue;
                float connected = 0;
                for(Building b : brain.view.buildings){
                    if(!(b instanceof DrillBuild) || ((DrillBuild)b).dominantItem != item || !brain.construction.feeds(b, target)) continue;
                    int sharing = brain.view.buildings.count(other -> brain.economy.inputRate(other, item) > 0 && brain.construction.feeds(b, other));
                    connected += brain.economy.drillRate((DrillBuild)b) / Math.max(1, sharing);
                }
                if(connected >= needed * 0.9f) continue;
                long key=((long)target.pos()<<16)|(item.id&0xffff);
                Float retry=retryAfter.get(key);if(retry!=null && brain.time()<retry)continue;
                Seq<Building> sources=brain.view.buildings.select(b -> b instanceof DrillBuild && ((DrillBuild)b).dominantItem == item &&
                    !brain.construction.feeds(b,target) && (!assignedElsewhere(b,target,item) || spareCapacity(b,target,item,needed)));
                sources.sort(b->b.dst2(target));
                for(Building source:sources){
                    Seq<BuildPlan> route = brain.construction.branchRoute(source, target);
                    if(route != null && route.any() && brain.submit(route, "直送 " + item.localizedName + " → " + target.block.localizedName)){
                        brain.construction.recordFlow(route, item); return true;
                    }
                }
                //An unreachable spare source is not usable capacity for this consumer.
                if(brain.buildDrill(item, target)) return true;
                retryAfter.put(key,brain.time()+600f);
            }
        }
        return false;
    }

    private boolean spareCapacity(Building source,Building target,Item item,float needed){
        //Private utility/ammunition mines and unsupported existing recipes retain exclusive allocation.
        if(brain.defense.assigned(source) || WorldMarks.getInt("fair-ai.utility-mine."+brain.team.id+"."+source.pos(),-1)>=0)return false;
        float allocated=0;
        for(Building other:brain.view.buildings)if(other!=target && brain.inputs(other.block,other).contains(item,true) && brain.construction.feeds(source,other)){
            if(!brain.economy.manages(other))return false;
            Item product=brain.construction.outputItem(other);
            if(!other.enabled && product!=null && brain.core().items.get(product)>=brain.economy.stockGoal(product))continue;
            float total=0;
            for(Building mine:brain.view.buildings)if(mine instanceof DrillBuild && ((DrillBuild)mine).dominantItem==item && brain.construction.feeds(mine,other))total+=brain.economy.drillRate((DrillBuild)mine);
            allocated+=brain.economy.inputRate(other,item)*brain.economy.drillRate((DrillBuild)source)/Math.max(0.001f,total);
        }
        return brain.economy.drillRate((DrillBuild)source)>=(allocated+needed)*1.12f;
    }

    private boolean assignedElsewhere(Building source, Building target, Item item){
        return brain.view.buildings.contains(b -> b != target && (brain.economy.inputRate(b, item) > 0 ||
            !brain.economy.manages(b) && brain.inputs(b.block, b).contains(item, true)) &&
            brain.construction.feeds(source, b));
    }
}
