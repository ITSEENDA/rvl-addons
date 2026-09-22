package net.eenda.rvladdons.client.cooldown

import net.eenda.rvladdons.client.coma.ComaSwapController
import net.eenda.rvladdons.client.coma.FabricComaItemAdapter
import net.eenda.rvladdons.core.RvlAddonsConfigStore
import net.eenda.rvladdons.core.RvlTextMatcher
import net.eenda.rvladdons.feature.cooldown.SkillDescriptor
import net.eenda.rvladdons.feature.cooldown.SkillHand
import net.eenda.rvladdons.feature.cooldown.SkillProfileStore
import net.eenda.rvladdons.feature.cooldown.SkillSourceSnapshot
import net.eenda.rvladdons.feature.cooldown.SkillSourceType
import net.eenda.rvladdons.feature.cooldown.SkillTextParser
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.util.Hand

/** Discovers item and set skills from the active loadout sources. */
object CooldownSkillDiscovery {
    data class DiscoveredSkill(
        val descriptor: SkillDescriptor,
        val stack: ItemStack,
        val sourceType: SkillSourceType
    )

    private data class SetCandidate(
        val logicalKey: String,
        val setId: String,
        val setName: String,
        val pieces: List<ItemStack>,
        val sourceType: SkillSourceType
    )

    fun discoverActiveSetProfiles(player: ClientPlayerEntity): List<DiscoveredSkill> {
        val armor = player.inventory.armor.filterNot(ItemStack::isEmpty)
        SkillProfileStore.removeLegacyArmorItemProfiles(armor.map { it.name.string }.toSet())
        val sets = RvlAddonsConfigStore.config.comaSets.withIndex().filter { it.value.enabled }
        val livePieces = (armor + listOf(player.mainHandStack, player.offHandStack))
            .filterNot(ItemStack::isEmpty)
        val comaStacks = ComaSwapController.hudStacks(MinecraftClient.getInstance())
        val comaMatch = sets.firstOrNull { (_, set) -> matchesRoleRules(comaStacks, set.rules()) }
        val candidates = mutableListOf<SetCandidate>()

        livePieces.groupBy(::setGroupKey).filterKeys { it != null }.forEach { (key, pieces) ->
            if (pieces.size < 2) return@forEach
            val configured = sets.firstOrNull { (_, set) -> matchesRules(pieces, set.rules()) }
            if (configured == null && comaMatch != null && overlapsRules(pieces, comaMatch.value.rules())) {
                return@forEach
            }
            val logicalKey = key!!
            val setId = configured?.let { comaSetId(it.index, it.value.rules()) } ?: logicalKey
            val setName = configured?.value?.name ?: resolveSetName(pieces, null)
            candidates += SetCandidate(
                logicalKey,
                setId,
                setName,
                pieces,
                if (configured != null) SkillSourceType.COMA_SET else SkillSourceType.EQUIPPED_SET
            )
        }

        val comaPieces = comaStacks.filterNotNull().filterNot(ItemStack::isEmpty)
        if (comaPieces.isNotEmpty()) {
            comaPieces.groupBy(::setGroupKey).filterKeys { it != null }.forEach { (key, pieces) ->
                if (pieces.size < 2) return@forEach
                val logicalKey = key!!
                val setId = comaMatch?.let { comaSetId(it.index, it.value.rules()) } ?: "COMA:$logicalKey"
                val setName = comaMatch?.value?.name ?: resolveSetName(pieces, null)
                candidates += SetCandidate(logicalKey, setId, setName, pieces, SkillSourceType.COMA_SET)
            }
        }

        val result = candidates.groupBy { it.logicalKey }.values.mapNotNull { sameSet ->
            val selected = sameSet.maxWithOrNull(
                compareBy<SetCandidate> { it.pieces.size }.thenBy { sourcePriority(it.sourceType) }
            ) ?: return@mapNotNull null
            SkillProfileStore.removeLegacyEquippedSetProfiles(
                selected.pieces.mapNotNull(FabricComaItemAdapter::extractSetName).toSet()
            )
            discoverSetProfiles(selected.pieces, selected.setId, selected.setName, selected.sourceType)
        }.flatten()
        SkillProfileStore.deduplicate()
        return result.distinctBy { it.descriptor.skillKey }
    }

    fun discoverProfiles(
        stack: ItemStack,
        forcedSetId: String? = null,
        sourceType: SkillSourceType = SkillSourceType.ITEM
    ): List<DiscoveredSkill> {
        if (stack.isEmpty || FabricComaItemAdapter.isArmor(stack)) return emptyList()
        val snapshot = FabricComaItemAdapter.snapshot(stack)
        val setId = forcedSetId ?: FabricComaItemAdapter.extractSetName(stack)
        return SkillTextParser.parse(
            snapshot.itemId,
            stack.name.string,
            snapshot.fingerprint,
            setId,
            FabricComaItemAdapter.loreLines(stack)
        ).map { descriptor ->
            val profile = SkillProfileStore.register(descriptor, sourceType)
            DiscoveredSkill(normalizeDescriptor(descriptor, profile), stack.copy(), sourceType)
        }
    }

