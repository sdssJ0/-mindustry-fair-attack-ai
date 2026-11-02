package fairai;

import arc.*;
import arc.files.Fi;
import arc.struct.Seq;
import arc.util.*;
import mindustry.Vars;
import mindustry.content.Planets;
import mindustry.game.*;
import mindustry.game.EventType.*;
import mindustry.gen.Icon;
import mindustry.mod.Mod;
import mindustry.ui.dialogs.BaseDialog;

/** Host-authoritative, opt-in custom attack mode. Does not alter campaign or PvP. */
public class FairAiMod extends Mod{
    public static final String enabledTag = "fair-ai.enabled";
    public static final String preference = "fair-ai-next-attack";
    public final Seq<TeamBrain> brains = new Seq<>();
    public final EconomyMonitor monitor = new EconomyMonitor(this);
    public final BlueprintLibrary blueprints = new BlueprintLibrary();
    public boolean active;
    private boolean pending;
    private String error;

    public FairAiMod(){
        //Register before maps/saves are read, and identically on host and clients.
        int id = BotPlayer.BotUnit.entityId;
    }

    @Override public void init(){
        reloadBlueprints();
        Events.on(ResetEvent.class, event -> reset());
        Events.on(WorldLoadEvent.class, event -> {
            reset();
            pending = "true".equals(Vars.state.rules.tags.get(enabledTag)) || Core.settings.getBool(preference, false);
        });
        Events.run(Trigger.update, this::update);
        Events.on(ClientLoadEvent.class, event -> monitor.installHud());
        if(!Vars.headless){
            Vars.ui.settings.addCategory("公平 AI 进攻", Icon.units, table -> {
                table.checkPref(preference, false);
                table.checkPref(EconomyMonitor.hudPreference, true);
                table.row();
                table.button("查看 AI 资源、弹药与补给记录", monitor::show).width(420f).height(55f);
                table.row();
                table.button("蓝图库 / 重新读取本地蓝图", this::showBlueprints).width(420f).height(55f);
                table.row();
                table.button("启用本局 / 查看 AI 状态", this::showStatus).width(420f).height(60f);
                table.row();
                table.add("仅用于赛普罗自定义进攻图；全部科技解锁。\n启用后关闭免费波次和敌方资源特权。\n原有建筑与库存保留，不能保证开局资产相等。")
                    .wrap().width(420f).pad(12f);
            });
        }
    }

    public boolean activate(){
        if(active) return true;
        if(Vars.net.client()){ error = "请由房主启用这个模式。"; return false; }
        if(!Vars.state.isGame() || Vars.state.isCampaign() || Vars.state.isEditor() || Vars.state.rules.pvp || !Vars.state.rules.attackMode){
            error = "请先进入自定义游戏的进攻地图。"; return false;
        }
        if(Vars.state.rules.planet == Planets.erekir){
            error = "此原型先支持赛普罗；埃里克尔的物流与热量系统尚未实现。"; return false;
        }
        boolean enemyCore = false;
        for(Team team : Team.all){
            if(team != Vars.state.rules.defaultTeam && team != Team.derelict && team.core() != null) enemyCore = true;
        }
        if(!enemyCore){ error = "地图没有敌方核心，无法启动进攻 AI。"; return false; }
        applyFairRules();
        Vars.state.rules.tags.put(enabledTag, "true");
        Vars.state.rules.modeName = "公平 AI 进攻";
        for(Team team : Team.all){
            if(team != Vars.state.rules.defaultTeam && team != Team.derelict && team.core() != null){
                brains.add(new TeamBrain(team, blueprints));
            }
        }
        active = true;
        error = null;
        Log.info("[Fair AI] Activated for @ enemy team(s); existing buildings and inventory preserved.", brains.size);
        if(Vars.net.server()) mindustry.gen.Call.setRules(Vars.state.rules);
        return true;
    }

