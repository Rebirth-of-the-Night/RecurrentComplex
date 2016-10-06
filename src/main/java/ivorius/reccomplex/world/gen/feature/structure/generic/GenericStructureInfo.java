/*
 *  Copyright (c) 2014, Lukas Tenbrink.
 *  * http://lukas.axxim.net
 */

package ivorius.reccomplex.world.gen.feature.structure.generic;

import com.google.gson.*;
import ivorius.ivtoolkit.blocks.IvBlockCollection;
import ivorius.ivtoolkit.tools.IvWorldData;
import ivorius.ivtoolkit.transform.Mover;
import ivorius.ivtoolkit.transform.PosTransformer;
import ivorius.reccomplex.RecurrentComplex;
import ivorius.reccomplex.block.GeneratingTileEntity;
import ivorius.reccomplex.json.JsonUtils;
import ivorius.reccomplex.json.NbtToJson;
import ivorius.reccomplex.world.gen.feature.structure.*;
import ivorius.reccomplex.world.gen.feature.structure.generic.gentypes.MazeGenerationInfo;
import ivorius.reccomplex.world.gen.feature.structure.generic.gentypes.NaturalGenerationInfo;
import ivorius.reccomplex.world.gen.feature.structure.generic.gentypes.GenerationInfo;
import ivorius.reccomplex.utils.expression.DependencyMatcher;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.Transformer;
import ivorius.reccomplex.world.gen.feature.structure.generic.transformers.TransformerMulti;
import ivorius.reccomplex.utils.*;
import ivorius.reccomplex.world.storage.loot.InventoryGenerationHandler;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.Constants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Created by lukas on 24.05.14.
 */
public class GenericStructureInfo implements StructureInfo<GenericStructureInfo.InstanceData>, Cloneable
{
    public static final int LATEST_VERSION = 3;
    public static final int MAX_GENERATING_LAYERS = 30;

    public final List<GenerationInfo> generationInfos = new ArrayList<>();
    public TransformerMulti transformer = new TransformerMulti();
    public final DependencyMatcher dependencies = new DependencyMatcher("");

    public NBTTagCompound worldDataCompound;

    public boolean rotatable;
    public boolean mirrorable;

    public Metadata metadata = new Metadata();

    public JsonObject customData;

    public static GenericStructureInfo createDefaultStructure()
    {
        GenericStructureInfo genericStructureInfo = new GenericStructureInfo();
        genericStructureInfo.rotatable = true;
        genericStructureInfo.mirrorable = true;

        genericStructureInfo.transformer.getData().setPreset("structure");
        genericStructureInfo.generationInfos.add(new NaturalGenerationInfo());

        return genericStructureInfo;
    }

    private static double[] getEntityPos(NBTTagCompound compound)
    {
        NBTTagList pos = compound.getTagList("Pos", Constants.NBT.TAG_DOUBLE);
        return new double[]{pos.getDoubleAt(0), pos.getDoubleAt(1), pos.getDoubleAt(2)};
    }

    @Nonnull
    @Override
    public int[] structureBoundingBox()
    {
        if (worldDataCompound == null)
            return new int[]{0, 0, 0};

        NBTTagCompound compound = worldDataCompound.getCompoundTag("blockCollection");
        return new int[]{compound.getInteger("width"), compound.getInteger("height"), compound.getInteger("length")};
    }

    @Override
    public boolean isRotatable()
    {
        return rotatable;
    }

    @Override
    public boolean isMirrorable()
    {
        return mirrorable;
    }

