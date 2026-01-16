package net.darkunity.customstartinventory.handlers;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.world.ContainerHelper;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Обработчик сохранения и загрузки аксессуаров через WispForest Accessories API (Forge/NeoForge).
 * ФИНАЛЬНЫЙ ФИКС V11: 4-уровневый агрессивный поиск с обходом иерархии классов и агрессивной синхронизацией.
 * Найдено решение: ExpandedSimpleContainer наследует net.minecraft.world.SimpleContainer,
 * который содержит приватное поле "items".
 */
public class WispForestAccessoriesHandler {

    private static final String ACC_API_CLASS = "io.wispforest.accessories.api.AccessoriesAPI";
    private static final String ACCESSORIES_HOLDER_CLASS = "io.wispforest.accessories.api.AccessoriesHolder";
    
    private static Boolean apiAvailableCache = null;
    private static Method cachedGetHolderMethod = null;
    private static Class<?> accessoriesHolderClassCache = null;
    private static boolean isEntityMethodCache = false;
    
    // Вспомогательный класс для кэширования пути доступа к List<ItemStack> внутри L3 (ExpandedSimpleContainer)
    private static class ListAccessor {
        public Object listAccessor; // Field или Method для получения List<ItemStack>
        public boolean isField;
    }
    private static ListAccessor cachedListAccessor = null;

    // Методы NBT для 1.21+ Forge (с HolderLookup.Provider)
    private static final String[] NBT_METHODS_PROVIDER = new String[]{"serializeNBT", "deserializeNBT"};
    // Методы NBT для сохранения/загрузки без провайдера
    private static final String[] NBT_METHODS_NO_PROVIDER = new String[]{"toNbt", "writeNbt", "writeToNbt", "write", "save", "toTag", "serializeNBT", "readNbt", "readFromNbt", "read", "load", "fromTag", "deserializeNBT"};
    
    private static final String CONTAINER_TAG_KEY = "CSI_Accessories_Fallback_Inventory";


    public static boolean hasAccessories() {
        if (apiAvailableCache == null) {
            try {
                Class.forName(ACC_API_CLASS);
                accessoriesHolderClassCache = Class.forName(ACCESSORIES_HOLDER_CLASS);
                apiAvailableCache = true;
                System.out.println("[WispForestHandler] Accessories API is available");
            } catch (ClassNotFoundException e) {
                apiAvailableCache = false;
                System.out.println("[WispForestHandler] Accessories API or Holder class not found");
            }
        }
        return apiAvailableCache;
    }

    private static Optional<Method> findAndCacheGetHolderMethod(ServerPlayer player) {
        if (cachedGetHolderMethod != null) {
            return Optional.of(cachedGetHolderMethod);
        }
        
        // Поиск инжектированного метода (как уже найдено в логах)
        try {
            Class<?> entityClass = player.getClass();
            for (Method method : entityClass.getMethods()) {
                if (method.getParameterTypes().length == 0 && (method.getName().contains("accessories") || method.getName().contains("Accessories") || method.getName().contains("Holder"))) {
                     if (Optional.class.isAssignableFrom(method.getReturnType()) || accessoriesHolderClassCache.isAssignableFrom(method.getReturnType())) {
                            cachedGetHolderMethod = method;
                            isEntityMethodCache = true;
                            System.out.println("[WispForestHandler] FOUND INJECTED METHOD: " + method.getName() + "() in LivingEntity.");
                            return Optional.of(cachedGetHolderMethod);
                     }
                }
            }
        } catch (Exception e) {}
        
        return Optional.empty();
    }
    
