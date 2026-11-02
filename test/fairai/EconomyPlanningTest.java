package fairai;

import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.logic.LAccess;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import arc.struct.Seq;

import static fairai.SimulationTest.*;

final class EconomyPlanningTest{
    static void run(){
        ratesAndReserves();
        paidDrillUpgrade();
        pauseKeepsFactoryProgress();
        isolatedStreams();
        factoriesCannotPolluteExistingLines();
        streamPolicySurvivesSaving();
        unsupportedIndustryKeepsItsControls();
    }

    static void ratesAndReserves(){
        TeamBrain brain = RawLogisticsTest.fixture();
        Vars.world.tile(100, 36).setBlock(Blocks.pneumaticDrill, Team.crux);
        Vars.world.tile(94, 44).setBlock(Blocks.siliconSmelter, Team.crux);
        Vars.world.tile(85, 44).setBlock(Blocks.airFactory, Team.crux);
        Vars.world.build(85, 44).configure(0);
        Vars.world.tile(96, 59).setBlock(Blocks.combustionGenerator, Team.crux);
        brain.core().items.set(Items.graphite, 80);
        brain.core().items.set(Items.silicon, 100);
        frames(null, 2); brain.view.refresh(); brain.economy.refresh(0);
        DrillBuild drill = (DrillBuild)Vars.world.build(100, 36);
        float expected = 3600f * drill.dominantItems / ((Drill)drill.block).getDrillTime(Items.sand);
        check(Math.abs(brain.economy.supply.get(Items.sand, 0) - expected) < 0.01f,
            "Mining capacity uses actual ore coverage and the engine's item-specific drilling time");
        check(Math.abs(brain.economy.demand.get(Items.sand, 0) - 160f) < 0.01f &&
            Math.abs(brain.economy.demand.get(Items.coal, 0) - 110f) < 0.01f,
            "Planning converts silicon recipes and coal generator fuel into consistent per-minute demands");
        check(brain.economy.powerSupply == 0 && brain.economy.powerDemand > 100,
            "An isolated generator cannot satisfy the industrial grid's planned electricity demand");
        brain.economy.control();
        check(!Vars.world.build(85, 44).enabled && !brain.deliver(Vars.world.build(85, 44), Items.silicon, 15),
            "Economy-first blocks both manufacturing and input deliveries until development conditions are ready");
        check(brain.economy.reserve(Items.silicon) >= 100 && brain.core().items.get(Items.silicon) == 100,
            "Future construction reserves are accounting decisions and do not deduct inventory");
        brain.dispose();
    }

