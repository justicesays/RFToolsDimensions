package mcjty.rftoolsdim.dimensions.world;

import net.minecraft.world.biome.Biome;
import net.minecraft.world.gen.layer.GenLayer;
import net.minecraft.world.gen.layer.IntCache;

import java.util.List;

public class GenLayerCheckerboard extends GenLayer {

    private final GenericBiomeProvider chunkManager;

    public GenLayerCheckerboard(GenericBiomeProvider chunkManager, long seed, GenLayer parent) {
        super(seed);
        this.parent = parent;
        this.chunkManager = chunkManager;
    }

    @Override
    public int[] getInts(int x, int z, int width, int length) {
//        return parent.getInts(x, z, width, length);
        List<Biome> biomes = chunkManager.getDimensionInformation().getBiomes();
        boolean b = ((x >> 3) & 1) == ((z >> 3) & 1);
        int[] aint = IntCache.getIntCache(width * length);
        for (int i = 0; i < width * length; ++i) {
            if (b) {
                aint[i] = Biome.getIdForBiome(biomes.get(0));
            } else {
                aint[i] = Biome.getIdForBiome(biomes.get(1));
            }
        }
        return aint;
    }
}
