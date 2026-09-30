package com.fongmi.android.tv.ui.adapter;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.ui.custom.PointerRecyclerView;
import com.fongmi.android.tv.ui.presenter.FilterPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native category/search grid. Presenters bind existing card layouts, not selection or scrolling. */
public final class VodGridAdapter extends ListAdapter<VodGridAdapter.Item, VodGridAdapter.Holder> {
    private final VodPresenter.OnClickListener clicks;
    private final List<Vod> videos = new ArrayList<>();
    private List<Filter> filters = List.of();
    private FilterPresenter.OnClickListener filterClicks;
    private Style style = Style.rect();
    private final Map<String, Integer> types = new HashMap<>();
    private final Map<Integer, Presenter> presenters = new HashMap<>();
    private final Map<String, Long> ids = new HashMap<>();
    private long nextId;
    private Runnable onCommitted;

    static final class Item {
        final String key;
        final int type;
        final Object value;
        final Style style;
        Item(String key, int type, Object value, Style style) {
            this.key = key; this.type = type; this.value = value; this.style = style;
        }
    }
    public VodGridAdapter(VodPresenter.OnClickListener clicks) {
        super(new DiffUtil.ItemCallback<>() {
            @Override public boolean areItemsTheSame(@NonNull Item a, @NonNull Item b) { return a.key.equals(b.key); }
            @Override public boolean areContentsTheSame(@NonNull Item a, @NonNull Item b) { return false; }
        });
        this.clicks = clicks;
        setHasStableIds(true);
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }
    public void attach(PointerRecyclerView view, Runnable committed) {
        GridLayoutManager layout = new GridLayoutManager(view.getContext(), columns(style));
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) { return fullSpan(position) ? layout.getSpanCount() : 1; }
        });
        view.setLayoutManager(layout);
        view.setItemAnimator(null);
        view.setAdapter(this);
        view.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect out, @NonNull View child, @NonNull RecyclerView rv, @NonNull RecyclerView.State state) {
                int pos = rv.getChildAdapterPosition(child);
                if (pos == RecyclerView.NO_POSITION) return;
                int gap = ResUtil.dp2px(16), count = layout.getSpanCount();
                if (!fullSpan(pos)) {
                    int column = ((GridLayoutManager.LayoutParams) child.getLayoutParams()).getSpanIndex();
                    out.left = column * gap / count;
                    out.right = gap - (column + 1) * gap / count;
                }
                out.bottom = gap;
            }
        });
        onCommitted = () -> {
            if (layout.getSpanCount() != columns(style)) layout.setSpanCount(columns(style));
            committed.run();
        };
    }
    private int columns(Style style) { return style.isList() ? 1 : Math.max(1, Product.getColumn(style)); }
    private boolean fullSpan(int position) {
        return position < 0 || position >= getItemCount() || getItem(position).type == 0 || getItem(position).style.isList();
    }
    public int getFilterCount() { return filters.size(); }
    public int firstRowEnd() { return filters.size() + columns(style); }
    public int positionOf(Vod vod) {
        for (int i = 0; i < getItemCount(); i++) if (getItem(i).value == vod) return i;
        return RecyclerView.NO_POSITION;
    }
    public void setFilters(List<Filter> filters, FilterPresenter.OnClickListener listener) {
        this.filters = new ArrayList<>(filters);
        filterClicks = listener;
        rebuild();
    }
    public void clearVideos() { videos.clear(); rebuild(); }
    public void setVideos(List<Vod> items, Style style) {
        videos.clear(); videos.addAll(items); this.style = style; rebuild();
    }
    public void addVideos(List<Vod> items, Style style) { videos.addAll(items); this.style = style; rebuild(); }
    private void rebuild() {
        List<Item> items = new ArrayList<>();
        for (Filter filter : filters) items.add(new Item("filter:" + filter.getKey(), 0, filter.copy(), style));
        String spec = style.getType() + ":" + style.getRatio() + ":" + columns(style);
        int type = types.computeIfAbsent(spec, key -> {
            int next = types.size() + 1;
            presenters.put(next, new VodPresenter(clicks, style));
            return next;
        });
        Map<String, Integer> duplicates = new HashMap<>();
        for (Vod vod : videos) {
            String key = "vod:" + vod.getSiteKey() + ":" + vod.getId() + ":" + vod.getName();
            items.add(new Item(key + ":" + duplicates.merge(key, 1, Integer::sum), type, vod, style));
        }
        submitList(items);
    }
    @Override public void onCurrentListChanged(@NonNull List<Item> old, @NonNull List<Item> current) {
        super.onCurrentListChanged(old, current);
        if (onCommitted != null) onCommitted.run();
    }
    @Override public long getItemId(int position) { return ids.computeIfAbsent(getItem(position).key, key -> nextId++); }
    @Override public int getItemViewType(int position) { return getItem(position).type; }
    @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == 0) {
            PointerRecyclerView row = new PointerRecyclerView(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setLayoutManager(new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
            row.setItemAnimator(null);
            row.setClipChildren(false);
            row.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override public void getItemOffsets(@NonNull Rect out, @NonNull View child, @NonNull RecyclerView rv, @NonNull RecyclerView.State state) {
                    if (rv.getChildAdapterPosition(child) > 0) out.left = ResUtil.dp2px(8);
                }
            });
            return new Holder(row, null, null);
        }
        Presenter presenter = presenters.get(type);
        Presenter.ViewHolder card = presenter.onCreateViewHolder(parent);
        card.view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        card.view.setFocusableInTouchMode(false);
        card.view.setOnFocusChangeListener((v, focus) -> {
            v.animate().cancel();
            v.animate().scaleX(focus ? 1.04f : 1).scaleY(focus ? 1.04f : 1).setDuration(120).start();
        });
        return new Holder(card.view, presenter, card);
    }
    @Override public void onBindViewHolder(@NonNull Holder holder, int position) {
        Item item = getItem(position);
        if (item.type != 0) { holder.presenter.onBindViewHolder(holder.card, item.value); return; }
        Filter filter = (Filter) item.value;
        PointerRecyclerView row = (PointerRecyclerView) holder.itemView;
        if (holder.filter == null || !holder.filter.key.equals(filter.getKey())) {
            holder.filter = new FilterAdapter(filter.getKey());
            row.setAdapter(holder.filter);
        }
        holder.filter.setValues(filter.getValue());
    }
    @Override public void onViewRecycled(@NonNull Holder holder) {
        if (holder.presenter != null) holder.presenter.onUnbindViewHolder(holder.card);
        holder.itemView.animate().cancel(); holder.itemView.setScaleX(1); holder.itemView.setScaleY(1);
        super.onViewRecycled(holder);
    }
    static final class Holder extends RecyclerView.ViewHolder {
        final Presenter presenter;
        final Presenter.ViewHolder card;
        FilterAdapter filter;
        Holder(View view, Presenter presenter, Presenter.ViewHolder card) { super(view); this.presenter = presenter; this.card = card; }
    }
    private final class FilterAdapter extends RecyclerView.Adapter<Holder> {
        final String key;
        final FilterPresenter presenter;
        List<Value> values = List.of();
        FilterAdapter(String key) {
            this.key = key;
            presenter = new FilterPresenter(key);
            presenter.setOnClickListener((k, value) -> { if (filterClicks != null) filterClicks.onItemClick(k, value); });
        }
        void setValues(List<Value> next) {
            int old = values.size(); values = new ArrayList<>(next);
            if (old != values.size()) notifyDataSetChanged();
            else notifyItemRangeChanged(0, values.size());
        }
        @Override public int getItemCount() { return values.size(); }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Presenter.ViewHolder card = presenter.onCreateViewHolder(parent);
            card.view.setFocusableInTouchMode(false);
            return new Holder(card.view, presenter, card);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { presenter.onBindViewHolder(holder.card, values.get(position)); }
    }
}
