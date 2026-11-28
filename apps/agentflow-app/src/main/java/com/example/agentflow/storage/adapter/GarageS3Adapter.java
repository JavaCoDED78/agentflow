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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.NonRetryableException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.InvalidRequestException;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The one adapter for the provider-neutral {@link ObjectStoragePort} contract (S07-06C/D/E).
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
 * <p>The {@code head} / {@code list} / {@code delete} operations (S07-06E) are implemented against
 * the same S3 API. {@code head} reports presence + metadata from the stored object's headers — a
 * missing key yields {@code Optional.empty()}, never an exception — and requires the same stored
 * {@code checksum-sha256} anchor as {@code get}. {@code list} pages over a server-generated prefix
 * (structurally validated, never reinterpreted) through S3's continuation-token pagination — a page
 * is built with one {@code listObjectsV2} call plus one bounded {@code headObject} per item (the
 * only honest source of the reference's {@code contentType}/{@code sha256} — a list response
 * carries neither, so nothing is fabricated). {@code delete} is idempotent: a present object is
 * removed ({@code DELETED}), a missing one returns {@code ALREADY_ABSENT} — a success in either
 * case. All three operations are bound to the configured bucket and never accept one from the
 * caller.
 *
 * <p><b>Unified error policy (S07-06F).</b> Every operation classifies provider failures through
 * the single {@code mapFailure} mapper, so no path can drift on the taxonomy: a deterministic
 * caller-side rejection (verification failure, {@code INVALID_REQUEST}); a typed missing object
 * ({@code NOT_FOUND}); the SDK's own per-attempt / total-call timeout ({@code TIMEOUT}); an
 * explicit throttling classification or {@code 429}/{@code 5xx} ({@code UNAVAILABLE}); a {@code
 * 401}/{@code 403} ({@code ACCESS_DENIED}); a model-level {@code 400} ({@code INVALID_REQUEST}); a
 * {@code 408}/{@code 504} ({@code TIMEOUT}); any other structural {@code 4xx} ({@code
 * PERMANENT_FAILURE}); a transport-level fault ({@code UNAVAILABLE} or {@code TIMEOUT} by cause);
 * and anything else ({@code PERMANENT_FAILURE}). Only {@code UNAVAILABLE} and {@code TIMEOUT} are
 * retryable, and the retry loop itself is never this module's business — the bounded SDK client
 * owns it; the caller may add its own bounded backoff on top. Messages stay key-free and
 * content-free; the provider's request/correlation id, when supplied, is preserved on the exception
 * (never a secret).
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
      throw new ObjectStorageException(
          ObjectStorageErrorCode.NOT_FOUND, "object not found", null, requestIdFrom(exception));
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
   *
   * @param operation the operation whose message is used when a field is missing ("get"/"head")
   */
  private ObjectMetadata buildMetadata(GetObjectResponse response, String key) {
    return metadataFromHeaders(
        response.contentLength(),
        response.contentType(),
        response.lastModified(),
        response.eTag(),
        response.metadata(),
        key,
        "get");
  }

  private ObjectMetadata buildMetadata(HeadObjectResponse response, String key) {
    return metadataFromHeaders(
        response.contentLength(),
        response.contentType(),
        response.lastModified(),
        response.eTag(),
        response.metadata(),
        key,
        "head");
  }

  private ObjectMetadata metadataFromHeaders(
      Long contentLength,
      String contentType,
      Instant lastModified,
      String etag,
      Map<String, String> metadata,
      String key,
      String operation) {
    if (contentLength == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " returned no content length");
    }
    String safeContentType = contentType;
    if (safeContentType == null || safeContentType.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " returned no content type");
    }
    if (lastModified == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " returned no last-modified instant");
    }
    if (etag == null || etag.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " returned no ETag");
    }
    String sha256 = metadata == null ? null : metadata.get(CHECKSUM_METADATA_KEY);
    if (sha256 == null || sha256.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage "
              + operation
              + " found no stored checksum on the object — it was written without a declared checksum");
    }
    return new ObjectMetadata(
        bucket, key, safeContentType, contentLength, sha256, lastModified, etag);
  }

  // -------------------------------------------------------------------------------------------
  // head, list, delete — S07-06E.
  // -------------------------------------------------------------------------------------------

  /**
   * Presence + metadata. A missing key yields {@code Optional.empty()} — presence is checked
   * without throwing. The metadata is built from the stored object's headers exactly as {@code get}
   * does: the stored {@code checksum-sha256} is the only honest digest, so an object written
   * without a declared checksum is refused, never reported with a fabricated digest.
   */
  @Override
  public Optional<ObjectMetadata> head(String key) throws ObjectStorageException {
    validateKey(key);
    HeadObjectResponse response;
    try {
      response = s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException exception) {
      return Optional.empty();
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "head");
    }
    if (response == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE, "object storage head returned no response");
    }
    return Optional.of(buildMetadata(response, key));
  }

  /**
   * Paginated, server-side prefix listing. The prefix is structurally validated (never
   * reinterpreted — the consumer is the only authority on which prefix it may see), the limit is
   * bounded to 1–100, and the cursor is passed through to the provider as-is (opaque to the caller
   * and to this adapter). One {@code listObjectsV2} call yields the page's keys + sizes; each
   * item's {@link ObjectReference} is then built from one bounded {@code headObject} per item — the
   * only honest source of the reference's {@code contentType} and {@code sha256} (a list response
   * carries neither). The page is required to be strictly ascending by key — a provider that
   * returns otherwise is a structural fault, never a normalized one.
   */
  @Override
  public ObjectListPage list(String prefix, String cursor, int limit)
      throws ObjectStorageException {
    if (limit < 1 || limit > MAX_LIST_LIMIT) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "limit must be between 1 and " + MAX_LIST_LIMIT + ", was " + limit);
    }
    validatePrefix(prefix);
    if (cursor != null && cursor.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "cursor must not be blank");
    }

    ListObjectsV2Response response;
    try {
      ListObjectsV2Request.Builder builder =
          ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).maxKeys(limit);
      if (cursor != null) {
        builder.continuationToken(cursor);
      }
      response = s3Client.listObjectsV2(builder.build());
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "list");
    }

    String nextCursor = response.nextContinuationToken();
    boolean hasMore = nextCursor != null && !nextCursor.isBlank();
    if (Boolean.TRUE.equals(response.isTruncated()) && !hasMore) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage list reported a truncated page without a continuation token");
    }

    List<S3Object> pageObjects = new ArrayList<>();
    String previousKey = null;
    for (S3Object object : response.contents()) {
      String itemKey = object.key();
      if (itemKey == null || itemKey.isBlank()) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.PERMANENT_FAILURE,
            "object storage list returned an item without a key");
      }
      if (previousKey != null && previousKey.compareTo(itemKey) >= 0) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.PERMANENT_FAILURE,
            "object storage list returned unordered keys");
      }
      previousKey = itemKey;
      pageObjects.add(object);
    }

    List<ObjectReference> items = new ArrayList<>();
    for (S3Object object : pageObjects) {
      String itemKey = object.key();
      ObjectMetadata metadata = headMetadata(itemKey);
      items.add(
          new ObjectReference(
              bucket, itemKey, metadata.contentType(), metadata.size(), metadata.sha256()));
    }

    return new ObjectListPage(items, hasMore ? nextCursor : null, hasMore);
  }

  /**
   * Idempotent delete. A present object is removed ({@code DELETED}); a missing object returns
   * {@code ALREADY_ABSENT} — an idempotent success, never a failure. Never opens content. The
   * presence check uses a raw {@code headObject} (not {@code head}'s metadata anchor): a stored
   * object without a declared checksum is still deleted — the metadata anchor governs {@code
   * head}/{@code get} reporting, not the idempotent delete.
   */
  @Override
  public DeleteResult delete(String key) throws ObjectStorageException {
    validateKey(key);
    try {
      s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (NoSuchKeyException exception) {
      return DeleteResult.ALREADY_ABSENT;
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "delete");
    }
    try {
      s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "delete");
    }
    return DeleteResult.DELETED;
  }

  /**
   * The per-item metadata for a listed key: one bounded {@code headObject} per item — the only
   * honest source of the reference's {@code contentType}/{@code sha256}. An item that vanished
   * between the list and the head is a structural provider fault, never a silent drop.
   */
  private ObjectMetadata headMetadata(String itemKey) {
    HeadObjectResponse response;
    try {
      response =
          s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(itemKey).build());
    } catch (NoSuchKeyException exception) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage list returned an item that no longer exists");
    } catch (Throwable throwable) {
      throw mapFailure(throwable, "list");
    }
    if (response == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage list returned an item whose head returned no response");
    }
    return buildMetadata(response, itemKey);
  }

  // -------------------------------------------------------------------------------------------
  // Internals
  // -------------------------------------------------------------------------------------------

  /** Maximum page size for the paginated listing (contract §5.4: 1–100). */
  private static final int MAX_LIST_LIMIT = 100;

  /**
   * The contract's structural key/prefix rules, repeated in the adapter — the callers pass raw keys
   * and prefixes to a raw adapter, and the contract's own validation home, the package-private
   * {@code StorageValidation}, is in the root package and is not visible to this adapter. The
   * {@link ObjectMetadata} constructor re-runs the same rules for metadata keys. A rejected
   * key/prefix never reaches the network.
   */
  private static void validateKey(String key) {
    validateStructuredName(key, "key");
  }

  /**
   * The same structural rules applied to a list prefix — a prefix is never reinterpreted, only
   * validated and passed to the provider as-is.
   */
  private static void validatePrefix(String prefix) {
    validateStructuredName(prefix, "prefix");
  }

  private static void validateStructuredName(String value, String kind) {
    if (value == null || value.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY, kind + " must not be null or blank");
    }
    if (value.length() > 1024) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_KEY,
          kind + " must not exceed 1024 characters, was " + value.length());
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < 0x20) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, kind + " must not contain control characters");
      }
    }
    for (String segment : value.split("/", -1)) {
      if (segment.equals("..")) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_KEY, kind + " must not contain '..' path segments");
      }
    }
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

  /**
   * The unified storage error policy (S07-06F). Every operation classifies provider failures
   * through this single mapper, so no path can drift on the taxonomy. The order is deliberate:
   *
   * <ol>
   *   <li><b>Deterministic caller-side rejection</b> — a {@link NonRetryableException} anywhere in
   *       the cause chain (verification failure) → {@code INVALID_REQUEST} (never retried, never
   *       provider-side).
   *   <li><b>Missing object</b> — a {@link NoSuchKeyException} (any 404-ish class) → {@code
   *       NOT_FOUND} (typed miss, never a raw provider exception).
   *   <li><b>SDK-level timeout</b> — the SDK's own per-attempt / total-call timeout classes →
   *       {@code TIMEOUT} (retryable).
   *   <li><b>Service failures</b> — an {@link AwsServiceException}: the SDK's explicit throttling
   *       classification (or a {@code 429}) → {@code UNAVAILABLE} (retryable); a model {@link
   *       InvalidRequestException} (a 400-shaped rejection) → {@code INVALID_REQUEST} (a
   *       deterministic rejection); a {@code 401}/{@code 403} → {@code ACCESS_DENIED}; a {@code
   *       408}/{@code 504} → {@code TIMEOUT}; a 5xx → {@code UNAVAILABLE} (retryable); anything
   *       else (structural 4xx, e.g. malformed bucket config) → {@code PERMANENT_FAILURE}.
   *   <li><b>Transport faults</b> — an {@link SdkClientException}: a timeout-shaped cause → {@code
   *       TIMEOUT}; otherwise → {@code UNAVAILABLE} (retryable).
   *   <li>Anything else — {@code PERMANENT_FAILURE}.
   * </ol>
   *
   * <p>Only {@code UNAVAILABLE} and {@code TIMEOUT} are retryable; the retry loop itself is
   * <b>not</b> this module's business — the bounded SDK client owns it (and the caller may add its
   * own bounded backoff on top). Messages stay key-free, content-free and detail-free: no key,
   * content, credential, header or raw provider detail ever reaches the contract boundary. The
   * provider's request/correlation id, when the provider supplies one, is preserved on the
   * exception — it is a safe correlation value, never a secret.
   */
  private ObjectStorageException mapFailure(Throwable throwable, String operation) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current instanceof NonRetryableException nonRetryable) {
        // The verifying stream signals a deterministically rejectable payload failure — the SDK
        // never retries it, and the message is key-free and content-free.
        return new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST, nonRetryable.getMessage());
      }
    }
    if (throwable instanceof NoSuchKeyException) {
      return new ObjectStorageException(
          ObjectStorageErrorCode.NOT_FOUND, "object storage " + operation + " found no such key");
    }
    if (throwable instanceof ApiCallTimeoutException
        || throwable instanceof ApiCallAttemptTimeoutException) {
      // The SDK's own timeout class — a bounded wait exceeded, never a generic transport fault.
      return new ObjectStorageException(
          ObjectStorageErrorCode.TIMEOUT, "object storage " + operation + " timed out");
    }
    if (throwable instanceof AwsServiceException awsService) {
      String requestId = requestIdFrom(awsService);
      if (awsService.isThrottlingException()) {
        // The SDK's explicit throttling classification — a transient condition, retryable.
        return new ObjectStorageException(
            ObjectStorageErrorCode.UNAVAILABLE,
            "object storage " + operation + " is unavailable",
            null,
            requestId);
      }
      if (throwable instanceof InvalidRequestException) {
        // A 400-shaped model rejection — deterministic, never retried.
        return new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "object storage " + operation + " was rejected by the provider",
            null,
            requestId);
      }
      int statusCode = awsService.statusCode();
      if (statusCode == 401 || statusCode == 403) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.ACCESS_DENIED,
            "object storage " + operation + " was denied by the provider",
            null,
            requestId);
      }
      if (statusCode == 408 || statusCode == 504) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.TIMEOUT,
            "object storage " + operation + " timed out",
            null,
            requestId);
      }
      if (statusCode == 429 || statusCode >= 500) {
        return new ObjectStorageException(
            ObjectStorageErrorCode.UNAVAILABLE,
            "object storage " + operation + " is unavailable",
            null,
            requestId);
      }
      return new ObjectStorageException(
          ObjectStorageErrorCode.PERMANENT_FAILURE,
          "object storage " + operation + " was rejected by the provider",
          null,
          requestId);
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

  /**
   * The provider's request/correlation id, if it supplied one — never a fabricated value. The
   * {@link AwsServiceException} carries the {@code x-amzn-request-id}; a blank value is treated as
   * absent. {@code null} when the provider carried none.
   */
  private static String requestIdFrom(AwsServiceException awsService) {
    String requestId = awsService.requestId();
    if (requestId != null && !requestId.isBlank()) {
      return requestId.trim();
    }
    return null;
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