    fun sourceSnapshot(
        stack: ItemStack,
        descriptor: SkillDescriptor?,
        sourceType: SkillSourceType,
        hand: SkillHand
    ): SkillSourceSnapshot? {
        if (stack.isEmpty) return null
        val snapshot = FabricComaItemAdapter.snapshot(stack)
        return SkillSourceSnapshot(
            itemId = snapshot.itemId,
            displayName = descriptor?.displayName ?: stack.name.string,
            fingerprint = snapshot.fingerprint,
            setId = descriptor?.setId ?: FabricComaItemAdapter.extractSetName(stack),
            skillKey = descriptor?.skillKey,
            cooldownHintMs = descriptor?.cooldownHintMs,
            sourceType = sourceType,
            hand = hand,
            skillType = descriptor?.skillType ?: net.eenda.rvladdons.feature.cooldown.SkillType.UNKNOWN,
            setBonusLevel = descriptor?.setBonusLevel
        )
    }

    fun heldSourceType(stack: ItemStack, hand: SkillHand): SkillSourceType = when {
        !FabricComaItemAdapter.isWeapon(stack) -> SkillSourceType.ITEM
        hand == SkillHand.MAIN -> SkillSourceType.MAIN_HAND_WEAPON
        hand == SkillHand.OFF -> SkillSourceType.OFF_HAND_WEAPON
        else -> SkillSourceType.ITEM
    }

    fun isSetBonusDescriptor(descriptor: SkillDescriptor): Boolean =
        descriptor.setId != null && descriptor.setBonusLevel != null

    private fun discoverSetProfiles(
        pieces: List<ItemStack>,
        setId: String,
        setName: String,
        sourceType: SkillSourceType
    ): List<DiscoveredSkill> {
        SkillProfileStore.removeLegacyItemProfiles(
            pieces.map { FabricComaItemAdapter.snapshot(it).fingerprint }.toSet()
        )
        val parsed = pieces.flatMap { stack ->
            val snapshot = FabricComaItemAdapter.snapshot(stack)
            SkillTextParser.parse(
                snapshot.itemId,
                stack.name.string,
                snapshot.fingerprint,
                setId,
                FabricComaItemAdapter.loreLines(stack)
            ).filter { descriptor ->
                val tier = descriptor.setBonusLevel
                tier == null || tier <= pieces.size
            }.map { descriptor -> descriptor to stack.copy() }
        }
        val discovered = parsed.groupBy { it.first.trigger to it.first.setBonusLevel }.map { (identity, candidates) ->
            val trigger = identity.first
            val tier = identity.second
            val original = candidates.firstOrNull { it.first.cooldownHintMs != null }?.first ?: candidates.first().first
            val descriptor = original.copy(
                skillKey = "$setId:SET:${trigger.name}:${tier ?: "UNKNOWN"}",
                displayName = setName,
                setId = setId,
                sourceFingerprint = "set:$setId"
            )
            val profile = SkillProfileStore.register(descriptor, sourceType)
            DiscoveredSkill(normalizeDescriptor(descriptor, profile), candidates.first().second, sourceType)
        }
        SkillProfileStore.removeSetProfilesExcept(setId, discovered.map { it.descriptor.skillKey }.toSet())
        return discovered
    }

    private fun matchesRules(stacks: List<ItemStack>, rules: List<String>): Boolean {
        val remaining = stacks.toMutableList()
        return rules.filter(String::isNotBlank).all { rule ->
            val match = remaining.firstOrNull { FabricComaItemAdapter.matches(it, rule) } ?: return false
            remaining.remove(match)
            true
        }
    }

    private fun matchesRoleRules(stacks: List<ItemStack?>, rules: List<String>): Boolean =
        rules.withIndex().all { (index, rule) ->
            rule.isBlank() || stacks.getOrNull(index)?.let { FabricComaItemAdapter.matches(it, rule) } == true
        }

    private fun overlapsRules(stacks: List<ItemStack>, rules: List<String>): Boolean =
        rules.filter(String::isNotBlank).any { rule -> stacks.any { FabricComaItemAdapter.matches(it, rule) } }

    private fun comaSetId(index: Int, rules: List<String>): String =
        "COMA_SET:$index:${rules.joinToString("|").hashCode().toString(16)}"

    private fun setGroupKey(stack: ItemStack): String? =
        FabricComaItemAdapter.extractSetName(stack)
            ?.let(RvlTextMatcher::normalize)
            ?.replace(Regex("\\s+-\\s+(?:I|II|III|IV|V|VI|VII|VIII|IX|X)\\s*:?$"), "")
            ?.takeIf(String::isNotBlank)

    private fun sourcePriority(sourceType: SkillSourceType): Int = when (sourceType) {
        SkillSourceType.COMA_SET -> 3
        SkillSourceType.EQUIPPED_SET -> 2
        else -> 1
    }

    private fun normalizeDescriptor(
        descriptor: SkillDescriptor,
        profile: net.eenda.rvladdons.feature.cooldown.SkillProfile
    ): SkillDescriptor = descriptor.copy(
        skillKey = profile.skillKey,
        displayName = profile.label,
        setId = profile.setId,
        sourceFingerprint = profile.sourceFingerprint,
        cooldownHintMs = profile.cooldownHintMs ?: descriptor.cooldownHintMs,
        skillType = profile.skillType,
        setBonusLevel = profile.setBonusLevel
    )

    private fun resolveSetName(pieces: List<ItemStack>, configuredName: String?): String {
        val configured = configuredName?.trim()
            ?.takeIf { it.isNotBlank() && !FabricComaItemAdapter.isDefaultSetName(it) }
        return configured
            ?: pieces.asSequence().mapNotNull(FabricComaItemAdapter::extractSetName).firstOrNull()
            ?: FabricComaItemAdapter.inferSetName(pieces.map { it.name.string })
            ?: "Equipped Set"
    }
}
