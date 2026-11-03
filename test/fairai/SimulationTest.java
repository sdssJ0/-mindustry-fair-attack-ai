package fairai;

import arc.*;
import arc.files.Fi;
import arc.mock.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.core.*;
import mindustry.core.GameState.State;
import mindustry.entities.units.BuildPlan;
import mindustry.game.*;
import mindustry.gen.*;
import mindustry.io.*;
import mindustry.maps.Map;
import mindustry.net.Net;
import mindustry.type.*;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

/** Engine-backed integration tests; all terrain and inventory grants occur only in fixtures. */
public final class SimulationTest{
    static int passed;
    static int unitsProduced;

    public static void main(String[] args) throws Exception{
        try{
            setup();
            constructionConsumesTimeAndItems();
            noMaterialsNoConstruction();
            turretNeedsRealAmmo();
            activationScopeAndRules();
            fogDoesNotRevealEnemy();
            productionBeforeConsumption();
            SchedulerTest.run();
            haulingUsesProductionAndReserves();
            RawLogisticsTest.run();
            BridgeTest.run();
            EconomyPlanningTest.run();
            PowerRoutesTest.run();
            AutomationTest.run();
            DevelopmentTest.run();
            UtilitiesTest.run();
            TertiaryTest.run();
            routeAndTransport(0);
            routeAndTransport(1);
            routeAndTransport(2);
            autonomousEconomy(0);
            autonomousEconomy(1);
            autonomousEconomy(2);
            coreBuilderRespawnsFairly();
            saveResume();
            monitorReadsRealState();
            blueprintSelectionAndConstruction();
            lifecycleThroughModEvents();
            System.out.println("PASS: " + passed + " engine-backed checks");
            System.exit(0);
        }catch(Throwable error){ error.printStackTrace(); System.exit(1); }
    }

    static void setup(){
        Core.app = new MockApplication();
        Core.graphics = new MockGraphics();
        Core.files = new MockFiles();
        Core.audio = new MockAudio();
        Core.input = new MockInput();
        Core.settings = new MockSettings();
        Core.settings.setDataDirectory(new Fi(System.getProperty("fairai.testData","test-output/data")));
        Core.bundle = I18NBundle.createEmptyBundle();
        Vars.headless = true;
        Vars.loadLocales = false;
        Vars.net = new Net(null);
        Vars.tree = new FileTree();
        Vars.init();
        Vars.content.createBaseContent();
        Vars.logic = new Logic();
        Vars.netServer = new NetServer();
        Vars.content.init();
        Events.fire(new EventType.ContentInitEvent());
        Events.on(EventType.UnitCreateEvent.class, event -> {
            if(event.unit.team == Team.crux) unitsProduced++;
        });
        Time.setDeltaProvider(() -> 1f);
        Log.useColors = false;
    }

    static void map(int kind){
        Vars.logic.reset();
        Time.clear();
        Vars.state.rules = new Rules();
        Rules rules = Vars.state.rules;
        rules.attackMode = true;
        rules.canGameOver = false;
        rules.enemyCoreBuildRadius = 40f;
        rules.loadout = ItemStack.list(Items.copper, 100);
        Vars.world.loadGenerator(112, 88, tiles -> {
            for(int x = 0; x < tiles.width; x++) for(int y = 0; y < tiles.height; y++){
                tiles.set(x, y, new Tile(x, y, Blocks.stone, Blocks.air, Blocks.air));
            }
            if(kind == 1){
                for(int y = 30; y < 70; y++) if(y < 43 || y > 45){
                    tiles.getn(84, y).setBlock(Blocks.stoneWall);
                    tiles.getn(27, y).setBlock(Blocks.stoneWall);
                }
            }
            if(kind == 2){
                for(int x = 82; x < 87; x++) for(int y = 45; y < 55; y++){
                    tiles.getn(x, y).setFloor(Blocks.deepwater.asFloor());
                    tiles.getn(111 - x, y).setFloor(Blocks.deepwater.asFloor());
                }
            }
            tiles.getn(21, 44).setBlock(Blocks.coreShard, Team.sharded);
            tiles.getn(90, 44).setBlock(Blocks.coreShard, Team.crux);
            int[][] deposits = {{77, 49}, {83, 58}, {96, 58}, {99, 35}, {77, 30}};
            Block[] overlays = {Blocks.oreCopper, Blocks.oreLead, Blocks.oreCoal, Blocks.air, Blocks.oreTitanium};
            for(int i = 0; i < deposits.length; i++){
                for(int x = 0; x < 5; x++) for(int y = 0; y < 5; y++){
                    Tile tile = tiles.getn(deposits[i][0] + x, deposits[i][1] + y);
                    if(i == 3) tile.setFloor(Blocks.sand.asFloor());
                    else tile.setOverlay(overlays[i]);
                    Tile mirror = tiles.getn(111 - tile.x, tile.y);
                    if(i == 3) mirror.setFloor(Blocks.sand.asFloor());
                    else mirror.setOverlay(overlays[i]);
                }
            }
        });
        Vars.state.map = new Map(StringMap.of("name", "Fair AI Test " + kind, "author", "Fair AI", "description", "Finite resources attack test"));
        Vars.state.set(State.playing);
    }

