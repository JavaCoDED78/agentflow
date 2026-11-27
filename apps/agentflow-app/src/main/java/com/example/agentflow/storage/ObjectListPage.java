package com.example.agentflow.storage;

import java.util.List;

/**
 * The {@code list} result (S07-06B, contract §2.2, §5.4 and §8).
 *
 * <p>A paginated, server-side-prefix page of object references in <b>strictly ascending key
 * order</b>. The cursor is opaque — a caller must not parse it. Contract rule: the cursor is
 * non-null iff {@code hasMore} is true (a page that is not the last has a cursor; the last page has
 * none). `list` is only a reconciliation/inspection operation — never an authorization path.
 *
 * <p>Immutable, provider-neutral, JDK-only; no tenant, run, prompt or content.
 *
 * @param items the page's object references, in strictly ascending key order; an empty list is a
 *     legitimate page (a {@code hasMore=false} terminal page can be empty); a defensive copy is
 *     stored, so mutating the caller's list never affects the page
 * @param nextCursor the opaque cursor for the next page; {@code null} iff {@code hasMore} is {@code
 *     false}
 * @param hasMore whether more pages exist
 */
public record ObjectListPage(List<ObjectReference> items, String nextCursor, boolean hasMore) {

  /**
   * Compact constructor.
   *
   * @throws ObjectStorageException with {@code INVALID_REQUEST} on any violation — null items, a
   *     null-cursor-with-{@code hasMore} invariant violation, a blank cursor, or a page that is not
   *     strictly ascending by key
   */
  public ObjectListPage {
    if (items == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "items must not be null");
    }
    items = List.copyOf(items);
    if (hasMore && nextCursor == null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST,
          "nextCursor must not be null when hasMore is true");
    }
    if (!hasMore && nextCursor != null) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "nextCursor must be null when hasMore is false");
    }
    if (nextCursor != null && nextCursor.isBlank()) {
      throw new ObjectStorageException(
          ObjectStorageErrorCode.INVALID_REQUEST, "nextCursor must not be blank");
    }
    for (int i = 1; i < items.size(); i++) {
      String previous = items.get(i - 1).key();
      String current = items.get(i).key();
      if (previous.compareTo(current) >= 0) {
        throw new ObjectStorageException(
            ObjectStorageErrorCode.INVALID_REQUEST,
            "items must be in strictly ascending key order");
      }
    }
  }
}
