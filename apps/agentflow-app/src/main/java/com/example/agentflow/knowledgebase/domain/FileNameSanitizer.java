package com.example.agentflow.knowledgebase.domain;

/**
 * Client-filename sanitiser, {@code S10-03}. The client-supplied filename is <b>never</b> trusted:
 * it can carry path separators, {@code ..} traversal, control characters or a prompt-injected
 * payload. This reduces it to a bare, safe base name before it is stored on the {@code documents}
 * row or fed to any other system.
 *
 * <p>The sanitiser only strips and rejects; it does not guess a real name (invariant 1 / the {@code
 * S10-04} note that the filename can never become an object path — the server-generated {@code
 * DocumentObjectKey} owns the object path, never this value). It rejects a blank, control-character
 * or traversal-bearing input and returns the last path segment of the rest.
 */
public final class FileNameSanitizer {

  private FileNameSanitizer() {}

  /**
   * Reduce a client-supplied filename to a safe base name.
   *
   * <p>Rejects a {@code null}/blank input, any {@code NUL} byte, any C0 control character, and any
   * name that is traversal ({@code ..}), a dot ({@code .}) or empty once sanitised; otherwise
   * strips any directory component (forward or back slash) and returns the trailing segment.
   *
   * @param clientFileName the raw client filename
   * @return the sanitised, path-free base name
   * @throws IllegalArgumentException if the filename is blank, control-bearing or traversal-bearing
   */
  public static String sanitize(String clientFileName) {
    if (clientFileName == null || clientFileName.isBlank()) {
      throw new IllegalArgumentException("filename must not be blank");
    }
    for (int i = 0; i < clientFileName.length(); i++) {
      char c = clientFileName.charAt(i);
      if (c == 0 || c < 0x20) {
        throw new IllegalArgumentException("filename must not contain control characters");
      }
    }
    // Reduce any path to its final segment, accepting both forward and back separators.
    int lastSep = -1;
    for (int i = 0; i < clientFileName.length(); i++) {
      char c = clientFileName.charAt(i);
      if (c == '/' || c == '\\') {
        lastSep = i;
      }
    }
    String base = lastSep >= 0 ? clientFileName.substring(lastSep + 1) : clientFileName;
    base = base.trim();
    if (base.isEmpty() || base.equals(".") || base.equals("..")) {
      throw new IllegalArgumentException("filename must be a real file name");
    }
    return base;
  }
}