    static Player player(Team team){
        Player player = Player.create();
        player.team(team);
        player.add();
        team.core().requestSpawn(player);
        return player;
    }

    static void frame(TeamBrain brain){
        ((MockGraphics)Core.graphics).incrementFrameId();
        Time.update();
        Vars.state.updateId++;
        Vars.state.tick += Time.delta;
        Vars.state.teams.updateTeamStats();
        if(brain != null) brain.update();
        Groups.update();
    }

    static void frames(TeamBrain brain, int count){ for(int i = 0; i < count; i++) frame(brain); }

    static void constructionConsumesTimeAndItems(){
        map(0);
        Player p = player(Team.crux);
        CoreBuild core = Team.crux.core();
        core.items.set(Items.copper, 100);
        p.unit().addBuild(new BuildPlan(94, 44, 0, Blocks.copperWall), false);
        frame(null);
        check(Vars.world.tile(94, 44).build instanceof ConstructBuild, "Building starts as a construction site");
        check(((ConstructBuild)Vars.world.tile(94, 44).build).progress < 1f, "Construction takes time");
        frames(null, 600);
        check(Vars.world.tile(94, 44).block() == Blocks.copperWall, "Normal builder finishes wall");
        check(core.items.get(Items.copper) == 94, "Exact normal wall cost deducted");
        p.remove();
    }

    static void noMaterialsNoConstruction(){
        map(0);
        Player p = player(Team.crux);
        p.unit().addBuild(new BuildPlan(94, 44, 0, Blocks.copperWall), false);
        frames(null, 300);
        check(Vars.world.tile(94, 44).block() == Blocks.air, "No resources means no free construction");
        Team.crux.core().items.set(Items.copper, 2);
        frames(null, 300);
        check(Vars.world.tile(94, 44).build instanceof ConstructBuild, "Partial inventory creates unfinished construction");
        check(((ConstructBuild)Vars.world.tile(94, 44).build).progress < 1f, "Partial resources stall construction");
        Team.crux.core().items.add(Items.copper, 4);
        frames(null, 600);
        check(Vars.world.tile(94, 44).block() == Blocks.copperWall, "Construction resumes when normal resources arrive");
        p.remove();
    }

    static void activationScopeAndRules(){
        map(0);
        FairAiMod mod = new FairAiMod();
        Vars.state.rules.pvp = true;
        check(!mod.activate(), "PvP is excluded");
        Vars.state.rules.pvp = false;
        Vars.state.rules.planet = Planets.erekir;
        check(!mod.activate(), "Unsupported Erekir map fails explicitly");
        Vars.state.rules.planet = Planets.serpulo;
        Vars.state.rules.waves = true;
        Vars.state.rules.infiniteResources = true;
        Team.crux.rules().cheat = Team.crux.rules().infiniteResources = Team.crux.rules().buildAi = true;
        Team.crux.core().items.set(Items.surgeAlloy, 17);
        check(mod.activate(), "Custom attack mode activates");
        check(!Vars.state.rules.waves && !Vars.state.rules.infiniteResources && !Team.crux.rules().cheat &&
            !Team.crux.rules().infiniteResources && !Team.crux.rules().buildAi && !Team.crux.rules().aiCoreSpawn &&
            !Team.crux.rules().infiniteAmmo, "All free resource/spawn paths disabled");
        check(Team.crux.core().items.get(Items.surgeAlloy) == 17, "Initial inventory preserved without top-ups");
        check(Blocks.surgeSmelter.unlockedNowHost() && UnitTypes.reign.unlockedNowHost(), "Advanced technology remains unlocked");
        check(mindustry.entities.Units.getCap(Team.crux) == mindustry.entities.Units.getCap(Team.sharded), "AI uses the same normal unit cap as the player");
        for(TeamBrain brain : mod.brains) brain.dispose();
    }

