package fairai;

import arc.math.Mathf;
import arc.struct.*;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.world.Tile;

/** AI-owned observations. Vanilla FogControl deliberately grants AI teams full vision. */
public final class WorldView{
    public final Team team;
    public final Seq<Building> buildings = new Seq<>();
    public final Seq<Teamc> enemies = new Seq<>();
    private final Bits discovered = new Bits();
    private final Bits visible = new Bits();
    private final ObjectMap<Item, Tile> oreCache = new ObjectMap<>();
    private final ObjectSet<Item> searchedOre = new ObjectSet<>();
    private final ObjectMap<Item,Tile> depositCache=new ObjectMap<>();
    private final ObjectSet<Item> searchedDeposits=new ObjectSet<>();

    public WorldView(Team team){ this.team = team; }

    public void refresh(){
        oreCache.clear();
        searchedOre.clear();
        depositCache.clear();searchedDeposits.clear();
        buildings.clear();
        visible.clear();
        //Groups.build omits sleeping/static buildings, including power nodes and walls.
        for(Building b : team.data().buildings){
            buildings.add(b);
            if(b.fogRadius() > 0) reveal(b.tileX(), b.tileY(), Mathf.ceil(b.fogRadius()));
        }
        for(Unit u : Groups.unit){
            if(u.team == team) reveal(u.tileX(), u.tileY(), Mathf.ceil(u.type.fogRadius));
        }
        discovered.or(visible);
        enemies.clear();
        for(Unit u : Groups.unit){
            if(u.team != team && u.team != Team.derelict && visible(u.tileX(), u.tileY()) && !u.dead){
                enemies.add(u);
            }
        }
        for(Team other : Team.all){
            if(other == team || other == Team.derelict) continue;
            for(Building b : other.data().buildings){
                if(visible(b.tileX(), b.tileY())) enemies.add(b);
            }
        }
    }

    private void reveal(int x, int y, int radius){
        int width = Vars.world.width(), height = Vars.world.height();
        for(int dx = -radius; dx <= radius; dx++){
            for(int dy = -radius; dy <= radius; dy++){
                int tx = x + dx, ty = y + dy;
                if(dx * dx + dy * dy <= radius * radius && tx >= 0 && ty >= 0 && tx < width && ty < height){
                    visible.set(tx + ty * width);
                }
            }
        }
    }

    public boolean visible(int x, int y){
        return !Vars.state.rules.fog || (Vars.world.tile(x, y) != null && visible.get(x + y * Vars.world.width()));
    }

    public boolean known(int x, int y){
        return !Vars.state.rules.fog || !Vars.state.rules.staticFog ||
            (Vars.world.tile(x, y) != null && discovered.get(x + y * Vars.world.width()));
    }

    public Tile ore(Item item, Unit worker){
        if(searchedOre.contains(item)) return oreCache.get(item);
        Tile best = null;
        float score = Float.MAX_VALUE;
        for(Tile tile : Vars.world.tiles){
            if(!known(tile.x, tile.y) || worker.getMineResult(tile) != item) continue;
            float next = worker.dst2(tile) + danger(tile.worldx(), tile.worldy()) * 10000f;
            if(next < score){ best = tile; score = next; }
        }
        searchedOre.add(item);
        if(best != null) oreCache.put(item, best);
        return best;
    }
    /** Drill prospecting depends on discovered terrain, not on the core ship's hand-mining tier. */
    public Tile deposit(Item item,arc.math.geom.Position from){
        if(searchedDeposits.contains(item))return depositCache.get(item);
        searchedDeposits.add(item);
        Tile best=null;float score=Float.MAX_VALUE;
        for(Tile t:Vars.world.tiles) if(known(t.x,t.y) && t.drop()==item){
            float next=arc.math.Mathf.dst2(from.getX(),from.getY(),t.worldx(),t.worldy())+danger(t.worldx(),t.worldy())*10000f;
            if(next<score){score=next;best=t;}
        }
        if(best!=null)depositCache.put(item,best);
        return best;
    }

    public float danger(float x, float y){
        float result = 0;
        for(Teamc enemy : enemies){
            float radius = enemy instanceof Unit ? ((Unit)enemy).range() + 80f :
                enemy instanceof Building ? ((Building)enemy).block.size * 8f + 120f : 120f;
            float distance = Mathf.dst(x, y, enemy.getX(), enemy.getY());
            if(distance < radius) result += 1f - distance / radius;
        }
        return result;
    }

    public int count(mindustry.world.Block block){
        return buildings.count(b -> b.block == block);
    }
}
