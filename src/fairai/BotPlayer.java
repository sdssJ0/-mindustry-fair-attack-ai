package fairai;

import arc.func.Prov;
import mindustry.gen.*;
import mindustry.type.UnitType;

/**
 * Player core ships use remote-player interpolation in vanilla, which overwrites a server
 * bot's movement. A serializable entity subclass disables that interpolation on the host;
 * every speed, mining, inventory, construction and combat statistic stays vanilla.
 */
public final class BotPlayer extends Player{
    public BotPlayer(){ super(); }

    @Override public void update(){
        if(dead() && bestCore() != null){
            UnitType type = ((mindustry.world.blocks.storage.CoreBlock)bestCore().block).unitType;
            Prov<? extends Unit> original = type.constructor;
            try{
                type.constructor = BotUnit::new;
                super.update(); //Same player death timer -> CoreBuild.requestSpawn -> playerSpawn.
            }finally{
                type.constructor = original;
            }
        }else super.update();
    }

    public static final class BotUnit extends UnitEntity{
        public static final int entityId = EntityMapping.register("fair-ai-core-unit-v1", BotUnit::new);
        public BotUnit(){ super(); }
        @Override public int classId(){ return entityId; }
        @Override public boolean isRemote(){ return mindustry.Vars.net.client() && super.isRemote(); }
    }
}
