package com.example.agentflow.storage.adapter;

import com.example.agentflow.storage.CreateObjectRequest;
import com.example.agentflow.storage.DeleteResult;
import com.example.agentflow.storage.ObjectListPage;
import com.example.agentflow.storage.ObjectMetadata;
import com.example.agentflow.storage.ObjectReference;
import com.example.agentflow.storage.ObjectStorageErrorCode;
import com.example.agentflow.storage.ObjectStorageException;
import com.example.agentflow.storage.ObjectStoragePort;
import com.example.agentflow.storage.OpenedObject;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AccessDeniedException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The one adapter for the provider-neutral {@link ObjectStoragePort} contract (S07-06C).
 *
 * <p>{@code put} is the streaming upsert of the storage contract: the caller's stream is consumed
 * through a bounded, chunked reader (never {@code readAllBytes()}), the actual bytes are verified
 * against any declared {@code contentLength} and {@code sha256} before anything is sent, and the
 * written object is bound to the adapter's configured bucket — the caller never re-chooses the
 * bucket. A declared {@code contentLength} is <b>required</b>: the streaming SDK upload cannot send
 * an unknown-length body without buffering the whole object in heap, and the contract forbids
 * heap-loading the object — an undeclared length is an honest {@code INVALID_REQUEST}, never a
 * silent mis-sized write.
 *
 * <p>The checksum is computed over the actual bytes, progressively, by the adapter itself — never
 * delegated to a provider-side checksum computation — so the persisted {@link ObjectReference}
 * carries the digest of what was actually stored. The caller's declared {@code sha256} (if any) is
 * passed to the object as the {@code checksum-sha256} metadata entry (the only checksum available
 * at request-build time — the computed digest is only known after the stream is read), the declared
 * content type is passed as the object's {@code Content-Type}, and the caller's metadata is passed
 * through as-is. No content, key, credential or secret material is logged.
 *
 * <p>{@code get} (S07-06D) opens the object for streaming read and returns an {@link OpenedObject}
 * whose stream is caller-owned: the provider body stream is handed out lazily — no bytes are read
 * in {@code get} itself — and it is released when the caller closes the object
 * (try-with-resources), so reading is bounded-memory. A missing key is mapped to a typed {@code
 * NOT_FOUND}; provider failures reuse the same provider-neutral error policy as {@code put}. The
 * stored {@code checksum-sha256} object metadata — the only checksum stamped on the stored object
 * (the caller's declared checksum, verified against the actual bytes by {@code put}) — is reported
 * as the {@link OpenedObject} metadata's {@code sha256}; an object that carries no stored checksum
 * (written without a declared checksum) is refused with {@code PERMANENT_FAILURE} rather than
 * reported with a fabricated digest.
 *
 * <p>The {@code head} / {@code list} / {@code delete} operations are deliberately not implemented
 * yet — they land with {@code S07-06E}…{@code S07-06H}. They throw a typed {@link
 * ObjectStorageException} naming the pending task rather than a raw {@code
 * UnsupportedOperationException}.
 */
public class GarageS3Adapter implements ObjectStoragePort {

  private static final Logger log = LoggerFactory.getLogger(GarageS3Adapter.class);

  /**
   * Maximum in-flight chunk read from the caller's stream (bounded memory — the contract forbids
   * heap-loading the object).
   */
  private static final int CHUNK_SIZE = 64 * 1024;

  /** Metadata key under which the declared checksum is stamped on the stored object. */
  private static final String CHECKSUM_METADATA_KEY = "checksum-sha256";

  private final S3Client s3Client;
  private final String bucket;

  /**
   * @param s3Client the shared {@link S3Client} built by {@code StorageClientConfiguration} (never
   *     per-request)
   * @param bucket the configured bucket the adapter is bound to; never blank
   */
  public GarageS3Adapter(S3Client s3Client, String bucket) {
    this.s3Client = Objects.requireNonNull(s3Client, "s3 client must not be null");
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalArgumentException(
          "agentflow.storage.bucket must not be blank — the Garage adapter is bound to its configured bucket");
    }
    this.bucket = bucket;
  }

  @Override
  public ObjectReference put(CreateObjectRequest request) throws ObjectStorageException {
    Objects.requireNonNull(request, "request must not be null");

    long declaredLength = request.contentLength() == null ? -1 : request.contentLength();
    if (declaredLength < 0) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "contentLength must be declared — the streaming SDK upload cannot send an unknown-length body without buffering the whole object in heap, and the contract forbids heap-loading the object");
    }

    Map<String, String> metadata = new LinkedHashMap<>(request.metadata());
    if (request.sha256() != null) {
      // The only checksum available at request-build time is the caller's declared one — the
      // computed digest is only known after the stream is consumed. The persisted reference carries
      // the computed digest; the object metadata carries the declared one.
      metadata.put(CHECKSUM_METADATA_KEY, request.sha256());
    }

    VerifyingInputStream verifying =
        new VerifyingInputStream(request.stream(), declaredLength, request.sha256());

    try {
      if (declaredLength == 0) {
        // An empty object: nothing may be sent, but the declared length/digest must still be
        // verified against the (empty) stream — drain it through the bounded reader below.
        drain(verifying);
      }

      PutObjectRequest.Builder putRequestBuilder =
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(request.key())
              .contentType(request.contentType());
      if (!metadata.isEmpty()) {
        putRequestBuilder.metadata(metadata);
      }
      PutObjectRequest putRequest = putRequestBuilder.build();

      RequestBody body =
          declaredLength == 0
              ? RequestBody.empty()
              : RequestBody.fromInputStream(verifying, declaredLength);
      s3Client.putObject(putRequest, body);

      return new ObjectReference(
          bucket,
          request.key(),
          request.contentType(),
          verifying.bytesRead(),
          verifying.computedSha256());
    } catch (ObjectStorageException exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "put");
    } finally {
      closeQuietly(verifying);
    }
  }

  @Override
  public OpenedObject get(String key) throws ObjectStorageException {
    // Key validation is caller-side and deterministic — it never reaches the network. The same
    // rules are re-checked by the ObjectMetadata constructor that follows (via the contract's
    // value types), so a rejected key never escapes twice with different codes.
    validateKey(key);

    // The SDK returns the streamed body directly: the headers are eagerly parsed while the body
    // stream is lazy — no bytes are read in get itself. The response is opened eagerly and held
    // by the adapter until the OpenedObject is handed out (or construction fails); the caller
    // closes it (try-with-resources) to release the underlying connection.
    ResponseInputStream<GetObjectResponse> stream;
    try {
      stream = s3Client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException exception) {
      throw new ObjectStorageException(ObjectStorageErrorCode.NOT_FOUND, "object not found");
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "get");
    }
    try {
      GetObjectResponse response = stream.response();
      if (response == null) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.PERMANENT_FAILURE,
            "object storage get returned no response metadata");
      }
      return new OpenedObject(buildMetadata(response, key), stream);
    } catch (Throwable failure) {
      // The stream is released before the failure escapes — a rejected object never leaks.
      closeQuietly(stream);
      if (failure instanceof ObjectStorageException exception) {
        throw exception;
      }
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage get failed while building the result",
          failure);
    }
  }

  /**
   * Build the eager {@link ObjectMetadata} from the response's parsed headers. Every value is
   * provider-supplied and validated — the adapter never invents a size, a content type, an ETag or
   * a digest. The stored {@code checksum-sha256} object metadata is the only digest the object
   * carries (the caller's declared checksum, verified by {@code put} against the actual bytes), so
   * it is the only honest {@code sha256} to report; an object with no stored checksum is refused
   * rather than reported with a fabricated digest.
   */
  private ObjectMetadata buildMetadata(GetObjectResponse response, String key) {
    Long contentLength = response.contentLength();
    if (contentLength == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage get returned no content length");
    }
    String contentType = response.contentType();
    if (contentType == null || contentType.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE, "object storage get returned no content type");
    }
    java.time.Instant lastModified = response.lastModified();
    if (lastModified == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage get returned no last-modified instant");
    }
    String etag = response.eTag();
    if (etag == null || etag.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE, "object storage get returned no ETag");
    }
    Map<String, String> metadata = response.metadata();
    String sha256 = metadata == null ? null : metadata.get(CHECKSUM_METADATA_KEY);
    if (sha256 == null || sha256.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage get found no stored checksum on the object — it was written without a declared checksum");
    }
    return new ObjectMetadata(bucket, key, contentType, contentLength, sha256, lastModified, etag);
  }

  // -------------------------------------------------------------------------------------------
  // Not implemented yet — S07-06E/F/G/H.
  // -------------------------------------------------------------------------------------------

  @Override
  public Optional<ObjectMetadata> head(String key) throws ObjectStorageException {
    throw notImplemented("head", "S07-06E");
  }

  @Override
  public ObjectListPage list(String prefix, String cursor, int limit)
      throws ObjectStorageException {
    throw notImplemented("list", "S07-06E");
  }

  @Override
  public DeleteResult delete(String key) throws ObjectStorageException {
    throw notImplemented("delete", "S07-06E");
  }

  // -------------------------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------------------------

  /**
   * The contract's structural key rules, repeated in the adapter — the {@code get} path takes a raw
   * key and has no request object to validate on its behalf (the contract's own validation home,
   * the package-private {@code StorageValidation}, is in the root package and is not visible to
   * this adapter). The {@link ObjectMetadata} constructor re-runs the same rules. A rejected key
   * never reaches the network.
   */
  private static void validateKey(String key) {
    if (key == null || key.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY, "key must not be null or blank");
    }
    if (key.length() > 1024) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY,
          "key must not exceed 1024 characters, was " + key.length());
    }
    for (int i = 0; i < key.length(); i++) {
      if (key.charAt(i) < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, "key must not contain control characters");
      }
    }
    for (String segment : key.split("/", -1)) {
      if (segment.equals("..")) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, "key must not contain '..' path segments");
      }
    }
  }

  private static ObjectStorageException notImplemented(String operation, String task) {
    return new ObjectStorageException(
        ObjectStorageErrorCode.PERMANENT_FAILURE,
        "object storage " + operation + " is not implemented yet — lands with " + task);
  }

  private static String hex(MessageDigest digest) {
    byte[] bytes = digest.digest();
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(Character.forDigit((b >> 4) & 0xF, 16));
      sb.append(Character.forDigit(b & 0xF, 16));
    }
    return sb.toString();
  }

  /**
   * Drain the stream to its end through the bounded reader, verifying the declared length/digest.
   * The empty-object path uses this instead of letting the SDK send a zero-length body, because the
   * SDK does not read a zero-length stream — a declared checksum/length would never be verified.
   */
  private static void drain(VerifyingInputStream verifying) throws IOException {
    byte[] chunk = new byte[CHUNK_SIZE];
    while (verifying.read(chunk) != -1) {
      // drain to EOF; the bounded reader's verification runs at the EOF transition
    }
  }

  private ObjectStorageException mapFailure(Throwable throwable, String operation) {
    // The canonical provider-neutral error policy lives in one place — put and get both classify
    // provider failures through this single mapper so neither can drift. The messages carry the
    // operation name, never the key, the content, a credential or the provider's raw detail.
    // The verifying stream signals a deterministically rejectable payload failure with a
    // NonRetryableException — the SDK never retries it, and the message is key-free and
    // content-free.
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof NonRetryableException nonRetryable) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, nonRetryable.getMessage());
      }
    }
    if (throwable instanceof AccessDeniedException) {
      return new ObjectStorageException(
          ObjectStorageErrorCode.ACCESS_DENIED,
          "object storage " + operation + " was denied by the provider");
    }
    if (throwable instanceof AwsServiceException awsService) {
      int statusCode = awsService.statusCode();
      if (statusCode == 403) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.ACCESS_DENIED,
            "object storage " + operation + " was denied by the provider");
      }
      if (statusCode == 408 || statusCode == 504) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.TIMEOUT, "object storage " + operation + " timed out");
      }
      if (statusCode == 429 || statusCode >= 500) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.UNAVAILABLE, "object storage " + operation + " is unavailable");
      }
      return new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " was rejected by the provider");
    }
    if (throwable instanceof SdkClientException) {
      // Transport-level fault — provider unreachable or the response could not be read.
      if (isTimeout(throwable)) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.TIMEOUT, "object storage " + operation + " timed out");
      }
      return new ObjectStorageException(
          ObjectStorageErrorCode.UNAVAILABLE, "object storage " + operation + " is unavailable");
    }
    return new ObjectStorageException(
        ObjectStorageErrorCode.PERMANENT_FAILURE, "object storage " + operation + " failed");
  }

  private static boolean isTimeout(Throwable throwable) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof java.util.concurrent.TimeoutException) return true;
      if (current instanceof java.net.SocketTimeoutException) return true;
      if (current instanceof java.net.SocketException
          && current.getMessage() != null
          && (current.getMessage().toLowerCase().contains("timed out")
              || current.getMessage().toLowerCase().contains("timeout"))) return true;
    }
    return false;
  }

  private static void closeQuietly(InputStream stream) {
    if (stream == null) return;
    try {
      stream.close();
    } catch (Throwable closeFailure) {
      log.debug("object storage stream release failed", closeFailure);
    }
  }

  /**
   * The bounded chunked reader over the caller's stream: tracks the bytes read, hashes them into a
   * running SHA-256 and verifies the declared {@code contentLength} / {@code sha256} — a mismatch
   * is an honest {@code INVALID_REQUEST} rejection, never a silent acceptance.
   *
   * <p>Memory use is bounded by a single {@link #CHUNK_SIZE} buffer plus the SDK's own bounded read
   * window — the object is never heap-loaded.
   */
  private static final class VerifyingInputStream extends InputStream {

    private final InputStream delegate;
    private final long declaredLength;
    private final String declaredSha256;

    private final MessageDigest digest;
    private final byte[] buffer = new byte[CHUNK_SIZE];

    private int bufferPos = 0;
    private int bufferLen = 0;
    private long bytesRead = 0L;
    private boolean finished = false;
    private NonRetryableException stickyFailure = null;
    private String computedSha256 = null;
    private boolean finalized = false;

    VerifyingInputStream(InputStream delegate, long declaredLength, String declaredSha256) {
      this.delegate = Objects.requireNonNull(delegate, "stream must not be null");
      this.declaredLength = declaredLength;
      this.declaredSha256 = declaredSha256;
      try {
        this.digest = MessageDigest.getInstance("SHA-256");
      } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException("SHA-256 is not available on this JVM", exception);
      }
    }

    long bytesRead() {
      return bytesRead;
    }

    String computedSha256() {
      if (computedSha256 == null) {
        throw new IllegalStateException("verifying stream was not fully drained");
      }
      return computedSha256;
    }

    @Override
    public int read() throws IOException {
      byte b[] = new byte[1];
      int n = read(b, 0, 1);
      return n < 0 ? -1 : (b[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (b == null || off < 0 || len < 0 || off + len > b.length) {
        throw new IllegalArgumentException("bad read range");
      }
      if (len == 0) return 0;
      if (stickyFailure != null) throw stickyFailure;
      int total = 0;
      while (total < len) {
        if (bufferPos >= bufferLen) {
          if (finished) return total == 0 ? -1 : total;
          bufferLen = readNextChunk();
          bufferPos = 0;
          if (bufferLen == 0) {
            finished = true;
            if (total > 0) return total;
            try {
              verifyAtEnd();
            } catch (NonRetryableException exception) {
              stickyFailure = exception;
              throw exception;
            }
            return -1;
          }
        }
        int take = Math.min(len - total, bufferLen - bufferPos);
        System.arraycopy(buffer, bufferPos, b, off + total, take);
        bufferPos += take;
        total += take;
      }
      return total;
    }

    private int readNextChunk() throws IOException {
      int total = 0;
      while (total < CHUNK_SIZE) {
        int read = delegate.read(buffer, total, CHUNK_SIZE - total);
        if (read == -1) break;
        digest.update(buffer, 0, read);
        bytesRead += read;
        total += read;
        if (declaredLength >= 0 && bytesRead > declaredLength) {
          throw nonRetryable(
              "object stream exceeds the declared content length (declared "
                  + declaredLength
                  + " bytes)");
        }
      }
      finalizeIfComplete();
      return total;
    }

    /**
     * Finalize the digest once the declared length is fully read — the SDK stops reading at the
     * declared content length (never at EOF), so the digest cannot be finalized at EOF only. The
     * result is stored for the reference's computed sha256.
     */
    private void finalizeIfComplete() {
      if (finalized) return;
      if (declaredLength >= 0 && bytesRead >= declaredLength) {
        // The declared length is fully read — compute and verify the digest here, once.
        String computed = hex(digest);
        computedSha256 = computed;
        finalized = true;
        if (declaredSha256 != null && !computed.equals(declaredSha256)) {
          throw nonRetryable(
              "actual sha256 does not match the declared sha256 (declared " + declaredSha256 + ")");
        }
      }
    }

    private void verifyAtEnd() {
      // The stream ended before the declared length was read — an honest mismatch. (When the
      // declared length was fully read, the digest was finalized inside readNextChunk.)
      if (declaredLength >= 0 && bytesRead != declaredLength) {
        throw nonRetryable(
            "actual length "
                + bytesRead
                + " bytes does not match the declared content length "
                + declaredLength);
      }
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    private static NonRetryableException nonRetryable(String message) {
      return NonRetryableException.create(message);
    }
  }
}
