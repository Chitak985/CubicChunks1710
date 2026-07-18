package com.cardinalstar.cubicchunks.world.convert;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.ParametersAreNonnullByDefault;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.storage.RegionFileCache;

import org.spongepowered.asm.util.Files;

import com.cardinalstar.cubicchunks.api.ICube;
import com.cardinalstar.cubicchunks.api.world.storage.ICubicStorage;
import com.cardinalstar.cubicchunks.network.CCPacketBuffer;
import com.cardinalstar.cubicchunks.server.chunkio.RegionCubeStorage;
import com.cardinalstar.cubicchunks.util.CubePos;
import com.cardinalstar.cubicchunks.util.Coords;
import com.cardinalstar.cubicchunks.world.convert.adapter.AdapterDiscovery;
import com.cardinalstar.cubicchunks.world.convert.adapter.CubeData;
import com.cardinalstar.cubicchunks.world.convert.adapter.SectionAdapter;
import com.cardinalstar.cubicchunks.world.core.ServerHeightMap;

import io.netty.buffer.Unpooled;

/**
 * Converts a CubicChunks world into vanilla Anvil format ({@code region/*.mca}).
 *
 * <p>Only cubes with Y in [0, 15] (blocks 0–255) are written; cubes outside that
 * range are silently skipped since vanilla cannot represent them.
 *
 * <p>Runs offline — no world or server instance is required.
 */
@ParametersAreNonnullByDefault
public final class CCToVanillaConverter implements IWorldConverter {

    /** Vanilla height range in cube coordinates. */
    private static final int VANILLA_CUBE_MIN_Y = 0;
    private static final int VANILLA_CUBE_MAX_Y = 15;

    /** CC column Level keys that are explicitly handled; all others are preserved as-is. */
    private static final Set<String> CC_COLUMN_LEVEL_KEYS = new HashSet<>(Arrays.asList(
        "v", "x", "z", "InhabitedTime", "Biomes", "OpacityIndex"
    ));

    private final SectionAdapter writeAdapter;

    public CCToVanillaConverter(SectionAdapter writeAdapter) {
        this.writeAdapter = writeAdapter;
    }

    @Override
    public void convert(File dimensionRoot, boolean isOverworld, ConversionProgress progress, AtomicBoolean cancelSignal) throws IOException {
        Path worldPath = dimensionRoot.toPath();

        LinkedHashSet<ChunkCoordIntPair> columns = new LinkedHashSet<>();
        try (ICubicStorage storage = new RegionCubeStorage(worldPath)) {
            storage.forEachColumn(columns::add);
            columns.removeIf(pos -> {
                try {
                    return !storage.columnExists(pos);
                } catch (IOException e) {
                    return false;
                }
            });
            int total = columns.size();
            progress.update(0, total, "Starting...");

            int done = 0;
            for (ChunkCoordIntPair colPos : columns) {
                convertColumn(dimensionRoot, colPos, storage);
                done++;

                progress.update(done, total, colPos.chunkXPos + "," + colPos.chunkZPos);
                if (cancelSignal.get()) return;
            }
        } finally {
            RegionFileCache.clearRegionFileReferences();
        }

        Files.deleteRecursively(worldPath.resolve("region2d").toFile());
        Files.deleteRecursively(worldPath.resolve("region3d").toFile());

        progress.update(progress.getCompleted(), progress.getTotal(), "Done.");
    }

    private void convertColumn(File dimensionRoot, ChunkCoordIntPair colPos, ICubicStorage storage) throws IOException {
        NBTTagCompound columnNbt = storage.readColumn(colPos);
        if (columnNbt == null) return;

        NBTTagCompound columnLevel = columnNbt.getCompoundTag("Level");
        int chunkX = colPos.chunkXPos;
        int chunkZ = colPos.chunkZPos;

        NBTTagList sections   = new NBTTagList();
        NBTTagList entities   = new NBTTagList();
        NBTTagList tileEntities = new NBTTagList();
        NBTTagList tileTicks  = new NBTTagList();

        for (int cubeY = VANILLA_CUBE_MIN_Y; cubeY <= VANILLA_CUBE_MAX_Y; cubeY++) {
            NBTTagCompound cubeNbt = storage.readCube(new CubePos(chunkX, cubeY, chunkZ));
            if (cubeNbt == null) continue;

            NBTTagCompound cubeLevel = cubeNbt.getCompoundTag("Level");

            appendCubeSection(cubeLevel, cubeY, sections);
            mergeList(cubeLevel.getTagList("Entities", 10), entities);
            mergeList(cubeLevel.getTagList("TileEntities", 10), tileEntities);
            mergeList(cubeLevel.getTagList("TileTicks", 10), tileTicks);
        }

        NBTTagCompound level = buildVanillaLevel(columnLevel, chunkX, chunkZ, sections,
            entities, tileEntities, tileTicks);

        NBTTagCompound root = new NBTTagCompound();
        root.setTag("Level", level);

        try (DataOutputStream out = RegionFileCache.getChunkOutputStream(dimensionRoot, chunkX, chunkZ)) {
            CompressedStreamTools.write(root, out);
        }
    }

