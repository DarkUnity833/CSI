package net.darkunity.customstartinventory.handlers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;

public class CuriosAccessoriesHandler {
    private static final String CURIOS_API_CLASS = "top.theillusivec4.curios.api.CuriosApi";

    public static boolean hasCurios() { 
        try {
            Class.forName(CURIOS_API_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    public static ListTag saveCurios(ServerPlayer player) { 
        ListTag curiosList = new ListTag();
        if (!hasCurios()) return curiosList;

        try {
            Class<?> apiClass = Class.forName(CURIOS_API_CLASS);
            // Получаем помощника: CuriosApi.getCuriosHelper()
            Object helper = apiClass.getMethod("getCuriosHelper").invoke(null);
            
            // Получаем Optional<ICuriosItemHandler>: helper.getCuriosHandler(player)
            Object optionalHandler = helper.getClass().getMethod("getCuriosHandler", net.minecraft.world.entity.LivingEntity.class).invoke(helper, player);
            
            Optional<?> opt = (Optional<?>) optionalHandler;
            if (opt.isEmpty()) return curiosList;

            Object handler = opt.get();
            // Получаем карту слотов: handler.getCurios() -> Map<String, ICurioStacksHandler>
            Map<String, ?> curiosMap = (Map<String, ?>) handler.getClass().getMethod("getCurios").invoke(handler);

            for (Map.Entry<String, ?> entry : curiosMap.entrySet()) {
                String slotId = entry.getKey();
                Object stacksHandler = entry.getValue(); // ICurioStacksHandler
                
                // Получаем IItemHandlerModifiable: stacksHandler.getStacks()
                Object inventory = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                
                int slots = (int) inventory.getClass().getMethod("getSlots").invoke(inventory);
                for (int i = 0; i < slots; i++) {
                    ItemStack stack = (ItemStack) inventory.getClass().getMethod("getStackInSlot", int.class).invoke(inventory, i);
                    if (!stack.isEmpty()) {
                        CompoundTag itemEntry = new CompoundTag();
                        itemEntry.putString("SlotId", slotId);
                        itemEntry.putInt("SlotIndex", i);
                        // Сохранение предмета (NeoForge способ)
                        itemEntry.put("Item", stack.save(player.registryAccess()));
                        curiosList.add(itemEntry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[CSI-Curios] Error during save: " + e.getMessage());
        }
        return curiosList;
    }

    public static boolean loadCurios(ServerPlayer player, CompoundTag accessoriesData) { 
        if (!hasCurios() || !accessoriesData.contains("CuriosList")) return false;
        ListTag list = accessoriesData.getList("CuriosList", Tag.TAG_COMPOUND);
        
        try {
            Class<?> apiClass = Class.forName(CURIOS_API_CLASS);
            Object helper = apiClass.getMethod("getCuriosHelper").invoke(null);
            Object optionalHandler = helper.getClass().getMethod("getCuriosHandler", net.minecraft.world.entity.LivingEntity.class).invoke(helper, player);
            
            Optional<?> opt = (Optional<?>) optionalHandler;
            if (opt.isEmpty()) return false;
            
            Object handler = opt.get();
            Map<String, ?> curiosMap = (Map<String, ?>) handler.getClass().getMethod("getCurios").invoke(handler);

            for (int i = 0; i < list.size(); i++) {
                CompoundTag entryTag = list.getCompound(i);
                String slotId = entryTag.getString("SlotId");
                int index = entryTag.getInt("SlotIndex");
                
                // Загрузка предмета
                Optional<ItemStack> stackOpt = ItemStack.parse(player.registryAccess(), entryTag.getCompound("Item"));
                
                if (stackOpt.isPresent() && curiosMap.containsKey(slotId)) {
                    Object stacksHandler = curiosMap.get(slotId);
                    Object inventory = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                    inventory.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(inventory, index, stackOpt.get());
                }
            }
            
            // Важно: Синхронизация данных с клиентом
            helper.getClass().getMethod("onInventoryTick", net.minecraft.world.entity.LivingEntity.class).invoke(helper, player);
            return true;
        } catch (Exception e) {
            System.err.println("[CSI-Curios] Error during load: " + e.getMessage());
            return false;
        }
    }

    public static void clearAccessories(ServerPlayer player) {
        if (!hasCurios()) return;
        try {
            Class<?> apiClass = Class.forName(CURIOS_API_CLASS);
            Object helper = apiClass.getMethod("getCuriosHelper").invoke(null);
            Object optionalHandler = helper.getClass().getMethod("getCuriosHandler", net.minecraft.world.entity.LivingEntity.class).invoke(helper, player);
            
            Optional<?> opt = (Optional<?>) optionalHandler;
            if (opt.isEmpty()) return;

            Object handler = opt.get();
            Map<String, ?> curiosMap = (Map<String, ?>) handler.getClass().getMethod("getCurios").invoke(handler);

            for (Object stacksHandler : curiosMap.values()) {
                Object inventory = stacksHandler.getClass().getMethod("getStacks").invoke(stacksHandler);
                int slots = (int) inventory.getClass().getMethod("getSlots").invoke(inventory);
                for (int i = 0; i < slots; i++) {
                    inventory.getClass().getMethod("setStackInSlot", int.class, ItemStack.class).invoke(inventory, i, ItemStack.EMPTY);
                }
            }
        } catch (Exception e) {
            // Игнорируем ошибки при очистке
        }
    }
}