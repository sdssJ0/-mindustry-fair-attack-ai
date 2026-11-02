package fairai;

import arc.files.Fi;
import arc.math.geom.Point2;
import arc.struct.*;
import arc.util.Log;
import arc.util.serialization.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.game.*;
import mindustry.game.Schematic.Stile;
import mindustry.type.Item;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;
import mindustry.world.blocks.logic.*;
import mindustry.world.blocks.power.*;
import mindustry.world.blocks.production.*;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.units.UnitFactory;
import mindustry.world.consumers.Consume;
import mindustry.world.consumers.ConsumeItems;

/** Attributed blueprint data -> terrain/budget-checked normal build plans. Never places tiles. */
public final class BlueprintLibrary{
    public final Seq<Entry> entries = new Seq<>();
    public final Seq<String> failures = new Seq<>();
    public int acceptedProjects;

    public void load(Fi bundled, Fi custom){
        entries.clear(); failures.clear();
        Fi manifest = bundled.child("catalog.json");
        if(manifest.exists()){
            for(JsonValue row : new JsonReader().parse(manifest)){
                loadFile(bundled.child(row.getString("file")), row.getString("author", "unknown"),
                    row.getString("url", ""), row.getString("license", ""));
            }
        }
        if(custom != null && custom.exists()){
            for(Fi file : custom.list("msch")) loadFile(file, "本地蓝图", file.name(), "本地导入");
        }
        Log.info("[Fair AI] Loaded @ blueprints, @ rejected files.", entries.size, failures.size);
    }

    private void loadFile(Fi file, String author, String source, String license){
        try{
            if(file.length() > 1024 * 1024) throw new IllegalArgumentException("蓝图文件过大");
            Schematic raw = Schematics.read(file);
            if(raw.width > 128 || raw.height > 128 || raw.tiles.size > 4096) throw new IllegalArgumentException("超出蓝图规模限制");
            Entry entry = new Entry();
            entry.file = file.name(); entry.author = author; entry.source = source; entry.license = license;
            entry.raw = raw;
            Seq<Stile> safe = new Seq<>();
            for(Stile tile : raw.tiles){
                if(forbidden(tile.block)){
                    entry.removed++;
                    continue;
                }
                safe.add(copyTile(tile));
            }
            if(safe.isEmpty()) throw new IllegalArgumentException("没有可用的普通建筑");
            entry.safe = normalize(safe, raw.tags);
            entries.add(entry);
        }catch(Exception error){
            failures.add(file.name() + ": " + error.getMessage());
        }
    }

    /** Test sources/sinks and imported programs cannot enter the fair execution path. */
    public static boolean forbidden(Block block){
        return block == Blocks.air || block instanceof CoreBlock || block instanceof LogicBlock || block instanceof MessageBlock ||
            block instanceof MemoryBlock || block instanceof LogicDisplay ||
            block == Blocks.itemSource || block == Blocks.itemVoid || block == Blocks.liquidSource || block == Blocks.liquidVoid ||
            block == Blocks.powerSource || block == Blocks.powerVoid || block == Blocks.payloadSource || block == Blocks.payloadVoid;
    }

