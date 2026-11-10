package com.example.agentflow.knowledgebase.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link MediaDuration} WAV decoder ({@code S10-03}). DB-free: the decoder is
 * pure and reads only the header. A tiny sample rate keeps the test payloads small while the
 * decoded duration is exact (the value the media-duration limit is applied to).
 */
class MediaDurationTest {

  @Test
  void the_duration_is_the_data_size_divided_by_the_byte_rate() {
    // 8000 Hz, mono, 8-bit => 1 s is 8000 bytes.
    byte[] wav = buildWav(8000, 1, 8, 8000 * 3);

    assertThat(MediaDuration.durationSeconds(wav)).hasValue(3L);
  }

  @Test
  void a_longer_media_payload_reports_a_longer_duration() {
    // A low sample rate keeps the byte array small while encoding a 600 s file.
    byte[] wav = buildWav(100, 1, 8, 600 * 100);

    assertThat(MediaDuration.durationSeconds(wav)).hasValue(600L);
  }

  @Nested
  class NotWav {

    @Test
    void a_non_wav_payload_is_empty() {
      byte[] pdf = "%PDF-1.7 header".getBytes(StandardCharsets.US_ASCII);
      assertThat(MediaDuration.durationSeconds(pdf)).isEmpty();
    }

    @Test
    void a_payload_smaller_than_a_wav_header_is_empty() {
      assertThat(MediaDuration.durationSeconds(new byte[10])).isEmpty();
    }

    @Test
    void a_null_payload_is_empty() {
      assertThat(MediaDuration.durationSeconds(null)).isEmpty();
    }

    @Test
    void a_riff_without_wave_is_empty() {
      byte[] riff = "RIFF".getBytes(StandardCharsets.US_ASCII);
      byte[] payload = new byte[44];
      System.arraycopy(riff, 0, payload, 0, 4);
      assertThat(MediaDuration.durationSeconds(payload)).isEmpty();
    }
  }

  /** Build a minimal, well-formed WAV header over {@code dataSize} of payload. */
  private static byte[] buildWav(int sampleRate, int numChannels, int bitsPerSample, int dataSize) {
    byte[] payload = new byte[44 + dataSize];
    ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(36 + dataSize);
    buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
    buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(16); // subchunk1Size
    buffer.putShort((short) 1); // audioFormat: PCM
    buffer.putShort((short) numChannels);
    buffer.putInt(sampleRate);
    int byteRate = sampleRate * numChannels * (bitsPerSample / 8);
    buffer.putInt(byteRate);
    buffer.putShort((short) (numChannels * (bitsPerSample / 8))); // blockAlign
    buffer.putShort((short) bitsPerSample);
    buffer.put("data".getBytes(StandardCharsets.US_ASCII));
    buffer.putInt(dataSize);
    return payload;
  }
}