    static void turretNeedsRealAmmo(){
        map(0);
        new FairAiMod().applyFairRules();
        Player p = player(Team.crux);
        CoreBuild core = Team.crux.core();
        core.items.set(Items.copper, 100);
        p.unit().addBuild(new BuildPlan(94, 44, 0, Blocks.duo), false);
        frames(null, 600);
        mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild turret =
            (mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)Vars.world.build(94, 44);
        Vars.world.tile(104, 44).setBlock(Blocks.copperWallLarge, Team.sharded);
        frames(null, 180);
        check(turret.totalAmmo == 0 && turret.totalShots == 0, "New un-fed AI turret cannot fire");
        p.set(p.unit());
        mindustry.input.InputHandler.requestItem(p, core, Items.copper, 5);
        check(core.items.get(Items.copper) == 60 && p.unit().stack.amount == 5, "Hand-carried ammo is withdrawn from real core stock");
        mindustry.input.InputHandler.transferInventory(p, turret);
        check(p.unit().stack.amount == 0 && turret.totalAmmo == 10, "Five copper become ten vanilla duo ammo units");
        frames(null, 900);
        check(turret.totalShots > 0 && turret.totalAmmo == 0 && !turret.hasAmmo(), "Fed turret consumes its ammo and runs dry");
        int shots = turret.totalShots;
        frames(null, 300);
        check(turret.totalShots == shots && core.items.get(Items.copper) == 60, "No resupply means no further shooting or free refill");
        p.remove();
    }

    static void fogDoesNotRevealEnemy(){
        map(0);
        Vars.state.rules.fog = true;
        WorldView view = new WorldView(Team.crux);
        view.refresh();
        check(!view.visible(15, 44) && view.enemies.isEmpty(), "Enemy core hidden outside actual team vision");
        check(!view.known(15, 44), "Unexplored terrain hidden");
        check(view.visible(90, 44), "Own core provides vision");
    }

    static void routeAndTransport(int kind) throws Exception{
        map(kind);
        new FairAiMod().applyFairRules();
        WorldView view = new WorldView(Team.crux);
        view.refresh();
        Construction planner = new Construction(Team.crux, view);
        BuildPlan drill = new BuildPlan(78, 50, 0, Blocks.mechanicalDrill);
        Seq<BuildPlan> plans = planner.drillRoute(drill, Team.crux.core());
        check(plans != null && plans.size > 1, "Map " + kind + " has valid terrain-dependent drill route");
        Player p = player(Team.crux);
        CoreBuild core = Team.crux.core();
        core.items.set(Items.copper, 500);
        int cost = planner.costs(plans).get(Items.copper, 0);
        for(BuildPlan plan : plans) p.unit().addBuild(plan, false);
        frames(null, 6000);
        check(Vars.world.tile(78, 50).block() == Blocks.mechanicalDrill, "Map " + kind + " drill constructed normally");
        int after = core.items.get(Items.copper);
        frames(null, 1800);
        check(core.items.get(Items.copper) > after, "Map " + kind + " conveyor actually delivers mined copper");
        check(core.items.get(Items.copper) < 500 + 200, "Map " + kind + " core was not filled by AI");
        check(cost > 12, "Route construction budget includes belts and drill");
        //Export independent fixtures, before autonomous play, for manual testing.
        map(kind);
        Vars.state.rules.canGameOver = true;
        Vars.state.rules.loadout=ItemStack.list(Items.copper,250,Items.lead,150);
        Vars.state.rules.tags.put(FairAiMod.enabledTag, "true");
        Team.sharded.core().items.set(Items.copper, 250);
        Team.sharded.core().items.set(Items.lead, 150);
        Team.crux.core().items.set(Items.copper, 250);
        Team.crux.core().items.set(Items.lead, 150);
        Vars.state.map.tags.put("name", new String[]{"公平 AI - 平原", "公平 AI - 隘口", "公平 AI - 湖岸"}[kind]);
        Vars.state.map.tags.put("rules", JsonIO.write(Vars.state.rules));
        Fi exported=new Fi("build/test-maps/fair-ai-"+kind+".msav");MapIO.writeMap(exported,Vars.state.map);
        Map playable=MapIO.createMap(exported,false);Vars.world.loadMap(playable,playable.rules());Vars.logic.play();
        check(Team.crux.core().items.get(Items.copper)==250 && Team.crux.core().items.get(Items.lead)==150 &&
            Team.sharded.core().items.get(Items.copper)==250 && Team.sharded.core().items.get(Items.lead)==150,
            "Exported map "+kind+" starts both teams with the documented finite copper and lead through actual custom-game loading");
    }

