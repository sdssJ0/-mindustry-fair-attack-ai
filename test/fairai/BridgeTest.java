package fairai;

import arc.files.Fi;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.io.SaveIO;
import mindustry.type.Item;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import static fairai.SimulationTest.*;

/** Real bridge links cross an impassable full-height barrier and physically carry mined inputs. */
public final class BridgeTest{
    public static void main(String[] args){try{setup();run();System.out.println("PASS bridge: "+passed);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
    static void run(){
        processingCrossing();coreCollectors();preserveExistingOutput();separateT3Inputs();coreSpineReuse();directionalIsolation();rejectReceiverSideInput();bridgeFallbackKeepsCollector();
    }
    static void processingCrossing(){
        map(0);new FairAiMod().applyFairRules();
        for(Item i:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.metaglass})Team.crux.core().items.set(i,2500);
        for(int y=0;y<Vars.world.height();y++)Vars.world.tile(94,y).setBlock(Blocks.stoneWall);
        Vars.world.tile(83,58).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(101,61).setBlock(Blocks.kiln,Team.crux);
        Vars.world.build(101,61).items.set(Items.sand,10); //Finite initial fixture input; no runtime grants.
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        Building mine=Vars.world.build(83,58),kiln=Vars.world.build(101,61);
        Seq<BuildPlan> route=brain.construction.branchRoute(mine,kiln);
        check(route!=null && route.contains(p->p.block==Blocks.itemBridge && p.config instanceof arc.math.geom.Point2),
            "A disconnected processing input finds normally configured bridge crossings rather than deleting terrain");
        check(!route.contains(p->p.x==94) && route.count(p->p.block==Blocks.itemBridge)>=2,
            "Both bridge endpoints stay on buildable tiles while the full-height obstacle remains intact");
        int lead=brain.core().items.get(Items.lead);
        check(brain.submit(route,"test paid lead bridge"),"Crossing endpoints and conveyor approaches are one affordable construction project");
        brain.construction.recordFlow(route,Items.lead);
        for(int i=0;i<9000 && brain.project.any();i++)frame(brain);
        check(brain.project.isEmpty() && brain.core().items.get(Items.lead)<lead,"The real core builder spends lead on bridge construction");
        frames(null,3600);brain.view.refresh();
        if(kiln.items.get(Items.lead)==0)for(BuildPlan p:route)System.out.println("Bridge part "+p.x+","+p.y+" "+p.block+" config="+p.tile().build.config()+" items="+p.tile().build.items);
        check(brain.construction.feeds(mine,kiln) && (kiln.items.get(Items.lead)>0 || kiln.items.get(Items.metaglass)>0),
            "Actual mined lead crosses the configured bridges and reaches the kiln without worker hauling");
        check(Vars.world.tile(94,60).block()==Blocks.stoneWall && brain.supplyLog.isEmpty(),"Transport preserves terrain and requires no core-ship input delivery");
        Fi file=new Fi("test-output/bridge.msav");SaveIO.write(file);brain.dispose();SaveIO.load(file);brain=new TeamBrain(Team.crux);
        check(brain.construction.feeds(Vars.world.build(83,58),Vars.world.build(101,61)),"Bridge links and commodity identity survive actual save/load");
        boolean valid=true;
        for(Building b:brain.view.buildings)if(b.block==Blocks.itemBridge && ((ItemBridgeBuild)b).link!=-1)
            valid &= ((ItemBridge)b.block).linkValid(b.tile,Vars.world.tile(((ItemBridgeBuild)b).link));
        check(valid,"All saved bridge links obey the original range and team rules");brain.dispose();
    }
    static void coreCollectors(){
        map(0);new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper,2500);Team.crux.core().items.set(Items.lead,2500);
        for(int y=0;y<Vars.world.height();y++)Vars.world.tile(85,y).setBlock(Blocks.stoneWall);
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(3)){
            int x=90+e.x,y=44+e.y;int rotation=x<89 ? 0:x>91 ? 2:y<43 ? 1:3;
            Vars.world.tile(x,y).setBlock(Blocks.conveyor,Team.crux,rotation);
        }
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        Seq<BuildPlan> route=brain.construction.miningReturn(Vars.world.build(78,31),brain.core());
        check(route!=null && route.contains(p->p.block==Blocks.itemBridge),"A new mine can bridge into a core whose inlet tiles already contain working conveyors");
        check(brain.submit(route,"test bridged mining collector"),"Core collector merging uses normal paid construction and preserves existing inlet conveyors");
        brain.construction.recordFlow(route,Items.titanium);
        for(int i=0;i<9000 && brain.project.any();i++)frame(brain);
        frames(null,3600);
        check(brain.core().items.get(Items.titanium)>0 && brain.construction.feeds(Vars.world.build(78,31),brain.core()),
            "Real mined titanium crosses the obstacle and physically merges into the existing core inlet");brain.dispose();
    }
    static void preserveExistingOutput(){
        map(0);new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper,2500);Team.crux.core().items.set(Items.lead,2500);
        Vars.world.tile(77,49).setBlock(Blocks.pneumaticDrill,Team.crux);
        for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(2))if(77+e.x!=79 || 49+e.y!=49)Vars.world.tile(77+e.x,49+e.y).setBlock(Blocks.stoneWall);
        Vars.world.tile(87,55).setBlock(Blocks.duo,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);Building mine=Vars.world.build(77,49),turret=Vars.world.build(87,55);
        Seq<BuildPlan> original=brain.construction.miningReturn(mine,brain.core());
        check(original!=null && brain.submit(original,"fixture one-port paid mine"),"A one-port mine first establishes a real core income line");
        brain.construction.recordFlow(original,Items.copper);
        for(int i=0;i<9000 && brain.project.any();i++)frame(brain);
        brain.view.refresh();Seq<BuildPlan> branch=brain.construction.branchRoute(mine,turret);
        check(branch!=null && branch.contains(p->p.block==Blocks.router) && brain.submit(branch,"test paid source off-ramp"),
            "A sealed producer adds a normally replaceable router on its owned output instead of deleting the original income route");
        brain.construction.recordFlow(branch,Items.copper);
        for(int i=0;i<9000 && brain.project.any();i++)frame(brain);
        int copper=brain.core().items.get(Items.copper);frames(null,3600);brain.view.refresh();
        check(brain.core().items.get(Items.copper)>copper && ((mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)turret).totalAmmo>0 && brain.construction.feeds(mine,brain.core()) && brain.construction.feeds(mine,turret),
            "The physical router concurrently preserves core income and automatically delivers real ammunition to the new consumer");brain.dispose();
    }
    static void separateT3Inputs(){
        map(0);new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.metaglass})Team.crux.core().items.set(item,2500);
        Vars.world.tile(85,39).setBlock(Blocks.multiplicativeReconstructor,Team.crux);
        Vars.world.tile(76,61).setBlock(Blocks.siliconSmelter,Team.crux);
        Vars.world.tile(99,61).setBlock(Blocks.kiln,Team.crux);
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        //Finite setup output buffers isolate routing; no inventory changes occur during transport.
        Vars.world.build(76,61).items.set(Items.silicon,10);
        Vars.world.build(99,61).items.set(Items.metaglass,10);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        Building target=Vars.world.build(85,39);
        int[][] positions={{78,31},{76,61},{99,61}};Item[] inputs={Items.titanium,Items.silicon,Items.metaglass};
        for(int n=0;n<inputs.length;n++){
            Building source=Vars.world.build(positions[n][0],positions[n][1]);
            Seq<BuildPlan> route=brain.construction.branchRoute(source,target);
            check(route!=null && brain.submit(route,"test separate T3 "+inputs[n].name),"T3 finds a paid dedicated inlet for "+inputs[n].name+" after earlier inputs occupy the perimeter");
            brain.construction.recordFlow(route,inputs[n]);
            for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
            brain.view.refresh();
        }
        frames(null,7200);
        check(target.items.get(Items.titanium)>0 && target.items.get(Items.silicon)>0 && target.items.get(Items.metaglass)>0,
            "All three actual ingredient streams reach the same T3 building without sealing later inlets");
        check(brain.supplyLog.isEmpty(),"Dedicated ingredient landings require no worker input hauling");brain.dispose();
    }
    static void coreSpineReuse(){
        map(0);new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.metaglass})Team.crux.core().items.set(item,2500);
        for(int y=0;y<Vars.world.height();y++)Vars.world.tile(85,y).setBlock(Blocks.stoneWall);
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        for(int x=71;x<=73;x++)for(int y=26;y<=28;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreThorium);
        Vars.world.tile(72,27).setBlock(Blocks.laserDrill,Team.crux);
        Vars.world.tile(75,27).setBlock(Blocks.powerSource,Team.crux); //Isolated power fixture only.
        Vars.world.tile(74,27).setBlock(Blocks.powerNode,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        Seq<BuildPlan> trunk=brain.construction.miningReturn(Vars.world.build(78,31),brain.core());
        check(trunk!=null && brain.submit(trunk,"fixture paid core bridge trunk"),"The core trunk is built and paid before the later thorium mine connects");
        brain.construction.recordFlow(trunk,Items.titanium);
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        frames(null,600);brain.view.refresh();
        IntIntMap links=new IntIntMap();for(Building b:brain.view.buildings)if(b instanceof ItemBridgeBuild)links.put(b.pos(),((ItemBridgeBuild)b).link);
        Seq<BuildPlan> branch=brain.construction.miningReturn(Vars.world.build(72,27),brain.core());
        check(branch!=null && !branch.contains(p->p.block==Blocks.itemBridge && p.config instanceof arc.math.geom.Point2) && brain.submit(branch,"test paid thorium merge"),
            "A later ore joins the verified core-only conveyor and bridge spine without rebuilding another crossing");
        brain.construction.recordFlow(branch,Items.thorium);
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        int titanium=brain.core().items.get(Items.titanium);frames(null,3600);
        boolean unchanged=true;for(IntIntMap.Entry entry:links)unchanged &= ((ItemBridgeBuild)Vars.world.build(entry.key)).link==entry.value;
        if(!unchanged || brain.core().items.get(Items.thorium)==0 || brain.core().items.get(Items.titanium)<=titanium){
            System.out.println("SPINE stock="+brain.core().items+" oldTi="+titanium+" unchanged="+unchanged+" laser="+Vars.world.build(72,27).items+" power="+Vars.world.build(72,27).power.status);
            for(BuildPlan p:branch)System.out.println(" SPINE PART "+p.x+","+p.y+" "+p.block+" r="+p.rotation+" actual="+p.tile().build+" items="+p.tile().build.items);
        }
        check(unchanged && brain.core().items.get(Items.thorium)>0 && brain.core().items.get(Items.titanium)>titanium,
            "Real titanium and thorium both reach the core while all original bridge configurations stay unchanged");
        check(brain.core().items.get(Items.coal)==0 && brain.core().items.get(Items.sand)==0 && brain.supplyLog.isEmpty(),
            "Core trunk merging introduces neither raw core deliveries nor worker input transport");brain.dispose();
    }
    static void directionalIsolation(){
        map(0);new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.metaglass})Team.crux.core().items.set(item,2500);
        Vars.world.tile(101,44).setBlock(Blocks.multiplicativeReconstructor,Team.crux);
        Vars.world.tile(87,40).setBlock(Blocks.siliconSmelter,Team.crux);
        Vars.world.build(87,40).items.set(Items.silicon,10); //Finite setup output, routing is the subject.
        Vars.world.tile(87,39).setBlock(Blocks.conveyor,Team.crux,3);
        Vars.world.tile(87,38).setBlock(Blocks.conveyor,Team.crux,3);
        for(int x=84;x<=85;x++)for(int y=37;y<=38;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreCopper);
        Vars.world.tile(84,37).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(86,37).setBlock(Blocks.conveyor,Team.crux,0);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        brain.construction.recordFlow(Seq.with(new BuildPlan(87,39,3,Blocks.conveyor),new BuildPlan(87,38,3,Blocks.conveyor)),Items.silicon);
        brain.construction.recordFlow(Seq.with(new BuildPlan(86,37,0,Blocks.conveyor)),Items.copper);
        Seq<BuildPlan> route=brain.construction.branchRoute(Vars.world.build(87,38),Vars.world.build(101,44));
        check(route!=null && route.contains(p->p.x==87 && p.y==37 && p.block==Blocks.itemBridge),
            "A forced output beside a foreign incoming belt selects a directionally isolated paid bridge sender");
        check(brain.submit(route,"test native directional item isolation"),"The isolated sender and complete downstream route use normal paid construction");
        brain.construction.recordFlow(route,Items.silicon);
        for(int i=0;i<18000 && brain.project.any();i++)frame(brain);
        frames(null,7200);
        check(Vars.world.build(101,44).items.get(Items.silicon)>0 && Vars.world.build(101,44).items.get(Items.copper)==0 &&
            Vars.world.tile(86,37).block()==Blocks.conveyor && Vars.world.build(86,37).items.get(Items.copper)>0,
            "Actual silicon arrives while the untouched foreign copper belt is backpressured and cannot contaminate the T3 input");brain.dispose();
    }
    static void rejectReceiverSideInput(){
        map(0);new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper,2500);Team.crux.core().items.set(Items.lead,2500);
        Vars.world.tile(88,44).setBlock(Blocks.itemBridge,Team.crux);
        Vars.world.tile(84,44).setBlock(Blocks.itemBridge,Team.crux);
        Vars.world.build(84,44).configure(new arc.math.geom.Point2(4,0));
        for(int x=87;x<=88;x++)for(int y=41;y<=42;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreCopper);
        Vars.world.tile(87,41).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(88,43).setBlock(Blocks.conveyor,Team.crux,1);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,600);
        int copper=brain.core().items.get(Items.copper);
        check(copper==2500 && !brain.construction.feeds(Vars.world.build(87,41),brain.core()),
            "An ordinary belt facing an unlinked bridge receiver neither delivers ore nor counts as a working core income path");
        brain.construction.recordFlow(Seq.with(new BuildPlan(88,43,1,Blocks.conveyor),new BuildPlan(84,44,0,Blocks.itemBridge),new BuildPlan(88,44,0,Blocks.itemBridge)),Items.copper);
        Seq<BuildPlan> route=brain.construction.miningReturn(Vars.world.build(87,41),brain.core());
        check(route!=null && brain.submit(route,"test repair false receiver-side input"),"Repair routing merges through an actual accepting core input rather than treating the receiver as a side-input port");
        brain.construction.recordFlow(route,Items.copper);
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        int after=brain.core().items.get(Items.copper);frames(null,3600);
        check(brain.core().items.get(Items.copper)>after && brain.construction.feeds(Vars.world.build(87,41),brain.core()),
            "The repaired normally paid route restores real copper income in the original engine");brain.dispose();
    }
    static void bridgeFallbackKeepsCollector(){
        map(0);new FairAiMod().applyFairRules();UtilitiesTest.stock();
        for(int y=0;y<Vars.world.height();y++)Vars.world.tile(82,y).setBlock(Blocks.stoneWall);
        for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(Team.crux.core().block.size))
            Vars.world.tile(90+e.x,44+e.y).setBlock(Blocks.stoneWall);
        Vars.world.tile(88,44).setBlock(Blocks.itemBridge,Team.crux);
        Vars.world.tile(84,44).setBlock(Blocks.itemBridge,Team.crux);
        Vars.world.build(84,44).configure(new arc.math.geom.Point2(4,0));
        Vars.world.tile(83,44).setBlock(Blocks.conveyor,Team.crux,0);
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        Seq<BuildPlan> route=brain.construction.miningReturn(Vars.world.build(78,31),brain.core());
        check(route!=null && route.contains(p->p.block==Blocks.itemBridge && p.config instanceof arc.math.geom.Point2),
            "A bridge fallback crosses an obstacle and merges into an existing core trunk when direct core ports are occupied");
        check(!route.contains(p->p.x==84 && p.y==44 && p.block!=Blocks.itemBridge) && brain.submit(route,"test paid fallback core merge"),
            "Fallback routing retains the existing bridge sender instead of planning an invalid conveyor replacement");
        brain.construction.recordFlow(route,Items.titanium);
        for(int i=0;i<16000 && brain.project.any();i++)frame(brain);
        int titanium=brain.core().items.get(Items.titanium);frames(null,4800);
        check(brain.project.isEmpty() && ((ItemBridgeBuild)Vars.world.build(84,44)).link==arc.math.geom.Point2.pack(88,44) && brain.core().items.get(Items.titanium)>titanium,
            "The paid fallback completes and actually delivers mined titanium without changing the original trunk link");
        brain.dispose();
    }
}
