#!/usr/bin/env python3
"""Execute the production live key handler against Android input doubles (not device evidence)."""
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as tmp:
 p=Path(tmp)
 def w(name,s):
  q=p/name;q.parent.mkdir(parents=True,exist_ok=True);q.write_text(s,encoding='utf-8')
 keys=['DPAD_CENTER','ENTER','SPACE','NUMPAD_ENTER','DPAD_UP','CHANNEL_UP','PAGE_UP','MEDIA_PREVIOUS','DPAD_DOWN','CHANNEL_DOWN','PAGE_DOWN','MEDIA_NEXT','DPAD_LEFT','DPAD_RIGHT','BACK','MENU','MEDIA_PLAY_PAUSE','MEDIA_PLAY','MEDIA_PAUSE','MEDIA_STOP','MEDIA_REWIND','MEDIA_FAST_FORWARD']
 constants=''.join('public static final int KEYCODE_'+k+'='+str(i+20)+';' for i,k in enumerate(keys))
 w('android/view/KeyEvent.java','package android.view; public class KeyEvent {'+constants+'public static final int ACTION_DOWN=0,ACTION_UP=1,KEYCODE_0=7,KEYCODE_9=16,KEYCODE_NUMPAD_0=144,KEYCODE_NUMPAD_9=153;int a,k,r;boolean l;public KeyEvent(int a,int k,int r,boolean l){this.a=a;this.k=k;this.r=r;this.l=l;}public int getAction(){return a;}public int getKeyCode(){return k;}public int getRepeatCount(){return r;}public long getDownTime(){return 0;}public long getEventTime(){return r*100;}public boolean isLongPress(){return l;}}')
 w('android/content/Context.java','package android.content;public class Context {}')
 w('android/view/MotionEvent.java','package android.view;public class MotionEvent {}')
 w('android/view/GestureDetector.java','package android.view;import android.content.Context; public class GestureDetector {public GestureDetector(Context c,SimpleOnGestureListener l){}public boolean onTouchEvent(MotionEvent e){return true;}public static class SimpleOnGestureListener {public boolean onDoubleTap(MotionEvent e){return false;}public boolean onSingleTapConfirmed(MotionEvent e){return false;}}}')
 w('androidx/annotation/NonNull.java','package androidx.annotation;public @interface NonNull {}')
 w('com/fongmi/android/tv/App.java','package com.fongmi.android.tv;public class App {public static void post(Runnable r,long d){}}')
 w('com/fongmi/android/tv/Constant.java','package com.fongmi.android.tv;public class Constant {public static final long INTERVAL_SEEK=1000;}')
 w('Probe.java',r'''import android.content.Context;import android.view.KeyEvent;import com.fongmi.android.tv.ui.custom.CustomKeyDownLive;
public class Probe extends Context implements CustomKeyDownLive.Listener {
 boolean fullscreen=true;int toggles,lists,plays,pauses;public boolean dispatch(boolean b){return fullscreen;}
 public void onShow(String s){}public void onFind(String s){}public void onSeeking(long t){}public void onKeyUp(){}public void onKeyDown(){}public void onKeyLeft(long t){}public void onKeyRight(long t){}public void onMenu(){}public void onSingleTap(){}public void onDoubleTap(){}
 public void onKeyCenter(){toggles++;}public void onPlayPause(){toggles++;}public void onMediaPlay(boolean b){if(b)plays++;else pauses++;}public void onChannelList(){lists++;fullscreen=false;}
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static KeyEvent e(int a,int k,int r,boolean l){return new KeyEvent(a,k,r,l);}
 public static void main(String[] args){Probe p=new Probe();CustomKeyDownLive h=CustomKeyDownLive.create(p);
 int ok=KeyEvent.KEYCODE_DPAD_CENTER;
 check(h.onKeyDown(e(0,ok,0,false)),"center DOWN leaked");h.onKeyDown(e(0,ok,3,false));h.onKeyDown(e(1,ok,0,false));check(p.toggles==1,"short center not single toggle");
 h.onKeyDown(e(0,ok,0,false));h.onKeyDown(e(0,ok,1,true));h.onKeyDown(e(0,ok,2,true));check(h.onKeyDown(e(1,ok,0,false)),"long-release selected channel");check(p.lists==1&&p.toggles==1,"long press toggled or repeated");
 check(!h.onKeyDown(e(0,ok,0,false))&&!h.onKeyDown(e(1,ok,0,false)),"channel list center swallowed");
 for(int k:new int[]{KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE}){
 check(h.onKeyDown(e(0,k,0,false)),"media DOWN leaked to MediaSession");h.onKeyDown(e(0,k,2,false));check(h.onKeyDown(e(1,k,0,false)),"media UP leaked");}
 check(p.plays==1&&p.pauses==1&&p.toggles==2,"media semantics/double dispatch");
 p.fullscreen=true;h.onKeyDown(e(0,ok,0,false));h.onKeyDown(e(0,ok,6,false));h.onKeyDown(e(1,ok,0,false));check(p.lists==2&&p.toggles==2,"unflagged hardware/shell long press failed");
 System.out.println("PASS production live remote: single consumed toggle, explicit play/pause, repeats, long-center channel list and release isolation, list selection passthrough");}}
''')
 java=[str(x) for x in p.rglob('*.java')]+[str(ROOT/'app/src/main/java/com/fongmi/android/tv/utils/KeyUtil.java'),str(ROOT/'app/src/leanback/java/com/fongmi/android/tv/ui/custom/CustomKeyDownLive.java')]
 subprocess.run(['javac','-encoding','UTF-8','-d',tmp,*java],check=True);subprocess.run(['java','-cp',tmp,'Probe'],check=True)
activity=(ROOT/'app/src/leanback/java/com/fongmi/android/tv/ui/activity/LiveActivity.java').read_text(encoding='utf-8')
assert '&& mKeyDown.onKeyDown(event)) return true;' in activity
assert 'controller().setPlayWhenReady(!controller().getPlayWhenReady())' in activity
print('PASS STATIC Activity consumes handler-owned events and toggles intent, not isPlaying')
