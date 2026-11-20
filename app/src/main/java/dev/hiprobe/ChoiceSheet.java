package dev.hiprobe;

import android.app.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.IntConsumer;

/** Searchable selection sheet; filtered results keep their original catalog indices. */
final class ChoiceSheet {
    static void show(Activity activity,String title,List<String> choices,int selected,IntConsumer choose){
        if(choices.isEmpty())return;
        float density=activity.getResources().getDisplayMetrics().density;
        int pad=Math.round(20*density);
        Dialog dialog=new Dialog(activity);
        LinearLayout root=new LinearLayout(activity);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(pad,pad,pad,pad);
        GradientDrawable background=new GradientDrawable();background.setColor(MainActivity.PAPER);background.setCornerRadius(28*density);root.setBackground(background);
        LinearLayout heading=new LinearLayout(activity);heading.setGravity(Gravity.CENTER_VERTICAL);root.addView(heading);
        TextView name=new TextView(activity);name.setText(title);name.setTextColor(MainActivity.INK);name.setTextSize(23);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.addView(name,new LinearLayout.LayoutParams(0,Math.round(56*density),1));name.setGravity(Gravity.CENTER_VERTICAL);
        TextView close=new TextView(activity);close.setText("×");close.setTextSize(26);close.setTextColor(MainActivity.INK);close.setGravity(Gravity.CENTER);close.setContentDescription("Close "+title);heading.addView(close,new LinearLayout.LayoutParams(Math.round(48*density),Math.round(48*density)));close.setOnClickListener(v->dialog.dismiss());
        EditText search=new EditText(activity);search.setSingleLine(true);search.setTextSize(16);search.setHint("Search");search.setTextColor(MainActivity.INK);search.setHintTextColor(MainActivity.MUTED);root.addView(search,new LinearLayout.LayoutParams(-1,Math.round(52*density)));search.setVisibility(choices.size()>7?View.VISIBLE:View.GONE);
        ArrayList<Integer> indices=new ArrayList<>();ArrayList<String> visible=new ArrayList<>();
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_single_choice,visible){
            @Override public View getView(int position,View convert,ViewGroup parent){TextView row=(TextView)super.getView(position,convert,parent);row.setTextColor(MainActivity.INK);row.setTextSize(16);row.setMinHeight(Math.round(56*density));return row;}
        };
        TextView empty=new TextView(activity);empty.setText("No matches");empty.setTextColor(MainActivity.MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(0,pad,0,pad);root.addView(empty);
        ListView list=new ListView(activity);list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);list.setDividerHeight(0);list.setAdapter(adapter);list.setEmptyView(empty);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        Runnable filter=()->{indices.clear();visible.clear();String query=search.getText().toString().trim().toLowerCase(Locale.ROOT);list.clearChoices();for(int i=0;i<choices.size();i++)if(choices.get(i).toLowerCase(Locale.ROOT).contains(query)){indices.add(i);visible.add(choices.get(i));}adapter.notifyDataSetChanged();int checked=indices.indexOf(selected);if(checked>=0)list.setItemChecked(checked,true);};
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){filter.run();}public void afterTextChanged(Editable e){}});
        list.setOnItemClickListener((parent,view,position,id)->{int original=indices.get(position);dialog.dismiss();choose.accept(original);});
        dialog.setContentView(root);Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.setGravity(Gravity.BOTTOM);window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);window.setWindowAnimations(R.style.VoiceSheetAnimation);}
        dialog.show();if(window!=null)window.setLayout(-1,(int)(activity.getResources().getDisplayMetrics().heightPixels*.72));filter.run();
    }
}
