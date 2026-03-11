package net.shendi.umapica.hologram;

/** The visual representation mode used when rendering a hologram. */
public enum RenderMode {

    /** Each actor's bounding volume is outlined as a coloured line-box. Fast, no occlusion. */
    WIREFRAME("Wireframe"),

    /**
     * Each actor's bounding volume is filled with a translucent, tinted block quad.
     * Colour is derived from the material/mesh name hint.
     */
    VOXEL("Voxel"),

    /**
     * The actual triangle geometry from the referenced .uasset is rendered as a
     * translucent ghost overlay.  Falls back to WIREFRAME when mesh data is unavailable.
     */
    GHOST_MESH("Ghost Mesh"),

    /**
     * Like GHOST_MESH but the triangles are rendered with the actual material textures
     * loaded from the .uasset files.  Falls back to GHOST_MESH when textures are unavailable.
     */
    TEXTURED_MESH("Textured Mesh");

    public final String displayName;

    RenderMode(String displayName) {
        this.displayName = displayName;
    }

    /** Cycles to the next mode in the declaration order. */
    public RenderMode next() {
        RenderMode[] vals = values();
        return vals[(ordinal() + 1) % vals.length];
    }
}
