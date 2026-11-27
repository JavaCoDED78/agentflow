package com.example.agentflow.config;

import com.example.agentflow.config.AgentFlowProperties.Storage;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Singleton S3 client wiring for the opt-in storage profile/role (S07-06A, ADR-013, ADR-024).
 *
 * <p>The {@link S3Client} bean exists only when {@code agentflow.storage.enabled=true}; otherwise
 * no client is created and the app boots and passes readiness with no endpoint and no credentials —
 * the same opt-in shape as Redis ({@code S12-16}). The endpoint and the bounded transport settings
 * come from the validated typed config ({@code agentflow.storage.*}), which is the only
 * storage-typed state this bean reads. The access key and secret key are read from the environment
 * ({@code AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY} / {@code AGENTFLOW_OBJECT_STORAGE_SECRET_KEY}) and
 * never from configuration or source (invariant 8) — this class has no credential fields, the typed
 * config has no credential fields, and neither is logged.
 *
 * <p>The client is a Spring singleton — the application never creates a client per request (the
 * S07-06A acceptance). It is built with bounded transport settings:
 *
 * <ul>
 *   <li><b>connection / request timeouts</b> — {@code connection-timeout-seconds} / {@code
 *       request-timeout-seconds} bound a connection establishment and a single object request, so
 *       the client can never hang a thread indefinitely;
 *   <li><b>retry budget</b> — {@code max-retry-attempts} bounds the number of retries the SDK makes
 *       per request (a fully disabled retry policy is {@code 0});
 *   <li><b>path-style addressing</b> — the Garage default ({@code path-style-addressing: true});
 *   <li><b>credentials</b> — static, read from the environment; no default and no placeholder.
 * </ul>
 *
 * <p>Missing required values fail startup with a clear message that names the offending setting —
 * only for the enabled storage profile/role. The client is {@link AutoCloseable} and is shut down
 * through Spring's bean destruction, never via a manual {@code close()}.
 */
@Configuration
@ConditionalOnProperty(prefix = "agentflow.storage", name = "enabled", havingValue = "true")
public class StorageClientConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StorageClientConfiguration.class);

  private static final String ACCESS_KEY_ENV = "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY";
  private static final String SECRET_KEY_ENV = "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY";

  /**
   * Build the singleton {@link S3Client} from the validated typed config and the environment.
   *
   * @param properties the validated {@code agentflow.storage.*} typed config
   * @param env the environment, from which the access key and secret key are read
   * @return the singleton client wired with the bounded transport settings
   * @throws IllegalStateException when the endpoint or either credential is missing — each failure
   *     names the offending setting
   */
  @Bean(destroyMethod = "close")
  public S3Client s3Client(AgentFlowProperties properties, Environment env) {
    Storage storage = properties.getStorage();

    String endpoint = storage.getEndpoint();
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalStateException(
          "agentflow.storage.endpoint is required when agentflow.storage.enabled=true "
              + "(supply AGENTFLOW_OBJECT_STORAGE_ENDPOINT or agentflow.storage.endpoint) — the endpoint is "
              + "environment-specific and is never a hardcoded default");
    }

    String accessKey = env.getProperty(ACCESS_KEY_ENV);
    if (accessKey == null || accessKey.isBlank()) {
      throw new IllegalStateException(
          "AGENTFLOW_OBJECT_STORAGE_ACCESS_KEY is required when agentflow.storage.enabled=true — "
              + "the access key is read from the environment, never from configuration or source");
    }

    String secretKey = env.getProperty(SECRET_KEY_ENV);
    if (secretKey == null || secretKey.isBlank()) {
      throw new IllegalStateException(
          "AGENTFLOW_OBJECT_STORAGE_SECRET_KEY is required when agentflow.storage.enabled=true — "
              + "the secret key is read from the environment, never from configuration or source");
    }

    S3Client client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(storage.getRegion()))
            .serviceConfiguration(
                S3Configuration.builder()
                    .pathStyleAccessEnabled(storage.isPathStyleAddressing())
                    .build())
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            .httpClientBuilder(
                Apache5HttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(storage.getConnectionTimeoutSeconds()))
                    .socketTimeout(Duration.ofSeconds(storage.getRequestTimeoutSeconds())))
            .overrideConfiguration(
                ClientOverrideConfiguration.builder()
                    // The typed config bounds the number of retries (default 3); the retry
                    // strategy API configures the maximum number of total attempts, so the
                    // first attempt is always included (maxAttempts = maxRetryAttempts + 1).
                    // The standard strategy (the SDK default since 2.58.0) is the recommended
                    // implementation
                    // (https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/retry-strategy.html).
                    .retryStrategy(
                        StandardRetryStrategy.builder()
                            .maxAttempts((int) storage.getMaxRetryAttempts() + 1)
                            .build())
                    .build())
            .build();

    log.info(
        "agentflow.storage: S3 client created (endpoint={}, bucket={}, region={}, path-style={}, "
            + "connection-timeout={}s, request-timeout={}s, max-retries={})",
        sanitizedEndpoint(endpoint),
        storage.getBucket(),
        storage.getRegion(),
        storage.isPathStyleAddressing(),
        storage.getConnectionTimeoutSeconds(),
        storage.getRequestTimeoutSeconds(),
        storage.getMaxRetryAttempts());

    return client;
  }

  /**
   * Strip any user-info from the endpoint before it is logged, so a credential smuggled into the
   * URL's {@code userinfo} component can never appear in a log line. Never place the credentials
   * here — they are read from the environment and never logged.
   */
  private static String sanitizedEndpoint(String endpoint) {
    try {
      URI uri = new URI(endpoint);
      String scheme = uri.getScheme();
      String host = uri.getHost();
      int port = uri.getPort();
      String path = uri.getPath();
      return (scheme == null ? "" : scheme + "://")
          + (host == null ? "<unparseable>" : host + (port >= 0 ? ":" + port : ""))
          + (path == null ? "" : path);
    } catch (URISyntaxException exception) {
      return "<unparseable endpoint>";
    }
  }
}
