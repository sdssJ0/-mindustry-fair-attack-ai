package fairai;

import arc.math.*;
import arc.struct.*;
import arc.util.Structs;
import mindustry.Vars;
import mindustry.ai.types.CommandAI;
import mindustry.content.*;
import mindustry.entities.units.*;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.input.InputHandler;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.units.UnitFactory.UnitFactoryBuild;
import mindustry.world.consumers.*;

/** One AI player per enemy team, using normal player machinery and a shared treasury. */
public final class TeamBrain{
    public final Team team;
    public final WorldView view;
    public final Construction construction;
    public final Player actor;
    public final BlueprintLibrary blueprints;
    public final RawLogistics raw;
    public final EconomyPlanner economy;
    public final DefenseLogistics defense;
    public final Seq<BuildPlan> project = new Seq<>();
    public String status = "等待核心机体重生";
    public int finishedProjects;
    public final Seq<String> supplyLog = new Seq<>();
    public String lastBlueprint = "尚未使用蓝图";
    public int blueprintProjects;
    private final Worker driver = new Worker();
    private final ObjectIntMap<Item> demands = new ObjectIntMap<>();
    private final ObjectSet<Item> supplyStack = new ObjectSet<>();
    private final IntSet failedSites = new IntSet();
    private float clock, nextThink, nextVision, nextCommand, lastProgress, nextTransfer;
    private int progressSignature;
    private Item mineItem;
    private Tile mineTile;
    private Building source, destination;
    private Item cargo;
    private int cargoAmount;
    private float cargoStart;
    private int exploreIndex;
    private int supplyCursor;
    private float clearFailedAt = 1800f;
    public float emergencyMiningTicks;

    public TeamBrain(Team team){
        this(team, null);
    }

    public TeamBrain(Team team, BlueprintLibrary blueprints){
        this.team = team;
        this.blueprints = blueprints;
        this.view = new WorldView(team);
        this.construction = new Construction(team, view);
        this.raw = new RawLogistics(this);
        this.economy = new EconomyPlanner(this);
        this.defense = new DefenseLogistics(this);
        actor = new BotPlayer();
        actor.name = "公平 AI · " + team.name;
        actor.team(team);
        actor.color.set(team.color);
        actor.add();
        //Core units are serialized, players are not. Reattach before the first Unit.update.
        int savedUnit = Vars.state.rules.tags.getInt("fair-ai.unit." + team.id, -1);
        Unit restored = Groups.unit.getByID(savedUnit);
        if(restored != null && restored.team == team && restored.spawnedByCore && !restored.dead){
            actor.unit(restored);
        }
        view.refresh();
    }

    public void dispose(){
        actor.remove();
    }

    public CoreBuild core(){ return team.core(); }

    public void update(){
        clock += arc.util.Time.delta;
        if(clock >= clearFailedAt){ failedSites.clear(); clearFailedAt = clock + 1800f; }
        if(core() == null){
            status = "核心已被摧毁";
            return;
        }
        if(clock >= nextVision){ view.refresh(); nextVision = clock + 30f; }
        if(actor.dead()) return; //Player.update supplies the same normal deathDelay and core spawn.
        Unit unit = actor.unit();
        actor.set(unit);
        Vars.state.rules.tags.put("fair-ai.unit." + team.id, Integer.toString(unit.id));
        driver.unit(unit);
        if(clock >= nextCommand){ commandArmy(); nextCommand = clock + 120f; }
        if(clock >= nextThink){
            economy.refresh(clock);
            plan();
            economy.refresh(clock);
            economy.control();
            nextThink = clock + 120f;
        }
        work(unit);
    }

    private void plan(){
        if(project.any()) return;
        demands.clear();
        demands.put(Items.copper, 160);
        demands.put(Items.lead, 100);

        //All technology remains unlocked. These are economic priorities, never a tech ban.
        if(raw.disconnectCoreInputs()) return;
        //Initial stock funds infrastructure; it is not evidence of a working economy.
        for(Item item : new Item[]{Items.copper, Items.lead, Items.coal, Items.sand}){
            if(ensureSupply(item, 0) == Supply.working) return;
        }
        if(repairMiningReturns()) return;
        //Repair missing upstream production before topping up existing consumers.
        if(prepareInputs()) return;
        //Existing defenses get real ammunition infrastructure before more turrets are bought.
        if(defense.plan()) return;
        //Reserve working local defense before later mining belts occupy every edge of small ore patches.
        if(defense.ready() && view.count(Blocks.duo) == 0 && build(Blocks.duo, true)) return;
        if(defense.ready() && view.count(Blocks.scatter) == 0 && build(Blocks.scatter, true)) return;
        if(expandCoreMining()) return;
        if(productReturns()) return;
        if(economy.joinIndustrialGrid()) return;
        if(repairPower()) return;
        if(economy.improve()) return;
        if(raw.plan()) return;
        if(threatNearCore() && defense.ready()){
            Block turret = hasAirThreat() ? Blocks.scatter : Blocks.duo;
            if(view.count(turret) < 3 && build(turret, true)) return;
        }
        if(ensureItem(Items.copper, 40, 0) || ensureItem(Items.lead, 40, 0)) return;
        if(!view.buildings.contains(b -> b.block == Blocks.graphitePress && !defense.assigned(b)) && build(Blocks.graphitePress, false)) return;
        if(ensureItem(Items.graphite, 25, 0)) return;
        if(view.count(Blocks.combustionGenerator) < 2 && build(Blocks.combustionGenerator, false)) return;
        if(!view.buildings.contains(b -> b.block == Blocks.siliconSmelter && !defense.assigned(b)) && build(Blocks.siliconSmelter, false)) return;
        if(view.count(Blocks.airFactory) == 0 && build(Blocks.airFactory, false)) return;
        if(defense.ready() && view.count(Blocks.duo) < 2 && build(Blocks.duo, true)) return;
        if(defense.ready() && view.count(Blocks.scatter) < 2 && build(Blocks.scatter, true)) return;
        if(view.count(Blocks.groundFactory) == 0 && build(Blocks.groundFactory, false)) return;

        if(rebuildDestroyed()) return;
        //Factory count follows throughput; an empty core alone is never a reason to clone factories.
        if(view.count(Blocks.siliconSmelter) < economy.machinesWanted(Blocks.siliconSmelter) && build(Blocks.siliconSmelter, false)) return;
        if(view.count(Blocks.graphitePress) < economy.machinesWanted(Blocks.graphitePress) && build(Blocks.graphitePress, false)) return;
        if(drillCount(Items.titanium) == 0 && core().items.get(Items.graphite) >= 40 && buildDrill(Items.titanium)) return;
        status = economy.phase;
    }

