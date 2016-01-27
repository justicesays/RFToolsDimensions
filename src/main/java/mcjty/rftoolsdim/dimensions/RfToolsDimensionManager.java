package mcjty.rftoolsdim.dimensions;

import mcjty.lib.varia.Logging;
import mcjty.rftoolsdim.config.DimletRules;
import mcjty.rftoolsdim.config.PowerConfiguration;
import mcjty.rftoolsdim.dimensions.description.DimensionDescriptor;
import mcjty.rftoolsdim.dimensions.world.GenericWorldProvider;
import mcjty.rftoolsdim.network.PacketRegisterDimensions;
import mcjty.rftoolsdim.network.PacketSyncDimensionInfo;
import mcjty.rftoolsdim.network.PacketSyncRules;
import mcjty.rftoolsdim.network.RFToolsDimMessages;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.BlockPos;
import net.minecraft.util.ClassInheritanceMultiMap;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.Constants;

import java.util.*;

public class RfToolsDimensionManager extends WorldSavedData {
    public static final String DIMMANAGER_NAME = "RFToolsDimensionManager";
    private static RfToolsDimensionManager instance = null;

    private final Map<Integer, DimensionDescriptor> dimensions = new HashMap<Integer, DimensionDescriptor>();
    private final Map<DimensionDescriptor, Integer> dimensionToID = new HashMap<DimensionDescriptor, Integer>();
    private final Map<Integer, DimensionInformation> dimensionInformation = new HashMap<Integer, DimensionInformation>();

    private final Set<Integer> reclaimedIds = new HashSet<Integer>();

    public void syncFromServer(Map<Integer, DimensionDescriptor> dims, Map<Integer, DimensionInformation> dimInfo) {
        Logging.log("RfToolsDimensionManager.syncFromServer");
        if (dims.isEmpty() || dimInfo.isEmpty()) {
            Logging.log("Dimension information from server is empty.");
        }

        for (Map.Entry<Integer, DimensionDescriptor> entry : dims.entrySet()) {
            int id = entry.getKey();
            DimensionDescriptor descriptor = entry.getValue();
            if (dimensions.containsKey(id)) {
                dimensionToID.remove(dimensions.get(id));
            }
            dimensions.put(id, descriptor);
            dimensionToID.put(descriptor, id);
        }

        for (Map.Entry<Integer, DimensionInformation> entry : dimInfo.entrySet()) {
            int id = entry.getKey();
            DimensionInformation info = entry.getValue();
            dimensionInformation.put(id, info);
        }
    }

    public RfToolsDimensionManager(String identifier) {
        super(identifier);
    }

    public static void clearInstance() {
        if (instance != null) {
            instance.dimensions.clear();
            instance.dimensionToID.clear();
            instance.dimensionInformation.clear();
            instance.reclaimedIds.clear();
            instance = null;
        }
    }

    public static void cleanupDimensionInformation() {
        if (instance != null) {
            Logging.log("Cleaning up RFTools dimensions");
            unregisterDimensions();
            instance.getDimensions().clear();
            instance.dimensionToID.clear();
            instance.dimensionInformation.clear();
            instance.reclaimedIds.clear();
            instance = null;
        }
    }

    public static void unregisterDimensions() {
        for (Map.Entry<Integer, DimensionDescriptor> me : instance.getDimensions().entrySet()) {
            int id = me.getKey();
            if (DimensionManager.isDimensionRegistered(id)) {
                Logging.log("    Unregister dimension: " + id);
                try {
                    DimensionManager.unregisterDimension(id);
                } catch (Exception e) {
                    // We ignore this error.
                    Logging.log("        Could not unregister dimension: " + id);
                }
                try {
                    DimensionManager.unregisterProviderType(id);
                } catch (Exception e) {
                    // We ignore this error.
                    Logging.log("        Could not unregister provider: " + id);
                }
            } else {
                Logging.log("    Already unregistered! Dimension: " + id);
            }
        }
    }

    public void save(World world) {
        world.getMapStorage().setData(DIMMANAGER_NAME, this);
        markDirty();

        syncDimInfoToClients(world);
    }

    public void reclaimId(int id) {
        reclaimedIds.add(id);
    }

