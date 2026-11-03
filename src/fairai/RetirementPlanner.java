package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.*;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;

/** Commission replacements before normal player deconstruction; no direct tile removal or fake refunds. */
public final class RetirementPlanner{
    private final TeamBrain brain;
    private float nextUpgrade;
    public String phase = "保留现有有效产线";
    public int retired;
    RetirementPlanner(TeamBrain brain){
        this.brain=brain;
        retired=Vars.state.rules.tags.getInt(key("retired-count"),0);
    }
    private String key(String suffix){ return "fair-ai.renewal."+brain.team.id+"."+suffix; }
    private boolean basic(Building b){
        return b.block==Blocks.graphitePress || b.block==Blocks.siliconSmelter || b.block==Blocks.groundFactory;
    }

    boolean canRetire(Building b,float clock){
        if(b==null || !b.isValid() || b.team!=brain.team || !basic(b) || brain.development.owns(b) || brain.defense.assigned(b)) return false;
        if(b instanceof UnitFactoryBuild){
            UnitFactoryBuild f=(UnitFactoryBuild)b;
            return b.block==Blocks.groundFactory && f.payload==null && f.currentPlan==0 &&
                brain.development.upgraded()>=2 && Groups.unit.contains(u -> u.team==brain.team && u.type==UnitTypes.mace);
        }
        GenericCrafter block=(GenericCrafter)b.block;
        Item item=brain.construction.outputItem(b);
        if(brain.core().items.get(item)<Math.max(brain.economy.reserve(item),brain.economy.stockGoal(item))) return false;
        if(brain.view.buildings.contains(other -> other!=b && !(other instanceof CoreBuild) &&
            brain.inputs(other.block,other).contains(item,true) && brain.construction.feeds(b,other))) return false;
        int good=brain.view.buildings.count(other -> other!=b && other.block==block && !brain.defense.assigned(other) &&
            brain.construction.feeds(other,brain.core()) && brain.development.ledger.observed(other,clock));
        return good>=brain.economy.machinesWanted(block);
    }

    private boolean remove(Building old,String reason){
        Seq<BuildPlan> plans=new Seq<>();
        //Only remove a private terminal belt, never a shared upstream route.
        for(Building b:brain.view.buildings) if(b.front()==old && b.block instanceof mindustry.world.blocks.distribution.Conveyor &&
            DevelopmentPlanner.owned(brain.team,b)) plans.add(new BuildPlan(b.tileX(),b.tileY()));
        plans.add(new BuildPlan(old.tileX(),old.tileY()));
        if(!brain.submit(plans,reason)) return false;
        Vars.state.rules.tags.put(key("removing"),Integer.toString(old.pos()));
        Vars.state.rules.tags.put(key("removing-block"),old.block.name);
        phase=reason; return true;
    }

    boolean guard(BuildPlan plan){
        int removing=Vars.state.rules.tags.getInt(key("removing"),-1);
        if(removing<0 || !plan.breaking) return true;
        Building old=Vars.world.build(removing);
        if(old==null || old instanceof ConstructBuild) return true;
        if(!old.block.name.equals(Vars.state.rules.tags.get(key("removing-block")))) return false;
        if(old instanceof DrillBuild){
            int replacement=Vars.state.rules.tags.getInt(key("replacement"),-1);
            Building next=Vars.world.build(replacement);
            return next instanceof DrillBuild && brain.development.ledger.commissioned(next,brain.time()) &&
                brain.construction.feeds(next,brain.core()) && !brain.defense.assigned(old);
        }
        return canRetire(old,brain.time());
    }
    boolean protectsReplacement(Building building){
        return Vars.state.rules.tags.getInt(key("replacement"),-1)==building.pos() &&
            (Vars.state.rules.tags.getInt(key("old-mine"),-1)>=0 || Vars.state.rules.tags.getInt(key("removing"),-1)>=0);
    }