    /** Return true only if a real job was started. Missing inaccessible resources never freeze all planning. */
    boolean ensureItem(Item item, int amount, int depth){
        if(depth > 6 || core() == null) return false;
        if(RawLogistics.processingInput(item)){
            if(ensureSupply(item, depth + 1) == Supply.working) return true;
            Building target = raw.consumer(item, actor.unit());
            return target != null && deliver(target, item, amount);
        }
        demands.put(item, Math.max(demands.get(item, 0), amount));
        if(core().items.get(item) >= amount) return false;
        if(actor.unit().stack.amount == 0 && (cargo != item || destination != core())){
            source = destination = null; cargo = null;
        }
        if(ensureSupply(item, depth + 1) == Supply.working) return true;
        if(collectOutput(item)) return true;
        Unit unit = actor.unit();
        Tile ore = view.ore(item, unit);
        if(ore != null && emergencyMine(item, amount)) return true;
        if(ore != null && hasCoreIncome(item)){
            status = "等待自动矿业供给：" + item.localizedName;
            return true;
        }
        GenericCrafter producer = producer(item);
        if(producer != null){
            Building existing = view.buildings.find(b -> b.block == producer && !defense.assigned(b));
            if(existing == null) return build(producer, false, depth + 1);
            if(producer.consPower != null && existing.power != null && existing.power.status < 0.9f && repairPower()) return true;
            for(Consume consume : producer.consumers){
                if(consume.optional) continue;
                if(consume instanceof ConsumeItems){
                    for(ItemStack input : ((ConsumeItems)consume).items){
                        if(existing.items.get(input.item) < Math.min(existing.getMaximumAccepted(input.item), input.amount * 5)){
                            if(ensureSupply(input.item, depth + 1) == Supply.working) return true;
                            if(deliver(existing, input.item, Math.max(10, input.amount * 5))) return true;
                            if(ensureItem(input.item, Math.max(30, input.amount * 10), depth + 1)) return true;
                        }
                    }
                }
            }
        }
        if(drillCount(item) == 0 && buildDrill(item)) return true;
        status = "缺少 " + item.localizedName + "，寻找矿源或生产条件";
        return false;
    }

    int requested(Item item){ return demands.get(item, 0); }

    private boolean productReturns(){
        for(Building producer : view.buildings){
            if(!economy.manages(producer)) continue;
            if(!(producer.block instanceof GenericCrafter)) continue;
            GenericCrafter crafter = (GenericCrafter)producer.block;
            if(defense.assigned(producer)) continue;
            if(crafter.outputItems == null || Structs.contains(crafter.outputItems, s -> RawLogistics.processingInput(s.item))) continue;
            if(construction.feeds(producer, core())) continue;
            Seq<BuildPlan> route = construction.supplyRoute(producer, core());
            if(route != null && route.any() && submit(route, "成品自动入库：" + producer.block.localizedName)){
                construction.recordFlow(route, construction.outputItem(producer)); return true;
            }
        }
        return false;
    }

    private enum Supply{ ready, working, unavailable }

    /** Structural prerequisites, deliberately independent of the treasury's current stock. */
    private Supply ensureSupply(Item item, int depth){
        if(depth > 8 || !supplyStack.add(item)) return Supply.unavailable;
        try{
            if(drillCount(item) > 0) return Supply.ready;
            if(buildDrill(item)) return Supply.working;
            GenericCrafter producer = producer(item);
            if(producer == null) return Supply.unavailable;
            boolean missing = false;
            for(Consume consume : producer.consumers){
                if(consume.optional || !(consume instanceof ConsumeItems)) continue;
                for(ItemStack input : ((ConsumeItems)consume).items){
                    Supply upstream = ensureSupply(input.item, depth + 1);
                    if(upstream == Supply.working) return upstream;
                    if(upstream != Supply.ready) missing = true;
                }
            }
            if(missing) return Supply.unavailable;
            if(view.count(producer) > 0) return Supply.ready;
            return build(producer, false, depth + 1) ? Supply.working : Supply.unavailable;
        }finally{
            supplyStack.remove(item);
        }
    }

    /** Existing sources only; used by hauling so a delivery cannot create construction work. */
    public boolean hasProduction(Item item){ return hasProduction(item, new ObjectSet<Item>(), 0); }

    private boolean hasProduction(Item item, ObjectSet<Item> visiting, int depth){
        if(depth > 8 || !visiting.add(item)) return false;
        try{
            if(drillCount(item) > 0) return true;
            for(Building building : view.buildings){
                if(!(building.block instanceof GenericCrafter)) continue;
                GenericCrafter crafter = (GenericCrafter)building.block;
                if(crafter.outputItems == null || !Structs.contains(crafter.outputItems, s -> s.item == item)) continue;
                boolean complete = true;
                for(Consume consume : crafter.consumers){
                    if(consume.optional) continue;
                    if(consume instanceof ConsumeLiquid || consume instanceof ConsumeLiquids){ complete = false; break; }
                    if(consume instanceof ConsumeItems){
                        for(ItemStack input : ((ConsumeItems)consume).items){
                            if(!hasProduction(input.item, visiting, depth + 1)){ complete = false; break; }
                        }
                    }
                    if(!complete) break;
                }
                if(complete) return true;
            }
            return false;
        }finally{ visiting.remove(item); }
    }

    Seq<Item> inputs(Block block, Building building){
        Seq<Item> result = new Seq<>();
        if(block instanceof ItemTurret){
            Item ammo = defense.ammo((ItemTurret)block);
            if(ammo != null) result.add(ammo);
            return result;
        }
        for(Consume consume : block.consumers){
            if(consume.optional) continue;
            ItemStack[] stacks = consume instanceof ConsumeItems ? ((ConsumeItems)consume).items :
                consume instanceof ConsumeItemDynamic && building != null ? ((ConsumeItemDynamic)consume).items.get(building) : null;
            if(stacks != null) for(ItemStack input : stacks) if(!result.contains(input.item, true)) result.add(input.item);
            if(consume instanceof ConsumeItemFilter && block == Blocks.combustionGenerator) result.add(Items.coal);
        }
        if(block instanceof UnitFactory && building == null){
            UnitFactory factory = (UnitFactory)block;
            int index = combatPlan(factory);
            if(index >= 0) for(ItemStack input : factory.plans.get(index).requirements) if(!result.contains(input.item, true)) result.add(input.item);
        }
        return result;
    }

