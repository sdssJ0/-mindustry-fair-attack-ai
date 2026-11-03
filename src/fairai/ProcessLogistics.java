package fairai;

import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter;

/** Automated non-raw recipe inputs come from actual mines/crafters, preserving their core return. */
public final class ProcessLogistics{
    private final TeamBrain brain;
    public String issue="尚未规划玻璃窑";
    private final ObjectIntMap<Item> kilnBudget=new ObjectIntMap<>();
    private int bestKilnMissing;
    public int reserve(Item item){return kilnBudget.get(item,0);}
    ProcessLogistics(TeamBrain brain){this.brain=brain;}
    private String key(int pos){return "fair-ai.process-mine."+brain.team.id+"."+pos;}
    public void assign(int source,Building target){WorldMarks.put(key(source),Integer.toString(target.pos()));}
    public boolean assigned(Building b){
        int pos=WorldMarks.getInt(key(b.pos()),-1);Building target=Vars.world.build(pos);
        return target!=null && target.team==brain.team;
    }
    public boolean manages(Building b){return b.block==Blocks.kiln && !brain.defense.assigned(b) || b.block==Blocks.multiplicativeReconstructor && brain.development.owns(b);}
    public boolean needsOutput(Building producer){
        Item item=brain.construction.outputItem(producer);if(item==null)return false;
        return brain.view.buildings.contains(target->target!=producer && manages(target) && brain.inputs(target.block,target).contains(item,true) &&
            target.items!=null && target.items.get(item)<target.getMaximumAccepted(item) && brain.construction.feeds(producer,target));
    }
    private float required(Building target,Item item){return target.block==Blocks.multiplicativeReconstructor ? RecipeCatalog.perMinute(target.block,item,1f):brain.economy.inputRate(target,item);}
    private float capacity(Building source,Item item){
        float rate=source instanceof DrillBuild ? brain.economy.drillRate((DrillBuild)source):brain.economy.outputRate(source);
        int users=brain.construction.feeds(source,brain.core()) && brain.core().items.get(item)<brain.economy.stockGoal(item) ? 1:0;
        users+=brain.view.buildings.count(target->target!=source && manages(target) && brain.inputs(target.block,target).contains(item,true) && brain.construction.feeds(source,target));
        return rate/Math.max(1,users);
    }
    /** Commission the secondary input with the machine, before raw mine expansion can seal its ports. */
    boolean buildKiln(){
        kilnBudget.clear();bestKilnMissing=Integer.MAX_VALUE;
        Seq<BuildPlan> sites=new Seq<>();
        BuildPlan preferred=brain.raw.site(Blocks.kiln);if(preferred!=null)sites.add(preferred);
        for(Building sand:brain.view.buildings){
            if(brain.construction.outputItem(sand)!=Items.sand && brain.construction.outputItem(sand)!=Items.lead)continue;
            for(int dx=-4;dx<=4;dx++)for(int dy=-4;dy<=4;dy++){
                int x=sand.tileX()+dx,y=sand.tileY()+dy;
                if(brain.construction.place(Blocks.kiln,x,y,0))sites.add(new BuildPlan(x,y,0,Blocks.kiln));
            }
        }
        sites.sort(p->{
            Building lead=brain.view.buildings.min(b->brain.construction.outputItem(b)==Items.lead && !brain.defense.assigned(b),b->b.dst2(p.x*8f,p.y*8f));
            return (lead==null ? 0:lead.dst2(p.x*8f,p.y*8f))+brain.core().dst2(p.x*8f,p.y*8f)*0.05f;
        });
        for(int i=0;i<Math.min(32,sites.size);i++){
            BuildPlan site=sites.get(i);Building target=brain.construction.proposed(Blocks.kiln,site.x,site.y);
            Seq<Building> lead=brain.view.buildings.select(b->brain.construction.outputItem(b)==Items.lead && !brain.defense.assigned(b));
            lead.sort(b->b.dst2(target));
            for(Building source:lead){
                if(submitKiln(site,source,null))return true;
            }
        }
        //An old mine may have every output committed to a mixed core collector. Start a private input module.
        mindustry.world.blocks.production.Drill drill=(mindustry.world.blocks.production.Drill)Blocks.pneumaticDrill;
        Seq<mindustry.world.Tile> deposits=new Seq<>();
        for(mindustry.world.Tile tile:Vars.world.tiles)if(tile.drop()==Items.lead && brain.view.known(tile.x,tile.y) &&
            brain.construction.place(drill,tile.x,tile.y,0) && brain.oreTiles(drill,tile,Items.lead)>=3)deposits.add(tile);
        deposits.sort(t->brain.core().dst2(t));
        for(int n=0;n<Math.min(24,deposits.size);n++){
            mindustry.world.Tile tile=deposits.get(n);Building mine=brain.construction.proposed(drill,tile.x,tile.y);
            ((DrillBuild)mine).dominantItem=Items.lead;
            for(int dx=-3;dx<=3;dx++)for(int dy=-3;dy<=3;dy++){
                int x=tile.x+dx,y=tile.y+dy;
                if(!brain.construction.place(Blocks.kiln,x,y,0))continue;
                Building target=brain.construction.proposed(Blocks.kiln,x,y);
                if(brain.construction.overlaps(mine,target))continue;
                boolean adjacent=false;
                for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(drill.size))if(LiquidLogistics.inside(new BuildPlan(x,y,0,Blocks.kiln),tile.x+e.x,tile.y+e.y))adjacent=true;
                if(!adjacent)continue;
                if(submitKiln(new BuildPlan(x,y,0,Blocks.kiln),mine,new BuildPlan(tile.x,tile.y,0,drill)))return true;
            }
        }
        issue="玻璃窑选址受阻：现有矿边候选 "+sites.size+"，可用专用铅矿 "+deposits.size;
        return false;
    }
    private void reservePlan(BuildPlan plan,ObjectMap<String,String> previous,int halo){
        int offset=-(plan.block.size-1)/2;
        for(int dx=-halo;dx<plan.block.size+halo;dx++)for(int dy=-halo;dy<plan.block.size+halo;dy++){
            String key="fair-ai.reserve."+brain.team.id+"."+arc.math.geom.Point2.pack(plan.x+offset+dx,plan.y+offset+dy);
            if(!previous.containsKey(key))previous.put(key,WorldMarks.get(key));WorldMarks.put(key,"true");
        }
    }
    private boolean submitKiln(BuildPlan site,Building lead,BuildPlan newMine){
        ObjectMap<String,String> previous=new ObjectMap<>();Seq<BuildPlan> output=null,input=null,sandInput=null;
        Building target=brain.construction.proposed(Blocks.kiln,site.x,site.y);
        try{
            if(newMine!=null)reservePlan(newMine,previous,0);
            output=brain.construction.supplyRoute(target,brain.core());
            if(output==null)return false;
            //Future belts do not exist in proximity lists yet. Reserve their side cells too,
            //otherwise a later input can receive the kiln's output and deadlock on metaglass.
            for(BuildPlan p:output)reservePlan(p,previous,1);
            Seq<Building> sands=brain.view.buildings.select(b->brain.construction.outputItem(b)==Items.sand);
            sands.sort(b->b.dst2(target));
            for(Building sand:sands){sandInput=brain.construction.branchRoute(sand,target);if(sandInput!=null)break;}
            if(sandInput==null)return false;
            for(BuildPlan p:sandInput)reservePlan(p,previous,1);
            input=newMine==null ? brain.construction.branchRoute(lead,target):new Seq<>();
            if(input==null)return false;
        }finally{
            for(ObjectMap.Entry<String,String> entry:previous)if(entry.value==null)WorldMarks.remove(entry.key);else WorldMarks.put(entry.key,entry.value);
        }
        Seq<BuildPlan> plans=new Seq<>();plans.addAll(output);plans.addAll(sandInput);plans.addAll(input);plans.add(site);if(newMine!=null)plans.add(newMine);
        if(brain.submit(plans,"建设玻璃窑、铅供应与完整成品回库线")){
            brain.construction.recordFlow(output,Items.metaglass);brain.construction.recordFlow(sandInput,Items.sand);brain.construction.recordFlow(input,Items.lead);
            if(newMine!=null)assign(arc.math.geom.Point2.pack(newMine.x,newMine.y),target);
            kilnBudget.clear();issue="完整玻璃生产模块施工中";return true;
        }
        ObjectIntMap<Item> costs=brain.construction.costs(plans);int missing=0;
        for(ObjectIntMap.Entry<Item> cost:costs)missing+=Math.max(0,cost.value-brain.core().items.get(cost.key));
        if(missing<bestKilnMissing){bestKilnMissing=missing;kilnBudget.clear();for(ObjectIntMap.Entry<Item> cost:costs)kilnBudget.put(cost.key,cost.value+20);}
        return false;
    }
    boolean plan(){
        for(Building target:brain.view.buildings){
            if(!manages(target))continue;
            for(Item item:brain.inputs(target.block,target)){
                if(RawLogistics.processingInput(item))continue;
                if(target.block==Blocks.multiplicativeReconstructor && brain.development.inputSource(item)!=null){
                    Building source=brain.development.inputSource(item);
                    if(brain.construction.feeds(source,target))continue;
                    Seq<BuildPlan> route=brain.construction.branchRoute(source,target);
                    if(route!=null && route.any() && brain.submit(route,"自动仓储分配 "+item.localizedName+" → T3")){
                        brain.construction.recordFlow(route,item);return true;
                    }
                    continue;
                }
                float supplied=0;
                for(Building b:brain.view.buildings)if(b!=target && brain.construction.outputItem(b)==item && brain.construction.feeds(b,target))supplied+=capacity(b,item);
                if(supplied>=required(target,item)*0.9f && supplied>0)continue;
                Seq<Building> sources=brain.view.buildings.select(b->b!=target && !brain.defense.assigned(b) && brain.construction.outputItem(b)==item && !brain.construction.feeds(b,target));
                sources.sort(b->b.dst2(target));
                for(Building source:sources){
                    Seq<BuildPlan> plans=brain.construction.branchRoute(source,target);
                    if(plans!=null && plans.any() && brain.submit(plans,"自动供料 "+item.localizedName+" → "+target.block.localizedName)){
                        brain.construction.recordFlow(plans,item);return true;
                    }
                }
                if((item==Items.lead || item==Items.titanium) && brain.buildDrill(item,target,true))return true;
                if(item==Items.silicon && brain.view.count(Blocks.siliconSmelter)<brain.economy.machinesWanted(Blocks.siliconSmelter)+1 && brain.build(Blocks.siliconSmelter,false))return true;
            }
        }
        return false;
    }
}
