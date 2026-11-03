package fairai;

import arc.*;
import arc.files.Fi;
import arc.math.Mathf;
import arc.mock.MockGraphics;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.entities.Units;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.production.GenericCrafter.GenericCrafterBuild;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.consumers.*;
import java.util.*;

/** Read-only long-run audit around the current controller and actual v146 engine. */
public final class EconomyBenchmark{
    static Audit running;

    public static void main(String[] args){
        try{
            SimulationTest.setup();
            Events.on(EventType.UnitCreateEvent.class, e -> {
                if(running != null && e.unit.team == Team.crux && e.spawner != null){
                    running.created.merge(e.unit.type.name, 1L, Long::sum);
                    running.births.put(e.unit, running.tick);
                    Machine m = running.machine(e.spawner);
                    if(m != null){ m.units++; if(running.late()) m.lateUnits++; }
                }
            });
            Events.on(EventType.WithdrawEvent.class, e -> {
                if(running != null && e.player != null && e.player.team() == Team.crux){
                    running.withdrawals++;
                    running.withdrawn.merge(e.item.name, (long)e.amount, Long::sum);
                    if(e.tile instanceof CoreBuild) running.withdrawnCore.merge(e.item.name, (long)e.amount, Long::sum);
                }
            });
            Events.on(EventType.DepositEvent.class, e -> {
                if(running != null && e.player != null && e.player.team() == Team.crux){
                    running.deposits++;
                    if(e.tile instanceof CoreBuild) running.depositedCore.merge(e.item.name, (long)e.amount, Long::sum);
                }
            });
            String[] scenarios = args[0].split(","), seeds = args[1].split(",");
            int minutes = Integer.parseInt(args[2]);
            Fi output = new Fi(args[3]); output.mkdirs();
            BlueprintLibrary blueprints = new BlueprintLibrary();
            blueprints.load(new Fi("assets/blueprints"), null);
            for(String scenario : scenarios) for(String seed : seeds){
                run(scenario, Integer.parseInt(seed), minutes, output, blueprints);
            }
            System.out.println("BENCHMARK COMPLETE");
            System.exit(0);
        }catch(Throwable failure){ failure.printStackTrace(); System.exit(1); }
    }

    static void run(String scenario, int seed, int minutes, Fi output, BlueprintLibrary library){
        int kind = scenario.startsWith("choke") ? 1 : scenario.startsWith("lake") ? 2 : 0;
        SimulationTest.map(kind);
        if(scenario.startsWith("rich") || scenario.startsWith("late")) enrich();
        if(scenario.startsWith("late")) for(int x=96;x<104;x++) for(int y=20;y<28;y++){
            Vars.world.tile(x,y).setOverlay(Blocks.oreThorium);
            Vars.world.tile(111-x,y).setOverlay(Blocks.oreThorium);
        }
        new FairAiMod().applyFairRules();
        for(Team team : new Team[]{Team.crux, Team.sharded}){
            team.core().items.set(Items.copper, 250);
            team.core().items.set(Items.lead, 150);
            if(scenario.equals("rich-start")) for(Item item : Vars.content.items()) team.core().items.set(item, 1000);
        }
        Mathf.rand.setSeed(seed);
        TeamBrain brain = new TeamBrain(Team.crux, scenario.contains("no-blueprints") ? null : library);
        TeamBrain opponent = scenario.equals("duel") ? new TeamBrain(Team.sharded, library) : null;
        Audit audit = new Audit(scenario, seed, minutes * 3600, brain);
        running = audit;
        System.out.println("START " + scenario + " seed=" + seed + " minutes=" + minutes);
        long start = System.nanoTime();
        for(int tick = 0; tick < audit.limit; tick++){
            audit.tick = tick;
            ((MockGraphics)Core.graphics).incrementFrameId();
            Time.update(); Vars.state.updateId++; Vars.state.tick += Time.delta;
            Vars.state.teams.updateTeamStats();
            brain.update(); if(opponent != null) opponent.update();
            Groups.update();
            audit.sample();
            if(scenario.contains("attrition") && tick % 1800 == 0){
                Seq<Unit> remove = new Seq<>();
                for(Unit unit : Groups.unit){
                    Integer born = audit.births.get(unit);
                    if(unit.team == Team.crux && !unit.spawnedByCore && born != null && tick - born >= 7200) remove.add(unit);
                }
                for(Unit unit : remove){ unit.kill(); audit.simulatedLosses++; }
            }
            if((tick + 1) % (300 * 60) == 0){
                audit.snapshot();
                System.out.println("PROGRESS " + scenario + " seed=" + seed + " t=" + (tick + 1) / 60 +
                    "s projects=" + brain.finishedProjects + " factories=" + audit.currentFactories() + " units=" + audit.created);
            }
            if(brain.core() == null || opponent != null && opponent.core() == null) break;
        }
        audit.snapshot();
        String name = scenario + "-seed-" + seed;
        audit.wallSeconds = (System.nanoTime() - start) / 1e9;
        output.child(name + ".json").writeString(json(audit.result()), false, "UTF-8");
        SaveIO.write(output.child(name + ".msav"));
        output.child(name + "-layout.json").writeString(json(audit.layout()), false, "UTF-8");
        System.out.println("DONE " + name + " wall=" + Math.round(audit.wallSeconds) + "s simulated=" + (audit.tick + 1) / 60 + "s");
        running = null;
        brain.dispose(); if(opponent != null) opponent.dispose();
    }

