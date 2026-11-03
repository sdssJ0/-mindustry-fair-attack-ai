package fairai;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.type.Item;
import mindustry.world.blocks.power.PowerGenerator.GeneratorBuild;
import mindustry.world.blocks.production.SolidPump.SolidPumpBuild;
import static fairai.SimulationTest.*;

/** Fixtures supply terrain/assets only; liquid extraction, fuel, power and construction are real engine updates. */
public final class UtilitiesTest{
    public static void main(String[] args){
        try{setup();run();System.out.println("PASS utilities: "+passed);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}
    }
    static void run(){naturalPumpSelection();waterSteamAndRepair();privateLeadModule();futureFuelSpace();}
    static void futureFuelSpace(){
        map(0);new FairAiMod().applyFairRules();stock();
        Vars.world.tile(96,58).setBlock(Blocks.pneumaticDrill,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        int copper=brain.core().items.get(Items.copper);brain.utilities.reserveFuelSpace();
        int pos=WorldMarks.getInt("fair-ai.utility-future."+brain.team.id,-1);
        check(pos>=0 && !brain.construction.place(Blocks.laserDrill,arc.math.geom.Point2.x(pos),arc.math.geom.Point2.y(pos),0),
            "An available future steam fuel mine is reserved before small raw mining can fill both coal patches");
        boolean starter=false;
        for(mindustry.world.Tile tile:Vars.world.tiles)if(tile.drop()==Items.coal && brain.construction.place(Blocks.pneumaticDrill,tile.x,tile.y,0) && brain.oreTiles((mindustry.world.blocks.production.Drill)Blocks.pneumaticDrill,tile,Items.coal)>=3)starter=true;
        check(starter && Vars.world.tile(96,58).block()==Blocks.pneumaticDrill && brain.core().items.get(Items.copper)==copper,
            "Future fuel reservation preserves the running starter mine, other legal coal mining and all real inventory");
        boolean guarded=false;
        for(mindustry.world.Tile t:Vars.world.tiles)if(DevelopmentPlanner.reserved(brain.team,t.pos()))for(int r=0;r<4;r++){
            int x=t.x-arc.math.geom.Geometry.d4x[r]*4,y=t.y-arc.math.geom.Geometry.d4y[r]*4;
            boolean empty=true;for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)if(DevelopmentPlanner.reserved(brain.team,arc.math.geom.Point2.pack(x+dx,y+dy)))empty=false;
            if(empty && mindustry.world.Build.validPlace(Blocks.groundFactory,brain.team,x,y,r) && !brain.construction.place(Blocks.groundFactory,x,y,r))guarded=true;
        }
        check(guarded,"A later unit factory cannot aim its output corridor through a reserved utility module despite its own footprint being empty");
        Vars.world.tile(105,34).setBlock(Blocks.kiln,Team.crux);brain.view.refresh();brain.development.armed=true;
        check(brain.utilities.plan() && brain.project.contains(p->p.block==Blocks.steamGenerator) && brain.project.contains(p->p.block==Blocks.laserDrill),
            "The utility planner can normally commission its own complete reserved steam module while unrelated projects remain excluded");brain.dispose();
    }
    static void privateLeadModule(){
        map(0);new FairAiMod().applyFairRules();stock();
        Vars.world.tile(100,36).setBlock(Blocks.pneumaticDrill,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        check(brain.processes.buildKiln() && brain.project.contains(p->p.block==Blocks.kiln) && brain.project.contains(p->p.block==Blocks.pneumaticDrill),
            "Glass bootstrap can commission a dedicated lead mine and kiln together when no existing lead output can be shared");
        int lead=brain.core().items.get(Items.lead);
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        frames(null,3600);brain.view.refresh();Building kiln=brain.view.buildings.find(b->b.block==Blocks.kiln);
        check(kiln!=null && kiln.items.get(Items.lead)>0 && brain.core().items.get(Items.lead)<lead,
            "The paid private module transfers actually mined lead into the kiln with no runtime material grants");
        check(brain.view.buildings.contains(b->b.block==Blocks.pneumaticDrill && brain.processes.assigned(b)) && brain.supplyLog.isEmpty(),
            "Private processing mine identity is retained and the worker does not haul its input");
        for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(kiln.block.size)){
            mindustry.world.Tile tile=Vars.world.tile(kiln.tileX()+e.x,kiln.tileY()+e.y);
            if(tile.block()==Blocks.air){tile.setBlock(Blocks.powerSource,Team.crux);break;} //Isolated production-power fixture.
        }
        frames(null,12000);
        check(brain.core().items.get(Items.metaglass)>2500 && brain.supplyLog.isEmpty(),
            "The complete kiln module actually processes and returns metaglass for several minutes without its future output contaminating its inputs");brain.dispose();
    }
    static void stock(){for(Item i:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.titanium,Items.metaglass})Team.crux.core().items.set(i,2500);}
    static void naturalPumpSelection(){
        map(2);new FairAiMod().applyFairRules();stock();
        Vars.world.tile(96,58).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(105,34).setBlock(Blocks.kiln,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);brain.development.armed=true;brain.economy.refresh(0);
        check(brain.utilities.plan() && brain.project.contains(p->p.block==Blocks.mechanicalPump),"Steam planning chooses a reachable natural water pump on the lake map");
        check(!brain.project.contains(p->p.block==Blocks.powerSource || p.block==Blocks.liquidSource || p.block==Blocks.itemSource),"Utility projects contain no testing resource or energy sources");
        check(brain.utilities.liquid.waterRate(Blocks.mechanicalPump,Vars.world.tile(83,50))>=7f-0.01f && UtilitiesPlanner.waterDemand()==6f,
            "Pump yield and steam water demand use v146 per-tick quantities, displayed per second");
        brain.dispose();
    }
    static String serialize(Seq<BuildPlan> plans){StringBuilder s=new StringBuilder();for(BuildPlan p:plans)s.append(p.x).append(',').append(p.y).append(',').append(p.rotation).append(',').append(p.block.id).append(';');return s.toString();}
    static float water(){float amount=0;for(Building b:Team.crux.data().buildings)if(b.liquids!=null)amount+=b.liquids.get(Liquids.water);return amount;}
    static void waterSteamAndRepair(){
        map(0);new FairAiMod().applyFairRules();stock();
        for(int x=94;x<=104;x++)for(int y=58;y<=63;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreCoal);
        Vars.world.tile(96,58).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(98,58).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(102,60).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(101,60).setBlock(Blocks.combustionGenerator,Team.crux);
        Vars.world.tile(94,54).setBlock(Blocks.waterExtractor,Team.crux);
        Vars.world.tile(97,56).setBlock(Blocks.steamGenerator,Team.crux);
        Seq<BuildPlan> station=Seq.with(new BuildPlan(94,54,0,Blocks.waterExtractor),new BuildPlan(95,56,0,Blocks.conduit),
            new BuildPlan(96,56,0,Blocks.conduit),new BuildPlan(97,56,0,Blocks.steamGenerator),
            new BuildPlan(96,54,0,Blocks.powerNode),new BuildPlan(100,57,0,Blocks.powerNode));
        Vars.state.rules.tags.put("fair-ai.utilities."+Team.crux.id,serialize(station));
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);brain.development.armed=true;
        check(brain.utilities.liquid.waterRate(Blocks.waterExtractor,Vars.world.tile(94,54))>UtilitiesPlanner.waterDemand(),
            "Groundwater output includes actual floor efficiency and exceeds one steam generator's water consumption");
        check(brain.utilities.capacity(Vars.world.build(97,56))==0,"A dry disconnected steam generator contributes no planned generation capacity");
        check(brain.submit(station,"test real groundwater and steam pipes"),"Water and power connections are submitted as normal paid construction plans");
        int glass=brain.core().items.get(Items.metaglass);
        for(int i=0;i<6000 && brain.project.any();i++)frame(brain);
        check(brain.project.isEmpty() && brain.core().items.get(Items.metaglass)<glass,"The core builder physically constructs and pays for liquid conduits and power nodes");
        SolidPumpBuild pump=(SolidPumpBuild)Vars.world.build(94,54);GeneratorBuild gen=(GeneratorBuild)Vars.world.build(97,56);
        for(int i=0;i<3000;i++){frame(null);brain.utilities.update(brain.time()+i);}
        System.out.println("Steam diagnostic pump="+pump.liquids+" efficiency="+pump.efficiency+" power="+pump.power.status+" gen="+gen.liquids+" items="+gen.items+" eff="+gen.productionEfficiency+" same="+(pump.power.graph==gen.power.graph)+" capacity="+brain.utilities.capacity(gen));
        System.out.println("Water route="+brain.utilities.liquid.feeds(pump,gen)+" rate="+brain.utilities.liquid.waterRate(pump.block,pump.tile)+" water="+gen.liquids.get(Liquids.water)+" plans="+station.allMatch(Construction::complete));
        for(Building b:brain.view.buildings)if(b.block==Blocks.pneumaticDrill)System.out.println("Fuel "+b.tileX()+","+b.tileY()+" output="+brain.construction.outputItem(b)+" feeds="+brain.construction.feeds(b,gen));
        for(BuildPlan p:station)if(p.tile().build!=null)System.out.println("Station component "+p.block.name+" "+p.x+","+p.y+" liquids="+p.tile().build.liquids+" power="+(p.tile().build.power==null ? "none":p.tile().build.power.status));
        check(brain.utilities.commissioned()==1 && gen.productionEfficiency>0.95f,"Coal mining and real extracted water commission steam power while the previous coal generator remains intact");
        check(pump.power.graph==gen.power.graph && pump.power.status>0.95f && gen.getPowerProduction()*60f>300,
            "The coal-powered bootstrap starts the extractor and reaches a net-positive shared steam grid");
        float initial=water(),created=0,spent=0;
        for(int i=0;i<1800;i++){frame(null);created+=pump.lastPump;spent+=gen.efficiency*UtilitiesPlanner.waterDemand()/60f;}
        check(Math.abs(initial+created-spent-water())<0.25f,"Extracted water balances across pump, pipes and steam consumption without granting liquid");
        check(brain.supplyLog.isEmpty() && brain.emergencyMiningTicks==0,"The commissioned utility station needs neither worker fuel hauling nor hand mining");
        Building backup=Vars.world.build(101,60);
        WorldMarks.put("fair-ai.owned."+Team.crux.id+"."+backup.pos(),backup.block.name);
        brain.economy.refresh(brain.time());brain.utilities.control();
        check(!backup.enabled && backup.isValid(),"Verified excess steam generation puts owned coal power on standby without deleting the backup");
        Vars.world.build(96,56).kill();brain.view.refresh();brain.utilities.update(brain.time()+4000);brain.utilities.control();
        check(backup.enabled,"Lost water supply immediately re-enables the existing coal bootstrap backup");
        check(brain.utilities.plan(),"The live station repairs its water line before optional expansion");
        for(int i=0;i<6000 && brain.project.any();i++)frame(brain);
        Fi file=new Fi("test-output/utilities.msav");SaveIO.write(file);brain.dispose();SaveIO.load(file);brain=new TeamBrain(Team.crux);brain.development.armed=true;
        check(brain.utilities.count()==1,"Station identity, water source and planned pipes survive an actual save/load");
        Vars.world.build(96,56).kill();brain.view.refresh();
        check(brain.utilities.capacity(Vars.world.build(97,56))==0,"A severed water line is excluded from reliable power even while the generator has buffered water");
        check(brain.utilities.plan() && brain.project.contains(p->p.x==96 && p.y==56),"A destroyed conduit schedules a normal paid station repair instead of a new generator");
        for(int i=0;i<6000 && brain.project.any();i++)frame(brain);
        check(Vars.world.tile(96,56).block()==Blocks.conduit && Vars.world.tile(101,60).block()==Blocks.combustionGenerator,
            "Real pipe repair preserves the old bootstrap power source");brain.dispose();
    }
}