    static void productionBeforeConsumption(){
        map(0);
        new FairAiMod().applyFairRules();
        CoreBuild core = Team.crux.core();
        for(Item item : new Item[]{Items.copper, Items.lead, Items.coal, Items.sand, Items.graphite, Items.silicon}) core.items.set(item, 500);
        TeamBrain rich = new TeamBrain(Team.crux);
        frames(null, 120);
        check(!rich.hasProduction(Items.coal), "A full treasury is not mistaken for an established production source");
        frame(rich);
        check(rich.project.contains(p -> p.block instanceof mindustry.world.blocks.production.Drill) &&
            !rich.project.contains(p -> p.block instanceof mindustry.world.blocks.production.GenericCrafter),
            "Even with ample startup stock the first project is a drill and its transport line");
        check(rich.supplyLog.isEmpty(), "Startup infrastructure takes priority over optional core withdrawals");
        for(int i = 0; i < 3600 && rich.finishedProjects == 0; i++) frame(rich);
        check(rich.hasProduction(Items.copper) && rich.view.count(Blocks.graphitePress) == 0,
            "The first mining line is physically completed before downstream processing");
        rich.dispose();

        map(0);
        new FairAiMod().applyFairRules();
        core = Team.crux.core();
        core.items.set(Items.copper, 500);
        core.items.set(Items.lead, 300);
        core.items.set(Items.coal, 200);
        Vars.world.tile(78, 50).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(84, 59).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(100, 36).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(94, 44).setBlock(Blocks.graphitePress, Team.crux);
        TeamBrain inherited = new TeamBrain(Team.crux);
        frames(null, 120);
        check(!inherited.hasProduction(Items.graphite), "An inherited factory without upstream mining is not counted as a complete supply chain");
        frame(inherited);
        check(inherited.project.contains(p -> p.block instanceof mindustry.world.blocks.production.Drill &&
            p.tile().drop() == Items.coal), "An inherited graphite factory triggers construction of its missing coal mine");
        check(core.items.get(Items.coal) == 200 && inherited.supplyLog.isEmpty(), "Missing upstream production is addressed before hauling coal out of the core");
        for(int i = 0; i < 3600 && inherited.finishedProjects == 0; i++) frame(inherited);
        check(inherited.hasProduction(Items.graphite), "Completing real upstream mining makes the existing processing chain available");
        inherited.dispose();

        map(0);
        new FairAiMod().applyFairRules();
        Team.crux.core().items.clear();
        TeamBrain empty = new TeamBrain(Team.crux);
        for(int i = 0; i < 7200 && empty.finishedProjects == 0; i++) frame(empty);
        check(empty.hasProduction(Items.copper), "A zero-stock start mines real bootstrap materials and constructs a paid copper line");
        check(empty.view.count(Blocks.graphitePress) == 0, "Bootstrap mining cannot skip ahead to an unfunded factory");
        empty.dispose();

        map(0);
        new FairAiMod().applyFairRules();
        for(Tile tile : Vars.world.tiles) if(tile.overlay() == Blocks.oreCoal) tile.setOverlay(Blocks.air);
        core = Team.crux.core();
        core.items.set(Items.copper, 500);
        core.items.set(Items.lead, 300);
        core.items.set(Items.coal, 200);
        Vars.world.tile(78, 50).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(84, 59).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(100, 36).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(94, 44).setBlock(Blocks.graphitePress, Team.crux);
        TeamBrain unavailable = new TeamBrain(Team.crux);
        frames(unavailable, 240);
        check(core.items.get(Items.coal) == 200 && Vars.world.build(94, 44).items.get(Items.coal) == 0,
            "Unobtainable coal is not hidden by repeatedly feeding an incomplete factory from stock");
        unavailable.dispose();
    }

