package androidx.media3.extractor.mkv;

// A WebM WebVTT block (D_WEBVTT/*, also what FFmpeg writes into .mkv) is "cue id\ncue settings\n
// cue text"; the WebVTT sample Media3 builds wants the settings on the timing line instead.
final class WebmVttBlock {

  private WebmVttBlock() {}

  /**
   * Rewrites, in place, a block that follows a prefix ending in the cue timing line and "\n":
   * drops the id line and moves a non-empty settings line onto the end of the timing line.
   *
   * @param start where the block starts, just after the prefix
   * @param limit where the block ends
   * @return the new limit; unchanged when the block isn't laid out that way
   */
  static int toCue(byte[] data, int start, int limit) {
    if (start < 1 || data[start - 1] != '\n') {
      return limit;
    }
    final int idEnd = indexOfNewline(data, start, limit);
    if (idEnd < 0) {
      return limit;
    }
    final int settingsStart = idEnd + 1;
    final int settingsEnd = indexOfNewline(data, settingsStart, limit);
    if (settingsEnd < 0) {
      return limit;
    }
    int settingsLength = settingsEnd - settingsStart;
    if (settingsLength > 0 && data[settingsEnd - 1] == '\r') {
      settingsLength--;
    }
    // over the prefix's own line break; everything only moves left
    int out = start - 1;
    if (settingsLength > 0) {
      data[out++] = ' ';
      System.arraycopy(data, settingsStart, data, out, settingsLength);
      out += settingsLength;
    }
    data[out++] = '\n';
    final int textStart = settingsEnd + 1;
    final int textLength = limit - textStart;
    System.arraycopy(data, textStart, data, out, textLength);
    return out + textLength;
  }

  private static int indexOfNewline(byte[] data, int from, int limit) {
    for (int i = from; i < limit; i++) {
      if (data[i] == '\n') {
        return i;
      }
    }
    return -1;
  }
}
