package fairai;
import arc.files.Fi;
import arc.struct.ObjectIntMap;
import mindustry.game.*;
import mindustry.game.Schematic.Stile;

/** Development-only inventory of real .msch files decoded by the v146 engine. */
public class BlueprintAudit{
    public static void main(String[] args) throws Exception{
        SimulationTest.setup();
        for(Fi file : new Fi("assets/blueprints").list("msch")){
            Schematic schematic = Schematics.read(file);
            ObjectIntMap<String> blocks = new ObjectIntMap<>();
            for(Stile tile : schematic.tiles) blocks.increment(tile.block.name, 0, 1);
            System.out.println(file.name() + " " + schematic.width + "x" + schematic.height + " " + blocks);
        }
        System.exit(0);
    }
}
