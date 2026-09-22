package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.RvlAddonsClient
import net.eenda.rvladdons.client.coma.FabricComaItemAdapter
import net.eenda.rvladdons.core.RvlAddonsTrace
import net.eenda.rvladdons.feature.cooldown.CooldownEntry
import net.eenda.rvladdons.feature.cooldown.PendingSkillAction
import net.eenda.rvladdons.feature.cooldown.SkillHand
import net.eenda.rvladdons.feature.cooldown.SkillSourceSnapshot
import net.eenda.rvladdons.feature.cooldown.SkillSourceType
import net.eenda.rvladdons.feature.cooldown.SkillTrigger
import net.minecraft.client.MinecraftClient
import net.minecraft.item.ItemStack
import net.minecraft.util.Hand

/** Client-side skill discovery and server-CD correlation for one Fabric target. */
object CooldownController {
    private val pending = CooldownPendingActionStore()
    private val runtime = CooldownRuntimeStore(pending)
    private val actionTracker = CooldownActionTracker()
    private val serverCorrelation = CooldownServerCorrelation(
        runtime.registry,
        pending,
        runtime.icons,
        ::stackForSource,
        { runtime.lastSkillKey },
        { runtime.lastSkillKey = it }
    )

    fun observeAction(baseTrigger: SkillTrigger, hand: Hand) {
        if (!RvlAddonsClient.isGameplayServerActive()) return
        val client = MinecraftClient.getInstance()
        val player = client.player ?: return
        val now = System.currentTimeMillis()
        actionTracker.markClick(now)
        if (actionTracker.jumpWasRecent(now)) {
            cancelGesture(SkillTrigger.JUMP, now)
        }
        val allowedTriggers = actionTracker.clickTriggers(
            baseTrigger,
            player.isSneaking,
            actionTracker.jumpWasRecent(now)
        )
        val skillHand = actionTracker.toSkillHand(hand)
        val stack = if (hand == Hand.OFF_HAND) player.offHandStack else player.mainHandStack
        val heldSourceType = CooldownSkillDiscovery.heldSourceType(stack, skillHand)
        val activeSetSkills = CooldownSkillDiscovery.discoverActiveSetProfiles(player)
        val heldSkills = CooldownSkillDiscovery.discoverProfiles(stack, sourceType = heldSourceType)
            .filter {
                it.descriptor.trigger in allowedTriggers &&
                    !CooldownSkillDiscovery.isSetBonusDescriptor(it.descriptor)
            }
        val setSkills = activeSetSkills.filter { it.descriptor.trigger in allowedTriggers }
        val candidates = (heldSkills + setSkills).distinctBy { it.descriptor.skillKey }

        if (candidates.isEmpty()) {
            queueAction(
                PendingSkillAction(
                    at = now,
                    trigger = allowedTriggers.first(),
                    source = CooldownSkillDiscovery.sourceSnapshot(stack, null, heldSourceType, skillHand),
                    hand = skillHand
                ),
                stack
            )
        } else {
            candidates.forEach { candidate ->
                queueAction(
                    PendingSkillAction(
                        at = now,
                        trigger = candidate.descriptor.trigger,
                        source = CooldownSkillDiscovery.sourceSnapshot(candidate.stack, candidate.descriptor, candidate.sourceType, skillHand),
                        hand = skillHand
                    ),
                    candidate.stack
                )
            }
        }

        pending.prune(now)
        RvlAddonsTrace.log(
            "cooldown",
            "action triggers=${allowedTriggers.joinToString { it.name }} hand=${skillHand.name} " +
                "candidates=${candidates.joinToString { it.descriptor.skillKey }} item=${compactName(stack)}"
        )
        pending.actions.filter { it.at == now }.forEach(runtime::startEstimated)
    }

    fun observeInputTransitions(client: MinecraftClient) {
        actionTracker.observeInputTransitions(
            client,
            RvlAddonsClient.isGameplayServerActive(),
            ::observeGesture
        )
    }