    private static Optional<?> getAccessoryHolder(ServerPlayer player) throws Exception {
        if (!hasAccessories()) return Optional.empty();
        
        Optional<Method> methodOpt = findAndCacheGetHolderMethod(player);
        if (methodOpt.isEmpty()) {
            System.out.println("[WispForestHandler] ERROR: No valid accessor method found.");
            return Optional.empty();
        }
        
        Method getHolderMethod = methodOpt.get();
        Object targetInstance = isEntityMethodCache ? player : null;
        Object[] args = isEntityMethodCache ? new Object[0] : new Object[]{player};
        
        System.out.println("[WispForestHandler] Attempting to invoke accessor method: " + getHolderMethod.getName() + "()...");

        try {
            Object result = getHolderMethod.invoke(targetInstance, args);

            if (result != null && accessoriesHolderClassCache.isInstance(result)) {
                System.out.println("[WispForestHandler] SUCCESS: Retrieved holder directly via " + getHolderMethod.getName() + "().");
                return Optional.of(result);
            }
            
        } catch (java.lang.reflect.InvocationTargetException e) {
             System.out.println("[WispForestHandler] FATAL: " + getHolderMethod.getName() + "() FAILED INTERNALLY. Cause: " + e.getCause().getMessage());
             e.getCause().printStackTrace();
        }

        System.out.println("[WispForestHandler] Failed to retrieve accessory holder (returned null or unexpected type).");
        return Optional.empty();
    }


