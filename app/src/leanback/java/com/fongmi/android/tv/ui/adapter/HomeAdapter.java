package com.fongmi.android.tv.ui.adapter;

import android.graphics.Rect;
import android.view.LayoutInflater;
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
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterHeaderBinding;
import com.fongmi.android.tv.impl.Diffable;
import com.fongmi.android.tv.ui.custom.HomeRecyclerView;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Native grid: toolbar, functions, history, section headings and individual recommendation cards. */
public final class HomeAdapter extends PresenterGridAdapter<HomeAdapter.Item, HomeAdapter.Holder> {

    private static final int TOOLBAR = 0, FUNCTIONS = 1, HISTORY = 2, SECTION = 3, PROGRESS = 4;
    /** Types at or above this are individual recommendation cards, not fixed header rows. */
    private static final int CARD = 5;
    /** Where rebuild() puts the entry row; the fixed header order below must keep it at 1. */
    private static final int FUNCTIONS_POSITION = 1;
    private final View toolbar;
    private final FuncPresenter funcs;
    private final VodPresenter.OnClickListener vodListener;
    private final HeaderPresenter.OnClickListener headerListener;
    private final ProgressPresenter progress = new ProgressPresenter();
    private List<Func> functions = List.of();
    private List<History> history = List.of();
    private List<Vod> recommendations = List.of();
    private HistoryPresenter historyPresenter;
    private Style style = Style.rect();
    private boolean loading;
    private Runnable onCommitted;

    static final class Item {
        final String key;
        final int type;
        final Object value;
        final Presenter presenter;
        final List<Object> cardContent;
        Item(String key, int type, Object value, Presenter presenter) {
            this.key = key;
            this.type = type;
            this.value = value;
            this.presenter = presenter;
            cardContent = value instanceof Vod vod ? vod.cardContent() : List.of();
        }
    }

    public HomeAdapter(View toolbar, FuncPresenter.OnClickListener funcs, VodPresenter.OnClickListener vods,
                       HeaderPresenter.OnClickListener headers) {
        super(new DiffUtil.ItemCallback<>() {
            @Override public boolean areItemsTheSame(@NonNull Item a, @NonNull Item b) { return a.key.equals(b.key); }

            @Override public boolean areContentsTheSame(@NonNull Item a, @NonNull Item b) {
                if (a.type != b.type) return false;
                // Toolbar and section headings render a constant; nothing to compare.
                if (a.type == TOOLBAR || a.type == SECTION) return true;
                // Cards carry the model they render, so reuse the bean's own content comparison —
                // returning false here would re-bind every poster on any list update.
                if (a.type >= CARD) return a.cardContent.equals(b.cardContent);
                // Entry/history/progress rows are snapshots of mutable state (or a whole list in one
                // item) and carry no comparable value; re-binding them is cheap and always correct.
                return false;
            }
        }, CARD);
        this.toolbar = toolbar;
        this.funcs = new FuncPresenter(funcs);
        vodListener = vods;
        headerListener = headers;
        setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
    }

    public void setOnCommitted(Runnable callback) { onCommitted = callback; }

    @Override
    public void onCurrentListChanged(@NonNull List<Item> previous, @NonNull List<Item> current) {
        super.onCurrentListChanged(previous, current);
        if (onCommitted != null) onCommitted.run();
    }

    public int getColumns() { return Math.max(1, Product.getColumn(style)); }
    public boolean isFullSpan(int position) {
        return position < 0 || position >= getItemCount() || getItem(position).type < CARD
                || isFullSpanType(getItem(position).type);
    }
    public int getFunctionsPosition() { return FUNCTIONS_POSITION; }

    /** O(1) by key, so a restored Vod instance does not have to be the exact list object. */
    public int positionOf(Vod vod) {
        return positionOfKey(cardKey(vod));
    }

    private static String cardKey(Vod vod) { return "vod:" + vod.getId() + ":" + vod.getName(); }
    public boolean isLoading() {
        for (Item item : getCurrentList()) if (item.type == PROGRESS) return true;
        return getItemCount() == 0;
    }
    public boolean isHistoryPosition(int position) {
        return position >= 0 && position < getItemCount() && getItem(position).type == HISTORY;
    }

