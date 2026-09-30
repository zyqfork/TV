#!/usr/bin/env python3
"""JVM contract tests of production pagination/diff code using small AndroidX scheduling stubs.
Not an Android rendering test; device regressions cover actual RecyclerView behaviour.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
STUBS = {
'androidx/annotation/NonNull.java': 'package androidx.annotation; public @interface NonNull {}',
'android/view/ViewGroup.java': 'package android.view; public class ViewGroup {}',
'android/os/SystemClock.java': 'package android.os; public class SystemClock {public static long time; public static long uptimeMillis(){return time;}}',
'androidx/leanback/widget/Presenter.java': 'package androidx.leanback.widget; public class Presenter {}',
'androidx/recyclerview/widget/DiffUtil.java': 'package androidx.recyclerview.widget; public class DiffUtil {public abstract static class ItemCallback<T>{public abstract boolean areItemsTheSame(T a,T b);public abstract boolean areContentsTheSame(T a,T b);}}',
'androidx/recyclerview/widget/ListAdapter.java': '''package androidx.recyclerview.widget; import java.util.*;
public abstract class ListAdapter<T,VH extends RecyclerView.ViewHolder> extends RecyclerView.Adapter<VH> {
 protected List<T> current=List.of(),pending=List.of(); public ListAdapter(DiffUtil.ItemCallback<T> c){}
 public List<T> getCurrentList(){return current;}public void submitList(List<T> v){pending=v;}
 public void flush(){List<T> old=current;current=pending;onCurrentListChanged(old,current);}
 public void onCurrentListChanged(List<T> old,List<T> next){}public int getItemCount(){return current.size();}
}''',
'androidx/recyclerview/widget/RecyclerView.java': '''package androidx.recyclerview.widget;
import java.util.*; import android.view.ViewGroup;
public class RecyclerView {
 public static final int SCROLL_STATE_IDLE=0,NO_POSITION=-1;
 public boolean more; public List<Runnable> tasks=new ArrayList<>(); public Adapter<?> adapter;
 public boolean canScrollVertically(int d){return more;} public Adapter<?> getAdapter(){return adapter;}
 public void post(Runnable r){tasks.add(r);} public void flush(){List<Runnable> t=new ArrayList<>(tasks);tasks.clear();t.forEach(Runnable::run);}
 public static class ViewHolder {}
 public static class OnScrollListener {public void onScrolled(RecyclerView v,int x,int y){} public void onScrollStateChanged(RecyclerView v,int s){}}
 public static abstract class Adapter<VH extends ViewHolder> {public void setHasStableIds(boolean b){}public abstract int getItemCount(); public abstract VH onCreateViewHolder(ViewGroup p,int t); public abstract void onBindViewHolder(VH h,int p);}
}''',
'androidx/recyclerview/widget/AsyncListDiffer.java': '''package androidx.recyclerview.widget; import java.util.*;
public class AsyncListDiffer<T> {
 private List<T> current=List.of(); private List<Runnable> tasks=new ArrayList<>(); private int generation;
 public AsyncListDiffer(RecyclerView.Adapter<?> a,Object c){} public List<T> getCurrentList(){return current;}
 public void submitList(List<T> list,Runnable done){int g=++generation;tasks.add(()->{if(g!=generation)return;current=list;if(done!=null)done.run();});}
 public void flush(){List<Runnable> t=new ArrayList<>(tasks);tasks.clear();t.forEach(Runnable::run);}
}''',
'com/fongmi/android/tv/ui/adapter/BaseItemCallback.java': 'package com.fongmi.android.tv.ui.adapter; public class BaseItemCallback<T> {}',
'com/fongmi/android/tv/bean/Result.java': '''package com.fongmi.android.tv.bean; import java.util.*;
public class Result {public boolean empty;public int count; public List<String> getList(){return empty?List.of():List.of("item");}public int getPageCount(){return count;}}''',
'Contracts.java': '''import java.util.*; import android.view.ViewGroup; import androidx.recyclerview.widget.RecyclerView;
import com.fongmi.android.tv.ui.custom.CustomScroller; import com.fongmi.android.tv.ui.adapter.BaseDiffAdapter;
import com.fongmi.android.tv.impl.Diffable; import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.ui.adapter.PresenterGridAdapter;import androidx.recyclerview.widget.DiffUtil;
public class Contracts {
 record Item(int id) implements Diffable<Item> {public boolean isSameItem(Item b){return id==b.id;}public boolean isSameContent(Item b){return id==b.id;}}
 static class Adapter extends BaseDiffAdapter<Item,RecyclerView.ViewHolder> {
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p,int t){return new RecyclerView.ViewHolder();}public void onBindViewHolder(RecyclerView.ViewHolder h,int p){}
  void flush(){differ.flush();}
 }
 static class Grid extends PresenterGridAdapter<String,RecyclerView.ViewHolder> {
  Grid(){super(new DiffUtil.ItemCallback<>(){public boolean areItemsTheSame(String a,String b){return a.equals(b);}public boolean areContentsTheSame(String a,String b){return a.equals(b);}},1);}
  protected String itemKey(String s){return s;}protected String cardLookupKey(String s){return s;}
  void set(String... items){submitGridList(List.of(items));}long id(String s){return stableId(s);}int pos(String s){return positionOfKey(s);}
  public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup p,int t){return new RecyclerView.ViewHolder();}public void onBindViewHolder(RecyclerView.ViewHolder h,int p){}
 }
 static Result result(int count,boolean empty){Result r=new Result();r.count=count;r.empty=empty;return r;}
 public static void main(String[] args){
  Adapter a=new Adapter(); a.add(new Item(1)); a.add(new Item(2)); a.flush(); assert a.getItemCount()==2:"concurrent appends lost data";
  a.remove(new Item(1)); a.add(new Item(3)); a.flush(); assert a.getItems().equals(List.of(new Item(2),new Item(3)));
  a.setItems(List.of(new Item(4))); a.setItems(List.of(new Item(2),new Item(3)), changed->{assert !changed;}); a.flush(); assert a.getItems().equals(List.of(new Item(2),new Item(3))):"equal replacement failed to cancel older diff";
  List<String> pages=new ArrayList<>(); CustomScroller s=new CustomScroller(p->{pages.add(p);return true;});
  RecyclerView v=new RecyclerView();v.adapter=a;
  s.onScrolled(v,0,128);v.flush(); assert pages.equals(List.of("2")):"idle wheel did not paginate";
  s.checkMore(v);assert pages.size()==1:"duplicate request";
  s.endLoading(result(2,false));s.checkMore(v);assert pages.size()==1:"past final page";
  s.reset();s.endLoading(result(0,true));s.checkMore(v);assert pages.size()==1:"empty unknown-page result looped";
  s.setPage(1);s.checkMore(v);assert pages.size()==2:"new source inherited disabled state";
  s.reset();s.onScrolled(v,0,1);s.reset();v.flush();assert pages.size()==2:"stale scroll after refresh";
  CustomScroller[] sync=new CustomScroller[1];sync[0]=new CustomScroller(p->{assert !sync[0].first();sync[0].endLoading(result(3,false));return true;});sync[0].checkMore(v);assert !sync[0].isLoading():"synchronous result overwritten";
  CustomScroller declined=new CustomScroller(p->false);declined.checkMore(v);assert declined.first()&&!declined.isLoading();
  List<String> heldPages=new ArrayList<>();CustomScroller held=new CustomScroller(p->{heldPages.add(p);return true;});held.checkMore();android.os.SystemClock.time=60000;held.checkMore();assert heldPages.equals(List.of("2")):"timeout skipped an unreceived page";
  CustomScroller first=new CustomScroller(p->{throw new AssertionError("first page skipped");});first.beginLoading();android.os.SystemClock.time=120000;first.checkMore();assert first.first()&&first.isLoading();
  Grid grid=new Grid();grid.set("A");grid.flush();long id=grid.id("A");grid.set("B");grid.set("C");assert grid.id("A")==id:"accepted ID pruned by overlapping submits";
  grid.set("A","B");grid.flush();grid.set("B");assert grid.pos("B")==1:"index described pending rather than displayed data";grid.flush();assert grid.pos("B")==0&&grid.pos("A")==-1;
  System.out.println("PASS diff snapshots / wheel paging / dedup / timeout no-skip / accepted IDs / accepted positions / reset / sync callback");
 }
}'''
}
with tempfile.TemporaryDirectory(prefix='native-lists-') as folder:
    root = Path(folder)
    for name, text in STUBS.items():
        file = root / name; file.parent.mkdir(parents=True, exist_ok=True); file.write_text(text, encoding='utf-8')
    sources = [str(p) for p in root.rglob('*.java')]
    sources += [str(ROOT / p) for p in ('app/src/main/java/com/fongmi/android/tv/ui/custom/CustomScroller.java',
        'app/src/main/java/com/fongmi/android/tv/ui/adapter/BaseDiffAdapter.java', 'app/src/main/java/com/fongmi/android/tv/impl/Diffable.java',
        'app/src/leanback/java/com/fongmi/android/tv/ui/adapter/PresenterGridAdapter.java')]
    subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8', '-d', str(root / 'classes'), *sources], check=True)
    subprocess.run(['java', '-ea', '-cp', str(root / 'classes'), 'Contracts'], check=True)
