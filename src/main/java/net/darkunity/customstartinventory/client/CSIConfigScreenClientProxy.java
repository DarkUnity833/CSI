package net.darkunity.customstartinventory.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@OnlyIn(Dist.CLIENT)
public class CSIConfigScreenClientProxy {
    // Registers the config screen extension point. Must only ever be called from behind a
    // Dist.CLIENT check in the shared mod class - the lambda below implements
    // IConfigScreenFactory#createScreen(ModContainer, Screen), so its compiled bytecode
    // references Screen directly. Keeping that bytecode inside this @OnlyIn(CLIENT) class
    // (rather than inline in the shared class) is what actually avoids the dedicated-server
    // crash - NeoForge's dist cleaner rejects any class whose own bytecode references a
    // client-only type, regardless of runtime "if" guards around the call site.
    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class,
            () -> (container, parent) -> createConfigScreen(parent));
    }

    // Этот метод создает экземпляр CSIConfigScreen
    // Он будет вызываться ТОЛЬКО на клиенте
    public static Screen createConfigScreen(Screen parent) {
        // Используем reflection для загрузки класса только когда нужно
        try {
            Class<?> configScreenClass = Class.forName("net.darkunity.customstartinventory.CSIConfigScreen");
            return (Screen) configScreenClass.getConstructor(Screen.class).newInstance(parent);
        } catch (Exception e) {
            // Если что-то пошло не так, вернем простой экран с ошибкой
            return new Screen(Component.literal("Error")) {
                @Override
                protected void init() {
                    super.init();
                }
            };
        }
    }
}