    static void enrich(){
        //Only initial map terrain changes: no grants or resource modifications during play.
        patch(74, 45, 12, 12, Blocks.oreCopper, false);
        patch(77, 58, 12, 12, Blocks.oreLead, false);
        patch(94, 57, 12, 12, Blocks.oreCoal, false);
        patch(94, 22, 12, 14, Blocks.air, true);
        patch(74, 18, 12, 12, Blocks.oreTitanium, false);
        patch(95, 70, 12, 12, Blocks.oreThorium, false);
    }

    static void patch(int x, int y, int width, int height, Block ore, boolean sand){
        for(int dx = 0; dx < width; dx++) for(int dy = 0; dy < height; dy++){
            for(int tx : new int[]{x + dx, 111 - x - dx}){
                Tile tile = Vars.world.tile(tx, y + dy);
                if(tile == null || tile.build != null) continue;
                if(sand) tile.setFloor(Blocks.sand.asFloor());
                tile.setOverlay(ore);
            }
        }
    }

    static class Machine{
        Building building;
        int firstTick, lastTick = -2;
        float previous;
        long frames, lateFrames, cycles, lateCycles, units, lateUnits;
        double efficiency, lateEfficiency;
        java.util.Map<String, Long> reasons = new TreeMap<>(), lateReasons = new TreeMap<>();
        Machine(Building b, int tick){ building = b; firstTick = tick; }
    }

