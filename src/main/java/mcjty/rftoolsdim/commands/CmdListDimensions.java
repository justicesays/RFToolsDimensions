package mcjty.rftoolsdim.commands;

import mcjty.lib.tools.ChatTools;
import mcjty.rftoolsdim.dimensions.DimensionInformation;
import mcjty.rftoolsdim.dimensions.DimensionStorage;
import mcjty.rftoolsdim.dimensions.RfToolsDimensionManager;
import mcjty.rftoolsdim.dimensions.description.DimensionDescriptor;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.Map;

public class CmdListDimensions extends AbstractRfToolsCommand {
    @Override
    public String getHelp() {
        return "";
    }

    @Override
    public String getCommand() {
        return "list";
    }

    @Override
    public int getPermissionLevel() {
        return 0;
    }

    @Override
    public boolean isClientSide() {
        return false;
    }

    @Override
    public void execute(ICommandSender sender, String[] args) {
        WorldServer[] worlds = DimensionManager.getWorlds();
        for (WorldServer world : worlds) {
            int id = world.provider.getDimension();
            String dimName = world.provider.getDimensionType().getName();
            ChatTools.addChatMessage(sender, new TextComponentString("    Loaded: id:" + id + ", " + dimName));
        }

        RfToolsDimensionManager dimensionManager = RfToolsDimensionManager.getDimensionManager(sender.getEntityWorld());
        DimensionStorage dimensionStorage = DimensionStorage.getDimensionStorage(sender.getEntityWorld());
        for (Map.Entry<Integer,DimensionDescriptor> me : dimensionManager.getDimensions().entrySet()) {
            int id = me.getKey();
            DimensionInformation dimensionInformation = dimensionManager.getDimensionInformation(id);
            String dimName = dimensionInformation.getName();
            int energy = dimensionStorage.getEnergyLevel(id);
            String ownerName = dimensionInformation.getOwnerName();
            if (ownerName != null && !ownerName.isEmpty()) {
                ChatTools.addChatMessage(sender, new TextComponentString("    RfTools: id:" + id + ", " + dimName + " (power " + energy + ") (owner " + ownerName + ")"));
            } else {
                ChatTools.addChatMessage(sender, new TextComponentString("    RfTools: id:" + id + ", " + dimName + " (power " + energy + ")"));
            }
        }
    }
}
