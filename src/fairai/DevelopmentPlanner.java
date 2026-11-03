package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.logic.LAccess;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.storage.Unloader.UnloaderBuild;
import mindustry.world.blocks.units.*;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;

/** A persistent, normally paid T2 project with actual core unloaders and a vanilla payload handoff. */
public final class DevelopmentPlanner{
    private final TeamBrain brain;
    public final ProductionLedger ledger = new ProductionLedger();
    private final ObjectFloatMap<Item> rates = RecipeCatalog.t2Demand();
    private Seq<BuildPlan> campus;
    private float clock;
    public boolean armed;
    public boolean blocked;
    public String phase = "稳定基础经济与防线";
    private int direction = -1;
    private int released;

    DevelopmentPlanner(TeamBrain brain){ this.brain = brain; }
    private String key(String suffix){ return "fair-ai.development." + brain.team.id + "." + suffix; }
    public static boolean reserved(Team team, int position){
        return "true".equals(WorldMarks.get("fair-ai.reserve." + team.id + "." + position));
    }
    public static boolean owned(Team team, Building building){
        return building != null && building.block.name.equals(WorldMarks.get("fair-ai.owned." + team.id + "." + building.pos()));
    }
    public static Item busItem(Team team,int position){
        int id=WorldMarks.getInt("fair-ai.campus-flow."+team.id+"."+position,-1);
        return id<0 ? null : Vars.content.item(id);
    }

    void initialize(){
        direction = Vars.state.rules.tags.getInt(key("direction"), -1);
        armed = "true".equals(Vars.state.rules.tags.get(key("armed")));
        released = Vars.state.rules.tags.getInt(key("released"), 0);
        if(direction >= 0){ campus = layout(direction); return; }
        int best = Integer.MAX_VALUE;
        for(int d = 0; d < 4; d++){
            Seq<BuildPlan> plans = layout(d);
            if(!valid(plans)) continue;
            int score = 0;
            for(BuildPlan p : plans) if(p.tile().drop() != null) score += 100;
            if(score < best){ best = score; direction = d; campus = plans; }
        }
        if(campus != null){
            Vars.state.rules.tags.put(key("direction"), Integer.toString(direction));
            for(int dx=brain.core().block.size/2+8;dx<=brain.core().block.size/2+10;dx++) for(int dy=-1;dy<=1;dy++){
                Point2 exit=position(dx,dy,direction);
                Tile tile=Vars.world.tile(exit.x,exit.y);
                if(tile!=null && tile.block()==Blocks.air) WorldMarks.put("fair-ai.reserve."+brain.team.id+"."+tile.pos(),"true");
            }
            for(BuildPlan p : campus){
                int offset = -(p.block.size - 1)/2;
                for(int dx = 0; dx < p.block.size; dx++) for(int dy = 0; dy < p.block.size; dy++){
                    Tile tile = Vars.world.tile(p.x + offset + dx, p.y + offset + dy);
                    if(tile != null && tile.block() == Blocks.air) WorldMarks.put("fair-ai.reserve."+brain.team.id+"."+tile.pos(), "true");
                }
                if(p.block==Blocks.conveyor){
                    int x=p.x-brain.core().tileX(),y=p.y-brain.core().tileY();
                    for(int i=0;i<direction;i++){int old=x;x=y;y=-old;}
                    Item item=y>0 ? Items.graphite : Items.silicon;
                    WorldMarks.put("fair-ai.campus-flow."+brain.team.id+"."+Point2.pack(p.x,p.y),Integer.toString(item.id));
                }
            }
        }else phase = "核心周边没有完整 T2 工程空间，保留基础发展";
    }

    private Point2 position(int x, int y, int rotation){
        for(int i = 0; i < rotation; i++){ int old = x; x = -y; y = old; }
        return new Point2(brain.core().tileX()+x, brain.core().tileY()+y);
    }
    private void add(Seq<BuildPlan> plans, int d, Block block, int x, int y, int rotation, Object config){
        Point2 p = position(x,y,d);
        plans.add(new BuildPlan(p.x,p.y,(rotation+d)%4,block,config));
    }
    Seq<BuildPlan> layout(int d){
        Seq<BuildPlan> plans = new Seq<>();
        int h = brain.core().block.size/2, factory = h+3, recon = h+6, bus = h+2;
        //Infrastructure first; all production stays disabled until the whole project is commissioned.
        add(plans,d,Blocks.unloader,h+1,-1,0,Items.lead);
        add(plans,d,Blocks.unloader,h+1,0,0,Items.silicon);
        add(plans,d,Blocks.unloader,0,h+1,0,Items.graphite);
        add(plans,d,Blocks.unloader,0,-h-1,0,Items.silicon);
        //Separate commodity lines cannot deadlock behind a full magazine of the other input.
        for(int x = 0; x < recon; x++){
            add(plans,d,Blocks.conveyor,x,bus,0,null);
            add(plans,d,Blocks.conveyor,x,-bus,0,null);
        }
        for(int y = bus; y >= 2; y--){
            add(plans,d,Blocks.conveyor,recon,y,3,null);
            add(plans,d,Blocks.conveyor,recon,-y,1,null);
        }
        add(plans,d,Blocks.powerNode,factory,2,0,null);
        add(plans,d,Blocks.additiveReconstructor,recon,0,0,null);
        add(plans,d,Blocks.groundFactory,factory,0,0,((UnitFactory)Blocks.groundFactory).plans.indexOf(p -> p.unit==UnitTypes.dagger));
        return plans;
    }

