package dev.hiprobe;

import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** A compact, movable shortcut. Only its visible bounds intercept touches. */
final class FloatingControl {
    private final Context context;
    private final Runnable stop;
    private final WindowManager windows;
    private WindowManager.LayoutParams layout;
    private LinearLayout panel,details;
    private TextView state,caption;
    private BrandView logo;
    private boolean expanded;
    private ValueAnimator snap;
    FloatingControl(Context context,Runnable stop){this.context=context;this.stop=stop;windows=context.getSystemService(WindowManager.class);}
    void show(){
        if(!Settings.canDrawOverlays(context))return;
        panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setBackground(shape(0xff184d3e,26));panel.setClipToOutline(true);
        logo=new BrandView(context,false,0xffe5f2d2);logo.setPadding(dp(11),dp(11),dp(11),dp(11));logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);logo.setContentDescription("Any Voice floating controls. Tap to open; drag to move.");logo.setFocusable(true);panel.addView(logo,new LinearLayout.LayoutParams(dp(52),dp(52)));
        details=new LinearLayout(context);details.setOrientation(LinearLayout.VERTICAL);details.setPadding(dp(18),0,dp(18),dp(16));details.setVisibility(View.GONE);panel.addView(details);
        text(details,"any voice",19,0xfff6f5ef);state=text(details,"Listening",12,0xffd9edb5);caption=text(details,"",13,0xffd4e1d7);caption.setMaxLines(3);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(new VoiceVolumeView(context,0xffd9edb5,null));
        TextView open=text(details,"Open app  ↗",13,Color.WHITE);open.setMinHeight(dp(44));open.setGravity(Gravity.CENTER_VERTICAL);open.setOnClickListener(v->{context.startActivity(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));collapse();});
        TextView end=text(details,"Stop listening",14,0xff183c35);end.setGravity(Gravity.CENTER);end.setMinHeight(dp(46));end.setBackground(shape(0xffd9edb5,14));end.setOnClickListener(v->stop.run());
        layout=new WindowManager.LayoutParams(dp(52),dp(52),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        layout.gravity=Gravity.TOP|Gravity.START;layout.alpha=.78f;layout.x=width()-dp(64);layout.y=dp(170);
        logo.setOnClickListener(v->{expanded=!expanded;applySize();});
        logo.setOnTouchListener(new View.OnTouchListener(){float x,y;int sx,sy;boolean moved;
            @Override public boolean onTouch(View view,MotionEvent event){
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        if(snap!=null)snap.cancel();x=event.getRawX();y=event.getRawY();sx=layout.x;sy=layout.y;moved=false;return true;
                    case MotionEvent.ACTION_MOVE:
                        if(Math.hypot(event.getRawX()-x,event.getRawY()-y)>dp(7))moved=true;
                        if(moved){if(expanded){expanded=false;applySize();}layout.x=clamp(sx+(int)(event.getRawX()-x),dp(4),width()-dp(56));layout.y=clamp(sy+(int)(event.getRawY()-y),dp(32),height()-dp(110));updateLayout();}return true;
                    case MotionEvent.ACTION_UP: if(moved)snapToEdge();else view.performClick();return true;
                    case MotionEvent.ACTION_CANCEL:if(moved)snapToEdge();return true;
                    default:return false;
                }
            }
        });
        try{windows.addView(panel,layout);}catch(RuntimeException e){panel=null;}
    }
    private void collapse(){expanded=false;applySize();}
    private void applySize(){
        if(panel==null)return;
        details.setVisibility(expanded?View.VISIBLE:View.GONE);layout.width=dp(expanded?264:52);layout.height=expanded?WindowManager.LayoutParams.WRAP_CONTENT:dp(52);layout.alpha=expanded?1f:.78f;
        layout.x=clamp(layout.x,dp(4),Math.max(dp(4),width()-layout.width-dp(4)));layout.y=clamp(layout.y,dp(32),Math.max(dp(32),height()-dp(expanded?440:110)));
        update();updateLayout();if(expanded&&ValueAnimator.areAnimatorsEnabled()){details.setAlpha(0);details.animate().alpha(1).setDuration(160).start();}
    }
    void update(){
        if(panel==null)return;logo.active(ListeningState.running);
        if(expanded){UserProfile profile=new UserProfile(context);state.setText(ListeningState.replacing?"Translating · "+profile.languageName():ListeningState.detectedLanguage.isEmpty()?"Finding the language":"Original voice · "+profile.languageName());String value=ListeningState.replacing?ListeningState.translatedText:ListeningState.sourceText;caption.setText(value.isEmpty()?"Your audio, in your language.":value);}
    }
    void reposition(){if(panel!=null)applySize();}
    private void snapToEdge(){
        int target=layout.x+dp(26)<width()/2?dp(4):width()-dp(56);
        if(!ValueAnimator.areAnimatorsEnabled()){layout.x=target;updateLayout();return;}
        snap=ValueAnimator.ofInt(layout.x,target);snap.setDuration(180);snap.addUpdateListener(a->{if(panel!=null){layout.x=(int)a.getAnimatedValue();updateLayout();}});snap.start();
    }
    private void updateLayout(){if(panel!=null&&panel.isAttachedToWindow())try{windows.updateViewLayout(panel,layout);}catch(RuntimeException ignored){}}
    void close(){if(snap!=null)snap.cancel();if(panel!=null){logo.active(false);if(panel.isAttachedToWindow())windows.removeView(panel);panel=null;}}
    private TextView text(LinearLayout parent,String value,int size,int color){TextView text=new TextView(context);text.setText(value);text.setTextSize(size);text.setTextColor(color);text.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));text.setPadding(0,dp(5),0,dp(5));parent.addView(text);return text;}
    private GradientDrawable shape(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private int dp(int n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private int width(){return context.getResources().getDisplayMetrics().widthPixels;}
    private int height(){return context.getResources().getDisplayMetrics().heightPixels;}
    private int clamp(int n,int low,int high){return Math.max(low,Math.min(n,Math.max(low,high)));}
}
