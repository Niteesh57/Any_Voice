package dev.hiprobe.testsource;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.widget.*;

/** Public, non-personal screen fixture for research/scroll verification. No audio. */
public final class ResearchFixtureActivity extends Activity {
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);stopService(new android.content.Intent(this,SourceActivity.SourceService.class));
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(32,40,32,40);page.setBackgroundColor(0xfff5f5ef);scroll.addView(page);setContentView(scroll);
        TextView title=new TextView(this);title.setText("C++ learning choices — test page");title.setTextSize(26);title.setTextColor(0xff153e33);page.addView(title);
        card(page,"Course A · Quick C++ Basics\nSponsored\n4.5 rating · 1,200 reviews\nC++11 · 2 hours\nFree\nShort overview; few exercises.");
        card(page,"Course B · Modern C++ Practice\n4.8 rating · 480 reviews\nBeginner-friendly · C++17 / C++20\n20 hours · practical exercises\nFree\nA structured path from fundamentals to projects.");
        card(page,"Course C · Advanced C++ Internals\n4.9 rating · 8,000 reviews\nAdvanced · requires prior C++ knowledge\n12 hours · ₹1,499\nDetailed internals, not an introduction.");
        TextView end=new TextView(this);end.setText("End of the three test choices. Popularity alone does not establish suitability.");end.setTextSize(20);page.addView(end);
    }
    private void card(LinearLayout page,String content){TextView text=new TextView(this);text.setText(content);text.setTextSize(24);text.setTextColor(Color.BLACK);text.setPadding(24,48,24,48);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,Math.round(600*getResources().getDisplayMetrics().density));lp.setMargins(0,30,0,30);page.addView(text,lp);}
}