    /**
     * Freeze a dimension: avoid ticking all tile entities and remove all
     * active entities (they are still there but will not do anything).
     * Entities that are within range of a player having a PFG will be kept
     * active (but not tile entities).
     */
    public static void freezeDimension(World world) {
        // First find all players that have a valid PFG.
        List<BlockPos> pfgList = new ArrayList<>();
        int radius = PowerConfiguration.phasedFieldGeneratorRange;
        if (radius > 0) {
            for (Object ent : world.playerEntities) {
                EntityPlayer player = (EntityPlayer) ent;
                // Check if this player has a valid PFG but don't consume energy.
                int cost = 0;
                if (PowerConfiguration.dimensionDifficulty != -1) {
                    RfToolsDimensionManager dimensionManager = RfToolsDimensionManager.getDimensionManager(world);
                    DimensionInformation information = dimensionManager.getDimensionInformation(world.provider.getDimensionId());
                    cost = information.getActualRfCost();
                    if (cost == 0) {
                        DimensionDescriptor descriptor = dimensionManager.getDimensionDescriptor(world.provider.getDimensionId());
                        cost = descriptor.getRfMaintainCost();
                    }
                }
                if (checkValidPhasedFieldGenerator(player, false, cost)) {
                    pfgList.add(new BlockPos((int) player.posX, (int) player.posY, (int) player.posZ));
                }
            }
        }

        // If there are players with a valid PFG then we check if there are entities we want to keep.
        List tokeep = new ArrayList();
        tokeep.addAll(world.playerEntities);    // We want to keep all players for sure.
        // Add all entities that are within range of a PFG.
        for (BlockPos coordinate : pfgList) {
            getEntitiesInSphere(world, coordinate, radius, tokeep);
        }

        world.loadedEntityList.clear();
        world.loadedEntityList.addAll(tokeep);

        world.loadedTileEntityList.clear();
    }

    private static void getEntitiesInSphere(World world, BlockPos c, float radius, List tokeep) {
        int i = MathHelper.floor_double((c.getX() - radius) / 16.0D);
        int j = MathHelper.floor_double((c.getX() + 1 + radius) / 16.0D);
        int k = MathHelper.floor_double((c.getZ() - radius) / 16.0D);
        int l = MathHelper.floor_double((c.getZ() + 1 + radius) / 16.0D);

        for (int i1 = i; i1 <= j; ++i1) {
            for (int j1 = k; j1 <= l; ++j1) {
                if (world.getChunkProvider().chunkExists(i1, j1)) {
                    Chunk chunk = world.getChunkFromChunkCoords(i1, j1);
                    getEntitiesInSphere(chunk, c, radius, tokeep);
                }
            }
        }
    }

    private static void getEntitiesInSphere(Chunk chunk, BlockPos c, float radius, List entities) {
        float squaredRange = radius * radius;
        int i = MathHelper.floor_double((c.getY() - radius) / 16.0D);
        int j = MathHelper.floor_double((c.getY() + 1 + radius) / 16.0D);
        i = MathHelper.clamp_int(i, 0, chunk.getEntityLists().length - 1);
        j = MathHelper.clamp_int(j, 0, chunk.getEntityLists().length - 1);

        for (int k = i; k <= j; ++k) {
            ClassInheritanceMultiMap<Entity> entityList = chunk.getEntityLists()[k];
            for (Entity entity : entityList) {
                if (!(entity instanceof EntityPlayer)) {
                    float sqdist = squaredDistance(c, new BlockPos((int) entity.posX, (int) entity.posY, (int) entity.posZ));
                    if (sqdist < squaredRange) {
                        entities.add(entity);
                        break;
                    }
                }
            }
        }
    }

    public static float squaredDistance(BlockPos p1, BlockPos c) {
        return ((c.getX() - p1.getX()) * (c.getX() - p1.getX()) + (c.getY() - p1.getY()) * (c.getY() - p1.getY()) + (c.getZ() - p1.getZ()) * (c.getZ() - p1.getZ()));
    }



    public static void unfreezeDimension(World world) {
        WorldServer worldServer = (WorldServer) world;
        for (Object chunk : worldServer.theChunkProviderServer.loadedChunks) {
            Chunk c = (Chunk) chunk;
            unfreezeChunk(c);
        }
    }

    public static void unfreezeChunk(Chunk chunk) {
        chunk.setChunkLoaded(true);
        chunk.getWorld().addTileEntities(chunk.getTileEntityMap().values());
        for (ClassInheritanceMultiMap<Entity> entityList : chunk.getEntityLists()) {
            chunk.getWorld().loadedEntityList.addAll(entityList);
        }
    }

