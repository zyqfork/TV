package com.fongmi.android.tv.ui.adapter;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Main-thread bookkeeping for presenter-backed grids; indexes describe accepted UI data only. */
public abstract class PresenterGridAdapter<T, VH extends RecyclerView.ViewHolder> extends ListAdapter<T, VH> {
    private final Map<String, Integer> typeIds = new HashMap<>();
    private final Map<Integer, Presenter> presenters = new HashMap<>();
    private final Map<Integer, Boolean> fullSpanTypes = new HashMap<>();
    private final Map<String, Long> ids = new HashMap<>();
    private final Map<String, Integer> positions = new HashMap<>();
    private List<String> incomingKeys = List.of();
    private final int firstCardType;
    private long nextId;

    protected PresenterGridAdapter(@NonNull DiffUtil.ItemCallback<T> callback, int firstCardType) {
        super(callback);
        this.firstCardType = firstCardType;
        setHasStableIds(true);
    }

    protected abstract String itemKey(T item);
    /** Null for fixed header/filter rows; duplicate cards resolve to the first accepted position. */
    protected abstract String cardLookupKey(T item);

    protected int cardType(String spec, boolean fullSpanRow, Supplier<Presenter> factory) {
        Integer existing = typeIds.get(spec);
        if (existing != null) return existing;
        int id = firstCardType + typeIds.size();
        typeIds.put(spec, id);
        presenters.put(id, factory.get());
        fullSpanTypes.put(id, fullSpanRow);
        return id;
    }
    protected Presenter presenterFor(int type) { return presenters.get(type); }
    protected boolean isFullSpanType(int type) { return Boolean.TRUE.equals(fullSpanTypes.get(type)); }
    /** A key keeps its id while present in accepted or incoming data. */
    protected long stableId(String key) { return ids.computeIfAbsent(key, ignored -> nextId++); }
    protected int positionOfKey(String key) { return positions.getOrDefault(key, RecyclerView.NO_POSITION); }

    protected final void submitGridList(List<T> incoming) {
        List<String> keys = new ArrayList<>(incoming.size());
        for (T item : incoming) keys.add(itemKey(item));
        incomingKeys = keys;
        pruneIds();
        submitList(incoming);
    }

    private void pruneIds() {
        // A/B/C can all be submitted before B is accepted. Retaining B+C is NOT enough:
        // RecyclerView can still request ids for accepted A during that entire interval.
        Set<String> keep = new HashSet<>(incomingKeys);
        for (T item : getCurrentList()) keep.add(itemKey(item));
        ids.keySet().retainAll(keep);
    }

    @Override public void onCurrentListChanged(@NonNull List<T> previous, @NonNull List<T> current) {
        super.onCurrentListChanged(previous, current);
        positions.clear();
        for (int i = 0; i < current.size(); i++) {
            String key = cardLookupKey(current.get(i));
            if (key != null) positions.putIfAbsent(key, i);
        }
        pruneIds();
    }
}
