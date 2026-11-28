package com.example.agentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.example.agentflow.storage.CreateObjectRequest;
import com.example.agentflow.storage.ObjectStorageException;
import com.example.agentflow.storage.adapter.GarageS3Adapter;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/**
 * S07-06G — the storage access policy, proven through DB-free, SDK-level seams:
 *
 * <ul>
 *   <li>the runtime credential (the sanctioned canary fixture) never reaches object metadata — the
 *       adapter stamps only the caller's own metadata plus the declared checksum;
 *   <li>the runtime credential never reaches an error message or an exception {@code toString}
 *       through every contract operation — a provider/transport failure whose message carries the
 *       canary cannot leak it through the contract boundary;
 *   <li>the runtime adapter never issues an administrative SDK operation (the source scan asserts
 *       every {@code s3Client.<method>(} is one of the five contract operations; nothing else — no
 *       bucket/key/policy/lifecycle/ACL admin call — exists in the runtime path;
 *   <li>the runtime adapter never implements tenant authorization — no tenant concept in the
 *       adapter source, and authorization stays with the consuming application service (the port
 *       contract is pinned by {@code ObjectStoragePortContractTest}).
 * </ul>
 *
 * <p>DB-free and network-free: the {@link S3Client} is mocked, and the canary is read from the
 * sanctioned fixture (the same fixture the {@code S07-06A} canary scan owns).
 */
class StorageCredentialsSecurityTest {

  private static final String CANARY_RESOURCE = "/secrets/canary-storage-access-key.txt";
  private static final String BUCKET = "agentflow-test";
  private static final String ADAPTER_SOURCE_PATH =
      "apps/agentflow-app/src/main/java/com/example/agentflow/storage/adapter/GarageS3Adapter.java";
  private static final String[] CONTRACT_OPERATIONS = {
    "putObject", "getObject", "headObject", "listObjectsV2", "deleteObject"
  };
  private static final Pattern S3_CLIENT_CALL =
      Pattern.compile("s3Client\\.([a-zA-Z0-9_]+)\\s*\\(");

  private final S3Client s3Client = mock(S3Client.class);
  private final GarageS3Adapter adapter = new GarageS3Adapter(s3Client, BUCKET);

