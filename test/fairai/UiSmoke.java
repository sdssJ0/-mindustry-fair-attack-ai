package fairai;

import arc.*;
import arc.backend.sdl.*;
import arc.files.Fi;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.core.GameState.State;
import mindustry.desktop.DesktopLauncher;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.io.SaveIO;

/** Isolated graphical QA launcher. MINDUSTRY_DATA_DIR must point inside test-output. */
public final class UiSmoke{
    public static void main(String[] args){
        if(System.getenv("MINDUSTRY_DATA_DIR") == null) throw new IllegalStateException("Isolated profile required");
        System.setProperty("nodiscord", "true");
        Events.on(ClientLoadEvent.class, event -> Timer.schedule(() -> {
            arc.util.Log.info("[UI QA] Mod directory @; loaded mods @; files @", Vars.modDirectory, Vars.mods.list().size,
                java.util.Arrays.toString(Vars.modDirectory.list()));
            SaveIO.load(new Fi("build/test-maps/fair-ai-0.msav"));
            Vars.state.rules.canGameOver = false;
            Vars.player.team(Vars.state.rules.defaultTeam);
            Vars.player.add();
            Vars.player.team().core().requestSpawn(Vars.player);
            Vars.state.set(State.playing);
            Core.graphics.setTitle("Fair AI - isolated UI check");
            Core.graphics.setWindowedMode(1200, 800);
        }, 2f));
        SdlConfig config = new SdlConfig();
        config.title = "Fair AI - isolated UI check";
        config.width = 1200;
        config.height = 800;
        config.maximized = true;
        new SdlApplication(new DesktopLauncher(new String[0]){
            @Override public mindustry.net.Net.NetProvider getNet(){ return null; }
            @Override public void setup(){
                super.setup();
                Core.settings.put("locale", "zh_CN");
                Core.settings.put("lastBuild", 146);
            }
        }, config);
    }
}
