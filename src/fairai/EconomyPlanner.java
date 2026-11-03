package fairai;

import arc.math.Mathf;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.Units;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.logic.LAccess;
import mindustry.type.*;
import mindustry.world.Block;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.consumers.*;

/** Conservative per-minute capacity model and economy-first spending policy. Never edits inventories. */
public final class EconomyPlanner{
    private final TeamBrain brain;
    public final ObjectFloatMap<Item> supply = new ObjectFloatMap<>(), demand = new ObjectFloatMap<>();
    private final ObjectFloatMap<Item> wanted = new ObjectFloatMap<>();
    private final ObjectSet<Building> military = new ObjectSet<>();
    public float powerSupply, powerDemand;
    public boolean mobilized;
    public String phase = "建设基础经济";
    private float clock;

    EconomyPlanner(TeamBrain brain){ this.brain = brain; }

    public boolean manages(Building building){
        if(building.block instanceof GenericCrafter) return building.block == Blocks.graphitePress || building.block == Blocks.siliconSmelter ||
            building.block == Blocks.kiln && (brain.requested(Items.metaglass) > 0 || brain.view.buildings.contains(b ->
                b.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret &&
                brain.defense.ammo((mindustry.world.blocks.defense.turrets.ItemTurret)b.block) == Items.metaglass));
        if(building instanceof UnitFactoryBuild) return building.block == Blocks.airFactory || building.block == Blocks.groundFactory;
        return true;
    }

    public int stockGoal(Item item){
        int base = item == Items.graphite ? 80 : item == Items.silicon ? 160 : item == Items.copper ? 180 : item == Items.lead ? 160 : 30;
        return Math.max(Math.max(base, brain.requested(item)),brain.development.stockGoal(item));
    }

    public int reserve(Item item){
        int base = item == Items.copper || item == Items.lead ? 40 : item == Items.graphite ? 20 : item == Items.silicon ? 60 : 0;
        for(ObjectIntMap.Entry<Item> cost : brain.construction.costs(brain.project)) if(cost.key == item) base = Math.max(base, cost.value);
        if(brain.view.count(Blocks.groundFactory) == 0){
            for(ItemStack cost : Blocks.groundFactory.requirements) if(cost.item == item) base = Math.max(base, cost.amount + 20);
        }
        return Math.max(base,brain.development.reserve(item));
    }

