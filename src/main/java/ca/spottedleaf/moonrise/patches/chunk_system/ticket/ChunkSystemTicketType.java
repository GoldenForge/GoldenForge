package ca.spottedleaf.moonrise.patches.chunk_system.ticket;

import net.minecraft.server.level.*;

import java.util.*;

public interface ChunkSystemTicketType<T> {

    public static final long COUNTER_TYPE_FORCED                   = 0L;
    // used only by neoforge
    public static final long COUNTER_TYPER_NATURAL_SPAWNING_FORCED = 1L;

    public static <T> TicketType create(final String name, final Comparator<T> identifierComparator) {
        final TicketType type = TicketType.create(name, Long::compareTo);
        ((ChunkSystemTicketType) type).moonrise$setIdentifierComparator(identifierComparator);
        return type;
    }

    public static <T> TicketType create(final String name, final Comparator<T> identifierComparator, final int timeout) {
        // note: cannot persist unless registered
        final TicketType ret = TicketType.create(name, Long::compareTo, timeout);

        ((ChunkSystemTicketType<T>)(Object)ret).moonrise$setIdentifierComparator(identifierComparator);

        return ret;
    }

    public long moonrise$getId();

    public Comparator<T> moonrise$getIdentifierComparator();

    public void moonrise$setIdentifierComparator(final Comparator<T> comparator);

    public long[] moonrise$getCounterTypes();

    public void moonrise$setTimeout(final long to);
}