    @Override
    public boolean generate(@Nonnull final StructureSpawnContext context, @Nonnull InstanceData instanceData, @Nonnull TransformerMulti foreignTransformer)
    {
        WorldServer world = context.environment.world;
        IvWorldData worldData = constructWorldData();
        boolean asSource = context.generateAsSource;

        TransformerMulti transformer = this.transformer;
        TransformerMulti.InstanceData transformerData = instanceData.transformerData;

        if (!asSource && !foreignTransformer.isEmpty(instanceData.foreignTransformerData))
        {
            transformer = TransformerMulti.fuse(Arrays.asList(this.transformer, foreignTransformer));
            transformerData = transformer.fuseDatas(Arrays.asList(instanceData.transformerData, instanceData.foreignTransformerData));
        }

        if (context.generateMaturity == StructureSpawnContext.GenerateMaturity.SUGGEST)
        {
            if (!transformer.mayGenerate(transformerData, context, worldData))
                return false;
        }

        // The world initializes the block event array after it generates the world - in the constructor
        // This hackily sets the field to a temporary value. Yay.
        RCAccessorWorldServer.ensureBlockEventArray(world); // Hax

        IvBlockCollection blockCollection = worldData.blockCollection;
        int[] areaSize = new int[]{blockCollection.width, blockCollection.height, blockCollection.length};
        BlockPos origin = context.lowerCoord();

        Map<BlockPos, TileEntity> origTileEntities = new HashMap<>();
        Map<BlockPos, NBTTagCompound> tileEntityCompounds = new HashMap<>();
        for (NBTTagCompound tileEntityCompound : worldData.tileEntities)
        {
            BlockPos key = new BlockPos(tileEntityCompound.getInteger("x"), tileEntityCompound.getInteger("y"), tileEntityCompound.getInteger("z"));

            TileEntity origTileEntity = RecurrentComplex.specialRegistry.loadTileEntity(world, tileEntityCompound);
            Mover.setTileEntityPos(origTileEntity, context.transform.apply(key, areaSize).add(origin));

            origTileEntities.put(key, origTileEntity);
            tileEntityCompounds.put(key, tileEntityCompound);
        }

        if (!asSource)
            transformer.transform(transformerData, Transformer.Phase.BEFORE, context, worldData);

        BlockPos.MutableBlockPos worldPos = new BlockPos.MutableBlockPos();
        for (int pass = 0; pass < 2; pass++)
        {
            for (BlockPos sourcePos : RCBlockAreas.mutablePositions(blockCollection.area()))
            {
                IBlockState state = PosTransformer.transformBlockState(blockCollection.getBlockState(sourcePos), context.transform);
                worldPos = RCMutableBlockPos.add(RCAxisAlignedTransform.apply(sourcePos, worldPos, areaSize, context.transform), origin);

                if (context.includes(worldPos) && RecurrentComplex.specialRegistry.isSafe(state.getBlock())
                        && pass == getPass(state) && (asSource || !transformer.skipGeneration(transformerData, context, worldPos, state, worldData, sourcePos)))
                {
                    TileEntity origTileEntity = origTileEntities.get(sourcePos);

                    if (asSource || !(origTileEntity instanceof GeneratingTileEntity) || ((GeneratingTileEntity) origTileEntity).shouldPlaceInWorld(context, instanceData.tileEntities.get(sourcePos)))
                    {
                        if (context.setBlock(worldPos, state, 2) && world.getBlockState(worldPos).getBlock() == state.getBlock())
                        {
                            NBTTagCompound tileEntityCompound = tileEntityCompounds.get(sourcePos);

                            if (tileEntityCompound != null)
                            {
                                TileEntity tileEntity = world.getTileEntity(worldPos);

                                if (tileEntity != null)
                                {
                                    tileEntity.readFromNBT(tileEntityCompound);
                                    Mover.setTileEntityPos(tileEntity, worldPos);

                                    generateTileEntityContents(context, tileEntity);
                                }
                            }

                            PosTransformer.transformBlock(world, worldPos, context.transform);
                        }
                    }
                    else
                        context.setBlock(worldPos, Blocks.AIR.getDefaultState(), 2); // Replace with air
                }
            }
        }

        if (!asSource)
            transformer.transform(transformerData, Transformer.Phase.AFTER, context, worldData);

        for (NBTTagCompound entityCompound : worldData.entities)
        {
            double[] entityPos = getEntityPos(entityCompound);
            double[] transformedEntityPos = context.transform.apply(entityPos, areaSize);
            if (context.includes(transformedEntityPos[0] + origin.getX(), transformedEntityPos[1] + origin.getY(), transformedEntityPos[2] + origin.getZ()))
            {
                Entity entity = EntityList.createEntityFromNBT(entityCompound, world);

                if (entity != null)
                {
                    PosTransformer.transformEntityPos(entity, context.transform, areaSize);
                    Mover.moveEntity(entity, origin);

                    RCAccessorEntity.setEntityUniqueID(entity, UUID.randomUUID());
                    generateEntityContents(context, entity);
                    world.spawnEntityInWorld(entity);
                }
                else
                {
                    RecurrentComplex.logger.error("Error loading entity with ID '" + entityCompound.getString("id") + "'");
                }
            }
        }

        if (!asSource && context.generationLayer < MAX_GENERATING_LAYERS)
        {
            origTileEntities.entrySet().stream().filter(entry -> entry.getValue() instanceof GeneratingTileEntity).forEach(entry -> ((GeneratingTileEntity) entry.getValue()).generate(context, instanceData.tileEntities.get(entry.getKey())));
        }
        else
        {
            RecurrentComplex.logger.warn("Structure generated with over " + MAX_GENERATING_LAYERS + " layers; most likely infinite loop!");
        }

        return true;
    }

