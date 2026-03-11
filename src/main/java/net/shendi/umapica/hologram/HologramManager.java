package net.shendi.umapica.hologram;

import net.shendi.umapica.Umapica;
import net.shendi.umapica.umap.UmapPackage;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
}
