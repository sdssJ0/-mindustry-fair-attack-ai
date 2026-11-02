package fairai;

import arc.files.Fi;
import mindustry.content.Items;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.type.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.consumers.*;
import java.util.*;

/** Inspect saved benchmark worlds without advancing any simulation or changing the AI. */
public final class BenchmarkSnapshotAudit{
    public static void main(String[] args){
        try{
            SimulationTest.setup(); new FairAiMod();
            for(String directory:args) for(Fi save:new Fi(directory).list("msav")) inspect(save);
            System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
    static void inspect(Fi save){
        SaveIO.load(save);
        java.util.List<Object> drills=new ArrayList<>(), factories=new ArrayList<>(), network=new ArrayList<>();
        java.util.Map<String,Double> ratedSupply=new TreeMap<>(), recipeDemand=new TreeMap<>();
        java.util.Map<String,Integer> activeUnits=new TreeMap<>();
        for(Unit unit:Groups.unit) if(unit.team==Team.crux) activeUnits.merge(unit.type.name,1,Integer::sum);
        for(Building b:Team.crux.data().buildings){
            java.util.Map<String,Integer> inventory=new TreeMap<>();
            if(b.items!=null)for(Item item:mindustry.Vars.content.items())if(b.items.get(item)>0)inventory.put(item.name,b.items.get(item));
            network.add(EconomyBenchmark.obj("block",b.block.name,"x",b.tileX(),"y",b.tileY(),"rotation",b.rotation,
                "items",inventory,"front",b.front()==null?null:EconomyBenchmark.obj("x",b.front().tileX(),"y",b.front().tileY())));
            if(b instanceof DrillBuild){
                DrillBuild d=(DrillBuild)b;
                if(d.dominantItem==null)continue;
                double rate=3600.0*d.dominantItems/((Drill)b.block).getDrillTime(d.dominantItem);
                ratedSupply.merge(d.dominantItem.name,rate,Double::sum);
                drills.add(EconomyBenchmark.obj("block",b.block.name,"x",b.tileX(),"y",b.tileY(),"item",d.dominantItem.name,
                    "oreTiles",d.dominantItems,"nominalPerMinuteWithoutBoost",rate,"buffer",b.items.get(d.dominantItem)));
            }
            if(b.block instanceof GenericCrafter){
                GenericCrafter c=(GenericCrafter)b.block;
                java.util.Map<String,Double> demand=new TreeMap<>();
                for(Consume consume:c.consumers) if(!consume.optional&&consume instanceof ConsumeItems){
                    for(ItemStack stack:((ConsumeItems)consume).items){
                        double rate=3600.0*stack.amount/c.craftTime;
                        demand.merge(stack.item.name,rate,Double::sum);recipeDemand.merge(stack.item.name,rate,Double::sum);
                    }
                }
                factories.add(EconomyBenchmark.obj("block",b.block.name,"x",b.tileX(),"y",b.tileY(),"fullLoadInputsPerMinute",demand));
            }
        }
        save.sibling(save.nameWithoutExtension()+"-state.json").writeString(EconomyBenchmark.json(EconomyBenchmark.obj(
            "sourceSave",save.name(),"drills",drills,"ratedUnboostedSupplyPerMinute",ratedSupply,
            "crafterFullLoadDemandPerMinute",recipeDemand,"factories",factories,"activeUnits",activeUnits,"network",network)),false,"UTF-8");
        System.out.println(save.name()+" supply="+ratedSupply+" crafterDemand="+recipeDemand);
    }
}
