package dev.totem.lumen.render;

public record BackendStatus(
        boolean deviceAvailable,
        boolean vulkan,
        String backendName,
        String deviceName,
        String vendorName,
        String driverInfo
) {
    public static BackendStatus waitingForDevice() {
        return new BackendStatus(false, false, "unavailable", "unknown", "unknown", "unknown");
    }
}
