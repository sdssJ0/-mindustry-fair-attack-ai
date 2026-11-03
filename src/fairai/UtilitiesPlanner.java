package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.logic.LAccess;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.consumers.*;

/** Paired water/steam projects. Old generation stays available throughout bootstrap and repair. */
public final class UtilitiesPlanner{
    private final TeamBrain brain;
    public final LiquidLogistics liquid;
    private final Seq<Station> stations=new Seq<>();
    private final ObjectIntMap<Item> pendingCosts=new ObjectIntMap<>();
    public String phase="保留基础煤电";
    private float clock,nextSearch,nextFuelSearch;
    UtilitiesPlanner(TeamBrain brain){
        this.brain=brain;liquid=new LiquidLogistics(brain);
        String saved=Vars.state.rules.tags.get(key(),"");
        for(String station:saved.split("\\|")){
            if(station.isEmpty()) continue; Station s=new Station();
            for(String entry:station.split(";")){
                String[] p=entry.split(",");s.plans.add(new BuildPlan(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Vars.content.block(Integer.parseInt(p[3]))));
            }
            stations.add(s);
        }
    }
    private String key(){return "fair-ai.utilities."+brain.team.id;}
    private String fuelKey(){return "fair-ai.utility-fuelspace."+brain.team.id+".";}
    private float fuelDemand(){return 3600f/((ConsumeGenerator)Blocks.steamGenerator).itemDuration*1.25f;}
    private boolean sufficientFuel(Tile tile){return 3600f*brain.oreTiles((Drill)Blocks.laserDrill,tile,Items.coal)/((Drill)Blocks.laserDrill).getDrillTime(Items.coal)>=fuelDemand();}
    /** Keep a future efficient fuel mine available before many small drills consume every tile. */
    void reserveFuelSpace(){
        if(clock<nextFuelSearch || WorldMarks.get("fair-ai.utility-future."+brain.team.id)!=null)return;
        nextFuelSearch=clock+600f;
        Station future=findUnreserved();if(future==null)return;
        BuildPlan mine=future.plans.find(p->p.block==Blocks.laserDrill);
        WorldMarks.put("fair-ai.utility-future."+brain.team.id,Integer.toString(Point2.pack(mine.x,mine.y)));
        StringBuilder encoded=new StringBuilder();
        for(BuildPlan p:future.plans){
            encoded.append(p.x).append(',').append(p.y).append(',').append(p.rotation).append(',').append(p.block.id).append(';');
            int o=-(p.block.size-1)/2,halo=p.block==Blocks.laserDrill || p.block instanceof Pump ? 1:0;
            for(int dx=-halo;dx<p.block.size+halo;dx++)for(int dy=-halo;dy<p.block.size+halo;dy++){
                Tile t=Vars.world.tile(p.x+o+dx,p.y+o+dy);
                if(t!=null && t.block()==Blocks.air && !DevelopmentPlanner.reserved(brain.team,t.pos())){
                    WorldMarks.put(fuelKey()+t.pos(),"true");WorldMarks.put("fair-ai.reserve."+brain.team.id+"."+t.pos(),"true");
                }
            }
        }
        WorldMarks.put("fair-ai.utility-future-plan."+brain.team.id,encoded.toString());
    }
    private void save(){
        StringBuilder out=new StringBuilder();
        for(Station s:stations){if(out.length()>0)out.append('|'); for(BuildPlan p:s.plans)out.append(p.x).append(',').append(p.y).append(',').append(p.rotation).append(',').append(p.block.id).append(';');}
        Vars.state.rules.tags.put(key(),out.toString());
    }
    public boolean owns(Building b){return b!=null && stations.contains(s->s.plans.contains(p->p.x==b.tileX() && p.y==b.tileY() && p.block==b.block));}
    public boolean active(){return brain.development.armed;}
    public int count(){return stations.size;}
    public int commissioned(){return stations.count(s->s.verified);}
    public boolean repairing(){return stations.contains(s->!s.complete());}
    public float extraPower(){return brain.development.upgraded()>=2 && !brain.development.t3Complete() && brain.view.deposit(Items.thorium,brain.core())!=null ? Blocks.multiplicativeReconstructor.consPower.usage*60f*1.08f : 0;}
    public boolean wantsMetaglass(){return active();}
    public int reserve(Item item){
        int goal=pendingCosts.get(item,0);
        for(Station s:stations) for(ObjectIntMap.Entry<Item> cost:brain.construction.costs(s.plans)) if(cost.key==item) goal+=cost.value;
        return goal;
    }
    private Building building(BuildPlan p){return p==null || p.tile()==null || p.tile().block()!=p.block ? null : p.tile().build;}
    private boolean supplied(Station s){
        Building pump=building(s.source()),gen=building(s.generator());
        return pump!=null && gen!=null && liquid.feeds(pump,gen) &&
            liquid.waterRate(pump.block,pump.tile)>=waterDemand()*1.04f &&
            brain.view.buildings.contains(b->b instanceof DrillBuild && ((DrillBuild)b).dominantItem==Items.coal && brain.construction.feeds(b,gen));
    }
    public static float waterDemand(){
        for(Consume c:Blocks.steamGenerator.consumers) if(c instanceof ConsumeLiquid && !c.optional) return ((ConsumeLiquid)c).amount*60f;
        return 0;
    }
    public void update(float clock){
        this.clock=clock;
        for(Station s:stations){
            Building gen=building(s.generator());
            if(clock>=s.nextConnectionCheck || gen==null){s.connected=s.complete() && supplied(s);s.nextConnectionCheck=clock+30f;}
            boolean operating=s.connected && gen instanceof PowerGenerator.GeneratorBuild && gen.enabled && building(s.source())!=null;
            if(!operating){s.observed=s.generated=s.mean=0;s.verified=false;}
            else{
                s.observed+=arc.util.Time.delta;
                s.generated+=((PowerGenerator.GeneratorBuild)gen).productionEfficiency*arc.util.Time.delta;
                if(s.observed>=1800f){s.mean=s.generated/s.observed;s.verified=s.mean>=0.90f;s.observed=s.generated=0;}
            }
        }
    }
    public float capacity(Building gen){
        if(gen==null)return 0;
        Station s=stations.find(x->x.generator().x==gen.tileX() && x.generator().y==gen.tileY());
        if(s==null || !supplied(s) || !(gen instanceof PowerGenerator.GeneratorBuild) || !gen.enabled)return 0;
        float efficiency=s.verified ? s.mean : ((PowerGenerator.GeneratorBuild)gen).productionEfficiency;
        return ((PowerGenerator)gen.block).powerProduction*60f*Math.min(1f,efficiency);
    }
    void control(){
        Seq<Building> coal=brain.view.buildings.select(b->b.block==Blocks.combustionGenerator && DevelopmentPlanner.owned(brain.team,b));
        if(coal.isEmpty())return;
        Building industry=brain.view.buildings.find(b->b.block==Blocks.siliconSmelter && b.power!=null);
        if(industry==null)industry=coal.first();
        float firm=0;for(Station s:stations){Building gen=building(s.generator());if(s.verified && gen!=null && industry!=null && gen.power.graph==industry.power.graph)firm+=capacity(gen);}
        int awake=firm<=0 ? coal.size : Math.max(0,(int)Math.ceil((brain.economy.powerDemand+extraPower()+60f-firm)/(((PowerGenerator)Blocks.combustionGenerator).powerProduction*60f)));
        coal.sort(b->b.dst2(brain.core()));
        for(int i=0;i<coal.size;i++)coal.get(i).control(LAccess.enabled,i<awake ? 1:0,0,0,0);
    }
    boolean plan(){
        if(!active()) return false;
        for(Station s:stations) if(!s.complete()){
            phase="修复水源、输水管与蒸汽电站工程";
            return brain.submit(s.plans,phase);
        }
        if(!brain.view.buildings.contains(b->b.block==Blocks.kiln && !brain.defense.assigned(b))){
            boolean started=brain.build(Blocks.kiln,false);phase=brain.processes.issue;return started;
        }
        if(stations.contains(s->!s.verified)){phase="蒸汽电站验收：等待真实水、煤和稳定供电";return false;}
        if(commissioned()>=1 && brain.economy.powerSupply>=brain.economy.powerDemand+extraPower()){
            phase="水与蒸汽供电已投产："+commissioned()+" 组";return false;
        }
        if(stations.size>=8 || clock<nextSearch) return false;
        nextSearch=clock+600f;
        Station candidate=find();
        if(candidate==null){pendingCosts.clear();phase="找不到水、煤与安全通道可同时满足的电站位置";return false;}
        if(!brain.construction.affordable(candidate.plans,brain.core())){
            pendingCosts.clear();for(ObjectIntMap.Entry<Item> cost:brain.construction.costs(candidate.plans))pendingCosts.put(cost.key,cost.value);
            phase="蒸汽工程积累钢化玻璃与建设材料";return false;
        }
        pendingCosts.clear();
        stations.add(candidate);save();
        for(BuildPlan p:candidate.plans)if(p.block==Blocks.laserDrill)WorldMarks.put("fair-ai.utility-mine."+brain.team.id+"."+Point2.pack(p.x,p.y),Integer.toString(Point2.pack(candidate.generator().x,candidate.generator().y)));
        int sourcePos=Point2.pack(candidate.source().x,candidate.source().y);
        WorldMarks.put("fair-ai.water-source."+brain.team.id+"."+sourcePos,Integer.toString(Point2.pack(candidate.generator().x,candidate.generator().y)));
        for(BuildPlan p:candidate.plans)if(p.block.hasLiquids)WorldMarks.put("fair-ai.water-line."+brain.team.id+"."+Point2.pack(p.x,p.y),Integer.toString(sourcePos));
        return brain.submit(candidate.plans,"建设真实水源、输水管和蒸汽电站");
    }
    private Station find(){
        Seq<String> released=new Seq<>();
        WorldMarks.each((key,value)->{if(key.startsWith(fuelKey()))released.add("fair-ai.reserve."+brain.team.id+"."+key.substring(fuelKey().length()));});
        for(String key:released)WorldMarks.remove(key);
        try{
            String saved=WorldMarks.get("fair-ai.utility-future-plan."+brain.team.id);
            if(saved!=null){Station future=new Station();for(String entry:saved.split(";")){
                if(entry.isEmpty())continue;String[] p=entry.split(",");future.plans.add(new BuildPlan(Integer.parseInt(p[0]),Integer.parseInt(p[1]),Integer.parseInt(p[2]),Vars.content.block(Integer.parseInt(p[3]))));
            }
                if(future.plans.any() && future.plans.allMatch(p->brain.construction.place(p.block,p.x,p.y,p.rotation)))return future;
            }
            return findUnreserved();
        }finally{for(String key:released)WorldMarks.put(key,"true");}
    }
    private Station findUnreserved(){
        Seq<BuildPlan> generators=new Seq<>();ObjectMap<BuildPlan,BuildPlan> fuelMines=new ObjectMap<>();
        for(Tile tile:Vars.world.tiles){
            if(tile.drop()!=Items.coal || !brain.view.known(tile.x,tile.y) || !brain.construction.place(Blocks.laserDrill,tile.x,tile.y,0) ||
                !sufficientFuel(tile))continue;
            BuildPlan coal=new BuildPlan(tile.x,tile.y,0,Blocks.laserDrill);
            boolean privateSource=true;for(Point2 edge:Edges.getEdges(coal.block.size)){
                Building neighbour=Vars.world.build(coal.x+edge.x,coal.y+edge.y);if(neighbour!=null && neighbour.items!=null)privateSource=false;
            }
            if(!privateSource)continue;
            for(int dx=-4;dx<=4;dx++) for(int dy=-4;dy<=4;dy++){
                int x=coal.x+dx,y=coal.y+dy;
                if(!brain.construction.place(Blocks.steamGenerator,x,y,0)) continue;
                BuildPlan p=new BuildPlan(x,y,0,Blocks.steamGenerator);
                if(overlap(p,coal))continue;
                boolean adjacent=false;
                for(Point2 e:Edges.getEdges(coal.block.size)) if(LiquidLogistics.inside(p,coal.x+e.x,coal.y+e.y)) adjacent=true;
                if(adjacent){generators.add(p);fuelMines.put(p,coal);}
            }
        }
        generators.sort(p->brain.core().dst(p.x*8f,p.y*8f)+orePenalty(p));
        for(int i=0;i<Math.min(20,generators.size);i++){
            BuildPlan gen=generators.get(i),mine=fuelMines.get(gen);Seq<BuildPlan> sources=new Seq<>();
            for(Tile t:Vars.world.tiles) if(t.floor().liquidDrop==Liquids.water && Math.abs(t.x-gen.x)+Math.abs(t.y-gen.y)<=32 &&
                liquid.waterRate(Blocks.mechanicalPump,t)>=waterDemand()*1.04f && brain.construction.place(Blocks.mechanicalPump,t.x,t.y,0))
                sources.add(new BuildPlan(t.x,t.y,0,Blocks.mechanicalPump));
            for(int dx=-7;dx<=7;dx++) for(int dy=-7;dy<=7;dy++){
                int x=gen.x+dx,y=gen.y+dy; Tile t=Vars.world.tile(x,y);
                if(t!=null && brain.construction.place(Blocks.waterExtractor,x,y,0) && liquid.waterRate(Blocks.waterExtractor,t)>=waterDemand()*1.04f)
                    sources.add(new BuildPlan(x,y,0,Blocks.waterExtractor));
            }
            sources.removeAll(p->overlap(p,gen) || overlap(p,mine) || touches(p,mine) || !exclusiveSource(p));
            sources.sort(p->Math.abs(p.x-gen.x)+Math.abs(p.y-gen.y)+orePenalty(p)+(p.block==Blocks.waterExtractor ? 50 : 0));
            for(int n=0;n<Math.min(16,sources.size);n++){
                BuildPlan source=sources.get(n);Seq<BuildPlan> pipes=liquid.route(source,gen);if(pipes==null)continue;
                if(pipes.contains(p->overlap(p,mine)))continue;
                Station s=new Station();s.plans.add(mine);s.plans.addAll(pipes);s.plans.add(source);s.plans.add(gen);
                nodes(s,source);nodes(s,gen);nodes(s,mine);
                return s;
            }
        }
        return null;
    }
    private boolean exclusiveSource(BuildPlan source){
        //Pumps dump into all accepting neighbours, including optional drill coolant consumers.
        for(Point2 e:Edges.getEdges(source.block.size)){
            Building b=Vars.world.build(source.x+e.x,source.y+e.y);
            if(b!=null && b.block.hasLiquids)return false;
        }
        return true;
    }
    private boolean overlap(BuildPlan a,BuildPlan b){
        int o=-(a.block.size-1)/2;for(int dx=0;dx<a.block.size;dx++)for(int dy=0;dy<a.block.size;dy++)if(LiquidLogistics.inside(b,a.x+o+dx,a.y+o+dy))return true;return false;
    }
    private boolean touches(BuildPlan source,BuildPlan consumer){for(Point2 e:Edges.getEdges(source.block.size))if(LiquidLogistics.inside(consumer,source.x+e.x,source.y+e.y))return true;return false;}
    private float orePenalty(BuildPlan p){
        float score=0;int o=-(p.block.size-1)/2;for(int dx=0;dx<p.block.size;dx++)for(int dy=0;dy<p.block.size;dy++)if(Vars.world.tile(p.x+o+dx,p.y+o+dy).drop()!=null)score+=1000;return score;
    }
    private void nodes(Station s,BuildPlan around){
        for(Point2 e:Edges.getEdges(around.block.size)){
            BuildPlan node=new BuildPlan(around.x+e.x,around.y+e.y,0,Blocks.powerNode);
            if(s.plans.contains(p->overlap(node,p)) || !brain.construction.place(node.block,node.x,node.y,0))continue;
            s.plans.add(node);return;
        }
    }
    public String describe(){return "公用设施："+phase+"；水耗 "+waterDemand()+"/秒/台，已验收 "+commissioned()+" 组。";}
    private static final class Station{
        final Seq<BuildPlan> plans=new Seq<>();float observed,generated,mean,nextConnectionCheck;boolean verified,connected;
        BuildPlan source(){return plans.find(p->p.block instanceof Pump);}
        BuildPlan generator(){return plans.find(p->p.block==Blocks.steamGenerator);}
        boolean complete(){return plans.allMatch(Construction::complete);}
    }
}
