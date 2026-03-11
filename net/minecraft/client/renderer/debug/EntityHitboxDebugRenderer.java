package net.minecraft.client.renderer.debug;

import net.minecraft.SharedConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.debug.DebugValueAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jspecify.annotations.Nullable;

@OnlyIn(Dist.CLIENT)
public class EntityHitboxDebugRenderer implements DebugRenderer.SimpleDebugRenderer {
    final Minecraft minecraft;

    public EntityHitboxDebugRenderer(Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    @Override
    public void emitGizmos(double p_454845_, double p_455240_, double p_456173_, DebugValueAccess p_456179_, Frustum p_455245_, float p_455267_) {
        if (this.minecraft.level != null) {
            for (Entity entity : this.minecraft.level.entitiesForRendering()) {
                if (!entity.isInvisible()
                    && p_455245_.isVisible(entity.getBoundingBox())
                    && (entity != this.minecraft.getCameraEntity() || this.minecraft.options.getCameraType() != CameraType.FIRST_PERSON)) {
                    this.showHitboxes(entity, p_455267_, false);
                    if (SharedConstants.DEBUG_SHOW_LOCAL_SERVER_ENTITY_HIT_BOXES) {
                        Entity entity1 = this.getServerEntity(entity);
                        if (entity1 != null) {
                            this.showHitboxes(entity, p_455267_, true);
                        } else {
                            Gizmos.billboardText(
                                "Missing Server Entity",
                                entity.getPosition(p_455267_).add(0.0, entity.getBoundingBox().getYsize() + 1.5, 0.0),
                                TextGizmo.Style.forColorAndCentered(-65536)
                            );
                        }
                    }
                }
            }
        }
    }

    private @Nullable Entity getServerEntity(Entity entity) {
        IntegratedServer integratedserver = this.minecraft.getSingleplayerServer();
        if (integratedserver != null) {
            ServerLevel serverlevel = integratedserver.getLevel(entity.level().dimension());
            if (serverlevel != null) {
                return serverlevel.getEntity(entity.getId());
            }
        }

        return null;
    }

    private void showHitboxes(Entity p_entity, float partialTick, boolean isServerEntity) {
        Vec3 vec3 = p_entity.position();
        Vec3 vec31 = p_entity.getPosition(partialTick);
        Vec3 vec32 = vec31.subtract(vec3);
        int i = isServerEntity ? -16711936 : -1;
        Gizmos.cuboid(p_entity.getBoundingBox().move(vec32), GizmoStyle.stroke(i));
        Gizmos.point(vec31, i, 2.0F);
        Entity entity = p_entity.getVehicle();
        if (entity != null) {
            float f = Math.min(entity.getBbWidth(), p_entity.getBbWidth()) / 2.0F;
            float f1 = 0.0625F;
            Vec3 vec33 = entity.getPassengerRidingPosition(p_entity).add(vec32);
            Gizmos.cuboid(new AABB(vec33.x - f, vec33.y, vec33.z - f, vec33.x + f, vec33.y + 0.0625, vec33.z + f), GizmoStyle.stroke(-256));
        }

        if (p_entity instanceof LivingEntity) {
            AABB aabb = p_entity.getBoundingBox().move(vec32);
            float f2 = 0.01F;
            Gizmos.cuboid(
                new AABB(aabb.minX, aabb.minY + p_entity.getEyeHeight() - 0.01F, aabb.minZ, aabb.maxX, aabb.minY + p_entity.getEyeHeight() + 0.01F, aabb.maxZ),
                GizmoStyle.stroke(-65536)
            );
        }

        if (p_entity instanceof EnderDragon enderdragon) {
            for (EnderDragonPart enderdragonpart : enderdragon.getSubEntities()) {
                Vec3 vec34 = enderdragonpart.position();
                Vec3 vec35 = enderdragonpart.getPosition(partialTick);
                Vec3 vec36 = vec35.subtract(vec34);
                Gizmos.cuboid(enderdragonpart.getBoundingBox().move(vec36), GizmoStyle.stroke(ARGB.colorFromFloat(1.0F, 0.25F, 1.0F, 0.0F)));
            }
        }

        Vec3 vec37 = vec31.add(0.0, p_entity.getEyeHeight(), 0.0);
        Vec3 vec38 = p_entity.getViewVector(partialTick);
        Gizmos.arrow(vec37, vec37.add(vec38.scale(2.0)), -16776961);
        if (isServerEntity) {
            Vec3 vec39 = p_entity.getDeltaMovement();
            Gizmos.arrow(vec31, vec31.add(vec39), -256);
        }
    }
}
