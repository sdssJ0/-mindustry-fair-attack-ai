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
import mindustry.world.blocks.units.Reconstructor;
import static fairai.SimulationTest.*;

/** Isolate real three-tier payload handoff and aggregate material conservation from economic strategy. */
public final class TertiaryTest{
    static boolean capture;static int daggers,maces,fortresses;
    public static void main(String[] args){try{setup();run();System.out.println("PASS tertiary: "+passed);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
    static void run(){
        Events.on(EventType.UnitCreateEvent.class,e->{
            if(!capture || e.unit.team!=Team.crux)return;
            if(e.unit.type==UnitTypes.dagger)daggers++;if(e.unit.type==UnitTypes.mace)maces++;if(e.unit.type==UnitTypes.fortress)fortresses++;
        });
        blockedSiteKeepsBudget();
        map(0);new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.titanium,Items.thorium,Items.metaglass})Team.crux.core().items.set(item,2500);
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        Vars.world.tile(95,49).setBlock(Blocks.powerSource,Team.crux); //Fixture only, never part of an AI project.
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);brain.development.armed=true;
        Vars.world.tile(96,20).setOverlay(Blocks.oreThorium);brain.view.refresh();
        check(!brain.actor.unit().canMine(Items.thorium) && brain.view.deposit(Items.thorium,brain.core())!=null,
            "Drill prospecting finds explored thorium even when the core ship cannot hand-mine it");
        check(RecipeCatalog.supported((Reconstructor)Blocks.multiplicativeReconstructor) && RecipeCatalog.upgrade((Reconstructor)Blocks.multiplicativeReconstructor,UnitTypes.mace)==UnitTypes.fortress,
            "The supported T3 adapter reads the actual mace-to-fortress upgrade");
        ObjectFloatMap<Item> demand=RecipeCatalog.t3Demand();
        check(demand.get(Items.silicon,0)==180 && demand.get(Items.graphite,0)==40 && demand.get(Items.titanium,0)==80 && demand.get(Items.metaglass,0)==40,
            "T3 planning includes real T1, T2 and T3 input batches rather than only the final recipe");
        check(!RecipeCatalog.supported((Reconstructor)Blocks.exponentialReconstructor),"Unimplemented cryofluid-dependent T4 remains protected");
        check(brain.development.plan(),"A real normally paid T2 project precedes T3");
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        Seq<BuildPlan> plans=brain.development.layoutT3(Vars.state.rules.tags.getInt("fair-ai.development."+Team.crux.id+".direction",0));
        int thorium=brain.core().items.get(Items.thorium);
        check(brain.submit(plans,"test T3 and real three-input delivery"),"The 5x5 reconstructor and input connections use ordinary build plans");
        for(int i=0;i<18000 && brain.project.any();i++)frame(brain);
        check(brain.development.t3Complete() && thorium-brain.core().items.get(Items.thorium)==650,
            "The normal core builder pays the real 650-thorium T3 construction cost");
        brain.view.refresh();
        boolean links=true;
        for(Building b:brain.view.buildings)if(b instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild){
            mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild bridge=(mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)b;
            if(bridge.link!=-1)links &= ((mindustry.world.blocks.distribution.ItemBridge)b.block).linkValid(b.tile,Vars.world.tile(bridge.link));
        }
        check(links,"Every paid bridge in the complete T3 transport template obeys the original range and team link rules");
        boolean connected=true;
        for(Item item:brain.inputs(Blocks.multiplicativeReconstructor,brain.development.t3Reconstructor()))
            connected &= brain.construction.feeds(brain.development.inputSource(item),brain.development.t3Reconstructor());
        check(connected,
            "All three normally configured core unloaders physically feed their separate T3 inlets");
        brain.development.control();capture=true;daggers=maces=fortresses=0;
        int si=DevelopmentTest.total(Items.silicon),gr=DevelopmentTest.total(Items.graphite),ti=DevelopmentTest.total(Items.titanium),mg=DevelopmentTest.total(Items.metaglass),lead=DevelopmentTest.total(Items.lead);
        for(int i=0;i<7200;i++){frame(null);brain.development.update(brain.time()+i+1);}
        capture=false;
        System.out.println("T3 diagnostic dagger="+daggers+" mace="+maces+" fortress="+fortresses+" recon="+brain.development.t3Reconstructor().items);
        check(fortresses>=2 && Groups.unit.contains(u->u.team==Team.crux && u.type==UnitTypes.fortress),
            "Real factories hand off dagger and mace payloads and release multiple live fortress units");
        check(brain.development.upgraded()==maces && brain.development.t3Upgraded()==fortresses,
            "Development counters match actual upgrade events even when payloads transfer into the next factory in the same tick");
        check(si-DevelopmentTest.total(Items.silicon)==10*daggers+40*maces+130*fortresses && gr-DevelopmentTest.total(Items.graphite)==40*maces &&
            ti-DevelopmentTest.total(Items.titanium)==80*fortresses && mg-DevelopmentTest.total(Items.metaglass)==40*fortresses && lead-DevelopmentTest.total(Items.lead)==10*daggers,
            "All three tiers balance exact silicon, graphite, titanium, metaglass and lead consumption across every inventory");
        check(brain.supplyLog.isEmpty() && brain.emergencyMiningTicks==0,"Three-tier payload production runs without the AI worker transporting inputs");
        Vars.state.rules.tags.put("fair-ai.development."+Team.crux.id+".armed","true");
        Vars.state.rules.tags.put("fair-ai.development."+Team.crux.id+".t3-armed","true");
        Fi save=new Fi("test-output/tertiary.msav");SaveIO.write(save);brain.dispose();SaveIO.load(save);brain=new TeamBrain(Team.crux);
        check(brain.development.t3Complete() && brain.development.t3Upgraded()>=2,"The real save preserves T3 identity, completed upgrades and normal payload state");
        Vars.world.tile(104,41).setBlock(Blocks.kiln,Team.crux);
        Vars.world.tile(104,46).setBlock(Blocks.siliconSmelter,Team.crux);
        //Independent control fixture: full warehouse, but the downstream input buffers need replenishment.
        brain.development.t3Reconstructor().items.set(Items.metaglass,0);
        brain.development.t3Reconstructor().items.set(Items.silicon,0);
        brain.view.refresh();brain.economy.refresh(0);brain.economy.control();
        check(brain.core().items.get(Items.metaglass)>brain.economy.stockGoal(Items.metaglass) && Vars.world.build(104,41).enabled,
            "A full core does not disable metaglass production that still directly supplies the T3 reconstructor");
        check(brain.core().items.get(Items.silicon)>brain.economy.stockGoal(Items.silicon) && Vars.world.build(104,46).enabled,
            "A full core does not disable silicon production while a connected T3 input buffer needs material");
        brain.dispose();
    }

    static void blockedSiteKeepsBudget(){
        map(0);new FairAiMod().applyFairRules();
        for(Item item:new Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.titanium,Items.metaglass})Team.crux.core().items.set(item,2500);
        Vars.state.rules.tags.put("fair-ai.development."+Team.crux.id+".armed","true");
        Vars.state.rules.tags.put("fair-ai.development."+Team.crux.id+".t3-armed","true");
        Vars.world.tile(78,31).setBlock(Blocks.pneumaticDrill,Team.crux);
        TeamBrain brain=new TeamBrain(Team.crux);frames(null,120);
        check(brain.development.plan(),"Saved T3 intent still constructs the required normal T2 campus first");
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        BuildPlan site=brain.development.layoutT3(Vars.state.rules.tags.getInt("fair-ai.development."+Team.crux.id+".direction",0)).find(p->p.block==Blocks.multiplicativeReconstructor);
        Unit blocker=UnitTypes.dagger.create(Team.crux);blocker.set(site.x*8f,site.y*8f);blocker.add();frames(null,2);
        check(!mindustry.world.Build.validPlace(site.block,Team.crux,site.x,site.y,0),"A friendly ground unit physically blocks the future reconstructor under native placement rules");
        brain.development.plan();
        check(brain.development.reserve(Items.thorium)>=650 && brain.development.stockGoal(Items.thorium)>=650,
            "Temporary friendly-unit occupancy preserves T3 construction and mining reserves instead of cancelling thorium production");
        blocker.remove();brain.dispose();
    }
}
