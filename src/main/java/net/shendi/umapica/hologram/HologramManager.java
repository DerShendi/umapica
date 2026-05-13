package net.shendi.umapica.hologram;

import net.shendi.umapica.Umapica;
import net.shendi.umapica.umap.BlenderModelLoader;
import net.shendi.umapica.umap.DisneyWorldLoader;
import net.shendi.umapica.umap.GoldSrcBspLoader;
import net.shendi.umapica.umap.Source2VpkLoader;
import net.shendi.umapica.umap.SourceBspLoader;
import net.shendi.umapica.umap.TomodachiLifeLoader;
import net.shendi.umapica.umap.UmapActor;
import net.shendi.umapica.umap.UmapPackage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side singleton that owns all loaded {@link HologramInstance}s.
 *
 * <p>Hologram loading is done asynchronously on a background thread to avoid
 * freezing the client while large .umap files are being parsed.
 */
public class HologramManager {

    private static final HologramManager INSTANCE = new HologramManager();

    public static HologramManager get() { return INSTANCE; }

    private final List<HologramInstance> holograms = new ArrayList<>();
    private final AtomicInteger          nextId     = new AtomicInteger(0);

    /**
     * Limits concurrent Disney/Meridian ZIP loads to 1 at a time.
     * Large worlds (e.g. st_junkyard) can consume hundreds of MB while parsing;
     * running several in parallel would exhaust heap and crash the client.
     * This mirrors the single-threaded pattern used by {@code loadMeshes()}.
     */
    private final Semaphore disneyLoadSlots = new Semaphore(1);

    /** Listeners notified when the hologram list changes. */
    private final List<Runnable> changeListeners = new ArrayList<>();

    private HologramManager() {}

    // ------------------------------------------------------------------ //
    //  Loading
    // ------------------------------------------------------------------ //

