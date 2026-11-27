package com.example.agentflow.storage.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.agentflow.storage.CreateObjectRequest;
import com.example.agentflow.storage.ObjectReference;
import com.example.agentflow.storage.ObjectStorageErrorCode;
import com.example.agentflow.storage.ObjectStorageException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;

/**
 * Integration test for {@code S07-06C} — the {@code GarageS3Adapter} put path against a real Garage
 * container (the same image the compose stack runs, {@code dxflrs/garage:v2.4.1}).
 *
 * <p>Mirrors the production wiring in {@code StorageClientConfiguration} (endpoint override, region
 * {@code garage}, path-style addressing, static environment credentials, bounded timeouts) so the
 * adapter is exercised exactly as the app runs it. Each test writes to a unique key under a unique
 * prefix — no data is shared between tests and the container is discarded after the suite.
 *
 * <p>The written object's size, content type, metadata and the caller's declared checksum are
 * verified through the SDK's own {@code headObject} — the same operation the {@code S07-06E}
 * adapter will expose through {@code head}; it is not a substitute for that operation, it is the
 * honest confirmation that the object landed in Garage as written.
 *
 * <p>No bucket or key is accepted from the test body beyond what the adapter is configured — the
 * adapter is bound to its configured bucket — so every assertion uses the adapter's own reference
 * plus the raw SDK confirmation.
 */
@Testcontainers
@DisplayName("Garage adapter put (integration, real Garage via Testcontainers)")
class GarageS3AdapterIT {

  private static final String BUCKET = "agentflow-it";
  private static final String ACCESS_KEY = "agentflowitaccesskey0123456789";
  private static final String SECRET_KEY =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String RPC_SECRET =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String GARAGE_TOML =
      "metadata_dir = \"/var/lib/garage/meta\"\n"
          + "data_dir = \"/var/lib/garage/data\"\n"
          + "db_engine = \"sqlite\"\n"
          + "replication_factor = 1\n"
          + "rpc_bind_addr = \"[::]:3901\"\n"
          + "[s3_api]\n"
          + "s3_region = \"garage\"\n"
          + "api_bind_addr = \"[::]:3900\"\n"
          + "root_domain = \".s3.garage.localhost\"\n";

  /**
   * Generated once for the suite, before the container field is initialized: Garage requires its
   * {@code [s3_api]} config at startup and there is no flag-based equivalent, so the config is
   * written to a temp file the container is mounted from.
   */
  private static final Path tomlFile;

  static {
    try {
      Path file = Files.createTempFile("agentflow-it-garage", ".toml");
      Files.writeString(file, GARAGE_TOML, StandardCharsets.UTF_8);
      tomlFile = file;
    } catch (IOException exception) {
      throw new IllegalStateException(
          "cannot generate the Garage config for the integration test", exception);
    }
  }

  @Container
  static GenericContainer<?> garage =
      new GenericContainer<>("dxflrs/garage:v2.4.1")
          .withCommand("/garage", "server", "--single-node", "--default-bucket")
          .withEnv("GARAGE_DEFAULT_BUCKET", BUCKET)
          .withEnv("GARAGE_DEFAULT_ACCESS_KEY", ACCESS_KEY)
          .withEnv("GARAGE_DEFAULT_SECRET_KEY", SECRET_KEY)
          .withEnv("GARAGE_RPC_SECRET", RPC_SECRET)
          .withFileSystemBind(
              tomlFile.toAbsolutePath().toString(), "/etc/garage.toml", BindMode.READ_ONLY)
          .withExposedPorts(3900);

  private static S3Client s3Client;
  private static GarageS3Adapter adapter;