    static void haulingUsesProductionAndReserves(){
        map(0);
        new FairAiMod().applyFairRules();
        CoreBuild core = Team.crux.core();
        core.items.set(Items.copper, 100);
        core.items.set(Items.coal, 100);
        Vars.world.tile(96, 59).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(94, 44).setBlock(Blocks.graphitePress, Team.crux);
        TeamBrain brain = new TeamBrain(Team.crux);
        frames(null, 120);
        Building drill = Vars.world.build(96, 59), press = Vars.world.build(94, 44);
        drill.items.set(Items.coal, 10);
        //A paid queued project holds the planner while the already assigned haul finishes.
        brain.project.add(new BuildPlan(92, 48, 0, Blocks.copperWall));
        check(brain.deliver(press, Items.coal, 10), "Available mine output can be assigned directly to a processing factory");
        for(int i = 0; i < 900 && press.items.get(Items.coal) == 0; i++) frame(brain);
        check(press.items.get(Items.coal) > 0 && core.items.get(Items.coal) == 100,
            "The worker physically hauls producer output without withdrawing the same item from the core");
        check(brain.supplyLog.contains(s -> s.contains("mechanical-drill")) && brain.actor.unit().stack.amount == 0,
            "Producer-to-consumer delivery uses the normal inventory interfaces and leaves an audit trail");
        brain.dispose();

        map(0);
        new FairAiMod().applyFairRules();
        core = Team.crux.core();
        core.items.set(Items.copper, 100);
        core.items.set(Items.lead, 20);
        Vars.world.tile(84, 59).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(94, 44).setBlock(Blocks.scatter, Team.crux);
        TeamBrain defensive = new TeamBrain(Team.crux);
        frames(null, 120);
        Vars.world.build(84, 59).items.clear();
        Building turret = Vars.world.build(94, 44);
        check(!defensive.deliver(turret, Items.lead, 10) && core.items.get(Items.lead) == 20,
            "Routine ammunition top-ups preserve the lead construction reserve");
        Vars.world.tile(105, 44).setBlock(Blocks.copperWall, Team.sharded);
        defensive.view.refresh();
        defensive.project.add(new BuildPlan(92, 48, 0, Blocks.copperWall));
        check(defensive.deliver(turret, Items.lead, 10), "An observed threat permits emergency ammunition to use the reserve");
        mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild ammo =
            (mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)turret;
        for(int i = 0; i < 900 && ammo.totalAmmo == 0; i++) frame(defensive);
        check(core.items.get(Items.lead) == 10 && ammo.totalAmmo > 0,
            "Emergency ammunition is still paid for and physically delivered");
        defensive.dispose();
    }

