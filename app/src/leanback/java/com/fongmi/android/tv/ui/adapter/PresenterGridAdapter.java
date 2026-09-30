package com.fongmi.android.tv.ui.adapter;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared bookkeeping for presenter-backed grids (home, category, search): stable ids, the card
 * type/presenter registry and a key to position index.
 *
 * <p>Deliberately contains no view code. Each adapter keeps its own row views, item spacing and bind
 * logic, so pulling this out cannot change what is drawn — it only removes the identical maps,
 * magic type offsets and id bookkeeping that used to be copy-pasted into every grid adapter.
 *
 * <p>Threading: main thread only, like every other RecyclerView.Adapter method.
 */
public abstract class PresenterGridAdapter<T, VH extends RecyclerView.ViewHolder> extends ListAdapter<T, VH> {

    private final Map<String, Integer> typeIds = new HashMap<>();
    private final Map<Integer, Presenter> presenters = new HashMap<>();
    private final Map<Integer, Boolean> fullSpanTypes = new HashMap<>();
    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, Integer> positions = new HashMap<>();
    /** Keys of the last submitted list: the differ only ever compares it with the incoming one. */
    private List<String> submittedKeys = List.of();
    private final int firstCardType;
    private long nextId;

    /**
     * @param firstCardType view type id for the first card style; must sit above the adapter's own
     *                      fixed row types so the two registries cannot collide.
     */
    protected PresenterGridAdapter(@NonNull DiffUtil.ItemCallback<T> callback, int firstCardType) {
        super(callback);
        this.firstCardType = firstCardType;
        setHasStableIds(true);
    }

    /**
     * One view type per distinct style spec; the factory runs at most once per spec. Without this a
     * grid would either rebuild a presenter per row or have to keep a separate type constant for
     * every style, column count and list/grid combination.
     */
    protected int cardType(String spec, boolean fullSpanRow, Supplier<Presenter> factory) {
        Integer existing = typeIds.get(spec);
        if (existing != null) return existing;
        int id = firstCardType + typeIds.size();
        typeIds.put(spec, id);
        presenters.put(id, factory.get());
        fullSpanTypes.put(id, fullSpanRow);
        return id;
    }

    protected Presenter presenterFor(int type) {
        return presenters.get(type);
    }

    protected boolean isFullSpanType(int type) {
        return Boolean.TRUE.equals(fullSpanTypes.get(type));
    }

    /** Stable id for an item key; the same key always maps to the same id for the whole session. */
    protected long stableId(String key) {
        return ids.computeIfAbsent(key, ignored -> nextId++);
    }

    /** Clears the position index before a rebuild repopulates it. */
    protected void beginIndex() {
        positions.clear();
    }

    /** Records the first position of a card key; duplicates of the same key keep the first one. */
    protected void indexCard(String key, int position) {
        positions.putIfAbsent(key, position);
    }

    protected int positionOfKey(String key) {
        Integer position = positions.get(key);
        return position == null ? RecyclerView.NO_POSITION : position;
    }

    /**
     * Bound the id registry. Only the accepted list and the incoming one can be asked for ids while
     * the differ runs, so anything absent from both will never be looked up again — without this the
     * map keeps one entry for every item ever shown, for the whole session.
     *
     * <p>Call before {@code submitList(incoming)} with the incoming keys; the submitted keys are
     * remembered here.
     */
    protected void pruneIds(@NonNull Collection<String> incomingKeys) {
        Set<String> keep = new HashSet<>(submittedKeys);
        keep.addAll(incomingKeys);
        ids.keySet().retainAll(keep);
        submittedKeys = List.copyOf(incomingKeys);
    }
}