    int combatPlan(UnitFactory factory){
        return factory.plans.indexOf(p -> !p.unit.isBanned() && p.unit.envEnabled != 0 &&
            p.unit != UnitTypes.mono && p.unit != UnitTypes.poly);
    }

    private boolean prepareInputs(){
        for(Building building : view.buildings){
            if(!economy.manages(building)) continue;
            if(building instanceof UnitFactoryBuild && ((UnitFactoryBuild)building).currentPlan < 0){
                int index = combatPlan((UnitFactory)building.block);
                if(index >= 0) building.configure(index);
            }
            for(Item item : inputs(building.block, building)){
                if(!hasProduction(item) && ensureSupply(item, 0) == Supply.working) return true;
            }
        }
        return false;
    }

    GenericCrafter producer(Item item){
        //Prefer simple recipes: no liquids or environmental/heat requirements in bootstrap.
        GenericCrafter best = null;
        float cost = Float.MAX_VALUE;
        for(Block block : Vars.content.blocks()){
            if(!(block instanceof GenericCrafter) || !construction.allowed(block)) continue;
            GenericCrafter crafter = (GenericCrafter)block;
            if(crafter.outputItems == null) continue;
            boolean output = false, liquid = false;
            for(ItemStack stack : crafter.outputItems) if(stack.item == item) output = true;
            for(Consume consume : block.consumers){
                if(!consume.optional && (consume instanceof ConsumeLiquid || consume instanceof ConsumeLiquids)) liquid = true;
            }
            //Vanilla content uses anonymous subclasses for plain GenericCrafters.
            boolean plain = block.getClass() == GenericCrafter.class || block.getClass().getSuperclass() == GenericCrafter.class;
            if(!output || liquid || !plain) continue;
            float sum = 0;
            for(ItemStack req : block.requirements) sum += req.amount;
            if(sum < cost){ best = crafter; cost = sum; }
        }
        return best;
    }

    boolean build(Block block, boolean front){ return build(block, front, 0); }

    /** Buy new basic defenses as a complete, route-checked mining and ammunition project. */
    private boolean buildDefense(ItemTurret turret){
        Item item = defense.ammo(turret);
        //A mine can dump straight into an adjacent turret while its existing core belts stay intact.
        //This also works when a small ore patch has no room for another drill.
        for(Building source : view.buildings){
            if(!(source instanceof Drill.DrillBuild) || construction.outputItem(source) != item || !source.within(core(),200f)) continue;
            int reach = source.block.size + turret.size;
            for(int dx = -reach; dx <= reach; dx++) for(int dy = -reach; dy <= reach; dy++){
                int x = source.tileX() + dx, y = source.tileY() + dy;
                if(!construction.place(turret,x,y,0)) continue;
                boolean adjacent = false;
                int offset = -(turret.size-1)/2;
                for(arc.math.geom.Point2 edge : Edges.getEdges(source.block.size)){
                    int ex=source.tileX()+edge.x, ey=source.tileY()+edge.y;
                    if(ex>=x+offset && ex<x+offset+turret.size && ey>=y+offset && ey<y+offset+turret.size){ adjacent=true; break; }
                }
                if(!adjacent) continue;
                boolean coreMine = construction.feeds(source,core());
                if(submit(Seq.with(new BuildPlan(x,y,0,turret)),"建设矿机直供炮塔："+turret.localizedName)){
                    if(coreMine) Vars.state.rules.tags.put("fair-ai.core-mine."+team.id+"."+source.pos(),"true");
                    return true;
                }
            }
        }
        Drill drill = (Drill)(core().items.get(Items.graphite) >= 12 &&
            core().items.has(Blocks.pneumaticDrill.requirements, Vars.state.rules.buildCostMultiplier) ? Blocks.pneumaticDrill : Blocks.mechanicalDrill);
        Seq<Tile> deposits = new Seq<>();
        for(Tile tile : Vars.world.tiles) if(tile.drop() == item && view.known(tile.x, tile.y) && core().dst(tile) < 240f &&
            view.danger(tile.worldx(), tile.worldy()) < 0.5f && construction.place(drill,tile.x,tile.y,0)){
            int offset = -(drill.size - 1) / 2;
            boolean mixed = false;
            for(int dx = 0; dx < drill.size; dx++) for(int dy = 0; dy < drill.size; dy++){
                Item other = Vars.world.tile(tile.x + offset + dx,tile.y + offset + dy).drop();
                if(other != null && other != item && other != Items.sand) mixed = true;
            }
            if(!mixed) deposits.add(tile);
        }
        deposits.sort(tile -> core().dst(tile) - oreTiles(drill,tile,item) * 50f);
        Teamc enemy = view.enemies.min(e -> core().dst2(e));
        float tx = enemy == null ? Vars.world.unitWidth()/2f : enemy.getX();
        float ty = enemy == null ? Vars.world.unitHeight()/2f : enemy.getY();
        BuildPlan preferred = construction.site(turret,core(),tx,ty,true);
        for(int i = 0; i < Math.min(12,deposits.size); i++){
            Tile tile = deposits.get(i);
            Building mine = construction.proposed(drill,tile.x,tile.y);
            ((Drill.DrillBuild)mine).dominantItem = item;
            Seq<BuildPlan> sites = new Seq<>();
            if(preferred != null) sites.add(preferred);
            int reach = drill.size + turret.size;
            for(int dx = -reach; dx <= reach; dx++) for(int dy = -reach; dy <= reach; dy++){
                int x = tile.x + dx, y = tile.y + dy;
                if(construction.place(turret,x,y,0)) sites.add(new BuildPlan(x,y,0,turret));
            }
            sites.sort(p -> Mathf.dst(p.x*8f,p.y*8f,tx,ty) + Math.abs(core().dst(p.x*8f,p.y*8f)-100f)*2f);
            for(int s = 0; s < Math.min(16,sites.size); s++){
                BuildPlan site = sites.get(s);
                Building target = construction.proposed(turret,site.x,site.y);
                if(construction.overlaps(mine,target)) continue;
                Seq<BuildPlan> route = construction.supplyRoute(mine,target);
                if(route == null) continue;
                Seq<BuildPlan> plans = Seq.with(site);
                plans.addAll(route); plans.add(new BuildPlan(tile.x,tile.y,0,drill));
                for(ObjectIntMap.Entry<Item> cost : construction.costs(plans)) if(core().items.get(cost.key) < cost.value){
                    return ensureItem(cost.key,cost.value,0);
                }
                if(submit(plans,"建设炮塔、专用矿机及自动供弹线："+turret.localizedName)){
                    defense.reserve(tile.pos(),target); construction.recordFlow(plans,item); return true;
                }
            }
        }
        defense.issue = "找不到炮塔与弹药矿机可同时施工并连通的位置，暂缓新增炮塔";
        return false;
    }

