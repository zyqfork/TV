package com.fongmi.android.tv.ui.custom;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Result;

/** One pagination policy for touch, wheel and D-pad scrolling in both flavours. */
public class CustomScroller extends RecyclerView.OnScrollListener {
    private final Callback callback;
    private boolean loading;
    private boolean enable = true;
    private int page = 1;
    private int generation;

    public CustomScroller(Callback callback) { this.callback = callback; }

    @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
        // Wheel and focus scrolling commonly leave RecyclerView in IDLE.
        if (dy > 0) schedule(view);
    }
    @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
        if (state == RecyclerView.SCROLL_STATE_IDLE) schedule(view);
    }
    private void schedule(RecyclerView view) {
        int expected = generation;
        view.post(() -> { if (expected == generation) checkMore(view); });
    }
    public void checkMore(RecyclerView view) {
        if (view == null || view.getAdapter() == null || view.getAdapter().getItemCount() == 0
                || view.canScrollVertically(1)) return;
        loadMore();
    }
    public void checkMore() { loadMore(); }

    private void loadMore() {
        if (!enable || loading || callback == null) return;
        int previous = page;
        page++;
        loading = true;
        // State precedes the callback: cached/synchronous results must see the correct page.
        try {
            if (!callback.onLoadMore(String.valueOf(page))) { page = previous; loading = false; }
        } catch (RuntimeException error) {
            page = previous;
            loading = false;
            throw error;
        }
    }
    public void reset() { generation++; loading = false; enable = true; page = 1; }
    public void beginLoading() { loading = true; }
    public boolean first() { return page == 1; }
    public void setPage(int value) { generation++; page = Math.max(1, value); loading = false; enable = true; }
    public boolean isLoading() { return loading; }
    public boolean isDisable() { return !enable; }
    public void setEnable(int pageCount) { enable = pageCount == 0 || page < pageCount; }
    public void endLoading(Result result) {
        if (result.getList().isEmpty()) {
            page = Math.max(1, page - 1);
            enable = false;
        } else setEnable(result.getPageCount());
        loading = false;
    }
    public void endLoading(boolean hasMore) { enable = hasMore; loading = false; }
    public interface Callback { boolean onLoadMore(String page); }
}
