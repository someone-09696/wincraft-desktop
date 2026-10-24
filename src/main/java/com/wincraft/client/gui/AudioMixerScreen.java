package com.wincraft.client.gui;

import com.wincraft.client.WinCraftClient;
import com.wincraft.client.audio.SpatialAudioService;
import com.wincraft.client.render.AppWindowEntity;
import java.util.List;
import javax.sound.sampled.Mixer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Explicit opt-in to an AUDIO OUTPUT virtual cable's RECORDING side (not the microphone). */
public final class AudioMixerScreen extends Screen {
    private final Screen parent;
    private final AppWindowEntity panel;
    private List<Mixer.Info> cables = List.of();
    private int page;
    private String startError = "";

    /** Volume slider 0..200% shown when audio is active for this panel */
    private AbstractSliderButton volumeSlider;

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
                    rebuildWidgets();
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
            rebuildWidgets();
        }).bounds(x + 70, navY, 115, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Stop all audio"), b -> {
            WinCraftClient.AUDIO.stopAll();
            rebuildWidgets();
        }).bounds(x + 190, navY, 115, 20).build());

        // Volume slider — shown only when audio is active for this panel
        if (WinCraftClient.AUDIO.activeFor(panel)) {
            float currentGain = WinCraftClient.AUDIO.gainFor(panel);
            double initialVal = Math.clamp((double) currentGain / 2.0, 0.0, 1.0);
            volumeSlider = new AbstractSliderButton(x, navY + 25, 320, 20,
                Component.literal("Volume: " + Math.round(initialVal * 200) + "%"), initialVal) {
                @Override
                protected void updateMessage() {
                    setMessage(Component.literal("Volume: " + Math.round(value * 200) + "%"));
                }
                @Override
                protected void applyValue() {
                    WinCraftClient.AUDIO.setGain(panel, (float) (value * 2.0));
                }
            };
            addRenderableWidget(volumeSlider);
        }

        addRenderableWidget(Button.builder(Component.literal("Back"), b -> minecraft.gui.setScreen(parent))
            .bounds(x + 110, navY + (WinCraftClient.AUDIO.activeFor(panel) ? 50 : 25), 100, 20).build());
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
        g.text(font, "WINCRAFT  /  3D SPATIAL AUDIO", panelLeft + 64, panelTop + 15, 0xFFF2FAFF, true);
        super.extractRenderState(g, mouseX, mouseY, delta);

        String title = "3D Audio for: " + (panel != null ? panel.info.title() : "No panel");
        if (title.length() > 50) title = title.substring(0, 47) + "...";
        g.text(font, title, x, y-26, 0xFFFFFFFF, true);

        String currentStatus = !startError.isBlank() ? startError :
            (panel != null ? WinCraftClient.AUDIO.statusFor(panel) : "No panel");
        g.text(font, "Status: " + currentStatus, x, y-14, 0xFFBBDDEE, true);

        // Real-time distance + direction indicator
        if (panel != null && WinCraftClient.AUDIO.activeFor(panel) && minecraft.player != null) {
            net.minecraft.world.phys.Vec3 eye = minecraft.player.getEyePosition();
            org.joml.Vector3d src = panel.transform.position;
            double dist = Math.sqrt(
                (src.x - eye.x) * (src.x - eye.x) +
                (src.y - eye.y) * (src.y - eye.y) +
                (src.z - eye.z) * (src.z - eye.z));
            String distStr = String.format("Distance: %.1f blocks", dist);
            int distColor = dist <= 2 ? 0xFF88FF88 : dist >= 24 ? 0xFFFF6666 : 0xFFFFCC44;
            g.text(font, distStr, x, y - 2, distColor, false);

            // Visual bar: filled portion = how much audio remains (linear 2→24 blocks)
            int barW = 200;
            double t = Math.clamp((dist - 2.0) / (24.0 - 2.0), 0.0, 1.0);
            int filled = (int) Math.round(barW * (1.0 - t));
            g.fill(x, y + 9, x + barW, y + 14, 0xFF1A2535);
            g.fill(x, y + 9, x + filled, y + 14, distColor);
            g.text(font, "near", x, y + 15, 0xFF667788, false);
            g.text(font, "24 blocks", x + barW - 52, y + 15, 0xFF667788, false);
        }

        if (cables.isEmpty()) {
            g.text(font, "No audio capture device found on your system.", x, y+35, 0xFFFFAAAA, true);
        } else {
            g.text(font, "Pick a virtual cable routed from your app (e.g. VB-Audio Cable).", x, Math.min(height-36, y+168), 0xFF88CC88, true);
        }
        g.text(font, "Sound pans left/right and fades as you walk around the panel in 3D.", x, Math.min(height-24, y+180), 0xFFAAAA88, true);
        g.text(font, "Full volume \u2264 2 blocks · silence \u2265 24 blocks.", x, Math.min(height-12, y+192), 0xFF888888, true);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
