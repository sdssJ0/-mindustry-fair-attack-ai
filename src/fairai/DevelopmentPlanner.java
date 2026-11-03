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
    private Seq<BuildPlan> tertiary;
    private boolean t3Armed;
    private boolean t3Feasible;
    private int fortresses;
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
        if(id<0)id=WorldMarks.getInt("fair-ai.egress."+team.id+"."+position,-1);
        return id<0 ? null : Vars.content.item(id);
    }

    void initialize(){
        direction = Vars.state.rules.tags.getInt(key("direction"), -1);
        armed = "true".equals(Vars.state.rules.tags.get(key("armed")));
        released = Vars.state.rules.tags.getInt(key("released"), 0);
        t3Armed="true".equals(Vars.state.rules.tags.get(key("t3-armed")));
        fortresses=Vars.state.rules.tags.getInt(key("fortresses"),0);
        if(direction >= 0){ campus = layout(direction); initializeT3(); return; }
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
            initializeT3();
        }else phase = "核心周边没有完整 T2 工程空间，保留基础发展";
    }
    private final IntMap<Item> tertiaryFlows=new IntMap<>();
    private void initializeT3(){
        tertiary=layoutT3(direction);
        IntSet base=new IntSet();
        if(campus!=null)for(BuildPlan p:campus){int o=-(p.block.size-1)/2;for(int x=0;x<p.block.size;x++)for(int y=0;y<p.block.size;y++)base.add(Point2.pack(p.x+o+x,p.y+o+y));}
        t3Feasible=valid(tertiary);
        for(BuildPlan p:tertiary){int o=-(p.block.size-1)/2;for(int x=0;x<p.block.size;x++)for(int y=0;y<p.block.size;y++)if(base.contains(Point2.pack(p.x+o+x,p.y+o+y)))t3Feasible=false;}
        if(!t3Feasible){tertiary=null;return;}
        BuildPlan recon=tertiary.find(p->p.block==Blocks.multiplicativeReconstructor);
        reserveInputs(brain.team,recon.x,recon.y,recon.rotation);
        for(BuildPlan p:tertiary){
            int o=-(p.block.size-1)/2;
            for(int dx=0;dx<p.block.size;dx++)for(int dy=0;dy<p.block.size;dy++)reserve(p.x+o+dx,p.y+o+dy);
            Item item=tertiaryFlows.get(Point2.pack(p.x,p.y));
            if(item!=null){
                brain.construction.recordFlow(Seq.with(p),item);
                WorldMarks.put("fair-ai.campus-flow."+brain.team.id+"."+Point2.pack(p.x,p.y),Integer.toString(item.id));
                if(p.block==Blocks.itemBridge && p.config instanceof Integer && ((Integer)p.config)==-1)
                    for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)reserve(p.x+dx,p.y+dy);
            }
        }
        for(int x=brain.core().block.size/2+13;x<=brain.core().block.size/2+15;x++)for(int y=-1;y<=1;y++){Point2 p=position(x,y,direction);reserve(p.x,p.y);}
    }
    private void reserve(int x,int y){Tile tile=Vars.world.tile(x,y);if(tile!=null && tile.block()==Blocks.air)WorldMarks.put("fair-ai.reserve."+brain.team.id+"."+tile.pos(),"true");}
    Seq<BuildPlan> layoutT3(int d){
        tertiaryFlows.clear();Seq<BuildPlan> plans=new Seq<>();int h=brain.core().block.size/2;
        add(plans,d,Blocks.unloader,-h-1,-1,0,Items.silicon);
        add(plans,d,Blocks.unloader,h+1,1,0,Items.titanium);
        add(plans,d,Blocks.unloader,-h-1,1,0,Items.metaglass);
        int[][] silicon={{-3,-1,-3,-4},{-2,-4,2,-4},{3,-4,7,-4},{8,-4,12,-4},{13,-4,17,-4},{17,-3,17,1},{17,2,17,6},{16,6,12,6},{10,6,10,3}};
        int[][] titanium={{2,2,2,5},{3,5,7,5},{8,5,12,5},{13,5,17,5},{17,4,17,0},{17,-1,17,-5},{16,-5,12,-5},{10,-3,12,-3}};
        int[][] metaglass={{-3,1,-3,5},{-3,6,-3,10},{-2,10,2,10},{3,10,7,10},{8,10,12,10},{14,10,14,7},{14,6,14,2}};
        for(int[] link:silicon)moduleLink(plans,d,Items.silicon,link);
        for(int[] link:titanium)moduleLink(plans,d,Items.titanium,link);
        for(int[] link:metaglass)moduleLink(plans,d,Items.metaglass,link);
        moduleBelt(plans,d,Items.silicon,11,6,2);
        moduleBelt(plans,d,Items.titanium,11,-5,2);moduleBelt(plans,d,Items.titanium,10,-5,1);moduleBelt(plans,d,Items.titanium,10,-4,1);
        moduleBelt(plans,d,Items.metaglass,13,10,0);
        add(plans,d,Blocks.powerNode,h+10,4,0,null);
        add(plans,d,Blocks.multiplicativeReconstructor,h+10,0,0,null);
        return plans;
    }
    private int moduleX(int x){int shift=brain.core().block.size/2-1;return x<0 ? x-shift:x+shift;}
    private void moduleLink(Seq<BuildPlan> plans,int d,Item item,int[] coordinates){
        int sx=moduleX(coordinates[0]),sy=coordinates[1],rx=moduleX(coordinates[2]),ry=coordinates[3];
        int dx=Integer.signum(rx-sx),dy=Integer.signum(ry-sy),range=((mindustry.world.blocks.distribution.ItemBridge)Blocks.itemBridge).range;
        while(Math.abs(rx-sx)+Math.abs(ry-sy)>range){int distance=Math.min(range,Math.abs(rx-sx)+Math.abs(ry-sy)-2);modulePair(plans,d,item,sx,sy,sx+dx*distance,sy+dy*distance);sx+=dx*(distance+1);sy+=dy*(distance+1);}
        modulePair(plans,d,item,sx,sy,rx,ry);
    }
    private void modulePair(Seq<BuildPlan> plans,int d,Item item,int sx,int sy,int rx,int ry){
        Point2 sender=position(sx,sy,d),receiver=position(rx,ry,d);
        plans.add(new BuildPlan(sender.x,sender.y,0,Blocks.itemBridge,new Point2(receiver.x-sender.x,receiver.y-sender.y)));
        plans.add(new BuildPlan(receiver.x,receiver.y,0,Blocks.itemBridge,Integer.valueOf(-1)));
        tertiaryFlows.put(sender.pack(),item);tertiaryFlows.put(receiver.pack(),item);
    }
    private void moduleBelt(Seq<BuildPlan> plans,int d,Item item,int x,int y,int rotation){
        Point2 point=position(moduleX(x),y,d);plans.add(new BuildPlan(point.x,point.y,(rotation+d)%4,Blocks.conveyor));tertiaryFlows.put(point.pack(),item);
    }
    static Point2 inputPort(int x,int y,int rotation,Item item){
        int dx=item==Items.silicon ? -1:item==Items.metaglass ? 3:1,dy=item==Items.titanium ? -3:item==Items.metaglass ? 2:3;
        for(int i=0;i<rotation;i++){int old=dx;dx=-dy;dy=old;}
        return new Point2(x+dx,y+dy);
    }
    static void reserveInputs(Team team,int x,int y,int rotation){
        for(Item item:new Item[]{Items.silicon,Items.titanium,Items.metaglass}){
            Point2 port=inputPort(x,y,rotation,item);
            for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++){
                Tile tile=Vars.world.tile(port.x+dx,port.y+dy);
                if(tile!=null && tile.block()==Blocks.air)WorldMarks.put("fair-ai.reserve."+team.id+"."+tile.pos(),"true");
            }
        }
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
        ReconstructorBuild advanced=t3Reconstructor();
        if(advanced!=null && advanced.payload!=null && advanced.payload.unit.type==UnitTypes.fortress && Vars.state.rules.tags.getInt(key("last-fortress"),-1)!=advanced.payload.unit.id){
            Vars.state.rules.tags.put(key("last-fortress"),Integer.toString(advanced.payload.unit.id));
            fortresses++;Vars.state.rules.tags.put(key("fortresses"),Integer.toString(fortresses));
        }
    }
    void created(mindustry.game.EventType.UnitCreateEvent event){
        if(event.unit.team!=brain.team || event.spawner==null)return;
        int id=event.unit.id;
        if(event.spawner==reconstructor() && event.unit.type==UnitTypes.mace && Vars.state.rules.tags.getInt(key("last-upgraded"),-1)!=id){
            Vars.state.rules.tags.put(key("last-upgraded"),Integer.toString(id));
            released++;Vars.state.rules.tags.put(key("released"),Integer.toString(released));
        }
        if(event.spawner==t3Reconstructor() && event.unit.type==UnitTypes.fortress && Vars.state.rules.tags.getInt(key("last-fortress"),-1)!=id){
            Vars.state.rules.tags.put(key("last-fortress"),Integer.toString(id));
            fortresses++;Vars.state.rules.tags.put(key("fortresses"),Integer.toString(fortresses));
        }
    }
    public boolean owns(Building building){
        return campus != null && campus.contains(p -> p.x==building.tileX() && p.y==building.tileY() && p.block==building.block) || tertiary!=null && tertiary.contains(p->p.x==building.tileX() && p.y==building.tileY() && p.block==building.block);
    }
    public boolean complete(){ return campus != null && campus.allMatch(Construction::complete); }
    public boolean preparing(){ return armed && !blocked && !complete(); }
    public int upgraded(){ return released; }
    public boolean t3Active(){return t3Armed;}
    public boolean savingT3(){
        if(!preparingT3())return false;
        for(ObjectIntMap.Entry<Item> cost:brain.construction.costs(tertiary)){
            if(brain.core().items.get(cost.key)>=cost.value)continue;
            Item item=cost.key;
            if(!brain.view.buildings.contains(b->brain.construction.outputItem(b)==item && ledger.flowing(b,clock) && brain.construction.feeds(b,brain.core())))return false;
        }
        return true;
    }
    public boolean t3Complete(){return tertiary!=null && tertiary.allMatch(Construction::complete);}
    public int t3Upgraded(){return fortresses;}
    private boolean preparingT3(){return t3Armed && !t3Complete() && tertiary!=null && t3Feasible;}
    public ReconstructorBuild t3Reconstructor(){
        if(tertiary==null)return null;BuildPlan p=tertiary.find(x->x.block==Blocks.multiplicativeReconstructor);
        return p!=null && p.tile()!=null && p.tile().build instanceof ReconstructorBuild ? (ReconstructorBuild)p.tile().build : null;
    }
    public Building inputSource(Item item){
        if(tertiary==null || item!=Items.silicon && item!=Items.titanium && item!=Items.metaglass)return null;
        BuildPlan recon=tertiary.find(p->p.block==Blocks.multiplicativeReconstructor);
        Point2 port=inputPort(recon.x,recon.y,recon.rotation,item);
        BuildPlan plan=tertiary.find(p->p.block==Blocks.itemBridge && p.x==port.x && p.y==port.y);
        return plan!=null && Construction.complete(plan) ? plan.tile().build:null;
    }
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
        int amount=0;
        if(preparing())for(ObjectIntMap.Entry<Item> cost : brain.construction.costs(campus)) if(cost.key == item) amount+=cost.value;
        if(preparingT3())for(ObjectIntMap.Entry<Item> cost:brain.construction.costs(tertiary))if(cost.key==item)amount+=cost.value;
        if(amount>0)return amount;
        return 0;
    }
    public int stockGoal(Item item){ return !armed || blocked ? 0 : Math.max(reserve(item)+40, item==Items.graphite ? 160 : item==Items.silicon ? 360 : t3Armed && item==Items.titanium ? 200 : t3Armed && item==Items.metaglass ? 160 : 0); }
    public float demand(Item item){ return armed && !blocked ? (t3Armed ? RecipeCatalog.t3Demand() : rates).get(item,0) : 0; }
    public float powerDemand(){ return (complete() ? Blocks.additiveReconstructor.consPower.usage*60f : 0)+(t3Complete() ? Blocks.multiplicativeReconstructor.consPower.usage*60f : 0); }

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
        if(tertiary!=null && (t3Armed || released>=2 && brain.utilities.commissioned()>=1 && brain.economy.powerSupply>=brain.economy.powerDemand+brain.utilities.extraPower() && brain.view.deposit(Items.thorium,brain.core())!=null)){
            t3Armed=true;Vars.state.rules.tags.put(key("t3-armed"),"true");
            if(!valid(tertiary)){clearWorkSite();phase="T3 施工位置暂不可用；保留工程预算，等待通道清空";return false;}
            if(!brain.hasProduction(Items.thorium)){
                phase="T3 前置：建立真实激光钍矿、回库和电网";return brain.buildDrill(Items.thorium,null);
            }
            if(!t3Complete()){
                phase="T3 工程：积累钍、钛、硅、铅并建设高级重构厂";
                return brain.submit(tertiary,phase);
            }
            phase="T3 产线运行；已完成 fortress 重构 "+fortresses+" 次";
        }
        return false;
    }

    private void clearWorkSite(){
        for(BuildPlan p:tertiary){
            if(!p.block.solid && !p.block.solidifes)continue;
            float x=p.x*8f+p.block.offset,y=p.y*8f+p.block.offset,half=p.block.size*4f;
            for(Unit u:Groups.unit){
                if(u.team!=brain.team || u.isPlayer() || u.isFlying() || !(u.controller() instanceof mindustry.ai.types.CommandAI))continue;
                if(Math.abs(u.x-x)<half+u.hitSize/2f && Math.abs(u.y-y)<half+u.hitSize/2f){
                    ((mindustry.ai.types.CommandAI)u.controller()).commandPosition(new Vec2(x,arc.math.Mathf.clamp(y+half+48f,24f,Vars.world.unitHeight()-24f)));
                }
            }
        }
    }

    void control(){
        if(campus == null || !armed) return;
        boolean complete = complete();
        boolean repairing = brain.project.any();
        boolean canRun = complete && !repairing && !preparingT3();
        boolean reserveSlot=!t3Complete() && brain.view.deposit(Items.thorium,brain.core())!=null &&
            Groups.unit.count(u->u.team==brain.team && u.type==UnitTypes.mace)>=Math.max(1,Units.getCap(brain.team)-1);
        if(reserveSlot)canRun=false;
        for(BuildPlan p : campus){
            Building b = p.tile().build;
            if(b == null || b.block != p.block) continue;
            if(b instanceof UnloaderBuild){
                UnloaderBuild u = (UnloaderBuild)b;
                if(u.sortItem != p.config) u.configure(p.config);
                int reserve = Math.max(u.sortItem == Items.silicon ? 60 : u.sortItem == Items.graphite ? 20 : 40,brain.economy.reserve(u.sortItem));
                b.control(LAccess.enabled,canRun && brain.core().items.get(u.sortItem)>reserve ? 1:0,0,0,0);
            }else if(b instanceof UnitFactoryBuild || b instanceof ReconstructorBuild){
                b.control(LAccess.enabled,canRun ? 1:0,0,0,0);
            }
        }
        ReconstructorBuild advanced=t3Reconstructor();
        if(advanced!=null)advanced.control(LAccess.enabled,t3Complete() && !repairing ? 1:0,0,0,0);
        if(tertiary!=null)for(BuildPlan p:tertiary)if(p.block==Blocks.unloader && Construction.complete(p)){
            UnloaderBuild u=(UnloaderBuild)p.tile().build;
            if(u.sortItem!=p.config)u.configure(p.config);
            u.control(LAccess.enabled,t3Complete() && !repairing && brain.core().items.get(u.sortItem)>brain.economy.reserve(u.sortItem) ? 1:0,0,0,0);
        }
        if(complete && !Units.canCreate(brain.team,UnitTypes.dagger) && !Units.canCreate(brain.team,UnitTypes.mace)) phase="T2 产线等待正常单位上限释放";
    }
    public String describe(){ return phase + "\n自动重构：T2 已完成 "+released+"；T3 已完成 "+fortresses+"。T4～T5 仍待后续适配。"; }
}