    private void appendCubeSection(NBTTagCompound cubeLevel, int cubeY, NBTTagList sections) {
        NBTTagList cubeSections = cubeLevel.getTagList("Sections", 10);
        if (cubeSections.tagCount() == 0) return;

        NBTTagCompound cubeSection = cubeSections.getCompoundTagAt(0);
        if (!cubeSection.hasKey("Blocks")) return;

        CubeData cubeData = new CubeData();
        AdapterDiscovery.detect(cubeSection).readSectionData(cubeSection, cubeData);

        NBTTagCompound section = new NBTTagCompound();
        section.setByte("Y", (byte) (cubeY & 0xFF));
        copyUnknownTags(cubeSection, section, AdapterDiscovery.SECTION_MANAGED_KEYS);
        writeAdapter.writeSectionData(cubeData, section);

        sections.appendTag(section);
    }

    private NBTTagCompound buildVanillaLevel(NBTTagCompound columnLevel, int chunkX, int chunkZ,
                                              NBTTagList sections, NBTTagList entities,
                                              NBTTagList tileEntities, NBTTagList tileTicks) {
        NBTTagCompound level = new NBTTagCompound();

        level.setByte("V", (byte) 1);
        level.setInteger("xPos", chunkX);
        level.setInteger("zPos", chunkZ);
        level.setLong("LastUpdate", 0L);
        level.setBoolean("TerrainPopulated", true);
        level.setBoolean("LightPopulated", false);
        level.setLong("InhabitedTime", columnLevel.getLong("InhabitedTime"));
        level.setByteArray("Biomes", columnLevel.getByteArray("Biomes"));
        level.setIntArray("HeightMap", buildHeightMap(columnLevel.getByteArray("OpacityIndex")));
        level.setTag("Sections", sections);
        level.setTag("Entities", entities);
        level.setTag("TileEntities", tileEntities);
        level.setTag("TileTicks", tileTicks);

        copyUnknownTags(columnLevel, level, CC_COLUMN_LEVEL_KEYS);

        return level;
    }

    private static void copyUnknownTags(NBTTagCompound src, NBTTagCompound dst, Set<String> exclude) {
        for (String key : src.func_150296_c()) {
            if (!exclude.contains(key)) {
                dst.setTag(key, src.getTag(key).copy());
            }
        }
    }

    /**
     * Reconstructs a vanilla-style HeightMap (first-air-block Y per XZ) from a
     * CC {@code OpacityIndex} byte array. Returns an all-zeros map on empty input.
     */
    private int[] buildHeightMap(byte[] opacityIndexData) {
        int[] heightMap = new int[ICube.SIZE * ICube.SIZE];

        if (opacityIndexData.length == 0) return heightMap;

        ServerHeightMap hmap = new ServerHeightMap(new int[ICube.SIZE * ICube.SIZE]);
        hmap.readData(new CCPacketBuffer(Unpooled.wrappedBuffer(opacityIndexData)));

        for (int lx = 0; lx < ICube.SIZE; lx++) {
            for (int lz = 0; lz < ICube.SIZE; lz++) {
                int topBlock = hmap.getTopBlockY(lx, lz);
                // vanilla HeightMap = Y of first air block above surface
                if (topBlock != Coords.NO_HEIGHT && topBlock >= 0) {
                    heightMap[lz * ICube.SIZE + lx] = Math.min(255, topBlock + 1);
                }
            }
        }

        return heightMap;
    }

    private void mergeList(NBTTagList src, NBTTagList dst) {
        for (int i = 0; i < src.tagCount(); i++) {
            dst.appendTag(src.getCompoundTagAt(i).copy());
        }
    }
}
