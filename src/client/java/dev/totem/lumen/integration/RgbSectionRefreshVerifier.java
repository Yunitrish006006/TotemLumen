package dev.totem.lumen.integration;

import java.util.List;

/** Build-time check that sky refreshes stay bounded and never delay source updates. */
public final class RgbSectionRefreshVerifier {
    private RgbSectionRefreshVerifier() {
    }

    public static void main(String[] args) {
        String dimension = "minecraft:overworld";
        ClientGameplayLightField.clear();
        for (int x = 0; x < 40; x++) {
            ClientGameplayLightField.markSkySectionDirty(dimension, x, 4, 0);
        }
        ClientGameplayLightField.markSectionDirty(dimension, 39, 4, 0);
        ClientGameplayLightField.markSectionDirty(dimension, 100, 4, 0);
        List<ClientGameplayLightField.SectionCoordinate> first =
                ClientGameplayLightField.drainDirtySections(dimension, 32);
        if (first.size() != 14 || first.get(0).x() != 39 || first.get(1).x() != 100) {
            throw new IllegalStateException("RGB source refresh must precede at most 12 sky sections");
        }
        if (first.stream().filter(section -> section.x() == 39).count() != 1) {
            throw new IllegalStateException("A source update must not duplicate its queued sky refresh");
        }
        List<ClientGameplayLightField.SectionCoordinate> second =
                ClientGameplayLightField.drainDirtySections(dimension, 32);
        if (second.size() != 12) {
            throw new IllegalStateException("Sky refresh budget must remain bounded on later ticks");
        }
        ClientGameplayLightField.clear();
        System.out.println("RGB section refresh verification PASS: sourcePriority=true, skyBudget=12, deduplicated=true");
    }
}
