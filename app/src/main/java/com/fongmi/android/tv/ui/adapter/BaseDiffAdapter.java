package com.fongmi.android.tv.ui.adapter;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.impl.Diffable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public abstract class BaseDiffAdapter<T extends Diffable<T>, VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {

    protected final AsyncListDiffer<T> differ;
    // Accepted UI data can lag behind successive search/page callbacks. Mutations build
    // on the latest submitted snapshot so an in-flight diff cannot discard earlier appends.
    private List<T> pendingItems = new ArrayList<>();

    public BaseDiffAdapter() {
        this.differ = new AsyncListDiffer<>(this, new BaseItemCallback<T>());
    }

    private boolean listsAreSame(List<T> oldList, List<T> newList) {
        if (oldList.size() != newList.size()) return false;
        for (int i = 0; i < oldList.size(); i++) {
            T oldItem = oldList.get(i);
            T newItem = newList.get(i);
            if (!oldItem.isSameItem(newItem) || !oldItem.isSameContent(newItem)) return false;
        }
        return true;
    }

    public T getItem(int position) {
        return differ.getCurrentList().get(position);
    }

    public List<T> getItems() {
        return differ.getCurrentList();
    }

    public void setItems(List<T> items) {
        setItems(items, () -> {});
    }

    public void setItems(List<T> items, Runnable runnable) {
        pendingItems = new ArrayList<>(Objects.requireNonNullElseGet(items, ArrayList::new));
        differ.submitList(pendingItems, runnable);
    }

    public void setItems(List<T> items, Callback callback) {
        List<T> newItems = new ArrayList<>(Objects.requireNonNullElseGet(items, ArrayList::new));
        boolean hasChange = !listsAreSame(getItems(), newItems);
        // Always submit to supersede an older pending diff, even if accepted data is equal.
        setItems(newItems, () -> callback.onUpdateFinished(hasChange));
    }

    public void add(T item) {
        add(item, null);
    }

    public void add(T item, Runnable runnable) {
        List<T> current = new ArrayList<>(pendingItems);
        current.add(item);
        setItems(current, runnable);
    }

    public void addAll(List<T> items) {
        addAll(items, null);
    }

    public void addAll(List<T> items, Runnable runnable) {
        List<T> current = new ArrayList<>(pendingItems);
        current.addAll(items);
        setItems(current, runnable);
    }

    public void sort(T item) {
        sort(item, null);
    }

    public void sort(T item, Runnable runnable) {
        List<T> current = Stream.concat(pendingItems.stream(), Stream.of(item)).distinct().sorted().toList();
        setItems(current, runnable);
    }

    public void sort(List<T> items) {
        sort(items, null);
    }

    public void sort(List<T> items, Runnable runnable) {
        List<T> current = Stream.concat(pendingItems.stream(), items.stream()).distinct().sorted().toList();
        setItems(current, runnable);
    }

    public void remove(T item) {
        remove(item, null);
    }

    public void remove(T item, Runnable runnable) {
        List<T> current = new ArrayList<>(pendingItems);
        if (current.remove(item)) setItems(current, runnable);
    }

    public void clear() {
        clear(null);
    }

    public void clear(Runnable runnable) {
        setItems(new ArrayList<>(), runnable);
    }

    @Override
    public int getItemCount() {
        return differ.getCurrentList().size();
    }

    @NonNull
    @Override
    public abstract VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType);

    @Override
    public abstract void onBindViewHolder(@NonNull VH holder, int position);

    public interface Callback {

        void onUpdateFinished(boolean hasChange);
    }
}
