package ca.spottedleaf.dataconverter.minecraft.hooks;

import ca.spottedleaf.dataconverter.converters.datatypes.DataHook;
import ca.spottedleaf.dataconverter.types.MapType;
import ca.spottedleaf.dataconverter.util.NamespaceUtil;

public class DataHookEnforceNamespacedID implements DataHook<MapType<String>, MapType<String>> {

    private final String path;

    public DataHookEnforceNamespacedID() {
        this("id");
    }

    public DataHookEnforceNamespacedID(final String path) {
        this.path = path;
    }

    @Override
    public MapType<String> preHook(final MapType<String> data, final long fromVersion, final long toVersion) {
        final String id = data.getString(this.path);
        if (id != null) {
            final String replace = NamespaceUtil.correctNamespaceOrNull(id);
            if (replace != null) {
                data.setString(this.path, replace);
            }
        }
        return null;
    }

    @Override
    public MapType<String> postHook(final MapType<String> data, final long fromVersion, final long toVersion) {
        return null;
    }
}
