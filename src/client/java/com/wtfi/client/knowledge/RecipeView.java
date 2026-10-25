package com.wtfi.client.knowledge;

import com.wtfi.data.WtfiDataset.RecipeInfo;
import com.wtfi.data.WtfiDataset.SlotInfo;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** A recipe with every slot resolved to concrete stacks (tags expanded) ready to draw. */
public record RecipeView(RecipeInfo info, List<List<ItemStack>> inputs, List<ItemStack> outputs, List<ItemStack> station) {
	public static RecipeView of(RecipeInfo info) {
		List<List<ItemStack>> inputs = new ArrayList<>(info.inputs().size());
		for (SlotInfo slot : info.inputs()) inputs.add(resolve(slot));
		return new RecipeView(info, inputs, resolve(info.output()), resolve(info.station()));
	}

	public static List<ItemStack> resolve(SlotInfo slot) {
		if (slot.tag().isPresent()) {
			List<ItemStack> out = new ArrayList<>();
			for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(slot.tag().get())) {
				out.add(new ItemStack(holder));
			}
			return out;
		}
		return slot.stacks();
	}

	public ItemStack firstOutput() {
		return this.outputs.isEmpty() ? ItemStack.EMPTY : this.outputs.getFirst();
	}

	public String layout() {
		return this.info.layout();
	}

	/** Non-empty input slots. */
	public int ingredientSlots() {
		int n = 0;
		for (List<ItemStack> input : this.inputs) if (!input.isEmpty()) n++;
		return n;
	}
}