    public Placement choose(Block wanted, TeamBrain brain){
        if(!Vars.state.rules.schematicsAllowed || brain.core() == null) return null;
        Placement best = null;
        float bestScore = Float.MAX_VALUE;
        for(Entry entry : entries){
            int count = entry.safe.tiles.count(t -> t.block == wanted);
            if(count == 0) continue;
            Seq<Variant> variants = new Seq<>();
            variants.add(new Variant(entry.safe, "完整模块（去除测试源与程序）"));
            if(!(wanted instanceof Drill) && !(wanted instanceof PowerGenerator)){
                variants.add(new Variant(compact(entry.safe, wanted, 1), "缩减为单机模块"));
                if(count > 1) variants.add(new Variant(compact(entry.safe, wanted, 2), "缩减为双机模块"));
            }
            for(Variant variant : variants){
                if(variant.schematic == null || variant.schematic.tiles.size > 240 || variant.schematic.width > 32 || variant.schematic.height > 32) continue;
                Seq<BuildPlan> estimate = plans(variant.schematic, 0, 0, brain);
                if(estimate.count(p -> p.block == wanted) > brain.economy.machinesWanted(wanted)) continue;
                if(!brain.construction.affordable(estimate, brain.core())) continue;
                for(int rotation = 0; rotation < 4; rotation++){
                    Schematic rotated = rotateCopy(variant.schematic, rotation);
                    //A bounded perimeter search avoids rescanning the entire map for every template.
                    boolean found = false;
                    for(int radius = 5; radius <= 25 && !found; radius += 2){
                        for(int dx = -radius; dx <= radius && !found; dx++) for(int dy = -radius; dy <= radius; dy++){
                            if(Math.abs(dx) != radius && Math.abs(dy) != radius) continue;
                            int x = brain.core().tileX() + dx, y = brain.core().tileY() + dy;
                            Seq<BuildPlan> candidate = plans(rotated, x, y, brain);
                            if(!valid(candidate, wanted, brain)) continue;
                            if(!brain.construction.affordable(candidate, brain.core())) continue;
                            float score = dx * dx + dy * dy + candidate.size * 2f - candidate.count(p -> p.block == wanted) * 16f;
                            score += brain.view.danger(x * 8f, y * 8f) * 10000f;
                            if(score < bestScore){
                                bestScore = score;
                                best = new Placement();
                                best.entry = entry; best.plans = candidate; best.rotation = rotation;
                                best.adaptation = variant.adaptation + "；旋转 " + rotation * 90 + "°";
                                if(candidate.contains(p -> p.block == Blocks.conveyor) && rotated.tiles.contains(t -> t.block == Blocks.titaniumConveyor)){
                                    best.adaptation += "；传送带降级适配库存";
                                }
                            }
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
        return best;
    }

    Placement nearInput(Placement selected, BuildPlan site, Block wanted, TeamBrain brain){
        if(selected == null || site == null || selected.plans.count(p -> p.block == wanted) != 1) return null;
        BuildPlan machine = selected.plans.find(p -> p.block == wanted);
        Seq<BuildPlan> moved = selected.plans.map(p -> new BuildPlan(p.x + site.x - machine.x,
            p.y + site.y - machine.y, p.rotation, p.block, copyConfig(p.config)));
        if(!valid(moved, wanted, brain) || !brain.construction.affordable(moved, brain.core())) return null;
        selected.plans = moved;
        selected.adaptation += "；按实际原料供给重新选址";
        return selected;
    }

    private boolean valid(Seq<BuildPlan> plans, Block wanted, TeamBrain brain){
        IntSet occupied = new IntSet();
        ObjectSet<Item> ingredients = new ObjectSet<>();
        for(Consume consume : wanted.consumers){
            if(consume instanceof ConsumeItems) for(mindustry.type.ItemStack stack : ((ConsumeItems)consume).items) ingredients.add(stack.item);
        }
        for(BuildPlan plan : plans){
            if(forbidden(plan.block) || !brain.construction.place(plan.block, plan.x, plan.y, plan.rotation)) return false;
            int offset = -(plan.block.size - 1) / 2;
            for(int dx = 0; dx < plan.block.size; dx++) for(int dy = 0; dy < plan.block.size; dy++){
                int x = plan.x + offset + dx, y = plan.y + offset + dy;
                if(!occupied.add(Point2.pack(x, y))) return false;
            }
            if(plan.block instanceof Drill && ingredients.size > 0){
                //A graphite/Si prefab must really sit on one of its recipe's ores.
                boolean found = false;
                for(int dx = 0; dx < plan.block.size; dx++) for(int dy = 0; dy < plan.block.size; dy++){
                    Item item = Vars.world.tile(plan.x + offset + dx, plan.y + offset + dy).drop();
                    if(item != null && item != mindustry.content.Items.sand && !ingredients.contains(item)) return false;
                    if(item != null && ingredients.contains(item)) found = true;
                }
                if(!found) return false;
            }
        }
        //A prefab must not obstruct its own factories' output lanes either.
        for(BuildPlan plan : plans){
            if(!(plan.block instanceof UnitFactory)) continue;
            for(int step = plan.block.size / 2 + 1; step <= plan.block.size / 2 + 3; step++){
                int x = plan.x + arc.math.geom.Geometry.d4x[plan.rotation] * step;
                int y = plan.y + arc.math.geom.Geometry.d4y[plan.rotation] * step;
                for(BuildPlan other : plans){
                    if(other == plan || !other.block.solid) continue;
                    int off = -(other.block.size - 1) / 2;
                    if(x >= other.x + off && x < other.x + off + other.block.size && y >= other.y + off && y < other.y + off + other.block.size) return false;
                }
            }
        }
        return true;
    }

    private Seq<BuildPlan> plans(Schematic schematic, int x, int y, TeamBrain brain){
        Seq<BuildPlan> plans = new Seq<>();
        for(Stile tile : schematic.tiles){
            Block block = tile.block;
            if(block == Blocks.titaniumConveyor && brain.core().items.get(mindustry.content.Items.titanium) == 0) block = Blocks.conveyor;
            plans.add(new BuildPlan(tile.x + x - schematic.width / 2, tile.y + y - schematic.height / 2,
                tile.rotation, block, copyConfig(tile.config)));
        }
        //Complete paid logistics before enabling the machines they serve.
        plans.sort(p -> p.block instanceof GenericCrafter || p.block instanceof UnitFactory || p.block instanceof mindustry.world.blocks.defense.turrets.Turret ? 1 : 0);
        return plans;
    }

    /** Keep a complete one/two-machine cell and its immediate routing; no arbitrary belt stretching. */
    public static Schematic compact(Schematic source, Block wanted, int count){
        Seq<Stile> machines = source.tiles.select(t -> t.block == wanted);
        machines.sort(t -> t.y * 256 + t.x);
        if(machines.isEmpty()) return null;
        Seq<Stile> kept = new Seq<>();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for(int i = 0; i < Math.min(count, machines.size); i++){
            Stile tile = machines.get(i);
            kept.add(copyTile(tile));
            int offset = -(tile.block.size - 1) / 2;
            minX = Math.min(minX, tile.x + offset); minY = Math.min(minY, tile.y + offset);
            maxX = Math.max(maxX, tile.x + offset + tile.block.size - 1); maxY = Math.max(maxY, tile.y + offset + tile.block.size - 1);
        }
        for(Stile tile : source.tiles){
            if(tile.block == wanted || forbidden(tile.block)) continue;
            boolean routing = tile.block instanceof Conveyor || tile.block instanceof Router || tile.block instanceof Junction ||
                tile.block instanceof ItemBridge || tile.block instanceof PowerNode;
            //A cropped machine's old item links no longer describe a complete supply chain.
            //The capacity/commodity-aware planner reconnects its inputs and outputs normally.
            if((wanted instanceof GenericCrafter || wanted instanceof UnitFactory) && !(tile.block instanceof PowerNode)) routing = false;
            if(routing && tile.x >= minX - 1 && tile.x <= maxX + 1 && tile.y >= minY - 1 && tile.y <= maxY + 1) kept.add(copyTile(tile));
        }
        //Do not preserve bridge/node links to removed pieces of the original module.
        kept.removeAll(tile -> tile.block instanceof ItemBridge && tile.config instanceof Point2 &&
            !kept.contains(other -> other.x == tile.x + ((Point2)tile.config).x && other.y == tile.y + ((Point2)tile.config).y));
        for(Stile tile : kept){ if(tile.block instanceof PowerNode) tile.config = null; }
        return normalize(kept, source.tags);
    }

    public static Schematic rotateCopy(Schematic source, int turns){
        Schematic result = copy(source);
        //v146's rotate() returns a shared temporary. Copy after each quarter-turn.
        for(int i = 0; i < turns; i++) result = copy(Schematics.rotate(result, 1));
        return result;
    }

    private static Schematic copy(Schematic source){
        Seq<Stile> tiles = new Seq<>();
        for(Stile tile : source.tiles) tiles.add(copyTile(tile));
        StringMap tags = new StringMap(); tags.putAll(source.tags);
        return new Schematic(tiles, tags, source.width, source.height);
    }

    private static Stile copyTile(Stile tile){ return new Stile(tile.block, tile.x, tile.y, copyConfig(tile.config), tile.rotation); }
    private static Object copyConfig(Object config){
        if(config instanceof Point2){ Point2 point = (Point2)config; return new Point2(point.x, point.y); }
        if(config instanceof Point2[]){
            Point2[] points = (Point2[])config, copy = new Point2[points.length];
            for(int i = 0; i < points.length; i++) copy[i] = new Point2(points[i].x, points[i].y);
            return copy;
        }
        if(config instanceof byte[]) return ((byte[])config).clone();
        return config;
    }

    private static Schematic normalize(Seq<Stile> tiles, StringMap originalTags){
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for(Stile tile : tiles){
            int offset = -(tile.block.size - 1) / 2;
            minX = Math.min(minX, tile.x + offset); minY = Math.min(minY, tile.y + offset);
            maxX = Math.max(maxX, tile.x + offset + tile.block.size - 1); maxY = Math.max(maxY, tile.y + offset + tile.block.size - 1);
        }
        for(Stile tile : tiles){ tile.x -= minX; tile.y -= minY; }
        StringMap tags = new StringMap(); tags.putAll(originalTags);
        return new Schematic(tiles, tags, maxX - minX + 1, maxY - minY + 1);
    }

    public String describe(){
        StringBuilder text = new StringBuilder("已载入 " + entries.size + " 张蓝图，拒绝 " + failures.size + " 个文件。\n" +
            "按当前目标、库存、空地、矿物和方向选择；不适配时回退普通建设。\n" +
            "自动改造：过滤测试源/处理器，单机或双机缩减、旋转、传送带降级。\n" +
            "工厂与防线仍需真实供料、电力；不会原地生成资源。\n\n");
        for(Entry entry : entries){
            text.append(entry.file).append("\n  ").append(entry.author).append(" · ").append(entry.license)
                .append(" · 原尺寸 ").append(entry.raw.width).append('×').append(entry.raw.height)
                .append(" · 已过滤 ").append(entry.removed).append(" 个测试/程序建筑\n");
        }
        for(String failure : failures) text.append("拒绝：").append(failure).append('\n');
        return text.toString();
    }

    public static final class Entry{
        public String file, author, source, license;
        public Schematic raw, safe;
        public int removed;
    }
    private static final class Variant{
        Schematic schematic; String adaptation;
        Variant(Schematic schematic, String adaptation){ this.schematic = schematic; this.adaptation = adaptation; }
    }
    public static final class Placement{
        public Entry entry; public Seq<BuildPlan> plans; public String adaptation; public int rotation;
        public String description(){ return entry.file + " · " + entry.author + " · " + adaptation; }
    }
}
