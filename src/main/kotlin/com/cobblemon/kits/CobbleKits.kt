package com.cobblemon.kits

import com.mojang.authlib.GameProfile
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.component.DataComponentTypes
import net.minecraft.component.type.LoreComponent
import net.minecraft.component.type.ProfileComponent
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.registry.Registries
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.ScreenHandlerType
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.sound.SoundEvents
import net.minecraft.stat.Stats
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.Identifier
import net.minecraft.util.math.MathHelper
import java.util.UUID

object CobbleKits : ModInitializer {
    private const val GUI_TITLE = "CobbleKits"
    private const val INVENTORY_SIZE = 27
    private val claimedKits: MutableMap<UUID, MutableSet<String>> = mutableMapOf()

    private data class Kit(
        val id: String,
        val displayName: String,
        val requiredHours: Int,
        val icon: Item,
        val rewards: List<ItemStack>,
    )

    private val kits = listOf(
        Kit(
            id = "starter",
            displayName = "Starter",
            requiredHours = 0,
            icon = getCobblemonItem("poke_ball"),
            rewards = listOf(
                ItemStack(getCobblemonItem("poke_ball"), 32),
                ItemStack(getCobblemonItem("pokedex"), 1),
                ItemStack(Items.COOKED_BEEF, 16),
            ),
        ),
        Kit(
            id = "novice",
            displayName = "Novice",
            requiredHours = 2,
            icon = getCobblemonItem("great_ball"),
            rewards = listOf(
                ItemStack(getCobblemonItem("great_ball"), 16),
                ItemStack(Items.POTION, 4),
            ),
        ),
        Kit(
            id = "expert",
            displayName = "Expert",
            requiredHours = 10,
            icon = getCobblemonItem("ultra_ball"),
            rewards = listOf(
                ItemStack(getCobblemonItem("ultra_ball"), 16),
                ItemStack(getCobblemonItem("rare_candy"), 5),
                ItemStack(Items.DIAMOND, 3),
            ),
        ),
        Kit(
            id = "legend",
            displayName = "Légende",
            requiredHours = 50,
            icon = getCobblemonItem("master_ball"),
            rewards = listOf(
                ItemStack(getCobblemonItem("master_ball"), 1),
                ItemStack(Items.NETHERITE_INGOT, 2),
            ),
        ),
    )

