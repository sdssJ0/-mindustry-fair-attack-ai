package fairai;
import arc.files.Fi;
import arc.struct.ObjectIntMap;
import mindustry.game.*;
import mindustry.game.Schematic.Stile;

/** Development-only inventory of real .msch files decoded by the v146 engine. */
public class BlueprintAudit{
    public static void main(String[] args) throws Exception{
        SimulationTest.setup();
        int loaded=0,failed=0;
        for(Fi file : new Fi(args.length==0 ? "assets/blueprints":args[0]).list("msch")){
            try{
                Schematic schematic = Schematics.read(file);
                ObjectIntMap<String> blocks = new ObjectIntMap<>();int filtered=0;
                for(Stile tile : schematic.tiles){blocks.increment(tile.block.name, 0, 1);if(BlueprintLibrary.forbidden(tile.block))filtered++;}
                System.out.println(file.name() + " " + schematic.width + "x" + schematic.height + " tiles="+schematic.tiles.size+" filtered="+filtered+" " + blocks);
                loaded++;
            }catch(Exception error){failed++;System.out.println("REJECT "+file.name()+": "+error.getMessage());}
        }
        System.out.println("READ "+loaded+" FAILED "+failed);
        System.exit(0);
    }
}
