package pl.landmc.auth;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** Turns whatever Velocity hands us into the address string sessions and accounts are keyed by. */
public final class Addresses {

    /** What is stored when the remote address is not an IP one - never matches a real session. */
    public static final String UNKNOWN = "unknown";

    private Addresses() {
    }

    /**
     * The host part of a connection's address, without the port.
     *
     * <p>The port changes on every connection, so a session bound to one would never match.
     * {@code getHostAddress} rather than {@code getHostName}, because the latter can perform a
     * reverse DNS lookup - a blocking network call, on a Netty thread, during a login.
     */
    public static String of(SocketAddress address) {
        if (address instanceof InetSocketAddress socket && socket.getAddress() != null) {
            return socket.getAddress().getHostAddress();
        }
        return UNKNOWN;
    }
}