    public static boolean checkValidPhasedFieldGenerator(EntityPlayer player, boolean consume, int tickCost) {
//        InventoryPlayer inventory = player.inventory;
//        for (int i = 0 ; i < inventory.getHotbarSize() ; i++) {
//            ItemStack slot = inventory.getStackInSlot(i);
//            if (slot != null && slot.getItem() == DimletSetup.phasedFieldGeneratorItem) {
//                PhasedFieldGeneratorItem pfg = (PhasedFieldGeneratorItem) slot.getItem();
//                int energyStored = pfg.getEnergyStored(slot);
//                int toConsume;
//                if (GeneralConfiguration.enableDynamicPhaseCost) {
//                    toConsume = (int) (DimensionTickEvent.MAXTICKS * tickCost * GeneralConfiguration.dynamicPhaseCostAmount);
//                } else {
//                    toConsume = DimensionTickEvent.MAXTICKS * DimletConfiguration.PHASEDFIELD_CONSUMEPERTICK;
//                }
//                if (energyStored >= toConsume) {
//                    if (consume) {
//                        pfg.extractEnergy(slot, toConsume, false);
//                    }
//                    return true;
//                }
//            }
//        }
        //@todo
        return false;
    }

    /**
     * Sync dimlet rules to the client
     */
    public void syncDimletRules(EntityPlayer player) {
        if (!player.getEntityWorld().isRemote) {
            // Send over dimlet configuration to the client so that the client can check that the id's match.
            Logging.log("Send dimlet rules to the client");
            RFToolsDimMessages.INSTANCE.sendTo(new PacketSyncRules(DimletRules.getRules()), (EntityPlayerMP) player);
        }
    }

    public void syncDimInfoToClients(World world) {
        if (!world.isRemote) {
            // Sync to clients.
            Logging.log("Sync dimension info to clients!");
            RFToolsDimMessages.INSTANCE.sendToAll(new PacketSyncDimensionInfo(dimensions, dimensionInformation));
        }
    }

    public Map<Integer, DimensionDescriptor> getDimensions() {
        return dimensions;
    }

    public void registerDimensions() {
        Logging.log("Registering RFTools dimensions");
        for (Map.Entry<Integer, DimensionDescriptor> me : dimensions.entrySet()) {
            int id = me.getKey();
            Logging.log("    Dimension: " + id);
            registerDimensionToServerAndClient(id);
        }
    }

    private void registerDimensionToServerAndClient(int id) {
        if (!DimensionManager.isDimensionRegistered(id)) {
            DimensionManager.registerProviderType(id, GenericWorldProvider.class, false);
            DimensionManager.registerDimension(id, id);
        }
        RFToolsDimMessages.INSTANCE.sendToAll(new PacketRegisterDimensions(id));
    }

    public static RfToolsDimensionManager getDimensionManager(World world) {
        if (instance != null) {
            return instance;
        }
        instance = (RfToolsDimensionManager) world.getMapStorage().loadData(RfToolsDimensionManager.class, DIMMANAGER_NAME);
        if (instance == null) {
            instance = new RfToolsDimensionManager(DIMMANAGER_NAME);
        }
        return instance;
    }

    public DimensionDescriptor getDimensionDescriptor(int id) {
        return dimensions.get(id);
    }

    public Integer getDimensionID(DimensionDescriptor descriptor) {
        return dimensionToID.get(descriptor);
    }

    public DimensionInformation getDimensionInformation(int id) {
        return dimensionInformation.get(id);
    }

    /**
     * Get a world for a dimension, possibly loading it from the configuration manager.
     */
    public static World getWorldForDimension(int id) {
        World w = DimensionManager.getWorld(id);
        if (w == null) {
            w = MinecraftServer.getServer().getConfigurationManager().getServerInstance().worldServerForDimension(id);
        }
        return w;
    }

    public void removeDimension(int id) {
        DimensionDescriptor descriptor = dimensions.get(id);
        dimensions.remove(id);
        dimensionToID.remove(descriptor);
        dimensionInformation.remove(id);
        if (DimensionManager.isDimensionRegistered(id)) {
            DimensionManager.unregisterDimension(id);
        }
        DimensionManager.unregisterProviderType(id);
    }