    override fun onInitialize() {
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
            dispatcher.register(
                net.minecraft.server.command.CommandManager.literal("kits").executes { context ->
                    val player = context.source.player
                    player.openHandledScreen(SimpleNamedScreenHandlerFactory({ syncId, inventory, screenPlayer ->
                        KitsScreenHandler(syncId, inventory, screenPlayer)
                    }, Text.literal(GUI_TITLE)))
                    1
                },
            )
        }
    }

    private fun getCobblemonItem(path: String): Item {
        return Registries.ITEM.get(Identifier.of("cobblemon", path))
    }

    private fun getPlayTimeHours(player: ServerPlayerEntity): Double {
        val ticks = player.statHandler.getStat(Stats.CUSTOM.getOrCreateStat(Stats.PLAY_TIME)).toDouble()
        return ticks / 20.0 / 3600.0
    }

    private fun getClaimedSet(player: ServerPlayerEntity): MutableSet<String> {
        return claimedKits.getOrPut(player.uuid) { mutableSetOf() }
    }

    private fun buildProfileStack(player: ServerPlayerEntity, hours: Double): ItemStack {
        val stack = ItemStack(Items.PLAYER_HEAD)
        val profile = GameProfile(player.uuid, player.name.string)
        stack.set(DataComponentTypes.PROFILE, ProfileComponent(profile))
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(player.name.string).formatted(Formatting.GOLD))
        val lore = listOf(
            Text.literal("Temps de jeu: %.2f h".format(hours)).formatted(Formatting.GRAY),
        )
        stack.set(DataComponentTypes.LORE, LoreComponent(lore))
        return stack
    }

    private fun buildKitStack(player: ServerPlayerEntity, kit: Kit, hours: Double): ItemStack {
        val stack = ItemStack(kit.icon)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(kit.displayName).formatted(Formatting.AQUA))

        val claimed = kit.id in getClaimedSet(player)
        val statusLine = when {
            claimed -> Text.literal("Déjà récupéré").formatted(Formatting.GRAY)
            hours >= kit.requiredHours -> Text.literal("Disponible").formatted(Formatting.GREEN)
            else -> Text.literal("Verrouillé").formatted(Formatting.RED)
        }

        val lore = mutableListOf(
            statusLine,
            Text.literal("Requis: ${kit.requiredHours}h").formatted(Formatting.DARK_GRAY),
        )

        if (!claimed && hours < kit.requiredHours) {
            val progress = MathHelper.clamp(hours / kit.requiredHours.toDouble(), 0.0, 1.0)
            val filled = (progress * 10).toInt()
            val bar = buildString {
                append("[")
                repeat(filled) { append("█") }
                repeat(10 - filled) { append("▒") }
                append("] ")
                append("%d%%".format((progress * 100).toInt()))
            }
            lore.add(Text.literal(bar).setStyle(Style.EMPTY.withColor(Formatting.GRAY)))
        }

        stack.set(DataComponentTypes.LORE, LoreComponent(lore))

        if (!claimed && hours >= kit.requiredHours) {
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true)
        }

        return stack
    }

    private fun buildBackgroundStack(): ItemStack {
        val stack = ItemStack(Items.GRAY_STAINED_GLASS_PANE)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(" "))
        return stack
    }

    private fun buildCloseStack(): ItemStack {
        val stack = ItemStack(Items.BARRIER)
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Fermer").formatted(Formatting.RED))
        return stack
    }

    private fun fillInventory(player: ServerPlayerEntity, inventory: net.minecraft.inventory.Inventory) {
        val hours = getPlayTimeHours(player)
        val background = buildBackgroundStack()
        for (slot in 0 until INVENTORY_SIZE) {
            inventory.setStack(slot, background.copy())
        }
        inventory.setStack(4, buildProfileStack(player, hours))
        inventory.setStack(22, buildCloseStack())

        val kitSlots = listOf(10, 12, 14, 16)
        kits.zip(kitSlots).forEach { (kit, slot) ->
            inventory.setStack(slot, buildKitStack(player, kit, hours))
        }
    }

    private fun tryClaimKit(player: ServerPlayerEntity, kit: Kit): Boolean {
        val hours = getPlayTimeHours(player)
        val claimed = getClaimedSet(player)
        if (kit.id in claimed) {
            player.playSound(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return false
        }
        if (hours < kit.requiredHours) {
            player.playSound(SoundEvents.ENTITY_VILLAGER_NO, 1.0f, 1.0f)
            return false
        }
        kit.rewards.forEach { reward ->
            player.inventory.insertStack(reward.copy())
        }
        claimed.add(kit.id)
        player.playSound(SoundEvents.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f)
        return true
    }

    private class KitsScreenHandler(
        syncId: Int,
        playerInventory: PlayerInventory,
        private val player: PlayerEntity,
    ) : GenericContainerScreenHandler(
        ScreenHandlerType.GENERIC_9X3,
        syncId,
        playerInventory,
        net.minecraft.inventory.SimpleInventory(INVENTORY_SIZE),
        3,
    ) {
        init {
            if (player is ServerPlayerEntity) {
                CobbleKits.fillInventory(player, inventory)
            }
        }

        override fun onSlotClick(slotIndex: Int, button: Int, actionType: net.minecraft.screen.slot.SlotActionType, player: PlayerEntity) {
            if (player !is ServerPlayerEntity) {
                return
            }
            if (slotIndex == 22) {
                player.closeHandledScreen()
                return
            }

            val kitSlots = listOf(10, 12, 14, 16)
            val kitIndex = kitSlots.indexOf(slotIndex)
            if (kitIndex >= 0) {
                val kit = CobbleKits.kits[kitIndex]
                CobbleKits.tryClaimKit(player, kit)
                CobbleKits.fillInventory(player, inventory)
                return
            }
        }

        override fun canUse(player: PlayerEntity): Boolean = true
    }
}
