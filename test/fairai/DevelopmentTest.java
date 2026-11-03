package fairai;

import arc.*;
import arc.files.Fi;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.units.Reconstructor.ReconstructorBuild;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;

import static fairai.SimulationTest.*;

/** Stock and terrain grants are fixture setup only. All building, unloaders, payloads and refunds use the engine. */
final class DevelopmentTest{
    static boolean capture;
    static int daggers, maces;
    public static void main(String[] args){
        try{ setup(); run(); System.out.println("PASS development: "+passed); System.exit(0); }
        catch(Throwable failure){ failure.printStackTrace(); System.exit(1); }
    }
    static void run(){
        Events.on(EventType.UnitCreateEvent.class,e -> {
            if(!capture || e.unit.team!=Team.crux) return;
            if(e.unit.type==UnitTypes.dagger) daggers++;
            if(e.unit.type==UnitTypes.mace) maces++;
        });
        recipesAndReservation();
        automatedT2AndRetirement();
        replacementBeforeRemoval();
        unsupportedAndUniqueAreProtected();
        compactSaveRecords();
    }
    static TeamBrain fixture(){
        map(0); new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.titanium}) Team.crux.core().items.set(item,2500);
        TeamBrain brain=new TeamBrain(Team.crux); frames(null,120);
        return brain;
    }
    static void recipesAndReservation(){
        TeamBrain brain=fixture();
        check(RecipeCatalog.upgrade((mindustry.world.blocks.units.Reconstructor)Blocks.additiveReconstructor,UnitTypes.dagger)==UnitTypes.mace,
            "Upgrade planning uses the actual v146 dagger-to-mace relationship");
        check(RecipeCatalog.t2Demand().get(Items.silicon,0)==50 && RecipeCatalog.t2Demand().get(Items.graphite,0)==40 &&
            RecipeCatalog.t2Demand().get(Items.lead,0)==10,"T2 budget includes both the real T1 recipe and the reconstruction recipe");
        check(!RecipeCatalog.supported((mindustry.world.blocks.units.Reconstructor)Blocks.exponentialReconstructor),
            "Unsupported liquid-dependent tiers are not claimed as executable projects");
        Seq<BuildPlan> layout=brain.development.layout(Vars.state.rules.tags.getInt("fair-ai.development.1.direction",0));
        BuildPlan factory=layout.find(p -> p.block==Blocks.groundFactory);
        check(!brain.construction.place(Blocks.groundFactory,factory.x,factory.y,factory.rotation) &&
            brain.construction.placeDevelopment(Blocks.groundFactory,factory.x,factory.y,factory.rotation),
            "Ordinary expansion cannot occupy the reserved future military campus");
        int before=brain.core().items.get(Items.silicon);
        brain.development.armed=true;
        check(brain.development.reserve(Items.silicon)>0 && brain.core().items.get(Items.silicon)==before,
            "Project reservations use a ledger and never deduct real inventory");
        brain.dispose();
    }
    static int total(Item item){
        ObjectSet<mindustry.world.modules.ItemModule> seen=new ObjectSet<>(); int result=0;
        for(Building b:Team.crux.data().buildings){
            if(b.items!=null && seen.add(b.items)) result+=b.items.get(item);
            if(b instanceof mindustry.world.blocks.distribution.BufferedItemBridge.BufferedItemBridgeBuild)try{
                java.lang.reflect.Field field=b.getClass().getDeclaredField("buffer");field.setAccessible(true);
                mindustry.world.ItemBuffer buffer=(mindustry.world.ItemBuffer)field.get(b);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                buffer.write(new arc.util.io.Writes(new java.io.DataOutputStream(bytes)));
                java.io.DataInputStream data=new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray()));
                int count=data.readUnsignedByte();data.readUnsignedByte();
                for(int n=0;n<count;n++)if(mindustry.gen.TimeItem.item(data.readLong())==item.id)result++;
            }catch(Exception failure){throw new RuntimeException("Cannot audit vanilla in-flight bridge inventory",failure);}
        }
        return result;
    }
    static void automatedT2AndRetirement(){
        TeamBrain brain=fixture();
        //A rich-start contract test isolates production and exact accounting from the normal-start benchmark.
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(95,49).setBlock(Blocks.powerSource,Team.crux);
        brain.view.refresh(); brain.development.armed=true;
        Vars.state.rules.tags.put("fair-ai.development.1.armed","true");
        check(brain.development.plan() && brain.project.any(),"A T2 goal creates a complete affordable normal-build project");
        check(!brain.project.contains(p -> p.block==Blocks.itemSource || p.block==Blocks.powerSource),
            "The paid military project never includes testing resource or power sources");
        int silicon=brain.core().items.get(Items.silicon);
        frame(brain);
        check(brain.project.first().tile().build instanceof ConstructBuild,"T2 infrastructure begins as an actual construction site");
        for(int i=0;i<18000 && brain.project.any();i++) frame(brain);
        check(brain.development.complete() && brain.core().items.get(Items.silicon)<silicon,
            "The normal core builder completes and pays for unloaders, transport, factory and reconstructor");
        brain.development.control();
        daggers=maces=0; capture=true;
        int si=total(Items.silicon), graph=total(Items.graphite), lead=total(Items.lead);
        for(int tick=0;tick<3600;tick++){
            frame(null);
            brain.development.update(brain.time()+tick+1);
        }
        capture=false;
        System.out.println("T2 diagnostic: dagger="+daggers+" mace="+maces+" recon="+brain.development.reconstructor().items+
            " power="+brain.development.reconstructor().power.status+" factory="+brain.development.factory().items);
        if(maces==0) for(Building b:brain.view.buildings) if(brain.development.owns(b)) System.out.println("T2 component "+b.block.name+
            " "+b.tileX()+","+b.tileY()+" items="+b.items+" config="+b.config()+" enabled="+b.enabled);
        check(maces>=2 && Groups.unit.contains(u -> u.team==Team.crux && u.type==UnitTypes.mace),
            "Core unloaders, mixed ammunition-free supply and normal payload handoff autonomously produce and release multiple T2 units");
        check(si-total(Items.silicon)==daggers*10+maces*40 && graph-total(Items.graphite)==maces*40 && lead-total(Items.lead)==daggers*10,
            "All real T1 and T2 costs balance across core, conveyors, routers and factory inventories");
        check(brain.supplyLog.isEmpty() && brain.emergencyMiningTicks==0,"The complete T2 line needs neither worker hauling nor hand mining");
        //Preserve the completed layout and normal payload state through engine save/load.
        Fi save=new Fi("test-output/development-t2.msav"); SaveIO.write(save); brain.dispose(); SaveIO.load(save);
        brain=new TeamBrain(Team.crux); brain.development.armed=true;
        check(brain.development.complete() && brain.development.factory().currentPlan==0,
            "Actual save/load restores campus identity, factory recipe and normal reconstruction payloads");
        Vars.world.tile(98,53).setBlock(Blocks.groundFactory,Team.crux);
        UnitFactoryBuild obsolete=(UnitFactoryBuild)Vars.world.build(98,53); obsolete.configure(0); obsolete.enabled=false;
        brain.view.refresh();
        //Observe two real upgrade completions before permitting retirement.
        for(int i=0;i<9000 && brain.development.upgraded()<2;i++) frame(brain);
        check(brain.retirement.canRetire(obsolete,brain.time()),"A redundant old T1 factory is removable only after actual T2 commissioning");
        check(brain.retirement.plan(brain.time()) && brain.project.contains(p -> p.breaking && p.x==98 && p.y==53),
            "The replacement planner schedules normal player deconstruction of the obsolete factory");
        frame(brain);
        check(Vars.world.build(98,53) instanceof ConstructBuild,"Factory retirement takes construction time rather than deleting the tile");
        for(int i=0;i<6000 && brain.project.any();i++) frame(brain);
        check(Vars.world.tile(98,53).block()==Blocks.air && brain.development.complete(),
            "Paid-time deconstruction removes the old factory while the working upgraded line remains");
        frames(brain,180);
        check(Vars.world.tile(98,53).block()==Blocks.air,"An intentionally retired factory is not rebuilt by the destroyed-building repair queue");
        brain.dispose();
    }
    static void replacementBeforeRemoval(){
        TeamBrain brain=fixture();
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        Building old=Vars.world.build(78,31);
        Seq<BuildPlan> route=brain.construction.miningReturn(old,brain.core());
        check(route!=null && brain.submit(route,"fixture paid titanium connection"),"The mining replacement fixture starts with a paid core-bound line");
        for(int i=0;i<9000 && brain.project.any();i++) frame(brain);
        brain.view.refresh();
        check(brain.retirement.upgradeMine(),"A larger laser drill is planned at a new legal site rather than replacing a 2x2 tile directly");
        check(old.isValid() && !brain.project.contains(p -> p.breaking),"The old income source stays intact during construction of its replacement");
        BuildPlan laser=brain.project.find(p -> p.block==Blocks.laserDrill);
        for(int i=0;i<12000 && brain.project.any();i++) frame(brain);
        check(laser.tile().block()==Blocks.laserDrill && old.isValid(),"A completed but unverified powered drill cannot trigger premature demolition");
        brain.development.armed=true;
        brain.retirement.plan(brain.time());
        check(!brain.project.contains(p -> p.breaking),"No power and no observed replacement output preserve the old mineral income");
        //Power is a test fixture here; autonomous benchmarks must build and fuel their own generators.
        Vars.world.tile(laser.x+3,laser.y).setBlock(Blocks.powerSource,Team.crux);
        brain.view.refresh();
        for(int i=0;i<3600;i++){
            frame(null); brain.development.ledger.update(brain.view.buildings,brain.time()-3600+i);
        }
        check(brain.development.ledger.commissioned(laser.tile().build,brain.time()) && brain.retirement.plan(brain.time()) &&
            brain.project.contains(p -> p.breaking && p.x==old.tileX() && p.y==old.tileY()),
            "Only actual powered laser production and a live core return authorize retirement of the old drill");
        Building next=laser.tile().build;
        next.enabled=false;
        check(!brain.retirement.guard(brain.project.first()),"Losing the commissioned replacement cancels permission to dismantle old income");
        next.enabled=true;
        for(int i=0;i<6000 && brain.project.any();i++) frame(brain);
        check(!old.isValid() && next.isValid(),"The core builder normally dismantles the obsolete drill while preserving its working laser replacement");
        brain.dispose();
    }
    static void unsupportedAndUniqueAreProtected(){
        TeamBrain brain=fixture();
        Vars.world.tile(94,54).setBlock(Blocks.multiPress,Team.crux);
        Vars.world.tile(99,54).setBlock(Blocks.graphitePress,Team.crux);
        brain.view.refresh();
        check(!brain.retirement.canRetire(Vars.world.build(94,54),0),"Unsupported prebuilt advanced factories are protected from automatic deletion");
        check(!brain.retirement.canRetire(Vars.world.build(99,54),0),"The sole graphite production facility cannot be classified as redundant");
        brain.dispose();
    }
    static void compactSaveRecords(){
        TeamBrain brain=fixture();
        Vars.state.rules.tags.put("fair-ai.flow.1.12345",Integer.toString(Items.graphite.id));
        //A new world load migrates old-version per-tile records as well as current ownership data.
        for(int i=0;i<2000;i++) WorldMarks.put("fair-ai.owned.1."+(100000+i),Blocks.pneumaticDrill.name);
        Fi save=new Fi("test-output/development-large-records.msav");
        SaveIO.write(save); brain.dispose(); SaveIO.load(save);
        check(Blocks.pneumaticDrill.name.equals(WorldMarks.get("fair-ai.owned.1.101999")) &&
            WorldMarks.getInt("fair-ai.flow.1.12345",-1)==Items.graphite.id,
            "Thousands of ownership records and legacy commodity tags survive real save/load within vanilla metadata limits");
    }
}
