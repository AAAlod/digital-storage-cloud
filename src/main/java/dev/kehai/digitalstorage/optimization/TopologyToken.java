package dev.kehai.digitalstorage.optimization;

/** Opaque topology lifetime; the owning platform verifies identity and version. */
public interface TopologyToken {
    boolean isCurrent();
    String staleDetail();

    static boolean isCurrent(TopologyToken token) {
        return token != null && token.isCurrent();
    }
}
