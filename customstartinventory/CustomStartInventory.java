package net.darkunity.customstartinventory;

import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Mod("customstartinventory")
public class CustomStartInventory {
    
    private static final String INVENTORY_FILE = "config/csi_inventory.dat";
    
    // Конфигурация
    public static ModConfigSpec.BooleanValue MOD_ENABLED;
    public static ModConfigSpec.BooleanValue ALLOW_OTHER_ITEMS;
    private static ModConfigSpec CONFIG_SPEC;
    
    // Очередь игроков для мгновенной обработки
    private static final Set<UUID> instantPlayers = new HashSet<>();
    
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        
        MOD_ENABLED = builder
            .comment("Включить/выключить мод")
            .define("mod_enabled", true);
            
        ALLOW_OTHER_ITEMS = builder
            .comment("Разрешить другие предметы в инвентаре (не очищать)")
            .comment("Если true - предметы других модов не будут удаляться")
            .define("allow_other_items", false);
            
        CONFIG_SPEC = builder.build();
    }
    
    public CustomStartInventory(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
        NeoForge.EVENT_BUS.register(this);
        
        modEventBus.addListener(this::onClientSetup);
    }
    
    private void onClientSetup(FMLClientSetupEvent event) {
        // Инициализация клиентской части
    }
    
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!MOD_ENABLED.get() || instantPlayers.isEmpty()) return;
        
        // Обрабатываем всех игроков сразу
        Iterator<UUID> iterator = instantPlayers.iterator();
        while (iterator.hasNext()) {
            UUID playerId = iterator.next();
            ServerPlayer player = ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(playerId);
            
            if (player != null) {
                // Проверяем инвентарь и сразу очищаем если нужно
                boolean inventoryClear = checkAndClearInventory(player);
                
                // Если инвентарь чист (или разрешены другие предметы) - выдаем наш инвентарь
                if (inventoryClear || ALLOW_OTHER_ITEMS.get()) {
                    giveInventory(player);
                    iterator.remove();
                }
                // Если инвентарь не чист - продолжаем очистку в следующем тике
            } else {
                // Игрок вышел - удаляем из очереди
                iterator.remove();
            }
        }
    }
    
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!MOD_ENABLED.get()) return;
        
        if (event.getEntity() instanceof ServerPlayer player) {
            // Проверяем, был ли игроку уже выдан инвентарь
            CompoundTag playerData = player.getPersistentData();
            CompoundTag persistentData = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            
            if (!persistentData.getBoolean("csi_inventory_received")) {
                // Если разрешены другие предметы - сразу выдаем наш инвентарь
                if (ALLOW_OTHER_ITEMS.get()) {
                    giveInventory(player);
                } else {
                    // Добавляем игрока в очередь для мгновенной обработки
                    instantPlayers.add(player.getUUID());
                    
                    // Первая немедленная проверка и очистка
                    if (checkAndClearInventory(player)) {
                        // Если сразу очистилось - выдаем инвентарь
                        giveInventory(player);
                        instantPlayers.remove(player.getUUID());
                    }
                }
            }
        }
    }
    
    /**
     * Проверяет и очищает инвентарь за один проход
     * Возвращает true если инвентарь чист после очистки
     */
    private boolean checkAndClearInventory(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        boolean hasItems = false;
        
        // Одновременно проверяем и очищаем все слоты
        // Основной инвентарь (36 слотов)
        for (int i = 0; i < 36; i++) {
            if (!inventory.getItem(i).isEmpty()) {
                inventory.setItem(i, ItemStack.EMPTY);
                hasItems = true;
            }
        }
        
        // Броня (4 слота)
        for (int i = 0; i < 4; i++) {
            if (!inventory.armor.get(i).isEmpty()) {
                inventory.armor.set(i, ItemStack.EMPTY);
                hasItems = true;
            }
        }
        
        // Левая рука
        if (!inventory.offhand.get(0).isEmpty()) {
            inventory.offhand.set(0, ItemStack.EMPTY);
            hasItems = true;
        }
        
        // Если что-то очистили - обновляем инвентарь
        if (hasItems) {
            player.containerMenu.broadcastChanges();
            return false; // Были предметы, инвентарь не чист
        }
        
        return true; // Инвентарь чист
    }
    
    /**
     * Выдает инвентарь игроку
     */
    private void giveInventory(ServerPlayer player) {
        // Загружаем наш сохраненный инвентарь
        if (loadInventory(player)) {
            // Помечаем, что инвентарь был выдан
            CompoundTag playerData = player.getPersistentData();
            CompoundTag persistentData = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            persistentData.putBoolean("csi_inventory_received", true);
            playerData.put(Player.PERSISTED_NBT_TAG, persistentData);
        } else {
            // Если нет сохраненного инвентаря
            CompoundTag playerData = player.getPersistentData();
            CompoundTag persistentData = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            persistentData.putBoolean("csi_inventory_received", true);
            playerData.put(Player.PERSISTED_NBT_TAG, persistentData);
        }
    }
    
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // Команда /csi_help - показывает список команд
        event.getDispatcher().register(Commands.literal("csi_help")
            .executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(
                    "===== Custom Start Inventory Help =====\n" +
                    "/csi_set - Сохранить текущий инвентарь как стартовый\n" +
                    "/csi_load - Очистить инвентарь и загрузить сохраненный\n" +
                    "/csi_remove - Удалить сохраненный стартовый инвентарь\n" +
                    "/csi_allow <true/false> - Разрешить/запретить другие предметы\n" +
                    "/csi_status - Показать статус настроек\n" +
                    "Текущие настройки:\n" +
                    "  - Мод: " + (MOD_ENABLED.get() ? "ВКЛ" : "ВЫКЛ") + "\n" +
                    "  - Другие предметы: " + (ALLOW_OTHER_ITEMS.get() ? "РАЗРЕШЕНЫ" : "ЗАПРЕЩЕНЫ")
                ), false);
                return 1;
            })
        );

        // Команда /csi_status - показывает статус
        event.getDispatcher().register(Commands.literal("csi_status")
            .executes(context -> {
                context.getSource().sendSuccess(() -> Component.literal(
                    "Статус Custom Start Inventory:\n" +
                    "  - Мод: " + (MOD_ENABLED.get() ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН") + "§r\n" +
                    "  - Другие предметы: " + (ALLOW_OTHER_ITEMS.get() ? "§aРАЗРЕШЕНЫ" : "§cЗАПРЕЩЕНЫ") + "§r\n" +
                    "  - Игроков в очереди: " + instantPlayers.size() + "\n" +
                    "  - Файл инвентаря: " + (Files.exists(Paths.get(INVENTORY_FILE)) ? "§aСУЩЕСТВУЕТ" : "§cОТСУТСТВУЕТ") + "§r"
                ), false);
                return 1;
            })
        );

        // Команда /csi_set - сохраняет текущий инвентарь
        event.getDispatcher().register(Commands.literal("csi_set")
            .executes(context -> {
                if (!MOD_ENABLED.get()) {
                    context.getSource().sendFailure(Component.literal("Мод отключен"));
                    return 0;
                }
                
                if (context.getSource().getPlayer() instanceof ServerPlayer player) {
                    saveInventory(player);
                    context.getSource().sendSuccess(() -> Component.literal("Стартовый инвентарь сохранен!"), false);
                    return 1;
                }
                return 0;
            })
        );

        // Команда /csi_remove - удаляет сохраненный инвентарь
        event.getDispatcher().register(Commands.literal("csi_remove")
            .executes(context -> {
                if (!MOD_ENABLED.get()) {
                    context.getSource().sendFailure(Component.literal("Мод отключен"));
                    return 0;
                }
                
                removeInventoryFile();
                context.getSource().sendSuccess(() -> Component.literal("Сохраненный инвентарь удален!"), false);
                return 1;
            })
        );

        // Команда /csi_load - принудительно загружает инвентарь
        event.getDispatcher().register(Commands.literal("csi_load")
            .executes(context -> {
                if (!MOD_ENABLED.get()) {
                    context.getSource().sendFailure(Component.literal("Мод отключен"));
                    return 0;
                }
                
                if (context.getSource().getPlayer() instanceof ServerPlayer player) {
                    // Если запрещены другие предметы - очищаем инвентарь
                    if (!ALLOW_OTHER_ITEMS.get()) {
                        clearInventory(player);
                    }
                    
                    // Загружаем наш инвентарь
                    if (loadInventory(player)) {
                        context.getSource().sendSuccess(() -> Component.literal("Инвентарь загружен!"), false);
                    } else {
                        context.getSource().sendSuccess(() -> Component.literal("Сохраненного инвентаря нет."), false);
                    }
                    return 1;
                }
                return 0;
            })
        );
        
        // Команда /csi_allow - разрешает/запрещает другие предметы
        event.getDispatcher().register(Commands.literal("csi_allow")
            .executes(context -> {
                boolean current = ALLOW_OTHER_ITEMS.get();
                context.getSource().sendSuccess(() -> Component.literal(
                    "Текущий статус других предметов: " + 
                    (current ? "РАЗРЕШЕНЫ" : "ЗАПРЕЩЕНЫ") + "\n" +
                    "Используйте: /csi_allow true или /csi_allow false"
                ), false);
                return 1;
            })
            .then(Commands.literal("true")
                .executes(context -> {
                    if (!MOD_ENABLED.get()) {
                        context.getSource().sendFailure(Component.literal("Мод отключен"));
                        return 0;
                    }
                    
                    ALLOW_OTHER_ITEMS.set(true);
                    context.getSource().sendSuccess(() -> Component.literal("Теперь другие предметы §aРАЗРЕШЕНЫ§r в инвентаре!"), false);
                    return 1;
                })
            )
            .then(Commands.literal("false")
                .executes(context -> {
                    if (!MOD_ENABLED.get()) {
                        context.getSource().sendFailure(Component.literal("Мод отключен"));
                        return 0;
                    }
                    
                    ALLOW_OTHER_ITEMS.set(false);
                    context.getSource().sendSuccess(() -> Component.literal("Теперь другие предметы §cЗАПРЕЩЕНЫ§r в инвентаре!"), false);
                    return 1;
                })
            )
        );
        
        // Команда /csi_force - принудительная очистка и выдача
        event.getDispatcher().register(Commands.literal("csi_force")
            .executes(context -> {
                if (!MOD_ENABLED.get()) {
                    context.getSource().sendFailure(Component.literal("Мод отключен"));
                    return 0;
                }
                
                if (context.getSource().getPlayer() instanceof ServerPlayer player) {
                    // Удаляем из очереди
                    instantPlayers.remove(player.getUUID());
                    
                    // Принудительно очищаем и выдаем
                    clearInventory(player);
                    if (loadInventory(player)) {
                        context.getSource().sendSuccess(() -> Component.literal("Инвентарь принудительно загружен!"), false);
                    } else {
                        context.getSource().sendSuccess(() -> Component.literal("Инвентарь очищен, но сохраненного инвентаря нет."), false);
                    }
                    
                    // Помечаем как выданный
                    CompoundTag playerData = player.getPersistentData();
                    CompoundTag persistentData = playerData.getCompound(Player.PERSISTED_NBT_TAG);
                    persistentData.putBoolean("csi_inventory_received", true);
                    playerData.put(Player.PERSISTED_NBT_TAG, persistentData);
                    
                    return 1;
                }
                return 0;
            })
        );
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (!MOD_ENABLED.get()) return;
        
        // Копируем флаг выдачи инвентаря при клонировании
        if (event.getOriginal() instanceof ServerPlayer original && event.getEntity() instanceof ServerPlayer newPlayer) {
            CompoundTag originalData = original.getPersistentData();
            CompoundTag originalPersistentData = originalData.getCompound(Player.PERSISTED_NBT_TAG);
            
            CompoundTag newPlayerData = newPlayer.getPersistentData();
            CompoundTag newPersistentData = newPlayerData.getCompound(Player.PERSISTED_NBT_TAG);
            
            newPersistentData.putBoolean("csi_inventory_received", 
                originalPersistentData.getBoolean("csi_inventory_received"));
            newPlayerData.put(Player.PERSISTED_NBT_TAG, newPersistentData);
        }
    }

    /**
     * Очищает весь инвентарь игрока
     */
    private void clearInventory(ServerPlayer player) {
        // Очищаем основной инвентарь (36 слотов)
        for (int i = 0; i < 36; i++) {
            player.getInventory().setItem(i, ItemStack.EMPTY);
        }
        
        // Очищаем броню (4 слота)
        for (int i = 0; i < 4; i++) {
            player.getInventory().armor.set(i, ItemStack.EMPTY);
        }
        
        // Очищаем левую руку
        player.getInventory().offhand.set(0, ItemStack.EMPTY);
        
        // Обновляем инвентарь
        player.containerMenu.broadcastChanges();
    }

    private void saveInventory(Player player) {
        try {
            Path path = Paths.get(INVENTORY_FILE);
            Files.createDirectories(path.getParent());

            CompoundTag data = new CompoundTag();
            Inventory inventory = player.getInventory();
            ListTag allItems = new ListTag();

            // Основной инвентарь
            for (int i = 0; i < 36; i++) {
                ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    itemTag.putInt("Slot", i);
                    itemTag.put("Item", stack.save(player.registryAccess(), new CompoundTag()));
                    allItems.add(itemTag);
                }
            }

            // Броня
            for (int i = 0; i < 4; i++) {
                ItemStack stack = inventory.armor.get(i);
                if (!stack.isEmpty()) {
                    CompoundTag itemTag = new CompoundTag();
                    itemTag.putInt("Slot", 36 + i);
                    itemTag.put("Item", stack.save(player.registryAccess(), new CompoundTag()));
                    allItems.add(itemTag);
                }
            }

            // Левая рука
            ItemStack offhand = inventory.offhand.get(0);
            if (!offhand.isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", 40);
                itemTag.put("Item", offhand.save(player.registryAccess(), new CompoundTag()));
                allItems.add(itemTag);
            }

            data.put("Inventory", allItems);

            try (DataOutputStream output = new DataOutputStream(new FileOutputStream(path.toFile()))) {
                net.minecraft.nbt.NbtIo.write(data, output);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean loadInventory(Player player) {
        try {
            Path path = Paths.get(INVENTORY_FILE);
            if (!Files.exists(path)) {
                return false;
            }

            CompoundTag data;
            try (DataInputStream input = new DataInputStream(new FileInputStream(path.toFile()))) {
                data = net.minecraft.nbt.NbtIo.read(input);
            }

            if (data == null || !data.contains("Inventory")) {
                return false;
            }

            Inventory inventory = player.getInventory();
            ListTag allItems = data.getList("Inventory", 10);

            for (int i = 0; i < allItems.size(); i++) {
                CompoundTag itemTag = allItems.getCompound(i);
                int slot = itemTag.getInt("Slot");

                if (itemTag.contains("Item")) {
                    CompoundTag stackTag = itemTag.getCompound("Item");
                    ItemStack stack = ItemStack.parse(player.registryAccess(), stackTag).orElse(ItemStack.EMPTY);

                    if (!stack.isEmpty()) {
                        if (slot >= 0 && slot < 36) {
                            inventory.setItem(slot, stack);
                        } else if (slot >= 36 && slot < 40) {
                            inventory.armor.set(slot - 36, stack);
                        } else if (slot == 40) {
                            inventory.offhand.set(0, stack);
                        }
                    }
                }
            }
            
            return true;

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void removeInventoryFile() {
        try {
            Path path = Paths.get(INVENTORY_FILE);
            Files.deleteIfExists(path);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}