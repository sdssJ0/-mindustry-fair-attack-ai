package fairai;

import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.world.blocks.production.Drill;
import static fairai.SimulationTest.*;

/** Waiting for an unaffordable defense must not consume the independent mining work slot. */
public final class SchedulerTest{
    public static void main(String[] args){try{setup();run();System.out.println("PASS scheduler: "+passed);System.exit(0);}catch(Throwable e){e.printStackTrace();System.exit(1);}}
    static void run(){
        TeamBrain brain=RawLogisticsTest.fixture();
        for(int x=87;x<=88;x++)for(int y=44;y<=45;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreCopper);
        Vars.world.tile(87,44).setBlock(Blocks.mechanicalDrill,Team.crux);
        Vars.world.tile(87,43).setBlock(Blocks.duo,Team.crux);
        for(int x=89;x<=90;x++)for(int y=47;y<=48;y++)Vars.world.tile(x,y).setOverlay(Blocks.oreLead);
        Vars.world.tile(89,47).setBlock(Blocks.mechanicalDrill,Team.crux);
        Vars.world.tile(89,46).setBlock(Blocks.conveyor,Team.crux,3);
        Vars.world.tile(96,58).setBlock(Blocks.mechanicalDrill,Team.crux);
        Vars.world.tile(100,36).setBlock(Blocks.mechanicalDrill,Team.crux);
        frames(null,120);brain.view.refresh();
        brain.core().items.set(Items.copper,116);brain.core().items.set(Items.lead,31);
        check(brain.defense.ready() && brain.hasCoreIncome(Items.copper) && brain.hasCoreIncome(Items.lead),
            "The early-game fixture has paid-resource mining and an automated duo before it waits for scatter lead");
        brain.update();
        System.out.println("Scheduler diagnostic: "+brain.status+" stock="+brain.core().items+" plans="+brain.project);
        check(brain.project.any() && brain.project.contains(p->p.block instanceof Drill),
            "Waiting for unaffordable scatter lead allows another affordable mining project to start immediately");
        BuildPlan mine=brain.project.find(p->p.block instanceof Drill);
        frames(brain,60);boolean paid=brain.core().items.get(Items.copper)<116;
        for(int i=0;i<6000 && brain.project.any();i++)frame(brain);
        check(Construction.complete(mine) && paid && brain.emergencyMiningTicks==0,
            "The independent mine completes through normal paid construction without routine hand mining");
        brain.dispose();
    }
}