  private String canary() {
    try (InputStream in = getClass().getResourceAsStream(CANARY_RESOURCE)) {
      assertThat(in).as("the canary fixture must be on the test classpath").isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (Exception exception) {
      throw new IllegalStateException("cannot read the canary fixture", exception);
    }
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
   * The mock drains the request body exactly like the real SDK would (verifying it happens as it is
   * read).
   */
  private void readBody() {
    lenient()
        .when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            invocation -> {
              RequestBody body = invocation.getArgument(1);
              try (InputStream stream = body.contentStreamProvider().newStream()) {
                while (stream.read() != -1) {
                  // drain exactly like the SDK does
                }
              }
              return PutObjectResponse.builder().build();
            });
  }

  @Test
  void put_metadata_never_carries_the_runtime_credential() {
    String canary = canary();
    byte[] bytes = "checkpoint bytes".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(bytes);
    readBody();

    adapter.put(
        new CreateObjectRequest(
            "checkpoints/run-1/output.bin",
            new ByteArrayInputStream(bytes),
            "application/octet-stream",
            (long) bytes.length,
            digest,
            Map.of("purpose", "checkpoint-test")));

    ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
    org.mockito.Mockito.verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
    Map<String, String> metadata = captor.getValue().metadata();

    // Only caller metadata plus the declared checksum may be stamped — nothing else.
    assertThat(metadata.keySet())
        .as("the object metadata must contain only the caller's own keys and the declared checksum")
        .containsExactlyInAnyOrder("purpose", "checksum-sha256");
    assertThat(metadata.get("checksum-sha256"))
        .as("checksum is the declared digest")
        .isEqualTo(digest);
    assertThat(metadata.values())
        .as("the runtime credential must never reach object metadata")
        .noneMatch(value -> value != null && value.contains(canary));
    assertThat(metadata.keySet())
        .as("the runtime credential must never appear as a metadata key")
        .noneMatch(key -> key != null && key.contains(canary));
  }

  @Test
  void put_error_path_never_echoes_the_runtime_credential() {
    String canary = canary();
    byte[] bytes = "checkpoint bytes".getBytes(StandardCharsets.UTF_8);
    String digest = sha256(bytes);
    lenient()
        .when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenThrow(
            new IllegalStateException(
                "transport detail carrying the runtime credential " + canary,
                new IllegalStateException("cause also carrying " + canary)));

    assertThatThrownBy(
            () ->
                adapter.put(
                    new CreateObjectRequest(
                        "checkpoints/run-1/output.bin",
                        new ByteArrayInputStream(bytes),
                        "application/octet-stream",
                        (long) bytes.length,
                        digest,
                        Map.of())))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException typed = (ObjectStorageException) exception;
              assertThat(typed.getMessage()).doesNotContain(canary);
              assertThat(typed.toString()).doesNotContain(canary);
              assertThat(typed.requestId().toString()).doesNotContain(canary);
            });
  }

  @Test
  void head_error_path_never_echoes_the_runtime_credential() {
    String canary = canary();
    lenient()
        .when(s3Client.headObject(any(HeadObjectRequest.class)))
        .thenThrow(
            new IllegalStateException(
                "transport detail carrying the runtime credential " + canary));

    assertThatThrownBy(() -> adapter.head("checkpoints/run-1/output.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException typed = (ObjectStorageException) exception;
              assertThat(typed.getMessage()).doesNotContain(canary);
              assertThat(typed.toString()).doesNotContain(canary);
            });
  }

  @Test
  void get_error_path_never_echoes_the_runtime_credential() {
    String canary = canary();
    lenient()
        .when(s3Client.getObject(any(GetObjectRequest.class)))
        .thenThrow(
            new IllegalStateException(
                "transport detail carrying the runtime credential " + canary));

    assertThatThrownBy(() -> adapter.get("checkpoints/run-1/output.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException typed = (ObjectStorageException) exception;
              assertThat(typed.getMessage()).doesNotContain(canary);
              assertThat(typed.toString()).doesNotContain(canary);
            });
  }

  @Test
  void list_error_path_never_echoes_the_runtime_credential() {
    String canary = canary();
    lenient()
        .when(s3Client.listObjectsV2(any(ListObjectsV2Request.class)))
        .thenThrow(
            new IllegalStateException(
                "transport detail carrying the runtime credential " + canary));

    assertThatThrownBy(() -> adapter.list("checkpoints/run-1/", null, 10))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException typed = (ObjectStorageException) exception;
              assertThat(typed.getMessage()).doesNotContain(canary);
              assertThat(typed.toString()).doesNotContain(canary);
            });
  }

  @Test
  void delete_error_path_never_echoes_the_runtime_credential() {
    String canary = canary();
    lenient()
        .when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
        .thenThrow(
            new IllegalStateException(
                "transport detail carrying the runtime credential " + canary));

    assertThatThrownBy(() -> adapter.delete("checkpoints/run-1/output.bin"))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            exception -> {
              ObjectStorageException typed = (ObjectStorageException) exception;
              assertThat(typed.getMessage()).doesNotContain(canary);
              assertThat(typed.toString()).doesNotContain(canary);
            });
  }

  @Test
  void the_runtime_adapter_never_issues_an_administrative_sdk_operation() throws Exception {
    String canary = canary();

    String source =
        Files.readString(repoRoot().resolve(ADAPTER_SOURCE_PATH), StandardCharsets.UTF_8);

    Set<String> sdkCalls = new HashSet<>();
    Matcher matcher = S3_CLIENT_CALL.matcher(source);
    while (matcher.find()) {
      sdkCalls.add(matcher.group(1));
    }
    assertThat(sdkCalls)
        .as(
            "every s3Client.<method>( call in the runtime adapter must be one of the five contract operations")
        .isSubsetOf(Set.of(CONTRACT_OPERATIONS));
    assertThat(sdkCalls)
        .as("the runtime adapter must use the full contract surface (put/get/head/list/delete)")
        .containsExactlyInAnyOrder(CONTRACT_OPERATIONS);

    // The adapter has no administrative bucket/key/policy/lifecycle/ACL surface — no admin call may
    // appear anywhere in the runtime path. Credentials may never ride it either.
    String lower = source.toLowerCase();
    for (String forbidden :
        new String[] {
          "createbucket",
          "deletebucket",
          "listbuckets",
          "putbucketpolicy",
          "getbucketpolicy",
          "putbucketcors",
          "getbucketcors",
          "deletebucketencryption",
          "getbucketencryption",
          "putbucketpolycy",
          "putbucketlifecycleconfiguration",
          "getbucketlifecycleconfiguration",
          "putbucketversioning",
          "getbucketversioning",
          "listaccesskeys",
          "createaccesskey",
          "deleteaccesskey",
          "listgetbucketaccelerateconfiguration",
          "putbucketacl",
          "getbucketacl",
          "delebucketacl",
          "listbuckethapi",
          "listbucketowners",
          "createmultipartupload",
          "completemultipartupload",
          "abortmultipartupload",
          "listmultipartuploads",
          "getbucketlogging",
          "putbucketlogging",
          "aws_access_key",
          "aws_secret_key",
          "access_key_id",
          "private_key",
          "secret_key",
          "agentflow_object_storage",
          "canary-storage-access-key"
        }) {
      assertThat(lower)
          .as(
              "the runtime adapter must not reference an administrative or credential operation: "
                  + forbidden)
          .doesNotContain(forbidden);
    }

    // The canary must not be hardcoded anywhere in the runtime path either.
    assertThat(source)
        .as("the runtime adapter source must never contain a credential")
        .doesNotContain(canary);
  }

  @Test
  void the_runtime_adapter_never_implements_tenant_authorization() throws Exception {
    String source =
        Files.readString(repoRoot().resolve(ADAPTER_SOURCE_PATH), StandardCharsets.UTF_8);

    // Tenant authorization is defined by the consuming application service against PostgreSQL
    // ownership records — never by the adapter. The adapter source carries no tenant concept.
    String lower = source.toLowerCase();
    for (String forbidden : new String[] {"tenant", "isolation", "authoriz", "permission", "acl"}) {
      assertThat(lower)
          .as("the runtime adapter must not implement tenant authorization: " + forbidden)
          .doesNotContain(forbidden);
    }
  }

  private Path repoRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isDirectory(current.resolve(".git"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException(
        "git repository root not found walking up from " + Path.of(System.getProperty("user.dir")));
  }
}
