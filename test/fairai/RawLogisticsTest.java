package fairai;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.production.Drill;

import static fairai.SimulationTest.*;

/** Real construction, conveyors, processing and carried-inventory recovery. */
final class RawLogisticsTest{
    static void run(){
        directProcessingRoutes();
        recoverCarriedInputs();
        migrateOldCoreRoute();
    }

    static TeamBrain fixture(){
        map(0);
        new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper, 600);
        Team.crux.core().items.set(Items.lead, 300);
        TeamBrain brain = new TeamBrain(Team.crux);
        frames(null, 120);
        return brain;
    }

    static void finish(TeamBrain brain){
        int before = brain.finishedProjects;
        for(int i = 0; i < 6000 && brain.finishedProjects == before; i++) frame(brain);
        check(brain.finishedProjects > before, "Raw logistics project completes through the normal builder");
    }

    static void directProcessingRoutes(){
        TeamBrain brain = fixture();
        check(brain.construction.drillRoute(new BuildPlan(96, 59, 0, Blocks.mechanicalDrill), brain.core()) == null &&
            brain.construction.drillRoute(new BuildPlan(100, 36, 0, Blocks.mechanicalDrill), brain.core()) == null,
            "Both coal and sand are rejected by the generic drill-to-core router");
        check(brain.buildDrill(Items.coal, null) && brain.project.size == 1 && brain.project.first().block instanceof Drill,
            "A coal mine starts locally without a conveyor route to the core");
        finish(brain);
        check(brain.buildDrill(Items.sand, null) && brain.project.size == 1,
            "A sand mine also starts locally without core-bound logistics");
        finish(brain);
        Vars.world.tile(94, 44).setBlock(Blocks.graphitePress, Team.crux);
        Building press = Vars.world.build(94, 44);
        //Force the sink onto the upper left edge of an even-sized factory footprint.
        for(arc.math.geom.Point2 edge : mindustry.world.Edges.getEdges(press.block.size)){
            int x = press.tileX() + edge.x, y = press.tileY() + edge.y;
            if(x != 93 || y != 45) Vars.world.tile(x, y).setBlock(Blocks.stoneWall);
        }
        brain.view.refresh();
        Building coal = brain.view.buildings.find(b -> b instanceof Drill.DrillBuild && ((Drill.DrillBuild)b).dominantItem == Items.coal);
        Seq<BuildPlan> plans = brain.construction.supplyRoute(coal, press);
        check(plans != null && plans.contains(p -> p.x == 93 && p.y == 45 && p.rotation == 0),
            "Factory routing points into the footprint rather than diagonally toward its anchor");
        check(brain.submit(plans, "test coal to press"), "A direct coal-to-factory conveyor route uses paid build plans");
        finish(brain);
        frames(null, 3600);
        check(brain.construction.feeds(coal, press) && press.items.get(Items.graphite) > 0,
            "Conveyors actually deliver mined coal and the factory turns it into graphite");
        Vars.world.tile(98, 42).setBlock(Blocks.siliconSmelter, Team.crux);
        Building smelter = Vars.world.build(98, 42);
        brain.view.refresh();
        Building sand = brain.view.buildings.find(b -> b instanceof Drill.DrillBuild && ((Drill.DrillBuild)b).dominantItem == Items.sand);
        plans = brain.construction.supplyRoute(sand, smelter);
        check(plans != null && brain.submit(plans, "test sand to smelter"), "Sand routing targets an actual recipe consumer");
        finish(brain);
        frames(null, 3600);
        check(smelter.items.get(Items.sand) > 0, "Sand reaches the smelter through real conveyors without core storage");
        check(brain.core().items.get(Items.coal) == 0 && brain.core().items.get(Items.sand) == 0,
            "Direct processing routes never add coal or sand to the core");
        brain.dispose();
    }

    static void recoverCarriedInputs(){
        TeamBrain brain = fixture();
        brain.actor.unit().addItem(Items.sand, 10);
        brain.project.add(new BuildPlan(92, 48, 0, Blocks.copperWall));
        finish(brain);
        check(brain.actor.unit().stack.amount == 10 && brain.core().items.get(Items.sand) == 0,
            "Carried sand with no consumer is retained while construction can still proceed");
        Vars.world.tile(94, 44).setBlock(Blocks.siliconSmelter, Team.crux);
        Building target = Vars.world.build(94, 44);
        brain.view.refresh();
        brain.project.add(new BuildPlan(93, 48, 0, Blocks.copperWall));
        for(int i = 0; i < 900 && target.items.get(Items.sand) == 0; i++) frame(brain);
        check(target.items.get(Items.sand) == 10 && brain.actor.unit().stack.amount == 0 && brain.core().items.get(Items.sand) == 0,
            "Recovered raw cargo goes to a real factory rather than the default core fallback");
        brain.actor.unit().addItem(Items.coal, 10);
        target.kill();
        Vars.state.rules.onlyDepositCore = true;
        brain.project.clear();
        brain.project.add(new BuildPlan(93, 49, 0, Blocks.copperWall));
        finish(brain);
        check(brain.actor.unit().stack.amount == 10 && brain.core().items.get(Items.coal) == 0,
            "A lost destination and only-deposit-core rule cannot redirect raw cargo into the core");
        brain.dispose();
    }

    static void migrateOldCoreRoute(){
        TeamBrain brain = fixture();
        //A straight v0.2.0-style coal line to the eastern edge of the core.
        for(int x = 94; x < 99; x++) for(int y = 43; y < 45; y++) Vars.world.tile(x, y).setOverlay(Blocks.oreCoal);
        Vars.world.tile(97, 43).setBlock(Blocks.mechanicalDrill, Team.crux);
        for(int x = 92; x <= 96; x++) Vars.world.tile(x, 44).setBlock(Blocks.conveyor, Team.crux, 2);
        brain.view.refresh();
        check(brain.raw.disconnectCoreInputs() && brain.project.first().breaking,
            "An old coal-to-core belt is disconnected with normal deconstruction plans");
        finish(brain);
        int after = brain.core().items.get(Items.coal);
        frames(null, 1800);
        check(Vars.world.tile(92, 44).block() == Blocks.air && brain.core().items.get(Items.coal) == after,
            "Deconstructing the old core entrance stops subsequent raw material intake");
        Tile near = Vars.world.tile(92, 44);
        near.setOverlay(Blocks.oreCoal);
        check(!brain.construction.place(Blocks.mechanicalDrill, 92, 44, 0),
            "A new raw drill cannot dump directly into an adjacent core");
        brain.dispose();
    }
}