    public void refresh(float clock){
        this.clock = clock;
        supply.clear(); demand.clear(); wanted.clear();
        powerSupply = powerDemand = 0;
        if(brain.core() == null) return;
        for(Building b : brain.view.buildings){
            if(b instanceof DrillBuild){
                DrillBuild d = (DrillBuild)b;
                if(d.dominantItem != null) supply.increment(d.dominantItem, 0, drillRate(d));
                if(b.block.consPower != null) powerDemand += b.block.consPower.usage*60f;
            }
            if(b.block instanceof PowerGenerator){
                powerSupply += ((PowerGenerator)b.block).powerProduction * 60f;
                if(b.block instanceof ConsumeGenerator && brain.inputs(b.block, b).contains(Items.coal, true)){
                    demand.increment(Items.coal, 0, 3600f / ((ConsumeGenerator)b.block).itemDuration);
                }
            }
        }
        //One factory at a time until the economy can support more; keep room for development.
        float maxSilicon = 0, unitPower = 0;
        for(Building b : brain.view.buildings){
            if(!(b instanceof UnitFactoryBuild) || !manages(b)) continue;
            UnitFactoryBuild f = (UnitFactoryBuild)b;
            UnitFactory factory = (UnitFactory)b.block;
            int index = f.currentPlan >= 0 ? f.currentPlan : brain.combatPlan(factory);
            if(index < 0) continue;
            UnitFactory.UnitPlan plan = factory.plans.get(index);
            if(!Units.canCreate(brain.team, plan.unit)) continue;
            for(ItemStack item : plan.requirements) if(item.item == Items.silicon) maxSilicon = Math.max(maxSilicon, 3600f * item.amount / plan.time);
            if(b.block.consPower != null) unitPower = Math.max(unitPower, b.block.consPower.usage * 60f);
        }
        boolean needSilicon = brain.core().items.get(Items.silicon) < stockGoal(Items.silicon) || maxSilicon > 0;
        if(needSilicon) wanted.put(Items.silicon, Math.max(60f, maxSilicon + 20f));
        int graphiteShort = stockGoal(Items.graphite) - brain.core().items.get(Items.graphite);
        if(graphiteShort > 0) wanted.put(Items.graphite, Math.min(40f, Math.max(8f, graphiteShort / 2f)));
        for(Item item : new Item[]{Items.graphite,Items.silicon}) wanted.increment(item,0,brain.development.demand(item));
        powerDemand += brain.development.powerDemand();
        for(Item item : new Item[]{Items.graphite, Items.metaglass}){
            float ammo = brain.defense.productionDemand(item);
            if(ammo > 0) wanted.increment(item, 0, ammo);
        }
        for(Building b : brain.view.buildings){
            if(!(b.block instanceof GenericCrafter) || !manages(b)) continue;
            GenericCrafter c = (GenericCrafter)b.block;
            if(c.outputItems == null || c.outputItems.length == 0) continue;
            ItemStack output = c.outputItems[0];
            float goal = wanted.get(output.item, 0);
            if(goal <= 0 && output.item != Items.graphite && output.item != Items.silicon &&
                brain.core().items.get(output.item) < stockGoal(output.item)){
                goal = Math.min(3600f * output.amount / c.craftTime, 30f);
                wanted.put(output.item, goal);
            }
            int count = brain.view.buildings.count(other -> other.block == c && !brain.defense.assigned(other));
            float coreGoal = Math.max(0, goal - brain.defense.productionDemand(output.item));
            float fraction = brain.defense.assigned(b) ? 1f : Math.min(1f, coreGoal / Math.max(0.001f, count * 3600f * output.amount / c.craftTime));
            for(Consume consume : c.consumers){
                if(!consume.optional && consume instanceof ConsumeItems) for(ItemStack item : ((ConsumeItems)consume).items){
                    demand.increment(item.item, 0, 3600f * item.amount / c.craftTime * fraction);
                }
            }
            //Processing is staggered, so one active machine of a recipe sets peak draw.
            if(c.consPower != null && fraction > 0) powerDemand += c.consPower.usage * 60f / (brain.defense.assigned(b) ? 1 : Math.max(1, count));
        }
        powerDemand = (powerDemand + unitPower) * 1.08f;
        //An isolated generator cannot power the industrial grid across the map.
        Building industry = brain.view.buildings.find(b -> b.block == Blocks.siliconSmelter && b.power != null);
        if(industry != null){
            powerDemand = Math.max(powerDemand, industry.power.graph.getLastScaledPowerOut() * 60f * 1.08f);
            powerSupply = 0;
            for(Building b : brain.view.buildings) if(b.block instanceof PowerGenerator && b.power != null && b.power.graph == industry.power.graph){
                powerSupply += ((PowerGenerator)b.block).powerProduction * 60f;
            }
        }
    }

    public float drillRate(DrillBuild drill){
        return drill.dominantItem == null ? 0 : 3600f * drill.dominantItems / ((Drill)drill.block).getDrillTime(drill.dominantItem);
    }

    public float inputRate(Building building, Item item){
        if(!manages(building)) return 0;
        if(building.block instanceof GenericCrafter){
            GenericCrafter c = (GenericCrafter)building.block;
            if(c.outputItems == null || c.outputItems.length == 0) return 0;
            ItemStack output = c.outputItems[0];
            int count = brain.view.buildings.count(other -> other.block == c && !brain.defense.assigned(other));
            float coreGoal = Math.max(0, wanted.get(output.item, 0) - brain.defense.productionDemand(output.item));
            float fraction = brain.defense.assigned(building) ? 1f : Math.min(1f, coreGoal /
                Math.max(0.001f, count * 3600f * output.amount / c.craftTime));
            for(Consume consume : c.consumers) if(!consume.optional && consume instanceof ConsumeItems){
                for(ItemStack input : ((ConsumeItems)consume).items) if(input.item == item) return 3600f * input.amount / c.craftTime * fraction;
            }
        }
        if(building.block instanceof ConsumeGenerator && item == Items.coal) return 3600f / ((ConsumeGenerator)building.block).itemDuration;
        return 0;
    }

