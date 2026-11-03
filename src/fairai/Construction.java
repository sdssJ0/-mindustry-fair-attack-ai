package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.Team;
import mindustry.gen.Building;
import mindustry.world.*;
import mindustry.world.blocks.ConstructBlock.ConstructBuild;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;
import mindustry.type.*;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.blocks.distribution.Conveyor;
import mindustry.world.blocks.distribution.ItemBridge;
import mindustry.world.blocks.distribution.ItemBridge.ItemBridgeBuild;
import mindustry.world.blocks.distribution.Router;
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.defense.turrets.ItemTurret;

/** Plans only. Runtime construction always goes through Unit.addBuild and BuilderComp. */
public final class Construction{
    public final Team team;
    private final WorldView view;
    private final IntMap<Item> streams = new IntMap<>();
    private final IntMap<Boolean> collectorCache=new IntMap<>();
    private final IntMap<Seq<Building>> outputCache=new IntMap<>();
    private long collectorTick=-1;
    private void refreshCaches(){
        if(collectorTick!=Vars.state.updateId){collectorCache.clear();outputCache.clear();collectorTick=Vars.state.updateId;}
    }
    public Construction(Team team, WorldView view){
        this.team = team; this.view = view;
        String prefix = "fair-ai.flow." + team.id + ".";
        WorldMarks.each((key, value) -> {
            if(key.startsWith(prefix)) try{ streams.put(Integer.parseInt(key.substring(prefix.length())), Vars.content.item(Integer.parseInt(value))); }catch(Exception ignored){}
        });
    }

    public void recordFlow(Seq<BuildPlan> plans, Item item){
        if(item == null) return;
        outputCache.clear();collectorCache.clear();
        for(BuildPlan plan : plans) if(transport(plan.block)){
            int pos = Point2.pack(plan.x, plan.y);
            streams.put(pos, item);
            WorldMarks.put("fair-ai.flow." + team.id + "." + pos, Integer.toString(item.id));
        }
    }

    private Item stream(Building belt){
        Item item = streams.get(belt.pos());
        if(item != null) return item;
        if(belt.items != null) for(Item candidate : Vars.content.items()) if(belt.items.get(candidate) > 0){
            if(item != null && item != candidate) return null;
            item = candidate;
        }
        return item;
    }

    public Item outputItem(Building source){
        if(source instanceof DrillBuild) return ((DrillBuild)source).dominantItem;
        if(source.block instanceof GenericCrafter){
            ItemStack[] outputs = ((GenericCrafter)source.block).outputItems;
            if(outputs != null && outputs.length == 1) return outputs[0].item;
        }
        return null;
    }
    private Item carried(Building source){return source instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild ? ((mindustry.world.blocks.storage.Unloader.UnloaderBuild)source).sortItem:transport(source.block) ? stream(source):outputItem(source);}

    private boolean safeStream(int x, int y, Item item, Building source, Building target){
        if(item == null) return true;
        //Different finished products may merge at the final core inlet; it has no downstream recipe.
        Building inlet=Vars.world.build(x,y);
        if(target instanceof CoreBuild && !RawLogistics.processingInput(item) && coreCollector(inlet,(CoreBuild)target)) return true;
        for(Point2 dir : Geometry.d4){
            Building neighbor = Vars.world.build(x + dir.x, y + dir.y);
            if(neighbor == null || neighbor.team != team || neighbor == source || neighbor == target) continue;
            if(neighbor.block instanceof ItemTurret && !((ItemTurret)neighbor.block).ammoTypes.containsKey(item) &&
                !(Vars.world.build(x, y) != null && Vars.world.build(x, y).block instanceof Conveyor && stream(Vars.world.build(x, y)) == item)) return false;
            Item output = outputItem(neighbor);
            if(output != null && output != item) return false;
            if(neighbor.block instanceof ItemBridge && neighbor.block.hasItems && !linked((ItemBridgeBuild)neighbor) && stream(neighbor)!=item)return false;
            if(neighbor.block instanceof Router && stream(neighbor)!=item)return false;
            if(neighbor.block instanceof Conveyor){
                int fx = neighbor.tileX() + Geometry.d4x[neighbor.rotation], fy = neighbor.tileY() + Geometry.d4y[neighbor.rotation];
                if(fx == x && fy == y && stream(neighbor) != item) return false;
            }
        }
        return true;
    }

    public boolean allowed(Block block){
        return block.isPlaceable() && block.environmentBuildable() && !Vars.state.rules.isBanned(block);
    }

    public boolean place(Block block, int x, int y, int rotation){
        return place(block, x, y, rotation, false);
    }

    boolean placeDevelopment(Block block, int x, int y, int rotation){
        return place(block, x, y, rotation, true);
    }

