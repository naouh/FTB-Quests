package dev.ftb.mods.ftbquests.quest.task;

import de.marhali.json5.Json5Object;
import dev.ftb.mods.ftblibrary.client.config.EditableConfigGroup;
import dev.ftb.mods.ftblibrary.icon.Icon;
import dev.ftb.mods.ftblibrary.icon.ItemIcon;
import dev.ftb.mods.ftblibrary.json5.Json5Ops;
import dev.ftb.mods.ftblibrary.json5.Json5Util;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * Ultimate Neo: "store N of an item in a Deep Storage Unit at once". The task completes when a player right-clicks a
 * MineFactory Reloaded Deep Storage Unit (any block entity implementing {@code IDeepStorageUnit}) holding at least
 * {@code count} of the item. Nothing is taken: the items stay in the unit. All or nothing, as the user's 1.4.7 QuestCycle
 * {@code dsu_named} task (a DSU holds billions, far beyond an inventory, so an item task cannot count it).
 * <p>
 * The DSU is found by reflection ({@code nao.ultimateneo.mfr.api.IDeepStorageUnit#getStoredItemType}, the count may exceed
 * the stack size), so FTB Quests keeps no dependency on MineFactoryReloaded-neo; without it the task never completes.
 */
public class DeepStorageTask extends Task {
	private static final String DSU_API = "nao.ultimateneo.mfr.api.IDeepStorageUnit";
	private static volatile boolean resolved;
	private static volatile @Nullable Class<?> dsuInterface;
	private static volatile @Nullable Method getStoredItemType;

	private ItemStack itemStack;
	private long count;

	public DeepStorageTask(long id, Quest quest) {
		super(id, quest);
		itemStack = ItemStack.EMPTY;
		count = 1L;
	}

	@Override
	public TaskType getType() {
		return TaskTypes.DEEP_STORAGE;
	}

	@Override
	public long getMaxProgress() {
		return count;
	}

	public ItemStack getItemStack() {
		return itemStack;
	}

	@Override
	public void writeData(Json5Object json, HolderLookup.Provider provider) {
		super.writeData(json, provider);
		json.add("item", ItemStack.CODEC.encodeStart(provider.createSerializationContext(Json5Ops.INSTANCE), itemStack.copyWithCount(1)).getOrThrow());
		json.addProperty("count", count);
	}

	@Override
	public void readData(Json5Object json, HolderLookup.Provider provider) {
		super.readData(json, provider);
		itemStack = itemOrMissingFromJson(Json5Util.getJson5Object(json, "item").orElseThrow(), provider);
		count = Math.max(Json5Util.getLong(json, "count").orElse(1L), 1L);
	}

	@Override
	public void writeNetData(RegistryFriendlyByteBuf buffer) {
		super.writeNetData(buffer);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, itemStack);
		buffer.writeVarLong(count);
	}

	@Override
	public void readNetData(RegistryFriendlyByteBuf buffer) {
		super.readNetData(buffer);
		itemStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
		count = Math.max(buffer.readVarLong(), 1L);
	}

	@Override
	public void fillConfigGroup(EditableConfigGroup config) {
		super.fillConfigGroup(config);
		config.addItemStack("item", itemStack, v -> itemStack = v, ItemStack.EMPTY, true, false).setNameKey("ftbquests.task.ftbquests.item");
		config.addLong("count", count, v -> count = v, 1L, 1L, Long.MAX_VALUE);
	}

	@Override
	public MutableComponent getAltTitle() {
		return Component.translatable("ftbquests.task.ftbquests.deep_storage.title", count, itemStack.getHoverName());
	}

	@Override
	public Icon<?> getAltIcon() {
		return itemStack.isEmpty() ? super.getAltIcon() : ItemIcon.ofItemStack(itemStack.copyWithCount(1));
	}

	@Override
	public boolean checkOnLogin() {
		return false;
	}

	/** A player right-clicked {@code pos}: completes the task if it is a Deep Storage Unit holding enough of the item. */
	public void interacted(TeamData data, Level level, BlockPos pos) {
		if (data.isCompleted(this) || itemStack.isEmpty()) {
			return;
		}
		ItemStack stored = storedIn(level.getBlockEntity(pos));
		if (!stored.isEmpty() && ItemStack.isSameItem(stored, itemStack) && stored.getCount() >= count) {
			data.setProgress(this, count);
		}
	}

	/** What a Deep Storage Unit holds (count = the whole amount), or EMPTY when {@code be} is not one. */
	public static ItemStack storedIn(@Nullable BlockEntity be) {
		if (be == null) {
			return ItemStack.EMPTY;
		}
		if (!resolved) {
			resolve();
		}
		Class<?> api = dsuInterface;
		Method getter = getStoredItemType;
		if (api == null || getter == null || !api.isInstance(be)) {
			return ItemStack.EMPTY;
		}
		try {
			return getter.invoke(be) instanceof ItemStack stack ? stack : ItemStack.EMPTY;
		} catch (ReflectiveOperationException | RuntimeException e) {
			return ItemStack.EMPTY;
		}
	}

	private static synchronized void resolve() {
		if (resolved) {
			return;
		}
		try {
			Class<?> api = Class.forName(DSU_API, false, DeepStorageTask.class.getClassLoader());
			getStoredItemType = api.getMethod("getStoredItemType");
			dsuInterface = api;
		} catch (ReflectiveOperationException | LinkageError e) {
			dsuInterface = null;
			getStoredItemType = null;
		}
		resolved = true;
	}
}