    private boolean valid(Seq<BuildPlan> plans){
        IntSet occupied = new IntSet();
        for(BuildPlan p : plans){
            if(Construction.complete(p)) continue;
            Tile tile = p.tile();
            if(tile != null && tile.build instanceof ConstructBuild && ((ConstructBuild)tile.build).current == p.block) continue;
            if(!brain.construction.placeDevelopment(p.block,p.x,p.y,p.rotation)) return false;
            int offset = -(p.block.size-1)/2;
            for(int x=0;x<p.block.size;x++) for(int y=0;y<p.block.size;y++) if(!occupied.add(Point2.pack(p.x+offset+x,p.y+offset+y))) return false;
        }
        return true;
    }
    public void update(float clock){
        this.clock = clock;
        ledger.update(brain.view.buildings,clock);
        if(!armed && campus != null && brain.economy.mobilized && brain.team.data().unitCount >= 5){
            armed = true; Vars.state.rules.tags.put(key("armed"),"true");
        }
        ReconstructorBuild recon = reconstructor();
        if(recon != null && recon.payload != null && recon.payload.unit.type == UnitTypes.mace){
            int id = recon.payload.unit.id;
            if(Vars.state.rules.tags.getInt(key("last-upgraded"),-1) != id){
                Vars.state.rules.tags.put(key("last-upgraded"),Integer.toString(id));
                released++; Vars.state.rules.tags.put(key("released"),Integer.toString(released));
            }
        }
    }
    public boolean owns(Building building){
        return campus != null && campus.contains(p -> p.x==building.tileX() && p.y==building.tileY() && p.block==building.block);
    }
    public boolean complete(){ return campus != null && campus.allMatch(Construction::complete); }
    public boolean preparing(){ return armed && !blocked && !complete(); }
    public int upgraded(){ return released; }
    public ReconstructorBuild reconstructor(){
        if(campus == null) return null;
        BuildPlan p = campus.find(plan -> plan.block==Blocks.additiveReconstructor);
        return p.tile().build instanceof ReconstructorBuild ? (ReconstructorBuild)p.tile().build : null;
    }
    public UnitFactoryBuild factory(){
        if(campus == null) return null;
        BuildPlan p = campus.find(plan -> plan.block==Blocks.groundFactory);
        return p.tile().build instanceof UnitFactoryBuild ? (UnitFactoryBuild)p.tile().build : null;
    }
    public int reserve(Item item){
        if(!preparing()) return 0;
        for(ObjectIntMap.Entry<Item> cost : brain.construction.costs(campus)) if(cost.key == item) return cost.value;
        return 0;
    }
    public int stockGoal(Item item){ return !armed || blocked ? 0 : Math.max(reserve(item)+40, item==Items.graphite ? 160 : item==Items.silicon ? 360 : 0); }
    public float demand(Item item){ return armed && !blocked ? rates.get(item,0) : 0; }
    public float powerDemand(){ return complete() ? Blocks.additiveReconstructor.consPower.usage*60f : 0; }

    boolean plan(){
        if(!armed || campus == null) return false;
        blocked=!complete() && !valid(campus);
        if(blocked){ phase="T2 工程受阻：保留基础生产与军队，等待可用施工通道"; return false; }
        if(!brain.hasProduction(Items.titanium)){
            phase = "T2 工程：先建立钛产线以支付自动卸载器";
            return brain.buildDrill(Items.titanium,null);
        }
        if(!complete()){
            phase = "T2 工程：积累预算并建设自动供料与载荷对接";
            if(!brain.construction.affordable(campus,brain.core())) return false;
            if(!brain.submit(campus,"T2 工程：自动卸载、兵厂和重构产线")) return false;
            for(BuildPlan p:campus) if(p.block==Blocks.conveyor) brain.construction.recordFlow(Seq.with(p),busItem(brain.team,Point2.pack(p.x,p.y)));
            return true;
        }
        phase = "T2 产线运行；已完成重构 " + released + " 次";
        return false;
    }

    void control(){
        if(campus == null || !armed) return;
        boolean complete = complete();
        boolean repairing = brain.project.any();
        boolean canRun = complete && !repairing;
        for(BuildPlan p : campus){
            Building b = p.tile().build;
            if(b == null || b.block != p.block) continue;
            if(b instanceof UnloaderBuild){
                UnloaderBuild u = (UnloaderBuild)b;
                if(u.sortItem == null) u.configure(p.config);
                int reserve = u.sortItem == Items.silicon ? 60 : u.sortItem == Items.graphite ? 20 : 40;
                b.control(LAccess.enabled,canRun && brain.core().items.get(u.sortItem)>reserve ? 1:0,0,0,0);
            }else if(b instanceof UnitFactoryBuild || b instanceof ReconstructorBuild){
                b.control(LAccess.enabled,canRun ? 1:0,0,0,0);
            }
        }
        if(complete && !Units.canCreate(brain.team,UnitTypes.dagger) && !Units.canCreate(brain.team,UnitTypes.mace)) phase="T2 产线等待正常单位上限释放";
    }
    public String describe(){ return phase + "\n当前自动重构能力：T2；T3～T5 的液体和高级物料适配仍待后续阶段。"; }
}
