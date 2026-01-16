package net.darkunity.customstartinventory;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import net.darkunity.customstartinventory.handlers.CuriosAccessoriesHandler;
import net.darkunity.customstartinventory.handlers.WispForestAccessoriesHandler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import com.mojang.brigadier.arguments.StringArgumentType;

@Mod(CustomStartInventory.MODID)
public class CustomStartInventory {
    public static final String MODID = "customstartinventory";
    
    // ============================================================================
    // КОНФИГУРАЦИЯ
    // ============================================================================
    
    public static ModConfigSpec.BooleanValue MOD_ENABLED;
    public static ModConfigSpec.BooleanValue INTELLIGENT_SCANNING;
    public static ModConfigSpec.BooleanValue ENABLE_SOUNDS;
    public static ModConfigSpec.BooleanValue SHOW_WELCOME_MESSAGE;
    public static ModConfigSpec.BooleanValue HIDE_CHAT_MESSAGES;
    public static ModConfigSpec.BooleanValue SAVE_ACCESSORIES;
    public static ModConfigSpec.EnumValue<AccessoryMode> ACCESSORIES_MODE;
    public static ModConfigSpec.IntValue MONITORING_DURATION;
    public static ModConfigSpec.BooleanValue REACTIVE_CLEANING;
    public static ModConfigSpec.IntValue MAX_REACTIVE_ATTEMPTS;
    public static ModConfigSpec.BooleanValue GLOBAL_INVENTORY_STORAGE;
    public static ModConfigSpec.BooleanValue SHARE_INVENTORY_BETWEEN_WORLDS;
    public static ModConfigSpec.BooleanValue VERIFY_INVENTORY_MATCH;
    public static ModConfigSpec.BooleanValue VERBOSE_LOGGING;
    
    // Энум для режимов аксессуаров
    public enum AccessoryMode {
        AUTO, CURIOS, ACCESSORIES, BOTH, NONE
    }
    
    // ============================================================================
    // СТАТУС API (ОБНАРУЖЕНИЕ)
    // ============================================================================
    
    public static boolean hasCurios = false;
    public static boolean hasAccessories = false; // WispForest Accessories
    
    // ============================================================================
    // ИНТЕЛЛЕКТУАЛЬНАЯ СИСТЕМА МОНИТОРИНГА
    // ============================================================================
    
    private static final Map<UUID, PlayerMonitorData> activeMonitors = new HashMap<>();
    private static final Set<UUID> processedPlayers = new HashSet<>();
    private static MinecraftServer currentServer;
    private static Path globalStorageDir;
    
    // Класс для мониторинга инвентаря игрока
    private static class PlayerMonitorData {
        final ServerPlayer player;
        final String playerName;
        final CustomStartInventory csiInstance; // Добавляем ссылку на экземпляр
        int monitoringTicksRemaining;
        int lastItemCount = 0;
        boolean hasGivenInventory = false;
        int reactiveCleaningCount = 0;
        List<ItemSnapshot> itemHistory = new ArrayList<>();
        boolean isFirstCheck = true;
        
        PlayerMonitorData(ServerPlayer player, int durationTicks, CustomStartInventory instance) {
            this.player = player;
            this.playerName = player.getName().getString();
            this.csiInstance = instance; // Сохраняем экземпляр
            this.monitoringTicksRemaining = durationTicks;
            this.lastItemCount = instance.countAllItems(player); // Теперь можно вызывать
        }
    }
    
    // Снимок предмета для истории
    private static class ItemSnapshot {
        final String itemId;
        final int slot;
        final int count;
        final long timestamp;
        
        ItemSnapshot(String itemId, int slot, int count) {
            this.itemId = itemId;
            this.slot = slot;
            this.count = count;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return slot + ":" + itemId + "x" + count;
        }
    }
    
    // ============================================================================
    // ИНИЦИАЛИЗАЦИЯ
    // ============================================================================
    
