package fairai;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import static fairai.SimulationTest.*;

final class PowerRoutesTest{
    static void run(){
        map(0);new FairAiMod().applyFairRules();
        Vars.world.tile(100,24).setBlock(Blocks.siliconSmelter,Team.crux);
        Vars.world.tile(101,27).setBlock(Blocks.solarPanel,Team.crux);
        Vars.world.tile(100,26).setBlock(Blocks.powerNode,Team.crux);
        Vars.world.tile(74,24).setBlock(Blocks.laserDrill,Team.crux);
        //An insulated, occupied strip defeats midpoint-only placement and straight power links.
        for(int x=84;x<=90;x++)for(int y=19;y<=29;y++)Vars.world.tile(x,y).setBlock(Blocks.plastaniumWall,Team.crux);
        frames(null,120);TeamBrain brain=new TeamBrain(Team.crux);
        Building industry=Vars.world.build(100,24),mine=Vars.world.build(74,24);
        Seq<BuildPlan> route=new PowerRoutes(brain).connect(mine,industry);
        check(route!=null && route.size>=3 && route.allMatch(p->p.block==Blocks.powerNode),
            "Power planning finds a complete native node path around an insulated occupied industrial corridor");
        check(!brain.submit(route,"unfunded power path") && Vars.world.build(74,24).power.graph!=industry.power.graph,
            "Planning a power path neither joins graphs nor bypasses construction material costs");
        UtilitiesTest.stock();int copper=brain.core().items.get(Items.copper),lead=brain.core().items.get(Items.lead);
        check(brain.submit(route,"paid power path"),"The complete power path is submitted through the normal builder");
        for(int i=0;i<12000 && brain.project.any();i++)frame(brain);
        frames(null,120);
        check(route.allMatch(Construction::complete) && mine.power.graph==industry.power.graph && brain.core().items.get(Items.copper)<copper && brain.core().items.get(Items.lead)<lead,
            "Native construction and normal link configuration connect the remote drill and deduct real node materials");
        brain.dispose();
    }
}