    /** Ammunition factories need an output exit at the defense, not just a cheap site by coal. */
    boolean buildAmmoFactory(GenericCrafter block, Building target){
        defense.issue = "检查弹药厂选址";
        for(Item item : inputs(block, null)) if(ensureSupply(item, 0) == Supply.working) return true;
        for(ItemStack req : block.requirements){
            int needed = Math.round(req.amount * Vars.state.rules.buildCostMultiplier);
            if(core().items.get(req.item) < needed){ defense.issue = "弹药厂等待材料：" + req.item.name; return ensureItem(req.item, needed, 0); }
        }
        int reach = block.size + target.block.size;
        for(int dx = -reach; dx <= reach; dx++) for(int dy = -reach; dy <= reach; dy++){
            int x = target.tileX() + dx, y = target.tileY() + dy;
            if(!construction.place(block, x, y, 0)) continue;
            int offset = -(block.size - 1) / 2;
            boolean adjacent = false;
            for(arc.math.geom.Point2 edge : Edges.getEdges(target.block.size)){
                if(target.tileX() + edge.x >= x + offset && target.tileX() + edge.x < x + offset + block.size &&
                    target.tileY() + edge.y >= y + offset && target.tileY() + edge.y < y + offset + block.size){ adjacent = true; break; }
            }
            if(adjacent && submit(Seq.with(new BuildPlan(x, y, 0, block)), "建设自动弹药工厂：" + block.localizedName)){
                defense.reserve(arc.math.geom.Point2.pack(x, y), target); return true;
            }
        }
        int tried = 0;
        for(int radius = 3; radius <= 14; radius++) for(int dx = -radius; dx <= radius; dx++) for(int dy = -radius; dy <= radius; dy++){
            if(Math.abs(dx) != radius && Math.abs(dy) != radius) continue;
            int x = target.tileX() + dx, y = target.tileY() + dy;
            if(!construction.place(block, x, y, 0)) continue;
            if(++tried > 24){ defense.issue = "弹药厂缺少可用空间或可负担的输出通道"; return false; }
            Seq<BuildPlan> plans = construction.factorySupplyPlan(block, x, y, target);
            if(plans != null && submit(plans, "建设自动弹药厂及输出线：" + block.localizedName)){
                defense.reserve(arc.math.geom.Point2.pack(x, y), target);
                construction.recordFlow(plans, block.outputItems[0].item); return true;
            }
        }
        defense.issue = "弹药厂缺少可用空间或可负担的输出通道";
        return false;
    }

    private boolean build(Block block, boolean front, int depth){
        if(depth > 6 || !construction.allowed(block)) return false;
        if(!front || block instanceof ItemTurret){
            for(Item item : inputs(block, null)){
                Supply supply = ensureSupply(item, depth + 1);
                if(supply == Supply.working) return true;
                if(supply != Supply.ready) return false;
            }
        }
        for(ItemStack req : block.requirements){
            int needed = Math.round(req.amount * Vars.state.rules.buildCostMultiplier);
            demands.put(req.item, Math.max(demands.get(req.item, 0), needed + 30));
            if(core().items.get(req.item) < needed && ensureItem(req.item, needed + 20, depth + 1)) return true;
            if(core().items.get(req.item) < needed) return false;
        }
        if(block instanceof ItemTurret && (defense.ammo((ItemTurret)block) == Items.copper || defense.ammo((ItemTurret)block) == Items.lead)){
            return buildDefense((ItemTurret)block);
        }
        if(blueprints != null){
            BlueprintLibrary.Placement selected = blueprints.choose(block, this);
            if(block instanceof GenericCrafter) selected = blueprints.nearInput(selected,raw.site(block),block,this);
            if(selected != null && submit(selected.plans, "按蓝图建设 " + block.localizedName)){
                lastBlueprint = selected.description();
                blueprintProjects++;
                blueprints.acceptedProjects++;
                return true;
            }
        }
        float tx = core().x, ty = core().y;
        Teamc target = view.enemies.min(e -> core().dst2(e));
        if(target != null){ tx = target.getX(); ty = target.getY(); }
        else { tx = Vars.world.width() * 4f; ty = Vars.world.height() * 4f; }
        BuildPlan site = raw.site(block);
        if(site == null && block == Blocks.combustionGenerator){
            //Continuous fuel is more valuable than a short walk from the core.
            for(Building b : view.buildings){
                if(!(b instanceof Drill.DrillBuild) || ((Drill.DrillBuild)b).dominantItem != Items.coal) continue;
                for(arc.math.geom.Point2 edge : Edges.getEdges(b.block.size)){
                    if(construction.place(block, b.tileX() + edge.x, b.tileY() + edge.y, 0)){
                        site = new BuildPlan(b.tileX() + edge.x, b.tileY() + edge.y, 0, block);
                        break;
                    }
                }
                if(site != null) break;
            }
        }
        if(site == null) site = construction.site(block, core(), tx, ty, front);
        if(site == null) return false;
        return submit(Seq.with(site), "建设 " + block.localizedName);
    }

