package net.shendi.umapica.umap;

/** Unreal Engine FRotator – pitch/yaw/roll in degrees. */
public record FRotator(double pitch, double yaw, double roll) {

    public static final FRotator ZERO = new FRotator(0, 0, 0);

    /**
     * Converts this rotator to a unit quaternion using Unreal Engine's
     * left-handed coordinate convention.
     *
     * <p>Matches UE4 {@code FRotator::Quaternion()} exactly:
     * <pre>
     *   X =  CR*SP*SY - SR*CP*CY
     *   Y = -CR*SP*CY - SR*CP*SY
     *   Z =  CR*CP*SY - SR*SP*CY
     *   W =  CR*CP*CY + SR*SP*SY
     * </pre>
     * where CR/SR = cos/sin(Roll/2), CP/SP = cos/sin(Pitch/2), CY/SY = cos/sin(Yaw/2).
     */
    public FQuat toQuat() {
        double cy = Math.cos(Math.toRadians(yaw   * 0.5));
        double sy = Math.sin(Math.toRadians(yaw   * 0.5));
        double cp = Math.cos(Math.toRadians(pitch * 0.5));
        double sp = Math.sin(Math.toRadians(pitch * 0.5));
        double cr = Math.cos(Math.toRadians(roll  * 0.5));
        double sr = Math.sin(Math.toRadians(roll  * 0.5));
        return new FQuat(
             cr * sp * sy - sr * cp * cy,   // X
            -(cr * sp * cy + sr * cp * sy),  // Y
             cr * cp * sy - sr * sp * cy,    // Z
             cr * cp * cy + sr * sp * sy     // W
        );
    }

    /** Convert to a unit direction vector (ignores roll). */
    public FVector toDirection() {
        double pitchRad = Math.toRadians(pitch);
        double yawRad   = Math.toRadians(yaw);
        return new FVector(
            Math.cos(pitchRad) * Math.cos(yawRad),
            Math.cos(pitchRad) * Math.sin(yawRad),
            Math.sin(pitchRad)
        );
    }

    @Override
    public String toString() {
        return String.format("FRotator(P=%.1f Y=%.1f R=%.1f)", pitch, yaw, roll);
    }
}