    private boolean place(Block block, int x, int y, int rotation, boolean development){
        if(!allowed(block)) return false;
        if(block instanceof UnitFactory && !development)for(int step=block.size/2+1;step<=block.size/2+3;step++){
            if(DevelopmentPlanner.reserved(team,Point2.pack(x+Geometry.d4x[rotation]*step,y+Geometry.d4y[rotation]*step)))return false;
        }
        int offset = -(block.size - 1) / 2;
        if(development && transport(block) && streams.get(Point2.pack(x,y))!=null && view.known(x,y) && Vars.world.tile(x,y)!=null && Vars.world.tile(x,y).block()==Blocks.air)return Build.validPlace(block,team,x,y,rotation);
        for(int dx = 0; dx < block.size; dx++) for(int dy = 0; dy < block.size; dy++){
            Tile tile = Vars.world.tile(x + offset + dx, y + offset + dy);
            if(tile == null || !view.known(tile.x, tile.y) || tile.block() != Blocks.air) return false;
            if(!development && DevelopmentPlanner.reserved(team, tile.pos())) return false;
            if(block.hasItems && !(block instanceof Drill))for(Point2 e:Geometry.d4){
                Building fuel=Vars.world.build(tile.x+e.x,tile.y+e.y);
                if(fuel==null)continue;
                int generator=WorldMarks.getInt("fair-ai.utility-mine."+team.id+"."+fuel.pos(),-1);
                if(generator>=0 && (block!=Blocks.steamGenerator || Point2.pack(x,y)!=generator))return false;
            }
            if(block.hasLiquids)for(Point2 e:Geometry.d4){
                Building pump=Vars.world.build(tile.x+e.x,tile.y+e.y);
                if(pump==null || !(pump.block instanceof mindustry.world.blocks.production.Pump))continue;
                String purpose=WorldMarks.get("fair-ai.water-source."+team.id+"."+pump.pos());
                if(purpose!=null && WorldMarks.getInt("fair-ai.water-line."+team.id+"."+Point2.pack(x,y),-1)!=pump.pos())return false;
            }
            //Keep defense input ports clear of unrelated factories and blueprint tiles.
            //The flow-aware conveyor router uses beltCell instead of this placement path.
            for(Point2 dir : Geometry.d4){
                Building neighbor = Vars.world.build(tile.x + dir.x, tile.y + dir.y);
                if(neighbor == null || !(neighbor.block instanceof ItemTurret)) continue;
                Item output = block instanceof Drill ? Vars.world.tile(x, y).drop() :
                    block instanceof GenericCrafter && ((GenericCrafter)block).outputItems != null &&
                    ((GenericCrafter)block).outputItems.length == 1 ? ((GenericCrafter)block).outputItems[0].item : null;
                if(output == null || !((ItemTurret)neighbor.block).ammoTypes.containsKey(output)) return false;
            }
            if(block.solid){
                for(mindustry.gen.Building existing : view.buildings){
                    if(!(existing.block instanceof UnitFactory) || development && block instanceof mindustry.world.blocks.units.Reconstructor) continue;
                    for(int step = existing.block.size / 2 + 1; step <= existing.block.size / 2 + 3; step++){
                        int ex = existing.tileX() + Geometry.d4x[existing.rotation] * step;
                        int ey = existing.tileY() + Geometry.d4y[existing.rotation] * step;
                        if(tile.x == ex && tile.y == ey) return false;
                    }
                }
            }
        }
        if(block instanceof Drill){
            boolean processing = false;
            for(int dx = 0; dx < block.size; dx++) for(int dy = 0; dy < block.size; dy++){
                if(RawLogistics.processingInput(Vars.world.tile(x + offset + dx, y + offset + dy).drop())) processing = true;
            }
            if(processing) for(Point2 edge : Edges.getEdges(block.size)){
                if(Vars.world.build(x + edge.x, y + edge.y) instanceof CoreBuild) return false;
            }
            Item item = Vars.world.tile(x, y).drop();
            for(Point2 edge : Edges.getEdges(block.size)){
                Building belt = Vars.world.build(x + edge.x, y + edge.y);
                if(belt == null || !transport(belt.block)) continue;
                int fx = belt.tileX() + Geometry.d4x[belt.rotation], fy = belt.tileY() + Geometry.d4y[belt.rotation];
                boolean facingMine = fx >= x + offset && fx < x + offset + block.size && fy >= y + offset && fy < y + offset + block.size;
                if(facingMine || stream(belt) != item) return false;
            }
        }
        if(block instanceof Drill || block instanceof GenericCrafter){
            Item output=block instanceof Drill ? Vars.world.tile(x,y).drop() :
                ((GenericCrafter)block).outputItems!=null && ((GenericCrafter)block).outputItems.length==1 ? ((GenericCrafter)block).outputItems[0].item : null;
            for(Point2 edge:Edges.getEdges(block.size)){
                Item planned=DevelopmentPlanner.busItem(team,Point2.pack(x+edge.x,y+edge.y));
                if(planned!=null && planned!=output) return false;
            }
        }
        if(block instanceof GenericCrafter){
            ItemStack[] outputs = ((GenericCrafter)block).outputItems;
            if(outputs != null && outputs.length == 1) for(Point2 edge : Edges.getEdges(block.size)){
                Building belt = Vars.world.build(x + edge.x, y + edge.y);
                if(belt == null || !transport(belt.block)) continue;
                if((belt.block instanceof Router || belt.block instanceof ItemBridge) && stream(belt)!=outputs[0].item)return false;
                int fx = belt.tileX() + Geometry.d4x[belt.rotation], fy = belt.tileY() + Geometry.d4y[belt.rotation];
                boolean input = fx >= x + offset && fx < x + offset + block.size && fy >= y + offset && fy < y + offset + block.size;
                if(!input && stream(belt) != outputs[0].item) return false;
            }
        }
        if(block instanceof UnitFactory){
            for(int step = block.size / 2 + 1; step <= block.size / 2 + 3; step++){
                Tile exit = Vars.world.tile(x + Geometry.d4x[rotation] * step, y + Geometry.d4y[rotation] * step);
                if(exit!=null && !development && DevelopmentPlanner.reserved(team,exit.pos())) return false;
                if(development && exit!=null && exit.team()==team && exit.block() instanceof mindustry.world.blocks.units.Reconstructor) continue;
                if(exit == null || !view.known(exit.x, exit.y) || exit.solid() || exit.floor().isLiquid) return false;
            }
        }
        return Build.validPlace(block, team, x, y, rotation);
    }

    public BuildPlan site(Block block, CoreBuild core, float towardX, float towardY, boolean front){
        if(!allowed(block)) return null;
        BuildPlan best = null;
        float bestScore = Float.MAX_VALUE;
        for(int radius = 4; radius <= 34; radius++){
            for(int dx = -radius; dx <= radius; dx++) for(int dy = -radius; dy <= radius; dy++){
                if(Math.abs(dx) != radius && Math.abs(dy) != radius) continue;
                int x = core.tileX() + dx, y = core.tileY() + dy;
                if(!place(block, x, y, 0)) continue;
                Tile tile = Vars.world.tile(x, y);
                float score = core.dst(tile) + view.danger(tile.worldx(), tile.worldy()) * 800f;
                if(front){
                    int cover = 0;
                    for(Point2 dir : Geometry.d8){
                        Tile neighbor = Vars.world.tile(x + dir.x * (block.size + 1), y + dir.y * (block.size + 1));
                        if(neighbor != null && view.known(neighbor.x, neighbor.y) && (neighbor.solid() || neighbor.floor().isLiquid)) cover++;
                    }
                    score = tile.dst(towardX, towardY) + Math.abs(core.dst(tile) - 100f) * 2f - cover * 6f +
                        view.danger(tile.worldx(), tile.worldy()) * 120f;
                }
                //Keep a one-tile access aisle around factory footprints.
                int near = 0;
                for(Point2 edge : Edges.getEdges(block.size + 2)){
                    Tile other = Vars.world.tile(x + edge.x, y + edge.y);
                    if(other != null && other.build != null) near++;
                }
                score += near * 8f;
                if(score < bestScore){ bestScore = score; best = new BuildPlan(x, y, 0, block); }
            }
            if(best != null && !front) break;
        }
        return best;
    }