    public void setFunctions(List<Func> items) { functions = new ArrayList<>(items); rebuild(); }
    public void setHistory(List<History> items, HistoryPresenter presenter) {
        history = new ArrayList<>(items);
        historyPresenter = presenter;
        rebuild();
    }
    public void setLoading() { recommendations = List.of(); loading = true; rebuild(); }
    public void setRecommendations(List<Vod> items, Style style) {
        this.style = style;
        recommendations = new ArrayList<>(items);
        loading = false;
        rebuild();
    }

    private void rebuild() {
        List<Item> items = new ArrayList<>();
        items.add(new Item("toolbar", TOOLBAR, null, null));
        items.add(new Item("functions", FUNCTIONS, new ArrayList<>(functions), funcs));
        items.add(new Item("history-title", SECTION, R.string.home_history, null));
        if (!history.isEmpty()) items.add(new Item("history", HISTORY, new ArrayList<>(history), historyPresenter));
        items.add(new Item("recommend-title", SECTION, R.string.home_recommend, null));
        if (loading) items.add(new Item("loading", PROGRESS, "progress", progress));
        String spec = style.getType() + ":" + style.getRatio() + ":" + getColumns();
        int type = cardType(spec, style.isList(), () -> new VodPresenter(vodListener, style));
        Map<String, Integer> occurrences = new HashMap<>();
        for (Vod vod : recommendations) {
            String key = cardKey(vod);
            int index = occurrences.merge(key, 1, Integer::sum);
            String itemKey = key + ":" + index;
            items.add(new Item(itemKey, type, vod, presenterFor(type)));
        }
        submitGridList(items);
    }

    @Override protected String itemKey(Item item) { return item.key; }
    @Override protected String cardLookupKey(Item item) { return item.value instanceof Vod vod ? cardKey(vod) : null; }

