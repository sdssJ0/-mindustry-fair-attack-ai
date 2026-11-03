package fairai;

import arc.*;
import arc.math.Mathf;
import arc.scene.event.Touchable;
import arc.struct.*;
import mindustry.Vars;
import mindustry.content.Items;
import mindustry.entities.Units;
import mindustry.gen.*;
import mindustry.type.Item;
import mindustry.ui.Styles;
import mindustry.ui.dialogs.BaseDialog;
import mindustry.world.blocks.defense.turrets.ItemTurret;
import mindustry.world.blocks.defense.turrets.ItemTurret.ItemTurretBuild;
import mindustry.world.blocks.defense.turrets.Turret.AmmoEntry;
import mindustry.world.blocks.power.PowerGraph;

/** Read-only player diagnostics. This data is never provided to the AI's decision inputs. */
public final class EconomyMonitor{
    public static final String hudPreference = "fair-ai-resource-hud";
    private final FairAiMod mod;
    private final ObjectMap<TeamBrain, Snapshot> samples = new ObjectMap<>();
    private float elapsed;
    private boolean hudInstalled;

    public EconomyMonitor(FairAiMod mod){ this.mod = mod; }
    public void clear(){ samples.clear(); elapsed = 0; }

    public void update(float delta){
        elapsed += delta;
        if(elapsed < 60f) return;
        for(TeamBrain brain : mod.brains){
            Snapshot snapshot = samples.get(brain);
            if(snapshot == null){ snapshot = new Snapshot(); samples.put(brain, snapshot); }
            snapshot.sample(brain, elapsed / 60f);
        }
        elapsed = 0;
    }

    public Snapshot snapshot(TeamBrain brain){ return samples.get(brain); }

    public String compactText(){
        if(!mod.active) return "公平 AI 未运行";
        StringBuilder text = new StringBuilder("[accent]公平 AI · 核心库存[]");
        for(TeamBrain brain : mod.brains){
            text.append("\n[accent]").append(brain.team.name).append("[]  ");
            for(Item item : new Item[]{Items.copper, Items.lead, Items.graphite, Items.silicon}){
                text.append(item.localizedName).append(' ').append(brain.core() == null ? 0 : brain.core().items.get(item)).append("  ");
            }
            text.append("\n加工端缓存：").append(Items.coal.localizedName).append(' ').append(processingStock(brain, Items.coal))
                .append("  ").append(Items.sand.localizedName).append(' ').append(processingStock(brain, Items.sand));
            text.append("\n").append(brain.status);
        }
        return text.toString();
    }