    static void autonomousEconomy(int kind){
        map(kind);
        new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper, 250);
        Team.crux.core().items.set(Items.lead, 150);
        TeamBrain brain = new TeamBrain(Team.crux);
        int peakSilicon = 0;
        boolean rawEnteredCore = false;
        unitsProduced = 0;
        //Economy-first deliberately completes and upgrades industry before mobilizing.
        for(int i = 0; i < 60 * 1200; i++){
            frame(brain);
            peakSilicon = Math.max(peakSilicon, brain.core().items.get(Items.silicon));
            rawEnteredCore |= brain.core().items.get(Items.coal) != 0 || brain.core().items.get(Items.sand) != 0;
            if(i % (60 * 120) == 0){
                System.out.println("Map " + kind + " t=" + i / 60 + " status=" + brain.status +
                    " projects=" + brain.finishedProjects + " buildings=" + brain.view.buildings.size +
                    " copper=" + brain.core().items.get(Items.copper) + " graphite=" + brain.core().items.get(Items.graphite) +
                    " silicon=" + brain.core().items.get(Items.silicon) + " worker=" + brain.actor.unit().x + "," + brain.actor.unit().y +
                    " producedUnits=" + unitsProduced);
            }
        }
        brain.view.refresh();
        if(unitsProduced < 4){
            for(Building building : brain.view.buildings){
                if(building.block instanceof mindustry.world.blocks.units.UnitFactory || building.block instanceof mindustry.world.blocks.production.GenericCrafter){
                    System.out.println("Economy diagnostic: " + building.block.name + " items=" + building.items + " efficiency=" + building.efficiency);
                }
            }
            System.out.println("Supply diagnostic: graphite=" + brain.hasProduction(Items.graphite) + " silicon=" + brain.hasProduction(Items.silicon) + " coal=" + brain.hasProduction(Items.coal) + " sand=" + brain.hasProduction(Items.sand));
            System.out.println("MODEL "+brain.economy.describe());
            SaveIO.write(new Fi("test-output/autonomous-"+kind+".msav"));
            System.out.println("Treasury diagnostic: " + brain.core().items + " air site=" + brain.construction.site(Blocks.airFactory, brain.core(), 0, 0, false));
        }
        check(brain.finishedProjects >= 3, "Map " + kind + " autonomous AI builds multiple projects");
        check(brain.view.count(Blocks.graphitePress) > 0, "Map " + kind + " develops processing industry");
        check(brain.view.count(Blocks.siliconSmelter) > 0, "Map " + kind + " develops silicon industry");
        check(peakSilicon > 0, "Map " + kind + " produces and collects silicon");
        check(!rawEnteredCore, "Map " + kind + " processes coal and sand without ever depositing either in the core");
        check(brain.emergencyMiningTicks == 0, "Map " + kind + " funded autonomous economy never falls back to routine hand mining");
        check(brain.view.count(Blocks.duo)>0 && brain.view.count(Blocks.scatter)>0,
            "Map " + kind + " builds both ground and air defenses instead of avoiding defense entirely");
        check(!brain.view.buildings.contains(b -> b.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret && !b.within(brain.core(),300f)),
            "Map " + kind + " builds defenses around its own core rather than at a distant enemy-side mine");
        check(brain.view.buildings.select(b -> b.block instanceof mindustry.world.blocks.defense.turrets.ItemTurret).allMatch(b ->
            brain.defense.connected(b) && ((mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)b).totalAmmo > 0),
            "Map " + kind + " constructed defenses have complete automated ammunition chains and real ammo");
        check(unitsProduced >= 4, "Map " + kind + " manufactures a normal attack squad");
        boolean attacked = false;
        for(int i = 0; i < 60 * 180; i++){
            frame(brain);
            for(Unit u : Groups.unit){
                if(u.team == Team.crux && !u.spawnedByCore && u.type.hasWeapons() && u.dst(brain.core()) > 220f){ attacked = true; }
            }
        }
        if(!attacked) for(Unit u:Groups.unit) if(u.team==Team.crux && !u.spawnedByCore) System.out.println("Stalled unit "+u.type.name+" at "+u.x/8f+","+u.y/8f+
            " distance="+u.dst(brain.core())+" controller="+u.controller()+" devel="+brain.development.describe()+
            (u.controller() instanceof mindustry.ai.types.CommandAI ? " command="+((mindustry.ai.types.CommandAI)u.controller()).targetPos+" attack="+((mindustry.ai.types.CommandAI)u.controller()).attackTarget : ""));
        check(attacked, "Map " + kind + " squad leaves rally and advances on the enemy");
        check(Groups.player.count(p -> p.team() == Team.crux) == 1, "One core builder player per AI team");
        brain.dispose();
    }

    static void saveResume(){
        map(0);
        TeamBrain brain = new TeamBrain(Team.crux);
        frames(brain, 120);
        Unit previous = brain.actor.unit();
        check(previous.isAdded(), "AI has a normal spawned player unit");
        int id = previous.id;
        SaveIO.write(new Fi("test-output/resume.msav"));
        brain.dispose();
        SaveIO.load(new Fi("test-output/resume.msav"));
        TeamBrain restored = new TeamBrain(Team.crux);
        check(restored.actor.unit().id == id, "Saved unit identity can be reattached without duplicating core builders");
        restored.dispose();
    }

    static void coreBuilderRespawnsFairly(){
        map(0);
        new FairAiMod().applyFairRules();
        TeamBrain brain = new TeamBrain(Team.crux);
        frames(brain, 120);
        int previous = brain.actor.unit().id;
        brain.actor.unit().kill();
        frames(brain, 10);
        check(brain.actor.dead(), "Core builder does not respawn instantly after death");
        frames(brain, 100);
        check(!brain.actor.dead() && brain.actor.unit().id != previous, "Core builder uses normal delayed player respawn");
        check(Groups.unit.count(u -> u.team == Team.crux && u.spawnedByCore) == 1, "Respawning never duplicates core ships");
        brain.dispose();
    }

    static void lifecycleThroughModEvents(){
        FairAiMod mod = new FairAiMod();
        mod.init();
        Core.settings.put(FairAiMod.preference, true);
        map(0);
        check(!mod.active && Vars.state.rules.waveTeam == Team.crux, "Activation waits until map validation completes");
        Events.fire(EventType.Trigger.update);
        check(mod.active && mod.brains.size == 1, "Settings enable mode through actual event lifecycle");
        for(int i = 0; i < 120; i++){ Events.fire(EventType.Trigger.update); frame(null); }
        Team.crux.rules().cheat = true;
        Events.fire(EventType.Trigger.update);
        check(!Team.crux.rules().cheat, "Active mode removes reintroduced enemy resource privileges before simulation");
        int id = mod.brains.first().actor.unit().id;
        SaveIO.write(new Fi("test-output/lifecycle.msav"));
        SaveIO.load(new Fi("test-output/lifecycle.msav"));
        Vars.state.set(State.playing);
        Events.fire(EventType.Trigger.update);
        check(mod.active && mod.brains.first().actor.unit().id == id, "Real mod event lifecycle restores the saved bot ship");
        for(int i = 0; i < 120; i++){ Events.fire(EventType.Trigger.update); frame(null); }
        check(Groups.unit.count(u -> u.team == Team.crux && u.spawnedByCore) == 1, "Loading a live mode save never creates an extra core ship");
        Vars.logic.reset();
        Core.settings.put(FairAiMod.preference, false);
    }

    static void monitorReadsRealState(){
        map(0);
        new FairAiMod().applyFairRules();
        TeamBrain brain = new TeamBrain(Team.crux);
        FairAiMod mod = new FairAiMod();
        mod.brains.add(brain);
        mod.active = true;
        brain.core().items.set(Items.copper, 123);
        mod.monitor.update(60f);
        check(mod.monitor.snapshot(brain).amounts.get(Items.copper, -1) == 123, "Resource monitor reads actual core inventory");
        brain.core().items.add(Items.copper, 7);
        mod.monitor.update(60f);
        check(mod.monitor.snapshot(brain).netPerMinute.get(Items.copper, 0) == 420f, "Resource monitor distinguishes net stock change from production");
        Vars.world.tile(94, 44).setBlock(Blocks.duo, Team.crux);
        mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild turret =
            (mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild)Vars.world.build(94, 44);
        turret.handleItem(brain.core(), Items.copper);
        String text = mod.monitor.detailsText();
        check(text.contains(Items.copper.localizedName + ": 130") && text.contains("2/30"), "Diagnostics show both core stock and actual internal turret ammo");
        check(brain.core().items.get(Items.copper) == 130 && turret.totalAmmo == 2, "Resource observation never grants or consumes resources");
        Vars.world.tile(96, 59).setBlock(Blocks.mechanicalDrill, Team.crux);
        Vars.world.tile(94, 48).setBlock(Blocks.siliconSmelter, Team.crux);
        Vars.world.build(96, 59).items.set(Items.coal, 7);
        Vars.world.build(94, 48).items.set(Items.sand, 9);
        check(mod.monitor.processingStock(brain, Items.coal) == 7 && mod.monitor.processingStock(brain, Items.sand) == 9 &&
            brain.core().items.get(Items.coal) == 0 && brain.core().items.get(Items.sand) == 0,
            "Resource monitoring shows raw materials at producers and factories independently of the empty core");
        check(mod.monitor.detailsText().contains("加工原料缓存") && mod.monitor.compactText().contains("加工端缓存"),
            "Both resource views explain the separate processing-input buffers");
        brain.dispose();
    }

    static void blueprintSelectionAndConstruction() throws Exception{
        map(0);
        new FairAiMod().applyFairRules();
        BlueprintLibrary library = new BlueprintLibrary();
        library.load(new Fi("assets/blueprints"), null);
        check(library.entries.size == 17 && library.failures.isEmpty(), "Seventeen pinned online blueprints decode in the v146 engine");
        int filtered = 0;
        for(BlueprintLibrary.Entry entry : library.entries){
            filtered += entry.removed;
            for(mindustry.game.Schematic.Stile tile : entry.safe.tiles){
                if(BlueprintLibrary.forbidden(tile.block)) throw new AssertionError("Forbidden blueprint block " + tile.block.name);
            }
        }
        check(filtered > 0, "All test item/liquid/power sources, sinks and imported programs are filtered");
        Schematic source = library.entries.first().safe;
        int size = source.tiles.size;
        Schematic turned = BlueprintLibrary.rotateCopy(source, 3);
        check(turned.tiles.size == size && source.tiles.size == size, "Repeated rotation preserves original blueprint data and every tile");
        TeamBrain brain = new TeamBrain(Team.crux, library);
        check(library.choose(Blocks.graphitePress, brain) == null, "Blueprint placement cannot bypass an empty treasury");
        brain.core().items.set(Items.copper, 500);
        brain.core().items.set(Items.lead, 500);
        brain.core().items.set(Items.graphite, 80);
        brain.core().items.set(Items.silicon, 80);
        BlueprintLibrary.Placement first = library.choose(Blocks.graphitePress, brain);
        check(first != null && first.entry.author.equals("holmes-g"), "AI selects a real attributed community production module");
        check(first.adaptation.contains("缩减"), "AI adapts a large module into a small affordable machine cell");
        BuildPlan blocked = first.plans.first();
        Vars.world.tile(blocked.x, blocked.y).setBlock(Blocks.stoneWall);
        brain.view.refresh();
        BlueprintLibrary.Placement next = library.choose(Blocks.graphitePress, brain);
        check(next != null && !next.plans.contains(p -> p.x == blocked.x && p.y == blocked.y), "Obstacle changes cause another legal placement instead of overwriting terrain");
        Seq<BuildPlan> referencePlans = next.plans.map(p -> new BuildPlan(p.x, p.y, p.rotation, p.block, p.config));
        check(brain.submit(next.plans, next.description()), "Blueprint becomes ordinary paid build plans");
        for(int i = 0; i < 6000 && brain.project.any(); i++) frame(brain);
        brain.view.refresh();
        check(brain.project.isEmpty() && brain.view.count(Blocks.graphitePress) > 0, "A selected adapted blueprint is physically built by the normal core ship");
        int aiRemaining = brain.core().items.get(Items.copper);
        brain.dispose();
        map(0);
        new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper, 500);
        Team.crux.core().items.set(Items.lead, 500);
        Team.crux.core().items.set(Items.graphite, 80);
        Team.crux.core().items.set(Items.silicon, 80);
        Player human = player(Team.crux);
        for(BuildPlan plan : referencePlans) human.unit().addBuild(plan, false);
        frames(null, 6000);
        check(aiRemaining == Team.crux.core().items.get(Items.copper), "Blueprint material consumption matches the same normal human build queue, including v146 rounding");
        human.remove();
        map(2);
        new FairAiMod().applyFairRules();
        Team.crux.core().items.set(Items.copper, 250);
        Team.crux.core().items.set(Items.lead, 150);
        TeamBrain autonomous = new TeamBrain(Team.crux, library);
        for(int i = 0; i < 60 * 240 && autonomous.blueprintProjects == 0; i++) frame(autonomous);
        check(autonomous.blueprintProjects > 0 && autonomous.lastBlueprint.contains("holmes-g"), "Normal autonomous planning actually uses the online blueprint library");
        int completedBefore = autonomous.finishedProjects;
        for(int i = 0; i < 6000 && autonomous.finishedProjects == completedBefore; i++) frame(autonomous);
        check(autonomous.finishedProjects > completedBefore, "Autonomous blueprint project finishes on the lake map after upstream mining");
        autonomous.dispose();
    }

    static void check(boolean value, String message){
        if(!value) throw new AssertionError(message);
        passed++;
        System.out.println("PASS " + message);
    }
}