    public boolean submit(Seq<BuildPlan> plans, String description){
        if(project.any() || plans == null) return false;
        Seq<BuildPlan> pending = plans.select(p -> !Construction.complete(p));
        if(pending.isEmpty() || !construction.affordable(pending, core())) return false;
        project.addAll(pending);
        //A queued optional pickup must not delay the infrastructure that replaces it.
        if(actor.unit().stack.amount == 0){ source = destination = null; cargo = null; }
        mineTile = null;
        lastProgress = clock;
        progressSignature = -1;
        status = description;
        return true;
    }

    private int drillCount(Item item){
        return view.buildings.count(b -> b instanceof Drill.DrillBuild && ((Drill.DrillBuild)b).dominantItem == item);
    }

    private boolean buildDrill(Item item){ return buildDrill(item, null); }

    boolean buildDrill(Item item, Building toward){ return buildDrill(item, toward, false); }

    boolean buildDrill(Item item, Building toward, boolean local){
        Block drill = item.hardness <= 2 ? Blocks.mechanicalDrill : item.hardness <= 3 ? Blocks.pneumaticDrill : Blocks.laserDrill;
        if(item.hardness <= 2 && core().items.get(Items.graphite) >= 12 &&
            core().items.has(Blocks.pneumaticDrill.requirements, Vars.state.rules.buildCostMultiplier)) drill = Blocks.pneumaticDrill;
        if(!construction.allowed(drill)) return false;
        final Drill chosen = (Drill)drill;
        Seq<Tile> candidates = new Seq<>();
        for(Tile tile : Vars.world.tiles){
            if(tile.drop() == item && view.known(tile.x, tile.y) && core().dst(tile) < 700f &&
                !failedSites.contains(tile.pos()) && construction.place(drill, tile.x, tile.y, 0)){
                candidates.add(tile);
            }
        }
        candidates.sort(t -> {
            int count = oreTiles(chosen, t, item);
            float rate = 3600f * count / chosen.getDrillTime(item);
            return (toward == null ? core().dst(t) : toward.dst(t)) * 0.35f - rate * 35f +
                view.danger(t.worldx(), t.worldy()) * 10000f;
        });
        Seq<BuildPlan> bootstrap = null;
        int bootstrapCost = Integer.MAX_VALUE;
        for(int i = 0; i < Math.min(8, candidates.size); i++){
            Tile tile = candidates.get(i);
            //Do not cover a mixed vein that would make this drill output another ore.
            int offset = -(drill.size - 1) / 2;
            boolean mixed = false;
            for(int dx = 0; dx < drill.size; dx++) for(int dy = 0; dy < drill.size; dy++){
                Item other = Vars.world.tile(tile.x + offset + dx, tile.y + offset + dy).drop();
                if(other != null && other != item && other != Items.sand) mixed = true;
            }
            if(mixed) continue;
            BuildPlan mine = new BuildPlan(tile.x, tile.y, 0, drill);
            Seq<BuildPlan> route = RawLogistics.processingInput(item) || local ? Seq.with(mine) : construction.drillRoute(mine, core());
            if(route == null) continue;
            if(submit(route, "开采 " + item.localizedName + (RawLogistics.processingInput(item) ? "，保留原料供加工" : " 并铺设运输线"))){
                if(local && toward != null) defense.reserve(tile.pos(), toward);
                construction.recordFlow(route, item); return true;
            }
            int missing = 0;
            for(ObjectIntMap.Entry<Item> cost : construction.costs(route)) missing += Math.max(0, cost.value - core().items.get(cost.key));
            if(missing < bootstrapCost){ bootstrap = route; bootstrapCost = missing; }
        }
        if(bootstrap != null){
            for(ObjectIntMap.Entry<Item> cost : construction.costs(bootstrap)){
                if(core().items.get(cost.key) >= cost.value) continue;
                //Manual mining is a bootstrap job to pay for a real line, not its replacement.
                if(emergencyMine(cost.key, cost.value)) return true;
                if(!supplyStack.contains(cost.key) && ensureItem(cost.key, cost.value + 10, 1)) return true;
            }
        }
        return false;
    }

    /** Working paid mining lines replace the core ship, even when stock is temporarily low. */
    boolean hasCoreIncome(Item item){
        return view.buildings.contains(b -> b.enabled && construction.outputItem(b) == item && construction.feeds(b, core()));
    }

    private boolean emergencyMine(Item item, int amount){
        Unit unit = actor.unit();
        if(RawLogistics.processingInput(item) || !unit.canMine(item) || hasCoreIncome(item) || core().items.get(item) >= amount) return false;
        Tile ore = view.ore(item, unit);
        if(ore == null) return false;
        demands.put(item, Math.max(demands.get(item, 0), amount));
        if(unit.stack.amount == 0){ source = destination = null; cargo = null; }
        mineItem = item; mineTile = ore;
        status = "应急采集启动材料：" + item.localizedName;
        return true;
    }

    private boolean repairMiningReturns(){
        for(Building b : view.buildings){
            if(!(b instanceof Drill.DrillBuild) || defense.assigned(b) &&
                !"true".equals(Vars.state.rules.tags.get("fair-ai.core-mine."+team.id+"."+b.pos()))) continue;
            Item item = construction.outputItem(b);
            if(item == null || RawLogistics.processingInput(item) || construction.feeds(b, core())) continue;
            //A newly placed ammunition mine must be connected to its turret first.
            Seq<BuildPlan> route = construction.miningReturn(b, core());
            if(route != null && submit(route, "修复矿业自动入库：" + item.localizedName)){
                construction.recordFlow(route, item); return true;
            }
        }
        return false;
    }

    private boolean expandCoreMining(){
        for(Item item : new Item[]{Items.copper, Items.lead}){
            float rate = 0;
            for(Building b : view.buildings) if(b instanceof Drill.DrillBuild && construction.outputItem(b) == item && construction.feeds(b, core())){
                rate += economy.drillRate((Drill.DrillBuild)b);
            }
            if(rate < (item == Items.copper ? 90f : 60f) && buildDrill(item)) return true;
        }
        return false;
    }

    int oreTiles(Drill drill, Tile tile, Item item){
        int count = 0, offset = -(drill.size - 1) / 2;
        for(int dx = 0; dx < drill.size; dx++) for(int dy = 0; dy < drill.size; dy++){
            Tile other = Vars.world.tile(tile.x + offset + dx, tile.y + offset + dy);
            if(other != null && other.drop() == item && item.hardness <= drill.tier && item != drill.blockedItem) count++;
        }
        return count;
    }

