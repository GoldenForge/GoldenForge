package org.goldenforge.mixins.fixes.immersiveportal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IEWorld;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

@Mixin(McHelper.class)
public class McHelperMixin {
        /**
         * @author
         * @reason
         */
        @Overwrite
        public static <ENTITY extends Entity> List<ENTITY> getEntitiesNearby(
                Level world,
                Vec3 center,
                Class<ENTITY> entityClass,
                double range
        ) {
                return world.getEntitiesOfClass(entityClass, new AABB(center.x - range, center.y - range, center.z - range, center.x + range, center.y + range, center.z + range));
        }

        /**
         * @author
         * @reason
         */
        @Overwrite
        public static <T extends Entity> List<T> findEntitiesRough(
                Class<T> entityClass,
                Level world,
                Vec3 center,
                int radiusChunks,
                Predicate<T> predicate
        ) {
                // the minimum is 1
                if (radiusChunks <= 0) {
                        radiusChunks = 1;
                }

                if (radiusChunks > 32) {
                        radiusChunks = 32;
                }

                AABB boundingBox = new AABB(
                        center.x - radiusChunks * 16, center.y - radiusChunks * 16, center.z - radiusChunks * 16,
                        center.x + radiusChunks * 16, center.y + radiusChunks * 16, center.z + radiusChunks * 16
                );

                return world.getEntitiesOfClass(entityClass, boundingBox, predicate::test);
        }
        /**
         * @author
         * @reason
         */
        @Overwrite
        public static <T extends Entity> void foreachEntitiesByBoxApproximateRegions(
                Class<T> entityClass, Level world, AABB box, double maxEntityRadius, Consumer<T> consumer
        ) {
                int xMin = (int) Math.floor(box.minX - maxEntityRadius);
                int yMin = (int) Math.floor(box.minY - maxEntityRadius);
                int zMin = (int) Math.floor(box.minZ - maxEntityRadius);
                int xMax = (int) Math.ceil(box.maxX + maxEntityRadius);
                int yMax = (int) Math.ceil(box.maxY + maxEntityRadius);
                int zMax = (int) Math.ceil(box.maxZ + maxEntityRadius);

                AABB newBox = new AABB(xMin, yMin, zMin, xMax, yMax, zMax);
                world.getEntitiesOfClass(entityClass, newBox).forEach(consumer);
        }

        /**
         * @author
         * @reason
         */
        @Overwrite
        public static <T extends Entity, R> void traverseEntitiesByPointAndRoughRadius(
                Class<T> entityClass, Level world, Vec3 point, int roughRadius,
                Function<T, R> function
        ) {
                SectionPos sectionPos = SectionPos.of(BlockPos.containing(point));
                int roughRadiusChunks = (int) Math.ceil(roughRadius / 16.0);
                if (roughRadiusChunks == 0) {
                        roughRadiusChunks = 1;
                }
                AABB boundingBox = new AABB(
                        point.x - roughRadius, point.y - roughRadius, point.z - roughRadius,
                        point.x + roughRadius, point.y + roughRadius, point.z + roughRadius
                );
                world.getEntitiesOfClass(entityClass, boundingBox).forEach(entity -> function.apply(entity));
        }
}