    public boolean affordable(Seq<BuildPlan> plans, CoreBuild core){
        ObjectIntMap<Item> costs = costs(plans);
        for(ObjectIntMap.Entry<Item> entry : costs){
            if(core.items.get(entry.key) < entry.value) return false;
        }
        return true;
    }

    public ObjectIntMap<Item> costs(Seq<BuildPlan> plans){
        ObjectIntMap<Item> costs = new ObjectIntMap<>();
        for(BuildPlan plan : plans){
            if(complete(plan) || plan.breaking || plan.isRotation(team)) continue;
            for(ItemStack stack : plan.block.requirements){
                costs.increment(stack.item, 0, Math.round(stack.amount * Vars.state.rules.buildCostMultiplier));
            }
        }
        return costs;
    }

    public static boolean complete(BuildPlan plan){
        Tile tile = Vars.world.tile(plan.x, plan.y);
        if(plan.breaking) return tile != null && tile.block() == Blocks.air;
        return tile != null && tile.build != null && !(tile.build instanceof ConstructBuild) &&
            tile.block() == plan.block && (!plan.block.rotate || tile.build.rotation == plan.rotation);
    }

    public boolean replacement(BuildPlan plan){
        Tile tile = plan.tile();
        if(!plan.breaking && tile!=null && tile.team()==team && tile.block() instanceof Conveyor && plan.block==Blocks.router &&
            DevelopmentPlanner.owned(team,tile.build) && stream(tile.build)!=null)return Build.validPlace(plan.block,team,plan.x,plan.y,plan.rotation);
        return !plan.breaking && tile != null && tile.build != null && tile.team() == team &&
            tile.block() instanceof Drill && plan.block instanceof Drill && plan.block.size == tile.block().size &&
            plan.block.canReplace(tile.block()) && Build.validPlace(plan.block, team, plan.x, plan.y, plan.rotation);
    }

    /** Use the same verified core collectors for new mining as for manufactured materials. */
    public Seq<BuildPlan> drillRoute(BuildPlan drill, CoreBuild core){
        Item item=drill.tile().drop();
        if(RawLogistics.processingInput(item)) return null;
        Building source=proposed(drill.block,drill.x,drill.y);
        ((DrillBuild)source).dominantItem=item;
        Seq<BuildPlan> route=supplyRoute(source,core,true);
        if(route==null)route=bridgeRoute(source,core);
        if(route==null)route=landingRoute(source,core);
        if(route==null) return null;
        route.add(drill);
        return route;
    }

    /** Ordinary conveyor output paths; excludes belts facing back into the source. */
    public Seq<Building> outputBelts(Building source){
        refreshCaches();
        boolean actual=source.tile!=null && source.tile.build==source;
        Seq<Building> cached=actual ? outputCache.get(source.id):null;
        if(cached!=null)return cached;
        Seq<Building> result = new Seq<>();
        IntSet seen = new IntSet();
        Seq<Building> queue=new Seq<>();
        for(Point2 edge : Edges.getEdges(source.block.size)){
            Building belt = Vars.world.build(source.tileX() + edge.x, source.tileY() + edge.y);
            if(belt == null || belt.block instanceof Conveyor && belt.front() == source) continue;
            if(!outletAllows(source,belt.tileX(),belt.tileY(),carried(source)))continue;
            if(!acceptsTransport(source,belt))continue;
            queue.add(belt);
        }
        for(int i=0;i<queue.size;i++){
            Building belt=queue.get(i);
            if(belt==null || belt.team!=team || !transport(belt.block) || !seen.add(belt.pos()))continue;
            if(!coreCollector(belt,team.core()) && carried(source)!=null && stream(belt)!=null && stream(belt)!=carried(source))continue;
            result.add(belt);
            if(belt instanceof ItemBridgeBuild){
                ItemBridgeBuild bridge=(ItemBridgeBuild)belt;
                if(linked(bridge))queue.add(Vars.world.build(bridge.link));
                else for(Building next:belt.proximity)if(transport(next.block) && acceptsTransport(belt,next) && (!(next.block instanceof Conveyor) || next.front()!=belt) && bridge.canDump(next,carried(source)))queue.add(next);
            }else if(belt.block instanceof Router){
                for(Building next:belt.proximity)if(transport(next.block) && acceptsTransport(belt,next) && (!(next.block instanceof Conveyor) || next.front()!=belt))queue.add(next);
            }else if(acceptsTransport(belt,belt.front()))queue.add(belt.front());
        }
        if(actual)outputCache.put(source.id,result);
        return result;
    }

    public boolean feeds(Building source, Building target){
        return source.proximity.contains(target, true) && acceptsTransport(source,target) && (source.block instanceof Conveyor ? source.front()==target:outletAllows(source,target.tileX(),target.tileY(),carried(source))) || outputBelts(source).contains(b -> acceptsTransport(b,target) && (b instanceof ItemBridgeBuild ?
            !linked((ItemBridgeBuild)b) && b.proximity.contains(target,true) && ((ItemBridgeBuild)b).canDump(target,carried(source)) : b.block instanceof Router ? b.proximity.contains(target,true) : b.front() == target));
    }
    private boolean acceptsTransport(Building from,Building to){
        if(to==null)return false;
        if(to instanceof ItemBridgeBuild){
            if(from instanceof ItemBridgeBuild && ((ItemBridgeBuild)from).link==to.pos())return true;
            return linked((ItemBridgeBuild)to) && inletAllows(to,from.tileX(),from.tileY());
        }
        return !(to.block instanceof Conveyor) || to.front()!=from;
    }
    private boolean transport(Block b){return b instanceof Conveyor || b instanceof Router || b instanceof ItemBridge && b.hasItems;}
    private boolean linked(ItemBridgeBuild b){return ((ItemBridge)b.block).linkValid(b.tile,Vars.world.tile(b.link));}

