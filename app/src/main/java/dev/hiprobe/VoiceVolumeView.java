package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.widget.*;
import java.util.Locale;

/** Shared volume control for home, voice previews and the floating panel. */
final class VoiceVolumeView extends LinearLayout {
    private final OutputVolume volume;
    private final TextView label;
    private final SeekBar slider;
    private final Runnable changed;
    private final SharedPreferences.OnSharedPreferenceChangeListener listener=(prefs,key)->{
        if("voice_volume".equals(key))sync();
    };
    VoiceVolumeView(Context context,int color,Runnable changed) {
        super(context);this.changed=changed;volume=new OutputVolume(context);setOrientation(VERTICAL);
        label=new TextView(context);label.setTextColor(color);label.setTextSize(12);label.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));addView(label);
        slider=new SeekBar(context);slider.setMax(100);slider.setContentDescription("Translation voice volume");
        slider.setProgressTintList(ColorStateList.valueOf(color));slider.setThumbTintList(ColorStateList.valueOf(color));
        addView(slider,new LayoutParams(-1,Math.round(48*getResources().getDisplayMetrics().density)));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean fromUser){if(fromUser)volume.percent(value);}
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        });sync();
    }
    private void sync(){
        int value=volume.percent();label.setText(String.format(Locale.getDefault(),"Voice volume · %d%%",value));
        if(slider.getProgress()!=value)slider.setProgress(value);
        if(changed!=null)changed.run();
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();volume.listen(listener);sync();}
    @Override protected void onDetachedFromWindow(){volume.unlisten(listener);super.onDetachedFromWindow();}
}
