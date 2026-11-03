package fairai;

import arc.files.Fi;
import arc.struct.Seq;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.io.SaveIO;
import mindustry.gen.Building;
import mindustry.entities.units.BuildPlan;
import mindustry.world.blocks.production.GenericCrafter;

/** Diagnostic inspection in an isolated copy of a benchmark save. */
public final class DevelopmentAudit{
    public static void main(String[] args){
        try{
            SimulationTest.setup(); new FairAiMod();
            if(args[0].equals("--autonomous")){SimulationTest.autonomousEconomy(Integer.parseInt(args[1]));System.exit(0);}
            if(args[0].equals("--export-late")){
                SimulationTest.map(0); EconomyBenchmark.enrich();
                mindustry.Vars.state.rules.canGameOver=true;
                mindustry.Vars.state.rules.loadout=mindustry.type.ItemStack.list(Items.copper,250,Items.lead,150);
                mindustry.Vars.state.rules.tags.put(FairAiMod.enabledTag,"true");
                for(Team t:new Team[]{Team.crux,Team.sharded}){t.core().items.set(Items.copper,250);t.core().items.set(Items.lead,150);}
                mindustry.Vars.state.map.tags.put("name","公平 AI - 工业发展");
                mindustry.Vars.state.map.tags.put("description","镜像丰富矿区，铜铅有限开局；观察自动 T2、激光矿业和设施更新。");
                mindustry.Vars.state.map.tags.put("rules",mindustry.io.JsonIO.write(mindustry.Vars.state.rules));
                Fi exported=new Fi("build/test-maps/fair-ai-industry.msav");
                mindustry.io.MapIO.writeMap(exported,mindustry.Vars.state.map);
                mindustry.maps.Map playable=mindustry.io.MapIO.createMap(exported,false);
                mindustry.Vars.world.loadMap(playable,playable.rules());
                mindustry.Vars.logic.play();
                for(Team t:new Team[]{Team.crux,Team.sharded}) SimulationTest.check(t.core().items.get(Items.copper)==250 && t.core().items.get(Items.lead)==150,
                    "Actual custom-game launch restores the documented finite starting copper and lead");
                System.out.println("EXPORTED industry development map");System.exit(0);
            }
            SaveIO.load(new Fi(args[0]));
            TeamBrain brain=new TeamBrain(Team.crux); brain.economy.refresh(0);
            System.out.println("CORE "+brain.core().items+" armed="+brain.development.armed);
            for(mindustry.gen.Unit u:mindustry.gen.Groups.unit) if(u.team==Team.crux && !u.spawnedByCore) System.out.println("UNIT "+u.type.name+" "+u.x/8f+","+u.y/8f+" health="+u.health);
            for(Building b:brain.view.buildings) if(b.block instanceof GenericCrafter){
                Seq<BuildPlan> route=brain.construction.supplyRoute(b,brain.core());
                System.out.println(b.block.name+" "+b.tileX()+","+b.tileY()+" "+b.items+" feeds="+brain.construction.feeds(b,brain.core())+
                    " route="+(route==null ? "null":route.size)+" owned="+DevelopmentPlanner.owned(brain.team,b));
            }
            System.out.println("TI project="+brain.buildDrill(Items.titanium,null)+" status="+brain.status+" plans="+brain.project);
            brain.dispose(); System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