    private boolean repairPower(){
        for(Building b : view.buildings){
            if(b.power == null || !b.enabled || !b.block.consumesPower || b.block instanceof PowerNode || b.power.status > 0.9f) continue;
            boolean fueled = false;
            for(Building generator : view.buildings){
                if(generator.block.outputsPower && generator.power != null && generator.power.graph == b.power.graph){ fueled = true; break; }
            }
            if(fueled){
                if(supplyInputs()) return true;
                //Capacity and fuel are planned together by EconomyPlanner; avoid reactive solar spam.
                continue;
            }
            Building generator = view.buildings.min(g -> g.power != null && g.block.outputsPower, g -> b.dst2(g));
            if(generator == null) return view.count(Blocks.combustionGenerator) < 8 && build(Blocks.combustionGenerator, false);
            //Link through real, paid-for power nodes. A node's normal placed() autolink is used.
            Building anchor = view.buildings.min(g -> g.power != null && g.power.graph == generator.power.graph, g -> b.dst2(g));
            if(anchor == null) continue;
            float dx = b.x - anchor.x, dy = b.y - anchor.y, distance = Mathf.len(dx, dy);
            float step = Math.min(distance / 2f, 35f);
            int x = Math.round((anchor.x + dx / Math.max(distance, 1f) * step) / 8f);
            int y = Math.round((anchor.y + dy / Math.max(distance, 1f) * step) / 8f);
            for(int r = 0; r <= 3; r++) for(int ox = -r; ox <= r; ox++) for(int oy = -r; oy <= r; oy++){
                if(construction.place(Blocks.powerNode, x + ox, y + oy, 0)){
                    if(core().items.has(Blocks.powerNode.requirements, Vars.state.rules.buildCostMultiplier)){
                        return submit(Seq.with(new BuildPlan(x + ox, y + oy, 0, Blocks.powerNode)), "连接电网");
                    }
                }
            }
        }
        return false;
    }

    private boolean rebuildDestroyed(){
        for(Building factory : view.buildings){
            if(!(factory.block instanceof UnitFactory) || factory.front() == null || !factory.front().tile.solid()) continue;
            for(int rotation = 0; rotation < 4; rotation++){
                int step = factory.block.size / 2 + 1;
                Tile exit = Vars.world.tile(factory.tileX() + arc.math.geom.Geometry.d4x[rotation] * step,
                    factory.tileY() + arc.math.geom.Geometry.d4y[rotation] * step);
                if(exit != null && !exit.solid() && !exit.floor().isLiquid){
                    return submit(Seq.with(new BuildPlan(factory.tileX(), factory.tileY(), rotation, factory.block)), "疏通工厂出兵口");
                }
            }
        }
        for(Building unfinished : view.buildings){
            if(unfinished instanceof ConstructBuild){
                ConstructBuild site = (ConstructBuild)unfinished;
                if(site.current != Blocks.air && !failedSites.contains(site.pos()) && construction.allowed(site.current)){
                    if(submit(Seq.with(new BuildPlan(site.tileX(), site.tileY(), site.rotation, site.current, site.lastConfig)), "继续未完成的施工")) return true;
                }
            }
        }
        for(Teams.BlockPlan old : team.data().plans){
            Block block = Vars.content.block(old.block);
            if(block == null || block == Blocks.air || failedSites.contains(arc.math.geom.Point2.pack(old.x, old.y))) continue;
            if(construction.place(block, old.x, old.y, old.rotation) && core().items.has(block.requirements, Vars.state.rules.buildCostMultiplier)){
                return submit(Seq.with(new BuildPlan(old.x, old.y, old.rotation, block, old.config)), "修复被摧毁的建筑");
            }
        }
        return false;
    }

    private boolean collectOutput(Item item){
        if(RawLogistics.processingInput(item)) return false;
        if(source != null || destination != null) return cargo == item && destination == core();
        for(int offset = 0; offset < view.buildings.size; offset++){
            int index = (supplyCursor + offset) % view.buildings.size;
            Building b = view.buildings.get(index);
            if(b.items == null || b instanceof CoreBuild || b.items.get(item) < 5) continue;
            if(construction.feeds(b, core())) continue;
            if(defense.assigned(b) || defense.needs(item) && !construction.feeds(b, core())) continue;
            boolean output = b.block instanceof Drill;
            if(b.block instanceof GenericCrafter){
                ItemStack[] outputs = ((GenericCrafter)b.block).outputItems;
                if(outputs != null) for(ItemStack stack : outputs) if(stack.item == item) output = true;
            }
            if(output){ return haul(b, core(), item, Math.min(b.items.get(item), actor.unit().type.itemCapacity)); }
        }
        return false;
    }

    boolean deliver(Building target, Item item, int amount){
        if(source != null || destination != null) return true;
        if(Vars.state.rules.onlyDepositCore || target.items == null) return false;
        if(target instanceof UnitFactoryBuild && economy.manages(target) && !economy.militaryAllowed(target)) return false;
        if(view.buildings.contains(b -> construction.outputItem(b) == item && construction.feeds(b, target))) return false;
        if(target.acceptStack(item, amount, actor.unit()) < 1) return false;
        //Prefer actual producer output over drawing down the shared construction budget.
        Building output = view.buildings.min(b -> b != target && b.items != null && b.items.get(item) >= Math.min(amount, 5) &&
            (b instanceof Drill.DrillBuild && ((Drill.DrillBuild)b).dominantItem == item ||
             b.block instanceof GenericCrafter && ((GenericCrafter)b.block).outputItems != null &&
             Structs.contains(((GenericCrafter)b.block).outputItems, s -> s.item == item)), b -> b.dst2(target));
        if(output != null) return haul(output, target, item, Math.min(output.items.get(item), amount));
        boolean emergency = target.block instanceof ItemTurret && threatNearCore();
        if(!emergency && !hasProduction(item)) return false;
        int reserve = emergency ? 0 : economy.reserve(item);
        int available = core().items.get(item) - reserve;
        if(available < 1 || target.acceptStack(item, amount, actor.unit()) < 1) return false;
        return haul(core(), target, item, Math.min(available, amount));
    }