    boolean plan(float clock){
        int removing=Vars.state.rules.tags.getInt(key("removing"),-1);
        if(removing>=0){
            Tile tile=Vars.world.tile(removing);
            if(tile!=null && tile.block()==Blocks.air){
                retired++; Vars.state.rules.tags.put(key("retired-count"),Integer.toString(retired));
                Vars.state.rules.tags.remove(key("removing")); Vars.state.rules.tags.remove(key("removing-block"));
                Vars.state.rules.tags.remove(key("replacement")); Vars.state.rules.tags.remove(key("old-mine"));
                phase="完成正常拆除；保留替代产能与原版返还";
            }else if(tile!=null && tile.build instanceof ConstructBuild){
                return brain.submit(Seq.with(new BuildPlan(tile.x,tile.y)),"继续已保存的拆除工程");
            }else if(tile!=null && tile.build!=null){
                if(guard(new BuildPlan(tile.x,tile.y))) return remove(tile.build,"继续安全拆除已冗余设施");
                Vars.state.rules.tags.remove(key("removing")); phase="替代设施失效，取消拆除";
            }
        }
        if(!brain.development.armed) return false;
        int oldMine=Vars.state.rules.tags.getInt(key("old-mine"),-1);
        int replacement=Vars.state.rules.tags.getInt(key("replacement"),-1);
        if(oldMine>=0){
            Building old=Vars.world.build(oldMine), next=Vars.world.build(replacement);
            if(old instanceof DrillBuild && next instanceof DrillBuild && brain.development.ledger.commissioned(next,clock) &&
                brain.construction.feeds(next,brain.core()) && !brain.defense.assigned(old)){
                return remove(old,"新激光矿机已实际投产，拆除旧钻头");
            }
            if(next==null || old==null){ Vars.state.rules.tags.remove(key("old-mine")); Vars.state.rules.tags.remove(key("replacement")); }
            else{ phase="等待已建激光矿机的供电、回库与真实产出，旧线继续保留"; return false; }
        }
        for(Building b:brain.view.buildings) if(canRetire(b,clock)) return remove(b,"拆除已有可靠替代产能的冗余工厂："+b.block.localizedName);
        if(!brain.development.complete() || brain.development.upgraded()<2 || clock<nextUpgrade) return false;
        nextUpgrade=clock+1800f;
        if(brain.core().items.get(Items.titanium)<50 || brain.core().items.get(Items.silicon)<160 || brain.core().items.get(Items.graphite)<80) return false;
        //Additional powered mining must fit the supported generation expansion budget.
        if(brain.economy.powerDemand+Blocks.laserDrill.consPower.usage*60f*1.08f>12f*60f+brain.utilities.commissioned()*((mindustry.world.blocks.power.PowerGenerator)Blocks.steamGenerator).powerProduction*60f){
            phase="当前煤电方案已到扩展预算，保留现有矿业；后续需要液体与蒸汽供电适配"; return false;
        }
        if(brain.view.deposit(Items.thorium,brain.core())!=null && !brain.hasProduction(Items.thorium)){
            phase="建立激光采钍及真实回库、电力配套";
            return brain.buildDrill(Items.thorium,null);
        }
        return upgradeMine();
    }

    boolean upgradeMine(){
        Drill laser=(Drill)Blocks.laserDrill;
        Seq<Building> mines=brain.view.buildings.copy();
        mines.sort(b -> b instanceof DrillBuild && ((DrillBuild)b).dominantItem==Items.titanium ? 0 : 1);
        for(Building old:mines){
            if(!(old instanceof DrillBuild) || old.block!=Blocks.pneumaticDrill || brain.defense.assigned(old) || brain.processes.assigned(old) ||
                !brain.construction.feeds(old,brain.core())) continue;
            Item item=((DrillBuild)old).dominantItem;
            if(item==null || RawLogistics.processingInput(item)) continue;
            Seq<Tile> sites=new Seq<>();
            for(int dx=-5;dx<=5;dx++) for(int dy=-5;dy<=5;dy++){
                Tile tile=Vars.world.tile(old.tileX()+dx,old.tileY()+dy);
                if(tile==null || tile.drop()!=item || !brain.construction.place(laser,tile.x,tile.y,0)) continue;
                boolean mixed=false;
                for(int ox=-1;ox<=1;ox++) for(int oy=-1;oy<=1;oy++){
                    Item other=Vars.world.tile(tile.x+ox,tile.y+oy).drop();
                    if(other!=null && other!=item && other!=Items.sand) mixed=true;
                }
                if(mixed) continue;
                float rate=3600f*brain.oreTiles(laser,tile,item)/laser.getDrillTime(item);
                if(rate>brain.economy.drillRate((DrillBuild)old)*1.25f) sites.add(tile);
            }
            sites.sort(t -> -brain.oreTiles(laser,t,item));
            for(Tile site:sites){
                Seq<BuildPlan> plans=brain.construction.drillRoute(new BuildPlan(site.x,site.y,0,laser),brain.core());
                if(plans==null) continue;
                for(Point2 edge:Edges.getEdges(laser.size)){
                    int x=site.x+edge.x,y=site.y+edge.y;
                    if(!plans.contains(p -> p.x==x && p.y==y) && brain.construction.place(Blocks.powerNode,x,y,0)){
                        plans.add(new BuildPlan(x,y,0,Blocks.powerNode)); break;
                    }
                }
                if(brain.submit(plans,"先建设更高产能激光矿机，旧线保持运行")){
                    brain.construction.recordFlow(plans,item);
                    Vars.state.rules.tags.put(key("old-mine"),Integer.toString(old.pos()));
                    Vars.state.rules.tags.put(key("replacement"),Integer.toString(site.pos()));
                    phase="激光矿业迁移：等待真实产出与电力验收"; return true;
                }
            }
        }
        return false;
    }
    public String describe(){ return "设施更新："+phase+"；已正常拆除 "+retired+" 座冗余设施。"; }
}
