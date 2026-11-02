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
import mindustry.world.blocks.production.Drill;
import mindustry.world.blocks.production.Drill.DrillBuild;
import mindustry.world.blocks.production.GenericCrafter;
import mindustry.world.blocks.defense.turrets.ItemTurret;

/** Plans only. Runtime construction always goes through Unit.addBuild and BuilderComp. */
public final class Construction{
    public final Team team;
    private final WorldView view;
    private final IntMap<Item> streams = new IntMap<>();
    public Construction(Team team, WorldView view){
        this.team = team; this.view = view;
        String prefix = "fair-ai.flow." + team.id + ".";
        Vars.state.rules.tags.each((key, value) -> {
            if(key.startsWith(prefix)) try{ streams.put(Integer.parseInt(key.substring(prefix.length())), Vars.content.item(Integer.parseInt(value))); }catch(Exception ignored){}
        });
    }

    public void recordFlow(Seq<BuildPlan> plans, Item item){
        if(item == null) return;
        for(BuildPlan plan : plans) if(plan.block instanceof Conveyor){
            int pos = Point2.pack(plan.x, plan.y);
            streams.put(pos, item);
            Vars.state.rules.tags.put("fair-ai.flow." + team.id + "." + pos, Integer.toString(item.id));
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

    private boolean safeStream(int x, int y, Item item, Building source, Building target){
        if(item == null) return true;
        for(Point2 dir : Geometry.d4){
            Building neighbor = Vars.world.build(x + dir.x, y + dir.y);
            if(neighbor == null || neighbor.team != team || neighbor == source || neighbor == target) continue;
            if(neighbor.block instanceof ItemTurret && !((ItemTurret)neighbor.block).ammoTypes.containsKey(item) &&
                !(Vars.world.build(x, y) != null && Vars.world.build(x, y).block instanceof Conveyor && stream(Vars.world.build(x, y)) == item)) return false;
            Item output = outputItem(neighbor);
            if(output != null && output != item) return false;
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
        if(!allowed(block)) return false;
        int offset = -(block.size - 1) / 2;
        for(int dx = 0; dx < block.size; dx++) for(int dy = 0; dy < block.size; dy++){
            Tile tile = Vars.world.tile(x + offset + dx, y + offset + dy);
            if(tile == null || !view.known(tile.x, tile.y) || tile.block() != Blocks.air) return false;
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
                    if(!(existing.block instanceof UnitFactory)) continue;
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
                if(belt == null || !(belt.block instanceof Conveyor)) continue;
                int fx = belt.tileX() + Geometry.d4x[belt.rotation], fy = belt.tileY() + Geometry.d4y[belt.rotation];
                boolean facingMine = fx >= x + offset && fx < x + offset + block.size && fy >= y + offset && fy < y + offset + block.size;
                if(facingMine || stream(belt) != item) return false;
            }
        }
        if(block instanceof GenericCrafter){
            ItemStack[] outputs = ((GenericCrafter)block).outputItems;
            if(outputs != null && outputs.length == 1) for(Point2 edge : Edges.getEdges(block.size)){
                Building belt = Vars.world.build(x + edge.x, y + edge.y);
                if(belt == null || !(belt.block instanceof Conveyor)) continue;
                int fx = belt.tileX() + Geometry.d4x[belt.rotation], fy = belt.tileY() + Geometry.d4y[belt.rotation];
                boolean input = fx >= x + offset && fx < x + offset + block.size && fy >= y + offset && fy < y + offset + block.size;
                if(!input && stream(belt) != outputs[0].item) return false;
            }
        }
        if(block instanceof UnitFactory){
            for(int step = block.size / 2 + 1; step <= block.size / 2 + 3; step++){
                Tile exit = Vars.world.tile(x + Geometry.d4x[rotation] * step, y + Geometry.d4y[rotation] * step);
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
        return !plan.breaking && tile != null && tile.build != null && tile.team() == team &&
            tile.block() instanceof Drill && plan.block instanceof Drill && plan.block.size == tile.block().size &&
            plan.block.canReplace(tile.block()) && Build.validPlace(plan.block, team, plan.x, plan.y, plan.rotation);
    }

    /** BFS from the core edge. Builds belts from sink towards source before placing the drill. */
    public Seq<BuildPlan> drillRoute(BuildPlan drill, CoreBuild core){
        //Processing inputs are never routed to the construction treasury.
        if(RawLogistics.processingInput(drill.tile().drop())) return null;
        int width = Vars.world.width(), height = Vars.world.height();
        int[] parents = new int[width * height];
        java.util.Arrays.fill(parents, -2);
        IntQueue queue = new IntQueue();
        for(Point2 edge : Edges.getEdges(core.block.size)){
            int x = core.tileX() + edge.x, y = core.tileY() + edge.y;
            if(!beltCell(x, y, drill) || !safeStream(x, y, drill.tile().drop(), null, core)) continue;
            int index = x + y * width;
            parents[index] = -1;
            queue.addLast(index);
        }
        int reached = -1, steps = 0;
        while(queue.size > 0 && steps++ < 16000){
            int index = queue.removeFirst(), x = index % width, y = index / width;
            if(adjacent(drill, x, y)){ reached = index; break; }
            for(Point2 dir : Geometry.d4){
                int nx = x + dir.x, ny = y + dir.y, next = nx + ny * width;
                if(nx < 0 || ny < 0 || nx >= width || ny >= height || parents[next] != -2) continue;
                if(!beltCell(nx, ny, drill) || !safeStream(nx, ny, drill.tile().drop(), null, core) || core.dst(nx * 8f, ny * 8f) > 8f * 100f) continue;
                parents[next] = index;
                queue.addLast(next);
            }
        }
        if(reached < 0) return null;
        Seq<BuildPlan> route = new Seq<>();
        for(int cell = reached; cell >= 0; cell = parents[cell]){
            int x = cell % width, y = cell / width, parent = parents[cell];
            int tx, ty;
            if(parent < 0){ tx = core.tileX(); ty = core.tileY(); }
            else { tx = parent % width; ty = parent / width; }
            route.add(new BuildPlan(x, y, direction(tx - x, ty - y), Blocks.conveyor));
        }
        route.reverse();
        route.add(drill);
        return route;
    }

    /** Ordinary conveyor output paths; excludes belts facing back into the source. */
    public Seq<Building> outputBelts(Building source){
        Seq<Building> result = new Seq<>();
        IntSet seen = new IntSet();
        for(Point2 edge : Edges.getEdges(source.block.size)){
            Building belt = Vars.world.build(source.tileX() + edge.x, source.tileY() + edge.y);
            if(belt == null || belt.front() == source) continue;
            while(belt != null && belt.team == team && belt.block instanceof Conveyor && seen.add(belt.pos())){
                if(outputItem(source) != null && stream(belt) != null && stream(belt) != outputItem(source)) break;
                result.add(belt);
                belt = belt.front();
            }
        }
        return result;
    }

    public boolean feeds(Building source, Building target){
        return source.proximity.contains(target, true) || outputBelts(source).contains(b -> b.front() == target);
    }

    /** Paid route from an existing producer to an existing consumer; reuse only its own output belts. */
    public Seq<BuildPlan> supplyRoute(Building source, Building target){
        return supplyRoute(source, target, false);
    }

    public Seq<BuildPlan> miningReturn(Building source, CoreBuild target){
        return supplyRoute(source, target, true);
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
        if(source.team != team || target.team != team) return null;
        if(target instanceof CoreBuild){
            if(RawLogistics.processingInput(outputItem(source))) return null;
            if(!(mining && source instanceof DrillBuild) && !(source.block instanceof GenericCrafter)) return null;
        }
        BuildPlan footprint = new BuildPlan(source.tileX(), source.tileY(), source.rotation, source.block);
        Item flow = outputItem(source);
        if(feeds(source, target) || source.tile.build != source && target.tile.build != target && touching(source, target)) return new Seq<>();
        IntSet reusable = new IntSet();
        if(source.tile.build == source) for(Building belt : outputBelts(source)) reusable.add(belt.pos());
        IntSet shared = new IntSet();
        if(source instanceof DrillBuild){
            for(Building other : view.buildings){
                if(other == source || !(other instanceof DrillBuild) || ((DrillBuild)other).dominantItem != ((DrillBuild)source).dominantItem || !feeds(other, target)) continue;
                for(Building belt : outputBelts(other)){
                    if(!reusable.contains(belt.pos())) shared.add(belt.pos());
                    reusable.add(belt.pos());
                }
            }
        }
        int width = Vars.world.width(), height = Vars.world.height();
        int[] parents = new int[width * height];
        java.util.Arrays.fill(parents, -2);
        IntQueue queue = new IntQueue();
        for(Point2 edge : Edges.getEdges(target.block.size)){
            int x = target.tileX() + edge.x, y = target.tileY() + edge.y;
            if(!supplyCell(x, y, footprint, reusable) || !safeStream(x, y, flow, source, target)) continue;
            if(shared.contains(arc.math.geom.Point2.pack(x, y)) && Vars.world.build(x, y).front() != target) continue;
            int index = x + y * width;
            parents[index] = -1;
            queue.addLast(index);
        }
        int reached = -1, steps = 0;
        while(queue.size > 0 && steps++ < 16000){
            int index = queue.removeFirst(), x = index % width, y = index / width;
            if(adjacent(footprint, x, y)){ reached = index; break; }
            for(Point2 dir : Geometry.d4){
                int nx = x + dir.x, ny = y + dir.y, next = nx + ny * width;
                if(nx < 0 || ny < 0 || nx >= width || ny >= height || parents[next] != -2) continue;
                if(inside(target, nx, ny)) continue;
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

    private static int into(Building target, int x, int y){
        int offset = -(target.block.size - 1) / 2;
        int minX = target.tileX() + offset, minY = target.tileY() + offset;
        return x < minX ? 0 : x >= minX + target.block.size ? 2 : y < minY ? 1 : 3;
    }

    private boolean beltCell(int x, int y, BuildPlan drill){
        Tile tile = Vars.world.tile(x, y);
        if(tile == null || !view.known(x, y)) return false;
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
