package net.eenda.rvladdons.client.cooldown

import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.Hand
import net.eenda.rvladdons.feature.cooldown.SkillHand
import net.eenda.rvladdons.feature.cooldown.SkillTrigger

/** Owns client input edges and movement context used to classify skill actions. */
internal class CooldownActionTracker {
    companion object {
        const val HAND_DUPLICATE_WINDOW_MS = 140L
        private const val DOUBLE_SNEAK_WINDOW_MS = 350L
        private const val GROUNDED_SNAPSHOT_GRACE_MS = 75L
    }

    private var wasSneaking = false
    private var wasJumping = false
    private var lastSneakPressAt = 0L
    private var lastJumpPressAt = 0L
    private var lastClickAt = 0L
    private var lastGroundedAt = 0L

    fun clear() {
        wasSneaking = false
        wasJumping = false
        lastSneakPressAt = 0L
        lastJumpPressAt = 0L
        lastClickAt = 0L
        lastGroundedAt = 0L
    }

    fun markClick(now: Long) { lastClickAt = now }

    fun jumpWasRecent(now: Long): Boolean = now - lastJumpPressAt in 0..HAND_DUPLICATE_WINDOW_MS

    fun observeInputTransitions(
        client: MinecraftClient,
        gameplayActive: Boolean,
        onGesture: (SkillTrigger, ClientPlayerEntity) -> Unit
    ) {
        if (!gameplayActive) {
            wasSneaking = false
            wasJumping = false
            return
        }
        val player = client.player ?: return
        val now = System.currentTimeMillis()
        if (isPhysicallyGrounded(player)) lastGroundedAt = now
        val grounded = canActivateSkill(player)
        val sneaking = player.isSneaking
        if (sneaking && !wasSneaking) {
            val trigger = if (now - lastSneakPressAt <= DOUBLE_SNEAK_WINDOW_MS) SkillTrigger.DOUBLE_SNEAK else SkillTrigger.SNEAK_HOLD
            if (grounded) onGesture(trigger, player)
            lastSneakPressAt = now
        }
        val jumping = client.options.jumpKey.isPressed
        if (jumping && !wasJumping) {
            lastJumpPressAt = now
            if (grounded && now - lastClickAt > HAND_DUPLICATE_WINDOW_MS) onGesture(SkillTrigger.JUMP, player)
        }
        wasSneaking = sneaking
        wasJumping = jumping
    }

    fun canActivateSkill(player: ClientPlayerEntity): Boolean {
        val now = System.currentTimeMillis()
        if (isPhysicallyGrounded(player)) {
            lastGroundedAt = now
            return true
        }
        return now - lastGroundedAt <= GROUNDED_SNAPSHOT_GRACE_MS && player.velocity.y <= 0.0
    }

    fun clickTriggers(base: SkillTrigger, sneaking: Boolean, jumping: Boolean): List<SkillTrigger> {
        val clickTrigger = when (base) {
            SkillTrigger.LEFT_CLICK -> if (sneaking) SkillTrigger.SHIFT_LEFT else SkillTrigger.LEFT_CLICK
            SkillTrigger.RIGHT_CLICK -> if (sneaking) SkillTrigger.SHIFT_RIGHT else SkillTrigger.RIGHT_CLICK
            else -> base
        }
        if (!jumping) return listOf(clickTrigger)
        val jumpTrigger = when (base) {
            SkillTrigger.LEFT_CLICK -> SkillTrigger.JUMP_LEFT
            SkillTrigger.RIGHT_CLICK -> SkillTrigger.JUMP_RIGHT
            else -> return listOf(clickTrigger)
        }
        return listOf(clickTrigger, jumpTrigger)
    }

    fun toSkillHand(hand: Hand): SkillHand = when (hand) {
        Hand.MAIN_HAND -> SkillHand.MAIN
        Hand.OFF_HAND -> SkillHand.OFF
    }

    private fun isPhysicallyGrounded(player: ClientPlayerEntity): Boolean =
        player.isOnGround || (player.verticalCollision && player.velocity.y <= 0.0)
}