    public void generateEntityContents(@Nonnull StructureSpawnContext context, Entity entity)
    {
        if (!context.generateAsSource && entity instanceof IInventory)
            InventoryGenerationHandler.generateAllTags(context, (IInventory) entity);
    }

    public void generateTileEntityContents(@Nonnull StructureSpawnContext context, TileEntity tileEntity)
    {
        if (!context.generateAsSource && tileEntity instanceof IInventory)
            InventoryGenerationHandler.generateAllTags(context, (IInventory) tileEntity);
    }

    @Nonnull
    @Override
    public InstanceData prepareInstanceData(@Nonnull StructurePrepareContext context, @Nonnull TransformerMulti foreignTransformer)
    {
        InstanceData instanceData = new InstanceData();

        if (!context.generateAsSource)
        {
            IvWorldData worldData = constructWorldData();
            IvBlockCollection blockCollection = worldData.blockCollection;

            int[] areaSize = new int[]{blockCollection.width, blockCollection.height, blockCollection.length};
            BlockPos origin = context.lowerCoord();

            instanceData.transformerData = this.transformer.prepareInstanceData(context, worldData);
            instanceData.foreignTransformerData = foreignTransformer.prepareInstanceData(context, worldData);

            TransformerMulti transformer = TransformerMulti.fuse(Arrays.asList(this.transformer, foreignTransformer));
            TransformerMulti.InstanceData cInstanceData = transformer.fuseDatas(Arrays.asList(instanceData.transformerData, instanceData.foreignTransformerData));

            transformer.configureInstanceData(cInstanceData, context, worldData);

            worldData.tileEntities.forEach(teCompound ->
            {
                TileEntity tileEntity = RecurrentComplex.specialRegistry.loadTileEntity(TileEntities.getAnyWorld(), teCompound);
                if (tileEntity instanceof GeneratingTileEntity)
                {
                    BlockPos key = tileEntity.getPos();
                    Mover.setTileEntityPos(tileEntity, context.transform.apply(key, areaSize).add(origin));
                    instanceData.tileEntities.put(key, (NBTStorable) ((GeneratingTileEntity) tileEntity).prepareInstanceData(context));
                }
            });
        }

        return instanceData;
    }

    @Nonnull
    @Override
    public InstanceData loadInstanceData(@Nonnull StructureLoadContext context, @Nonnull final NBTBase nbt, @Nonnull TransformerMulti transformer)
    {
        InstanceData instanceData = new InstanceData();
        instanceData.readFromNBT(context, nbt, this.transformer, transformer, constructWorldData());
        return instanceData;
    }

    public IvWorldData constructWorldData()
    {
        return new IvWorldData(worldDataCompound, RecurrentComplex.specialRegistry.itemHidingMode());
    }

    @Nonnull
    @Override
    public <I extends GenerationInfo> List<I> generationInfos(@Nonnull Class<? extends I> clazz)
    {
        return generationInfos.stream().filter(info -> clazz.isAssignableFrom(info.getClass())).map(info -> (I) info).collect(Collectors.toList());
    }

    @Override
    public GenerationInfo generationInfo(@Nonnull String id)
    {
        for (GenerationInfo info : generationInfos)
        {
            if (Objects.equals(info.id(), id))
                return info;
        }

        return null;
    }

    private int getPass(IBlockState state)
    {
        return (state.isNormalCube() || state.getMaterial() == Material.AIR) ? 0 : 1;
    }

    @Override
    @Nonnull
    public GenericStructureInfo copyAsGenericStructureInfo()
    {
        return copy();
    }