    public void applyFairRules(){
        Rules rules = Vars.state.rules;
        rules.infiniteResources = false;
        rules.waves = false;
        rules.waveTimer = false;
        //v146 Units.getCap grants waveTeam an unconditional infinite cap, even with
        //cheat/infiniteResources disabled. No real opponent is a wave team in this mode.
        rules.waveTeam = Team.derelict;
        rules.coreDestroyClear = false;
        rules.buildCostMultiplier = rules.buildSpeedMultiplier = 1f;
        rules.unitBuildSpeedMultiplier = rules.unitCostMultiplier = 1f;
        rules.unitDamageMultiplier = rules.unitHealthMultiplier = 1f;
        rules.blockHealthMultiplier = rules.blockDamageMultiplier = 1f;
        for(Team team : Team.all){
            Rules.TeamRule tr = rules.teams.get(team);
            tr.cheat = tr.infiniteResources = tr.infiniteAmmo = false;
            tr.buildAi = tr.rtsAi = tr.aiCoreSpawn = false;
            tr.buildSpeedMultiplier = tr.unitBuildSpeedMultiplier = tr.unitCostMultiplier = 1f;
            tr.unitDamageMultiplier = tr.unitHealthMultiplier = tr.unitCrashDamageMultiplier = 1f;
            tr.blockHealthMultiplier = tr.blockDamageMultiplier = 1f;
        }
    }

    private void update(){
        //World.loadMap validates the original wave team's core *after* WorldLoadEvent.
        //Activate at the first game update, before vanilla BaseBuilderAI can run.
        if(pending && Vars.state.isGame()){
            pending = false;
            activate();
        }
        if(!active || Vars.net.client() || !Vars.state.isGame() || Vars.state.isPaused() || Vars.state.gameOver) return;
        try{
            //Other rule changes must not silently restore the original enemy privileges.
            Vars.state.rules.infiniteResources = false;
            Vars.state.rules.waves = false;
            for(TeamBrain brain : brains){
                Rules.TeamRule rule = brain.team.rules();
                rule.cheat = rule.infiniteResources = rule.infiniteAmmo = rule.buildAi = rule.aiCoreSpawn = false;
            }
            for(TeamBrain brain : brains) brain.update();
            monitor.update(Time.delta);
        }catch(Throwable failure){
            //Surface the first failure; never turn resources back on to "repair" the bot.
            active = false;
            error = "AI 已停止：" + failure.getClass().getSimpleName() + ": " + failure.getMessage();
            Log.err("[Fair AI] Controller stopped", failure);
        }
    }

    private void reset(){
        for(TeamBrain brain : brains) brain.dispose();
        brains.clear();
        monitor.clear();
        active = false;
        pending = false;
        error = null;
    }

    private void showStatus(){
        BaseDialog dialog = new BaseDialog("公平 AI 进攻");
        if(!active){
            dialog.cont.add(error == null ? "在自定义进攻地图中启用本局，或勾选设置后重新进入地图。" : error).wrap().width(540f).row();
            dialog.cont.button("启用本局", () -> { activate(); dialog.hide(); showStatus(); }).width(260f).height(55f).row();
        }else{
            for(TeamBrain brain : brains){
                dialog.cont.add(brain.actor.name + "：" + brain.status + "\n已完成建设任务：" + brain.finishedProjects)
                    .wrap().width(540f).pad(8f).row();
            }
            dialog.cont.button("查看资源、弹仓与补给记录", () -> { dialog.hide(); monitor.show(); }).width(360f).height(50f).row();
        }
        dialog.addCloseButton();
        dialog.show();
    }

    public void reloadBlueprints(){
        mindustry.mod.Mods.LoadedMod loaded = Vars.mods.getMod(FairAiMod.class);
        Fi bundled = loaded == null ? new Fi("assets/blueprints") : loaded.root.child("blueprints");
        Fi custom = loaded == null ? new Fi("test-output/custom-blueprints") : Vars.dataDirectory.child("fair-ai-blueprints");
        blueprints.load(bundled, custom);
    }

    private void showBlueprints(){
        reloadBlueprints();
        BaseDialog dialog = new BaseDialog("AI 蓝图库");
        dialog.cont.pane(table -> table.add(blueprints.describe()).wrap().width(680f).left()).grow();
        dialog.addCloseButton();
        dialog.show();
    }
}