  /**
   * Mirrors the production singleton wiring ({@code StorageClientConfiguration}) with bounded
   * timeouts and path-style addressing — the credentials are the throwaway local test values above,
   * injected through the environment of the container and used for the client only.
   */
  @BeforeAll
  static void wire() {
    String endpoint = "http://" + garage.getHost() + ":" + garage.getMappedPort(3900);
    s3Client =
        S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of("garage"))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .httpClientBuilder(
                Apache5HttpClient.builder()
                    .connectionTimeout(java.time.Duration.ofSeconds(5))
                    .socketTimeout(java.time.Duration.ofSeconds(30)))
            // Mirrors StorageClientConfiguration — Garage rejects the SDK's default
            // trailing-checksum chunked body (see the same comment there).
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .build();
    adapter = new GarageS3Adapter(s3Client, BUCKET);
    awaitBucket();
  }

  @AfterAll
  static void close() {
    if (s3Client != null) {
      s3Client.close();
    }
  }

  /**
   * Wait until the auto-bootstrapped bucket answers headBucket — bounded, so a wedged container
   * fails fast.
   */
  private static void awaitBucket() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (true) {
      try {
        s3Client.headBucket(HeadBucketRequest.builder().bucket(BUCKET).build());
        return;
      } catch (RuntimeException exception) {
        if (System.nanoTime() > deadline) {
          throw new IllegalStateException(
              "Garage bucket " + BUCKET + " did not become ready within 60s", exception);
        }
        try {
          Thread.sleep(500);
        } catch (InterruptedException exception2) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted waiting for bucket readiness", exception2);
        }
      }
    }
  }

  private String uniqueKey(String prefix) {
    return prefix + "/" + UUID.randomUUID() + ".bin";
  }

  private static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder sb = new StringBuilder();
      for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
      return sb.toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  /**
   * An InputStream that generates {@code totalBytes} of deterministic bytes lazily — the adapter's
   * streaming contract is exercised against a source that is never heap-loaded.
   */
  private static final class GeneratingInputStream extends InputStream {
    private final long totalBytes;
    private long index;

    GeneratingInputStream(long totalBytes) {
      this.totalBytes = totalBytes;
    }

    @Override
    public int read() throws IOException {
      int n = read(new byte[1], 0, 1);
      return n < 0 ? -1 : n;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (index >= totalBytes) return -1;
      int n = (int) Math.min(len, totalBytes - index);
      for (int i = 0; i < n; i++) b[off + i] = (byte) ((index + i) % 251);
      index += n;
      return n;
    }

    @Override
    public long skip(long n) throws IOException {
      long skipped = Math.min(n, totalBytes - index);
      if (skipped > 0) index += skipped;
      return skipped;
    }

    @Override
    public int available() throws IOException {
      return (int) Math.min(totalBytes - index, 1 << 20);
    }

    @Override
    public void close() {}
  }

  private static String sha256OfGenerated(long totalBytes) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      GeneratingInputStream stream = new GeneratingInputStream(totalBytes);
      byte[] chunk = new byte[64 * 1024];
      int read;
      while ((read = stream.read(chunk, 0, chunk.length)) != -1) {
        digest.update(chunk, 0, read);
      }
      byte[] out = digest.digest();
      StringBuilder sb = new StringBuilder();
      for (byte b : out) sb.append(String.format("%02x", b & 0xff));
      return sb.toString();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private HeadObjectResponse head(String key) {
    return s3Client.headObject(HeadObjectRequest.builder().bucket(BUCKET).key(key).build());
  }

  // -------------------------------------------------------------------------------------------
  // The put path — byte array, large stream, repeat-put, empty object, declared-checksum mismatch
  // -------------------------------------------------------------------------------------------

  @Test
  @DisplayName("a byte-array put writes the object and the reference carries the computed bytes")
  void byte_array_put_writes_the_object_and_returns_the_computed_reference() {
    byte[] bytes = "the durable object body — S07-06C integration".getBytes();
    String key = uniqueKey("it/byte-array");
    String declaredSha256 = sha256(bytes);

    ObjectReference reference =
        adapter.put(
            new CreateObjectRequest(
                key,
                new ByteArrayInputStream(bytes),
                "application/octet-stream",
                (long) bytes.length,
                declaredSha256,
                Map.of("pipeline", "extract-v1")));

    assertThat(reference.bucket()).isEqualTo(BUCKET);
    assertThat(reference.key()).isEqualTo(key);
    assertThat(reference.contentType()).isEqualTo("application/octet-stream");
    assertThat(reference.size()).isEqualTo(bytes.length);
    assertThat(reference.sha256()).isEqualTo(declaredSha256);

    HeadObjectResponse head = head(key);
    assertThat(head.contentLength()).isEqualTo(bytes.length);
    assertThat(head.contentType()).isEqualTo("application/octet-stream");
    assertThat(head.metadata())
        .containsEntry("pipeline", "extract-v1")
        .containsEntry("checksum-sha256", declaredSha256);
  }

  @Test
  @DisplayName("a large stream put is streamed end-to-end without heap-loading the object")
  void large_stream_put_is_streamed_end_to_end_without_heap_loading() {
    long size = 2L * 1024 * 1024; // 2 MiB — well past the adapter's 64 KiB bounded read window
    String key = uniqueKey("it/large");

    ObjectReference reference =
        adapter.put(
            new CreateObjectRequest(
                key,
                new GeneratingInputStream(size),
                "application/octet-stream",
                size,
                null,
                Map.of()));

    assertThat(reference.size()).isEqualTo(size);
    assertThat(reference.sha256()).isEqualTo(sha256OfGenerated(size));

    HeadObjectResponse head = head(key);
    assertThat(head.contentLength()).isEqualTo(size);
    assertThat(head.contentType()).isEqualTo("application/octet-stream");
  }

  @Test
  @DisplayName("a repeated put on the same key overwrites the same logical object")
  void repeat_put_on_the_same_key_overwrites_the_same_logical_object() {
    byte[] bytes = "repeat content".getBytes();
    String key = uniqueKey("it/repeat");

    ObjectReference first =
        adapter.put(
            new CreateObjectRequest(
                key,
                new ByteArrayInputStream(bytes),
                "application/octet-stream",
                (long) bytes.length,
                sha256(bytes),
                Map.of()));
    ObjectReference second =
        adapter.put(
            new CreateObjectRequest(
                key,
                new ByteArrayInputStream(bytes),
                "application/octet-stream",
                (long) bytes.length,
                sha256(bytes),
                Map.of()));

    assertThat(second).isEqualTo(first);

    HeadObjectResponse head = head(key);
    assertThat(head.contentLength()).isEqualTo(bytes.length);
    assertThat(head.metadata()).containsEntry("checksum-sha256", sha256(bytes));
  }

  @Test
  @DisplayName("an empty object put round-trips with a zero size")
  void empty_object_put_round_trips_with_zero_size() {
    String key = uniqueKey("it/empty");

    ObjectReference reference =
        adapter.put(
            new CreateObjectRequest(
                key,
                new ByteArrayInputStream(new byte[0]),
                "application/octet-stream",
                0L,
                null,
                Map.of()));

    assertThat(reference.size()).isZero();

    HeadObjectResponse head = head(key);
    assertThat(head.contentLength()).isZero();
  }

  @Test
  @DisplayName("a declared checksum mismatch is rejected before anything is sent")
  void declared_checksum_mismatch_is_rejected_before_anything_is_sent() {
    byte[] bytes = "mismatch body".getBytes();
    String key = uniqueKey("it/mismatch");

    assertThatThrownBy(
            () ->
                adapter.put(
                    new CreateObjectRequest(
                        key,
                        new ByteArrayInputStream(bytes),
                        "application/octet-stream",
                        (long) bytes.length,
                        sha256("a-completely-different-declared-checksum".getBytes()),
                        Map.of())))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException ise = (ObjectStorageException) exception;
              assertThat(ise.code()).isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST);
              assertThat(ise.getMessage()).contains("sha256");
            });

    // The object was never uploaded — the head call confirms nothing landed.
    assertThatThrownBy(() -> head(key))
        .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
  }
}
