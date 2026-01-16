package net.darkunity.customstartinventory;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.Locale;

@OnlyIn(Dist.CLIENT)
public class CSIConfigScreen extends Screen {
    private final Screen parent;
    
    private Button modEnabledButton;
    private Button intelligentScanningButton;
    private Button hideChatMessagesButton;
    private Button accessoriesModeButton;
    private Button doneButton;
    
    public CSIConfigScreen(Screen parent) {
        super(Component.translatable("csi.config.title"));
        this.parent = parent;
    }
    
    // Вспомогательный метод для создания текста кнопки-переключателя
    private Component createToggleText(String key, boolean enabled) {
        String status = enabled ? "§aENABLED" : "§cDISABLED";
        Component keyComponent = Component.translatable(key);
        return Component.literal(keyComponent.getString() + ": " + status);
    }

    // Вспомогательный метод для создания текста режима аксессуаров
    private Component createAccessoriesModeText() {
        String modeKey = CustomStartInventory.ACCESSORIES_MODE.get().toString().toLowerCase(Locale.ROOT);
        Component keyComponent = Component.translatable("csi.config.accessories_mode");
        Component valueComponent = Component.translatable("csi.config.accessories_mode." + modeKey);
        // Формат: Accessories Mode: [VALUE]
        return Component.literal(keyComponent.getString() + ": §a" + valueComponent.getString());
    }
    
    @Override
    protected void init() {
        int y = 40;
        int buttonWidth = 250;
        int buttonHeight = 20;
        int centerX = this.width / 2 - buttonWidth / 2;
        
        // Mod Enabled
        modEnabledButton = Button.builder(
            createToggleText("csi.config.mod_enabled", CustomStartInventory.MOD_ENABLED.get()),
            button -> {
                boolean newValue = !CustomStartInventory.MOD_ENABLED.get();
                CustomStartInventory.MOD_ENABLED.set(newValue);
                button.setMessage(createToggleText("csi.config.mod_enabled", newValue));
            })
            .pos(centerX, y)
            .size(buttonWidth, buttonHeight)
            .build();
        this.addRenderableWidget(modEnabledButton);
        y += buttonHeight + 2;

        // Intelligent Scanning
        intelligentScanningButton = Button.builder(
            createToggleText("csi.config.intelligent_scanning", CustomStartInventory.INTELLIGENT_SCANNING.get()),
            button -> {
                boolean newValue = !CustomStartInventory.INTELLIGENT_SCANNING.get();
                CustomStartInventory.INTELLIGENT_SCANNING.set(newValue);
                button.setMessage(createToggleText("csi.config.intelligent_scanning", newValue));
            })
            .pos(centerX, y)
            .size(buttonWidth, buttonHeight)
            .build();
        this.addRenderableWidget(intelligentScanningButton);
        y += buttonHeight + 2;

        // Hide Chat Messages - по умолчанию включено, но можно отключить
        hideChatMessagesButton = Button.builder(
            createToggleText("csi.config.hide_chat_messages", CustomStartInventory.HIDE_CHAT_MESSAGES.get()),
            button -> {
                boolean newValue = !CustomStartInventory.HIDE_CHAT_MESSAGES.get();
                CustomStartInventory.HIDE_CHAT_MESSAGES.set(newValue);
                button.setMessage(createToggleText("csi.config.hide_chat_messages", newValue));
            })
            .pos(centerX, y)
            .size(buttonWidth, buttonHeight)
            .build();
        this.addRenderableWidget(hideChatMessagesButton);
        y += buttonHeight + 2;

        // Accessories Mode
        accessoriesModeButton = Button.builder(
            createAccessoriesModeText(),
            button -> {
                CustomStartInventory.AccessoryMode currentMode = CustomStartInventory.ACCESSORIES_MODE.get();
                CustomStartInventory.AccessoryMode[] modes = CustomStartInventory.AccessoryMode.values();
                int nextIndex = (currentMode.ordinal() + 1) % modes.length;
                CustomStartInventory.ACCESSORIES_MODE.set(modes[nextIndex]);
                button.setMessage(createAccessoriesModeText());
            })
            .pos(centerX, y)
            .size(buttonWidth, buttonHeight)
            .build();
        this.addRenderableWidget(accessoriesModeButton);
        y += buttonHeight + 10;
        
        // DONE button
        doneButton = Button.builder(
            CommonComponents.GUI_DONE,
            button -> this.onClose())
            .pos(this.width / 2 - 100, this.height - 29)
            .size(200, 20)
            .build();
        this.addRenderableWidget(doneButton);
    }
    
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 10, 0xFFFFFF);
        
        // Информация о найденных API
        Component detectedModsBase = Component.translatable("csi.config.detected_apis");
        StringBuilder detectedMods = new StringBuilder("§f" + detectedModsBase.getString());

        if (CustomStartInventory.hasAccessories) {
            detectedMods.append("§d").append(Component.translatable("csi.config.api.accessories").getString());
            if (CustomStartInventory.hasCurios) {
                detectedMods.append(" §f& §6").append(Component.translatable("csi.config.api.curios").getString());
            }
        } else if (CustomStartInventory.hasCurios) {
            detectedMods.append("§6").append(Component.translatable("csi.config.api.curios").getString());
        } else {
            detectedMods.append("§7").append(Component.translatable("csi.config.api.none").getString());
        }
        
        guiGraphics.drawCenteredString(this.font, detectedMods.toString(), this.width / 2, 22, 0xFFFFFF);
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        
        // Подсказки (Tooltips)
        if (intelligentScanningButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(this.font, 
                Component.translatable("csi.config.tooltip.intelligent_scanning"), 
                mouseX, mouseY);
        }
        if (hideChatMessagesButton.isMouseOver(mouseX, mouseY)) {
            guiGraphics.renderTooltip(this.font, 
                Component.translatable("csi.config.tooltip.hide_chat_messages"), 
                mouseX, mouseY);
        }
    }
    
    @Override
    public void onClose() {
        // В NeoForge изменения ModConfigSpec.BooleanValue/EnumValue сохраняются автоматически.
        this.minecraft.setScreen(this.parent);
    }
}