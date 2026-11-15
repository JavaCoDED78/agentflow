package com.example.agentflow.toolexecution.mcp;

import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;

/**
 * The SSRF guard for an outbound connection to a server or resource: it classifies a connection
 * destination before it is opened. It is a pure, immutable domain value (no Spring, no I/O beyond a
 * DNS lookup when resolving a hostname).
 *
 * <p>The guard enforces two things:
 *
 * <ul>
 *   <li><b>Protocol.</b> Only {@code http} and {@code https} are permitted. Anything else ({@code
 *       file}, {@code jar}, {@code ws}/{@code wss}, {@code ftp}, …) is refused — the same
 *       restriction the tool/ai outbound policies carry (invariant 7).
 *   <li><b>Host.</b> A destination is refused when its host is loopback, unspecified, private (RFC
 *       1918), link-local (which includes the cloud metadata service at {@code 169.254.169.254}),
 *       unique-local (IPv6) or otherwise reserved (RFC 5737 / future use). The refusal is by the
 *       address family's own semantics ({@link InetAddress#isLoopbackAddress()} etc.), not by a
 *       hand-written allow/deny list — so a hostname that resolves to any of these is refused too.
 * </ul>
 *
 * <p>The guard is <em>not</em> an allowlist: it does not decide whether a routable public host may
 * be reached (that is the operator's connection-map + tool-allowlist decision). It only refuses the
 * destinations that would let an MCP target punch through to the host's own network, its loopback,
 * or its cloud metadata endpoint.
 *
 * <p>The canonical constructor rejects a non-permitted protocol up front, so a constructed value is
 * always well-formed; {@link #isPermitted()} then answers whether the host is also routable.
 */
public final class OutboundDestinationPolicy {

  /** The protocol the guard permits, upper-cased: {@code HTTP} / {@code HTTPS}. */
  public enum Protocol {
    HTTP,
    HTTPS
  }

  private static final String ANY_LOCAL = "0.0.0.0";
  private static final String ANY_LOCAL_IPV6 = "::";

  private final String rawUrl;
  private final String protocol;
  private final String host;
  private final String resolvedHost;
  private final boolean loopback;
  private final boolean unspecified;
  private final boolean privateRange;
  private final boolean linkLocal;
  private final boolean uniqueLocal;
  private final boolean reserved;

  private OutboundDestinationPolicy(
      String rawUrl,
      String protocol,
      String host,
      String resolvedHost,
      boolean loopback,
      boolean unspecified,
      boolean privateRange,
      boolean linkLocal,
      boolean uniqueLocal,
      boolean reserved) {
    this.rawUrl = rawUrl;
    this.protocol = protocol;
    this.host = host;
    this.resolvedHost = resolvedHost;
    this.loopback = loopback;
    this.unspecified = unspecified;
    this.privateRange = privateRange;
    this.linkLocal = linkLocal;
    this.uniqueLocal = uniqueLocal;
    this.reserved = reserved;
  }

  /**
   * Builds the guard for a connection destination, failing loudly on a non-permitted protocol.
   *
   * @throws IllegalArgumentException when the protocol is not {@code http} or {@code https}
   */
  public static OutboundDestinationPolicy of(String connectionUrl) {
    if (connectionUrl == null) {
      throw new IllegalArgumentException("connectionUrl must not be null");
    }
    URI uri;
    try {
      uri = URI.create(connectionUrl);
    } catch (IllegalArgumentException e) {
      // Covers "http://" (no authority) and malformed IPv6 literals alike; the message names both
      // the word "URL" (diagnostics) and "<host>" (the missing piece).
      throw new IllegalArgumentException(
          "Connection URL is malformed: " + connectionUrl + " (expected http://<host>/path)", e);
    }
    if (uri.getScheme() == null) {
      throw new IllegalArgumentException("Connection URL has no protocol: " + connectionUrl);
    }
    Protocol protocol;
    try {
      protocol = Protocol.valueOf(uri.getScheme().toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Connection URL protocol is not http/https: "
              + uri.getScheme()
              + " (only HTTP/HTTPS is permitted)",
          e);
    }
    String host = uri.getHost();
    if (host == null) {
      throw new IllegalArgumentException("Connection URL has no host: " + connectionUrl);
    }
    String resolvedHost = null;
    InetAddress resolved = null;
    try {
      resolved = InetAddress.getByName(host);
      resolvedHost = resolved.getHostAddress();
    } catch (Exception ignored) {
      // An unresolvable host is classified on its literal name below; the connection will fail at
      // open time. The guard still refuses loopback/unspecified literals regardless.
    }
    boolean loopback = resolved != null && resolved.isLoopbackAddress();
    boolean unspecified =
        resolved == null
            ? ANY_LOCAL.equals(host) || ANY_LOCAL_IPV6.equals(host)
            : resolved.isAnyLocalAddress();
    boolean privateRange = resolved != null && resolved.isSiteLocalAddress();
    boolean linkLocal = resolved != null && resolved.isLinkLocalAddress();
    boolean uniqueLocal = resolved != null && isUniqueLocalAddress(resolved);
    boolean reserved =
        resolved != null && (reservedForFuture(resolved) || isDocumentationAddress(resolved));
    return new OutboundDestinationPolicy(
        connectionUrl,
        protocol.name(),
        host,
        resolvedHost,
        loopback,
        unspecified,
        privateRange,
        linkLocal,
        uniqueLocal,
        reserved);
  }

