package net.shendi.umapica.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shendi.umapica.Config;
import net.shendi.umapica.UmapicaClient;
import net.shendi.umapica.hologram.HologramInstance;
import net.shendi.umapica.hologram.HologramManager;
import net.shendi.umapica.render.UmapTextureManager;
import net.shendi.umapica.umap.UmapActor;
import net.shendi.umapica.umap.UmapMeshData;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Umapica control screen with three panels:
 * <ol>
 *   <li><b>File Browser</b> – navigate folders and {@code .umap} files; click folders to enter, files to load.</li>
 *   <li><b>Loaded Holograms</b> – lists active holograms; click to select.</li>
 *   <li><b>Controls</b> – scale, render mode, origin with per-axis ±1/±10 buttons.</li>
 * </ol>
 */
public class UmapicaScreen extends Screen {

    // ── Panel metrics ─────────────────────────────────────────────────
    private static final int FILE_W = 170; // file-browser panel width
    private static final int HOLO_W = 148; // loaded-holograms panel width
    private static final int GAP    = 4;   // gap between panels
    private static final int TOP_H  = 30;  // top bar height
    private static final int ROW_H  = 16;  // list-row height
    private static final int HDR_H  = 13;  // panel header text height

    // Computed x-starts (set in init)
    private int fileX, holoX, ctrlX;

    // ── File-browser state ────────────────────────────────────────────
    /** Entries currently displayed (folders first, then .umap files). */
    private final List<FileEntry> browserEntries = new ArrayList<>();
    /** Current directory being browsed. null = root listing from umapica/ folder. */
    private File currentDir = null;
    /** Breadcrumb path shown at top of file panel. */
    private String breadcrumb = "";
    private int fileScroll = 0;

    /** Wrapper for a displayed entry in the file browser. */
    private record FileEntry(File file, String displayName, boolean isDirectory) implements Comparable<FileEntry> {
        @Override public int compareTo(FileEntry o) {
            if (isDirectory != o.isDirectory) return isDirectory ? -1 : 1;
            return displayName.compareToIgnoreCase(o.displayName);
        }
    }

    // ── Hologram-panel state ──────────────────────────────────────────
    private HologramInstance selected = null;
    private int holoScroll = 0;

    // ── Control widgets ───────────────────────────────────────────────
    private EditBox scaleBox;
    private Button  modeButton, visButton, setPlayerButton, loadMeshButton, removeButton;
    private EditBox xBox, yBox, zBox;
    private AbstractSliderButton renderDistSlider;
    private AbstractSliderButton faceLimitSlider;

    // ── Status bar ────────────────────────────────────────────────────
    private String statusMsg    = "";
    private long   statusExpiry = 0;

    public UmapicaScreen() {
        super(Component.translatable("screen.umapica.title"));
    }

    // ================================================================== //
    //  Init / layout
    // ================================================================== //

    @Override
    protected void init() {
        fileX = 4;
        holoX = fileX + FILE_W + GAP;
        ctrlX = holoX + HOLO_W + GAP;

        // ── Top bar ──────────────────────────────────────────────────── //
        addRenderableWidget(Button.builder(Component.literal("↻ Refresh"), btn -> refreshFiles())
                .bounds(fileX, 6, 80, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), btn -> onClose())
                .bounds(width - 90, 6, 82, 18).build());

        // ── Controls panel ───────────────────────────────────────────── //
        int cy = TOP_H + 2;

        // Scale
        scaleBox = new EditBox(font, ctrlX + 42, cy + 2, 68, 14, Component.literal("100.0"));
        scaleBox.setMaxLength(12);
        scaleBox.setFilter(s -> s.matches("^[0-9]*\\.?[0-9]*$") || s.isEmpty());
        addRenderableWidget(scaleBox);
        addRenderableWidget(Button.builder(Component.literal("÷2"), btn -> adjustScale(0.5))
                .bounds(ctrlX + 112, cy, 28, 18).build());
        addRenderableWidget(Button.builder(Component.literal("×2"), btn -> adjustScale(2.0))
                .bounds(ctrlX + 142, cy, 28, 18).build());
        cy += 22;

