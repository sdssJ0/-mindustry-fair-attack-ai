package fairai;

import mindustry.Vars;
import mindustry.content.*;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;

import static fairai.SimulationTest.*;

/** Real-frame regression tests for automated mining and autonomous ammunition supply. */
final class AutomationTest{
    public static void main(String[] args){
        try{ setup(); if(args.length > 0 && args[0].equals("processed")) processedAmmoStaysAutomated(); else run(); System.out.println("PASS automation: " + passed); System.exit(0); }
        catch(Throwable error){ error.printStackTrace(); System.exit(1); }
    }

    static void run(){
        normalStartDoesNotHandMine();
        inheritedTurretsGetAutomation();
        processedAmmoStaysAutomated();
        unfedDefenseDoesNotMultiply();
    }

    static void normalStartDoesNotHandMine(){
        TeamBrain brain = RawLogisticsTest.fixture();
        brain.core().items.set(Items.copper,250); brain.core().items.set(Items.lead,150);
        frames(brain, 3600 * 12);
        brain.view.refresh();
        check(brain.emergencyMiningTicks == 0, "A funded normal start never uses core-ship mining to top up stock");
        check(brain.view.buildings.count(b -> b instanceof DrillBuild && ((DrillBuild)b).dominantItem == Items.copper &&
            brain.construction.feeds(b,brain.core())) >= 2,
            "Early expansion builds multiple paid copper mines with real core-bound conveyors");
        check(brain.view.buildings.count(b -> b instanceof DrillBuild && ((DrillBuild)b).dominantItem == Items.lead &&
            brain.construction.feeds(b,brain.core())) >= 2,
            "Early expansion builds multiple autonomous lead mines rather than hand-mining reserves");
        float ticks=brain.emergencyMiningTicks;
        brain.core().items.set(Items.copper,0); brain.core().items.set(Items.lead,0);
        frames(brain,1800);
        check(brain.emergencyMiningTicks==ticks,"Temporary empty stock waits for existing automated income instead of hand mining");
        brain.dispose();
    }

    static void inheritedTurretsGetAutomation(){
        TeamBrain brain = RawLogisticsTest.fixture();
        Vars.world.tile(87,40).setBlock(Blocks.duo,Team.crux);
        Vars.world.tile(86,48).setBlock(Blocks.scatter,Team.crux);
        ItemTurretBuild duo=(ItemTurretBuild)Vars.world.build(87,40), scatter=(ItemTurretBuild)Vars.world.build(86,48);
        frames(brain,3600 * 12); brain.view.refresh();
        System.out.println("Defense diagnostic: " + brain.status + " core=" + brain.core().items + " ammo="+duo.totalAmmo+","+scatter.totalAmmo);
        check(brain.defense.connected(duo) && brain.defense.connected(scatter),
            "Inherited duo and scatter get dedicated real mining-and-conveyor ammunition lines");
        check(duo.totalAmmo>0 && scatter.totalAmmo>0,"Both turret types receive real ammunition without player deliveries");
        Building source=brain.view.buildings.find(b -> brain.construction.outputItem(b)==Items.copper && brain.construction.feeds(b,duo));
        Building belt=brain.construction.outputBelts(source).find(b -> b.front()==duo);
        check(belt!=null,"The ammunition supply is a physical conveyor rather than a worker task");
        belt.tile.remove(); brain.view.refresh();
        frames(brain,3600 * 3); brain.view.refresh();
        if(!brain.defense.connected(duo)) System.out.println("Repair diagnostic: source="+source.tileX()+","+source.tileY()+
            " assigned="+brain.defense.assigned(source)+" issue="+brain.defense.issue+" status="+brain.status+" core="+brain.core().items+
            " project="+brain.project+" route="+brain.construction.supplyRoute(source,duo));
        check(brain.defense.connected(duo),"A destroyed ammunition conveyor is rebuilt through paid construction");
        //Deplete fixture magazines; stop AI updates completely to prove conveyors refill them by themselves.
        duo.ammo.clear(); duo.totalAmmo=0; scatter.ammo.clear(); scatter.totalAmmo=0;
        frames(null,3600);
        check(duo.totalAmmo>0 && scatter.totalAmmo>0,"Mines and conveyors refill empty magazines with the controller stopped");
        brain.dispose();
    }

    static void processedAmmoStaysAutomated(){
        TeamBrain brain = RawLogisticsTest.fixture();
        Vars.world.tile(86,44).setBlock(Blocks.hail,Team.crux);
        ItemTurretBuild hail=(ItemTurretBuild)Vars.world.build(86,44);
        frames(brain,3600 * 15); brain.view.refresh();
        System.out.println("Processed ammo diagnostic: " + brain.status + " core=" + brain.core().items + " ammo="+hail.totalAmmo);
        if(hail.totalAmmo==0) for(Building b:brain.view.buildings) if(b.block==Blocks.graphitePress || b instanceof DrillBuild){
            System.out.println("AMMO SOURCE "+b.block.name+" "+b.tileX()+","+b.tileY()+" output="+brain.construction.outputItem(b)+
                " items="+b.items+" enabled="+b.enabled+" toHail="+brain.construction.feeds(b,hail)+" assigned="+brain.defense.assigned(b));
        }
        check(brain.defense.connected(hail) && hail.totalAmmo>0,
            "Graphite ammunition is produced by a real coal-to-press-to-turret chain");
        brain.core().items.set(Items.graphite,brain.core().storageCapacity);
        frames(brain,1200);
        hail.ammo.clear(); hail.totalAmmo=0;
        frames(null,3600);
        check(hail.totalAmmo>0,"A full construction graphite stockpile never shuts down the ammunition production line");
        check(brain.core().items.get(Items.coal)==0 && brain.core().items.get(Items.sand)==0,
            "Ammunition automation still keeps coal and sand out of the core");
        brain.dispose();
    }

    static void unfedDefenseDoesNotMultiply(){
        TeamBrain brain=RawLogisticsTest.fixture();
        for(mindustry.world.Tile tile:Vars.world.tiles) if(tile.overlay()==Blocks.oreCopper) tile.setOverlay(Blocks.air);
        Vars.world.tile(87,40).setBlock(Blocks.duo,Team.crux);
        Vars.world.tile(105,44).setBlock(Blocks.copperWall,Team.sharded);
        frames(brain,3600); brain.view.refresh();
        check(brain.view.count(Blocks.duo)==1,"An unsupported empty defense is not multiplied into more un-fed turrets under threat");
        check(((ItemTurretBuild)Vars.world.build(87,40)).totalAmmo==0,
            "A missing ammunition mine cannot be hidden by free or routine hand-delivered ammunition");
        brain.dispose();
    }
}
