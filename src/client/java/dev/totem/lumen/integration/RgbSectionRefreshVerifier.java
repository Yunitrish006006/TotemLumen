package dev.totem.lumen.integration;

import dev.totem.lumen.network.GameplayLightSectionsPayload;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Map;

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
        verifyServerAuthority(dimension);
        ClientGameplayLightField.clear();
        System.out.println("RGB section refresh verification PASS: sourcePriority=true, skyBudget=12, "
                + "deduplicated=true, serverAuthority=true");
    }

    private static void verifyServerAuthority(String dimension) {
        var section = new ClientGameplayLightField.SectionCoordinate(dimension, 0, 4, 0);
        char[] stable = new char[GameplayLightSectionsPayload.VOXEL_COUNT];
        stable[0] = (char) 0xF123;
        char[] prediction = new char[GameplayLightSectionsPayload.VOXEL_COUNT];
        prediction[0] = (char) 0xF456;
        Map<ClientGameplayLightField.SectionCoordinate, char[]> staged = Map.of(section, prediction);
        Identifier overworld = Identifier.fromNamespaceAndPath("minecraft", "overworld");
        ClientGameplayLightField.apply(new GameplayLightSectionsPayload(overworld, 1L,
                List.of(new GameplayLightSectionsPayload.Section(0, 4, 0, stable)), true));
        ClientGameplayLightField.replaceLocalRegion(dimension, 0, 64, 0, 0, 64, 0, staged);
        if (ClientGameplayLightField.localPackedAt(dimension, 0, 64, 0) != 0xF123
                || ClientGameplayLightField.setLocalPacked(dimension, 0, 64, 0, 0xF456)) {
            throw new IllegalStateException("A queued predictor job overwrote a stable server section");
        }

        ClientGameplayLightField.markSpeculativeRegion(dimension, 0, 64, 0, 0, 64, 0);
        ClientGameplayLightField.replaceLocalRegion(dimension, 0, 64, 0, 0, 64, 0, staged);
        if (ClientGameplayLightField.localPackedAt(dimension, 0, 64, 0) != 0xF456) {
            throw new IllegalStateException("A local block edit must retain immediate prediction");
        }
        ClientGameplayLightField.apply(new GameplayLightSectionsPayload(overworld, 2L,
                List.of(new GameplayLightSectionsPayload.Section(0, 4, 0, stable)), false));
        ClientGameplayLightField.replaceLocalRegion(dimension, 0, 64, 0, 0, 64, 0, staged);
        if (ClientGameplayLightField.localPackedAt(dimension, 0, 64, 0) != 0xF123) {
            throw new IllegalStateException("Server update did not retire local speculation");
        }

        ClientGameplayLightField.markSpeculativeRegion(dimension, 0, 64, 0, 0, 64, 0);
        ClientGameplayLightField.replaceLocalRegion(dimension, 0, 64, 0, 0, 64, 0, staged);
        for (int tick = 0; tick < 200; tick++) {
            ClientGameplayLightField.advancePredictionTick();
        }
        if (ClientGameplayLightField.localPackedAt(dimension, 0, 64, 0) != 0xF123) {
            throw new IllegalStateException("Unacknowledged prediction must expire to server state");
        }

        ClientGameplayLightField.drainDirtySections(dimension);
        ClientGameplayLightField.apply(new GameplayLightSectionsPayload(overworld, 3L, List.of(), true));
        if (ClientGameplayLightField.localPackedAt(dimension, 0, 64, 0) != 0
                || !ClientGameplayLightField.drainDirtySections(dimension).contains(section)) {
            throw new IllegalStateException("Full sync must refresh sections removed from the server field");
        }
    }
}
