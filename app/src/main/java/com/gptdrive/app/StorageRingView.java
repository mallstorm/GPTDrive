package com.gptdrive.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

public final class StorageRingView extends View {
    private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int percent=-1;

    public StorageRingView(Context context){
        super(context);float d=getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);track.setStrokeWidth(8f*d);track.setStrokeCap(Paint.Cap.ROUND);track.setColor(0xFFE5E8EA);
        progressPaint.setStyle(Paint.Style.STROKE);progressPaint.setStrokeWidth(8f*d);progressPaint.setStrokeCap(Paint.Cap.ROUND);progressPaint.setColor(0xFF08A66D);
        textPaint.setTextAlign(Paint.Align.CENTER);textPaint.setColor(0xFF101828);textPaint.setFakeBoldText(true);textPaint.setTextSize(20f*d);
    }

    public void setStorage(long used,long quota){if(quota>0)percent=(int)Math.max(0,Math.min(100,Math.round(used*100f/quota)));else percent=-1;invalidate();}

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);float pad=Math.max(track.getStrokeWidth(),progressPaint.getStrokeWidth())/2f+2f;float size=Math.min(getWidth(),getHeight());float left=(getWidth()-size)/2f+pad;float top=(getHeight()-size)/2f+pad;RectF oval=new RectF(left,top,left+size-pad*2f,top+size-pad*2f);canvas.drawArc(oval,-90,360,false,track);if(percent>=0)canvas.drawArc(oval,-90,360f*percent/100f,false,progressPaint);String label=percent>=0?percent+"%":"—";Paint.FontMetrics fm=textPaint.getFontMetrics();float y=getHeight()/2f-(fm.ascent+fm.descent)/2f;canvas.drawText(label,getWidth()/2f,y,textPaint);
    }
}
