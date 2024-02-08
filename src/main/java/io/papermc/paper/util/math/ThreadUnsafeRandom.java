package io.papermc.paper.util.math;

import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;

@DefaultQualifier(NonNull.class)
public final class ThreadUnsafeRandom extends LegacyRandomSource {

    // See javadoc and internal comments for java.util.Random where these values come from, how they are used, and the author for them.
    private static final long multiplier = 0x5DEECE66DL;
    private static final long addend = 0xBL;
    private static final long mask = (1L << 48) - 1;

    private static long initialScramble(long seed) {
        return (seed ^ multiplier) & mask;
    }

    private long seed;

    public ThreadUnsafeRandom(long seed) {
        super(seed);
    }

    @Override
    public RandomSource fork() {
        return new ThreadUnsafeRandom(this.nextLong());
    }

    @Override
    public PositionalRandomFactory forkPositional() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSeed(long seed) {
        // note: called by Random constructor
        this.seed = initialScramble(seed);
    }

    @Override
    public int next(int bits) {
        // avoid the expensive CAS logic used by superclass
        return (int) (((this.seed = this.seed * multiplier + addend) & mask) >>> (48 - bits));
    }

    // Taken from
    // https://lemire.me/blog/2016/06/27/a-fast-alternative-to-the-modulo-reduction/
    // https://github.com/lemire/Code-used-on-Daniel-Lemire-s-blog/blob/master/2016/06/25/fastrange.c
    // Original license is public domain
    public static int fastRandomBounded(final long randomInteger, final long limit) {
        // randomInteger must be [0, pow(2, 32))
        // limit must be [0, pow(2, 32))
        return (int)((randomInteger * limit) >>> 32);
    }

    @Override
    public int nextInt(int bound) {
        // yes this breaks random's spec
        // however there's nothing that uses this class that relies on it
        return fastRandomBounded(this.next(32) & 0xFFFFFFFFL, bound);
    }
}