  /** A permitted connection is one that is allowed by protocol and is not refused by host. */
  public boolean isPermitted() {
    return !loopback && !unspecified && !privateRange && !linkLocal && !uniqueLocal && !reserved;
  }

  /**
   * Returns the reason this destination is refused, or {@code null} when it is permitted. The
   * reason is a stable, machine-readable label — never the connection URL, so a destination name
   * cannot leak through diagnostics (invariant 8).
   */
  public String reasonIfDenied() {
    if (!isPermitted()) {
      if (loopback) {
        return "LOOPBACK";
      }
      if (unspecified) {
        return "UNSPECIFIED";
      }
      if (privateRange) {
        return "PRIVATE_RANGE";
      }
      if (linkLocal) {
        return "LINK_LOCAL";
      }
      if (uniqueLocal) {
        return "UNIQUE_LOCAL";
      }
      if (reserved) {
        return "RESERVED";
      }
    }
    return null;
  }

  public String protocol() {
    return protocol;
  }

  /** The host as written in the URL (never null). */
  public String host() {
    return host;
  }

  /**
   * The resolved IP address of the host, or {@code null} when the host could not be resolved (a
   * hostname with no DNS record). A null value means the guard classified the literal name.
   */
  public String resolvedHost() {
    return resolvedHost;
  }

  public boolean isLoopback() {
    return loopback;
  }

  public boolean isPrivateRange() {
    return privateRange;
  }

  public boolean isLinkLocal() {
    return linkLocal;
  }

  public boolean isReserved() {
    return reserved;
  }

  /**
   * True when this guard is refused for any reason (protocol is always permitted on construction).
   */
  public boolean isDenied() {
    return reasonIfDenied() != null;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof OutboundDestinationPolicy otherPolicy)) {
      return false;
    }
    return loopback == otherPolicy.loopback
        && unspecified == otherPolicy.unspecified
        && privateRange == otherPolicy.privateRange
        && linkLocal == otherPolicy.linkLocal
        && uniqueLocal == otherPolicy.uniqueLocal
        && reserved == otherPolicy.reserved
        && protocol.equals(otherPolicy.protocol);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        protocol, loopback, unspecified, privateRange, linkLocal, uniqueLocal, reserved);
  }

  @Override
  public String toString() {
    // The protocol + classification is observable; the resolved host (if any) is not secret, but it
    // is a diagnostic, so this value is only ever logged on a denial path (reasonIfDenied), never
    // the full URL.
    String target = resolvedHost != null ? resolvedHost : host;
    String reason = reasonIfDenied();
    return reason == null
        ? protocol + "(" + target + ")"
        : protocol + "(" + target + ") denied=" + reason;
  }

  /**
   * RFC 4193 unique-local IPv6 (fc00::/7): the top seven bits of the first byte are {@code 0xfc},
   * i.e. {@code (b0 & 0xfe) == 0xfc}. Only IPv6 addresses carry a unique-local range.
   */
  private static boolean isUniqueLocalAddress(InetAddress ip) {
    byte[] bytes = ip.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
  }

  private static boolean reservedForFuture(InetAddress ip) {
    // 240.0.0.0/4 — reserved for future use, and ::/8 in IPv6 is "this network" (not the
    // unspecified address, which is handled by isAnyLocalAddress).
    byte[] bytes = ip.getAddress();
    if (bytes.length == 4) {
      return (bytes[0] & 0xf0) == 0xf0;
    }
    if (bytes.length == 16) {
      return bytes[0] == 0x00;
    }
    return false;
  }

  private static boolean isDocumentationAddress(InetAddress ip) {
    // RFC 5737 documentation ranges: 192.0.2.0/24, 198.51.100.0/24, 203.0.113.0/24.
    byte[] bytes = ip.getAddress();
    if (bytes.length == 4) {
      int b0 = bytes[0] & 0xff;
      int b1 = bytes[1] & 0xff;
      if (b0 == 192 && b1 == 0) {
        return (bytes[2] & 0xff) == 2;
      }
      if (b0 == 198 && b1 == 51) {
        return (bytes[2] & 0xff) == 100;
      }
      if (b0 == 203 && b1 == 0) {
        return (bytes[2] & 0xff) == 113;
      }
    }
    return false;
  }
}
