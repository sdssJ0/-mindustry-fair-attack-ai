package fairai;

import arc.struct.*;
import mindustry.content.*;
import mindustry.type.*;
import mindustry.world.Block;
import mindustry.world.blocks.units.*;
import mindustry.world.consumers.*;

/** Engine recipes, kept separate from construction costs and from unsupported liquid mechanisms. */
public final class RecipeCatalog{
    public static ItemStack[] batch(Block block){
        for(Consume consume : block.consumers) if(!consume.optional && consume instanceof ConsumeItems) return ((ConsumeItems)consume).items;
        return ItemStack.empty;
    }

    public static float perMinute(Block block, Item item, float cyclesPerMinute){
        for(ItemStack stack : batch(block)) if(stack.item == item) return stack.amount * cyclesPerMinute;
        return 0;
    }

    public static UnitType upgrade(Reconstructor block, UnitType input){
        UnitType[] pair = block.upgrades.find(p -> p[0] == input);
        return pair == null ? null : pair[1];
    }

    public static boolean supported(Reconstructor block){
        //A complete liquid adapter is required before permitting the later tiers.
        return block == Blocks.additiveReconstructor || block == Blocks.multiplicativeReconstructor;
    }

    public static ObjectFloatMap<Item> t2Demand(){
        ObjectFloatMap<Item> result = new ObjectFloatMap<>();
        for(ItemStack stack : batch(Blocks.additiveReconstructor)) result.increment(stack.item, 0, stack.amount);
        UnitFactory f = (UnitFactory)Blocks.groundFactory;
        for(ItemStack stack : f.plans.find(p -> p.unit == UnitTypes.dagger).requirements) result.increment(stack.item, 0, stack.amount);
        return result; //One upgraded unit per minute, including its actual T1 input recipe.
    }
    public static ObjectFloatMap<Item> t3Demand(){
        ObjectFloatMap<Item> result=t2Demand();
        for(ItemStack stack:batch(Blocks.multiplicativeReconstructor))result.increment(stack.item,0,stack.amount);
        return result;
    }
}
