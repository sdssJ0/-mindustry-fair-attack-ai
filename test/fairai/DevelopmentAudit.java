package fairai;

import arc.files.Fi;
import mindustry.Vars;
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
            if(Boolean.getBoolean("fairai.profile")){
                Thread main=Thread.currentThread();Thread sampler=new Thread(()->{
                    while(true)try{Thread.sleep(10000);System.out.println("PROFILE AUDIT");for(StackTraceElement f:main.getStackTrace())System.out.println(" AT "+f);}
                    catch(InterruptedException stop){return;}
                });sampler.setDaemon(true);sampler.start();
            }
            if(args[0].equals("--autonomous")){SimulationTest.autonomousEconomy(Integer.parseInt(args[1]));System.exit(0);}
            if(args[0].equals("--export-late")){
                SimulationTest.map(0); EconomyBenchmark.enrich();
                for(int x=96;x<104;x++)for(int y=20;y<28;y++){
                    Vars.world.tile(x,y).setOverlay(Blocks.oreThorium);Vars.world.tile(111-x,y).setOverlay(Blocks.oreThorium);
                }
                mindustry.Vars.state.rules.canGameOver=true;
                mindustry.Vars.state.rules.loadout=mindustry.type.ItemStack.list(Items.copper,250,Items.lead,150);
                mindustry.Vars.state.rules.tags.put(FairAiMod.enabledTag,"true");
                for(Team t:new Team[]{Team.crux,Team.sharded}){t.core().items.set(Items.copper,250);t.core().items.set(Items.lead,150);}
                mindustry.Vars.state.map.tags.put("name","公平 AI - 工业发展");
                mindustry.Vars.state.map.tags.put("description","镜像丰富矿区，双方 250 铜、150 铅；观察水与蒸汽、自动 T2/T3、激光矿业和设施更新。");
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
            SimulationTest.frames(null,2);
            if(args.length>1 && !args[1].startsWith("--")){
                int ticks=Integer.parseInt(args[1])*60;System.out.println("RESUME START ticks="+ticks);
                for(int i=0;i<ticks;i++){
                    long begin=System.nanoTime();SimulationTest.frame(brain);double ms=(System.nanoTime()-begin)/1e6;
                    if(i%3600==0 || ms>1000)System.out.println("RESUME t="+i/60+" frameMs="+ms+" projects="+brain.finishedProjects+" status="+brain.status+" T3="+brain.development.t3Upgraded()+" active="+brain.project.size);
                }
            }
            System.out.println("CORE "+brain.core().items+" armed="+brain.development.armed);
            if(args[args.length-1].equals("--utility-probe")){
                int future=WorldMarks.getInt("fair-ai.utility-future."+brain.team.id,-1);
                if(future>=0){int fx=arc.math.geom.Point2.x(future),fy=arc.math.geom.Point2.y(future);System.out.println("FUTURE FUEL "+fx+","+fy+" native="+mindustry.world.Build.validPlace(Blocks.laserDrill,brain.team,fx,fy,0)+" policy="+brain.construction.placeDevelopment(Blocks.laserDrill,fx,fy,0)+" ores="+brain.oreTiles((mindustry.world.blocks.production.Drill)Blocks.laserDrill,Vars.world.tile(fx,fy),Items.coal));
                    for(int dx=-2;dx<=2;dx++)for(int dy=-2;dy<=2;dy++){mindustry.world.Tile t=Vars.world.tile(fx+dx,fy+dy);System.out.println("FUEL CELL "+t.x+","+t.y+" "+t.block()+" reserve="+DevelopmentPlanner.reserved(brain.team,t.pos()));}
                }
                int legal=0,nine=0,privateNine=0;StringBuilder places=new StringBuilder();
                for(mindustry.world.Tile t:Vars.world.tiles)if(t.drop()==Items.coal && brain.construction.place(Blocks.laserDrill,t.x,t.y,0)){
                    legal++;int ores=brain.oreTiles((mindustry.world.blocks.production.Drill)Blocks.laserDrill,t,Items.coal);if(ores>=9)nine++;
                    boolean alone=true;for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(3)){Building b=Vars.world.build(t.x+e.x,t.y+e.y);if(b!=null && b.items!=null)alone=false;}
                    if(alone && ores>=9)privateNine++;if(ores>=5)places.append(t.x+","+t.y+":"+ores+":"+alone+" ");
                }
                System.out.println("COAL SITES legal="+legal+" nine="+nine+" private="+privateNine+" candidates="+places);
                for(Building b:brain.view.buildings)if(b instanceof mindustry.world.blocks.production.Drill.DrillBuild && ((mindustry.world.blocks.production.Drill.DrillBuild)b).dominantItem==Items.coal)System.out.println("COAL MINE "+b.tileX()+","+b.tileY()+" "+b.block);
                for(Building kiln:brain.view.buildings)if(kiln.block==Blocks.kiln)for(Building sand:brain.view.buildings)if(brain.construction.outputItem(sand)==Items.sand){
                    Seq<BuildPlan> route=brain.construction.branchRoute(sand,kiln);System.out.println("SAND ROUTE "+sand.tileX()+","+sand.tileY()+" feeds="+brain.construction.feeds(sand,kiln)+" route="+(route==null ? -1:route.size));
                }
                System.out.println("RAW PROBE "+brain.raw.plan()+" plans="+brain.project);
            }
            Seq<BuildPlan> budget=brain.development.layoutT3(Vars.state.rules.tags.getInt("fair-ai.development."+brain.team.id+".direction",0));
            System.out.println("T3 BUDGET "+brain.construction.costs(budget)+" affordable="+brain.construction.affordable(budget,brain.core()));
            for(mindustry.type.Item item:new mindustry.type.Item[]{Items.copper,Items.lead,Items.graphite,Items.silicon,Items.titanium,Items.thorium,Items.metaglass})
                System.out.println("GOAL "+item+" stock="+brain.core().items.get(item)+" reserve="+brain.economy.reserve(item)+" target="+brain.economy.stockGoal(item));
            for(Building b:brain.view.buildings)if(b.block==Blocks.laserDrill){
                mindustry.world.blocks.production.Drill.DrillBuild drill=(mindustry.world.blocks.production.Drill.DrillBuild)b;
                System.out.println("LASER "+b.tileX()+","+b.tileY()+" item="+drill.dominantItem+" enabled="+b.enabled+" eff="+b.efficiency+" power="+b.power.status+" progress="+drill.progress+" items="+b.items+" feedsCore="+brain.construction.feeds(b,brain.core()));
                if(drill.dominantItem==Items.thorium){
                    Seq<BuildPlan> repair=brain.construction.miningReturn(b,brain.core());System.out.println("THORIUM REPAIR "+(repair==null ? -1:repair.size));
                    if(repair!=null)for(BuildPlan p:repair)if(!Construction.complete(p))System.out.println("THORIUM PART "+p.x+","+p.y+" "+p.block+" r="+p.rotation+" actual="+p.tile().block()+" rotate="+p.isRotation(brain.team)+" replacement="+brain.construction.replacement(p));
                }
            }
            if(args.length>1 && args[args.length-1].equals("--power-probe"))System.out.println("POWER JOIN "+brain.economy.joinIndustrialGrid()+" plans="+brain.project);
            for(BuildPlan p:brain.development.layoutT3(Vars.state.rules.tags.getInt("fair-ai.development."+brain.team.id+".direction",0)))
                if(!Construction.complete(p) && !brain.construction.placeDevelopment(p.block,p.x,p.y,p.rotation))
                    System.out.println("T3 BLOCKED "+p.x+","+p.y+" intended="+p.block+" actual="+p.tile().block());
            System.out.println("UTILITIES count="+brain.utilities.count()+" plan="+brain.utilities.plan()+" phase="+brain.utilities.describe()+" project="+brain.project);
            for(mindustry.gen.Unit u:mindustry.gen.Groups.unit) if(u.team==Team.crux && !u.spawnedByCore) System.out.println("UNIT "+u.type.name+" "+u.x/8f+","+u.y/8f+" health="+u.health);
            for(Building b:brain.view.buildings) if(b.block instanceof GenericCrafter){
                Seq<BuildPlan> route=brain.construction.supplyRoute(b,brain.core());
                System.out.println(b.block.name+" "+b.tileX()+","+b.tileY()+" "+b.items+" enabled="+b.enabled+" power="+(b.power==null ? -1:b.power.status)+" eff="+b.efficiency+" feeds="+brain.construction.feeds(b,brain.core())+
                    " route="+(route==null ? "null":route.size)+" owned="+DevelopmentPlanner.owned(brain.team,b));
                if(b.block==Blocks.kiln)for(Building p:brain.view.buildings)if(brain.construction.outputItem(p)==Items.lead){
                    Seq<BuildPlan> input=brain.construction.branchRoute(p,b);
                    System.out.println(" KILN LEAD "+p.tileX()+","+p.tileY()+" enabled="+p.enabled+" eff="+p.efficiency+" items="+p.items+" feeds="+brain.construction.feeds(p,b)+" route="+(input==null ? "null":input.size));
                    if(brain.construction.feeds(p,b))for(Building outlet:brain.construction.outputBelts(p))System.out.println(" LEAD OUT "+outlet.tileX()+","+outlet.tileY()+" "+outlet.block+" r="+outlet.rotation+" items="+outlet.items+" link="+(outlet instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild ? ((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)outlet).link:-1));
                }
            }
            for(Building b:brain.view.buildings)if(b.block==Blocks.steamGenerator || b.block==Blocks.waterExtractor || b.block==Blocks.mechanicalPump)
                System.out.println("UTILITY "+b.block.name+" "+b.tileX()+","+b.tileY()+" items="+b.items+" water="+(b.liquids==null ? 0:b.liquids.get(Liquids.water))+" eff="+b.efficiency+" power="+(b.power==null ? -1:b.power.status)+" capacity="+(b.block==Blocks.steamGenerator ? brain.utilities.capacity(b):0));
            System.out.println("DEVELOPMENT "+brain.development.describe()+" UTILITY "+brain.utilities.describe());
            if(args.length>1 && args[args.length-1].equals("--route-probe")){
                Building target=brain.development.t3Reconstructor();
                java.lang.reflect.Method cell=Construction.class.getDeclaredMethod("bridgeCell",int.class,int.class,BuildPlan.class,Building.class,Building.class,mindustry.type.Item.class,boolean.class,int.class);cell.setAccessible(true);
                java.lang.reflect.Method inlet=Construction.class.getDeclaredMethod("receiverCell",int.class,int.class,BuildPlan.class,Building.class,Building.class,mindustry.type.Item.class,int.class);inlet.setAccessible(true);
                for(mindustry.type.Item item:new mindustry.type.Item[]{Items.silicon,Items.titanium}){
                    Building source=brain.development.inputSource(item);BuildPlan footprint=new BuildPlan(source.tileX(),source.tileY(),source.rotation,source.block);
                    arc.math.geom.Point2 port=DevelopmentPlanner.inputPort(target.tileX(),target.tileY(),target.rotation,item);
                    System.out.println("PROBE "+item+" sourceItems="+source.items+" sourceLink="+((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)source).link+" incoming="+((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)source).incoming);
                    for(int dir=0;dir<4;dir++)System.out.println("PROBE inlet dir="+dir+" ok="+inlet.invoke(brain.construction,port.x,port.y,footprint,source,target,item,dir));
                    for(arc.math.geom.Point2 e:arc.math.geom.Geometry.d4){int x=source.tileX()+e.x,y=source.tileY()+e.y;
                        java.lang.reflect.Method safe=Construction.class.getDeclaredMethod("safeStream",int.class,int.class,mindustry.type.Item.class,Building.class,Building.class);safe.setAccessible(true);
                        java.lang.reflect.Method belt=Construction.class.getDeclaredMethod("beltCell",int.class,int.class,BuildPlan.class);belt.setAccessible(true);
                        System.out.println("PROBE outlet "+x+","+y+" actual="+Vars.world.tile(x,y).block()+" flow="+DevelopmentPlanner.busItem(brain.team,arc.math.geom.Point2.pack(x,y))+" belt="+belt.invoke(brain.construction,x,y,footprint)+" safe="+safe.invoke(brain.construction,x,y,item,source,target)+" dump="+((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)source).canDump(brain.construction.proposed(Blocks.conveyor,x,y),item));
                    }
                    for(int dir=0;dir<4;dir++)for(int dist=2;dist<=4;dist++){
                        arc.math.geom.Point2 d=arc.math.geom.Geometry.d4[dir];int sx=port.x+d.x*dist,sy=port.y+d.y*dist;
                        if(!(Boolean)cell.invoke(brain.construction,sx,sy,footprint,source,target,item,false,(dir+2)%4))continue;
                        Building sender=brain.construction.proposed(Blocks.itemBridge,sx,sy);((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)sender).link=port.pack();
                        Seq<BuildPlan> approach=brain.construction.supplyRoute(source,sender);
                        System.out.println("PROBE lastSender "+sx+","+sy+" approach="+(approach==null ? -1:approach.size));
                    }
                }
            }
            for(Building b:brain.view.buildings)if(b.block instanceof mindustry.world.blocks.units.Reconstructor){
                mindustry.world.blocks.units.Reconstructor.ReconstructorBuild r=(mindustry.world.blocks.units.Reconstructor.ReconstructorBuild)b;
                System.out.println("RECON "+b.block.name+" "+b.tileX()+","+b.tileY()+" enabled="+b.enabled+" items="+b.items+" power="+b.power.status+" payload="+(r.payload==null ? "none":r.payload.unit.type.name));
                if(b.block==Blocks.multiplicativeReconstructor){
                    java.lang.reflect.Method safe=Construction.class.getDeclaredMethod("safeStream",int.class,int.class,mindustry.type.Item.class,Building.class,Building.class);safe.setAccessible(true);
                    Building si=brain.view.buildings.find(p->p.block==Blocks.siliconSmelter);
                    for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(b.block.size)){
                        mindustry.world.Tile t=Vars.world.tile(b.tileX()+e.x,b.tileY()+e.y);
                        System.out.println("T3 PORT "+t.x+","+t.y+" "+t.block()+" reserved="+DevelopmentPlanner.reserved(brain.team,t.pos())+" safeSi="+safe.invoke(brain.construction,(int)t.x,(int)t.y,Items.silicon,si,b));
                    }
                    java.lang.reflect.Method fork=Construction.class.getDeclaredMethod("safeFork",Building.class,Building.class,Building.class,mindustry.type.Item.class);fork.setAccessible(true);
                    for(Building p:brain.view.buildings)if(p.block==Blocks.siliconSmelter || p.block==Blocks.kiln){
                        int owned=0,replaceable=0,safeForks=0;
                        for(Building n:brain.construction.outputBelts(p))if(n.block==Blocks.conveyor && DevelopmentPlanner.owned(brain.team,n)){
                            owned++;if(brain.construction.replacement(new BuildPlan(n.tileX(),n.tileY(),0,Blocks.router)))replaceable++;
                            if((Boolean)fork.invoke(brain.construction,n,p,b,brain.construction.outputItem(p)))safeForks++;
                        }
                        System.out.println("FORK SOURCE "+p.block+" "+p.tileX()+","+p.tileY()+" owned="+owned+" replaceable="+replaceable+" safe="+safeForks);
                    }
                }
                for(mindustry.type.Item item:brain.inputs(b.block,b)){
                    Building depot=brain.development.inputSource(item);
                    if(depot!=null){Seq<BuildPlan> rpath=brain.construction.branchRoute(depot,b);System.out.println(" WAREHOUSE "+item.name+" source="+depot+" r="+depot.rotation+" front="+depot.front()+" feeds="+brain.construction.feeds(depot,b)+" route="+(rpath==null ? -1:rpath.size));}
                    if(depot!=null && b.block==Blocks.multiplicativeReconstructor){
                        int ex=depot.tileX()+arc.math.geom.Geometry.d4x[depot.rotation],ey=depot.tileY()+arc.math.geom.Geometry.d4y[depot.rotation];
                        System.out.println(" EGRESS "+ex+","+ey+" reserved="+DevelopmentPlanner.reserved(brain.team,arc.math.geom.Point2.pack(ex,ey))+" item="+DevelopmentPlanner.busItem(brain.team,arc.math.geom.Point2.pack(ex,ey)));
                        for(arc.math.geom.Point2 d:arc.math.geom.Geometry.d4){Building n=Vars.world.build(ex+d.x,ey+d.y);System.out.println(" EGRESS NEIGHBOUR "+n+" output="+(n==null ? null:brain.construction.outputItem(n))+" dump="+(n instanceof mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild ? ((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)n).canDump(brain.construction.proposed(Blocks.conveyor,ex,ey),item):false));}
                    }
                    for(Building p:brain.view.buildings)if(brain.construction.outputItem(p)==item){
                        Seq<BuildPlan> branch=brain.construction.branchRoute(p,b);
                        System.out.println(" INPUT "+item.name+" source="+p.block.name+" "+p.tileX()+","+p.tileY()+" feeds="+brain.construction.feeds(p,b)+" route="+(branch==null ? "null":branch.size)+" forks="+(branch==null ? 0:branch.count(q->q.block==Blocks.router)));
                    }
                }
            }
            if(args.length>1 && args[args.length-1].equals("--prospect")){
            int legal=0,routed=0;StringBuilder prospect=new StringBuilder();
            for(mindustry.world.Tile tile:mindustry.Vars.world.tiles)if(tile.drop()==Items.thorium && brain.view.known(tile.x,tile.y) && brain.construction.place(Blocks.laserDrill,tile.x,tile.y,0)){
                legal++;Seq<BuildPlan> route=brain.construction.drillRoute(new BuildPlan(tile.x,tile.y,0,Blocks.laserDrill),brain.core());
                if(route!=null){routed++;if(routed<4)prospect.append(tile.x+","+tile.y+"="+route.size+" ");}
            }
            System.out.println("THORIUM allowed="+brain.construction.allowed(Blocks.laserDrill)+" legal="+legal+" routed="+routed+" "+prospect);
            java.lang.reflect.Method collector=Construction.class.getDeclaredMethod("coreCollector",Building.class,mindustry.world.blocks.storage.CoreBlock.CoreBuild.class);collector.setAccessible(true);
            for(arc.math.geom.Point2 e:mindustry.world.Edges.getEdges(brain.core().block.size)){
                mindustry.world.Tile t=Vars.world.tile(brain.core().tileX()+e.x,brain.core().tileY()+e.y);Building b=t.build;
                System.out.println("CORE PORT "+t.x+","+t.y+" "+t.block()+" rotation="+(b==null ? -1:b.rotation)+" collector="+(b!=null && (Boolean)collector.invoke(brain.construction,b,brain.core()))+" reserved="+DevelopmentPlanner.reserved(brain.team,t.pos()));
                if(t.x==91 && t.y==46){
                    for(arc.math.geom.Point2 d:arc.math.geom.Geometry.d4){Building n=Vars.world.build(t.x+d.x,t.y+d.y);System.out.println(" NEIGHBOUR "+(t.x+d.x)+","+(t.y+d.y)+" "+n+" r="+(n==null ? -1:n.rotation)+" future="+DevelopmentPlanner.busItem(brain.team,arc.math.geom.Point2.pack(t.x+d.x,t.y+d.y)));}
                    Building fake=brain.construction.proposed(Blocks.laserDrill,100,24);((mindustry.world.blocks.production.Drill.DrillBuild)fake).dominantItem=Items.thorium;
                    java.lang.reflect.Method c=Construction.class.getDeclaredMethod("bridgeCell",int.class,int.class,BuildPlan.class,Building.class,Building.class,mindustry.type.Item.class,boolean.class,int.class);c.setAccessible(true);
                    for(int d=0;d<4;d++)System.out.println(" BRIDGE CORE direction="+d+" valid="+c.invoke(brain.construction,91,46,new BuildPlan(100,24,0,Blocks.laserDrill),fake,brain.core(),Items.thorium,true,d));
                    java.lang.reflect.Method rc=Construction.class.getDeclaredMethod("receiverCell",int.class,int.class,BuildPlan.class,Building.class,Building.class,mindustry.type.Item.class,int.class);rc.setAccessible(true);
                    System.out.println(" CORE LANDING "+rc.invoke(brain.construction,91,46,new BuildPlan(100,24,0,Blocks.laserDrill),fake,brain.core(),Items.thorium,1));
                    for(int distance=2;distance<=4;distance++){
                        Building sender=brain.construction.proposed(Blocks.itemBridge,91,46+distance);((mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild)sender).link=arc.math.geom.Point2.pack(91,46);
                        Seq<BuildPlan> approach=brain.construction.supplyRoute(fake,sender);
                        System.out.println(" CORE SENDER "+distance+" valid="+c.invoke(brain.construction,91,46+distance,new BuildPlan(100,24,0,Blocks.laserDrill),fake,brain.core(),Items.thorium,false,3)+" approach="+(approach==null ? -1:approach.size));
                    }
                }
            }
            System.out.println("THORIUM project="+brain.buildDrill(Items.thorium,null)+" status="+brain.status+" plans="+brain.project);
            System.out.println("TI project="+brain.buildDrill(Items.titanium,null)+" status="+brain.status+" plans="+brain.project);
            }
            brain.dispose(); System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
