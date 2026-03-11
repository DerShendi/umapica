package net.minecraft.gizmos;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public class Gizmos {
    static final ThreadLocal<@Nullable GizmoCollector> collector = new ThreadLocal<>();

    private Gizmos() {
    }

    public static Gizmos.TemporaryCollection withCollector(GizmoCollector p_collector) {
        Gizmos.TemporaryCollection gizmos$temporarycollection = new Gizmos.TemporaryCollection();
        collector.set(p_collector);
        return gizmos$temporarycollection;
    }

    public static GizmoProperties addGizmo(Gizmo gizmo) {
        GizmoCollector gizmocollector = collector.get();
        if (gizmocollector == null) {
            throw new IllegalStateException("Gizmos cannot be created here! No GizmoCollector has been registered.");
        } else {
            return gizmocollector.add(gizmo);
        }
    }

    public static GizmoProperties cuboid(AABB aabb, GizmoStyle style) {
        return cuboid(aabb, style, false);
    }

    public static GizmoProperties cuboid(AABB aabb, GizmoStyle style, boolean colorCornerStroke) {
        return addGizmo(new CuboidGizmo(aabb, style, colorCornerStroke));
    }

    public static GizmoProperties cuboid(BlockPos pos, GizmoStyle style) {
        return cuboid(new AABB(pos), style);
    }

    public static GizmoProperties cuboid(BlockPos pos, float inflate, GizmoStyle style) {
        return cuboid(new AABB(pos).inflate(inflate), style);
    }

    public static GizmoProperties circle(Vec3 pos, float radius, GizmoStyle style) {
        return addGizmo(new CircleGizmo(pos, radius, style));
    }

    public static GizmoProperties line(Vec3 start, Vec3 end, int color) {
        return addGizmo(new LineGizmo(start, end, color, 3.0F));
    }

    public static GizmoProperties line(Vec3 start, Vec3 end, int color, float width) {
        return addGizmo(new LineGizmo(start, end, color, width));
    }

    public static GizmoProperties arrow(Vec3 start, Vec3 end, int color) {
        return addGizmo(new ArrowGizmo(start, end, color, 2.5F));
    }

    public static GizmoProperties arrow(Vec3 start, Vec3 end, int color, float width) {
        return addGizmo(new ArrowGizmo(start, end, color, width));
    }

    public static GizmoProperties rect(Vec3 corner1, Vec3 corner2, Direction face, GizmoStyle style) {
        return addGizmo(RectGizmo.fromCuboidFace(corner1, corner2, face, style));
    }

    public static GizmoProperties rect(Vec3 a, Vec3 b, Vec3 c, Vec3 d, GizmoStyle style) {
        return addGizmo(new RectGizmo(a, b, c, d, style));
    }

    public static GizmoProperties point(Vec3 pos, int color, float size) {
        return addGizmo(new PointGizmo(pos, color, size));
    }

    public static GizmoProperties billboardTextOverBlock(String text, BlockPos pos, int line, int color, float scale) {
        double d0 = 1.3;
        double d1 = 0.2;
        GizmoProperties gizmoproperties = billboardText(
            text,
            Vec3.atLowerCornerWithOffset(pos, 0.5, 1.3 + line * 0.2, 0.5),
            TextGizmo.Style.forColorAndCentered(color).withScale(scale)
        );
        gizmoproperties.setAlwaysOnTop();
        return gizmoproperties;
    }

    public static GizmoProperties billboardTextOverMob(Entity entity, int line, String text, int color, float scale) {
        double d0 = 2.4;
        double d1 = 0.25;
        double d2 = entity.getBlockX() + 0.5;
        double d3 = entity.getY() + 2.4 + line * 0.25;
        double d4 = entity.getBlockZ() + 0.5;
        float f = 0.5F;
        GizmoProperties gizmoproperties = billboardText(
            text, new Vec3(d2, d3, d4), TextGizmo.Style.forColor(color).withScale(scale).withLeftAlignment(0.5F)
        );
        gizmoproperties.setAlwaysOnTop();
        return gizmoproperties;
    }

    public static GizmoProperties billboardText(String text, Vec3 pos, TextGizmo.Style style) {
        return addGizmo(new TextGizmo(pos, text, style));
    }

    public static class TemporaryCollection implements AutoCloseable {
        private final @Nullable GizmoCollector old = Gizmos.collector.get();
        private boolean closed;

        TemporaryCollection() {
        }

        @Override
        public void close() {
            if (!this.closed) {
                this.closed = true;
                Gizmos.collector.set(this.old);
            }
        }
    }
}
