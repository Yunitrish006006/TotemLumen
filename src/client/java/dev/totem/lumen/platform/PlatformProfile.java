package dev.totem.lumen.platform;

import java.util.Locale;

public record PlatformProfile(
        String osName,
        String architecture,
        boolean macOS,
        boolean appleSilicon
) {
    public static PlatformProfile detect() {
        String osName = System.getProperty("os.name", "unknown");
        String architecture = System.getProperty("os.arch", "unknown");

        String normalizedOs = osName.toLowerCase(Locale.ROOT);
        String normalizedArch = architecture.toLowerCase(Locale.ROOT);
        boolean macOS = normalizedOs.contains("mac") || normalizedOs.contains("darwin");
        boolean arm64 = normalizedArch.contains("aarch64") || normalizedArch.contains("arm64");

        return new PlatformProfile(osName, architecture, macOS, macOS && arm64);
    }

    public String displayName() {
        if (appleSilicon) {
            return "Apple Silicon (" + architecture + ")";
        }
        return osName + " / " + architecture;
    }
}