    /**
     * Инициализирует и кэширует путь доступа к L4 (List<ItemStack>) внутри L3 (ExpandedSimpleContainer) и его суперклассах.
     */
    private static boolean initializeAccessors(Object capability) throws Exception {
        if (cachedListAccessor != null) return true;

        Class<?> capabilityClass = capability.getClass();
        
        // --- L2 Access: getSlotContainers() -> Map<SlotType, AccessoriesContainerImpl> ---
        Method getContainersMethod = capabilityClass.getDeclaredMethod("getSlotContainers");
        getContainersMethod.setAccessible(true);
        Map<?, ?> containersMap = (Map<?, ?>) getContainersMethod.invoke(capability);
        
        if (containersMap == null || containersMap.isEmpty()) {
             System.out.println("[WispForestHandler] Slot containers map is empty. Cannot proceed with initialization.");
             return false;
        }
        
        // --- L3 Access: getAccessories() -> ExpandedSimpleContainer ---
        Object firstContainerL2 = containersMap.values().iterator().next();
        Class<?> containerClassL2 = firstContainerL2.getClass();
        
        Method getAccessoriesMethod = containerClassL2.getDeclaredMethod("getAccessories");
        getAccessoriesMethod.setAccessible(true); // Убедимся, что этот метод доступен
        Object firstContainerL3 = getAccessoriesMethod.invoke(firstContainerL2); // Это ExpandedSimpleContainer
        
        Class<?> containerClassL3 = firstContainerL3.getClass();

        // --- L4 Access: Aggressive search for the List<ItemStack> inside L3 and its Superclasses ---
        
        Class<?> currentClass = containerClassL3;
        while (currentClass != null && currentClass != Object.class) {
            
            // Priority 1: Field named 'items' or 'stacks' of type List/NonNullList
            for (String fieldName : new String[]{"items", "stacks", "list", "inventory"}) {
                try {
                    Field listField = currentClass.getDeclaredField(fieldName);
                    if (Collection.class.isAssignableFrom(listField.getType())) {
                        listField.setAccessible(true);
                        
                        cachedListAccessor = new ListAccessor();
                        cachedListAccessor.listAccessor = listField;
                        cachedListAccessor.isField = true;
                        System.out.println("[WispForestHandler] FINAL ACCESSOR SUCCESS (Field:" + fieldName + ") found in class hierarchy: " + currentClass.getName());
                        return true;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            
            // Priority 2: Method named 'getStacks' or 'getItems' returning List/NonNullList
            for (String methodName : new String[]{"getStacks", "getItems", "getList"}) {
                 try {
                    Method listMethod = currentClass.getDeclaredMethod(methodName);
                    if (Collection.class.isAssignableFrom(listMethod.getReturnType())) {
                        listMethod.setAccessible(true);
                        
                        cachedListAccessor = new ListAccessor();
                        cachedListAccessor.listAccessor = listMethod;
                        cachedListAccessor.isField = false;
                        System.out.println("[WispForestHandler] FINAL ACCESSOR SUCCESS (Method:" + methodName + ") found in class hierarchy: " + currentClass.getName());
                        return true;
                    }
                 } catch (NoSuchMethodException ignored) {}
            }
            
            // Move up the hierarchy
            currentClass = currentClass.getSuperclass();
        }


        // --- ТАРГЕТИРОВАННЫЙ ДИАГНОСТИЧЕСКИЙ ДАМП НА НЕУДАЧУ (L3 Class) ---
        System.out.println("[WispForestHandler] Search for internal item list failed. Starting TARGETED DEBUG DUMP on " + containerClassL3.getName() + "...");

        List<String> failedMethods = Arrays.stream(containerClassL3.getDeclaredMethods())
            .map(m -> "  Method: " + m.getName() + "(" + Arrays.stream(m.getParameterTypes()).map(Class::getSimpleName).collect(Collectors.joining(", ")) + ") -> " + m.getReturnType().getSimpleName())
            .collect(Collectors.toList());

        List<String> failedFields = Arrays.stream(containerClassL3.getDeclaredFields())
            .map(f -> "  Field: " + f.getName() + " -> " + f.getType().getSimpleName())
            .collect(Collectors.toList());

        System.out.println("[WispForestHandler] --- L3 Container Declared Methods (" + failedMethods.size() + ") ---");
        failedMethods.forEach(System.out::println);
        System.out.println("[WispForestHandler] --- L3 Container Declared Fields (" + failedFields.size() + ") ---");
        failedFields.forEach(System.out::println);
        System.out.println("[WispForestHandler] --------------------------");
        
        System.out.println("[WispForestHandler] FATAL: Failed to find L4 item list accessor in L3 container class: " + containerClassL3.getName());
        return false;
    }

    /**
     * Извлекает List<ItemStack> (L4) из объекта-контейнера L3 (ExpandedSimpleContainer), используя кэшированный путь.
     */
    private static Optional<Collection<ItemStack>> getInternalItemCollectionL4(Object containerL3) throws Exception {
        if (cachedListAccessor == null) return Optional.empty();
        
        Object accessor = cachedListAccessor.listAccessor;
        
        if (cachedListAccessor.isField) {
            Field field = (Field) accessor;
            Object stacksObject = field.get(containerL3);
            if (stacksObject instanceof Collection) {
                return Optional.of((Collection<ItemStack>) stacksObject);
            }
        } else {
            Method method = (Method) accessor;
            Object stacksObject = method.invoke(containerL3);
            if (stacksObject instanceof Collection) {
                return Optional.of((Collection<ItemStack>) stacksObject);
            }
        }
        
        return Optional.empty();
    }


    /**
     * Сохраняет всю Accessories Capability игрока в CompoundTag (ULTIMATE Fallback).
     */
    private static CompoundTag saveAccessoriesFallback(ServerPlayer player, Object capability) {
        try {
            if (!initializeAccessors(capability)) {
                 System.out.println("[WispForestHandler] Failed to initialize container accessors. Aborting save.");
                 return new CompoundTag();
            }

            // Получаем L2 Map
            Method getContainersMethod = capability.getClass().getDeclaredMethod("getSlotContainers");
            getContainersMethod.setAccessible(true);
            Map<?, ?> containersMap = (Map<?, ?>) getContainersMethod.invoke(capability);
            
            // Получаем методы доступа к L3
            Class<?> containerClassL2 = containersMap.values().iterator().next().getClass();
            Method getAccessoriesMethod = containerClassL2.getDeclaredMethod("getAccessories");
            Method getCosmeticAccessoriesMethod = containerClassL2.getDeclaredMethod("getCosmeticAccessories");
            getAccessoriesMethod.setAccessible(true);
            getCosmeticAccessoriesMethod.setAccessible(true);
            
            NonNullList<ItemStack> itemsToSave = NonNullList.create();
            
            for (Object containerL2 : containersMap.values()) {
                // 1. Основные Аксессуары (L3)
                Object containerL3_main = getAccessoriesMethod.invoke(containerL2);
                Optional<Collection<ItemStack>> itemsMain = getInternalItemCollectionL4(containerL3_main);
                if (itemsMain.isPresent()) {
                    itemsToSave.addAll(itemsMain.get());
                } else {
                     System.out.println("[WispForestHandler] Failed to extract main item collection from L3 container. Skipping save.");
                     return new CompoundTag();
                }

                // 2. Косметические Аксессуары (L3)
                Object containerL3_cosmetic = getCosmeticAccessoriesMethod.invoke(containerL2);
                Optional<Collection<ItemStack>> itemsCosmetic = getInternalItemCollectionL4(containerL3_cosmetic);
                if (itemsCosmetic.isPresent()) {
                    itemsToSave.addAll(itemsCosmetic.get());
                } else {
                     System.out.println("[WispForestHandler] Failed to extract cosmetic item collection from L3 container. Skipping save.");
                     return new CompoundTag();
                }
            }
            
            CompoundTag containerTag = new CompoundTag();
            // Используем ContainerHelper для сохранения
            ContainerHelper.saveAllItems(containerTag, itemsToSave, player.registryAccess()); 
            System.out.println("[WispForestHandler] Saved Accessories using ULTIMATE Fallback (Total Size: " + itemsToSave.size() + ").");
            
            CompoundTag finalTag = new CompoundTag();
            finalTag.put(CONTAINER_TAG_KEY, containerTag);
            return finalTag;
            
        } catch (Exception e) {
             System.out.println("[WispForestHandler] ULTIMATE Fallback Save crashed: " + e.getMessage());
             e.printStackTrace();
        }
        return new CompoundTag();
    }
    
    /**
     * Загружает Accessories Capability игрока из CompoundTag (ULTIMATE Fallback).
     */
    private static boolean loadAccessoriesFallback(ServerPlayer player, Object capability, CompoundTag accessoriesTag) {
         try {
            if (!accessoriesTag.contains(CONTAINER_TAG_KEY)) return false;
            
            if (!initializeAccessors(capability)) {
                 System.out.println("[WispForestHandler] Failed to initialize container accessors. Aborting load.");
                 return false;
            }

            // Получаем L2 Map
            Method getContainersMethod = capability.getClass().getDeclaredMethod("getSlotContainers");
            getContainersMethod.setAccessible(true);
            Map<?, ?> containersMap = (Map<?, ?>) getContainersMethod.invoke(capability);
            
            // Получаем методы доступа к L3
            Class<?> containerClassL2 = containersMap.values().iterator().next().getClass();
            Method getAccessoriesMethod = containerClassL2.getDeclaredMethod("getAccessories");
            Method getCosmeticAccessoriesMethod = containerClassL2.getDeclaredMethod("getCosmeticAccessories");
            getAccessoriesMethod.setAccessible(true);
            getCosmeticAccessoriesMethod.setAccessible(true);
            
            // 1. Получаем все внутренние списки L4 в порядке
            List<List<ItemStack>> internalLists = new ArrayList<>();
            int totalSize = 0;
            
            for (Object containerL2 : containersMap.values()) {
                // Main Accessories (L3)
                Object containerL3_main = getAccessoriesMethod.invoke(containerL2);
                Optional<Collection<ItemStack>> itemsMain = getInternalItemCollectionL4(containerL3_main);
                if (itemsMain.isPresent() && itemsMain.get() instanceof List) {
                    List<ItemStack> list = (List<ItemStack>) itemsMain.get();
                    internalLists.add(list);
                    totalSize += list.size();
                } else {
                     System.out.println("[WispForestHandler] Failed to extract main item list L4 or it's not a List. Skipping load.");
                     return false;
                }

                // Cosmetic Accessories (L3)
                Object containerL3_cosmetic = getCosmeticAccessoriesMethod.invoke(containerL2);
                Optional<Collection<ItemStack>> itemsCosmetic = getInternalItemCollectionL4(containerL3_cosmetic);
                if (itemsCosmetic.isPresent() && itemsCosmetic.get() instanceof List) {
                    List<ItemStack> list = (List<ItemStack>) itemsCosmetic.get();
                    internalLists.add(list);
                    totalSize += list.size();
                } else {
                     System.out.println("[WispForestHandler] Failed to extract cosmetic item list L4 or it's not a List. Skipping load.");
                     return false;
                }
            }
            
            // 2. Загружаем все предметы из NBT
            CompoundTag containerTag = accessoriesTag.getCompound(CONTAINER_TAG_KEY);
            NonNullList<ItemStack> loadedItems = NonNullList.withSize(totalSize, ItemStack.EMPTY);
            ContainerHelper.loadAllItems(containerTag, loadedItems, player.registryAccess());
            
            // 3. Распределяем загруженные предметы обратно в соответствующие контейнеры
            int loadedItemIndex = 0;
            for (List<ItemStack> internalList : internalLists) {
                for (int i = 0; i < internalList.size(); i++) {
                    if (loadedItemIndex < loadedItems.size()) {
                        internalList.set(i, loadedItems.get(loadedItemIndex++));
                    } else {
                        internalList.set(i, ItemStack.EMPTY);
                    }
                }
            }
            
            System.out.println("[WispForestHandler] Loaded Accessories using ULTIMATE Fallback (Distributed).");
            syncAccessories(player, capability);
            return true;
            
        } catch (Exception e) {
             System.out.println("[WispForestHandler] ULTIMATE Fallback Load crashed: " + e.getMessage());
             e.printStackTrace();
        }
        return false;
    }


    /**
     * Сохраняет всю Accessories Capability игрока в CompoundTag.
     */
    public static CompoundTag saveAccessories(ServerPlayer player) {
        if (!hasAccessories()) return new CompoundTag();
        
        try {
            Optional<?> optionalCapability = getAccessoryHolder(player);
            
            if (optionalCapability.isPresent()) {
                Object capability = optionalCapability.get();
                
                // 1. Пробуем NBT-методы
                for (String name : NBT_METHODS_PROVIDER) {
                    try {
                        var serializeMethod = capability.getClass().getDeclaredMethod(name, HolderLookup.Provider.class); 
                        serializeMethod.setAccessible(true);
                        CompoundTag tag = (CompoundTag) serializeMethod.invoke(capability, player.registryAccess());
                        System.out.println("[WispForestHandler] Saved Accessories using " + name + "(Provider).");
                        return tag;
                    } catch (NoSuchMethodException ignored) { } 
                }
                for (String name : NBT_METHODS_NO_PROVIDER) {
                    try {
                        var serializeMethod = capability.getClass().getDeclaredMethod(name); 
                        serializeMethod.setAccessible(true);
                        CompoundTag tag = (CompoundTag) serializeMethod.invoke(capability);
                        System.out.println("[WispForestHandler] Saved Accessories using " + name + "().");
                        return tag;
                    } catch (NoSuchMethodException ignored) { }
                }
                
                // 2. ФИНАЛЬНЫЙ FALLBACK: Ультра-агрессивное сохранение
                CompoundTag fallbackTag = saveAccessoriesFallback(player, capability);
                if (!fallbackTag.isEmpty()) {
                    return fallbackTag;
                }

                System.out.println("[WispForestHandler] Warning: No NBT serialization method found. Accessories not saved.");
            }
        } catch (Exception e) {
            System.out.println("[WispForestHandler] Error saving Accessories: " + e.getMessage());
            e.printStackTrace();
        }
        
        return new CompoundTag();
    }

    /**
     * Загружает Accessories Capability игрока из CompoundTag.
     */
    public static boolean loadAccessories(ServerPlayer player, CompoundTag accessoriesTag) {
        if (!hasAccessories() || accessoriesTag.isEmpty()) return false;
        
        try {
            Optional<?> optionalCapability = getAccessoryHolder(player);
            
            if (optionalCapability.isPresent()) {
                Object capability = optionalCapability.get();
                
                // 1. Пробуем NBT-методы
                for (String name : NBT_METHODS_PROVIDER) {
                    try {
                        var deserializeMethod = capability.getClass().getDeclaredMethod(name, HolderLookup.Provider.class, CompoundTag.class); 
                        deserializeMethod.setAccessible(true);
                        deserializeMethod.invoke(capability, player.registryAccess(), accessoriesTag);
                        System.out.println("[WispForestHandler] Loaded Accessories using " + name + "(Provider, CompoundTag).");
                        syncAccessories(player, capability);
                        return true;
                    } catch (NoSuchMethodException ignored) { } 
                }
                for (String name : NBT_METHODS_NO_PROVIDER) {
                    try {
                        var deserializeMethod = capability.getClass().getDeclaredMethod(name, CompoundTag.class); 
                        deserializeMethod.setAccessible(true);
                        deserializeMethod.invoke(capability, accessoriesTag);
                        System.out.println("[WispForestHandler] Loaded Accessories using " + name + "(CompoundTag).");
                        syncAccessories(player, capability);
                        return true;
                    } catch (NoSuchMethodException ignored) { } 
                }
                
                // 2. ФИНАЛЬНЫЙ FALLBACK: Ультра-агрессивная загрузка
                if (loadAccessoriesFallback(player, capability, accessoriesTag)) {
                    return true;
                }
                
                System.out.println("[WispForestHandler] Failed to find any deserialization method. Accessories not loaded.");
            }
        } catch (Exception e) {
            System.out.println("[WispForestHandler] Error loading Accessories: " + e.getMessage());
            e.printStackTrace();
        }
        
        return false;
    }

    /**
     * Синхронизирует данные аксессуаров с клиентом, используя агрессивный Fallback.
     */
    private static void syncAccessories(ServerPlayer player, Object capability) {
        boolean synced = false;
        
        // 1. Пробуем API-методы синхронизации
        for (String methodName : new String[]{"sync", "update", "refresh", "markDirty"}) {
            if (synced) break;
            try {
                Method syncMethod = null;
                try {
                     // С параметром LivingEntity
                     syncMethod = capability.getClass().getMethod(methodName, LivingEntity.class);
                     syncMethod.invoke(capability, player);
                } catch (NoSuchMethodException e) {
                     // Без параметров
                     syncMethod = capability.getClass().getMethod(methodName);
                     syncMethod.invoke(capability);
                }
                
                System.out.println("[WispForestHandler] Synced Accessories data via " + methodName + "()");
                synced = true;
            } catch (Exception ignored) {}
        }
        
        // 2. АГРЕССИВНЫЙ FALLBACK: markChanged(true)
        if (!synced) {
            try {
                // L2 класс AccessoriesContainerImpl содержит markChanged(boolean)
                Method markChangedMethod = capability.getClass().getDeclaredMethod("markChanged", boolean.class);
                markChangedMethod.setAccessible(true);
                markChangedMethod.invoke(capability, true);
                System.out.println("[WispForestHandler] Synced Accessories data via markChanged(true) fallback.");
                synced = true;
            } catch (Exception ignored) {
                // Если markChanged не сработал, просто полагаемся на broadcastChanges
            }
        }

        // 3. Финальная синхронизация контейнера
        if (synced || player.containerMenu != null) {
            player.containerMenu.broadcastChanges();
            if (!synced) {
                 System.out.println("[WispForestHandler] Synced Accessories data via broadcastChanges().");
            }
        }
        
        if (!synced) {
            System.out.println("[WispForestHandler] Warning: No sync method found - accessories may not appear immediately");
        }
    }


    /**
     * Очищает все аксессуары игрока.
     */
    public static void clearAccessories(ServerPlayer player) {
        if (!hasAccessories()) return;

        try {
            Optional<?> optionalCapability = getAccessoryHolder(player);

            if (optionalCapability.isPresent()) {
                Object capability = optionalCapability.get();
                boolean cleared = false;
                
                // 1. Пробуем вызвать методы прямой очистки (публичные)
                for (String methodName : new String[]{"clear", "clearContent", "empty", "clearInventory"}) {
                     try {
                        Method clearMethod = capability.getClass().getMethod(methodName);
                        clearMethod.invoke(capability);
                        System.out.println("[WispForestHandler] Cleared Accessories using " + methodName + "()");
                        cleared = true;
                        break;
                    } catch (NoSuchMethodException ignored) { /* Пробуем дальше */ }
                }
                
                // 2. ФИНАЛЬНЫЙ FALLBACK: Ультра-агрессивная очистка через Map/List
                if (!cleared) {
                    try {
                        if (!initializeAccessors(capability)) {
                             System.out.println("[WispForestHandler] Failed to initialize container accessors. Aborting clear.");
                             return;
                        }

                        // Получаем L2 Map
                        Method getContainersMethod = capability.getClass().getDeclaredMethod("getSlotContainers");
                        getContainersMethod.setAccessible(true);
                        Map<?, ?> containersMap = (Map<?, ?>) getContainersMethod.invoke(capability);
                        
                        // Получаем методы доступа к L3
                        Class<?> containerClassL2 = containersMap.values().iterator().next().getClass();
                        Method getAccessoriesMethod = containerClassL2.getDeclaredMethod("getAccessories");
                        Method getCosmeticAccessoriesMethod = containerClassL2.getDeclaredMethod("getCosmeticAccessories");
                        getAccessoriesMethod.setAccessible(true);
                        getCosmeticAccessoriesMethod.setAccessible(true);
                        
                        int totalCleared = 0;
                        boolean success = true;
                        
                        for (Object containerL2 : containersMap.values()) {
                            // Main Accessories (L3)
                            Object containerL3_main = getAccessoriesMethod.invoke(containerL2);
                            Optional<Collection<ItemStack>> itemsMain = getInternalItemCollectionL4(containerL3_main);
                            
                            // Cosmetic Accessories (L3)
                            Object containerL3_cosmetic = getCosmeticAccessoriesMethod.invoke(containerL2);
                            Optional<Collection<ItemStack>> itemsCosmetic = getInternalItemCollectionL4(containerL3_cosmetic);

                            if (itemsMain.isPresent() && itemsMain.get() instanceof List) {
                                List<ItemStack> list = (List<ItemStack>) itemsMain.get();
                                for (int i = 0; i < list.size(); i++) {
                                    list.set(i, ItemStack.EMPTY);
                                    totalCleared++;
                                }
                            } else { success = false; break; }
                            
                            if (itemsCosmetic.isPresent() && itemsCosmetic.get() instanceof List) {
                                List<ItemStack> list = (List<ItemStack>) itemsCosmetic.get();
                                for (int i = 0; i < list.size(); i++) {
                                    list.set(i, ItemStack.EMPTY);
                                    totalCleared++;
                                }
                            } else { success = false; break; }
                        }
                        
                        if (success) {
                            System.out.println("[WispForestHandler] Cleared " + totalCleared + " accessory slots via ULTIMATE Fallback (Distributed).");
                            cleared = true;
                        } else {
                            System.out.println("[WispForestHandler] Failed to clear item lists (Internal collection is not a List or list not found).");
                        }
                    } catch (Exception e) {
                        System.out.println("[WispForestHandler] ULTIMATE Fallback Clear crashed/failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                if (cleared) {
                    // Синхронизация после очистки
                    syncAccessories(player, capability);
                } else {
                     System.out.println("[WispForestHandler] Warning: Accessories not cleared. [FINAL FALLBACK FAILED]");
                }
            }
        } catch (Exception e) {
            System.out.println("[CSI] Error clearing Accessories: " + e.getMessage());
            e.printStackTrace();
        }
    }
}