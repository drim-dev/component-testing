package dev.drim.relay.id;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mints opaque, time-ordered, URL-safe string ids. The spec treats ids as opaque non-empty strings
 * (02-api.md §0); time-ordering keeps newest-first pagination stable.
 *
 * <p>A factory carries a generator id so a second factory (the naive test host) never collides with
 * ids minted by the suite's seeding factory — the leading-timestamp + generator-id layout makes the
 * lexicographic order of the encoded string match creation order.
 */
public final class IdFactory {
  /** The Crockford Base32 alphabet (URL-safe, unambiguous). */
  private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  private static final SecureRandom RANDOM = new SecureRandom();

  private final short generatorId;
  private final AtomicInteger seq;

  public IdFactory(short generatorId) {
    this.generatorId = generatorId;
    this.seq = new AtomicInteger(RANDOM.nextInt());
  }

  /**
   * Returns a new id: 48-bit millisecond timestamp + 16-bit generator id + 32-bit monotonic
   * sequence, Crockford-Base32 encoded.
   */
  public String create() {
    byte[] buf = new byte[12];
    long ms = System.currentTimeMillis();
    buf[0] = (byte) (ms >> 40);
    buf[1] = (byte) (ms >> 32);
    buf[2] = (byte) (ms >> 24);
    buf[3] = (byte) (ms >> 16);
    buf[4] = (byte) (ms >> 8);
    buf[5] = (byte) ms;
    buf[6] = (byte) (generatorId >> 8);
    buf[7] = (byte) generatorId;
    int s = seq.incrementAndGet();
    buf[8] = (byte) (s >> 24);
    buf[9] = (byte) (s >> 16);
    buf[10] = (byte) (s >> 8);
    buf[11] = (byte) s;
    return encode(buf);
  }

  private static String encode(byte[] b) {
    StringBuilder out = new StringBuilder(b.length * 8 / 5 + 1);
    long buffer = 0;
    int bits = 0;
    for (byte c : b) {
      buffer = (buffer << 8) | (c & 0xff);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append(CROCKFORD.charAt((int) ((buffer >> bits) & 0x1f)));
      }
    }
    if (bits > 0) {
      out.append(CROCKFORD.charAt((int) ((buffer << (5 - bits)) & 0x1f)));
    }
    return out.toString();
  }
}
