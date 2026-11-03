package fairai;

import arc.math.geom.*;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.*;
import mindustry.entities.units.BuildPlan;
import mindustry.gen.Building;
import mindustry.world.*;
import mindustry.world.blocks.liquid.Conduit;
import mindustry.world.blocks.production.*;

/** Single-water lines use normal conduit pressure and direction; no liquid teleportation. */
public final class LiquidLogistics{
    private final TeamBrain brain;
    LiquidLogistics(TeamBrain brain){this.brain=brain;}
    static boolean inside(BuildPlan p,int x,int y){
        int o=-(p.block.size-1)/2;
        return x>=p.x+o && y>=p.y+o && x<p.x+o+p.block.size && y<p.y+o+p.block.size;
    }
    private boolean adjacent(BuildPlan p,int x,int y){
        for(Point2 e:Edges.getEdges(p.block.size)) if(p.x+e.x==x && p.y+e.y==y) return true;
        return false;
    }
    private boolean cell(int x,int y,BuildPlan source,BuildPlan target){
        if(inside(source,x,y) || inside(target,x,y) || !brain.construction.place(Blocks.conduit,x,y,0)) return false;
        for(Point2 e:Geometry.d4){
            Building b=Vars.world.build(x+e.x,y+e.y);
            if(b!=null && b.liquids!=null && b.liquids.currentAmount()>0.1f && b.liquids.current()!=Liquids.water) return false;
        }
        return true;
    }
    public Seq<BuildPlan> route(BuildPlan source,BuildPlan target){
        for(Point2 e:Edges.getEdges(source.block.size)) if(inside(target,source.x+e.x,source.y+e.y)) return new Seq<>();
        int w=Vars.world.width(), h=Vars.world.height(); int[] parent=new int[w*h];
        java.util.Arrays.fill(parent,-2); IntQueue queue=new IntQueue();
        for(Point2 e:Edges.getEdges(target.block.size)){
            int x=target.x+e.x,y=target.y+e.y;
            if(!cell(x,y,source,target)) continue;
            parent[x+y*w]=-1;queue.addLast(x+y*w);
        }
        int end=-1;
        while(queue.size>0){
            int at=queue.removeFirst(),x=at%w,y=at/w;
            if(adjacent(source,x,y)){end=at;break;}
            for(Point2 e:Geometry.d4){
                int nx=x+e.x,ny=y+e.y,n=nx+ny*w;
                if(nx<0 || ny<0 || nx>=w || ny>=h || parent[n]!=-2 || Math.abs(nx-target.x)+Math.abs(ny-target.y)>50) continue;
                if(cell(nx,ny,source,target)){parent[n]=at;queue.addLast(n);}
            }
        }
        if(end<0) return null;
        Seq<BuildPlan> plans=new Seq<>();
        for(int at=end;at>=0;at=parent[at]){
            int x=at%w,y=at/w,next=parent[at],rot;
            if(next<0){int o=-(target.block.size-1)/2;rot=x<target.x+o ? 0 : x>=target.x+o+target.block.size ? 2 : y<target.y+o ? 1 : 3;}
            else{int dx=next%w-x,dy=next/w-y;rot=dx>0 ? 0 : dy>0 ? 1 : dx<0 ? 2 : 3;}
            plans.add(new BuildPlan(x,y,rot,Blocks.conduit));
        }
        plans.reverse();return plans;
    }
    public boolean feeds(Building source,Building target){
        if(source==null || target==null || source.team!=target.team) return false;
        if(source.proximity.contains(target,true)) return true;
        IntSet seen=new IntSet();
        for(Point2 e:Edges.getEdges(source.block.size)){
            Building b=Vars.world.build(source.tileX()+e.x,source.tileY()+e.y);
            while(b!=null && b.team==brain.team && b.block instanceof Conduit && seen.add(b.pos())){
                if(b.liquids.currentAmount()>0.1f && b.liquids.current()!=Liquids.water) break;
                b=b.front();if(b==target) return true;
            }
        }
        return false;
    }
    public float waterRate(Block block,Tile tile){
        if(!(block instanceof Pump) || tile==null) return 0;
        Pump pump=(Pump)block; int o=-(block.size-1)/2; float amount=0;
        for(int dx=0;dx<block.size;dx++) for(int dy=0;dy<block.size;dy++){
            Tile t=Vars.world.tile(tile.x+o+dx,tile.y+o+dy);if(t==null) return 0;
            if(block instanceof SolidPump){
                SolidPump p=(SolidPump)block;
                if(!t.floor().isLiquid) amount+=(p.baseEfficiency+(p.attribute==null ? 0 : t.floor().attributes.get(p.attribute)))/(block.size*block.size);
            }else if(t.floor().liquidDrop==Liquids.water) amount+=t.floor().liquidMultiplier;
            else if(t.floor().liquidDrop!=null) return 0;
        }
        if(block instanceof SolidPump && ((SolidPump)block).attribute!=null) amount+=((SolidPump)block).attribute.env();
        return Math.max(0,amount)*pump.pumpAmount*60f;
    }
}
