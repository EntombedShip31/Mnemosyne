package com.etbs31.mnemosyne.command;

import com.etbs31.mnemosyne.MnemosyneMod;
import com.etbs31.mnemosyne.capability.MnemosyneData;
import com.etbs31.mnemosyne.registry.ModSchools;
import com.etbs31.mnemosyne.registry.ModSounds;
import com.etbs31.mnemosyne.util.SpellFeedback;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.redspace.ironsspellbooks.api.magic.MagicData;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.api.spells.AbstractSpell;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 忆海调试指令 —— {@code /mnemosyne ...}。
 *
 * <p><b>为什么需要它</b>：忆海学派设了 {@code requiresLearning = true}
 * （{@code ModSchools}），也就是**必须先读忆碑才能抄写忆海法术**。
 * 而忆碑只能从遗迹里拿、每块碑只给一个法术 —— 想测试 16 个法术就得先找到 16 块碑。
 * 这在开发/测试阶段完全不可行，也是"很多法术释放后没效果"很难被发现的原因之一
 * （测试者根本没把法术都试一遍）。
 *
 * <p><b>指令清单</b>：
 * <ul>
 *   <li>{@code /mnemosyne unlock [玩家]} —— <b>解锁全部忆海法术</b>（本次新增，权限 2）</li>
 *   <li>{@code /mnemosyne engrams [玩家]} —— 查看忆格使用情况（权限 0，自己也能看）</li>
 *   <li>{@code /mnemosyne clear [玩家]} —— 清空全部忆格（权限 2，调试用）</li>
 * </ul>
 *
 * <p><b>⚠️ 权限设计</b>：{@code unlock} 与 {@code clear} 需要权限等级 2（OP），
 * 因为它们直接绕过学派的进度门槛。{@code engrams} 是只读的，权限 0 即可 ——
 * 这是忆格 GUI 删除后玩家查看自己忆格状态的**唯一途径**，
 * 如果也要求 OP，普通玩家就完全看不到忆格了。
 *
 * <p><b>为什么用 {@code RegisterCommandsEvent} 而不是 {@code CommandEvent}</b>：
 * 前者是 Forge 1.20.1 注册 Brigadier 指令的标准入口，
 * 在 {@code FORGE} 事件总线上（不是 MOD 总线），所以 {@code @Mod.EventBusSubscriber}
 * 的 {@code bus} 必须写 {@code Bus.FORGE}。
 */
@Mod.EventBusSubscriber(modid = MnemosyneMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MnemosyneCommands {

    private MnemosyneCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(final RegisterCommandsEvent event) {
        final CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("mnemosyne")
                .then(Commands.literal("unlock")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> unlockAll(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> unlockAll(ctx,
                                        EntityArgument.getPlayer(ctx, "target")))))
                .then(Commands.literal("engrams")
                        .executes(ctx -> showEngrams(ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> showEngrams(EntityArgument.getPlayer(ctx, "target")))))
                .then(Commands.literal("clear")
                        .requires(source -> source.hasPermission(2))
                        .executes(ctx -> clearEngrams(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("target", EntityArgument.player())
                                .executes(ctx -> clearEngrams(ctx,
                                        EntityArgument.getPlayer(ctx, "target"))))));
    }

    /**
     * 解锁全部忆海法术。
     *
     * <p>实现要点：走 {@code SpellRegistry.getSpellsForSchool(忆海)} 拿到**当前注册的**全部忆海法术，
     * 而不是硬编码 16 个 id —— 这样以后加第 17 个法术时这条指令自动覆盖，不会漏。
     *
     * <p>学习用 ISS 自己的 {@code SyncedSpellData.learnSpell(...)}（与忆碑同一条路径），
     * 所以解锁后的状态与"正常读碑学会"完全一致：法术轮盘可见、铭刻台可抄、能正常施放。
     */
    private static int unlockAll(final CommandContext<CommandSourceStack> ctx,
                                 final ServerPlayer target) throws CommandSyntaxException {
        final List<AbstractSpell> spells = SpellRegistry.getSpellsForSchool(ModSchools.MEMORY.get());
        if (spells.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(
                    "忆海学派还没有注册任何法术（注册表未就绪？）"));
            return 0;
        }

        final var synced = MagicData.getPlayerMagicData(target).getSyncedData();
        int learned = 0;
        for (final AbstractSpell spell : spells) {
            // learnSpell 对已学会的法术是幂等的，所以可以无脑全学一遍
            synced.learnSpell(spell);
            learned++;
        }

        SpellFeedback.chat(target, Component.translatable("mnemosyne.cmd.unlock_done", learned));
        SpellFeedback.playAt(target.level(), target, ModSounds.UI_LEARN.get(), 1.0F, 1.2F);
        SpellFeedback.castBurst(target.level(), target, SpellFeedback.MEMORY_INDIGO);

        final int total = learned;
        ctx.getSource().sendSuccess(
                () -> Component.translatable("mnemosyne.cmd.unlock_done", total), true);
        return learned;
    }

    /** 查看忆格状态 —— 忆格 GUI 删除后，这是玩家查看忆格的唯一途径。 */
    private static int showEngrams(final ServerPlayer target) {
        final int used = MnemosyneData.getUsedEngrams(target);
        final int max = MnemosyneData.getMaxEngrams(target);
        SpellFeedback.chat(target, Component.translatable("mnemosyne.cmd.engrams", used, max));

        final var entries = MnemosyneData.getEngrams(target);
        if (entries.isEmpty()) {
            SpellFeedback.chat(target, Component.translatable("mnemosyne.cmd.engrams_empty"));
        } else {
            for (final var entry : entries) {
                SpellFeedback.chat(target, Component.literal(" · ")
                        .append(entry.describe()));
            }
        }
        return used;
    }

    /** 清空忆格（调试用）。 */
    private static int clearEngrams(final CommandContext<CommandSourceStack> ctx,
                                    final ServerPlayer target) {
        final int had = MnemosyneData.getUsedEngrams(target);
        MnemosyneData.clearAll(target);
        SpellFeedback.chat(target, Component.translatable("mnemosyne.cmd.clear_done", had));
        ctx.getSource().sendSuccess(
                () -> Component.translatable("mnemosyne.cmd.clear_done", had), true);
        return had;
    }
}
