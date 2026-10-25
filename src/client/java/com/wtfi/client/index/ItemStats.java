package com.wtfi.client.index;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.equipment.Equippable;

/** Numbers read straight from an item's components, used for sorting and the Compare panel. */
public record ItemStats(
	double attackDamage,
	double attackSpeed,
	double armor,
	double toughness,
	int durability,
	int nutrition,
	float saturation,
	int enchantability
) {
	/** Base values a player has with an empty hand, matching the vanilla tooltip numbers. */
	private static final double BASE_DAMAGE = 1.0;
	private static final double BASE_SPEED = 4.0;

	public static ItemStats of(ItemStack stack) {
		double[] damage = {0};
		double[] speed = {0};
		boolean[] hasWeaponStats = {false};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (attr, mod) -> {
			if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) return;
			if (attr.equals(Attributes.ATTACK_DAMAGE)) {
				damage[0] += mod.amount();
				hasWeaponStats[0] = true;
			} else if (attr.equals(Attributes.ATTACK_SPEED)) {
				speed[0] += mod.amount();
				hasWeaponStats[0] = true;
			}
		});

		double armor = 0;
		double toughness = 0;
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		if (equippable != null && equippable.slot().isArmor()) {
			double[] sums = new double[2];
			stack.forEachModifier(equippable.slot(), (attr, mod) -> addArmor(sums, attr, mod));
			armor = sums[0];
			toughness = sums[1];
		}

		FoodProperties food = stack.get(DataComponents.FOOD);
		var enchantable = stack.get(DataComponents.ENCHANTABLE);
		return new ItemStats(
			hasWeaponStats[0] ? BASE_DAMAGE + damage[0] : 0,
			hasWeaponStats[0] ? BASE_SPEED + speed[0] : 0,
			armor,
			toughness,
			stack.isDamageableItem() ? stack.getMaxDamage() : 0,
			food != null ? food.nutrition() : 0,
			food != null ? food.saturation() : 0,
			enchantable != null ? enchantable.value() : 0
		);
	}

	private static void addArmor(double[] sums, Holder<Attribute> attr, AttributeModifier mod) {
		if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) return;
		if (attr.equals(Attributes.ARMOR)) {
			sums[0] += mod.amount();
		} else if (attr.equals(Attributes.ARMOR_TOUGHNESS)) {
			sums[1] += mod.amount();
		}
	}

	public boolean isWeapon() {
		return this.attackDamage > 0;
	}

	public double dps() {
		return this.attackDamage * this.attackSpeed;
	}
}
