package com.example.agentflow.config;

import com.example.agentflow.config.AgentFlowProperties.Storage;
import com.example.agentflow.storage.adapter.GarageS3Adapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The Garage object-storage adapter wiring for the opt-in storage profile/role (S07-06C).
 *
 * <p>The {@link GarageS3Adapter} bean exists only when {@code agentflow.storage.enabled=true} — the
 * same condition as the shared {@link S3Client} bean ({@code StorageClientConfiguration}), so the
 * adapter is never created without its client. The bucket is taken from the validated typed config
 * ({@code agentflow.storage.bucket}, default {@code agentflow}); the adapter is bound to that
 * bucket and never re-chooses it. The adapter owns no lifecycle beyond construction — it holds only
 * the shared client and the bucket — so it is not closed by Spring; the client is closed through
 * {@code StorageClientConfiguration}'s {@code destroyMethod}.
 */
@Configuration
@ConditionalOnProperty(prefix = "agentflow.storage", name = "enabled", havingValue = "true")
public class StorageAdapterConfiguration {

  /**
   * Build the adapter from the shared client and the configured bucket.
   *
   * @param s3Client the shared singleton client
   * @param properties the validated {@code agentflow.storage.*} typed config
   * @return the adapter bound to its configured bucket
   */
  @Bean
  public GarageS3Adapter garageS3Adapter(S3Client s3Client, AgentFlowProperties properties) {
    Storage storage = properties.getStorage();
    return new GarageS3Adapter(s3Client, storage.getBucket());
  }
}