    /** A short collector may carry mixed core materials only when it cannot feed another recipe. */
    private boolean coreCollector(Building start,CoreBuild core){
        if(core==null || start==null) return false;
        refreshCaches();
        Boolean cached=collectorCache.get(start.pos());
        if(cached!=null) return cached;
        boolean result=traceCoreCollector(start,core);
        collectorCache.put(start.pos(),result);
        return result;
    }
    private boolean traceCoreCollector(Building start,CoreBuild core){
        return traceCoreCollector(start,core,new IntSet(),0);
    }
    private boolean traceCoreCollector(Building b,CoreBuild core,IntSet path,int depth){
        if(b==core)return true;
        if(b==null || b.team!=team || depth>=96 || !transport(b.block) || b.block instanceof Router || DevelopmentPlanner.reserved(team,b.pos()) ||
            RawLogistics.processingInput(stream(b)) || !path.add(b.pos()))return false;
        Boolean cached=collectorCache.get(b.pos());
        if(cached!=null){path.remove(b.pos());return cached;}
        collectorCache.put(b.pos(),false);
        boolean result=false;
        if(b.block instanceof Conveyor)result=acceptsTransport(b,b.front()) && traceCoreCollector(b.front(),core,path,depth+1);
        else if(b instanceof ItemBridgeBuild){
            ItemBridgeBuild bridge=(ItemBridgeBuild)b;
            if(linked(bridge))result=traceCoreCollector(Vars.world.build(bridge.link),core,path,depth+1);
            else{
                boolean output=false,unsafe=false;
                for(Building next:b.proximity){
                    if(next instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild || !bridge.canDump(next,carried(b)))continue;
                    if(!acceptsTransport(b,next))continue;
                    if(next.block instanceof Conveyor && next.front()==b)continue;
                    if(next instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)next))continue;
                    if(next.items==null)continue;
                    if(traceCoreCollector(next,core,path,depth+1))output=true;else unsafe=true;
                }
                result=output && !unsafe;
            }
        }
        path.remove(b.pos());collectorCache.put(b.pos(),result);return result;
    }

    /** Paid route from an existing producer to an existing consumer; reuse only its own output belts. */
    public Seq<BuildPlan> supplyRoute(Building source, Building target){
        Seq<BuildPlan> route=supplyRoute(source,target,false);
        return route==null ? bridgeRoute(source,target) : route;
    }
    public Seq<BuildPlan> branchRoute(Building source,Building target){
        if(feeds(source,target))return new Seq<>();
        boolean dedicated=target.block==Blocks.multiplicativeReconstructor;
        if(dedicated)DevelopmentPlanner.reserveInputs(team,target.tileX(),target.tileY(),target.rotation);
        Seq<BuildPlan> route=dedicated ? null:supplyRoute(source,target,false,true);
        if(route==null && !dedicated)route=bridgeRoute(source,target);
        if(route==null)route=landingRoute(source,target);
        if(route==null && dedicated)route=sourceEscape(source,target);
        if(route!=null)return route;
        for(Building outlet:outputBelts(source))if(outlet instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)outlet) && stream(outlet)==outputItem(source)){
            route=dedicated ? null:supplyRoute(outlet,target,false,true);
            if(route==null && !dedicated)route=bridgeRoute(outlet,target);
            if(route==null)route=landingRoute(outlet,target);
            if(route!=null)return route;
        }
        Item item=outputItem(source);
        for(Building node:outputBelts(source)){
            if(!(node.block instanceof Conveyor) || stream(node)!=item || !DevelopmentPlanner.owned(team,node) || !safeFork(node,source,target,item))continue;
            BuildPlan fork=new BuildPlan(node.tileX(),node.tileY(),0,Blocks.router);
            if(!replacement(fork))continue;
            boolean mixed= view.buildings.contains(other->other!=source && outputItem(other)!=null && outputItem(other)!=item && feeds(other,node));
            if(mixed)continue;
            Building proxy=proposed(Blocks.router,node.tileX(),node.tileY());
            route=dedicated ? null:supplyRoute(proxy,target,false,true);
            if(route==null && !dedicated)route=bridgeRoute(proxy,target);
            if(route==null)route=landingRoute(proxy,target);
            if(route!=null && route.any()){route.add(fork);return route;}
        }
        return null;
    }
    /** Fix the first bridge pair explicitly when an ordinary belt cannot share the forced output cell. */
    private Seq<BuildPlan> sourceEscape(Building source,Building target){
        Item item=carried(source);if(item==null || !allowed(Blocks.itemBridge))return null;
        BuildPlan footprint=new BuildPlan(source.tileX(),source.tileY(),source.rotation,source.block);
        int range=((ItemBridge)Blocks.itemBridge).range;
        for(Point2 edge:Edges.getEdges(source.block.size)){
            int sx=source.tileX()+edge.x,sy=source.tileY()+edge.y;
            if(!outletAllows(source,sx,sy,item))continue;
            for(Point2 d:Geometry.d4){
                int out=direction(d.x,d.y);
                if(!bridgeCell(sx,sy,footprint,source,target,item,false,out))continue;
                for(int distance=2;distance<=range;distance++){
                    int rx=sx+d.x*distance,ry=sy+d.y*distance;
                    if(!bridgeCell(rx,ry,footprint,source,target,item,true,direction(-d.x,-d.y)))continue;
                    boolean clear=true;for(int n=1;n<distance;n++)if(!view.known(sx+d.x*n,sy+d.y*n) || inside(source,sx+d.x*n,sy+d.y*n) || inside(target,sx+d.x*n,sy+d.y*n))clear=false;
                    if(!clear)continue;
                    ItemBridgeBuild receiver=(ItemBridgeBuild)proposed(Blocks.itemBridge,rx,ry);
                    receiver.link=-1;receiver.incoming.add(Point2.pack(sx,sy));
                    int pos=receiver.pos();Item previous=streams.get(pos);streams.put(pos,item);
                    String key="fair-ai.reserve."+team.id+"."+Point2.pack(sx,sy),reserved=WorldMarks.get(key);
                    WorldMarks.put(key,"true");Seq<BuildPlan> tail;
                    try{tail=landingRoute(receiver,target);}
                    finally{if(previous==null)streams.remove(pos);else streams.put(pos,previous);if(reserved==null)WorldMarks.remove(key);else WorldMarks.put(key,reserved);}
                    if(tail==null)continue;
                    Seq<BuildPlan> plans=Seq.with(new BuildPlan(sx,sy,0,Blocks.itemBridge,new Point2(rx-sx,ry-sy)),new BuildPlan(rx,ry,0,Blocks.itemBridge,Integer.valueOf(-1)));
                    plans.addAll(tail);return plans;
                }
            }
        }
        return null;
    }
    private boolean safeFork(Building node,Building source,Building target,Item item){
        if(!allowed(Blocks.router))return false;
        for(Point2 e:Geometry.d4){
            Item future=DevelopmentPlanner.busItem(team,Point2.pack(node.tileX()+e.x,node.tileY()+e.y));
            if(future!=null && future!=item)return false;
            Building b=Vars.world.build(node.tileX()+e.x,node.tileY()+e.y);
            if(b==null || b==source || b==target)continue;
            if(b instanceof CoreBuild){if(RawLogistics.processingInput(item))return false;continue;}
            if(transport(b.block)){if(stream(b)!=item)return false;}
            else if(b.items!=null && outputItem(b)!=item)return false;
        }
        return true;
    }
    /** An unlinked bridge receiver rejects ordinary side inputs, so separate ingredients may land together safely. */
    private Seq<BuildPlan> landingRoute(Building source,Building target){
        if(!(target.block instanceof mindustry.world.blocks.units.Reconstructor) && target.block!=Blocks.kiln && !(target instanceof CoreBuild) || !allowed(Blocks.itemBridge))return null;
        Item item=carried(source);if(item==null)return null;
        if(target instanceof CoreBuild && RawLogistics.processingInput(item))return null;
        BuildPlan footprint=new BuildPlan(source.tileX(),source.tileY(),0,source.block);
        if(!freshOutlet(source,target,footprint,item))return null;
        int range=((ItemBridge)Blocks.itemBridge).range;
        for(Point2 edge:Edges.getEdges(target.block.size)){
            int rx=target.tileX()+edge.x,ry=target.tileY()+edge.y;
            if(target.block==Blocks.multiplicativeReconstructor){
                Point2 assigned=DevelopmentPlanner.inputPort(target.tileX(),target.tileY(),target.rotation,item);
                if(rx!=assigned.x || ry!=assigned.y)continue;
            }
            for(Point2 d:Geometry.d4){
                int inputDir=direction(d.x,d.y);
                if(inputDir==into(target,rx,ry) || !receiverCell(rx,ry,footprint,source,target,item,inputDir))continue;
                for(int distance=2;distance<=range;distance++){
                    int sx=rx+d.x*distance,sy=ry+d.y*distance;
                    Building oldSender=Vars.world.build(sx,sy);
                    boolean reuse=oldSender instanceof ItemBridgeBuild && oldSender.team==team && stream(oldSender)==item && ((ItemBridgeBuild)oldSender).link==Point2.pack(rx,ry);
                    if(!reuse && !bridgeCell(sx,sy,footprint,source,target,item,false,direction(-d.x,-d.y)))continue;
                    boolean clear=true;
                    for(int n=1;n<distance;n++)if(inside(target,rx+d.x*n,ry+d.y*n) || !view.known(rx+d.x*n,ry+d.y*n))clear=false;
                    if(!clear)continue;
                    Building sender=reuse ? oldSender:proposed(Blocks.itemBridge,sx,sy);
                    if(!reuse)((ItemBridgeBuild)sender).link=Point2.pack(rx,ry);
                    Seq<BuildPlan> approach=supplyRoute(source,sender,false,true);
                    if(approach==null)approach=bridgeRoute(source,sender);
                    if(approach==null)continue;
                    Seq<BuildPlan> plans=Seq.with(new BuildPlan(rx,ry,0,Blocks.itemBridge,Integer.valueOf(-1)));
                    plans.addAll(approach);plans.add(new BuildPlan(sx,sy,0,Blocks.itemBridge,new Point2(rx-sx,ry-sy)));return plans;
                }
            }
        }
        return null;
    }
    private boolean receiverCell(int x,int y,BuildPlan footprint,Building source,Building target,Item item,int inputDir){
        Tile at=Vars.world.tile(x,y);if(at==null || !view.known(x,y) || inside(target,x,y) || inside(source,x,y))return false;
        boolean collector=target instanceof CoreBuild && coreCollector(at.build,(CoreBuild)target);
        boolean reuse=at.build instanceof ItemBridgeBuild && at.team()==team && (stream(at.build)==item || collector) && !linked((ItemBridgeBuild)at.build);
        if(reuse && collector)return true;
        boolean dedicated=target.block==Blocks.multiplicativeReconstructor && DevelopmentPlanner.inputPort(target.tileX(),target.tileY(),target.rotation,item).equals(new Point2(x,y));
        if(!reuse && (at.block()!=Blocks.air || !dedicated && DevelopmentPlanner.reserved(team,at.pos()) || !Build.validPlace(Blocks.itemBridge,team,x,y,0)))return false;
        for(Point2 d:Geometry.d4){
            int nx=x+d.x,ny=y+d.y;boolean protectedSide=direction(d.x,d.y)==inputDir;
            Item future=DevelopmentPlanner.busItem(team,Point2.pack(nx,ny));
            if(future!=null && future!=item && !protectedSide)return false;
            Building b=Vars.world.build(nx,ny);if(b==null || b==source || b==target || protectedSide)continue;
            if(b instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild)continue;
            if(b instanceof ItemBridgeBuild){
                ItemBridgeBuild other=(ItemBridgeBuild)b;
                if(other.link==Point2.pack(x,y) && stream(other)!=item)return false;
                if(!linked(other))continue;
                int out=direction(Point2.x(other.link)-other.tileX(),Point2.y(other.link)-other.tileY());
                if(out==direction(x-other.tileX(),y-other.tileY()))continue;
            }
            if(transport(b.block)){if(stream(b)!=item && !(target instanceof CoreBuild && coreCollector(b,(CoreBuild)target)))return false;}
        }
        return true;
    }

    public Seq<BuildPlan> miningReturn(Building source, CoreBuild target){
        Seq<BuildPlan> route=supplyRoute(source,target,true);
        if(route==null)route=bridgeRoute(source,target);
        return route==null ? landingRoute(source,target):route;
    }

    /** An unregistered planning object checks a factory's future exit without changing the world. */
    Seq<BuildPlan> factorySupplyPlan(Block block, int x, int y, Building target){
        Building proposed = proposed(block, x, y);
        Seq<BuildPlan> route = supplyRoute(proposed, target);
        if(route == null) return null;
        route.add(new BuildPlan(x, y, 0, block));
        return route;
    }

    Building proposed(Block block, int x, int y){
        Building proposed = block.newBuilding();
        proposed.block = block; proposed.team = team; proposed.tile = Vars.world.tile(x, y);
        proposed.set(x * 8f + block.offset, y * 8f + block.offset);
        return proposed;
    }

    private boolean inside(Building building, int x, int y){
        int offset = -(building.block.size - 1) / 2;
        return x >= building.tileX() + offset && x < building.tileX() + offset + building.block.size &&
            y >= building.tileY() + offset && y < building.tileY() + offset + building.block.size;
    }

    boolean overlaps(Building source, Building target){
        int offset = -(source.block.size - 1) / 2;
        for(int dx = 0; dx < source.block.size; dx++) for(int dy = 0; dy < source.block.size; dy++){
            if(inside(target, source.tileX() + offset + dx, source.tileY() + offset + dy)) return true;
        }
        return false;
    }

    private boolean touching(Building source, Building target){
        for(Point2 edge : Edges.getEdges(source.block.size)) if(inside(target, source.tileX() + edge.x, source.tileY() + edge.y)) return true;
        return false;
    }

    private Seq<BuildPlan> supplyRoute(Building source, Building target, boolean mining){
        return supplyRoute(source,target,mining,false);
    }
    private Seq<BuildPlan> supplyRoute(Building source, Building target, boolean mining,boolean fresh){
        if(source.team != team || target.team != team) return null;
        if(target instanceof CoreBuild){
            if(RawLogistics.processingInput(outputItem(source))) return null;
            if(!(mining && source instanceof DrillBuild) && !(source.block instanceof GenericCrafter)) return null;
        }
        BuildPlan footprint = new BuildPlan(source.tileX(), source.tileY(), source.rotation, source.block);
        Item flow = carried(source);
        if(feeds(source, target) || (source.tile.build != source || target.tile.build != target) && touching(source, target)) return new Seq<>();
        IntSet reusable = new IntSet();
        if(!fresh && source.tile.build == source) for(Building belt : outputBelts(source)) if(belt.block instanceof Conveyor)reusable.add(belt.pos());
        if(target instanceof CoreBuild) for(Building inlet:view.buildings){
            if(inlet.within(target,192f) && coreCollector(inlet,(CoreBuild)target)) reusable.add(inlet.pos());
        }
        IntSet shared = new IntSet();
        if(!fresh && source instanceof DrillBuild){
            for(Building other : view.buildings){
                if(other == source || !(other instanceof DrillBuild) || ((DrillBuild)other).dominantItem != ((DrillBuild)source).dominantItem || !feeds(other, target)) continue;
                for(Building belt : outputBelts(other)){
                    if(!(belt.block instanceof Conveyor))continue;
                    if(!reusable.contains(belt.pos())) shared.add(belt.pos());
                    reusable.add(belt.pos());
                }
            }
        }
        boolean exit=false;
        for(Point2 edge:Edges.getEdges(source.block.size)){
            int x=source.tileX()+edge.x,y=source.tileY()+edge.y;
            if(supplyCell(x,y,footprint,reusable) && safeStream(x,y,flow,source,target) && outletAllows(source,x,y,flow)){exit=true;break;}
        }
        if(!exit)return null;
        int width = Vars.world.width(), height = Vars.world.height();
        int[] parents = new int[width * height];
        java.util.Arrays.fill(parents, -2);
        IntQueue queue = new IntQueue();
        for(Point2 edge : Edges.getEdges(target.block.size)){
            int x = target.tileX() + edge.x, y = target.tileY() + edge.y;
            if(!inletAllows(target,x,y))continue;
            Building existing=Vars.world.build(x,y);
            if(target instanceof CoreBuild && existing instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)existing))continue;
            if(!supplyCell(x, y, footprint, reusable) || !safeStream(x, y, flow, source, target)) continue;
            if(shared.contains(arc.math.geom.Point2.pack(x, y)) && Vars.world.build(x, y).front() != target) continue;
            int index = x + y * width;
            parents[index] = -1;
            queue.addLast(index);
        }
        if(target instanceof CoreBuild)for(Building inlet:view.buildings){
            if(!reusable.contains(inlet.pos()) || inlet instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)inlet) || !coreCollector(inlet,(CoreBuild)target))continue;
            int index=inlet.tileX()+inlet.tileY()*width;
            if(parents[index]==-2){parents[index]=-1;queue.addLast(index);}
        }
        int reached = -1, steps = 0;
        while(queue.size > 0 && steps++ < 16000){
            int index = queue.removeFirst(), x = index % width, y = index / width;
            if(adjacent(footprint, x, y) && outletAllows(source,x,y,flow)){ reached = index; break; }
            for(Point2 dir : Geometry.d4){
                int nx = x + dir.x, ny = y + dir.y, next = nx + ny * width;
                if(nx < 0 || ny < 0 || nx >= width || ny >= height || parents[next] != -2) continue;
                if(inside(target, nx, ny)) continue;
                Building nextBuilding=Vars.world.build(nx,ny);
                if(target instanceof CoreBuild && nextBuilding instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)nextBuilding))continue;
                Building merge=Vars.world.build(x,y);
                if(target instanceof CoreBuild && merge!=null && reusable.contains(merge.pos()) && coreCollector(merge,(CoreBuild)target)){
                    if(merge.block instanceof Conveyor && merge.front()==Vars.world.build(nx,ny) || !inletAllows(merge,nx,ny))continue;
                }
                if(!supplyCell(nx, ny, footprint, reusable) || !safeStream(nx, ny, flow, source, target) || target.dst(nx * 8f, ny * 8f) > 800f) continue;
                if(shared.contains(arc.math.geom.Point2.pack(nx, ny)) &&
                    (Vars.world.build(nx, ny).front() == null || Vars.world.build(nx, ny).front() != Vars.world.build(x, y))) continue;
                parents[next] = index;
                queue.addLast(next);
            }
        }
        if(reached < 0) return null;
        Seq<BuildPlan> plans = new Seq<>();
        for(int cell = reached; cell >= 0; cell = parents[cell]){
            int x = cell % width, y = cell / width, parent = parents[cell];
            int tx = parent < 0 ? target.tileX() : parent % width;
            int ty = parent < 0 ? target.tileY() : parent / width;
            Tile tile = Vars.world.tile(x, y);
            Block belt = reusable.contains(tile.pos()) ? tile.block() : Blocks.conveyor;
            int rotation = parent < 0 ? into(target, x, y) : direction(tx - x, ty - y);
            if(parent<0 && target instanceof CoreBuild && tile.build!=null && coreCollector(tile.build,(CoreBuild)target))rotation=tile.build.rotation;
            plans.add(new BuildPlan(x, y, rotation, belt));
        }
        plans.reverse();
        return plans;
    }

    private boolean supplyCell(int x, int y, BuildPlan source, IntSet reusable){
        Tile tile = Vars.world.tile(x, y);
        return tile != null && view.known(x, y) &&
            (reusable.contains(tile.pos()) || beltCell(x, y, source));
    }

    /** A fresh commodity line may jump a blocked crossing using configured, normally paid bridge pairs. */
    private Seq<BuildPlan> bridgeRoute(Building source,Building target){
        Item item=carried(source);
        if(item==null || source.team!=team || target.team!=team || !allowed(Blocks.itemBridge) || target instanceof CoreBuild && RawLogistics.processingInput(item))return null;
        BuildPlan footprint=new BuildPlan(source.tileX(),source.tileY(),0,source.block);
        if(!freshOutlet(source,target,footprint,item))return null;
        int w=Vars.world.width(),h=Vars.world.height(),range=((ItemBridge)Blocks.itemBridge).range;
        RouteCells cells=new RouteCells(source,target,footprint,item,w,h);
        int[] parent=new int[w*h],cost=new int[w*h];java.util.Arrays.fill(parent,-2);java.util.Arrays.fill(cost,Integer.MAX_VALUE);java.util.PriorityQueue<RouteNode> queue=new java.util.PriorityQueue<>();
        for(Point2 e:Edges.getEdges(target.block.size)){
            int x=target.tileX()+e.x,y=target.tileY()+e.y;
            if(!inletAllows(target,x,y))continue;
            if(!cells.line(x,y))continue;
            parent[x+y*w]=-1;cost[x+y*w]=0;queue.add(new RouteNode(x+y*w,0,Math.abs(x-source.tileX())+Math.abs(y-source.tileY())));
        }
        if(target instanceof CoreBuild)for(Building inlet:view.buildings){
            if(inlet.within(target,192f) && coreCollector(inlet,(CoreBuild)target) && (!(inlet instanceof ItemBridgeBuild) || linked((ItemBridgeBuild)inlet))){
                int at=inlet.tileX()+inlet.tileY()*w;
                parent[at]=-1;cost[at]=0;queue.add(new RouteNode(at,0,Math.abs(inlet.tileX()-source.tileX())+Math.abs(inlet.tileY()-source.tileY())));
            }
        }
        int end=-1;
        while(!queue.isEmpty()){
            RouteNode node=queue.poll();int at=node.index,x=at%w,y=at/w;
            if(node.cost!=cost[at])continue;
            if(adjacent(footprint,x,y) && outletAllows(source,x,y,item)){end=at;break;}
            int downstream=parent[at];
            boolean relay=downstream>=0 && Math.abs(downstream%w-x)+Math.abs(downstream/w-y)>1;
            int outDir=downstream<0 ? into(target,x,y) : direction(downstream%w-x,downstream/w-y);
            for(Point2 d:Geometry.d4){
                boolean blocked=false;
                for(int distance=1;distance<=range;distance++){
                    int nx=x+d.x*distance,ny=y+d.y*distance,n=nx+ny*w;
                    if(nx<0 || ny<0 || nx>=w || ny>=h || !view.known(nx,ny))break;
                    if(inside(target,nx,ny) || inside(source,nx,ny))break;
                    Building merge=Vars.world.build(x,y);
                    if(target instanceof CoreBuild && merge!=null && coreCollector(merge,(CoreBuild)target) &&
                        (merge.block instanceof Conveyor && merge.front()==Vars.world.build(nx,ny) || !inletAllows(merge,nx,ny)))continue;
                    boolean plain=cells.line(nx,ny);
                    boolean legal=plain || distance>1 && cells.endpoint(nx,ny,false,direction(-d.x,-d.y));
                    int inputDir=direction(d.x,d.y);
                    boolean compatible=distance==1 ? !relay || inputDir!=outDir : relay || inputDir!=outDir;
                    boolean endpoints=distance==1 || cells.endpoint(x,y,!relay,relay ? outDir:inputDir) && cells.endpoint(nx,ny,false,direction(-d.x,-d.y));
                    int nextCost=cost[at]+distance+(distance>1 ? 2:0);
                    if(compatible && endpoints && legal && nextCost<cost[n] && (distance==1 || blocked || !plain)){
                        parent[n]=at;cost[n]=nextCost;queue.add(new RouteNode(n,nextCost,nextCost+Math.abs(nx-source.tileX())+Math.abs(ny-source.tileY())));
                    }
                    if(!plain)blocked=true;
                }
            }
        }
        if(end<0)return null;
        IntMap<BuildPlan> planned=new IntMap<>();Seq<BuildPlan> result=new Seq<>();
        for(int at=end;at>=0;at=parent[at]){
            int x=at%w,y=at/w,p=parent[at];
            boolean jump=p>=0 && Math.abs(p%w-x)+Math.abs(p/w-y)>1;
            BuildPlan current=planned.get(at);
            if(current==null){
                Building existing=Vars.world.build(x,y);
                boolean collector=target instanceof CoreBuild && existing!=null && coreCollector(existing,(CoreBuild)target);
                current=new BuildPlan(x,y,collector ? existing.rotation:p<0 ? into(target,x,y):direction(p%w-x,p/w-y),collector ? existing.block:Blocks.conveyor);
                planned.put(at,current);result.add(current);
            }
            if(jump){
                current.block=Blocks.itemBridge;current.config=new Point2(p%w-x,p/w-y);
                BuildPlan out=planned.get(p);
                if(out==null){out=new BuildPlan(p%w,p/w,0,Blocks.itemBridge,Integer.valueOf(-1));planned.put(p,out);result.add(out);}
            }
        }
        result.reverse();return result;
    }
    private static final class RouteNode implements Comparable<RouteNode>{
        final int index,cost,priority;
        RouteNode(int index,int cost,int priority){this.index=index;this.cost=cost;this.priority=priority;}
        public int compareTo(RouteNode other){int score=Integer.compare(priority,other.priority);return score==0 ? Integer.compare(index,other.index):score;}
    }
    /** Placement and neighbour checks are invariant during a single search, including its bridge jumps. */
    private final class RouteCells{
        final Building source,target;final BuildPlan footprint;final Item item;final int width;
        final byte[] lines;final byte[][] endpoints;
        RouteCells(Building source,Building target,BuildPlan footprint,Item item,int width,int height){
            this.source=source;this.target=target;this.footprint=footprint;this.item=item;this.width=width;
            lines=new byte[width*height];endpoints=new byte[8][width*height];
        }
        boolean line(int x,int y){
            int p=x+y*width;if(lines[p]==0)lines[p]=(byte)(bridgeLineCell(x,y,footprint,source,target,item) ? 1:2);
            return lines[p]==1;
        }
        boolean endpoint(int x,int y,boolean receiver,int direction){
            int p=x+y*width,variant=(receiver ? 4:0)+direction;
            if(endpoints[variant][p]==0)endpoints[variant][p]=(byte)(bridgeCell(x,y,footprint,source,target,item,receiver,direction) ? 1:2);
            return endpoints[variant][p]==1;
        }
    }
    private boolean bridgeCell(int x,int y,BuildPlan footprint,Building source,Building target,Item item,boolean receiver,int protectedDir){
        mindustry.world.Tile tile=Vars.world.tile(x,y);
        if(tile==null || !view.known(x,y) || inside(target,x,y) || !beltCell(x,y,footprint) || !Build.validPlace(Blocks.itemBridge,team,x,y,0))return false;
        if(!receiver && adjacent(footprint,x,y) && protectedDir==direction(source.tileX()-x,source.tileY()-y))return false;
        for(Point2 e:Geometry.d4){
            int nx=x+e.x,ny=y+e.y;boolean protectedSide=direction(e.x,e.y)==protectedDir;
            Item future=DevelopmentPlanner.busItem(team,Point2.pack(nx,ny));
            if(future!=null && future!=item && !protectedSide)return false;
            Building b=Vars.world.build(nx,ny);
            if(b==null || b==source || b==target || protectedSide)continue;
            if(b instanceof CoreBuild){if(RawLogistics.processingInput(item))return false;continue;}
            if(receiver){
                if(b instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild)continue;
                if(b instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)b))continue;
                if(b.block instanceof Conveyor && b.tileX()+Geometry.d4x[b.rotation]==x && b.tileY()+Geometry.d4y[b.rotation]==y)continue;
                if(transport(b.block)){
                    if(stream(b)!=item && (RawLogistics.processingInput(item) || !coreCollector(b,team.core())))return false;
                }
            }else{
                Item output=outputItem(b);
                if(output!=null && output!=item)return false;
                if(output==null && b.block.outputsItems() && !transport(b.block) && !(b instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild))return false;
                if(b.block instanceof Conveyor && b.tileX()+Geometry.d4x[b.rotation]==x && b.tileY()+Geometry.d4y[b.rotation]==y && stream(b)!=item)return false;
                if(b.block instanceof Router && stream(b)!=item)return false;
                if(b instanceof ItemBridgeBuild && !linked((ItemBridgeBuild)b) && stream(b)!=item && ((ItemBridgeBuild)b).canDump(proposed(Blocks.itemBridge,x,y),stream(b)))return false;
                if(b instanceof mindustry.world.blocks.storage.Unloader.UnloaderBuild && ((mindustry.world.blocks.storage.Unloader.UnloaderBuild)b).sortItem!=item)return false;
            }
        }
        return true;
    }
    private boolean outletAllows(Building source,int x,int y,Item item){
        if(source.block instanceof Conveyor)return source.tileX()+Geometry.d4x[source.rotation]==x && source.tileY()+Geometry.d4y[source.rotation]==y;
        return !(source instanceof ItemBridgeBuild) || ((ItemBridgeBuild)source).canDump(proposed(Blocks.conveyor,x,y),item);
    }
    private boolean inletAllows(Building target,int x,int y){
        if(!(target instanceof ItemBridgeBuild) || ((ItemBridgeBuild)target).link==-1)return true;
        int link=((ItemBridgeBuild)target).link;
        return direction(x-target.tileX(),y-target.tileY())!=direction(Point2.x(link)-target.tileX(),Point2.y(link)-target.tileY());
    }
    private boolean freshOutlet(Building source,Building target,BuildPlan footprint,Item item){
        for(Point2 e:Edges.getEdges(source.block.size)){
            int x=source.tileX()+e.x,y=source.tileY()+e.y;
            if(bridgeLineCell(x,y,footprint,source,target,item) && outletAllows(source,x,y,item))return true;
            if(outletAllows(source,x,y,item))for(int dir=0;dir<4;dir++)if(bridgeCell(x,y,footprint,source,target,item,false,dir))return true;
        }
        return false;
    }
    private boolean bridgeLineCell(int x,int y,BuildPlan footprint,Building source,Building target,Item item){
        Building existing=Vars.world.build(x,y);
        if(target instanceof CoreBuild && !RawLogistics.processingInput(item) && coreCollector(existing,(CoreBuild)target))return !(existing instanceof ItemBridgeBuild) || linked((ItemBridgeBuild)existing);
        if(inside(target,x,y) || !beltCell(x,y,footprint) || !safeStream(x,y,item,source,target) || target.dst(x*8f,y*8f)>800f)return false;
        if(RawLogistics.processingInput(item))for(Point2 e:Geometry.d4)if(Vars.world.build(x+e.x,y+e.y) instanceof CoreBuild)return false;
        return true;
    }

    private static int into(Building target, int x, int y){
        int offset = -(target.block.size - 1) / 2;
        int minX = target.tileX() + offset, minY = target.tileY() + offset;
        return x < minX ? 0 : x >= minX + target.block.size ? 2 : y < minY ? 1 : 3;
    }

    private boolean beltCell(int x, int y, BuildPlan drill){
        Tile tile = Vars.world.tile(x, y);
        if(tile == null || !view.known(x, y)) return false;
        if(DevelopmentPlanner.reserved(team, tile.pos())){
            Item item=streams.get(Point2.pack(drill.x,drill.y));
            if(item==null || WorldMarks.getInt("fair-ai.egress."+team.id+"."+tile.pos(),-1)!=item.id)return false;
        }
        int offset = -(drill.block.size - 1) / 2;
        if(x >= drill.x + offset && y >= drill.y + offset &&
            x < drill.x + offset + drill.block.size && y < drill.y + offset + drill.block.size) return false;
        return tile.block() == Blocks.air && view.danger(tile.worldx(), tile.worldy()) < 0.5f &&
            Build.validPlace(Blocks.conveyor, team, x, y, 0);
    }

    private boolean adjacent(BuildPlan plan, int x, int y){
        for(Point2 edge : Edges.getEdges(plan.block.size)){
            if(plan.x + edge.x == x && plan.y + edge.y == y) return true;
        }
        return false;
    }

    private static int direction(int dx, int dy){
        return Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 0 : 2) : (dy > 0 ? 1 : 3);
    }
}
