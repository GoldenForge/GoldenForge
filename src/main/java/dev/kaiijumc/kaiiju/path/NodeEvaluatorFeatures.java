package dev.kaiijumc.kaiiju.path;

import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.SwimNodeEvaluator;

public record NodeEvaluatorFeatures(NodeEvaluatorType type,
                                    boolean canPassDoors,
                                    boolean canFloat,
                                    boolean canOpenDoors,
                                    boolean allowBreaching) {
    public static NodeEvaluatorFeatures fromNodeEvaluator(NodeEvaluator nodeEvaluator) {
        NodeEvaluatorType type = NodeEvaluatorType.fromNodeEvaluator(nodeEvaluator);
        boolean canPassDoors = nodeEvaluator.canPassDoors();
        boolean canFloat = nodeEvaluator.canFloat();
        boolean canOpenDoors = nodeEvaluator.canOpenDoors();
        boolean allowBreaching = nodeEvaluator instanceof SwimNodeEvaluator swimNodeEvaluator && swimNodeEvaluator.allowBreaching;
        return new NodeEvaluatorFeatures(type, canPassDoors, canFloat, canOpenDoors, allowBreaching);
    }
}
