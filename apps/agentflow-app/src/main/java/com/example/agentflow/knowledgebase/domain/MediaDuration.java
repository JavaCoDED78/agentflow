package com.example.agentflow.knowledgebase.domain;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Minimal, dependency-free WAV duration decoder, {@code S10-03}. A WAV file is a RIFF container
 * whose header records the sample rate, channel count and bits-per-sample, so its playback duration
 * is derivable from the header alone — no audio library required (and none is on the build
 * classpath).
 *
 * <p>This exists only to enforce the configured media-duration limit on {@code audio/wav} uploads:
 * {@code duration = data_bytes / byte_rate}. A payload that is not a well-formed WAV, or whose
 * header cannot be parsed, is reported as {@link Optional#empty()} so the caller can refuse to
 * accept an undecodable media file rather than silently accept something it could not bound.
 */
public final class MediaDuration {

  private static final int WAV_MIN_LENGTH = 44;

  private MediaDuration() {}

  /**
   * @param payload the raw upload bytes
   * @return the whole-second playback duration of a well-formed WAV payload, or {@link
   *     Optional#empty()} when the payload is not a parseable WAV
   */
  public static Optional<Long> durationSeconds(byte[] payload) {
    if (payload == null || payload.length < WAV_MIN_LENGTH) {
      return Optional.empty();
    }
    ByteBuffer buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
    if (!isRiff(payload, "RIFF", 0) || !isRiff(payload, "WAVE", 8)) {
      return Optional.empty();
    }
    // Skip to the fmt chunk fields we need: chunkSize(4) + "fmt "(4).
    long sampleRate = getUint32(buffer, 24);
    int numChannels = getUint16(buffer, 22);
    int bitsPerSample = getUint16(buffer, 34);
    long dataBytes = getUint32(buffer, 40);
    if (dataBytes < 0 || sampleRate <= 0 || numChannels <= 0 || bitsPerSample <= 0) {
      return Optional.empty();
    }
    long byteRate = sampleRate * numChannels * (bitsPerSample / 8);
    if (byteRate <= 0) {
      return Optional.empty();
    }
    return Optional.of(dataBytes / byteRate);
  }

  private static boolean isRiff(byte[] payload, String tag, int offset) {
    byte[] signature = tag.getBytes(StandardCharsets.US_ASCII);
    if (offset + signature.length > payload.length) {
      return false;
    }
    for (int i = 0; i < signature.length; i++) {
      if (payload[offset + i] != signature[i]) {
        return false;
      }
    }
    return true;
  }

  private static long getUint32(ByteBuffer buffer, int position) {
    int save = buffer.position();
    buffer.position(position);
    long value = buffer.getInt() & 0xFFFF_FFFFL;
    buffer.position(save);
    return value;
  }

  private static int getUint16(ByteBuffer buffer, int position) {
    int save = buffer.position();
    buffer.position(position);
    int value = buffer.getShort() & 0xFFFF;
    buffer.position(save);
    return value;
  }
}
