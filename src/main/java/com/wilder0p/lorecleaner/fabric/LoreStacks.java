package com.wilder0p.lorecleaner.fabric;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;

final class LoreStacks {
    static final class Result {
        final List<ItemStack> extracted = new ArrayList<>();
        ItemStack remaining;
    }

    static boolean hasLore(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        ItemLore lore = stack.get(DataComponents.LORE);
        return lore != null && lore != ItemLore.EMPTY && !lore.lines().isEmpty();
    }

    static List<ItemStack> scan(ItemStack stack) {
        return extract(stack.copy()).extracted;
    }

    static Result extract(ItemStack stack) {
        Result r = new Result();
        if (stack == null || stack.isEmpty()) {
            r.remaining = stack;
            return r;
        }
        if (hasLore(stack)) {
            r.extracted.add(stack.copy());
            r.remaining = ItemStack.EMPTY;
            return r;
        }
        if (unpackContainer(stack, r)) {
            return r;
        }
        r.remaining = stack;
        return r;
    }

    private static boolean unpackContainer(ItemStack stack, Result r) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null || contents == ItemContainerContents.EMPTY) {
            return false;
        }
        List<ItemStack> items = contents.allItemsCopyStream().toList();
        List<ItemStack> keep = new ArrayList<>();
        boolean changed = false;
        for (ItemStack inner : items) {
            Result nested = extract(inner);
            if (!nested.extracted.isEmpty()) {
                r.extracted.addAll(nested.extracted);
                changed = true;
                if (nested.remaining != null && !nested.remaining.isEmpty()) {
                    keep.add(nested.remaining);
                }
            } else if (inner != null && !inner.isEmpty()) {
                keep.add(inner);
            }
        }
        if (changed) {
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(keep));
        }
        r.remaining = stack;
        return true;
    }
}
