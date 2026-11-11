package com.example.agentflow.ingestion.application.chunk;

import com.example.agentflow.ingestion.domain.ChunkingConfig;
import com.example.agentflow.ingestion.domain.DocumentChunk;
import com.example.agentflow.ingestion.domain.ExtractionResult;
import com.example.agentflow.ingestion.domain.Strategy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deterministic document chunker (S10-09).
 *
 * <p>Splits an {@link ExtractionResult} into a stable, ordered list of {@link DocumentChunk}s using
 * the configured {@link ChunkingConfig#strategy()}. The {@code Chunker} is pure and deterministic:
 * it reads neither time nor randomness, iterates only ordered lists, and normalises whitespace
 * before hashing, so <b>one input plus one configuration always produce the same chunk set</b> —
 * the S10-09 acceptance criterion. Re-running it (or running it on another machine / profile)
 * reproduces byte-identical chunks and content hashes.
 *
 * <p>Pages are recovered from the form-feed (`\u000C`) separator Apache PDFBox writes between pages
 * of a PDF; text-only payloads (page count 0) are a single page. Each page is tokenised on
 * whitespace; the {@link Strategy#CHARACTER_BOUNDARY} strategy then greedily packs those tokens
 * into the fewest chunks within {@link ChunkingConfig#maxChunkChars()} (never breaking a word),
 * carries {@link ChunkingConfig#overlapChars()} of context forward into the next chunk, and records
 * per chunk its {@code chunk_index}, {@code page_from}/{@code page_to}, {@code content_hash}
 * (SHA-256 hex of the normalised content) and {@code pipeline_version}.
 *
 * <p>Pure and DB-/Kafka-free, so it is safe in every profile (including the {@code api} role that
 * never starts the ingestion worker).
 */
public final class Chunker {

  /**
   * The page separator Apache {@code PDFTextStripper} writes between pages of a PDF. Splitting the
   * extracted text on it recovers the per-page ranges the chunk records carry; content without it
   * (text payloads, or a PDF truncated to one page) is a single page.
   */
  private static final char PAGE_SEPARATOR = '\u000C';

  private static final Pattern WHITESPACE = Pattern.compile("\\s+");

  private final ChunkingConfig config;

  /**
   * @param config the chunking configuration (never {@code null}); validated in its constructor
   */
  public Chunker(ChunkingConfig config) {
    this.config = Objects.requireNonNull(config, "config");
  }

  /**
   * Chunk the extracted text.
   *
   * @param result the extracted document; a {@code null} result or one with blank text yields no
   *     chunks (never throws)
   * @return an ordered, deterministic list of chunks (empty when there is no content)
   */
  public List<DocumentChunk> chunk(ExtractionResult result) {
    String text = (result == null) ? null : result.text();
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<PageToken> tokens = tokenise(text);
    if (tokens.isEmpty()) {
      return List.of();
    }
    return chunkTokens(tokens);
  }

  /** The chunking strategy the caller configured. */
  public Strategy strategy() {
    return config.strategy();
  }

  /**
   * The pipeline version label of the configured chunking, so a caller can persist the exact {@code
   * pipeline_version} it keyed a document on (used by {@code S10-13} / {@code S10-19}).
   *
   * @return the pipeline version (never {@code null} or blank).
   */
  public String pipelineVersion() {
    return config.pipelineVersion();
  }

  /** Split the extracted text into a page-tagged, whitespace-delimited token stream. */
  private List<PageToken> tokenise(String text) {
    List<PageToken> tokens = new ArrayList<>();
    String[] rawPages = text.split(Pattern.quote(String.valueOf(PAGE_SEPARATOR)), -1);
    int page = 0;
    for (String rawPage : rawPages) {
      String pageText = rawPage.trim();
      if (pageText.isEmpty()) {
        continue; // skip the empty / separator-only segments; only real pages are numbered
      }
      page += 1;
      for (String word : WHITESPACE.split(pageText)) {
        tokens.add(new PageToken(page, word));
      }
    }
    return tokens;
  }

  /** Greedy word-wrap, with a context overlap carried forward between consecutive chunks. */
  private List<DocumentChunk> chunkTokens(List<PageToken> tokens) {
    int maxChunk = config.maxChunkChars();
    int overlap = config.overlapChars();
    String version = config.pipelineVersion();

    List<DocumentChunk> chunks = new ArrayList<>();
    List<PageToken> previous = List.of();
    List<PageToken> current = new ArrayList<>();
    int index = 0;

    for (PageToken token : tokens) {
      if (!current.isEmpty() && joinedLength(current) + 1 + token.word.length() > maxChunk) {
        chunks.add(build(current, previous, index, version));
        index += 1;
        previous = current;
        current = new ArrayList<>(); // overlap is applied at build time (see build), not here
      }
      current.add(token);
    }
    if (!current.isEmpty()) {
      chunks.add(build(current, previous, index, version));
    }
    return chunks;
  }

  /**
   * Build one chunk. When a previous chunk exists and overlap is configured, the overlap tail of
   * that previous chunk is prepended to the content (deterministic context carry-over) and extends
   * the chunk's page range leftward; the content hash is over the final (overlap-prefixed) content.
   */
  private DocumentChunk build(
      List<PageToken> content, List<PageToken> previous, int index, String version) {
    String body = join(content);
    int firstPage = content.get(0).page;
    int lastPage = content.get(content.size() - 1).page;

    String contentText = body;
    if (config.overlapChars() > 0 && !previous.isEmpty()) {
      List<PageToken> overlapTail = overlapTail(previous);
      if (!overlapTail.isEmpty()) {
        contentText = join(overlapTail) + " " + body;
        firstPage = Math.min(firstPage, overlapTail.get(0).page);
      }
    }
    return new DocumentChunk(
        index, firstPage, lastPage, contentText, sha256Hex(contentText), version);
  }

  /** The trailing words of {@code prev} that fit within the overlap budget (at least one word). */
  private List<PageToken> overlapTail(List<PageToken> prev) {
    if (config.overlapChars() == 0 || prev.isEmpty()) {
      return List.of();
    }
    List<PageToken> tail = new ArrayList<>();
    int len = 0;
    for (int i = prev.size() - 1; i >= 0; i--) {
      PageToken token = prev.get(i);
      int added = tail.isEmpty() ? token.word.length() : len + 1 + token.word.length();
      if (!tail.isEmpty() && added > config.overlapChars()) {
        break; // keep at least the single last word
      }
      tail.add(0, token);
      len = added;
    }
    return tail;
  }

  private static int joinedLength(List<PageToken> tokens) {
    int len = 0;
    for (PageToken token : tokens) {
      len += token.word.length();
    }
    return len + Math.max(tokens.size() - 1, 0);
  }

  private static String join(List<PageToken> tokens) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < tokens.size(); i++) {
      if (i > 0) {
        sb.append(' ');
      }
      sb.append(tokens.get(i).word);
    }
    return sb.toString();
  }

  /** The SHA-256 hex fingerprint of the UTF-8 bytes of {@code text}. */
  private static String sha256Hex(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16));
        sb.append(Character.forDigit((b & 0xF), 16));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required but unavailable", e);
    }
  }

  /** A single word tagged with the page (1-based) it belongs to. */
  private record PageToken(int page, String word) {}
}