    public String detailsText(){
        if(Vars.net.client()) return "当前资源观察面板用于单机或房主端。";
        if(!mod.active) return "公平 AI 尚未运行，请在进攻对局内启用。";
        StringBuilder text = new StringBuilder();
        for(TeamBrain brain : mod.brains){
            text.append("[accent]").append(brain.actor.name).append("[]\n").append(brain.status).append('\n');
            if(brain.core() == null){ text.append("核心已摧毁\n\n"); continue; }
            text.append("核心库存（多个核心共享库存，只计一次）\n");
            Snapshot sample = samples.get(brain);
            for(Item item : Vars.content.items()){
                int amount = brain.core().items.get(item);
                if(Vars.state.rules.hiddenBuildItems.contains(item) && amount == 0) continue;
                text.append(item.localizedName).append(": ").append(amount).append(" / ").append(brain.core().storageCapacity);
                if(sample != null && sample.ready){
                    float rate = sample.netPerMinute.get(item, 0);
                    text.append("  净变化 ").append(rate >= 0 ? "+" : "").append(Mathf.round(rate)).append("/分钟");
                }
                text.append('\n');
            }
            text.append("净变化包含采矿、建造支出与搬运，并非纯产量。\n");
            text.append("加工原料缓存（矿点、运输线及工厂，不含核心和机体）：")
                .append(Items.coal.localizedName).append(' ').append(processingStock(brain, Items.coal)).append("；")
                .append(Items.sand.localizedName).append(' ').append(processingStock(brain, Items.sand)).append('\n');
            text.append("煤和沙直供加工/燃料消费者，不设核心库存目标。\n");
            ObjectSet<PowerGraph> graphs = new ObjectSet<>();
            float produced = 0, needed = 0, stored = 0;
            for(Building build : brain.team.data().buildings){
                if(build.power != null && graphs.add(build.power.graph)){
                    produced += build.power.graph.getLastScaledPowerIn() * 60f;
                    needed += build.power.graph.getLastScaledPowerOut() * 60f;
                    stored += build.power.graph.getLastPowerStored();
                }
            }
            text.append("电网：发电 ").append(Mathf.round(produced)).append(" / 需求 ").append(Mathf.round(needed))
                .append("，储能 ").append(Mathf.round(stored)).append('\n');
            text.append("部队：").append(brain.team.data().unitCount).append("；每种单位上限：").append(Units.getCap(brain.team)).append('\n');
            if(!brain.actor.dead()){
                Unit unit = brain.actor.unit();
                text.append("核心机体背包：").append(unit.stack.amount == 0 ? "空" : unit.item().localizedName + " ×" + unit.stack.amount).append('\n');
            }
            text.append("当前蓝图：").append(brain.lastBlueprint).append('\n');
            text.append(brain.economy.describe()).append('\n');
            text.append(brain.development.describe()).append('\n');
            text.append(brain.retirement.describe()).append('\n');
            text.append(brain.utilities.describe()).append('\n');
            text.append("[accent]炮塔实际弹仓[]（内部弹药单位，含原版弹药倍率）\n");
            int turrets = 0;
            for(Building build : brain.team.data().buildings){
                if(!(build instanceof ItemTurretBuild)) continue;
                ItemTurretBuild turret = (ItemTurretBuild)build;
                text.append(build.block.localizedName).append(" (").append(build.tileX()).append(',').append(build.tileY()).append(")：")
                    .append(turret.totalAmmo).append('/').append(((ItemTurret)build.block).maxAmmo);
                for(AmmoEntry entry : turret.ammo){
                    if(entry instanceof ItemTurret.ItemEntry){
                        text.append("  ").append(((ItemTurret.ItemEntry)entry).item.localizedName).append(' ').append(entry.amount);
                    }
                }
                text.append(turret.totalAmmo > 0 ? "" : "  [scarlet]无弹[]");
                text.append(brain.defense.ammo((ItemTurret)build.block) == null ? "  暂未支持此弹药产线\n" :
                    brain.defense.connected(build) ? "  自动供弹已接通\n" : "  [orange]待建立自动供弹线[]\n");
                turrets++;
            }
            if(turrets == 0) text.append("暂无物品弹药炮塔\n");
            text.append("常规弹药由矿机/加工厂与传送带供应；已有炮塔缺线时优先补线。\n");
            if(!brain.defense.ready() && !brain.defense.issue.isEmpty()) text.append(brain.defense.issue).append('\n');
            text.append("机体仅在缺少产线启动材料且没有自动收入时应急采矿；累计 ")
                .append(Mathf.round(brain.emergencyMiningTicks / 60f)).append(" 秒。\n");
            text.append("[accent]最近真实取料与补给[]\n");
            if(brain.supplyLog.isEmpty()) text.append("尚无记录\n");
            for(String entry : brain.supplyLog) text.append(entry).append('\n');
            text.append('\n');
        }
        return text.toString();
    }

    public int processingStock(TeamBrain brain, Item item){
        int result = 0;
        ObjectSet<mindustry.world.modules.ItemModule> seen = new ObjectSet<>();
        for(Building building : brain.team.data().buildings){
            if(!building.isValid() || building.items == null || building instanceof mindustry.world.blocks.storage.CoreBlock.CoreBuild ||
                brain.core() != null && building.items == brain.core().items || !seen.add(building.items)) continue;
            result += building.items.get(item);
        }
        return result;
    }

    public void installHud(){
        if(hudInstalled || Vars.headless) return;
        hudInstalled = true;
        Vars.ui.hudGroup.fill(root -> {
            root.name = "fair-ai-resource-overlay";
            root.touchable = Touchable.childrenOnly;
            root.top().right().marginTop(100f).marginRight(8f);
            root.visible(() -> mod.active && Vars.state.isGame() && Vars.ui.hudfrag.shown && Core.settings.getBool(hudPreference, true));
            root.table(Styles.black3, panel -> {
                panel.margin(8f);
                panel.label(this::compactText).wrap().width(310f).left().row();
                panel.button("AI 资源 / 弹仓 / 补给记录", this::show).growX().height(38f);
            }).width(330f);
        });
    }

    public void show(){
        BaseDialog dialog = new BaseDialog("AI 资源观察");
        dialog.cont.pane(pane -> pane.label(this::detailsText).wrap().width(680f).left().top()).grow();
        dialog.addCloseButton();
        dialog.show();
    }

    public static final class Snapshot{
        public final ObjectIntMap<Item> amounts = new ObjectIntMap<>();
        public final ObjectFloatMap<Item> netPerMinute = new ObjectFloatMap<>();
        public boolean ready;
        private boolean initialized;
        void sample(TeamBrain brain, float seconds){
            if(brain.core() == null) return;
            for(Item item : Vars.content.items()){
                int amount = brain.core().items.get(item);
                if(initialized) netPerMinute.put(item, (amount - amounts.get(item, 0)) * 60f / Math.max(seconds, 0.001f));
                amounts.put(item, amount);
            }
            ready = initialized;
            initialized = true;
        }
    }
}