    public void recoverDimension(World world, int id, DimensionDescriptor descriptor, String name, String playerName, UUID player) {
        if (!DimensionManager.isDimensionRegistered(id)) {
            registerDimensionToServerAndClient(id);
        }

        DimensionInformation dimensionInfo = new DimensionInformation(name, descriptor, world, playerName, player);

        dimensions.put(id, descriptor);
        dimensionToID.put(descriptor, id);
        dimensionInformation.put(id, dimensionInfo);

        save(world);
        touchSpawnChunk(id);
    }

    public int countOwnedDimensions(UUID player) {
        int cnt = 0;
        for (Map.Entry<Integer, DimensionInformation> entry : dimensionInformation.entrySet()) {
            int id = entry.getKey();
            DimensionInformation information = entry.getValue();
            if (player.equals(information.getOwner())) {
                cnt++;
            }

        }
        return cnt;
    }

    public int createNewDimension(World world, DimensionDescriptor descriptor, String name, String playerName, UUID player) {
        int id = 0;
        while (!reclaimedIds.isEmpty()) {
            int rid = reclaimedIds.iterator().next();
            reclaimedIds.remove(rid);
            if (!DimensionManager.isDimensionRegistered(rid)) {
                id = rid;
                break;
            }
        }
        if (id == 0) {
            id = DimensionManager.getNextFreeDimId();
        }

        registerDimensionToServerAndClient(id);
        Logging.log("id = " + id + " for " + name + ", descriptor = " + descriptor.getDescriptionString());


        dimensions.put(id, descriptor);
        dimensionToID.put(descriptor, id);


        try {
            DimensionInformation dimensionInfo = new DimensionInformation(name, descriptor, world, playerName, player);
            dimensionInformation.put(id, dimensionInfo);
        } catch (Exception e) {
            Logging.logError("Something went wrong during creation of the dimension!");
            e.printStackTrace();
        }

        save(world);

        touchSpawnChunk(id);
        return id;
    }

    private void touchSpawnChunk(int id) {
        // Make sure world generation kicks in for at least one chunk so that our matter receiver
        // is generated and registered.
        WorldServer worldServerForDimension = MinecraftServer.getServer().worldServerForDimension(id);
        ChunkProviderServer providerServer = worldServerForDimension.theChunkProviderServer;
        if (!providerServer.chunkExists(0, 0)) {
            try {
                providerServer.loadChunk(0, 0);
                providerServer.populate(providerServer, 0, 0);
//                providerServer.unloadChunksIfNotNearSpawn(0, 0);
                providerServer.unloadAllChunks();
            } catch (Exception e) {
                Logging.logError("Something went wrong during creation of the dimension!");
                e.printStackTrace();
                // We catch this exception to make sure our dimension tab is at least ok.
            }
        }
    }

    @Override
    public void readFromNBT(NBTTagCompound tagCompound) {
        dimensions.clear();
        dimensionToID.clear();
        dimensionInformation.clear();
        reclaimedIds.clear();
        NBTTagList lst = tagCompound.getTagList("dimensions", Constants.NBT.TAG_COMPOUND);
        for (int i = 0 ; i < lst.tagCount() ; i++) {
            NBTTagCompound tc = lst.getCompoundTagAt(i);
            int id = tc.getInteger("id");
            DimensionDescriptor descriptor = new DimensionDescriptor(tc);
            dimensions.put(id, descriptor);
            dimensionToID.put(descriptor, id);

            DimensionInformation dimensionInfo = new DimensionInformation(descriptor, tc);
            dimensionInformation.put(id, dimensionInfo);
        }

        int[] lstIds = tagCompound.getIntArray("reclaimedIds");
        for (int id : lstIds) {
            reclaimedIds.add(id);
        }

    }

    @Override
    public void writeToNBT(NBTTagCompound tagCompound) {
        NBTTagList lst = new NBTTagList();
        for (Map.Entry<Integer,DimensionDescriptor> me : dimensions.entrySet()) {
            NBTTagCompound tc = new NBTTagCompound();

            Integer id = me.getKey();
            tc.setInteger("id", id);
            me.getValue().writeToNBT(tc);
            DimensionInformation dimensionInfo = dimensionInformation.get(id);
            dimensionInfo.writeToNBT(tc);

            lst.appendTag(tc);
        }
        tagCompound.setTag("dimensions", lst);

        List<Integer> ids = new ArrayList<Integer>(reclaimedIds);
        int[] lstIds = new int[ids.size()];
        for (int i = 0 ; i < ids.size() ; i++) {
            lstIds[i] = ids.get(i);
        }
        tagCompound.setIntArray("reclaimedIds", lstIds);
    }
}