    @Override
    public boolean areDependenciesResolved()
    {
        return dependencies.test(RecurrentComplex.saver);
    }

    @Nullable
    @Override
    public IvBlockCollection blockCollection()
    {
        return constructWorldData().blockCollection;
    }

    @Override
    public String toString()
    {
        String s = StructureRegistry.INSTANCE.id(this);
        return s != null ? s : "Generic Structure";
    }

    public GenericStructureInfo copy()
    {
        GenericStructureInfo genericStructureInfo = StructureSaveHandler.INSTANCE.fromJSON(StructureSaveHandler.INSTANCE.toJSON(this));
        genericStructureInfo.worldDataCompound = worldDataCompound.copy();
        return genericStructureInfo;
    }

    public static class Serializer implements JsonDeserializer<GenericStructureInfo>, JsonSerializer<GenericStructureInfo>
    {
        public GenericStructureInfo deserialize(JsonElement jsonElement, Type par2Type, JsonDeserializationContext context)
        {
            JsonObject jsonObject = JsonUtils.asJsonObject(jsonElement, "status");
            GenericStructureInfo structureInfo = new GenericStructureInfo();

            Integer version;
            if (jsonObject.has("version"))
            {
                version = JsonUtils.getInt(jsonObject, "version");
            }
            else
            {
                version = LATEST_VERSION;
                RecurrentComplex.logger.warn("Structure JSON missing 'version', using latest (" + LATEST_VERSION + ")");
            }

            if (jsonObject.has("generationInfos"))
                Collections.addAll(structureInfo.generationInfos, context.<GenerationInfo[]>deserialize(jsonObject.get("generationInfos"), GenerationInfo[].class));

            if (version == 1)
                structureInfo.generationInfos.add(NaturalGenerationInfo.deserializeFromVersion1(jsonObject, context));

            {
                // Legacy version 2
                if (jsonObject.has("naturalGenerationInfo"))
                    structureInfo.generationInfos.add(NaturalGenerationInfo.getGson().fromJson(jsonObject.get("naturalGenerationInfo"), NaturalGenerationInfo.class));

                if (jsonObject.has("mazeGenerationInfo"))
                    structureInfo.generationInfos.add(MazeGenerationInfo.getGson().fromJson(jsonObject.get("mazeGenerationInfo"), MazeGenerationInfo.class));
            }

            if (jsonObject.has("transformer"))
                structureInfo.transformer = context.deserialize(jsonObject.get("transformer"), TransformerMulti.class);
            else if (jsonObject.has("transformers")) // Legacy
                Collections.addAll(structureInfo.transformer.getTransformers(), context.<Transformer[]>deserialize(jsonObject.get("transformers"), Transformer[].class));
            else if (jsonObject.has("blockTransformers")) // Legacy
                Collections.addAll(structureInfo.transformer.getTransformers(), context.<Transformer[]>deserialize(jsonObject.get("blockTransformers"), Transformer[].class));

            structureInfo.rotatable = JsonUtils.getBoolean(jsonObject, "rotatable", false);
            structureInfo.mirrorable = JsonUtils.getBoolean(jsonObject, "mirrorable", false);

            if (jsonObject.has("dependencyExpression"))
                structureInfo.dependencies.setExpression(JsonUtils.getString(jsonObject, "dependencyExpression"));
            else if (jsonObject.has("dependencies")) // Legacy
                structureInfo.dependencies.setExpression(DependencyMatcher.ofMods(context.<String[]>deserialize(jsonObject.get("dependencies"), String[].class)));

            if (jsonObject.has("worldData"))
                structureInfo.worldDataCompound = context.deserialize(jsonObject.get("worldData"), NBTTagCompound.class);
            else if (jsonObject.has("worldDataBase64"))
                structureInfo.worldDataCompound = NbtToJson.getNBTFromBase64(JsonUtils.getString(jsonObject, "worldDataBase64"));
            // And else it is taken out for packet size, or stored in the zip

            if (jsonObject.has("metadata")) // Else, use default
                structureInfo.metadata = context.deserialize(jsonObject.get("metadata"), Metadata.class);

            structureInfo.customData = JsonUtils.getJsonObject(jsonObject, "customData", new JsonObject());

            return structureInfo;
        }

