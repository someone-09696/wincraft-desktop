package com.wincraft.client.gui;

import com.wincraft.client.WinCraftClient;
import com.wincraft.client.audio.SpatialAudioService;
import com.wincraft.client.render.AppWindowEntity;
import java.util.List;
import javax.sound.sampled.Mixer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit opt-in to an AUDIO OUTPUT virtual cable's RECORDING side (not the microphone). */
public final class AudioMixerScreen extends Screen {
    private final Screen parent;
    private final AppWindowEntity panel;
    private List<Mixer.Info> cables = List.of();
    private int page;
    private String startError = "";

    public AudioMixerScreen(Screen parent, AppWindowEntity panel) {
        super(Component.literal("WinCraft 3D Audio"));
        this.parent = parent;
        this.panel = panel;
    }

    @Override protected void init() {
        cables = SpatialAudioService.inputMixers();
        int x = width/2-160, y = Math.max(30,height/2-95);
        int shown = Math.min(5,cables.size()-page*5);
        for (int row=0;row<shown;row++) {
            Mixer.Info mixer = cables.get(page*5+row);
            String name = mixer.getName();

            // Check if already in use by another panel
            String inUseBy = null;
            for (AppWindowEntity other : WinCraftClient.AUDIO.activePanels()) {
                if (other != panel) {
                    Mixer.Info otherMixer = WinCraftClient.AUDIO.mixerFor(other);
                    if (otherMixer != null && otherMixer.getName().equalsIgnoreCase(name)) {
                        inUseBy = other.info.title();
                        break;
                    }
                }
            }

            String btnText = name;
            if (inUseBy != null) {
                btnText += " (Used by: " + (inUseBy.length() > 10 ? inUseBy.substring(0,8)+"..." : inUseBy) + " - mixes)";
            }
            if (btnText.length() > 46) btnText = btnText.substring(0,43) + "...";

            addRenderableWidget(Button.builder(Component.literal(btnText), b -> {
                if (!WinCraftClient.surfaces().contains(panel)) return;
                try {
                    WinCraftClient.AUDIO.start(panel, mixer);
                    minecraft.gui.setScreen(parent);
                } catch (RuntimeException | LinkageError error) {
                    startError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                }
            }).bounds(x, y + row*23, 320, 20).build());
        }

        int navY = y + 120;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> { if(page>0){page--;rebuildWidgets();} })
            .bounds(x, navY, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> { if((page+1)*5<cables.size()){page++;rebuildWidgets();} })
            .bounds(x + 35, navY, 30, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop this panel"), b -> {
            WinCraftClient.AUDIO.stop(panel);
            minecraft.gui.setScreen(parent);
        }).bounds(x + 70, navY, 115, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop all audio"), b -> {
            WinCraftClient.AUDIO.stopAll();
            minecraft.gui.setScreen(parent);
        }).bounds(x + 190, navY, 115, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(parent))
            .bounds(x + 110, navY + 25, 100, 20).build());
    }

    @Override public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        int x = width/2-160, y = Math.max(30, height/2-95);
        int panelLeft = x - 18, panelTop = y - 56, panelRight = x + 338;
        int panelBottom = Math.min(height - 8, y + 207);
        g.fill(panelLeft, panelTop, panelRight, panelBottom, 0xE9101420);
        g.fill(panelLeft, panelTop, panelRight, panelTop + 2, WinCraftBrand.ACCENT);
        g.fill(panelLeft, panelTop + 2, panelLeft + 34, panelBottom, WinCraftBrand.RAIL);
        g.fill(panelLeft + 34, panelTop + 2, panelLeft + 35, panelBottom, 0xFF303B4A);
        WinCraftBrand.drawMark(g, panelLeft + 41, panelTop + 8);
        g.text(font, "WINCRAFT  /  AUDIO ROUTING", panelLeft + 64, panelTop + 15, 0xFFF2FAFF, true);
        super.extractRenderState(g, mouseX, mouseY, delta);
        String title = "3D Audio for: " + (panel != null ? panel.info.title() : "No panel");
        if (title.length() > 50) title = title.substring(0, 47) + "...";
        g.text(font, title, x, y-26, 0xFFFFFFFF, true);
        String currentStatus = !startError.isBlank() ? startError :
            (panel != null ? WinCraftClient.AUDIO.statusFor(panel) : "No panel");
        g.text(font, "Status: " + currentStatus, x, y-14, 0xFFBBDDEE, true);

        if (cables.isEmpty()) {
            g.text(font, "No audio capture device found on your system.", x, y+35, 0xFFFFAAAA, true);
        } else {
            g.text(font, "Select an audio device to spatialize in 3D around this panel.", x, Math.min(height-24, y+168), 0xFF88CC88, true);
        }
        g.text(font, "Sound dynamically shifts and pans as you move around in 3D.", x, Math.min(height-12, y+180), 0xFFAAAA88, true);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