    private boolean haul(Building from, Building to, Item item, int amount){
        if(amount < 1 || RawLogistics.processingInput(item) && to instanceof CoreBuild) return false;
        source = from; destination = to; cargo = item; cargoAmount = amount;
        cargoStart = clock;
        mineTile = null;
        return true;
    }

    private boolean supplyInputs(){
        if(source != null || destination != null) return true;
        for(int offset = 0; offset < view.buildings.size; offset++){
            int index = (supplyCursor + offset) % view.buildings.size;
            Building b = view.buildings.get(index);
            if(b.items == null || b instanceof CoreBuild) continue;
            if(b.block instanceof ItemTurret) continue; //Normal ammunition is entirely automated.
            if(!economy.manages(b)) continue;
            if(b instanceof UnitFactoryBuild){
                UnitFactory factory = (UnitFactory)b.block;
                UnitFactoryBuild build = (UnitFactoryBuild)b;
                if(build.currentPlan < 0 && factory.plans.any()){
                    int planIndex = combatPlan(factory);
                    if(planIndex >= 0) b.configure(planIndex);
                }
            }
            for(Consume consume : b.block.consumers){
                if(consume.optional) continue;
                ItemStack[] inputs = consume instanceof ConsumeItems ? ((ConsumeItems)consume).items :
                    consume instanceof ConsumeItemDynamic ? ((ConsumeItemDynamic)consume).items.get(b) : null;
                if(inputs != null){
                    for(ItemStack input : inputs){
                        int target = Math.min(b.getMaximumAccepted(input.item), Math.max(10, input.amount * 2));
                        if(b.items.get(input.item) < target && deliver(b, input.item, target - b.items.get(input.item))){
                            supplyCursor = (index + 1) % view.buildings.size;
                            return true;
                        }
                    }
                }else if(consume instanceof ConsumeItemFilter){
                    ConsumeItemFilter filter = (ConsumeItemFilter)consume;
                    for(Item item : Vars.content.items()){
                        if(filter.filter.get(item) && b.items.get(item) < 10 && deliver(b, item, 10 - b.items.get(item))){
                            supplyCursor = (index + 1) % view.buildings.size;
                            return true;
                        }
                    }
                }
            }
            if(b.block instanceof ItemTurret){
                ItemTurret turret = (ItemTurret)b.block;
                for(Item ammo : turret.ammoTypes.keys()){
                    if(b.acceptStack(ammo, 10, actor.unit()) > 0 && deliver(b, ammo, 10)){
                        supplyCursor = (index + 1) % view.buildings.size;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void work(Unit unit){
        unit.mineTile = null;
        if(RawLogistics.processingInput(mineItem) || mineItem != null && hasCoreIncome(mineItem)) mineTile = null;
        if(threatNearWorker(unit)){
            unit.clearBuilding();
            driver.go(core(), 50f);
            status = "建造者撤回核心";
            return;
        }
        boolean rawCargo = unit.stack.amount > 0 && RawLogistics.processingInput(unit.item());
        if(rawCargo && (destination == null || !destination.isValid() || destination instanceof CoreBuild ||
            destination.acceptStack(unit.item(), 1, unit) == 0)){
            destination = raw.consumer(unit.item(), unit);
            source = null;
        }
        if(unit.stack.amount > 0 && (!rawCargo || destination != null)){
            if(destination == null && project.isEmpty() && mineTile != null && unit.item() == mineItem &&
                unit.stack.amount < unit.type.itemCapacity && core().items.get(mineItem) + unit.stack.amount < demands.get(mineItem, 150) &&
                unit.validMine(mineTile, false)){
                driver.go(mineTile, unit.type.mineRange * 0.6f);
                if(unit.within(mineTile, unit.type.mineRange)){ unit.mineTile = mineTile; emergencyMiningTicks += arc.util.Time.delta; }
                return;
            }
            unit.clearBuilding();
            Building target = destination != null && destination.isValid() ? destination : core();
            if(Vars.state.rules.onlyDepositCore){
                if(rawCargo){ destination = null; return; }
                target = core();
            }
            if(target.acceptStack(unit.item(), unit.stack.amount, unit) == 0){
                if(rawCargo){ destination = null; return; }
                target = core();
            }
            driver.go(target, Vars.itemTransferRange * 0.7f);
            if(unit.within(target, Vars.itemTransferRange) && clock >= nextTransfer){
                actor.set(unit);
                Item deliveredItem = unit.item();
                int carriedBefore = unit.stack.amount;
                InputHandler.transferInventory(actor, target);
                int delivered = carriedBefore - unit.stack.amount;
                if(delivered > 0 && !(target instanceof CoreBuild)){
                    audit("送入 " + deliveredItem.localizedName + " ×" + delivered + " → " + target.block.localizedName +
                        " (" + target.tileX() + "," + target.tileY() + ")");
                }
                nextTransfer = clock + 30f;
                if(unit.stack.amount == 0){ source = destination = null; cargo = null; }
            }
            return;
        }
        if(source != null){
            unit.clearBuilding();
            if(!source.isValid() || destination == null || !destination.isValid() ||
                RawLogistics.processingInput(cargo) && destination instanceof CoreBuild || clock - cargoStart > 60f * 45f){
                source = destination = null; cargo = null;
            }else{
                driver.go(source, Vars.itemTransferRange * 0.7f);
                if(unit.within(source, Vars.itemTransferRange) && clock >= nextTransfer){
                    actor.set(unit);
                    int carriedBefore = unit.stack.amount;
                    InputHandler.requestItem(actor, source, cargo, cargoAmount);
                    int taken = unit.stack.amount - carriedBefore;
                    if(taken > 0){
                        audit("从" + (source instanceof CoreBuild ? "核心" : source.block.localizedName) + "取出 " +
                            cargo.localizedName + " ×" + taken);
                    }
                    nextTransfer = clock + 30f;
                    source = null;
                    if(unit.stack.amount == 0){ destination = null; cargo = null; }
                }
                return;
            }
        }
        while(project.any() && Construction.complete(project.first())){
            BuildPlan done = project.remove(0);
            if(done.config != null && done.tile().build != null) done.tile().build.configure(done.config);
            if(project.isEmpty()){ finishedProjects++; view.refresh(); nextThink = clock; }
        }
        if(project.any()){
            BuildPlan plan = project.first();
            Tile tile = plan.tile();
            if(tile == null || (!plan.breaking && tile.block() != Blocks.air && !(tile.build instanceof ConstructBuild) && !Construction.complete(plan) && !plan.isRotation(team) && !construction.replacement(plan))){
                cancelProject(plan); return;
            }
            int signature = project.size * 10000 + (tile.build instanceof ConstructBuild ? (int)(((ConstructBuild)tile.build).progress * 1000f) : 0);
            if(signature != progressSignature){ progressSignature = signature; lastProgress = clock; }
            if(clock - lastProgress > 60f * 30f){ cancelProject(plan); return; }
            driver.go(tile, Math.max(8f, unit.type.buildRange - 16f));
            if(unit.buildPlan() == null || !unit.buildPlan().samePos(plan)){
                unit.clearBuilding();
                unit.addBuild(plan, false);
            }
            unit.updateBuilding = true;
            return;
        }
        unit.clearBuilding();
        if(rawCargo){
            status = "保留加工原料，等待可用工厂";
            driver.go(core(), 70f);
            return;
        }
        //A deliberate shortage-recovery job outranks optional top-ups and output collection.
        //Otherwise a working graphite press can keep the only builder busy forever.
        if(mineTile != null && mineItem != null && !RawLogistics.processingInput(mineItem) && unit.validMine(mineTile, false) &&
            core().items.get(mineItem) < demands.get(mineItem, 150)){
            status = "应急采集启动材料：" + mineItem.localizedName;
            driver.go(mineTile, unit.type.mineRange * 0.6f);
            if(unit.within(mineTile, unit.type.mineRange)){ unit.mineTile = mineTile; emergencyMiningTicks += arc.util.Time.delta; }
            return;
        }
        if(supplyInputs()) return;
        for(Item item : new Item[]{Items.silicon, Items.graphite, Items.metaglass, Items.titanium, Items.copper, Items.lead}){
            if(core().items.get(item) < Math.max(100, demands.get(item, 0)) && collectOutput(item)) return;
        }
        mineTile = null;
        if(Vars.state.rules.fog){
            //Scout with the same physical core unit, without revealing the whole map.
            float angle = exploreIndex * 137.5f;
            float radius = Math.min(500f, 120f + exploreIndex * 24f);
            float x = Mathf.clamp(core().x + Angles.trnsx(angle, radius), 24f, Vars.world.unitWidth() - 24f);
            float y = Mathf.clamp(core().y + Angles.trnsy(angle, radius), 24f, Vars.world.unitHeight() - 24f);
            driver.go(x, y, 12f);
            if(unit.within(x, y, 20f)) exploreIndex++;
            status = "侦察可用矿区与敌军";
        }else{
            driver.go(core(), 70f);
        }
    }

    private void cancelProject(BuildPlan plan){
        failedSites.add(arc.math.geom.Point2.pack(plan.x, plan.y));
        project.clear();
        actor.unit().clearBuilding();
        status = "施工受阻，重新规划";
        nextThink = clock;
    }

    private void audit(String entry){
        supplyLog.add("[" + (int)(clock / 60f) + "秒] " + entry);
        while(supplyLog.size > 16) supplyLog.remove(0);
    }

    private boolean threatNearWorker(Unit unit){
        return unit.healthf() < 0.45f && view.enemies.contains(e -> e instanceof Unit && unit.within(e, 160f));
    }

    private boolean threatNearCore(){ return view.enemies.contains(e -> core().within(e, 350f)); }
    private boolean hasAirThreat(){ return view.enemies.contains(e -> e instanceof Unit && ((Unit)e).isFlying() && core().within(e, 450f)); }

    private void commandArmy(){
        Seq<Unit> army = new Seq<>();
        for(Unit unit : Groups.unit){
            if(unit.team == team && unit != actor.unit() && !unit.isPlayer() && unit.type.hasWeapons() && unit.type != UnitTypes.poly && unit.type != UnitTypes.mono){
                army.add(unit);
                if(!(unit.controller() instanceof FairCommand)) unit.controller(new FairCommand(view));
            }
        }
        Teamc target = view.enemies.min(e -> core().dst2(e));
        boolean defend = target != null && core().within(target, 350f);
        for(Unit unit : army){
            FairCommand command = (FairCommand)unit.controller();
            if(target != null && (defend || army.size >= 4)){
                if(unit.healthf() < 0.25f) command.commandPosition(new arc.math.geom.Vec2(core().x, core().y));
                else command.commandTarget(target);
            }else if(army.size >= 4){
                //No enemy knowledge under fog: scout towards successive sectors.
                float angle = exploreIndex++ * 137.5f;
                command.commandPosition(new arc.math.geom.Vec2(
                    Mathf.clamp(core().x + Angles.trnsx(angle, 500f), 24f, Vars.world.unitWidth() - 24f),
                    Mathf.clamp(core().y + Angles.trnsy(angle, 500f), 24f, Vars.world.unitHeight() - 24f)));
            }else command.commandPosition(new arc.math.geom.Vec2(core().x + 80f, core().y));
        }
    }

    private static final class Worker extends AIController{
        void go(arc.math.geom.Position target, float range){ moveTo(target, range, 8f); }
        void go(float x, float y, float range){ moveTo(new arc.math.geom.Vec2(x, y), range, 8f); }
    }

    /** Overrides vanilla's AI-full-vision targeting with the AI's own visible observations. */
    static final class FairCommand extends CommandAI{
        final WorldView view;
        FairCommand(WorldView view){ this.view = view; }
        @Override public Teamc findTarget(float x, float y, float range, boolean air, boolean ground){
            Teamc best = null;
            float distance = range * range;
            for(Teamc candidate : view.enemies){
                if(candidate instanceof Unit && !((Unit)candidate).checkTarget(air, ground)) continue;
                if(candidate instanceof Building && !ground) continue;
                float next = Mathf.dst2(x, y, candidate.getX(), candidate.getY());
                if(next < distance){ distance = next; best = candidate; }
            }
            return best;
        }
        @Override public Teamc findMainTarget(float x, float y, float range, boolean air, boolean ground){
            return findTarget(x, y, range, air, ground);
        }
        @Override public void updateUnit(){
            if(attackTarget != null && !view.visible(attackTarget.tileX(), attackTarget.tileY())){
                attackTarget = null;
            }
            super.updateUnit();
        }
    }
}
