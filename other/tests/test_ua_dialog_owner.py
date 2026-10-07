"""Full production mobile UA dialog + owner/view doubles. No Android keyboard/TV QR emulation."""
from pathlib import Path
from tempfile import TemporaryDirectory
import subprocess
ROOT=Path(__file__).resolve().parents[2]
STUBS={
'android/content/DialogInterface.java':'package android.content;public interface DialogInterface {interface OnClickListener {void onClick(DialogInterface d,int w);}}',
'android/text/TextUtils.java':'package android.text;public class TextUtils {public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;}}',
'android/view/inputmethod/EditorInfo.java':'package android.view.inputmethod;public class EditorInfo {public static int IME_ACTION_DONE=6;}',
'androidx/viewbinding/ViewBinding.java':'package androidx.viewbinding;public interface ViewBinding {Object getRoot();}',
'androidx/fragment/app/FragmentManager.java':'package androidx.fragment.app;public class FragmentManager {}',
'androidx/fragment/app/Fragment.java':'package androidx.fragment.app;public class Fragment {public FragmentManager getChildFragmentManager(){return new FragmentManager();}}',
'androidx/fragment/app/FragmentActivity.java':'package androidx.fragment.app;public class FragmentActivity {public FragmentManager getSupportFragmentManager(){return new FragmentManager();}}',
'com/fongmi/android/tv/R.java':'package com.fongmi.android.tv;public class R {public static class string {public static int player_ua=1,dialog_positive=2,dialog_negative=3;}}',
'com/fongmi/android/tv/setting/Setting.java':'package com.fongmi.android.tv.setting;public class Setting {public static String value="original";public static String getUa(){return value;}}',
'com/github/catvod/utils/Util.java':'package com.github.catvod.utils;public class Util {public static String CHROME="chrome-fixture",OKHTTP="okhttp-fixture";}',
'com/fongmi/android/tv/ui/custom/CustomTextListener.java':'package com.fongmi.android.tv.ui.custom;public class CustomTextListener {public void onTextChanged(CharSequence s,int a,int b,int c){}}',
'com/fongmi/android/tv/databinding/DialogUaBinding.java':'''package com.fongmi.android.tv.databinding;public class DialogUaBinding implements androidx.viewbinding.ViewBinding {public Edit text=new Edit();public static DialogUaBinding inflate(Object inflater){return new DialogUaBinding();}public Object getRoot(){return this;}public static class Edit {String value="";public void setText(String s){value=s;}public String getText(){return value;}public void setSelection(int i){}public void addTextChangedListener(com.fongmi.android.tv.ui.custom.CustomTextListener l){}public void setOnEditorActionListener(Action l){} }public interface Action {boolean action(Edit e,int id,Object event);}}''',
'com/google/android/material/dialog/MaterialAlertDialogBuilder.java':'''package com.google.android.material.dialog;public class MaterialAlertDialogBuilder {public MaterialAlertDialogBuilder setTitle(int i){return this;}public MaterialAlertDialogBuilder setView(Object v){return this;}public MaterialAlertDialogBuilder setPositiveButton(int i,android.content.DialogInterface.OnClickListener c){return this;}public MaterialAlertDialogBuilder setNegativeButton(int i,android.content.DialogInterface.OnClickListener c){return this;}}''',
'com/fongmi/android/tv/ui/dialog/BaseAlertDialog.java':'''package com.fongmi.android.tv.ui.dialog;public abstract class BaseAlertDialog {public androidx.fragment.app.Fragment parent;public androidx.fragment.app.FragmentActivity owner;public boolean dismissed;public static androidx.fragment.app.FragmentManager shown;protected abstract androidx.viewbinding.ViewBinding getBinding();protected abstract com.google.android.material.dialog.MaterialAlertDialogBuilder getBuilder();protected abstract void initView();protected abstract void initEvent();public Object getLayoutInflater(){return null;}public com.google.android.material.dialog.MaterialAlertDialogBuilder builder(){return new com.google.android.material.dialog.MaterialAlertDialogBuilder();}public androidx.fragment.app.Fragment getParentFragment(){return parent;}public androidx.fragment.app.FragmentActivity requireActivity(){return owner;}public void dismiss(){dismissed=true;}public void show(androidx.fragment.app.FragmentManager m,Object tag){shown=m;}}''',
'UaProbe.java':'''import com.fongmi.android.tv.ui.dialog.*;import com.fongmi.android.tv.impl.UaListener;import com.fongmi.android.tv.databinding.DialogUaBinding;import java.lang.reflect.*;
public class UaProbe {
 static class ActivityOwner extends androidx.fragment.app.FragmentActivity implements UaListener {String saved;public void setUa(String s){saved=s;}}
 static class FragmentOwner extends androidx.fragment.app.Fragment implements UaListener {String saved;public void setUa(String s){saved=s;}}
 static void invoke(UaDialog d,String name,Class<?>[] types,Object... values)throws Exception{Method m=UaDialog.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(d,values);}
 static DialogUaBinding binding(UaDialog d)throws Exception {invoke(d,"getBinding",new Class<?>[0]);Field f=UaDialog.class.getDeclaredField("binding");f.setAccessible(true);return (DialogUaBinding)f.get(d);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args)throws Exception {
  var a=new ActivityOwner();var d=new UaDialog();d.owner=a;var b=binding(d);invoke(d,"initView",new Class<?>[0]);check(b.text.getText().equals("original"),"existing UA lost on open");b.text.setText("  owned UA  ");invoke(d,"onPositive",new Class<?>[]{android.content.DialogInterface.class,int.class},null,0);check(a.saved.equals("owned UA")&&d.dismissed,"activity-owned UA cannot save/trim/dismiss");
  var f=new FragmentOwner();d=new UaDialog();d.owner=a;d.parent=f;b=binding(d);b.text.setText("");invoke(d,"onPositive",new Class<?>[]{android.content.DialogInterface.class,int.class},null,0);check(f.saved.equals(""),"legacy fragment owner or resetting UA broken");
  d=new UaDialog();b=binding(d);invoke(d,"detect",new Class<?>[]{String.class},"c");check(b.text.getText().equals("chrome-fixture"),"Chrome shortcut lost");invoke(d,"detect",new Class<?>[]{String.class},"");invoke(d,"detect",new Class<?>[]{String.class},"o");check(b.text.getText().equals("okhttp-fixture"),"OkHttp shortcut lost");
  UaDialog.show(a);check(BaseAlertDialog.shown!=null,"activity launch unsupported");UaDialog.show(f);check(BaseAlertDialog.shown!=null,"legacy fragment launch unsupported");
  System.out.println("PASS production mobile UA dialog: activity/fragment owner, existing UA, trim/reset/save/dismiss, Chrome/OkHttp shortcuts, both launch overloads");
 }
}'''
}
def main():
 with TemporaryDirectory() as tmp:
  folder=Path(tmp);src=[ROOT/'app/src/mobile/java/com/fongmi/android/tv/ui/dialog/UaDialog.java',ROOT/'app/src/main/java/com/fongmi/android/tv/impl/UaListener.java']
  for name,body in STUBS.items():
   p=folder/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(body,encoding='utf-8');src.append(p)
  subprocess.run(['javac','-encoding','UTF-8','-d',str(folder/'classes'),*map(str,src)],check=True)
  subprocess.run(['java','-cp',str(folder/'classes'),'UaProbe'],check=True)
if __name__=='__main__':main()
