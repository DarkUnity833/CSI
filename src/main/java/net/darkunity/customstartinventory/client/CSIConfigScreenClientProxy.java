package net.darkunity.customstartinventory.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class CSIConfigScreenClientProxy {
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