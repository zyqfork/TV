package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Product;

/** Size-constrained native list used by dialogs. Scrolling never implicitly changes focus. */
public class CustomRecyclerView extends PointerRecyclerView {
    private int minWidth, minHeight, maxWidth, maxHeight;

    public CustomRecyclerView(@NonNull Context context) { this(context, null); }
    public CustomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }
    public CustomRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        if (attrs == null) return;
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CustomRecyclerView);
        minWidth = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minWidth, 0);
        minHeight = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_android_minHeight, 0);
        maxWidth = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxWidth, 0);
        maxHeight = a.getDimensionPixelSize(R.styleable.CustomRecyclerView_maxHeight, 0);
        a.recycle();
    }

    public void setMinWidth(int value) { minWidth = value; requestLayout(); }
    public void setMaxWidth(int value) { maxWidth = value; requestLayout(); }
    public void setMinHeight(int value) { minHeight = value; requestLayout(); }
    public void setMaxHeight(int value) { maxHeight = value; requestLayout(); }

    /** Explicit initial dialog selection: TV focuses it, phone only scrolls it into view. */
    public void scrollToSelection(int position) {
        if (Product.getDeviceType() == 0) scrollToPositionAndFocus(position);
        else scrollToPosition(position);
    }

    private int boundedSpec(int spec, int max) {
        int mode = MeasureSpec.getMode(spec), size = MeasureSpec.getSize(spec);
        if (max <= 0 || mode == MeasureSpec.EXACTLY) return spec;
        return MeasureSpec.makeMeasureSpec(mode == MeasureSpec.UNSPECIFIED ? max : Math.min(size, max), MeasureSpec.AT_MOST);
    }

    private int minimum(int measured, int min, int spec) {
        if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) return MeasureSpec.getSize(spec);
        int value = Math.max(measured, min);
        return MeasureSpec.getMode(spec) == MeasureSpec.AT_MOST ? Math.min(value, MeasureSpec.getSize(spec)) : value;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = boundedSpec(widthSpec, maxWidth), height = boundedSpec(heightSpec, maxHeight);
        super.onMeasure(width, height);
        int finalWidth = minimum(getMeasuredWidth(), minWidth, width);
        int finalHeight = minimum(getMeasuredHeight(), minHeight, height);
        // Re-measure so LayoutManager and children see the same size as the container.
        if (finalWidth != getMeasuredWidth() || finalHeight != getMeasuredHeight()) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(finalWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(finalHeight, MeasureSpec.EXACTLY));
        }
    }
}