    public CustomStartInventory(IEventBus modEventBus, ModContainer modContainer) {
        // Регистрация конфигурации
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        setupConfig(builder);
        modContainer.registerConfig(ModConfig.Type.COMMON, builder.build());
        
        // Регистрация событий
        NeoForge.EVENT_BUS.register(this);
        
        // Регистрация экрана конфигурации только для клиента
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
            () -> (mc, parent) -> new CSIConfigScreen(parent));
    }
    
    private void setupConfig(ModConfigSpec.Builder builder) {
        builder.push("General");
        MOD_ENABLED = builder
            .comment("Включить/отключить весь мод.")
            .define("ModEnabled", true);
        INTELLIGENT_SCANNING = builder
            .comment("Если включено, то инвентарь сохраняется только при наличии хотя бы одного предмета (не пустого).")
            .define("IntelligentScanning", true);
        ENABLE_SOUNDS = builder
            .comment("Включить звуковые эффекты при сохранении/загрузке.")
            .define("EnableSounds", true);
        SHOW_WELCOME_MESSAGE = builder
            .comment("Показывать ли игроку приветственное сообщение с информацией.")
            .define("ShowWelcomeMessage", true);
        HIDE_CHAT_MESSAGES = builder
            .comment("Скрывать сообщения о сохранении/загрузке в чате для игроков (останутся только в логах).")
            .define("HideChatMessages", true);
        MONITORING_DURATION = builder
            .comment("Продолжительность мониторинга инвентаря (в тиках, 20 тиков = 1 секунда).")
            .defineInRange("MonitoringDuration", 20, 60, 200);
        REACTIVE_CLEANING = builder
            .comment("Реактивная очистка: мгновенно очищать любые появившиеся предметы.")
            .define("ReactiveCleaning", true);
        MAX_REACTIVE_ATTEMPTS = builder
            .comment("Максимальное количество реактивных очисток.")
            .defineInRange("MaxReactiveAttempts", 1, 5, 20);
        GLOBAL_INVENTORY_STORAGE = builder
            .comment("Сохранять инвентарь глобально (доступно во всех мирах) вместо локального сохранения в каждом мире.")
            .define("GlobalInventoryStorage", true);
        SHARE_INVENTORY_BETWEEN_WORLDS = builder
            .comment("Использовать один сохраненный инвентарь для всех миров (включая разные типы миров: выживание, креатив и т.д.)")
            .define("ShareInventoryBetweenWorlds", true);
        VERIFY_INVENTORY_MATCH = builder
            .comment("Проверять соответствие текущего инвентаря сохраненному перед очисткой.")
            .define("VerifyInventoryMatch", true);
        VERBOSE_LOGGING = builder
            .comment("Подробное логирование (может создавать много сообщений).")
            .define("VerboseLogging", false);
        builder.pop();
        
        builder.push("Accessories");
        SAVE_ACCESSORIES = builder
            .comment("Сохранять и загружать предметы из слотов аксессуаров.")
            .define("SaveAccessories", true);
        ACCESSORIES_MODE = builder
            .comment("Режим работы с аксессуарами: AUTO (попробует оба), CURIOS, ACCESSORIES (WispForest), BOTH (сохранит оба), NONE.")
            .defineEnum("AccessoriesMode", AccessoryMode.AUTO);
        builder.pop();
    }
    
    // ============================================================================
    // ОБРАБОТЧИКИ СОБЫТИЙ
    // ============================================================================
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        hasCurios = CuriosAccessoriesHandler.hasCurios(); 
        hasAccessories = WispForestAccessoriesHandler.hasAccessories();
        currentServer = event.getServer();
        
        if (GLOBAL_INVENTORY_STORAGE.get()) {
            initGlobalStorage();
        }
        
        // Инициализация системы наборов
        ServerInventoryManager.init();
        
        System.out.println("[CSI] Инициализация завершена");
        System.out.println("[CSI] Curios: " + hasCurios + ", WispForest: " + hasAccessories);
        System.out.println("[CSI] Мониторинг: " + MONITORING_DURATION.get() + " тиков");
        System.out.println("[CSI] Реактивная очистка: " + REACTIVE_CLEANING.get());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        activeMonitors.clear();
        processedPlayers.clear();
        currentServer = null;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (currentServer == null || !MOD_ENABLED.get()) return;
        
        // Обрабатываем всех активных мониторов
        processActiveMonitors();
    }
    
    private void processActiveMonitors() {
        Iterator<Map.Entry<UUID, PlayerMonitorData>> iterator = activeMonitors.entrySet().iterator();
        
        while (iterator.hasNext()) {
            Map.Entry<UUID, PlayerMonitorData> entry = iterator.next();
            PlayerMonitorData monitor = entry.getValue();
            UUID playerId = entry.getKey();
            
            // Проверяем что игрок онлайн
            ServerPlayer player = currentServer.getPlayerList().getPlayer(playerId);
            if (player == null || !player.isAlive()) {
                System.out.println("[CSI] Игрок " + monitor.playerName + " отключился, удаляем монитор");
                iterator.remove();
                continue;
            }
            
            // Проверяем инвентарь
            int currentItemCount = checkAndReactToInventory(player, monitor);
            
            // Обновляем счетчик тиков
            monitor.monitoringTicksRemaining--;
            
            // Логируем первый тик
            if (monitor.isFirstCheck) {
                System.out.println("[CSI] Начало мониторинга для " + monitor.playerName + 
                                 " (" + monitor.monitoringTicksRemaining + " тиков)");
                System.out.println("[CSI] Начальное количество предметов: " + currentItemCount);
                monitor.isFirstCheck = false;
            }
            
            // Если мониторинг завершен
            if (monitor.monitoringTicksRemaining <= 0) {
                System.out.println("[CSI] Мониторинг завершен для " + monitor.playerName);
                
                // Проверяем, совпадает ли инвентарь
                if (VERIFY_INVENTORY_MATCH.get() && inventoryMatchesSaved(player)) {
                    System.out.println("[CSI] Инвентарь уже совпадает с сохраненным");
                    monitor.hasGivenInventory = true;
                } else {
                    // Дополнительная проверка перед выдачей
                    int itemCount = countAllItems(player);
                    if (itemCount > 0) {
                        System.out.println("[CSI] Предупреждение: " + itemCount + " предметов осталось перед выдачей!");
                        aggressiveClearInventory(player);
                    }
                    
                    if (!monitor.hasGivenInventory) {
                        System.out.println("[CSI] Выдаем сохраненный инвентарь");
                        giveSavedInventory(player);
                        monitor.hasGivenInventory = true;
                    }
                }
                
                iterator.remove();
                processedPlayers.add(playerId);
            }
        }
    }
    
    /**
     * Проверяет инвентарь и реагирует на изменения
     */
    private int checkAndReactToInventory(ServerPlayer player, PlayerMonitorData monitor) {
        int currentItemCount = countAllItems(player);
        
        // Проверяем, совпадает ли инвентарь с сохраненным (если включена проверка)
        if (VERIFY_INVENTORY_MATCH.get() && inventoryMatchesSaved(player)) {
            System.out.println("[CSI] Инвентарь совпадает с сохраненным, пропускаем реакцию");
            monitor.lastItemCount = currentItemCount;
            monitor.hasGivenInventory = true; // Помечаем как выданный
            return currentItemCount;
        }
        
        // Если предметы появились
        if (currentItemCount > 0) {
            // Проверяем, не наши ли это предметы
            if (VERIFY_INVENTORY_MATCH.get() && !inventoryMatchesSaved(player)) {
                System.out.println("[CSI] Обнаружены ПОСТОРОННИЕ предметы у " + player.getName().getString() + 
                                 ": " + currentItemCount + " шт.");
                
                // Сравниваем с предыдущим количеством
                if (currentItemCount != monitor.lastItemCount) {
                    System.out.println("[CSI] Изменение количества предметов: " + 
                                     monitor.lastItemCount + " -> " + currentItemCount);
                    
                    // Логируем все найденные предметы
                    logAllItems(player, "обнаружение посторонних");
                    
                    // Сохраняем в историю
                    saveItemSnapshot(player, monitor);
                    
                    // Реактивная очистка
                    if (REACTIVE_CLEANING.get() && monitor.reactiveCleaningCount < MAX_REACTIVE_ATTEMPTS.get()) {
                        performReactiveCleaning(player, monitor);
                    }
                }
            } else {
                System.out.println("[CSI] Обнаружены НАШИ предметы, пропускаем");
            }
        }
        
        // Обновляем последнее количество
        monitor.lastItemCount = currentItemCount;
        return currentItemCount;
    }
    
    /**
     * Реактивная очистка
     */
    private void performReactiveCleaning(ServerPlayer player, PlayerMonitorData monitor) {
        // Проверяем еще раз перед очисткой
        if (VERIFY_INVENTORY_MATCH.get() && inventoryMatchesSaved(player)) {
            System.out.println("[CSI] Инвентарь уже совпадает с сохраненным, пропускаем очистку");
            monitor.hasGivenInventory = true;
            return;
        }
        
        monitor.reactiveCleaningCount++;
        
        System.out.println("[CSI] === РЕАКТИВНАЯ ОЧИСТКА " + monitor.reactiveCleaningCount + 
                         "/" + MAX_REACTIVE_ATTEMPTS.get() + " ===");
        System.out.println("[CSI] Для игрока: " + player.getName().getString());
        
        // Агрессивная очистка
        aggressiveClearInventory(player);
        
        // Выдача инвентаря
        giveSavedInventory(player);
        monitor.hasGivenInventory = true;
        
        System.out.println("[CSI] === РЕАКТИВНАЯ ОЧИСТКА ЗАВЕРШЕНА ===");
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!MOD_ENABLED.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        System.out.println("[CSI] Игрок " + player.getName().getString() + " вошел в игру");
        
        // Сбрасываем статусы
        processedPlayers.remove(player.getUUID());
        activeMonitors.remove(player.getUUID());
        
        // Проверяем, нужно ли выдавать стандартный набор (первый вход)
        if (shouldGiveStarterKit(player)) {
            ServerInventoryManager.giveDefaultKitOnFirstJoin(player);
            markStarterKitReceived(player);
            return; // Не запускаем мониторинг для нового игрока
        }
        
        // Проверяем, нужно ли выдавать сохраненный инвентарь
        if (!shouldGiveInventoryOnWorldJoin(player)) {
            System.out.println("[CSI] Игроку " + player.getName().getString() + " не нужно выдавать инвентарь");
            processedPlayers.add(player.getUUID());
            return;
        }
        
        // Немедленная очистка при входе
        System.out.println("[CSI] Немедленная очистка при входе для " + player.getName().getString());
        aggressiveClearInventory(player);
        
        // Запускаем мониторинг
        startInventoryMonitoring(player);
    }
    
    @SubscribeEvent
    public void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!MOD_ENABLED.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        System.out.println("[CSI] Игрок " + player.getName().getString() + " возродился");
        
        processedPlayers.remove(player.getUUID());
        activeMonitors.remove(player.getUUID());
        
        if (!shouldGiveInventoryOnWorldJoin(player)) {
            return;
        }
        
        // Очистка при респавне
        aggressiveClearInventory(player);
        startInventoryMonitoring(player);
    }
    
    @SubscribeEvent 
    public void onPlayerChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!MOD_ENABLED.get() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        
        System.out.println("[CSI] Игрок " + player.getName().getString() + " сменил измерение");
        processedPlayers.remove(player.getUUID());
        activeMonitors.remove(player.getUUID());
    }
    
    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        if (!MOD_ENABLED.get() || !event.isWasDeath()) return;
        
        if (event.getOriginal() instanceof ServerPlayer original && 
            event.getEntity() instanceof ServerPlayer newPlayer) {
            
            CompoundTag originalData = original.getPersistentData();
            CompoundTag originalPersistent = originalData.getCompound(Player.PERSISTED_NBT_TAG);
            
            CompoundTag newPlayerData = newPlayer.getPersistentData();
            CompoundTag newPersistent = newPlayerData.getCompound(Player.PERSISTED_NBT_TAG);
            
            if (originalPersistent.getBoolean("csi_inventory_received")) {
                newPersistent.putBoolean("csi_inventory_received", true);
            }
            
            newPlayerData.put(Player.PERSISTED_NBT_TAG, newPersistent);
        }
    }
    
    // ============================================================================
    // МЕТОДЫ МОНИТОРИНГА И ОЧИСТКИ
    // ============================================================================
    
    /**
     * Запуск мониторинга инвентаря
     */
    private void startInventoryMonitoring(ServerPlayer player) {
        // Увеличим время мониторинга для надежности
        int duration = Math.max(MONITORING_DURATION.get(), 40); // минимум 40 тиков (2 секунды)
        PlayerMonitorData monitor = new PlayerMonitorData(player, duration, this);
        activeMonitors.put(player.getUUID(), monitor);
        
        System.out.println("[CSI] Запущен мониторинг для " + player.getName().getString() + 
                         " на " + duration + " тиков");
    }
    
    /**
     * Агрессивная очистка инвентаря
     */
    private void aggressiveClearInventory(ServerPlayer player) {
        System.out.println("[CSI] === АГРЕССИВНАЯ ОЧИСТКА ===");
        System.out.println("[CSI] Игрок: " + player.getName().getString());
        
        Inventory inventory = player.getInventory();
        int clearedCount = 0;
        
        // Очистка основных слотов (все 36 слотов)
        for (int i = 0; i < 36; i++) {
            if (i < inventory.items.size() && !inventory.items.get(i).isEmpty()) {
                inventory.items.set(i, ItemStack.EMPTY);
                clearedCount++;
            }
        }
        
        // Очистка брони (4 слота)
        for (int i = 0; i < 4; i++) {
            if (i < inventory.armor.size() && !inventory.armor.get(i).isEmpty()) {
                inventory.armor.set(i, ItemStack.EMPTY);
                clearedCount++;
            }
        }
        
        // Очистка оффхенд (1 слот)
        if (!inventory.offhand.isEmpty() && !inventory.offhand.get(0).isEmpty()) {
            inventory.offhand.set(0, ItemStack.EMPTY);
            clearedCount++;
        }
        
        // Очистка аксессуаров
        if (SAVE_ACCESSORIES.get()) {
            clearAccessories(player);
        }
        
        // Принудительное обновление
        player.inventoryMenu.slotsChanged(inventory);
        player.containerMenu.broadcastFullState();
        
        System.out.println("[CSI] Очищено предметов: " + clearedCount);
        System.out.println("[CSI] === ОЧИСТКА ЗАВЕРШЕНА ===");
    }
    
    /**
     * Выдача сохраненного инвентаря
     */
    private void giveSavedInventory(ServerPlayer player) {
        System.out.println("[CSI] Выдача инвентаря для " + player.getName().getString());
        
        // Перед выдачей еще раз проверяем и очищаем инвентарь
        if (countAllItems(player) > 0) {
            System.out.println("[CSI] Предупреждение: инвентарь не пуст перед выдачей!");
            aggressiveClearInventory(player);
        }
        
        boolean loaded = loadInventory(player);
        
        if (loaded) {
            markInventoryReceived(player);
            
            if (SHOW_WELCOME_MESSAGE.get() && !HIDE_CHAT_MESSAGES.get()) {
                player.sendSystemMessage(Component.literal("§a[CSI] §fStarting inventory received!"));
            }
            
            if (ENABLE_SOUNDS.get()) {
                player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.5F, 1.0F);
            }
            
            System.out.println("[CSI] Инвентарь успешно выдан");
        } else {
            System.out.println("[CSI] Не удалось загрузить инвентарь");
        }
    }
    
    /**
     * Подсчет всех предметов
     */
    private int countAllItems(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        int count = 0;
        
        for (ItemStack item : inventory.items) {
            if (!item.isEmpty()) count++;
        }
        for (ItemStack item : inventory.armor) {
            if (!item.isEmpty()) count++;
        }
        for (ItemStack item : inventory.offhand) {
            if (!item.isEmpty()) count++;
        }
        
        return count;
    }
    
    /**
     * Логирование всех предметов
     */
    private void logAllItems(ServerPlayer player, String context) {
        // Только если включено подробное логирование
        if (VERBOSE_LOGGING.get()) {
            System.out.println("[CSI] === ЛОГ ПРЕДМЕТОВ (" + context + ") ===");
            System.out.println("[CSI] Игрок: " + player.getName().getString());
            
            Inventory inventory = player.getInventory();
            
            // Основные слоты
            for (int i = 0; i < inventory.items.size(); i++) {
                ItemStack item = inventory.items.get(i);
                if (!item.isEmpty()) {
                    System.out.println("[CSI] Слот " + i + ": " + item.getItem().getDescriptionId() + 
                                     " x" + item.getCount());
                }
            }
            
            // Броня
            for (int i = 0; i < inventory.armor.size(); i++) {
                ItemStack item = inventory.armor.get(i);
                if (!item.isEmpty()) {
                    System.out.println("[CSI] Броня " + i + ": " + item.getItem().getDescriptionId() + 
                                     " x" + item.getCount());
                }
            }
            
            // Оффхенд
            for (int i = 0; i < inventory.offhand.size(); i++) {
                ItemStack item = inventory.offhand.get(i);
                if (!item.isEmpty()) {
                    System.out.println("[CSI] Оффхенд " + i + ": " + item.getItem().getDescriptionId() + 
                                     " x" + item.getCount());
                }
            }
            
            System.out.println("[CSI] === КОНЕЦ ЛОГА ===");
        } else {
            // Только краткая информация
            int count = countAllItems(player);
            System.out.println("[CSI] Обнаружено " + count + " предметов (" + context + ")");
        }
    }
    
    /**
     * Сохранение снимка предметов
     */
    private void saveItemSnapshot(ServerPlayer player, PlayerMonitorData monitor) {
        List<ItemSnapshot> snapshot = new ArrayList<>();
        Inventory inventory = player.getInventory();
        
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack item = inventory.items.get(i);
            if (!item.isEmpty()) {
                snapshot.add(new ItemSnapshot(item.getItem().getDescriptionId(), i, item.getCount()));
            }
        }
        
        for (int i = 0; i < inventory.armor.size(); i++) {
            ItemStack item = inventory.armor.get(i);
            if (!item.isEmpty()) {
                snapshot.add(new ItemSnapshot(item.getItem().getDescriptionId(), 100 + i, item.getCount()));
            }
        }
        
        for (int i = 0; i < inventory.offhand.size(); i++) {
            ItemStack item = inventory.offhand.get(i);
            if (!item.isEmpty()) {
                snapshot.add(new ItemSnapshot(item.getItem().getDescriptionId(), 200 + i, item.getCount()));
            }
        }
        
        monitor.itemHistory.addAll(snapshot);
        
        if (monitor.itemHistory.size() > 10) {
            monitor.itemHistory = monitor.itemHistory.subList(monitor.itemHistory.size() - 10, monitor.itemHistory.size());
        }
    }
    
    /**
     * Очистка аксессуаров
     */
    private void clearAccessories(ServerPlayer player) {
        if (hasCurios && (ACCESSORIES_MODE.get() == AccessoryMode.CURIOS || 
                         ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                         ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
            CuriosAccessoriesHandler.clearAccessories(player);
        }
        
        if (hasAccessories && (ACCESSORIES_MODE.get() == AccessoryMode.ACCESSORIES || 
                              ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                              ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
            WispForestAccessoriesHandler.clearAccessories(player);
        }
    }
    
    /**
     * Проверяет, совпадает ли текущий инвентарь с сохраненным
     */
    private boolean inventoryMatchesSaved(ServerPlayer player) {
        // Загружаем сохраненный инвентарь для сравнения
        CompoundTag savedData = loadFromFile(player);
        if (!savedData.contains("Inventory")) {
            return false; // Нет сохраненного инвентаря
        }
        
        ListTag savedItems = savedData.getList("Inventory", Tag.TAG_COMPOUND);
        Inventory currentInventory = player.getInventory();
        
        // Создаем карту сохраненных предметов: слот -> предмет
        Map<Integer, ItemStack> savedItemsMap = new HashMap<>();
        for (int i = 0; i < savedItems.size(); i++) {
            CompoundTag slotTag = savedItems.getCompound(i);
            int slot = slotTag.getByte("Slot") & 255;
            int type = slotTag.getByte("Type") & 255;
            
            if (slotTag.contains("Item")) {
                CompoundTag itemTag = slotTag.getCompound("Item");
                ItemStack savedStack = loadItemStackSimple(player, itemTag);
                if (!savedStack.isEmpty()) {
                    // Преобразуем слот в абсолютный индекс
                    int absoluteSlot = getAbsoluteSlot(slot, type);
                    savedItemsMap.put(absoluteSlot, savedStack);
                }
            }
        }
        
        // Проверяем основные слоты
        for (int i = 0; i < currentInventory.items.size(); i++) {
            ItemStack currentItem = currentInventory.items.get(i);
            ItemStack savedItem = savedItemsMap.get(i);
            
            if (!itemsMatch(currentItem, savedItem)) {
                if (VERBOSE_LOGGING.get()) {
                    System.out.println("[CSI] Несовпадение в основном слоте " + i + 
                                     ": текущий=" + (currentItem.isEmpty() ? "пусто" : currentItem.getItem().getDescriptionId()) +
                                     ", сохраненный=" + (savedItem == null ? "null" : savedItem.getItem().getDescriptionId()));
                }
                return false;
            }
        }
        
        // Проверяем броню (слоты 100-103)
        for (int i = 0; i < currentInventory.armor.size(); i++) {
            ItemStack currentItem = currentInventory.armor.get(i);
            ItemStack savedItem = savedItemsMap.get(100 + i);
            
            if (!itemsMatch(currentItem, savedItem)) {
                if (VERBOSE_LOGGING.get()) {
                    System.out.println("[CSI] Несовпадение в броне " + i);
                }
                return false;
            }
        }
        
        // Проверяем оффхенд (слот 106)
        for (int i = 0; i < currentInventory.offhand.size(); i++) {
            ItemStack currentItem = currentInventory.offhand.get(i);
            ItemStack savedItem = savedItemsMap.get(106 + i);
            
            if (!itemsMatch(currentItem, savedItem)) {
                if (VERBOSE_LOGGING.get()) {
                    System.out.println("[CSI] Несовпадение в оффхенде " + i);
                }
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Преобразует относительный слот в абсолютный
     */
    private int getAbsoluteSlot(int relativeSlot, int type) {
        switch (type) {
            case 0: // Основной инвентарь
                return relativeSlot;
            case 1: // Броня
                return 100 + relativeSlot;
            case 2: // Оффхенд
                return 106 + relativeSlot;
            default:
                return relativeSlot;
        }
    }
    
    /**
     * Сравнивает два предмета
     */
    private boolean itemsMatch(ItemStack stack1, ItemStack stack2) {
        if (stack2 == null) {
            // Если в сохраненном нет предмета, то текущий должен быть пустым
            return stack1.isEmpty();
        }
        
        // Оба пустые - совпадают
        if (stack1.isEmpty() && stack2.isEmpty()) {
            return true;
        }
        
        // Один пустой, другой нет - не совпадают
        if (stack1.isEmpty() != stack2.isEmpty()) {
            return false;
        }
        
        // Сравниваем тип предмета и количество
        return stack1.getItem() == stack2.getItem() && 
               stack1.getCount() == stack2.getCount();
    }
    
    // ============================================================================
    // ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ
    // ============================================================================
    
    private void initGlobalStorage() {
        try {
            Path minecraftDir = Paths.get(".");
            globalStorageDir = minecraftDir.resolve("csi_storage");
            
            if (!Files.exists(globalStorageDir)) {
                Files.createDirectories(globalStorageDir);
            }
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка инициализации глобального хранилища: " + e.getMessage());
        }
    }
    
    private Path getPlayerSavePath(ServerPlayer player, boolean useGlobalStorage) {
        if (useGlobalStorage && globalStorageDir != null) {
            return globalStorageDir.resolve(player.getUUID().toString() + ".dat");
        }
        return null;
    }
    
    private boolean saveToFile(ServerPlayer player, CompoundTag data) {
        if (!GLOBAL_INVENTORY_STORAGE.get()) {
            CompoundTag playerData = player.getPersistentData();
            if (!playerData.contains(Player.PERSISTED_NBT_TAG)) {
                playerData.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
            }
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            persisted.put(MODID, data);
            return true;
        }
        
        try {
            Path savePath = getPlayerSavePath(player, true);
            if (savePath == null) return false;
            
            net.minecraft.nbt.NbtIo.write(data, savePath);
            
            CompoundTag playerData = player.getPersistentData();
            if (!playerData.contains(Player.PERSISTED_NBT_TAG)) {
                playerData.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
            }
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            persisted.put(MODID, data);
            
            return true;
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка сохранения: " + e.getMessage());
            return false;
        }
    }
    
    private CompoundTag loadFromFile(ServerPlayer player) {
        if (GLOBAL_INVENTORY_STORAGE.get()) {
            try {
                Path savePath = getPlayerSavePath(player, true);
                if (savePath != null && Files.exists(savePath) && Files.size(savePath) > 0) {
                    return net.minecraft.nbt.NbtIo.read(savePath);
                }
            } catch (Exception e) {
                // Продолжаем с локальным хранилищем
            }
        }
        
        CompoundTag playerData = player.getPersistentData();
        if (!playerData.contains(Player.PERSISTED_NBT_TAG)) {
            return new CompoundTag();
        }
        
        CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
        if (!persisted.contains(MODID)) {
            return new CompoundTag();
        }
        
        return persisted.getCompound(MODID);
    }
    
    private boolean hasSavedInventory(ServerPlayer player) {
        if (GLOBAL_INVENTORY_STORAGE.get()) {
            Path savePath = getPlayerSavePath(player, true);
            if (savePath != null && Files.exists(savePath)) {
                try {
                    return Files.size(savePath) > 0;
                } catch (IOException ignored) {}
            }
        }
        
        CompoundTag playerData = player.getPersistentData();
        if (playerData.contains(Player.PERSISTED_NBT_TAG)) {
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            if (persisted.contains(MODID)) {
                CompoundTag csiData = persisted.getCompound(MODID);
                return csiData.contains("Inventory") || csiData.contains("Accessories");
            }
        }
        
        return false;
    }
    
    private boolean shouldGiveInventoryOnWorldJoin(ServerPlayer player) {
        if (!hasSavedInventory(player)) {
            return false;
        }
        
        CompoundTag playerData = player.getPersistentData();
        if (playerData.contains(Player.PERSISTED_NBT_TAG)) {
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            if (persisted.getBoolean("csi_inventory_received")) {
                return false;
            }
        }
        
        return true;
    }
    
    private void markInventoryReceived(ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        if (!playerData.contains(Player.PERSISTED_NBT_TAG)) {
            playerData.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean("csi_inventory_received", true);
    }
    
    private boolean shouldGiveStarterKit(ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        if (playerData.contains(Player.PERSISTED_NBT_TAG)) {
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            // Проверяем, получал ли игрок уже стартовый набор
            if (persisted.getBoolean("csi_starter_kit_received")) {
                return false;
            }
        }
        
        // Проверяем, есть ли сохраненный инвентарь
        if (hasSavedInventory(player)) {
            return false; // У игрока уже есть сохраненный инвентарь
        }
        
        // Проверяем, есть ли стандартный набор для выдачи
        return ServerInventoryManager.getDefaultKit() != null;
    }
    
    private void markStarterKitReceived(ServerPlayer player) {
        CompoundTag playerData = player.getPersistentData();
        if (!playerData.contains(Player.PERSISTED_NBT_TAG)) {
            playerData.put(Player.PERSISTED_NBT_TAG, new CompoundTag());
        }
        CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
        persisted.putBoolean("csi_starter_kit_received", true);
    }
    
    // ============================================================================
    // КОМАНДЫ
    // ============================================================================
    
    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("csi")
            .requires(source -> source.hasPermission(3))
            .then(Commands.literal("save")
                .executes(context -> handleSave(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> handleSave(context.getSource(), EntityArgument.getPlayer(context, "target")))))
            .then(Commands.literal("load")
                .executes(context -> handleLoad(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> handleLoad(context.getSource(), EntityArgument.getPlayer(context, "target")))))
            .then(Commands.literal("remove")
                .executes(context -> handleRemove(context.getSource(), context.getSource().getPlayerOrException()))
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> handleRemove(context.getSource(), EntityArgument.getPlayer(context, "target")))))
            .then(Commands.literal("kit")
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("create")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .executes(context -> handleKitCreate(
                            context.getSource(), 
                            context.getSource().getPlayerOrException(),
                            StringArgumentType.getString(context, "kitname")
                        ))
                    )
                )
                .then(Commands.argument("kitname", StringArgumentType.word())
                    .executes(context -> handleKitGive(
                        context.getSource(),
                        context.getSource().getPlayerOrException(),
                        StringArgumentType.getString(context, "kitname")
                    ))
                    .then(Commands.argument("target", EntityArgument.player())
                        .executes(context -> handleKitGive(
                            context.getSource(),
                            EntityArgument.getPlayer(context, "target"),
                            StringArgumentType.getString(context, "kitname")
                        ))
                    )
                )
                .then(Commands.literal("list")
                    .executes(context -> handleKitList(context.getSource()))
                )
                .then(Commands.literal("remove")
                    .then(Commands.argument("kitname", StringArgumentType.word())
                        .executes(context -> handleKitRemove(
                            context.getSource(),
                            StringArgumentType.getString(context, "kitname")
                        ))
                        .then(Commands.literal("confirm")
                            .executes(context -> handleKitRemoveConfirm(
                                context.getSource(),
                                StringArgumentType.getString(context, "kitname")
                            ))
                        )
                    )
                    .then(Commands.literal("all")
                        .executes(context -> handleKitRemoveAll(context.getSource()))
                        .then(Commands.literal("confirm")
                            .executes(context -> handleKitRemoveAllConfirm(context.getSource()))
                        )
                    )
                )
                .then(Commands.literal("reload")
                    .executes(context -> handleKitReload(context.getSource()))
                )
            )
            .then(Commands.literal("help")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                        "=== Custom Start Inventory ===\n" +
                        "/csi save [player] - Сохранить инвентарь\n" +
                        "/csi load [player] - Загрузить инвентарь\n" +
                        "/csi remove [player] - Удалить сохраненный инвентарь\n" +
                        "/csi kit create <name> - Создать набор из инвентаря\n" +
                        "/csi kit <name> [player] - Выдать набор\n" +
                        "/csi kit list - Список всех наборов\n" +
                        "/csi kit remove <name> - Удалить набор\n" +
                        "/csi kit remove all - Удалить все наборов\n" +
                        "/csi kit reload - Перезагрузить конфигурацию\n\n" +
                        "Система автоматически следит за инвентарем"
                    ), false);
                    return 1;
                }))
        );
    }
    
    private int handleSave(CommandSourceStack source, ServerPlayer target) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        if (INTELLIGENT_SCANNING.get() && countAllItems(target) == 0) {
            source.sendFailure(Component.literal("§cCannot save empty inventory when intelligent scanning is enabled"));
            return 0;
        }

        boolean saved = saveInventory(target);
        
        if (saved) {
            source.sendSuccess(() -> Component.literal("§aInventory saved from " + target.getName().getString()), false);
            
            if (ENABLE_SOUNDS.get()) {
                playSound(source, target, SoundEvents.NOTE_BLOCK_PLING.value());
            }
            return 1;
        } else {
            source.sendFailure(Component.literal("§cFailed to save inventory"));
            return 0;
        }
    }

    private int handleRemove(CommandSourceStack source, ServerPlayer target) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        if (GLOBAL_INVENTORY_STORAGE.get()) {
            Path savePath = getPlayerSavePath(target, true);
            if (savePath != null && Files.exists(savePath)) {
                try {
                    Files.delete(savePath);
                } catch (IOException ignored) {}
            }
        }
        
        CompoundTag playerData = target.getPersistentData();
        if (playerData.contains(Player.PERSISTED_NBT_TAG)) {
            CompoundTag persisted = playerData.getCompound(Player.PERSISTED_NBT_TAG);
            persisted.remove(MODID);
            persisted.remove("csi_inventory_received");
        }
        
        source.sendSuccess(() -> Component.literal("§aSaved inventory deleted for " + target.getName().getString()), false);
        
        if (ENABLE_SOUNDS.get()) {
            playSound(source, target, SoundEvents.NOTE_BLOCK_HAT.value());
        }
        return 1;
    }
    
    private int handleLoad(CommandSourceStack source, ServerPlayer target) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        aggressiveClearInventory(target);
        boolean loaded = loadInventory(target);
        
        if (loaded) {
            source.sendSuccess(() -> Component.literal("§aInventory loaded for " + target.getName().getString()), false);
            
            if (ENABLE_SOUNDS.get()) {
                playSound(source, target, SoundEvents.EXPERIENCE_ORB_PICKUP);
            }
            
            markInventoryReceived(target);
            return 1;
        } else {
            source.sendFailure(Component.literal("§cNo saved inventory found"));
            return 0;
        }
    }
    
    private int handleKitCreate(CommandSourceStack source, ServerPlayer player, String kitName) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        boolean success = ServerInventoryManager.createKitFromInventory(player, kitName);
        if (success) {
            source.sendSuccess(() -> Component.literal("§aНабор '" + kitName + "' создан из вашего инвентаря"), false);
        } else {
            source.sendFailure(Component.literal("§cОшибка: набор с таким именем уже существует"));
        }
        return success ? 1 : 0;
    }

    private int handleKitGive(CommandSourceStack source, ServerPlayer target, String kitName) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        boolean success = ServerInventoryManager.giveKitToPlayer(target, kitName);
        if (success) {
            source.sendSuccess(() -> Component.literal("§aНабор '" + kitName + "' выдан игроку " + target.getName().getString()), false);
        } else {
            source.sendFailure(Component.literal("§cНабор '" + kitName + "' не найден"));
        }
        return success ? 1 : 0;
    }

    private int handleKitList(CommandSourceStack source) {
        List<ServerInventoryManager.StarterKit> kits = ServerInventoryManager.getAllKits();
        
        if (kits.isEmpty()) {
            source.sendSuccess(() -> Component.literal("§eНет сохраненных наборов"), false);
            source.sendSuccess(() -> Component.literal("§7Используйте §e/csi kit create <name>§7 для создания"), false);
        } else {
            source.sendSuccess(() -> Component.literal("§a=== Доступные наборы ==="), false);
            
            for (ServerInventoryManager.StarterKit kit : kits) {
                String autoGive = kit.giveOnFirstJoin ? "§a(авто)" : "§7(ручная)";
                int totalItems = kit.items.size() + kit.armor.size() + kit.offhand.size();
                source.sendSuccess(() -> Component.literal("§e" + kit.kitName + " §f- " + autoGive + " §7(" + totalItems + " предметов)"), false);
            }
        }
        
        source.sendSuccess(() -> Component.literal("§a=== Команды ==="), false);
        source.sendSuccess(() -> Component.literal("§e/csi kit create <name>§7 - создать из инвентаря"), false);
        source.sendSuccess(() -> Component.literal("§e/csi kit <name> [player]§7 - выдать набор"), false);
        source.sendSuccess(() -> Component.literal("§e/csi kit remove <name>§7 - удалить набор"), false);
        source.sendSuccess(() -> Component.literal("§e/csi kit remove all§7 - удалить все наборы"), false);
        source.sendSuccess(() -> Component.literal("§e/csi kit reload§7 - перезагрузить конфиг"), false);
        
        return kits.size();
    }

    private int handleKitRemove(CommandSourceStack source, String kitName) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        if (kitName.equalsIgnoreCase("all")) {
            return handleKitRemoveAll(source);
        }
        
        source.sendSuccess(() -> Component.literal("§cВы собираетесь удалить набор '" + kitName + "'"), false);
        source.sendSuccess(() -> Component.literal("§cЭто действие нельзя отменить!"), false);
        source.sendSuccess(() -> Component.literal("§aДля подтверждения используйте: §e/csi kit remove " + kitName + " confirm"), false);
        return 0;
    }

    private int handleKitRemoveConfirm(CommandSourceStack source, String kitName) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        boolean success = ServerInventoryManager.removeKit(kitName);
        if (success) {
            source.sendSuccess(() -> Component.literal("§aНабор '" + kitName + "' удален"), false);
        } else {
            source.sendFailure(Component.literal("§cНабор '" + kitName + "' не найден"));
        }
        return success ? 1 : 0;
    }

    private int handleKitRemoveAll(CommandSourceStack source) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        source.sendSuccess(() -> Component.literal("§cВНИМАНИЕ: Вы собираетесь удалить ВСЕ наборы!"), false);
        source.sendSuccess(() -> Component.literal("§cЭто действие нельзя отменить!"), false);
        source.sendSuccess(() -> Component.literal("§aДля подтверждения используйте: §e/csi kit remove all confirm"), false);
        return 0;
    }

    private int handleKitRemoveAllConfirm(CommandSourceStack source) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        boolean success = ServerInventoryManager.removeAllKits();
        if (success) {
            source.sendSuccess(() -> Component.literal("§aВсе наборы удалены"), false);
        } else {
            source.sendFailure(Component.literal("§cОшибка при удалении всех наборов"));
        }
        return success ? 1 : 0;
    }

    private int handleKitReload(CommandSourceStack source) {
        if (!MOD_ENABLED.get()) {
            source.sendFailure(Component.literal("§cMod is disabled"));
            return 0;
        }
        
        ServerInventoryManager.reloadConfig();
        source.sendSuccess(() -> Component.literal("§aКонфигурация наборов перезагружена"), false);
        return 1;
    }
    
    private boolean saveInventory(ServerPlayer player) {
        CompoundTag csiTag = new CompoundTag();
        Inventory inventory = player.getInventory();
        ListTag inventoryTag = new ListTag();
        
        for (int i = 0; i < inventory.items.size(); i++) {
            ItemStack item = inventory.items.get(i);
            if (!item.isEmpty()) {
                CompoundTag itemTag = saveItemStackSimple(player, item);
                if (!itemTag.isEmpty()) {
                    CompoundTag slotTag = new CompoundTag();
                    slotTag.putByte("Slot", (byte) i);
                    slotTag.putByte("Type", (byte) 0);
                    slotTag.put("Item", itemTag);
                    inventoryTag.add(slotTag);
                }
            }
        }
        
        for (int i = 0; i < inventory.armor.size(); i++) {
            ItemStack item = inventory.armor.get(i);
            if (!item.isEmpty()) {
                CompoundTag itemTag = saveItemStackSimple(player, item);
                if (!itemTag.isEmpty()) {
                    CompoundTag slotTag = new CompoundTag();
                    slotTag.putByte("Slot", (byte) i);
                    slotTag.putByte("Type", (byte) 1);
                    slotTag.put("Item", itemTag);
                    inventoryTag.add(slotTag);
                }
            }
        }
        
        for (int i = 0; i < inventory.offhand.size(); i++) {
            ItemStack item = inventory.offhand.get(i);
            if (!item.isEmpty()) {
                CompoundTag itemTag = saveItemStackSimple(player, item);
                if (!itemTag.isEmpty()) {
                    CompoundTag slotTag = new CompoundTag();
                    slotTag.putByte("Slot", (byte) i);
                    slotTag.putByte("Type", (byte) 2);
                    slotTag.put("Item", itemTag);
                    inventoryTag.add(slotTag);
                }
            }
        }
        
        csiTag.put("Inventory", inventoryTag);

        if (SAVE_ACCESSORIES.get()) {
            CompoundTag accessoriesTag = new CompoundTag();
            if (hasCurios && (ACCESSORIES_MODE.get() == AccessoryMode.CURIOS || 
                             ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                             ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
                ListTag curiosList = CuriosAccessoriesHandler.saveCurios(player);
                if (!curiosList.isEmpty()) {
                    accessoriesTag.put("Curios", curiosList);
                }
            }
            if (hasAccessories && (ACCESSORIES_MODE.get() == AccessoryMode.ACCESSORIES || 
                                  ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                                  ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
                CompoundTag wispData = WispForestAccessoriesHandler.saveAccessories(player);
                if (!wispData.isEmpty()) {
                    accessoriesTag.put("WispForest", wispData);
                }
            }
            if (!accessoriesTag.isEmpty()) {
                csiTag.put("Accessories", accessoriesTag);
            }
        }
        
        return saveToFile(player, csiTag);
    }

    private boolean loadInventory(ServerPlayer player) {
        CompoundTag csiTag = loadFromFile(player);
        
        if (!csiTag.contains("Inventory") && !csiTag.contains("Accessories")) {
            return false;
        }

        boolean inventoryLoaded = false;
        
        if (csiTag.contains("Inventory")) {
            ListTag inventoryTag = csiTag.getList("Inventory", Tag.TAG_COMPOUND);
            Inventory inventory = player.getInventory();
            
            for (int i = 0; i < inventoryTag.size(); ++i) {
                CompoundTag slotTag = inventoryTag.getCompound(i);
                int slot = slotTag.getByte("Slot") & 255;
                int type = slotTag.getByte("Type") & 255;
                
                if (slotTag.contains("Item")) {
                    CompoundTag itemTag = slotTag.getCompound("Item");
                    ItemStack stack = loadItemStackSimple(player, itemTag);
                    
                    if (!stack.isEmpty()) {
                        switch (type) {
                            case 0:
                                if (slot >= 0 && slot < inventory.items.size()) {
                                    inventory.items.set(slot, stack);
                                }
                                break;
                            case 1:
                                if (slot >= 0 && slot < inventory.armor.size()) {
                                    inventory.armor.set(slot, stack);
                                }
                                break;
                            case 2:
                                if (slot >= 0 && slot < inventory.offhand.size()) {
                                    inventory.offhand.set(slot, stack);
                                }
                                break;
                        }
                    }
                }
            }
            
            player.inventoryMenu.slotsChanged(inventory);
            inventoryLoaded = !inventoryTag.isEmpty();
        }

        if (SAVE_ACCESSORIES.get() && csiTag.contains("Accessories")) {
            CompoundTag accessoriesTag = csiTag.getCompound("Accessories");
            
            if (hasCurios && (ACCESSORIES_MODE.get() == AccessoryMode.CURIOS || 
                             ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                             ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
                if (accessoriesTag.contains("Curios")) {
                    CompoundTag curiosData = new CompoundTag();
                    curiosData.put("CuriosList", accessoriesTag.getList("Curios", Tag.TAG_COMPOUND));
                    CuriosAccessoriesHandler.loadCurios(player, curiosData);
                }
            }

            if (hasAccessories && (ACCESSORIES_MODE.get() == AccessoryMode.ACCESSORIES || 
                                  ACCESSORIES_MODE.get() == AccessoryMode.AUTO || 
                                  ACCESSORIES_MODE.get() == AccessoryMode.BOTH)) {
                if (accessoriesTag.contains("WispForest")) {
                    WispForestAccessoriesHandler.loadAccessories(player, accessoriesTag.getCompound("WispForest"));
                }
            }
        }
        
        return inventoryLoaded;
    }

    private CompoundTag saveItemStackSimple(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return new CompoundTag();
        
        try {
            Tag savedTag = stack.save(player.registryAccess());
            if (savedTag instanceof CompoundTag) {
                return (CompoundTag) savedTag;
            } else {
                CompoundTag tag = new CompoundTag();
                stack.save(player.registryAccess(), tag);
                return tag;
            }
        } catch (Exception e) {
            return new CompoundTag();
        }
    }

    private ItemStack loadItemStackSimple(ServerPlayer player, CompoundTag tag) {
        try {
            if (tag.isEmpty()) return ItemStack.EMPTY;
            Optional<ItemStack> optionalStack = ItemStack.parse(player.registryAccess(), tag);
            return optionalStack.orElse(ItemStack.EMPTY);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }
    
    private void playSound(CommandSourceStack source, ServerPlayer player, net.minecraft.sounds.SoundEvent sound) {
        if (player != null && source.getLevel() != null) {
            source.getLevel().playSound(null, player.getX(), player.getY(), player.getZ(),
                sound, SoundSource.PLAYERS, 0.5F, 1.0F);
        }
    }
}