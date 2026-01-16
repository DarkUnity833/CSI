package net.darkunity.customstartinventory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ServerInventoryManager {
    private static final String CONFIG_FILE_NAME = "starter-kits.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;
    private static StarterKit defaultStarterKit;
    
    // Структура для хранения набора предметов
    public static class StarterKit {
        public String kitName;
        public boolean giveOnFirstJoin; // Выдавать при первом входе
        public List<KitItem> items;
        public List<KitItem> armor;
        public List<KitItem> offhand;
        
        public StarterKit() {
            this.items = new ArrayList<>();
            this.armor = new ArrayList<>();
            this.offhand = new ArrayList<>();
        }
        
        public StarterKit(String name) {
            this();
            this.kitName = name;
            this.giveOnFirstJoin = false; // По умолчанию только ручная выдача
        }
    }
    
    public static class KitItem {
        public String itemId;
        public int slot;
        public int count;
        
        public KitItem() {}
        
        public KitItem(String itemId, int slot, int count) {
            this.itemId = itemId;
            this.slot = slot;
            this.count = count;
        }
    }
    
    // Инициализация и загрузка конфигурации
    public static void init() {
        try {
            Path configDir = Paths.get("config", "customstartinventory");
            configPath = configDir.resolve(CONFIG_FILE_NAME);
            
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
            }
            
            // Если файл не существует, создаем пример конфигурации
            if (!Files.exists(configPath)) {
                createExampleConfig();
                System.out.println("[CSI] Создан пустой файл конфигурации starter-kits.json");
                System.out.println("[CSI] Используйте команды:");
                System.out.println("[CSI]   /csi kit create <name> - создать набор из вашего инвентаря");
                System.out.println("[CSI]   /csi kit <name> - выдать набор себе");
                System.out.println("[CSI]   /csi kit <name> <player> - выдать набор другому игроку");
                System.out.println("[CSI]   /csi kit list - список всех наборов");
            }
            
            // Загружаем конфигурацию
            loadConfig();
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка инициализации ServerInventoryManager: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Создание примера конфигурации
    private static void createExampleConfig() throws IOException {
        List<StarterKit> kits = new ArrayList<>();
        
        // Сохраняем пустой список
        saveConfig(kits);
    }
    
    // Загрузка конфигурации из файла
    private static void loadConfig() {
        try {
            if (!Files.exists(configPath)) {
                defaultStarterKit = null;
                return;
            }
            
            try (Reader reader = new FileReader(configPath.toFile())) {
                List<StarterKit> kits = GSON.fromJson(reader, new TypeToken<List<StarterKit>>(){}.getType());
                
                if (kits != null) {
                    // Находим набор для автоматической выдачи
                    for (StarterKit kit : kits) {
                        if (kit.giveOnFirstJoin) {
                            defaultStarterKit = kit;
                            System.out.println("[CSI] Загружен набор для автоматической выдачи: " + kit.kitName);
                            break;
                        }
                    }
                    
                    System.out.println("[CSI] Загружено " + kits.size() + " наборов предметов");
                }
            }
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка загрузки конфигурации: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    // Сохранение конфигурации в файл
    private static void saveConfig(List<StarterKit> kits) throws IOException {
        String json = GSON.toJson(kits);
        Files.writeString(configPath, json);
    }
    
    // Получение всех наборов
    public static List<StarterKit> getAllKits() {
        try {
            if (!Files.exists(configPath)) {
                return new ArrayList<>();
            }
            
            try (Reader reader = new FileReader(configPath.toFile())) {
                List<StarterKit> kits = GSON.fromJson(reader, new TypeToken<List<StarterKit>>(){}.getType());
                return kits != null ? kits : new ArrayList<>();
            }
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка получения списка наборов: " + e.getMessage());
            return new ArrayList<>();
        }
    }
    
    // Получение набора по имени
    public static StarterKit getKit(String kitName) {
        List<StarterKit> kits = getAllKits();
        for (StarterKit kit : kits) {
            if (kit.kitName.equalsIgnoreCase(kitName)) {
                return kit;
            }
        }
        return null;
    }
    
    // Получение стандартного набора (для автоматической выдачи)
    public static StarterKit getDefaultKit() {
        return defaultStarterKit;
    }
    
    // Создание нового набора из инвентаря игрока
    public static boolean createKitFromInventory(ServerPlayer player, String kitName) {
        try {
            List<StarterKit> kits = getAllKits();
            
            // Проверяем, не существует ли уже набор с таким именем
            for (StarterKit kit : kits) {
                if (kit.kitName.equalsIgnoreCase(kitName)) {
                    return false; // Набор уже существует
                }
            }
            
            // Создаем новый набор
            StarterKit newKit = new StarterKit(kitName);
            newKit.kitName = kitName;
            newKit.giveOnFirstJoin = false;
            
            // Сохраняем основной инвентарь
            for (int i = 0; i < player.getInventory().items.size(); i++) {
                ItemStack stack = player.getInventory().items.get(i);
                if (!stack.isEmpty()) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    KitItem item = new KitItem(
                        itemId.toString(),
                        i,
                        stack.getCount()
                    );
                    newKit.items.add(item);
                }
            }
            
            // Сохраняем броню
            for (int i = 0; i < player.getInventory().armor.size(); i++) {
                ItemStack stack = player.getInventory().armor.get(i);
                if (!stack.isEmpty()) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    KitItem item = new KitItem(
                        itemId.toString(),
                        i,
                        stack.getCount()
                    );
                    newKit.armor.add(item);
                }
            }
            
            // Сохраняем оффхенд
            if (!player.getInventory().offhand.isEmpty()) {
                ItemStack stack = player.getInventory().offhand.get(0);
                if (!stack.isEmpty()) {
                    ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    KitItem item = new KitItem(
                        itemId.toString(),
                        0,
                        stack.getCount()
                    );
                    newKit.offhand.add(item);
                }
            }
            
            // Добавляем новый набор и сохраняем
            kits.add(newKit);
            saveConfig(kits);
            
            // Перезагружаем конфигурацию
            loadConfig();
            
            System.out.println("[CSI] Создан новый набор: " + kitName + " из инвентаря " + player.getName().getString());
            return true;
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка создания набора: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    // Удаление набора
    public static boolean removeKit(String kitName) {
        try {
            List<StarterKit> kits = getAllKits();
            
            // Проверяем, существует ли набор
            boolean found = false;
            StarterKit kitToRemove = null;
            for (StarterKit kit : kits) {
                if (kit.kitName.equalsIgnoreCase(kitName)) {
                    kitToRemove = kit;
                    found = true;
                    break;
                }
            }
            
            if (!found) {
                return false;
            }
            
            // Удаляем набор
            kits.remove(kitToRemove);
            saveConfig(kits);
            
            // Перезагружаем конфигурацию
            loadConfig();
            
            System.out.println("[CSI] Набор удален: " + kitName);
            return true;
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка удаления набора: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    // Удаление всех наборов
    public static boolean removeAllKits() {
        try {
            List<StarterKit> kits = new ArrayList<>();
            saveConfig(kits);
            
            // Перезагружаем конфигурацию
            loadConfig();
            
            System.out.println("[CSI] Все наборы удалены");
            return true;
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка удаления всех наборов: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    // Выдача набора игроку
    public static boolean giveKitToPlayer(ServerPlayer player, String kitName) {
        StarterKit kit = getKit(kitName);
        if (kit == null) {
            return false;
        }
        
        System.out.println("[CSI] Выдача набора '" + kit.kitName + "' игроку " + player.getName().getString());
        
        try {
            // Выдача предметов в основной инвентарь
            for (KitItem kitItem : kit.items) {
                giveItemToSlot(player, kitItem, false);
            }
            
            // Выдача брони
            for (KitItem kitItem : kit.armor) {
                giveItemToSlot(player, kitItem, true);
            }
            
            // Выдача оффхенда
            for (KitItem kitItem : kit.offhand) {
                giveOffhandItem(player, kitItem);
            }
            
            // Обновление инвентаря
            player.inventoryMenu.slotsChanged(player.getInventory());
            
            System.out.println("[CSI] Набор успешно выдан");
            return true;
            
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка выдачи набора: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    // Выдача стандартного набора при первом входе
    public static void giveDefaultKitOnFirstJoin(ServerPlayer player) {
        if (defaultStarterKit != null) {
            System.out.println("[CSI] Выдача стандартного набора новому игроку: " + player.getName().getString());
            giveKitToPlayer(player, defaultStarterKit.kitName);
            
            // Отправляем сообщение игроку
            if (!CustomStartInventory.HIDE_CHAT_MESSAGES.get()) {
                player.sendSystemMessage(Component.literal("§a[CSI] §fПолучен стартовый набор!"));
            }
        }
    }
    
    // Вспомогательный метод: выдача предмета в слот
    private static void giveItemToSlot(ServerPlayer player, KitItem kitItem, boolean isArmor) {
        try {
            // Разбираем ID предмета
            ResourceLocation itemId = ResourceLocation.parse(kitItem.itemId);
            Item item = BuiltInRegistries.ITEM.get(itemId);
            
            if (item == null || item == Items.AIR) {
                System.out.println("[CSI] Предмет не найден: " + kitItem.itemId);
                return;
            }
            
            ItemStack stack = new ItemStack(item, kitItem.count);
            
            if (isArmor) {
                // Для брони
                if (kitItem.slot >= 0 && kitItem.slot < player.getInventory().armor.size()) {
                    player.getInventory().armor.set(kitItem.slot, stack);
                }
            } else {
                // Для основного инвентаря
                if (kitItem.slot >= 0 && kitItem.slot < player.getInventory().items.size()) {
                    player.getInventory().items.set(kitItem.slot, stack);
                }
            }
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка выдачи предмета: " + kitItem.itemId + " - " + e.getMessage());
        }
    }
    
    // Вспомогательный метод: выдача предмета в оффхенд
    private static void giveOffhandItem(ServerPlayer player, KitItem kitItem) {
        try {
            // Разбираем ID предмета
            ResourceLocation itemId = ResourceLocation.parse(kitItem.itemId);
            Item item = BuiltInRegistries.ITEM.get(itemId);
            
            if (item == null || item == Items.AIR) {
                System.out.println("[CSI] Предмет не найден: " + kitItem.itemId);
                return;
            }
            
            ItemStack stack = new ItemStack(item, kitItem.count);
            
            if (!player.getInventory().offhand.isEmpty()) {
                player.getInventory().offhand.set(0, stack);
            }
        } catch (Exception e) {
            System.out.println("[CSI] Ошибка выдачи предмета в оффхенд: " + kitItem.itemId + " - " + e.getMessage());
        }
    }
    
    // Перезагрузка конфигурации
    public static void reloadConfig() {
        loadConfig();
        System.out.println("[CSI] Конфигурация наборов перезагружена");
    }
}