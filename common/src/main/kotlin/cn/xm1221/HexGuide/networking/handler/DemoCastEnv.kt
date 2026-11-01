package cn.xm1221.HexGuide.networking.handler

import at.petrak.hexcasting.api.casting.eval.vm.CastingImage
import at.petrak.hexcasting.api.casting.eval.env.StaffCastEnv
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand

/**
 * 演示用施法环境：把本地 CastingImage 上传到服务端运行。
 * 继承 StaffCastEnv（获得完整的施法语义：施法者、音效），但：
 * - 不写玩家的法杖栈（图像由调用方显式传入 CastingVM，不经过 getStaffcastVM）
 * - 屏蔽 postCast 的螺旋图案（演示在书内进行，不需要世界内特效）
 * - 媒质从虚拟池扣除（不消耗背包/法杖媒质，也不会过载扣血）
 *
 * 注意：图案仍会「真执行」——可能改世界（放置/破坏方块、影响实体）。
 * 公开服请配合 SecurityGuards 的速率与 iota 白名单；不要在无信任客户端环境下依赖演示安全。
 */
class DemoCastEnv(caster: ServerPlayer, hand: InteractionHand) : StaffCastEnv(caster, hand) {
    override fun postCast(image: CastingImage) {
        // 不发送 MsgNewSpiralPatternsS2C（不产生世界内螺旋图案）
    }

    override fun extractMediaEnvironment(cost: Long, simulate: Boolean): Long {
        // 返回 0 = 费用已全部支付；演示不消耗真实媒质
        return 0
    }
}
