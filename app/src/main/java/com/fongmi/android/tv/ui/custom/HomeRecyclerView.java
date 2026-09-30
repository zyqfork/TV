package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Home layout name retained for compatibility; behaviour lives in the shared native list. */
public class HomeRecyclerView extends PointerRecyclerView {
    public HomeRecyclerView(@NonNull Context context) { super(context); }
    public HomeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); }
    public HomeRecyclerView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }
}
