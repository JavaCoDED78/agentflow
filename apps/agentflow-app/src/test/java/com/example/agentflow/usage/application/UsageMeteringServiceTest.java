package com.example.agentflow.usage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import com.example.agentflow.usage.domain.Measurement;
import com.example.agentflow.usage.domain.UsageRecordDto;
import com.example.agentflow.usage.domain.UsageType;
import com.example.agentflow.usage.persistence.UsageRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * DB-free coverage of {@link UsageMeteringService}: it delegates the write to the {@link
 * UsageRepository} (null when no database is configured) and fails loudly — recording metadata is
 * never allowed to throw an unclassified fault into the caller.
 */
@ExtendWith(MockitoExtension.class)
class UsageMeteringServiceTest {

  @Mock private UsageRepository repository;

  @InjectMocks private UsageMeteringService service;

  @Test
  void record_delegates_to_repository_and_returns_record() {
    UsageRecordDto record = validModelRecord();

    UsageRecordDto returned = service.record(record);

    verify(repository).insert(record);
    assertThat(returned).isSameAs(record);
  }

  @Test
  void record_throws_when_record_null() {
    IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> service.record(null));

    assertThat(ex).hasMessageContaining("usage record must not be null");
  }

  @Test
  void record_throws_when_repository_absent() {
    UsageMeteringService noDb = new UsageMeteringService(null);

    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> noDb.record(validModelRecord()));

    assertThat(ex).hasMessageContaining("no database connection is configured");
  }

  private static UsageRecordDto validModelRecord() {
    return new UsageRecordDto(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        1,
        UsageType.MODEL,
        Measurement.ESTIMATED,
        "FAKE",
        "gpt-fake",
        null,
        null,
        12L,
        6L,
        null,
        null,
        null,
        null,
        null,
        null);
  }
}