    static class Audit{
        String scenario;
        int seed, limit, tick, lastConstruction, lastNewType, projects, lastBlueprintCount;
        TeamBrain brain;
        double wallSeconds;
        long withdrawals, deposits, simulatedLosses, rawPositiveInflow;
        java.util.Map<String, Long> created = new TreeMap<>(), withdrawn = new TreeMap<>(), withdrawnCore = new TreeMap<>(), depositedCore = new TreeMap<>();
        IdentityHashMap<Building, Machine> machines = new IdentityHashMap<>();
        IdentityHashMap<Unit, Integer> births = new IdentityHashMap<>();
        java.util.Map<String, Integer> firstTypes = new TreeMap<>();
        java.util.List<Object> timeline = new ArrayList<>(), blueprintUses = new ArrayList<>();
        int previousCoal, previousSand;
        Audit(String scenario, int seed, int limit, TeamBrain brain){
            this.scenario = scenario; this.seed = seed; this.limit = limit; this.brain = brain;
            previousCoal = brain.core().items.get(Items.coal); previousSand = brain.core().items.get(Items.sand);
        }
        boolean late(){ return tick >= limit - 600 * 60; }
        Machine machine(Building b){
            if(!(b instanceof GenericCrafterBuild || b instanceof UnitFactoryBuild || b instanceof DrillBuild || b.block instanceof PowerGenerator)) return null;
            Machine m = machines.get(b);
            if(m == null){ m = new Machine(b, tick); machines.put(b, m); }
            return m;
        }
        void sample(){
            if(brain.finishedProjects != projects){ projects = brain.finishedProjects; lastConstruction = tick; }
            if(brain.blueprintProjects != lastBlueprintCount){
                blueprintUses.add(obj("seconds", tick / 60.0, "description", brain.lastBlueprint)); lastBlueprintCount = brain.blueprintProjects;
            }
            if(brain.core() != null){
                int coal = brain.core().items.get(Items.coal), sand = brain.core().items.get(Items.sand);
                rawPositiveInflow += Math.max(0, coal - previousCoal) + Math.max(0, sand - previousSand);
                previousCoal = coal; previousSand = sand;
            }
            for(Building b : Team.crux.data().buildings){
                if(!firstTypes.containsKey(b.block.name)){ firstTypes.put(b.block.name, tick); lastNewType = tick; }
                Machine m = machine(b); if(m == null) continue;
                float progress = b instanceof GenericCrafterBuild ? ((GenericCrafterBuild)b).progress : b instanceof DrillBuild ? ((DrillBuild)b).progress : 0;
                if(m.lastTick == tick - 1 && progress + 0.00001f < m.previous){ m.cycles++; if(late()) m.lateCycles++; }
                m.previous = progress; m.lastTick = tick;
                m.frames++; m.efficiency += b.efficiency;
                String reason = reason(b);
                m.reasons.merge(reason, 1L, Long::sum);
                if(late()){
                    m.lateFrames++; m.lateEfficiency += b.efficiency; m.lateReasons.merge(reason, 1L, Long::sum);
                }
            }
        }
        String reason(Building b){
            if(!b.enabled) return "disabled";
            if(b instanceof UnitFactoryBuild){
                UnitFactoryBuild f = (UnitFactoryBuild)b;
                if(f.currentPlan < 0) return "unconfigured";
                if(f.payload != null) return "payload-blocked-or-cap";
            }
            if(b instanceof GenericCrafterBuild){
                GenericCrafter c = (GenericCrafter)b.block;
                if(c.outputItems != null) for(ItemStack item : c.outputItems) if(b.items.get(item.item) + item.amount > c.itemCapacity) return "output-full";
            }
            for(Consume consume : b.block.consumers){
                if(consume.optional) continue;
                ItemStack[] items = consume instanceof ConsumeItems ? ((ConsumeItems)consume).items :
                    consume instanceof ConsumeItemDynamic ? ((ConsumeItemDynamic)consume).items.get(b) : null;
                if(items != null) for(ItemStack item : items) if(b.items.get(item.item) < item.amount) return "input-shortage";
                if(consume instanceof ConsumeItemFilter){
                    boolean any = false;
                    for(Item item : Vars.content.items()) if(((ConsumeItemFilter)consume).filter.get(item) && b.items.has(item)) any = true;
                    if(!any) return "input-shortage";
                }
            }
            if(b.power != null && b.block.consPower != null && b.power.status < 0.95f) return "power-shortage";
            if(b instanceof DrillBuild && b.items.total() >= b.block.itemCapacity) return "output-full";
            return b.efficiency > 0.001f ? "working" : "other-idle";
        }
        java.util.Map<String, Long> counts(){
            java.util.Map<String, Long> counts = new TreeMap<>();
            for(Building b : Team.crux.data().buildings) counts.merge(b.block.name, 1L, Long::sum);
            return counts;
        }
        java.util.Map<String, Long> currentFactories(){
            java.util.Map<String, Long> result = new TreeMap<>();
            for(Building b : Team.crux.data().buildings) if(b instanceof GenericCrafterBuild || b instanceof UnitFactoryBuild) result.merge(b.block.name, 1L, Long::sum);
            return result;
        }
        java.util.Map<String, Object> stock(){
            java.util.Map<String, Object> result = new TreeMap<>();
            if(brain.core() != null) for(Item item : Vars.content.items()) if(brain.core().items.get(item) > 0) result.put(item.name, brain.core().items.get(item));
            return result;
        }
        java.util.Map<String, Object> power(){
            ObjectSet<PowerGraph> graphs = new ObjectSet<>(); double in = 0, out = 0, stored = 0;
            for(Building b : Team.crux.data().buildings) if(b.power != null && graphs.add(b.power.graph)){
                in += b.power.graph.getLastScaledPowerIn() * 60; out += b.power.graph.getLastScaledPowerOut() * 60; stored += b.power.graph.getLastPowerStored();
            }
            return obj("generation", in, "demand", out, "stored", stored);
        }
        void snapshot(){
            timeline.add(obj("seconds", (tick + 1) / 60.0, "stock", stock(), "factories", currentFactories(), "allBuildings", counts(),
                "manufacturedUnits", new TreeMap<>(created), "power", power(), "projects", brain.finishedProjects, "status", brain.status,
                "coalInCore", previousCoal, "sandInCore", previousSand, "productionTotals", productionTotals(), "economy", brain.economy.describe(),
                "development",brain.development.describe(),"retirement",brain.retirement.describe()));
        }
        Object productionTotals(){
            java.util.Map<String, java.util.Map<String,Object>> result = new TreeMap<>();
            for(Machine m : machines.values()){
                java.util.Map<String,Object> a = result.get(m.building.block.name);
                if(a == null){ a = obj("frames",0L,"cycles",0L,"units",0L); result.put(m.building.block.name,a); }
                add(a,"frames",m.frames); add(a,"cycles",m.cycles); add(a,"units",m.units);
            }
            return result;
        }
        Object result(){
            java.util.Map<String, java.util.Map<String, Object>> aggregate = new TreeMap<>();
            java.util.List<Object> detail = new ArrayList<>();
            for(Machine m : machines.values()){
                Building b = m.building;
                java.util.Map<String, Object> a = aggregate.get(b.block.name);
                if(a == null){ a = obj("instancesEverBuilt", 0L, "frames", 0L, "lateFrames", 0L, "cycles", 0L, "lateCycles", 0L,
                    "units", 0L, "lateUnits", 0L, "efficiencySum", 0.0, "lateEfficiencySum", 0.0, "reasons", new TreeMap<String, Long>(), "lateReasons", new TreeMap<String, Long>());
                    if(b.block instanceof GenericCrafter){
                        GenericCrafter crafter = (GenericCrafter)b.block; a.put("craftTimeTicks", crafter.craftTime);
                        java.util.Map<String, Object> outputs = new TreeMap<>();
                        if(crafter.outputItems != null) for(ItemStack item : crafter.outputItems) outputs.put(item.item.name, item.amount);
                        a.put("outputsPerCycle", outputs);
                    }
                    aggregate.put(b.block.name, a);
                }
                add(a, "instancesEverBuilt", 1); add(a,"frames",m.frames); add(a,"lateFrames",m.lateFrames); add(a,"cycles",m.cycles); add(a,"lateCycles",m.lateCycles);
                add(a,"units",m.units); add(a,"lateUnits",m.lateUnits); add(a,"efficiencySum",m.efficiency); add(a,"lateEfficiencySum",m.lateEfficiency);
                mergeReasons(a, "reasons", m.reasons); mergeReasons(a, "lateReasons", m.lateReasons);
                java.util.Map<String,Object> inventory = new TreeMap<>();
                if(b.items != null) for(Item item : Vars.content.items()) if(b.items.get(item) > 0) inventory.put(item.name,b.items.get(item));
                detail.add(obj("block", b.block.name, "x", b.tileX(), "y", b.tileY(), "firstSeconds", m.firstTick / 60.0,
                    "stillPresent", b.isValid(), "frames", m.frames, "cycles", m.cycles, "lateFrames", m.lateFrames,
                    "lateCycles", m.lateCycles, "units", m.units, "lateUnits", m.lateUnits, "lateReasons", m.lateReasons,
                    "inventory", inventory, "powerStatus", b.power==null?null:b.power.status));
            }
            java.util.Map<String,Object> discovered = new TreeMap<>();
            for(java.util.Map.Entry<String,Integer> e : firstTypes.entrySet()) discovered.put(e.getKey(), e.getValue() / 60.0);
            java.util.List<Object> defenses = new ArrayList<>();
            for(Building b : Team.crux.data().buildings) if(b instanceof mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild){
                defenses.add(obj("block",b.block.name,"x",b.tileX(),"y",b.tileY(),"automated",brain.defense.connected(b),
                    "ammo",((mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)b).totalAmmo));
            }
            return obj("scenario",scenario,"seed",seed,"plannedMinutes",limit/3600,"actualSeconds",(tick+1)/60.0,"wallSeconds",wallSeconds,
                "emergencyMiningSeconds",brain.emergencyMiningTicks/60f,"defenses",defenses,
                "completedT2Upgrades",brain.development.upgraded(),"development",brain.development.describe(),
                "retiredFacilities",brain.retirement.retired,"retirement",brain.retirement.describe(),
                "lateWindowSeconds",600,"firstBuiltSeconds",discovered,"lastNewTypeSeconds",lastNewType/60.0,"lastCompletedProjectSeconds",lastConstruction/60.0,
                "currentBuildings",counts(),"currentFactories",currentFactories(),"coreStock",stock(),"machines",aggregate,"machineInstances",detail,
                "unitCapPerType",Units.getCap(Team.crux),"manufacturedUnits",created,"simulatedAttritionKills",simulatedLosses,
                "coreAlive",brain.core()!=null,"opponentCoreAlive",Team.sharded.core()!=null,"power",power(),
                "rawPositiveCoreInflow",rawPositiveInflow,"withdrawalTrips",withdrawals,"depositTrips",deposits,
                "withdrawnItems",withdrawn,"withdrawnFromCore",withdrawnCore,"depositedToCore",depositedCore,
                "blueprintProjects",brain.blueprintProjects,"blueprintUses",blueprintUses,"timeline",timeline);
        }
        Object layout(){
            java.util.List<Object> buildings = new ArrayList<>();
            for(Building b : Team.crux.data().buildings) buildings.add(obj("block",b.block.name,"x",b.tileX(),"y",b.tileY(),"size",b.block.size,"rotation",b.rotation));
            java.util.List<Object> terrain = new ArrayList<>();
            for(Tile tile : Vars.world.tiles) if(tile.drop() != null || tile.floor().isLiquid || tile.solid() && tile.build == null){
                terrain.add(obj("x",tile.x,"y",tile.y,"ore",tile.drop()==null?null:tile.drop().name,"liquid",tile.floor().isLiquid,"solid",tile.solid()));
            }
            return obj("scenario",scenario,"seed",seed,"width",Vars.world.width(),"height",Vars.world.height(),"buildings",buildings,"terrain",terrain);
        }
    }

