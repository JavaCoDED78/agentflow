package com.example.agentflow.toolexecution.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OutboundDestinationPolicy} (S11-10): the outbound destination policy is a
 * pure classifier of a connection host + protocol — only {@code http}/{@code https} and a routable
 * public host are permitted, everything loopback / unspecified / private / link-local / reserved is
 * refused (the SSRF guard for the MCP adapter).
 */
class OutboundDestinationPolicyTest {

  @Nested
  class Protocol {
    @Test
    void httpAndHttpsArePermittedProtocols() {
      assertThat(OutboundDestinationPolicy.of("http://93.184.216.34/x").protocol())
          .isEqualTo("HTTP");
      assertThat(OutboundDestinationPolicy.of("https://93.184.216.34/x").protocol())
          .isEqualTo("HTTPS");
      assertThat(OutboundDestinationPolicy.of("http://93.184.216.34/x").isPermitted()).isTrue();
    }

    @Test
    void nonHttpProtocolIsRejectedAtConstruction() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("ws://93.184.216.34/mcp"))
          .withMessageContaining("protocol");
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("wss://93.184.216.34/mcp"));
      // A non-network scheme has no host, so it is also rejected.
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("file:///etc/passwd"));
    }

    @Test
    void missingProtocolIsRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("//93.184.216.34/x"))
          .withMessageContaining("protocol");
    }

    @Test
    void missingHostIsRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("http://"))
          .withMessageContaining("host");
    }

    @Test
    void malformedUriIsRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of("http://[::1"))
          .withMessageContaining("URL");
    }

    @Test
    void nullUrlIsRejected() {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> OutboundDestinationPolicy.of(null))
          .withMessageContaining("null");
    }
  }

  @Nested
  class Host {
    @Test
    void publicIpv4IsPermitted() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://8.8.8.8/mcp");
      assertThat(policy.isPermitted()).isTrue();
      assertThat(policy.reasonIfDenied()).isNull();
      assertThat(policy.resolvedHost()).isEqualTo("8.8.8.8");
    }

    @Test
    void publicIpv6IsPermitted() {
      OutboundDestinationPolicy policy =
          OutboundDestinationPolicy.of("https://[2001:4860:4860::8888]/mcp");
      assertThat(policy.isPermitted()).isTrue();
      assertThat(policy.resolvedHost()).isEqualTo("2001:4860:4860:0:0:0:0:8888");
    }

    @Test
    void loopbackIpv4IsRefused() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://127.0.0.1/mcp");
      assertThat(policy.isPermitted()).isFalse();
      assertThat(policy.reasonIfDenied()).isEqualTo("LOOPBACK");
    }

    @Test
    void loopbackIpv6IsRefused() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://[::1]/mcp");
      assertThat(policy.isPermitted()).isFalse();
      assertThat(policy.reasonIfDenied()).isEqualTo("LOOPBACK");
    }

    @Test
    void localhostHostnameResolvesToLoopbackIsRefused() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://localhost:8080/mcp");
      assertThat(policy.isPermitted()).isFalse();
      assertThat(policy.reasonIfDenied()).isEqualTo("LOOPBACK");
    }

    @Test
    void unspecifiedIpv4IsRefused() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://0.0.0.0/mcp");
      assertThat(policy.isPermitted()).isFalse();
      assertThat(policy.reasonIfDenied()).isEqualTo("UNSPECIFIED");
    }

    @Test
    void unspecifiedIpv6IsRefused() {
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of("http://[::]/mcp");
      assertThat(policy.isPermitted()).isFalse();
      assertThat(policy.reasonIfDenied()).isEqualTo("UNSPECIFIED");
    }

    @Test
    void privateRangesAreRefused() {
      assertThat(OutboundDestinationPolicy.of("http://10.0.0.5/mcp").reasonIfDenied())
          .isEqualTo("PRIVATE_RANGE");
      assertThat(OutboundDestinationPolicy.of("http://172.16.0.1/mcp").reasonIfDenied())
          .isEqualTo("PRIVATE_RANGE");
      assertThat(OutboundDestinationPolicy.of("http://172.31.255.255/mcp").reasonIfDenied())
          .isEqualTo("PRIVATE_RANGE");
      assertThat(OutboundDestinationPolicy.of("http://192.168.1.1/mcp").reasonIfDenied())
          .isEqualTo("PRIVATE_RANGE");
    }

    @Test
    void linkLocalIsRefusedIncludingMetadataEndpoint() {
      // 169.254.169.254 is the cloud metadata endpoint; it is link-local (169.254.0.0/16).
      assertThat(
              OutboundDestinationPolicy.of("http://169.254.169.254/latest/meta-data/")
                  .reasonIfDenied())
          .isEqualTo("LINK_LOCAL");
      assertThat(OutboundDestinationPolicy.of("http://169.254.1.1/mcp").reasonIfDenied())
          .isEqualTo("LINK_LOCAL");
    }

    @Test
    void uniqueLocalIpv6IsRefused() {
      assertThat(OutboundDestinationPolicy.of("http://[fc00::1]/mcp").reasonIfDenied())
          .isEqualTo("UNIQUE_LOCAL");
    }

    @Test
    void reservedFutureIsRefused() {
      assertThat(OutboundDestinationPolicy.of("http://240.0.0.1/mcp").reasonIfDenied())
          .isEqualTo("RESERVED");
    }

    @Test
    void documentationRangesAreRefused() {
      assertThat(OutboundDestinationPolicy.of("http://192.0.2.10/mcp").reasonIfDenied())
          .isEqualTo("RESERVED");
      assertThat(OutboundDestinationPolicy.of("http://198.51.100.10/mcp").reasonIfDenied())
          .isEqualTo("RESERVED");
      assertThat(OutboundDestinationPolicy.of("http://203.0.113.10/mcp").reasonIfDenied())
          .isEqualTo("RESERVED");
    }
  }

  @Nested
  class ValueContract {
    @Test
    void equalsAndHashCodeAreContentBased() {
      OutboundDestinationPolicy a = OutboundDestinationPolicy.of("http://8.8.8.8/x");
      OutboundDestinationPolicy b = OutboundDestinationPolicy.of("https://8.8.8.8/x");
      OutboundDestinationPolicy c = OutboundDestinationPolicy.of("http://8.8.8.8/x");
      // Protocol is part of identity (the permitted set is http/https); same host + different
      // protocol is a different policy; same protocol + same host is equal.
      assertThat(a).isEqualTo(c);
      assertThat(a).isNotEqualTo(b);
      assertThat(a.hashCode()).isEqualTo(c.hashCode());
      assertThat(OutboundDestinationPolicy.of("http://127.0.0.1/x")).isNotEqualTo(a);
    }

    @Test
    void toStringIsInvariant8SafeAndNeverCarriesTheFullUrl() {
      String url = "http://127.0.0.1:8080/mcp";
      OutboundDestinationPolicy policy = OutboundDestinationPolicy.of(url);
      String rendered = policy.toString();
      assertThat(rendered).contains("LOOPBACK").contains("HTTP");
      assertThat(rendered).doesNotContain(url);
      assertThat(rendered).doesNotContain(":8080");
    }

    @Test
    void isDeniedTrueOnlyWhenRefused() {
      assertThat(OutboundDestinationPolicy.of("http://8.8.8.8/x").isDenied()).isFalse();
      assertThat(OutboundDestinationPolicy.of("http://127.0.0.1/x").isDenied()).isTrue();
    }

    @Test
    void hostReflectsTheLiteralName() {
      assertThat(OutboundDestinationPolicy.of("http://example.internal/x").host())
          .isEqualTo("example.internal");
      assertThat(OutboundDestinationPolicy.of("http://example.internal/x").resolvedHost()).isNull();
    }
  }
}
