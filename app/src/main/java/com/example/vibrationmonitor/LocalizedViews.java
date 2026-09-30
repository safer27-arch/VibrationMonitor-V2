package com.example.vibrationmonitor;

import android.content.Context;
import android.widget.*;
import android.view.*;
import android.app.AlertDialog;
import android.content.DialogInterface;
import java.util.List;

// These are constructor replacements at UI boundaries, NOT replacements of Java identifiers inside text.
class LocalizedTextView extends TextView implements AppLanguage.Listener {
    private CharSequence source;
    LocalizedTextView(Context c){super(c);AppLanguage.init(c);AppLanguage.register(this);if(source==null)source=super.getText();languageChanged();}
    @Override public void setText(CharSequence s,BufferType type){source=s;super.setText(AppLanguage.text(s),type);}
    @Override public void languageChanged(){super.setText(AppLanguage.text(source),BufferType.NORMAL);}
}
class LocalizedButton extends Button implements AppLanguage.Listener {
    private CharSequence source;
    LocalizedButton(Context c){super(c);AppLanguage.init(c);AppLanguage.register(this);if(source==null)source=super.getText();languageChanged();}
    @Override public void setText(CharSequence s,BufferType type){source=s;super.setText(AppLanguage.text(s),type);}
    @Override public void languageChanged(){super.setText(AppLanguage.text(source),BufferType.NORMAL);}
}
class LocalizedCheckBox extends CheckBox implements AppLanguage.Listener {
    private CharSequence source;
    LocalizedCheckBox(Context c){super(c);AppLanguage.init(c);AppLanguage.register(this);if(source==null)source=super.getText();languageChanged();}
    @Override public void setText(CharSequence s,BufferType type){source=s;super.setText(AppLanguage.text(s),type);}
    @Override public void languageChanged(){super.setText(AppLanguage.text(source),BufferType.NORMAL);}
}
class LocalizedEditText extends EditText implements AppLanguage.Listener {
    private CharSequence sourceHint;
    LocalizedEditText(Context c){super(c);AppLanguage.init(c);AppLanguage.register(this);}
    // setHint is final in TextView; installer calls rememberHint instead, retaining user-entered text verbatim.
    public void rememberHint(CharSequence s){sourceHint=s;super.setHint(AppLanguage.text(s));}
    @Override public void languageChanged(){if(sourceHint!=null)super.setHint(AppLanguage.text(sourceHint));}
}
class LocalizedArrayAdapter<T> extends ArrayAdapter<T> implements AppLanguage.Listener {
    LocalizedArrayAdapter(Context c,int layout,T[] list){super(c,layout,list);AppLanguage.init(c);AppLanguage.register(this);}
    LocalizedArrayAdapter(Context c,int layout,List<T> list){super(c,layout,list);AppLanguage.init(c);AppLanguage.register(this);}
    @Override public View getView(int position,View reusable,ViewGroup parent){View v=super.getView(position,reusable,parent);translate(v,position);return v;}
    @Override public View getDropDownView(int position,View reusable,ViewGroup parent){View v=super.getDropDownView(position,reusable,parent);translate(v,position);return v;}
    private void translate(View v,int pos){if(v instanceof TextView)((TextView)v).setText(AppLanguage.text(String.valueOf(getItem(pos))));}
    @Override public void languageChanged(){notifyDataSetChanged();}
}
class LocalizedDialog extends AlertDialog.Builder {
    LocalizedDialog(Context c){super(c);AppLanguage.init(c);}
    @Override public AlertDialog.Builder setTitle(CharSequence s){return super.setTitle(AppLanguage.text(s));}
    @Override public AlertDialog.Builder setMessage(CharSequence s){return super.setMessage(AppLanguage.text(s));}
    @Override public AlertDialog.Builder setPositiveButton(CharSequence s,DialogInterface.OnClickListener l){return super.setPositiveButton(AppLanguage.text(s),l);}
    @Override public AlertDialog.Builder setNegativeButton(CharSequence s,DialogInterface.OnClickListener l){return super.setNegativeButton(AppLanguage.text(s),l);}
    @Override public AlertDialog.Builder setNeutralButton(CharSequence s,DialogInterface.OnClickListener l){return super.setNeutralButton(AppLanguage.text(s),l);}
    @Override public AlertDialog.Builder setItems(CharSequence[] s,DialogInterface.OnClickListener l){CharSequence[] a=new CharSequence[s.length];for(int i=0;i<a.length;i++)a[i]=AppLanguage.text(s[i]);return super.setItems(a,l);}
}
class LocalizedToast {
    static Toast makeText(Context c,CharSequence s,int duration){AppLanguage.init(c);return Toast.makeText(c,AppLanguage.text(s),duration);}
    static Toast makeText(Context c,int res,int duration){return makeText(c,c.getText(res),duration);}
}

class UiHints {
    static void set(TextView view,CharSequence hint){if(view instanceof LocalizedEditText)((LocalizedEditText)view).rememberHint(hint);else view.setHint(AppLanguage.text(hint));}
    static void set(TextView view,int resource){set(view,view.getContext().getText(resource));}
}
