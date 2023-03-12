package gg.pufferfish.pufferfish.util;

import com.destroystokyo.paper.util.misc.PlayerAreaMap;
import com.destroystokyo.paper.util.misc.PooledLinkedHashSets;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerPlayer;

public final class AsyncPlayerAreaMap extends PlayerAreaMap {

    public AsyncPlayerAreaMap() {
        super();
        this.areaMap = new Long2ObjectOpenHashMapWrapper<>(new ConcurrentHashMap<>(1024, 0.7f));
    }

    public AsyncPlayerAreaMap(final PooledLinkedHashSets<ServerPlayer> pooledHashSets) {
        super(pooledHashSets);
        this.areaMap = new Long2ObjectOpenHashMapWrapper<>(new ConcurrentHashMap<>(1024, 0.7f));
    }

    public AsyncPlayerAreaMap(final PooledLinkedHashSets<ServerPlayer> pooledHashSets, final ChangeCallback<ServerPlayer> addCallback,
                              final ChangeCallback<ServerPlayer> removeCallback) {
        this(pooledHashSets, addCallback, removeCallback, null);
    }

    public AsyncPlayerAreaMap(final PooledLinkedHashSets<ServerPlayer> pooledHashSets, final ChangeCallback<ServerPlayer> addCallback,
                              final ChangeCallback<ServerPlayer> removeCallback, final ChangeSourceCallback<ServerPlayer> changeSourceCallback) {
        super(pooledHashSets, addCallback, removeCallback, changeSourceCallback);
        this.areaMap = new Long2ObjectOpenHashMapWrapper<>(new ConcurrentHashMap<>(1024, 0.7f));
    }

}