    @Override public long getItemId(int position) { return stableId(getItem(position).key); }
    @Override public int getItemViewType(int position) { return getItem(position).type; }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        if (type == TOOLBAR) {
            toolbar.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new Holder(toolbar, null, null);
        }
        if (type == FUNCTIONS || type == HISTORY) {
            HomeRecyclerView row = new HomeRecyclerView(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.setLayoutManager(new LinearLayoutManager(parent.getContext(), RecyclerView.HORIZONTAL, false));
            row.setItemAnimator(null);
            row.setClipToPadding(false);
            row.setClipChildren(false);
            row.addItemDecoration(new RecyclerView.ItemDecoration() {
                @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView rv, @NonNull RecyclerView.State state) {
                    if (rv.getChildAdapterPosition(view) > 0) out.left = ResUtil.dp2px(16);
                }
            });
            return new Holder(row, null, null);
        }
        if (type == SECTION) {
            View text = AdapterHeaderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false).getRoot();
            text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            text.setBackgroundResource(R.drawable.selector_home_header);
            text.setFocusableInTouchMode(false);
            return new Holder(text, null, null);
        }
        Presenter presenter = type == PROGRESS ? progress : presenterFor(type);
        Presenter.ViewHolder view = presenter.onCreateViewHolder(parent);
        view.view.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        if (type != PROGRESS) {
            view.view.setFocusableInTouchMode(false);
            installCardFocus(view.view);
        }
        return new Holder(view.view, presenter, view);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        Item item = getItem(position);
        if (item.type == TOOLBAR) return;
        if (item.type == FUNCTIONS || item.type == HISTORY) {
            HomeRecyclerView row = (HomeRecyclerView) holder.itemView;
            if (holder.rowAdapter == null || holder.rowAdapter.presenter != item.presenter) {
                holder.rowAdapter = new RowAdapter(item.presenter, item.type == HISTORY);
                row.setAdapter(holder.rowAdapter);
            }
            RowAdapter adapter = holder.rowAdapter;
            adapter.submitList(new ArrayList<>((List<Object>) item.value), () -> {
                // HistoryPresenter deletion mode is presentation state, not a History field.
                if (item.type == HISTORY) adapter.notifyItemRangeChanged(0, adapter.getItemCount());
            });
        } else if (item.type == SECTION) {
            android.widget.TextView text = (android.widget.TextView) holder.itemView;
            int resId = (int) item.value;
            text.setText(resId);
            text.setOnClickListener(v -> headerListener.onHeaderClick(resId));
            // Deliberately no HeaderPresenter's repeating flicker animation.
        } else {
            holder.presenter.onBindViewHolder(holder.presented, item.value);
        }
    }

    @Override public void onViewRecycled(@NonNull Holder holder) {
        if (holder.presenter != null) holder.presenter.onUnbindViewHolder(holder.presented);
        resetScale(holder.itemView);
        super.onViewRecycled(holder);
    }

    public RecyclerView.ItemDecoration spacing() {
        return new RecyclerView.ItemDecoration() {
            @Override public void getItemOffsets(@NonNull Rect out, @NonNull View view, @NonNull RecyclerView rv, @NonNull RecyclerView.State state) {
                int position = rv.getChildAdapterPosition(view);
                if (position == RecyclerView.NO_POSITION) return;
                int gap = ResUtil.dp2px(16);
                if (!isFullSpan(position) && view.getLayoutParams() instanceof GridLayoutManager.LayoutParams params) {
                    int column = params.getSpanIndex();
                    int columns = ((GridLayoutManager) rv.getLayoutManager()).getSpanCount();
                    out.left = column * gap / columns;
                    out.right = gap - (column + 1) * gap / columns;
                }
                // Toolbar and entry row touch just as before; all content rows have 16dp gaps.
                if (position != 0) out.bottom = gap;
            }
        };
    }

    private static void installCardFocus(View view) {
        view.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate().scaleX(focused ? 1.04f : 1f).scaleY(focused ? 1.04f : 1f).setDuration(120).start();
            v.setTranslationZ(focused ? ResUtil.dp2px(4) : 0f);
        });
    }

    private static void resetScale(View view) {
        view.animate().cancel();
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationZ(0f);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final Presenter presenter;
        final Presenter.ViewHolder presented;
        RowAdapter rowAdapter;
        Holder(View view, Presenter presenter, Presenter.ViewHolder presented) {
            super(view);
            this.presenter = presenter;
            this.presented = presented;
        }
    }

    /** Existing presenters only inflate/bind cards; no Leanback GridView or selection controller. */
    private static final class RowAdapter extends ListAdapter<Object, Holder> {
        final Presenter presenter;
        final boolean scale;
        @SuppressWarnings({"rawtypes", "unchecked"})
        RowAdapter(Presenter presenter, boolean scale) {
            super(new DiffUtil.ItemCallback<>() {
                @Override public boolean areItemsTheSame(@NonNull Object a, @NonNull Object b) {
                    return a instanceof Diffable && a.getClass() == b.getClass() ? ((Diffable) a).isSameItem(b) : a.equals(b);
                }
                @Override public boolean areContentsTheSame(@NonNull Object a, @NonNull Object b) {
                    return a instanceof Diffable && a.getClass() == b.getClass() ? ((Diffable) a).isSameContent(b) : a.equals(b);
                }
            });
            this.presenter = presenter;
            this.scale = scale;
            setStateRestorationPolicy(StateRestorationPolicy.PREVENT_WHEN_EMPTY);
        }
        @NonNull @Override public Holder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            Presenter.ViewHolder view = presenter.onCreateViewHolder(parent);
            // Native Android first-tap focus handling must not swallow the first mouse click.
            view.view.setFocusableInTouchMode(false);
            if (scale) installCardFocus(view.view);
            return new Holder(view.view, presenter, view);
        }
        @Override public void onBindViewHolder(@NonNull Holder holder, int position) { presenter.onBindViewHolder(holder.presented, getItem(position)); }
        @Override public void onViewRecycled(@NonNull Holder holder) {
            presenter.onUnbindViewHolder(holder.presented);
            resetScale(holder.itemView);
            super.onViewRecycled(holder);
        }
    }
}
