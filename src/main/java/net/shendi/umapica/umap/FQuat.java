package net.shendi.umapica.umap;

/** Unreal Engine FQuat – unit quaternion (x, y, z, w). */
public record FQuat(double x, double y, double z, double w) {

    public static final FQuat IDENTITY = new FQuat(0, 0, 0, 1);

    /** Convert to Euler angles (pitch/yaw/roll) in degrees. */
    public FRotator toRotator() {
        // atan2-based conversion matches Unreal's FQuat::Rotator()
        double sinPcos = 2.0 * (w * x + y * z);
        double cosPcos = 1.0 - 2.0 * (x * x + y * y);
        double pitch = Math.toDegrees(Math.atan2(sinPcos, cosPcos));

        double sinP = 2.0 * (w * y - z * x);
        double yaw;
        if (Math.abs(sinP) >= 1) {
            yaw = Math.toDegrees(Math.copySign(Math.PI / 2, sinP));
        } else {
            yaw = Math.toDegrees(Math.asin(sinP));
        }

        double sinRcos = 2.0 * (w * z + x * y);
        double cosRcos = 1.0 - 2.0 * (y * y + z * z);
        double roll = Math.toDegrees(Math.atan2(sinRcos, cosRcos));

        return new FRotator(pitch, yaw, roll);
    }

    /**
     * Rotates vector (vx, vy, vz) by this quaternion.
     * Uses the Rodrigues formula:  t = 2*(q.xyz × v),  v' = v + w*t + q.xyz × t
     *
     * @return new double[]{rx, ry, rz}
     */
    public double[] rotateVector(double vx, double vy, double vz) {
        // t = 2 * (q.xyz × v)
        double tx = 2.0 * (y * vz - z * vy);
        double ty = 2.0 * (z * vx - x * vz);
        double tz = 2.0 * (x * vy - y * vx);
        // v' = v + w*t + (q.xyz × t)
        return new double[]{
            vx + w * tx + (y * tz - z * ty),
            vy + w * ty + (z * tx - x * tz),
            vz + w * tz + (x * ty - y * tx)
        };
    }

    @Override
    public String toString() {
        return String.format("FQuat(%.3f, %.3f, %.3f, %.3f)", x, y, z, w);
    }
}