        public JsonElement serialize(GenericStructureInfo structureInfo, Type par2Type, JsonSerializationContext context)
        {
            JsonObject jsonObject = new JsonObject();

            jsonObject.addProperty("version", LATEST_VERSION);

            jsonObject.add("generationInfos", context.serialize(structureInfo.generationInfos));
            jsonObject.add("transformer", context.serialize(structureInfo.transformer));

            jsonObject.addProperty("rotatable", structureInfo.rotatable);
            jsonObject.addProperty("mirrorable", structureInfo.mirrorable);

            jsonObject.add("dependencyExpression", context.serialize(structureInfo.dependencies.getExpression()));

            if (!RecurrentComplex.USE_ZIP_FOR_STRUCTURE_FILES && structureInfo.worldDataCompound != null)
            {
                if (RecurrentComplex.USE_JSON_FOR_NBT)
                    jsonObject.add("worldData", context.serialize(structureInfo.worldDataCompound));
                else
                    jsonObject.addProperty("worldDataBase64", NbtToJson.getBase64FromNBT(structureInfo.worldDataCompound));
            }

            jsonObject.add("metadata", context.serialize(structureInfo.metadata));
            jsonObject.add("customData", structureInfo.customData);

            return jsonObject;
        }
    }

    public static class InstanceData implements NBTStorable
    {
        public static final String KEY_TRANSFORMER = "transformer";
        public static final String KEY_FOREIGN_TRANSFORMER = "foreignTransformer";
        public static final String KEY_TILE_ENTITIES = "tileEntities";

        public TransformerMulti.InstanceData transformerData;
        public TransformerMulti.InstanceData foreignTransformerData;
        public final Map<BlockPos, NBTStorable> tileEntities = new HashMap<>();

        protected static NBTBase getTileEntityTag(NBTTagCompound tileEntityCompound, BlockPos coord)
        {
            return tileEntityCompound.getTag(getTileEntityKey(coord));
        }

        private static String getTileEntityKey(BlockPos coord)
        {
            return String.format("%d,%d,%d", coord.getX(), coord.getY(), coord.getZ());
        }

        public void readFromNBT(StructureLoadContext context, NBTBase nbt, TransformerMulti transformer, @Nonnull TransformerMulti foreignTransformer, IvWorldData worldData)
        {
            IvBlockCollection blockCollection = worldData.blockCollection;
            NBTTagCompound compound = nbt instanceof NBTTagCompound ? (NBTTagCompound) nbt : new NBTTagCompound();

            transformerData = transformer.loadInstanceData(context, compound.getTag(KEY_TRANSFORMER));
            foreignTransformerData = foreignTransformer.loadInstanceData(context, compound.getTag(KEY_FOREIGN_TRANSFORMER));

            int[] areaSize = new int[]{blockCollection.width, blockCollection.height, blockCollection.length};
            BlockPos origin = context.lowerCoord();

            NBTTagCompound tileEntityCompound = compound.getCompoundTag(InstanceData.KEY_TILE_ENTITIES);
            worldData.tileEntities.stream().filter(tileEntity -> tileEntity instanceof GeneratingTileEntity).forEach(teCompound ->
            {
                TileEntity tileEntity = RecurrentComplex.specialRegistry.loadTileEntity(TileEntities.getAnyWorld(), teCompound);
                BlockPos key = tileEntity.getPos();
                Mover.setTileEntityPos(tileEntity, context.transform.apply(key, areaSize).add(origin));
                tileEntities.put(key, (NBTStorable) ((GeneratingTileEntity) tileEntity).loadInstanceData(context, getTileEntityTag(tileEntityCompound, key)));
            });
        }

        @Override
        public NBTBase writeToNBT()
        {
            NBTTagCompound compound = new NBTTagCompound();

            compound.setTag(KEY_TRANSFORMER, transformerData.writeToNBT());
            compound.setTag(KEY_FOREIGN_TRANSFORMER, foreignTransformerData.writeToNBT());

            NBTTagCompound tileEntityCompound = new NBTTagCompound();
            for (Map.Entry<BlockPos, NBTStorable> entry : tileEntities.entrySet())
                tileEntityCompound.setTag(getTileEntityKey(entry.getKey()), entry.getValue().writeToNBT());
            compound.setTag(KEY_TILE_ENTITIES, tileEntityCompound);

            return compound;
        }
    }
}
