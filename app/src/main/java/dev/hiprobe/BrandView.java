package dev.hiprobe;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.animation.ValueAnimator;
import android.view.View;

/** Vector brand mark and gentle waveform; no bitmap assets or icon fonts. */
final class BrandView extends View {
    private static final float[] HEIGHTS={.3f,.65f,1f,.65f,.3f};
    private final Paint paint=new Paint(3);
    private final boolean waveform;
    private final int color;
    private boolean active;
    private boolean orb;
    public BrandView(Context context) { this(context,false,0xff1d5c49); }
    @Override public boolean performClick() { super.performClick(); return true; }
    BrandView(Context context, boolean waveform,int color) { super(context);this.waveform=waveform;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    BrandView(Context context,boolean waveform,int color,boolean orb){this(context,waveform,color);this.orb=orb;}
    void active(boolean value) { if(active!=value) { active=value;invalidate(); } }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w=getWidth(),h=getHeight(),center=h/2;
        paint.setColor(color);paint.setStyle(Paint.Style.FILL);
        int bars=waveform?37:5;
        float step=waveform?w/(bars+2):w/7,bar=waveform?step*.32f:step*.63f;
        float time=SystemClock.uptimeMillis()/430f;
        if(orb){
            float radius=h*.32f;float pulse=active&&ValueAnimator.areAnimatorsEnabled()?(float)(.5+.5*Math.sin(time)):0;
            paint.setAlpha(18);canvas.drawCircle(w/2,center,radius*(1.18f+.12f*pulse),paint);
            paint.setAlpha(35);canvas.drawCircle(w/2,center,radius,paint);
            step=h*.075f;bar=step*.55f;
        }
        for(int i=0;i<bars;i++) {
            float envelope=waveform?(float)(.15+.7*Math.pow(Math.sin((i+1)*.69),2)):HEIGHTS[i];
            float motion=active&&ValueAnimator.areAnimatorsEnabled()?(float)(.65+.35*Math.sin(time+i*.65)):1;
            float height=Math.max(bar,h*(orb?.34f:.7f)*envelope*motion);
            float x=waveform?step*(i+1.5f):w/2+(i-2)*step;
            paint.setAlpha(waveform?(int)(100+155*Math.sin(Math.PI*(i+1)/(bars+1))):255);
            canvas.drawRoundRect(x-bar/2,center-height/2,x+bar/2,center+height/2,bar,bar,paint);
        }
        if(active&&ValueAnimator.areAnimatorsEnabled()&&isShown())postInvalidateDelayed(40);
    }
}