        // Mode
        modeButton = Button.builder(Component.literal("Mode"), btn -> cycleMode())
                .bounds(ctrlX, cy, 142, 18).build();
        addRenderableWidget(modeButton);
        cy += 22;

        // Visibility
        visButton = Button.builder(Component.literal("Toggle"), btn -> toggleVisible())
                .bounds(ctrlX, cy, 142, 18).build();
        addRenderableWidget(visButton);
        cy += 26; // extra gap before origin section

        // Origin – X / Y / Z, each with -10 -1 +1 +10 step buttons
        int ew = 54; // EditBox width
        int bw = 26; // step-button width

        // X
        xBox = new EditBox(font, ctrlX + 14, cy + 2, ew, 14, Component.literal("X"));
        xBox.setMaxLength(10); xBox.setFilter(s -> s.matches("^-?[0-9]*$") || s.isEmpty());
        addRenderableWidget(xBox);
        addRenderableWidget(Button.builder(Component.literal("-X10"), btn -> nudge(-10, 0, 0))
                .bounds(ctrlX + 14 + ew + 2, cy, bw + 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-X"),   btn -> nudge(-1,  0, 0))
                .bounds(ctrlX + 14 + ew + 6 + bw, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+X"),   btn -> nudge(+1,  0, 0))
                .bounds(ctrlX + 14 + ew + 8 + bw * 2, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+X10"), btn -> nudge(+10, 0, 0))
                .bounds(ctrlX + 14 + ew + 10 + bw * 3, cy, bw + 2, 18).build());
        cy += 22;

        // Y
        yBox = new EditBox(font, ctrlX + 14, cy + 2, ew, 14, Component.literal("Y"));
        yBox.setMaxLength(10); yBox.setFilter(s -> s.matches("^-?[0-9]*$") || s.isEmpty());
        addRenderableWidget(yBox);
        addRenderableWidget(Button.builder(Component.literal("-Y10"), btn -> nudge(0, -10, 0))
                .bounds(ctrlX + 14 + ew + 2, cy, bw + 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-Y"),   btn -> nudge(0, -1,  0))
                .bounds(ctrlX + 14 + ew + 6 + bw, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+Y"),   btn -> nudge(0, +1,  0))
                .bounds(ctrlX + 14 + ew + 8 + bw * 2, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+Y10"), btn -> nudge(0, +10, 0))
                .bounds(ctrlX + 14 + ew + 10 + bw * 3, cy, bw + 2, 18).build());
        cy += 22;

        // Z
        zBox = new EditBox(font, ctrlX + 14, cy + 2, ew, 14, Component.literal("Z"));
        zBox.setMaxLength(10); zBox.setFilter(s -> s.matches("^-?[0-9]*$") || s.isEmpty());
        addRenderableWidget(zBox);
        addRenderableWidget(Button.builder(Component.literal("-Z10"), btn -> nudge(0, 0, -10))
                .bounds(ctrlX + 14 + ew + 2, cy, bw + 2, 18).build());
        addRenderableWidget(Button.builder(Component.literal("-Z"),   btn -> nudge(0, 0, -1))
                .bounds(ctrlX + 14 + ew + 6 + bw, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+Z"),   btn -> nudge(0, 0, +1))
                .bounds(ctrlX + 14 + ew + 8 + bw * 2, cy, bw, 18).build());
        addRenderableWidget(Button.builder(Component.literal("+Z10"), btn -> nudge(0, 0, +10))
                .bounds(ctrlX + 14 + ew + 10 + bw * 3, cy, bw + 2, 18).build());
        cy += 22;

        setPlayerButton = Button.builder(Component.translatable("button.umapica.set_origin"),
                btn -> setOriginToPlayer())
                .bounds(ctrlX, cy, 160, 18).build();
        addRenderableWidget(setPlayerButton);
        cy += 22;

        loadMeshButton = Button.builder(Component.translatable("button.umapica.load_meshes"),
                btn -> loadMeshes())
                .bounds(ctrlX, cy, 142, 18).build();
        addRenderableWidget(loadMeshButton);
        cy += 22;

        removeButton = Button.builder(Component.translatable("button.umapica.remove"),
                btn -> removeSelected())
                .bounds(ctrlX, cy, 90, 18).build();
        addRenderableWidget(removeButton);
        cy += 26;

        // ── Render Distance slider ───────────────────────────────────── //
        renderDistSlider = new AbstractSliderButton(ctrlX, cy, 162, 18,
                Component.empty(),
                (Config.RENDER_DISTANCE.get() - 16.0) / (Config.MAX_RENDER_DISTANCE.get() - 16.0)) {
            @Override protected void updateMessage() {
                setMessage(Component.literal("Mesh distance: " + sliderToBlocks(this.value) + " blocks"));
            }
            @Override protected void applyValue() {
                Config.RENDER_DISTANCE.set(sliderToBlocks(this.value));
            }
        };
        addRenderableWidget(renderDistSlider);
        cy += 22;

        // ── Face cap slider ──────────────────────────────────────────────── //
        faceLimitSlider = new AbstractSliderButton(ctrlX, cy, 162, 18,
                Component.empty(),
                facesToSlider(Config.FACE_LIMIT.get())) {
            @Override protected void updateMessage() {
                setMessage(Component.literal(sliderToFacesLabel(this.value)));
            }
            @Override protected void applyValue() {
                Config.FACE_LIMIT.set(sliderToFaces(this.value));
            }
        };
        addRenderableWidget(faceLimitSlider);
        cy += 22;

        refreshFiles();
        updateControls();
    }

    // ================================================================== //
    //  Render
    // ================================================================== //

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // NOTE: renderBackground() is already called by renderWithTooltipAndSubtitles();
        // calling it here again would trigger "Can only blur once per frame".
        super.render(g, mx, my, pt);

        // -- Screen title --
        String titleText = selected != null
                ? "Umapica - " + selected.name
                : "Umapica - UMAP Hologram Viewer";
        g.drawCenteredString(font, titleText, width / 2, 9, 0xFFFFAA);

        int listTop = TOP_H;
        int listBot = height - 18;

        // -- File browser panel --
        g.fill(fileX, listTop, fileX + FILE_W, listBot, 0xBB000000);

        // Breadcrumb / current path
        String headerLabel = breadcrumb.isEmpty() ? "> umapica/" : "> " + breadcrumb;
        String trimmedHeader = font.width(headerLabel) > FILE_W - 6
                ? font.plainSubstrByWidth(headerLabel, FILE_W - 14) + "..." : headerLabel;
        g.drawString(font, trimmedHeader, fileX + 3, listTop + 2, 0xFFDDDD);

        int maxVisF = Math.max(1, (listBot - listTop - HDR_H) / ROW_H);
        fileScroll = Math.clamp(fileScroll, 0, Math.max(0, browserEntries.size() - maxVisF));
        g.enableScissor(fileX, listTop + HDR_H, fileX + FILE_W, listBot - 12);
        if (browserEntries.isEmpty()) {
            g.drawCenteredString(font, "Empty folder", fileX + FILE_W / 2,
                    listTop + HDR_H + 10, 0x888888);
        }
        for (int i = fileScroll; i < browserEntries.size(); i++) {
            int ry = listTop + HDR_H + (i - fileScroll) * ROW_H;
            if (ry + ROW_H > listBot - 12) break;
            FileEntry entry = browserEntries.get(i);
            boolean hov = mx >= fileX + 2 && mx <= fileX + FILE_W - 2 && my >= ry && my < ry + ROW_H;
            int bgColor = entry.isDirectory()
                    ? (hov ? 0x885588CC : 0x44223366)
                    : (hov ? 0x884488FF : 0x44224488);
            g.fill(fileX + 2, ry + 1, fileX + FILE_W - 2, ry + ROW_H - 1, bgColor);

            // Draw folder icon/prefix then the name — use fully-opaque colors (0xFF alpha)
            String prefix = entry.isDirectory() ? "[>]" : " - ";
            int labelColor = entry.isDirectory()
                    ? (hov ? 0xFFFFFF00 : 0xFFDDCC44)   // yellow tones for folders
                    : (hov ? 0xFFFFFFFF : 0xFFCCCCFF);  // white/blue for files
            // Draw prefix
            g.drawString(font, prefix, fileX + 4, ry + 4, labelColor, false);
            int prefixW = font.width(prefix) + 2;
            // Draw name, trimmed to fit
            String name = entry.displayName();
            int maxW = FILE_W - prefixW - 8;
            String trimmed = font.width(name) > maxW
                    ? font.plainSubstrByWidth(name, maxW - font.width("...")) + "..." : name;
            g.drawString(font, trimmed, fileX + 4 + prefixW, ry + 4, labelColor, false);
        }
        g.disableScissor();

        // File count at bottom
        long fileCount = browserEntries.stream().filter(e -> !e.isDirectory()).count();
        long dirCount  = browserEntries.stream().filter(FileEntry::isDirectory).count();
        String countLabel = dirCount + " folder(s), " + fileCount + " file(s)";
        g.drawString(font, countLabel, fileX + 3, listBot - 11, 0x7777AA);

        // -- Loaded holograms panel --
        g.fill(holoX, listTop, holoX + HOLO_W, listBot, 0xBB000000);
        g.drawString(font, "> Loaded Holograms", holoX + 3, listTop + 2, 0xFFDDFFDD, false);

        List<HologramInstance> holos = HologramManager.get().getAll();
        int maxVisH = Math.max(1, (listBot - listTop - HDR_H) / ROW_H);
        holoScroll = Math.clamp(holoScroll, 0, Math.max(0, holos.size() - maxVisH));
        g.enableScissor(holoX, listTop + HDR_H, holoX + HOLO_W, listBot);
        if (holos.isEmpty()) {
            g.drawCenteredString(font, "No holograms loaded", holoX + HOLO_W / 2,
                    listTop + HDR_H + 10, 0xFF888888);
        }
        for (int i = holoScroll; i < holos.size(); i++) {
            HologramInstance h = holos.get(i);
            int ry = listTop + HDR_H + (i - holoScroll) * ROW_H;
            if (ry + ROW_H > listBot) break;
            boolean isSel = (h == selected);
            g.fill(holoX + 2, ry + 1, holoX + HOLO_W - 2, ry + ROW_H - 1,
                   isSel ? 0x88AAAA22 : (h.visible ? 0x44336633 : 0x33222222));
            String raw = (h.visible ? "[ON] " : "[__] ") + h.name;
            String lbl = font.width(raw) > HOLO_W - 10 ? font.plainSubstrByWidth(raw, HOLO_W - 14) + "..." : raw;
            g.drawString(font, lbl, holoX + 5, ry + 6, isSel ? 0xFFFFFF88 : 0xFFBBBBBB, false);
        }
        g.disableScissor();

        // -- Controls panel title + static labels --
        g.drawString(font, "> Controls", ctrlX + 3, listTop + 2, 0xFFDDBB);

        int cy = TOP_H + 2;
        g.drawString(font, "Scale:", ctrlX, cy + 4, 0xFFDDBB);
        cy += 22; // scale row
        cy += 22; // mode button
        cy += 26; // vis button + gap
        g.drawString(font, "Origin (blocks):", ctrlX + 2, cy, 0xDDDDDD);
        cy += 13; // origin header
        g.drawString(font, "X", ctrlX + 4, cy + 5, 0xFF5555);
        cy += 22;
        g.drawString(font, "Y", ctrlX + 4, cy + 5, 0x55FF55);
        cy += 22;
        g.drawString(font, "Z", ctrlX + 4, cy + 5, 0x5588FF);

        // (slider is self-labelled with distance value)

        // ── Status bar ────────────────────────────────────────────────
        if (!statusMsg.isEmpty() && System.currentTimeMillis() < statusExpiry) {
            g.drawCenteredString(font, statusMsg, width / 2, height - 12, 0xFFFF88);
        } else { statusMsg = ""; }
    }

    // ================================================================== //
    //  Mouse / Scroll
    // ================================================================== //

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
        double mx = event.x(), my = event.y();
        int listTop = TOP_H + HDR_H;
        int listBot = height - 18;

        // File browser – click to enter folder or load file
        if (mx >= fileX && mx <= fileX + FILE_W && my >= listTop && my <= listBot - 12) {
            int row = (int) ((my - listTop) / ROW_H) + fileScroll;
            if (row >= 0 && row < browserEntries.size()) {
                FileEntry entry = browserEntries.get(row);
                if (entry.isDirectory()) {
                    navigateTo(entry.file());
                } else {
                    loadFile(entry.file());
                }
                return true;
            }
        }
        // Hologram list – click to select
        if (mx >= holoX && mx <= holoX + HOLO_W && my >= listTop && my <= listBot) {
            List<HologramInstance> holos = HologramManager.get().getAll();
            int row = (int) ((my - listTop) / ROW_H) + holoScroll;
            if (row >= 0 && row < holos.size()) { selected = holos.get(row); updateControls(); return true; }
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int step = (int) -sy;
        int listTop = TOP_H, listBot = height - 18;
        if (my >= listTop && my <= listBot) {
            if (mx >= fileX && mx <= fileX + FILE_W) { fileScroll += step; return true; }
            if (mx >= holoX && mx <= holoX + HOLO_W) { holoScroll += step; return true; }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ================================================================== //
    //  Actions
    // ================================================================== //

    /** Maps slider [0,1] to an integer block distance in [16, maxRenderDistance]. */
    private static int sliderToBlocks(double value) {
        int max = Config.MAX_RENDER_DISTANCE.get();
        return Math.clamp((int) Math.round(value * (max - 16) + 16), 16, max);
    }

    /** Maps slider [0,1] to face cap: 1.0 → unlimited (0), otherwise 1000..2_000_000. */
    private static int sliderToFaces(double value) {
        if (value > 0.999) return 0;
        return Math.max(1000, (int) (value * 2_000_000));
    }

    private static String sliderToFacesLabel(double value) {
        int faces = sliderToFaces(value);
        return faces == 0 ? "Face cap: Unlimited" : "Face cap: " + faces;
    }

    /** Maps a stored face-cap int back to a slider [0,1] position. */
    private static double facesToSlider(int faces) {
        return faces <= 0 ? 1.0 : Math.max(0.0, Math.min(0.999, faces / 2_000_000.0));
    }

    private void refreshFiles() {
        navigateTo(currentDir);
    }

    /** Navigate the file browser to a given directory (null = umapica/ root). */
    private void navigateTo(File dir) {
        browserEntries.clear();
        fileScroll = 0;

        // Resolve the actual directory
        File root = UmapicaClient.getUmapDir();
        if (dir == null) dir = root;
        if (dir == null || !dir.isDirectory()) {
            currentDir = null;
            breadcrumb = "";
            return;
        }
        currentDir = dir;

        // Build breadcrumb showing path relative to umapica/ root
        if (root != null) {
            String rootPath = root.getAbsolutePath();
            String dirPath  = dir.getAbsolutePath();
            if (dirPath.startsWith(rootPath)) {
                String rel = dirPath.substring(rootPath.length());
                if (rel.startsWith(File.separator)) rel = rel.substring(1);
                breadcrumb = rel.isEmpty() ? "umapica/" : "umapica/" + rel.replace("\\", "/") + "/";
            } else {
                breadcrumb = dir.getName() + "/";
            }
        } else {
            breadcrumb = dir.getName() + "/";
        }

        // Add ".." back-navigation entry (unless we're at the root)
        if (root != null && !dir.getAbsolutePath().equals(root.getAbsolutePath())) {
            File parent = dir.getParentFile();
            if (parent != null) {
                browserEntries.add(new FileEntry(parent, "..", true));
            }
        }

        // List contents: folders + .umap files
        File[] children = dir.listFiles();
        if (children != null) {
            List<FileEntry> entries = new ArrayList<>();
            for (File f : children) {
                if (f.isDirectory()) {
                    entries.add(new FileEntry(f, f.getName(), true));
                } else if (f.getName().toLowerCase().endsWith(".umap")
                        || f.getName().toLowerCase().endsWith(".obj")
                        || f.getName().toLowerCase().endsWith(".glb")
                        || f.getName().toLowerCase().endsWith(".gltf")
                        || f.getName().toLowerCase().endsWith(".zip")
                        || f.getName().toLowerCase().endsWith(".bsp")
                        || f.getName().toLowerCase().endsWith(".vpk")
                        || f.getName().toLowerCase().endsWith(".bfres.zs")) {
                    entries.add(new FileEntry(f, f.getName(), false));
                }
            }
            Collections.sort(entries);
            browserEntries.addAll(entries);
        }

        net.shendi.umapica.Umapica.LOGGER.info("[Umapica] navigateTo '{}': {} entries",
                breadcrumb, browserEntries.size());
        setStatus(browserEntries.isEmpty()
                ? "Empty folder: " + breadcrumb
                : breadcrumb + " – " + browserEntries.size() + " item(s)");
    }

    private void loadFile(File umap) {
        setStatus("Loading " + umap.getName() + "…");
        String nameLower = umap.getName().toLowerCase(java.util.Locale.ROOT);
        boolean isModel  = nameLower.endsWith(".obj") || nameLower.endsWith(".glb") || nameLower.endsWith(".gltf");
        boolean isDisney = nameLower.endsWith(".zip");
        boolean isBsp    = nameLower.endsWith(".bsp");
        boolean isVpk    = nameLower.endsWith(".vpk");
        boolean isBfres  = nameLower.endsWith(".bfres.zs");
        java.util.concurrent.CompletableFuture<net.shendi.umapica.hologram.HologramInstance> future =
                isModel   ? HologramManager.get().loadModelAsync(umap)
                : isDisney ? HologramManager.get().loadDisneyAsync(umap)
                : isBsp   ? HologramManager.get().loadSourceBspAsync(umap)
                : isVpk   ? HologramManager.get().loadSource2VpkAsync(umap)
                : isBfres  ? HologramManager.get().loadBfresAsync(umap)
                           : HologramManager.get().loadAsync(umap, false);
        future.thenAccept(h -> {
            if (h != null) {
                if (minecraft != null && minecraft.player != null) h.origin = minecraft.player.blockPosition();
                selected = h;
                setStatus("Loaded " + h.name + " – " + h.umap.actors.size() + " actors");
                if (minecraft != null) minecraft.execute(this::updateControls);
            } else { setStatus("Load failed – see game log for details"); }
        });
    }

    /** Commits any typed X/Y/Z, then offsets origin by the given delta. */
    private void nudge(int dx, int dy, int dz) {
        if (selected == null) return;
        commitCoordBoxes();
        selected.origin = selected.origin.offset(dx, dy, dz);
        refreshCoordBoxes();
    }

    private void commitCoordBoxes() {
        if (selected == null || xBox == null) return;
        try { selected.origin = new BlockPos(Integer.parseInt(xBox.getValue()),
                                              Integer.parseInt(yBox.getValue()),
                                              Integer.parseInt(zBox.getValue()));
        } catch (NumberFormatException ignored) {}
    }

    private void refreshCoordBoxes() {
        if (xBox == null) return;
        if (selected == null) { xBox.setValue(""); yBox.setValue(""); zBox.setValue(""); return; }
        xBox.setValue(String.valueOf(selected.origin.getX()));
        yBox.setValue(String.valueOf(selected.origin.getY()));
        zBox.setValue(String.valueOf(selected.origin.getZ()));
    }

    private void cycleMode() {
        if (selected == null) return;
        selected.renderMode = selected.renderMode.next();
        updateControls();
    }

    private void toggleVisible() {
        if (selected == null) return;
        selected.visible = !selected.visible;
        updateControls();
    }

    private void setOriginToPlayer() {
        if (selected == null || minecraft == null || minecraft.player == null) return;
        selected.origin = minecraft.player.blockPosition();
        refreshCoordBoxes();
        setStatus("Origin → " + selected.origin.getX() + " " + selected.origin.getY() + " " + selected.origin.getZ());
    }

    private void loadMeshes() {
        if (selected == null) return;
        HologramInstance h = selected;
        setStatus("Loading meshes for " + h.name + "…");
        Thread t = new Thread(() -> {
            h.umap.loadMeshData();
            setStatus("Meshes loaded – resolving textures\u2026");
            File[] searchDirs = buildTextureDirs(h);
            if (minecraft != null) {
                minecraft.execute(() -> {
                    int resolved = 0;
                    for (UmapActor actor : h.umap.actors) {
                        if (actor.meshData == null || actor.meshData.sections == null) continue;
                        for (UmapMeshData.Section sec : actor.meshData.sections) {
                            UmapTextureManager.resolveTexture(sec, searchDirs);
                            if (sec.textureResourceId != null) resolved++;
                        }
                    }
                    setStatus("Textures: " + resolved + " resolved for " + h.name);
                });
            }
        }, "Umapica-Mesh");
        t.setDaemon(true); t.start();
    }

    /** Builds the list of directories to search for texture .uasset files. */
    private File[] buildTextureDirs(HologramInstance h) {
        List<File> dirs = new ArrayList<>();
        addIfDir(dirs, h.umap.file.getParentFile());

        // Walk up from the .umap looking for Content/, CookedPC*, or Cooked roots –
        // same heuristic that UmapPackage.resolveAssetFile uses for .upk lookup.
        File cur = h.umap.file.getParentFile();
        while (cur != null) {
            for (String sub : new String[]{"Content", "CookedPC", "CookedPCConsole", "Cooked"}) {
                addIfDir(dirs, new File(cur, sub));
            }
            cur = cur.getParentFile();
        }

        // Always include the umapica/ root and its immediate children so
        // a CookedPC folder dropped directly there is found without any config.
        File umapRoot = UmapicaClient.getUmapDir();
        if (umapRoot != null) {
            addIfDir(dirs, umapRoot);
            File[] topLevel = umapRoot.listFiles(File::isDirectory);
            if (topLevel != null) {
                for (File d : topLevel) addIfDir(dirs, d);
            }
        }

        // Include the source .umap file itself – Hat in Time textures are baked in
        if (h.umap.file != null && h.umap.file.isFile()) dirs.add(h.umap.file);
        return dirs.toArray(new File[0]);
    }

    private static void addIfDir(List<File> list, File dir) {
        if (dir != null && dir.isDirectory() && !list.contains(dir)) list.add(dir);
    }

    private void removeSelected() {
        if (selected == null) return;
        HologramManager.get().remove(selected.id);
        selected = null;
        updateControls();
        setStatus("Hologram removed");
    }

    private void adjustScale(double factor) {
        if (selected == null || scaleBox == null) return;
        try {
            double v = Math.max(0.01, Math.min(100_000, Double.parseDouble(scaleBox.getValue()) * factor));
            selected.scale = v; scaleBox.setValue(String.format("%.2f", v));
        } catch (NumberFormatException ignored) {}
    }

    private void updateControls() {
        boolean en = (selected != null);
        if (scaleBox        != null) { scaleBox.setEditable(en);     if (en) scaleBox.setValue(String.format("%.2f", selected.scale)); }
        if (modeButton      != null) { modeButton.active      = en;  if (en) modeButton.setMessage(Component.literal("Mode: " + selected.renderMode.displayName + " ▶")); }
        if (visButton       != null) { visButton.active       = en;  if (en) visButton.setMessage(Component.literal(selected.visible ? "▼ Hide" : "▲ Show")); }
        if (setPlayerButton != null) setPlayerButton.active   = en;
        if (loadMeshButton  != null) loadMeshButton.active    = en;
        if (removeButton    != null) removeButton.active      = en;
        if (xBox != null) { xBox.setEditable(en); yBox.setEditable(en); zBox.setEditable(en); }
        refreshCoordBoxes();
    }

    private void setStatus(String msg) { statusMsg = msg; statusExpiry = System.currentTimeMillis() + 5_000; }

    // ================================================================== //
    //  Overrides
    // ================================================================== //

    @Override
    public void onClose() {
        commitCoordBoxes();
        if (selected != null && scaleBox != null) {
            try { selected.scale = Double.parseDouble(scaleBox.getValue()); } catch (NumberFormatException ignored) {}
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() { return false; }
}
