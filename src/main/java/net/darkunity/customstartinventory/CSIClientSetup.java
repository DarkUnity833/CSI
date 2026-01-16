package net.darkunity.customstartinventory;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// Убрали bus = EventBusSubscriber.Bus.MOD, так как он deprecated
@EventBusSubscriber(modid = CustomStartInventory.MODID, value = Dist.CLIENT)
public class CSIClientSetup {
    
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // Регистрация экрана конфигурации только на клиенте
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
            () -> (mc, parent) -> new CSIConfigScreen(parent));
    }
}