    static void paidDrillUpgrade(){
        TeamBrain brain = RawLogisticsTest.fixture();
        brain.core().items.set(Items.copper, 300);
        brain.core().items.set(Items.graphite, 50);
        Vars.world.tile(78, 50).setBlock(Blocks.mechanicalDrill, Team.crux);
        brain.view.refresh();
        check(brain.economy.upgrade(Items.copper), "A resource bottleneck can schedule a legal mechanical-to-pneumatic drill upgrade");
        frame(brain);
        check(Vars.world.build(78, 50) instanceof ConstructBuild,
            "Drill upgrading creates a normal construction site instead of replacing the tile instantly");
        for(int i=0;i<1200 && brain.project.any();i++)frame(brain);
        check(Vars.world.tile(78,50).block()==Blocks.pneumaticDrill, "The normal core ship physically completes the mining upgrade");
        int copper=brain.core().items.get(Items.copper), graphite=brain.core().items.get(Items.graphite);
        brain.dispose();
        map(0);new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper,300);Team.crux.core().items.set(Items.graphite,50);
        Vars.world.tile(78,50).setBlock(Blocks.mechanicalDrill,Team.crux);
        Player player=player(Team.crux);
        player.unit().addBuild(new BuildPlan(78,50,0,Blocks.pneumaticDrill),false);
        frames(null,1200);
        check(copper==Team.crux.core().items.get(Items.copper)&&graphite==Team.crux.core().items.get(Items.graphite),
            "Drill upgrade costs match the identical normal human upgrade queue");
        player.remove();
    }

    static void pauseKeepsFactoryProgress(){
        TeamBrain brain = RawLogisticsTest.fixture();
        Vars.world.tile(94,44).setBlock(Blocks.airFactory,Team.crux);
        Vars.world.tile(92,46).setBlock(Blocks.powerNode,Team.crux);
        Vars.world.tile(91,46).setBlock(Blocks.combustionGenerator,Team.crux);
        Vars.world.tile(93,46).setBlock(Blocks.combustionGenerator,Team.crux);
        Vars.world.build(91,46).items.set(Items.coal,10);
        Vars.world.build(93,46).items.set(Items.coal,10);
        UnitFactoryBuild factory=(UnitFactoryBuild)Vars.world.build(94,44);
        factory.configure(0); factory.items.set(Items.silicon,30); factory.progress=200;
        unitsProduced=0;
        factory.control(LAccess.enabled,0,0,0,0);
        frames(null,300);
        check(factory.progress==200&&factory.items.get(Items.silicon)==30&&unitsProduced==0,
            "Normal enable control pauses production without clearing progress or consuming reserved materials");
        factory.control(LAccess.enabled,1,0,0,0);
        frames(null,1000);
        check(unitsProduced>=1&&factory.items.get(Items.silicon)<30,
            "Resuming a paused factory completes and pays for an actual unit");
        brain.dispose();
    }

    static void isolatedStreams(){
        TeamBrain brain=RawLogisticsTest.fixture();
        Vars.world.tile(96,59).setBlock(Blocks.mechanicalDrill,Team.crux);
        Vars.world.tile(94,44).setBlock(Blocks.graphitePress,Team.crux);
        for(int x=96;x<=97;x++)for(int y=50;y<=51;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreCopper);
        Vars.world.tile(96,50).setBlock(Blocks.mechanicalDrill,Team.crux);
        brain.view.refresh();
        Seq<BuildPlan> route=brain.construction.supplyRoute(Vars.world.build(96,59),Vars.world.build(94,44));
        check(route!=null&&brain.submit(route,"isolated coal line"),"A coal route can avoid side injection from a nearby copper drill");
        brain.construction.recordFlow(route,Items.coal);
        RawLogisticsTest.finish(brain);
        frames(null,2400);
        boolean clean=true;
        for(BuildPlan plan:route)if(plan.tile().build!=null&&plan.tile().build.items!=null&&plan.tile().build.items.get(Items.copper)>0)clean=false;
        check(clean&&Vars.world.build(94,44).items.get(Items.graphite)>0,
            "The physical coal line stays free of copper and sustains real graphite production");
        brain.dispose();
    }

    static void factoriesCannotPolluteExistingLines(){
        TeamBrain brain=RawLogisticsTest.fixture();
        Vars.world.tile(94,44).setBlock(Blocks.conveyor,Team.crux,3);
        brain.construction.recordFlow(Seq.with(new BuildPlan(94,44,3,Blocks.conveyor)),Items.coal);
        brain.view.refresh();
        check(Build.validPlace(Blocks.siliconSmelter,Team.crux,95,44,0) &&
            !brain.construction.place(Blocks.siliconSmelter,95,44,0),
            "New factory placement cannot side-dump silicon into an existing coal stream");
        brain.dispose();
    }

    static void streamPolicySurvivesSaving(){
        TeamBrain brain=RawLogisticsTest.fixture();
        Vars.world.tile(94,44).setBlock(Blocks.conveyor,Team.crux,3);
        brain.construction.recordFlow(Seq.with(new BuildPlan(94,44,3,Blocks.conveyor)),Items.coal);
        frame(brain);
        arc.files.Fi save=new arc.files.Fi("test-output/economy-flow.msav");
        mindustry.io.SaveIO.write(save);brain.dispose();mindustry.io.SaveIO.load(save);
        TeamBrain restored=new TeamBrain(Team.crux);
        check(!restored.construction.place(Blocks.siliconSmelter,95,44,0),
            "Commodity isolation survives a real save/load even when the protected belt is empty");
        restored.dispose();
    }

    static void unsupportedIndustryKeepsItsControls(){
        TeamBrain brain=RawLogisticsTest.fixture();
        Vars.world.tile(94,44).setBlock(Blocks.multiPress,Team.crux);
        Building advanced=Vars.world.build(94,44);
        brain.core().items.set(Items.graphite,300);
        brain.view.refresh();brain.economy.refresh(0);brain.economy.control();
        check(advanced.enabled&&!brain.economy.manages(advanced),
            "A prebuilt liquid-dependent advanced factory keeps its existing enabled state");
        advanced.control(LAccess.enabled,0,0,0,0);
        brain.economy.control();
        check(!advanced.enabled,"The economy planner also preserves an existing advanced factory's disabled state");
        brain.dispose();
    }
}
