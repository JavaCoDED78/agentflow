package com.example.agentflow.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Contract unit tests for the {@link ObjectListPage} list result (S07-06B, contract §5.4 and §9
 * case 10 — the pagination invariants).
 */
class ObjectListPageTest {

  private static final String VALID_SHA256 =
      "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

  private static ObjectReference ref(String key) {
    return new ObjectReference("agentflow", key, "application/pdf", 1L, VALID_SHA256);
  }

  @Test
  void an_ascending_page_with_a_cursor_is_kept() {
    ObjectListPage page =
        new ObjectListPage(List.of(ref("a"), ref("b"), ref("c")), "cursor-1", true);

    assertThat(page.items()).hasSize(3);
    assertThat(page.nextCursor()).isEqualTo("cursor-1");
    assertThat(page.hasMore()).isTrue();
  }

  @Test
  void an_empty_terminal_page_is_kept() {
    ObjectListPage page = new ObjectListPage(List.of(), null, false);

    assertThat(page.items()).isEmpty();
    assertThat(page.nextCursor()).isNull();
    assertThat(page.hasMore()).isFalse();
  }

  @Test
  void a_null_items_list_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(null, null, false))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_has_more_page_without_a_cursor_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(List.of(ref("a")), null, true))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_terminal_page_with_a_cursor_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(List.of(ref("a")), "cursor-1", false))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_blank_cursor_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(List.of(ref("a")), "   ", true))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_non_ascending_page_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(List.of(ref("b"), ref("a")), "cursor-1", true))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void a_page_with_duplicate_keys_is_a_contract_failure() {
    assertThatThrownBy(() -> new ObjectListPage(List.of(ref("a"), ref("a")), "cursor-1", true))
        .isInstanceOf(ObjectStorageException.class)
        .satisfies(
            e ->
                assertThat(((ObjectStorageException) e).code())
                    .isEqualTo(ObjectStorageErrorCode.INVALID_REQUEST));
  }

  @Test
  void the_items_list_is_defensively_copied() {
    List<ObjectReference> caller = new ArrayList<>();
    caller.add(ref("a"));
    caller.add(ref("b"));
    ObjectListPage page = new ObjectListPage(caller, "cursor-1", true);
    caller.add(ref("c"));
    caller.clear();

    assertThat(page.items()).hasSize(2);
  }

  @Test
  void the_items_list_is_unmodifiable() {
    ObjectListPage page = new ObjectListPage(List.of(ref("a")), null, false);

    assertThatThrownBy(() -> page.items().add(ref("b")))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