    fun observeServerMessage(message: String, actionBar: Boolean = true) {
        if (!RvlAddonsClient.isGameplayServerActive()) return
        serverCorrelation.observe(message, actionBar)
    }

    fun clear() {
        runtime.clear()
        actionTracker.clear()
    }

    fun refreshProfiles() {
        if (!RvlAddonsClient.isGameplayServerActive()) return
        MinecraftClient.getInstance().player?.let(CooldownSkillDiscovery::discoverActiveSetProfiles)
    }

    fun removeProfile(skillKey: String) {
        runtime.removeProfile(skillKey)
    }

    fun active(now: Long = System.currentTimeMillis()): List<CooldownEntry> {
        return runtime.active(now)
    }

    fun hasActive(now: Long = System.currentTimeMillis()): Boolean = runtime.hasActive(now)

    internal fun iconFor(skillKey: String): ItemStack? = runtime.iconFor(skillKey)

    private fun observeGesture(trigger: SkillTrigger, player: net.minecraft.client.network.ClientPlayerEntity) {
        if (!actionTracker.canActivateSkill(player)) {
            RvlAddonsTrace.log("cooldown", "gesture-ignored-not-grounded trigger=${trigger.name}")
            return
        }
        val mainSourceType = CooldownSkillDiscovery.heldSourceType(player.mainHandStack, SkillHand.MAIN)
        val offSourceType = CooldownSkillDiscovery.heldSourceType(player.offHandStack, SkillHand.OFF)
        val activeSetSkills = CooldownSkillDiscovery.discoverActiveSetProfiles(player)
            .filter { it.descriptor.trigger == trigger }
        val candidates = (activeSetSkills +
            CooldownSkillDiscovery.discoverProfiles(player.mainHandStack, sourceType = mainSourceType).filter { it.descriptor.trigger == trigger } +
            CooldownSkillDiscovery.discoverProfiles(player.offHandStack, sourceType = offSourceType).filter { it.descriptor.trigger == trigger })
            .distinctBy { it.descriptor.skillKey }
        val now = System.currentTimeMillis()
        candidates.forEach { candidate ->
            val hand = when {
                FabricComaItemAdapter.sameItem(player.mainHandStack, candidate.stack) -> SkillHand.MAIN
                FabricComaItemAdapter.sameItem(player.offHandStack, candidate.stack) -> SkillHand.OFF
                else -> SkillHand.UNKNOWN
            }
            queueAction(
                PendingSkillAction(
                    at = now,
                    trigger = trigger,
                    source = CooldownSkillDiscovery.sourceSnapshot(candidate.stack, candidate.descriptor, candidate.sourceType, hand),
                    hand = hand
                ),
                candidate.stack
            )
        }
        pending.prune(now)
        pending.actions.filter { it.at == now }.forEach(runtime::startEstimated)
    }

    private fun cancelGesture(trigger: SkillTrigger, now: Long) {
        pending.cancelGesture(trigger, now).forEach { action ->
                action.source?.skillKey?.let(runtime::removeEstimated)
            }
    }

    private fun queueAction(action: PendingSkillAction, icon: ItemStack? = null) {
        pending.queue(action, icon, ::sourceStack).forEach { old ->
            old.source?.skillKey?.let(runtime::removeEstimated)
        }
    }

    private fun sourceStack(action: PendingSkillAction): ItemStack? {
        val player = MinecraftClient.getInstance().player ?: return null
        return when (action.hand) {
            SkillHand.OFF -> player.offHandStack
            SkillHand.MAIN -> player.mainHandStack
            SkillHand.UNKNOWN -> null
        }
    }

    private fun stackForSource(source: SkillSourceSnapshot): ItemStack? {
        val player = MinecraftClient.getInstance().player ?: return null
        return when (source.hand) {
            SkillHand.OFF -> player.offHandStack
            SkillHand.MAIN -> player.mainHandStack
            SkillHand.UNKNOWN -> null
        }
    }

    private fun compactName(stack: ItemStack): String = if (stack.isEmpty) "empty" else stack.name.string.take(80)

}
