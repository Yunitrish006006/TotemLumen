package dev.totem.lumen.vulkan.resource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanResourceScopeTest {
    @Test
    void resourcesCloseInReverseOwnershipOrder() {
        VulkanResourceScope scope = new VulkanResourceScope();
        List<Integer> closed = new ArrayList<>();

        scope.own(() -> closed.add(1));
        scope.own(() -> closed.add(2));
        scope.own(() -> closed.add(3));
        assertEquals(3, scope.resourceCount());

        scope.close();

        assertEquals(List.of(3, 2, 1), closed);
        assertTrue(scope.isClosed());
        assertEquals(0, scope.resourceCount());
    }

    @Test
    void closeIsIdempotent() {
        VulkanResourceScope scope = new VulkanResourceScope();
        List<Integer> closed = new ArrayList<>();
        scope.own(() -> closed.add(1));

        scope.close();
        scope.close();

        assertEquals(List.of(1), closed);
    }
}
