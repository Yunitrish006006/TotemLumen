package dev.totem.lumen.integration;

/** Internal bridge for carrying an emissive-item marker into ItemStackRenderState.submit. */
public interface ItemStackRenderStateGlowAccess {
    void totemLumen$setEmissiveItem(boolean emissive);

    boolean totemLumen$isEmissiveItem();
}
