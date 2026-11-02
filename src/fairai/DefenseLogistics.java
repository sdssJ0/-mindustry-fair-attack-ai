package fairai;

import arc.struct.Seq;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.type.Item;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.production.GenericCrafter;

/** Persistent, paid producer-to-turret lines. Never takes ammunition from the treasury. */
public final class DefenseLogistics{
    public String issue = "";
    private final TeamBrain brain;
    DefenseLogistics(TeamBrain brain){ this.brain = brain; }

    void reserve(int source, Building target){
        Vars.state.rules.tags.put("fair-ai.ammo-source." + brain.team.id + "." + source, Integer.toString(target.pos()));
    }

    private Building reservedFor(Building source){
        int pos = Vars.state.rules.tags.getInt("fair-ai.ammo-source." + brain.team.id + "." + source.pos(), -1);
        Building target = Vars.world.build(pos);
        return target != null && target.team == brain.team && target.block instanceof ItemTurret ? target : null;
    }

    Item ammo(ItemTurret turret){
        //Prefer the simplest sustainable ammunition over rare, powerful alternatives.
        for(Item item : new Item[]{Items.copper, Items.lead, Items.graphite, Items.metaglass}){
            if(turret.ammoTypes.containsKey(item)) return item;
        }
        return null;
    }

    boolean needs(Item item){
        return brain.view.buildings.contains(b -> b.block instanceof ItemTurret && ammo((ItemTurret)b.block) == item && !connected(b));
    }

    boolean assigned(Building source){
        return reservedFor(source) != null || brain.view.buildings.contains(b -> b.block instanceof ItemTurret &&
            ammo((ItemTurret)b.block) == brain.construction.outputItem(source) && brain.construction.feeds(source, b));
    }

    boolean connected(Building turret){
        Item item = ammo((ItemTurret)turret.block);
        return item != null && brain.view.buildings.contains(source ->
            brain.construction.outputItem(source) == item && brain.construction.feeds(source, turret) && automated(source, 0));
    }

    private boolean automated(Building source, int depth){
        if(depth > 6) return false;
        for(Item input : brain.inputs(source.block, source)){
            if(!brain.view.buildings.contains(upstream -> brain.construction.outputItem(upstream) == input &&
                brain.construction.feeds(upstream, source) && automated(upstream, depth + 1))) return false;
        }
        return true;
    }

    float ammoRate(Building building){
        ItemTurret turret = (ItemTurret)building.block;
        Item item = ammo(turret);
        return item == null ? 0 : 3600f / turret.reload * turret.ammoPerShot *
            (turret.consumeAmmoOnce ? 1 : turret.shoot.shots) * turret.ammoTypes.get(item).reloadMultiplier /
            Math.max(1f, turret.ammoTypes.get(item).ammoMultiplier);
    }

    float productionDemand(Item item){
        float rate = 0;
        for(Building b : brain.view.buildings) if(b.block instanceof ItemTurret && ammo((ItemTurret)b.block) == item) rate += ammoRate(b);
        return rate;
    }

    boolean ready(){
        return !brain.view.buildings.contains(b -> b.block instanceof ItemTurret && ammo((ItemTurret)b.block) != null && !connected(b));
    }

    boolean plan(){
        for(Building target : brain.view.buildings){
            if(!(target.block instanceof ItemTurret)) continue;
            Item item = ammo((ItemTurret)target.block);
            if(item == null || connected(target)) continue;
            //Do not reroute construction income or another consumer's working supply.
            Seq<Building> sources = brain.view.buildings.select(b -> brain.construction.outputItem(b) == item &&
                (reservedFor(b) == null || reservedFor(b) == target) &&
                (reservedFor(b) == target || !brain.construction.feeds(b, brain.core()) && !brain.view.buildings.contains(other -> other != b && other != target &&
                    brain.inputs(other.block, other).contains(item, true) && brain.construction.feeds(b, other))));
            sources.sort(b -> b.dst2(target));
            for(Building source : sources){
                Seq<BuildPlan> route = brain.construction.supplyRoute(source, target);
                if(route != null && route.any() && brain.submit(route, "自动供弹：" + item.localizedName + " → " + target.block.localizedName)){
                    reserve(source.pos(), target);
                    brain.construction.recordFlow(route, item); return true;
                }
            }
            //One unfinished source per ammunition type. An unreachable route cannot cause endless mine spam.
            GenericCrafter recipe = brain.producer(item);
            if(recipe == null){
                if(sources.any()) continue;
                if(brain.buildDrill(item, target, true)) return true;
            }else if(!sources.contains(b -> reservedFor(b) == target) && brain.buildAmmoFactory(recipe, target)) return true;
        }
        return false;
    }
}