    static java.util.Map<String,Object> obj(Object... pairs){
        java.util.Map<String,Object> map = new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) map.put((String)pairs[i],pairs[i+1]); return map;
    }
    static void add(java.util.Map<String,Object> target,String key,double value){ target.put(key,((Number)target.get(key)).doubleValue()+value); }
    @SuppressWarnings("unchecked")
    static void mergeReasons(java.util.Map<String,Object> target,String key,java.util.Map<String,Long> source){
        java.util.Map<String,Long> reasons=(java.util.Map<String,Long>)target.get(key);
        for(java.util.Map.Entry<String,Long> e:source.entrySet()) reasons.merge(e.getKey(),e.getValue(),Long::sum);
    }
    static String json(Object value){
        if(value==null) return "null";
        if(value instanceof Number || value instanceof Boolean) return value.toString();
        if(value instanceof java.util.Map){
            StringBuilder result=new StringBuilder("{"); boolean first=true;
            for(Object raw:((java.util.Map<?,?>)value).entrySet()){
                java.util.Map.Entry<?,?> entry=(java.util.Map.Entry<?,?>)raw;
                if(!first)result.append(',');first=false;result.append(json(entry.getKey().toString())).append(':').append(json(entry.getValue()));
            }
            return result.append('}').toString();
        }
        if(value instanceof Iterable){
            StringBuilder result=new StringBuilder("[");boolean first=true;
            for(Object item:(Iterable<?>)value){if(!first)result.append(',');first=false;result.append(json(item));}return result.append(']').toString();
        }
        return "\""+value.toString().replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";
    }
}
