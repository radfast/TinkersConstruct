package tconstruct.util;

import cpw.mods.fml.common.event.FMLInterModComms;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.FluidStack;
import tconstruct.TConstruct;
import tconstruct.library.TConstructRegistry;
import tconstruct.library.crafting.CastingRecipe;
import tconstruct.library.crafting.PatternBuilder;
import tconstruct.library.crafting.StencilBuilder;
import tconstruct.library.tools.DynamicToolPart;
import tconstruct.library.tools.ToolMaterial;
import tconstruct.library.util.IPattern;
import tconstruct.library.util.IToolPart;
import tconstruct.smeltery.TinkerSmeltery;
import tconstruct.tools.TinkerTools;
import tconstruct.tools.items.Pattern;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public final class IMCHandler {
    private IMCHandler() {}

    public static void processIMC(List<FMLInterModComms.IMCMessage> messages)
    {
        for(FMLInterModComms.IMCMessage message : messages)
        {
            String type = message.key;
            if(type == null || type.isEmpty())
                continue;

            // process materials added from mods
            if(type.equals("addMaterial"))
            {
                if(!message.isNBTMessage())
                {
                    logInvalidMessage(message);
                    continue;
                }

                NBTTagCompound tag = message.getNBTValue();
                ToolMaterial mat = scanMaterial(tag);
                if(mat != null) {
                    TConstructRegistry.addtoolMaterial(tag.getInteger("Id"), mat);
                    TConstruct.logger.info("IMC: Added material " + mat.materialName);
                }
            }
            else if(type.equals("addPartBuilderMaterial"))
            {
                if(!message.isNBTMessage())
                {
                    logInvalidMessage(message);
                    continue;
                }
                NBTTagCompound tag = message.getNBTValue();

                if(!checkRequiredTags("PartBuilder", tag, "MaterialId", "Item", "Value"))
                    continue;

                int matID = tag.getInteger("MaterialId");
                int value = tag.getInteger("Value");

                if(TConstructRegistry.getMaterial(matID) == null)
                {
                    TConstruct.logger.error("PartBuilder IMC: Unknown Material ID " + matID);
                    continue;
                }

                ItemStack item = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Item"));
                ItemStack shard = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("Shard")); // optional
                ItemStack rod = new ItemStack(TinkerTools.toolRod, 1, matID);

                // register the material
                PatternBuilder.instance.registerFullMaterial(item, value, TConstructRegistry.getMaterial(matID).materialName, shard, rod, matID);

                List<Item> addItems = new LinkedList<Item>();
                List<Integer> addMetas = new LinkedList<Integer>();
                List<ItemStack> addOUtputs = new LinkedList<ItemStack>();

                // add mappings for everything that has stone tool mappings
               for(Map.Entry<List, ItemStack> mappingEntry : TConstructRegistry.patternPartMapping.entrySet())
                {
                    List mapping = mappingEntry.getKey();
                    // only stone mappings
                    if((Integer)mapping.get(2) != TinkerTools.MaterialID.Stone)
                        continue;

                    // only if the output is a dynamic part
                    if(!(mappingEntry.getValue().getItem() instanceof DynamicToolPart))
                        continue;

                    Item woodPattern = (Item) mapping.get(0);
                    Integer meta = (Integer) mapping.get(1);

                    ItemStack output = mappingEntry.getValue().copy();
                    output.setItemDamage(matID);

                    // save data, concurrent modification exception and i'm lazy
                    addItems.add(woodPattern);
                    addMetas.add(meta);
                    addOUtputs.add(output);
                }

                // add a part mapping for it
                for(int i = 0; i < addItems.size(); i++)
                    TConstructRegistry.addPartMapping(addItems.get(i), addMetas.get(i), matID, addOUtputs.get(i));


                TConstruct.logger.info("PartBuilder IMC: Added Part builder ampping for " + TConstructRegistry.getMaterial(matID).materialName);
            }
            else if(type.equals("addPartCastingMaterial"))
            {
                if(!message.isNBTMessage())
                {
                    logInvalidMessage(message);
                    continue;
                }

                NBTTagCompound tag = message.getNBTValue();

                if(!checkRequiredTags("Castingt", tag, "MaterialId", "FluidName"))
                    continue;

                if(!tag.hasKey("MaterialId"))
                {
                    TConstruct.logger.error("Casting IMC: Not material ID for the result present");
                    continue;
                }

                int matID = tag.getInteger("MaterialId");
                FluidStack liquid = FluidStack.loadFluidStackFromNBT(tag);
                if(liquid == null) {
                    TConstruct.logger.error("Casting IMC: No fluid found");
                    continue;
                }

                // we add the toolpart to all smeltery recipies that use iron and create a toolpart
                List<CastingRecipe> newRecipies = new LinkedList<CastingRecipe>();
                for(CastingRecipe recipe : TConstructRegistry.getTableCasting().getCastingRecipes())
                {
                    if(recipe.castingMetal.getFluid() != TinkerSmeltery.moltenIronFluid)
                        continue;
                    if(recipe.cast == null || !(recipe.cast.getItem() instanceof IPattern))
                        continue;
                    if(!(recipe.getResult().getItem() instanceof DynamicToolPart)) // has to be dynamic toolpart to support automatic addition
                        continue;

                    newRecipies.add(recipe);
                }

                // has to be done separately so we have all checks and no concurrent modification exception
                for(CastingRecipe recipe : newRecipies)
                {
                    ItemStack output = recipe.getResult().copy();
                    output.setItemDamage(matID);

                    FluidStack liquid2 = new FluidStack(liquid, recipe.castingMetal.amount);

                    // ok, this recipe creates a toolpart and uses iron for it. add a new one for the IMC stuff!
                    TConstructRegistry.getTableCasting().addCastingRecipe(output, liquid2, recipe.cast, recipe.consumeCast, recipe.coolTime);
                }

                TConstruct.logger.info("Casting IMC: Added fluid " + tag.getString("FluidName") + " to part casting");
            }
        }
    }

    private static boolean checkRequiredTags(String prefix, NBTTagCompound tag, String... tags)
    {
        boolean ok = true;
        for(String t : tags)
            if(!tag.hasKey(t))
            {
                TConstruct.logger.error(String.format("%s IMC: Missing required NBT Tag %s", prefix, t));
                ok = false; // don't abort, report all missing tags
            }

        return ok;
    }

    private static void logInvalidMessage(FMLInterModComms.IMCMessage message)
    {
        TConstruct.logger.error(String.format("Received invalid IMC '%s' from %s. Not a NBT Message.", message.key, message.getSender()));
    }

    private static ToolMaterial scanMaterial(NBTTagCompound tag)
    {
        if(!tag.hasKey("Name")) {
            TConstruct.logger.error("Material IMC: Material has no name");
            return null;
        }
        String name = tag.getString("Name");

        if(!tag.hasKey("Id")) {
            TConstruct.logger.error("Material IMC: Materials need a unique id. " + name);
            return null;
        }
        else if(!tag.hasKey("Durability")) {
            TConstruct.logger.error("Material IMC: Materials need a durability. " + name);
            return null;
        }
        else if(!tag.hasKey("MiningSpeed")) {
            TConstruct.logger.error("Material IMC: Materials need a mining speed. " + name);
            return null;
        }
        else if(tag.hasKey("Stonebound") && tag.hasKey("Jagged")) {
            TConstruct.logger.error("Material IMC: Materials can only be Stonebound or Jagged. " + name);
            return null;
        }

        int hlvl = tag.getInteger("HarvestLevel");
        int durability = tag.getInteger("Durability");
        int speed = tag.getInteger("MiningSpeed");
        int attack = tag.getInteger("Attack");
        float handle = tag.getFloat("HandleModifier");
        int reinforced = tag.getInteger("Reinforced");
        float shoddy = tag.getFloat("Stonebound");
        String style = tag.getString("Style");
        int color = tag.getInteger("Color");

        if(tag.hasKey("Jagged"))
            shoddy = tag.getFloat("Jagged");

        if(tag.hasKey("localizationString"))
            return new ToolMaterial(name, tag.getString("localizationString"), hlvl, durability, speed, attack, handle, reinforced, shoddy, style, color);
        else
            return new ToolMaterial(name, hlvl, durability, speed, attack, handle, reinforced, shoddy, style, color);
    }
}