    public boolean improve(){
        for(Item item : new Item[]{Items.coal, Items.sand}){
            float needed = demand.get(item, 0);
            if(needed <= 0) continue;
            if(supply.get(item, 0) < needed * 0.95f){
                if(upgrade(item)) return true;
                if(brain.buildDrill(item, rawTarget(item))) return true;
            }
        }
        if(powerSupply + 0.01f < powerDemand && brain.view.count(Blocks.combustionGenerator) < (brain.development.armed ? 12 : 6)){
            if(brain.build(Blocks.combustionGenerator, false)) return true;
        }
        //Economy first includes upgrading existing local raw mining before mobilizing.
        for(Item item : new Item[]{Items.coal, Items.sand, Items.copper, Items.lead}){
            if(brain.core().items.get(Items.graphite) >= 20 && upgrade(item)) return true;
        }
        return false;
    }

    boolean joinIndustrialGrid(){
        Building industry = brain.view.buildings.find(b -> b.block == Blocks.siliconSmelter && b.power != null);
        if(industry == null) return false;
        Building outside = brain.view.buildings.min(b -> b.power != null && b.power.graph != industry.power.graph &&
            (manages(b) && (b instanceof UnitFactoryBuild || b.block instanceof GenericCrafter) || b.block instanceof PowerGenerator && b.within(industry, 320f)), b -> b.dst2(industry));
        if(outside == null) return false;
        Building anchor = brain.view.buildings.min(b -> b.power != null && b.power.graph == industry.power.graph, b -> b.dst2(outside));
        if(anchor == null) return false;
        float dx = outside.x - anchor.x, dy = outside.y - anchor.y, distance = Mathf.len(dx, dy);
        float step = Math.min(distance / 2f, 35f);
        int x = Math.round((anchor.x + dx / Math.max(1f, distance) * step) / 8f);
        int y = Math.round((anchor.y + dy / Math.max(1f, distance) * step) / 8f);
        for(int r = 0; r <= 3; r++) for(int ox = -r; ox <= r; ox++) for(int oy = -r; oy <= r; oy++){
            if(brain.construction.place(Blocks.powerNode, x + ox, y + oy, 0) &&
                brain.submit(Seq.with(new BuildPlan(x + ox, y + oy, 0, Blocks.powerNode)), "合并工业电网")) return true;
        }
        return false;
    }

    private Building rawTarget(Item item){
        return brain.view.buildings.min(b -> inputRate(b, item) > 0, b -> -inputRate(b, item));
    }

    boolean upgrade(Item item){
        DrillBuild best = null;
        float gain = 0;
        Drill next = (Drill)Blocks.pneumaticDrill;
        if(!brain.construction.allowed(next) || !brain.core().items.has(next.requirements, Vars.state.rules.buildCostMultiplier)) return false;
        for(Building b : brain.view.buildings){
            if(!(b instanceof DrillBuild) || b.block != Blocks.mechanicalDrill) continue;
            DrillBuild d = (DrillBuild)b;
            if(d.dominantItem != item || !brain.construction.replacement(new BuildPlan(b.tileX(), b.tileY(), b.rotation, next))) continue;
            float delta = 3600f * d.dominantItems / next.getDrillTime(item) - drillRate(d);
            if(delta > gain){ gain = delta; best = d; }
        }
        return best != null && brain.submit(Seq.with(new BuildPlan(best.tileX(), best.tileY(), best.rotation, next)), "升级矿业：" + item.localizedName);
    }

    public int machinesWanted(Block block){
        if(!(block instanceof GenericCrafter)) return 1;
        GenericCrafter c = (GenericCrafter)block;
        if(c.outputItems == null || c.outputItems.length == 0) return 1;
        ItemStack output = c.outputItems[0];
        return Math.max(1, Mathf.ceil(wanted.get(output.item, 0) / (3600f * output.amount / c.craftTime)));
    }