    /**
     * Asynchronously loads a .umap file and adds the resulting hologram.
     *
     * @param file       the .umap file to load
     * @param loadMeshes if {@code true}, also attempts to load referenced .uasset mesh data
     *                   (needed for GHOST_MESH render mode)
     * @return a future that completes with the new {@link HologramInstance},
     *         or {@code null} if loading failed
     */
    public CompletableFuture<@Nullable HologramInstance> loadAsync(File file, boolean loadMeshes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UmapPackage pkg = new UmapPackage(file);
                pkg.load();
                if (loadMeshes) pkg.loadMeshData();

                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] Hologram {} loaded: {}", id, h);
                return h;
            } catch (IOException | IllegalArgumentException e) {
                Umapica.LOGGER.error("[Umapica] Failed to load {}: {}", file.getName(), e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Asynchronously loads a Blender-exported model file ({@code .obj}, {@code .glb}, {@code .gltf})
     * and adds it as a single-actor hologram.
     *
     * @param file the model file to load
     * @return a future completing with the new {@link HologramInstance}, or {@code null} on failure
     */
    public CompletableFuture<@Nullable HologramInstance> loadModelAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UmapPackage pkg = BlenderModelLoader.load(file);

                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] Blender hologram {} loaded: {}", id, h);
                return h;
            } catch (IOException | IllegalArgumentException e) {
                Umapica.LOGGER.error("[Umapica] Failed to load Blender model {}: {}", file.getName(), e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Asynchronously loads a Tomodachi Life BFRES NX file ({@code .bfres.zs})
     * and adds it as a single-actor hologram.
     *
     * @param file the .bfres.zs file to load
     * @return a future completing with the new {@link HologramInstance}, or {@code null} on failure
     */
    public CompletableFuture<@Nullable HologramInstance> loadBfresAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                TomodachiLifeLoader loader = new TomodachiLifeLoader();
                net.shendi.umapica.umap.UmapMeshData mesh = loader.load(file.toPath());

                UmapPackage pkg = new UmapPackage(file);
                UmapActor actor = new UmapActor("StaticMeshActor", file.getName());
                actor.meshData = mesh;
                pkg.actors.add(actor);

                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] BFRES hologram {} loaded: {}", id, h);
                return h;
            } catch (IOException | IllegalArgumentException e) {
                Umapica.LOGGER.error("[Umapica] Failed to load BFRES {}: {}", file.getName(), e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Asynchronously loads a Meridian/Disney world ZIP archive ({@code .zip})
     * and adds it as a single-actor hologram.
     *
     * @param file the .zip world file to load
     * @return a future completing with the new {@link HologramInstance}, or {@code null} on failure
     */
    public CompletableFuture<@Nullable HologramInstance> loadDisneyAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            // Throttle to 1 concurrent Disney load — large worlds can exhaust heap
            // if several parse simultaneously (same pattern as loadMeshes daemon thread).
            try {
                disneyLoadSlots.acquire();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Umapica.LOGGER.warn("[Umapica] Disney load interrupted while waiting for slot: {}", file.getName());
                return null;
            }
            try {
                UmapPackage pkg = DisneyWorldLoader.load(file);

                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] Disney hologram {} loaded: {}", id, h);
                return h;
            } catch (java.io.IOException | IllegalArgumentException e) {
                Umapica.LOGGER.error("[Umapica] Failed to load Disney world {}: {}", file.getName(), e.getMessage(), e);
                return null;
            } finally {
                disneyLoadSlots.release();
            }
        });
    }

    /**
     * Asynchronously loads a game map BSP file ({@code .bsp}) and adds it as a hologram.
     * Auto-detects the format:
     * <ul>
     *   <li><b>VBSP</b> magic → Source Engine BSP (CS:Source, HL2, TF2, Portal, L4D, Payday…)</li>
     *   <li><b>version=30</b> as first int → GoldSrc BSP (CS 1.6, CS:CZ, HL1 and mods)</li>
     * </ul>
     * CS2 maps (.vpk Source 2 format) are handled by {@link #loadSource2VpkAsync(File)}.
     *
     * @param file the .bsp map file to load
     * @return a future completing with the new {@link HologramInstance}, or {@code null} on failure
     */
    public CompletableFuture<@Nullable HologramInstance> loadSourceBspAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Detect BSP format from first 4 bytes
                UmapPackage pkg;
                byte[] magic = new byte[4];
                try (java.io.RandomAccessFile probe = new java.io.RandomAccessFile(file, "r")) {
                    probe.readFully(magic);
                }
                boolean isVbsp = magic[0]=='V' && magic[1]=='B' && magic[2]=='S' && magic[3]=='P';
                int versionInt = java.nio.ByteBuffer.wrap(magic).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt();

                if (isVbsp) {
                    pkg = SourceBspLoader.load(file);
                } else if (versionInt == GoldSrcBspLoader.VERSION) {
                    pkg = GoldSrcBspLoader.load(file);
                } else {
                    throw new java.io.IOException(
                        file.getName() + ": unrecognised BSP format (magic=" +
                        new String(magic, java.nio.charset.StandardCharsets.US_ASCII) +
                        " versionInt=" + versionInt + ")."
                        + " Note: CS2/Source-2 maps (.vpk) should be loaded via the VPK loader, not BSP.");
                }

                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] BSP hologram {} loaded: {}", id, h);
                return h;
            } catch (Exception e) {
                Umapica.LOGGER.error("[Umapica] Failed to load BSP {}: {}", file.getName(), e.getMessage(), e);
                return null;
            }
        });
    }

    /**
     * Asynchronously loads a CS2 / Source 2 map file ({@code .vpk}) and adds it as a hologram.
     * The map geometry is extracted from the embedded navigation mesh ({@code maps/<name>.nav}).
     *
     * @param file the {@code .vpk} map file to load
     * @return a future completing with the new {@link HologramInstance}, or {@code null} on failure
     */
    public CompletableFuture<@Nullable HologramInstance> loadSource2VpkAsync(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                UmapPackage pkg = Source2VpkLoader.load(file);
                int id = nextId.getAndIncrement();
                HologramInstance h = new HologramInstance(id, pkg);
                h.scale = net.shendi.umapica.Config.DEFAULT_SCALE.get();
                synchronized (holograms) { holograms.add(h); }
                notifyChange();
                Umapica.LOGGER.info("[Umapica] CS2 VPK hologram {} loaded: {}", id, h);
                return h;
            } catch (Exception e) {
                Umapica.LOGGER.error("[Umapica] Failed to load CS2 VPK {}: {}",
                        file.getName(), e.getMessage(), e);
                return null;
            }
        });
    }

    // ------------------------------------------------------------------ //
    //  Management
    // ------------------------------------------------------------------ //

    /**
     * Removes the hologram with the given id.
     * @return {@code true} if it was found and removed.
     */
    public boolean remove(int id) {
        synchronized (holograms) {
            boolean removed = holograms.removeIf(h -> h.id == id);
            if (removed) notifyChange();
            return removed;
        }
    }

    /** Removes all loaded holograms. */
    public void clear() {
        synchronized (holograms) {
            holograms.clear();
            notifyChange();
        }
    }

    /** Returns an unmodifiable snapshot of the current hologram list. */
    public List<HologramInstance> getAll() {
        synchronized (holograms) {
            return Collections.unmodifiableList(new ArrayList<>(holograms));
        }
    }

    /** Returns the hologram with the given id, or {@code null}. */
    public @Nullable HologramInstance get(int id) {
        synchronized (holograms) {
            return holograms.stream().filter(h -> h.id == id).findFirst().orElse(null);
        }
    }

    public int count() {
        synchronized (holograms) { return holograms.size(); }
    }

    // ------------------------------------------------------------------ //
    //  Change notification (for GUI updates)
    // ------------------------------------------------------------------ //

    public void addChangeListener(Runnable listener) {
        synchronized (changeListeners) { changeListeners.add(listener); }
    }

    public void removeChangeListener(Runnable listener) {
        synchronized (changeListeners) { changeListeners.remove(listener); }
    }

    private void notifyChange() {
        synchronized (changeListeners) {
            changeListeners.forEach(Runnable::run);
        }
    }

    // ------------------------------------------------------------------ //
    //  Per-actor hide / undo
    // ------------------------------------------------------------------ //

    private record HiddenEntry(HologramInstance hologram, UmapActor actor, int triIndex) {}
    private final ArrayDeque<HiddenEntry> hiddenHistory = new ArrayDeque<>();
    private static final int HISTORY_MAX = 64;

    /**
     * Hides a specific triangle ({@code triIndex >= 0}) or the whole actor ({@code triIndex < 0})
     * and records the action in the undo history.
     */
    public void hideActor(HologramInstance h, UmapActor actor, int triIndex) {
        if (triIndex >= 0) {
            if (actor.hiddenTriangles == null) actor.hiddenTriangles = new java.util.BitSet();
            actor.hiddenTriangles.set(triIndex);
        } else {
            actor.hidden = true;
        }
        if (hiddenHistory.size() >= HISTORY_MAX) hiddenHistory.pollLast();
        hiddenHistory.push(new HiddenEntry(h, actor, triIndex));
    }

    /**
     * Restores the most recently hidden triangle or actor.
     * @return the actor that was modified, or {@code null} if history is empty.
     */
    public @Nullable UmapActor undoHide() {
        HiddenEntry e = hiddenHistory.poll();
        if (e == null) return null;
        if (e.triIndex() >= 0) {
            if (e.actor().hiddenTriangles != null) e.actor().hiddenTriangles.clear(e.triIndex());
        } else {
            e.actor().hidden = false;
        }
        return e.actor();
    }
}
