package dev.totem.lumen.gui;

import dev.totem.lumen.network.ServerLightingViewPackets;
import net.minecraft.resources.Identifier;

/** One unsaved local edit; RGB is stored as exact slider steps rather than rounded floats. */
public record LightingRuleDraft(ServerLightingViewPackets.Entry original,
                                int red, int green, int blue, int strength) {
    public LightingRuleDraft {
        if (original == null || original.origin() == 2) {
            throw new IllegalArgumentException("A server-config override cannot be edited as a data pack");
        }
        if (red < 0 || red > 255 || green < 0 || green > 255 || blue < 0 || blue > 255
                || strength < -1 || strength > 15) {
            throw new IllegalArgumentException("Invalid RGB or strength value");
        }
    }

    public Identifier id() {
        return original.id();
    }

    public boolean changed() {
        return red != Math.round(original.red() * 255.0f)
                || green != Math.round(original.green() * 255.0f)
                || blue != Math.round(original.blue() * 255.0f)
                || strength != original.strength();
    }
}
