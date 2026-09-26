package dev.totem.lumen.gui;

import dev.totem.lumen.TotemLumen;
import dev.totem.lumen.network.ServerLightingViewPackets;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Connection-scoped read-only server preview data; no local-settings fallback. */
public final class ClientServerLightingViewState {
    private static ServerLightingViewPackets.Summary summary;
    private static ServerLightingViewPackets.Page page;
    private static ServerLightingViewPackets.PageRequest pending;

    private ClientServerLightingViewState() {
    }

    public static void apply(ServerLightingViewPackets.Summary next) {
        if (summary == null || summary.revision() != next.revision()) {
            page = null;
            pending = null;
        }
        summary = next;
    }

    public static void apply(ServerLightingViewPackets.Page next) {
        if (summary == null || next.revision() != summary.revision() || pending == null
                || pending.category() != next.category() || pending.page() != next.page()
                || !pending.query().equals(next.query())
                || !pending.packId().equals(next.packId())) {
            TotemLumen.LOGGER.warn("Ignored stale server lighting preview page: revision={}, category={}, page={}",
                    next.revision(), next.category(), next.page());
            return;
        }
        page = next;
        pending = null;
        TotemLumen.LOGGER.info("Received server lighting preview page: category={}, page={}, entries={}",
                next.category(), next.page(), next.entries().size());
    }

    public static void request(int category, int pageNumber, String query, String packId) {
        if (summary == null) return;
        ServerLightingViewPackets.PageRequest request = new ServerLightingViewPackets.PageRequest(
                summary.revision(), category, pageNumber, query, packId);
        pending = request;
        page = null;
        TotemLumen.LOGGER.info("Requested server lighting preview page: category={}, page={}, advertised={}",
                category, pageNumber, ClientPlayNetworking.canSend(ServerLightingViewPackets.PageRequest.TYPE));
        ClientPlayNetworking.send(request);
    }

    public static ServerLightingViewPackets.Summary summary() {
        return summary;
    }

    public static ServerLightingViewPackets.Page page() {
        return page;
    }

    public static void clear() {
        summary = null;
        page = null;
        pending = null;
    }
}
