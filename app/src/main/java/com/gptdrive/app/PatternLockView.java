package com.gptdrive.app;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

final class PatternLockView extends View {
    interface Listener { void onPattern(int[] pattern); }
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Integer> path=new ArrayList<>();
    private Listener listener;
    PatternLockView(Context c){super(c);paint.setStrokeWidth(dp(5));paint.setStrokeCap(Paint.Cap.ROUND);}
    void setListener(Listener l){listener=l;}
    @Override protected void onDraw(Canvas c){
        float[] x=new float[9],y=new float[9];
        for(int i=0;i<9;i++){x[i]=getWidth()*(.2f+.3f*(i%3));y[i]=getHeight()*(.2f+.3f*(i/3));}
        paint.setColor(0xFF08A66D);paint.setStyle(Paint.Style.STROKE);
        for(int i=1;i<path.size();i++)c.drawLine(x[path.get(i-1)],y[path.get(i-1)],x[path.get(i)],y[path.get(i)],paint);
        for(int i=0;i<9;i++){paint.setStyle(Paint.Style.FILL);paint.setColor(path.contains(i)?0xFF08A66D:0xFFCBD5E1);c.drawCircle(x[i],y[i],dp(path.contains(i)?10:8),paint);}
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(e.getAction()==MotionEvent.ACTION_DOWN)path.clear();
        if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){int h=hit(e.getX(),e.getY());if(h>=0&&!path.contains(h)){path.add(h);invalidate();}return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){if(listener!=null){int[] a=new int[path.size()];for(int i=0;i<a.length;i++)a[i]=path.get(i);listener.onPattern(a);}return true;}
        return true;
    }
    private int hit(float px,float py){for(int i=0;i<9;i++){float x=getWidth()*(.2f+.3f*(i%3)),y=getHeight()*(.2f+.3f*(i/3));if(Math.hypot(px-x,py-y)<dp(34))return i;}return -1;}
    private float dp(int v){return v*getResources().getDisplayMetrics().density;}
}