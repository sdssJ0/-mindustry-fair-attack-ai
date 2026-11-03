package fairai;

import arc.math.geom.Point2;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.world.Tile;
import mindustry.world.blocks.power.PowerNode;
import java.util.PriorityQueue;

/** Paid node paths around occupied industrial corridors, with vanilla range and insulation checks. */
final class PowerRoutes{
    private final TeamBrain brain;
    PowerRoutes(TeamBrain brain){this.brain=brain;}
    private static final class Step implements Comparable<Step>{
        int x,y,g;float score;Step previous;
        Step(int x,int y,int g,float score,Step previous){this.x=x;this.y=y;this.g=g;this.score=score;this.previous=previous;}
        public int compareTo(Step other){return Float.compare(score,other.score);}
    }
    Seq<BuildPlan> connect(Building outside,Building industry){
        PowerNode node=(PowerNode)Blocks.powerNode;
        Seq<Building> anchors=brain.view.buildings.select(b->b.power!=null && b.power.graph==industry.power.graph &&
            (!(b.block instanceof PowerNode) || b.power.links.size<((PowerNode)b.block).maxNodes));
        if(anchors.isEmpty())return null;
        PriorityQueue<Step> open=new PriorityQueue<>();IntIntMap best=new IntIntMap();IntSet blocked=new IntSet();
        int radius=(int)Math.ceil(node.laserRange)+2;
        for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
            int x=outside.tileX()+dx,y=outside.tileY()+dy;
            if(!brain.construction.place(node,x,y,0))continue;
            Building future=brain.construction.proposed(node,x,y);
            if(!node.linkValid(future,outside) || PowerNode.insulated(future,outside))continue;
            Step s=new Step(x,y,1,1+distance(x,y,anchors)/node.laserRange,null);open.add(s);best.put(Point2.pack(x,y),1);
        }
        int expanded=0;
        while(!open.isEmpty() && expanded++<12000){
            Step s=open.poll();int pos=Point2.pack(s.x,s.y);
            if(s.g!=best.get(pos,Integer.MAX_VALUE))continue;
            Building future=brain.construction.proposed(node,s.x,s.y);
            Building anchor=anchors.find(b->node.linkValid(future,b) && !PowerNode.insulated(future,b));
            if(anchor!=null){
                Seq<BuildPlan> plans=new Seq<>();Building previous=anchor;
                for(Step part=s;part!=null;part=part.previous){
                    int nx=part.previous==null ? outside.tileX():part.previous.x,ny=part.previous==null ? outside.tileY():part.previous.y;
                    plans.add(new BuildPlan(part.x,part.y,0,node,new Point2[]{new Point2(previous.tileX()-part.x,previous.tileY()-part.y),new Point2(nx-part.x,ny-part.y)}));
                    previous=brain.construction.proposed(node,part.x,part.y);
                }
                return plans;
            }
            for(int dx=-radius;dx<=radius;dx++)for(int dy=-radius;dy<=radius;dy++){
                if(dx==0 && dy==0 || dx*dx+dy*dy>(node.laserRange+0.5f)*(node.laserRange+0.5f))continue;
                int x=s.x+dx,y=s.y+dy,key=Point2.pack(x,y),g=s.g+1;
                if(g>=best.get(key,Integer.MAX_VALUE) || blocked.contains(key))continue;
                Tile tile=Vars.world.tile(x,y);
                if(tile==null || !brain.construction.place(node,x,y,0)){blocked.add(key);continue;}
                Building next=brain.construction.proposed(node,x,y);
                if(!node.linkValid(future,next,false) || PowerNode.insulated(future,next))continue;
                best.put(key,g);open.add(new Step(x,y,g,g+distance(x,y,anchors)/node.laserRange,s));
            }
        }
        return null;
    }
    private float distance(int x,int y,Seq<Building> anchors){
        float best=Float.MAX_VALUE;for(Building b:anchors){float dx=x-b.tileX(),dy=y-b.tileY();best=Math.min(best,(float)Math.sqrt(dx*dx+dy*dy));}return best;
    }
}