    public void control(){
        if(brain.core() == null) return;
        Seq<UnitFactoryBuild> candidates = new Seq<>();
        boolean complete = brain.view.count(Blocks.airFactory) > 0 && brain.view.count(Blocks.groundFactory) > 0 &&
            brain.view.count(Blocks.siliconSmelter) > 0 && brain.view.count(Blocks.graphitePress) > 0;
        boolean upgraded = !brain.view.buildings.contains(b -> b.block == Blocks.mechanicalDrill && b instanceof DrillBuild &&
            RawLogistics.processingInput(((DrillBuild)b).dominantItem));
        boolean supplyReady = supply.get(Items.sand, 0) >= demand.get(Items.sand, 0) * 0.9f &&
            supply.get(Items.coal, 0) >= demand.get(Items.coal, 0) * 0.9f && powerSupply >= powerDemand * 0.95f;
        boolean bankReady = brain.core().items.get(Items.silicon) >= (mobilized ? reserve(Items.silicon) : 100) &&
            brain.core().items.get(Items.graphite) >= (mobilized ? 20 : 60);
        boolean ready = complete && upgraded && supplyReady && bankReady && brain.project.isEmpty();
        if(ready) mobilized = true;
        military.clear();
        for(Building b : brain.view.buildings) if(b instanceof UnitFactoryBuild && manages(b)){
            UnitFactoryBuild f = (UnitFactoryBuild)b;
            if(f.currentPlan >= 0 && Units.canCreate(brain.team, ((UnitFactory)b.block).plans.get(f.currentPlan).unit)) candidates.add(f);
        }
        if(ready && candidates.any() && (!brain.development.armed || brain.development.blocked)) military.add(candidates.get((int)(clock / 3600f) % candidates.size));
        phase = !complete ? "经济优先：完善基础产业与造兵设施" : !upgraded ? "经济优先：升级原料矿业" :
            !supplyReady ? "经济优先：补足原料或电力吞吐" : !bankReady ? "经济优先：积累建设储备" :
            brain.project.any() ? "经济优先：为当前施工保留资源" : candidates.isEmpty() ? "部队已达当前上限，保留发展储备" : "经济就绪：轮换集中造兵";
        ObjectSet<Block> scheduled = new ObjectSet<>();
        for(Building b : brain.view.buildings){
            if(!manages(b)) continue;
            if(brain.development.owns(b)) continue;
            if(b instanceof UnitFactoryBuild){ b.control(LAccess.enabled, military.contains(b) ? 1 : 0, 0, 0, 0); continue; }
            if(!(b.block instanceof GenericCrafter)) continue;
            GenericCrafter c = (GenericCrafter)b.block;
            if(brain.defense.assigned(b)){
                //Ammunition keeps working without the controller, using normal inventory backpressure.
                b.control(LAccess.enabled, 1, 0, 0, 0); continue;
            }
            if(!scheduled.add(c) || c.outputItems == null || c.outputItems.length == 0) continue;
            ItemStack output = c.outputItems[0];
            Seq<Building> group = brain.view.buildings.select(other -> other.block == c && !brain.defense.assigned(other));
            float single = 3600f * output.amount / c.craftTime;
            float rate = Math.max(0, wanted.get(output.item, 0) - brain.defense.productionDemand(output.item));
            boolean refill = brain.core().items.get(output.item) < stockGoal(output.item);
            int slot = (int)(clock / 600f) % group.size;
            float fraction = Math.min(1f, rate / single);
            for(int i = 0; i < group.size; i++){
                boolean on = refill && (i == slot || rate > single && ((i - slot + group.size) % group.size) < Mathf.ceil(rate / single)) &&
                    clock % 600f < 600f * fraction;
                group.get(i).control(LAccess.enabled, on ? 1 : 0, 0, 0, 0);
            }
        }
    }

    public boolean militaryAllowed(Building building){ return military.contains(building); }

    public String describe(){
        return phase + "\n理论吞吐/分钟：煤 " + Mathf.round(supply.get(Items.coal, 0)) + "/需求 " + Mathf.round(demand.get(Items.coal, 0)) +
            "；沙 " + Mathf.round(supply.get(Items.sand, 0)) + "/需求 " + Mathf.round(demand.get(Items.sand, 0)) +
            "\n计划电力：供给 " + Mathf.round(powerSupply) + "/需求 " + Mathf.round(powerDemand) + "（含余量）；不假设水冷增益。";
    }
}
