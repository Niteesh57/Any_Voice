package dev.hiprobe;

/** Bounded speech gain for the interpreter track only; no change to playback or Bubble. */
final class ConversationInputGain {
    private volatile double gain=1;
    void reset(){gain=1;}
    short[] apply(short[] input,int count){
        double energy=0;int peak=0;
        for(int i=0;i<count;i++){int value=input[i];energy+=(double)value*value;peak=Math.max(peak,Math.abs(value));}
        double rms=count>0?Math.sqrt(energy/count):0;
        double wanted=rms>=40?Math.min(8,Math.max(1,2000/rms)):1;
        gain+=(wanted-gain)*.25;
        double safe=Math.min(gain,peak>0?30000.0/peak:gain);
        short[] output=new short[count];
        for(int i=0;i<count;i++)output[i]=(short)Math.max(-32768,Math.min(32767,Math.round(input[i]*safe)));
        return output;
    }
}
