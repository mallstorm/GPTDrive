package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;

public final class AccountManagerActivity extends Activity {
    private static final String AUTHORITY="com.gptdrive.app.documents";
    private static final int GREEN=0xFF08A66D, GREEN_SOFT=0xFFE5F8F1, TEXT=0xFF101828, MUTED=0xFF667085, BG=0xFFF7F9FA;
    private DriveDb db;
    private LinearLayout list;

    @Override protected void onCreate(Bundle state){super.onCreate(state);db=new DriveDb(this);buildUi();}
    @Override protected void onResume(){super.onResume();render();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(10),0,dp(12),0);
        TextView back=text("‹",28,false);back.setGravity(Gravity.CENTER);back.setOnClickListener(v->finish());bar.addView(back,new LinearLayout.LayoutParams(dp(40),dp(52)));
        TextView title=text("Аккаунты GPT Drive",20,true);title.setSingleLine(true);bar.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(56)));

        TextView add=text("＋  Добавить ChatGPT аккаунт",13,true);add.setGravity(Gravity.CENTER);add.setTextColor(GREEN);add.setBackground(round(Color.WHITE,15,0xFF8EDFC0,1));add.setOnClickListener(v->addAccount());
        LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(46));ap.setMargins(dp(14),dp(4),dp(14),dp(8));root.addView(add,ap);

        TextView hint=text("Сохраняй несколько профилей и переключай их на горячую. Активен всегда один аккаунт.",11,false);hint.setTextColor(MUTED);hint.setPadding(dp(16),0,dp(16),dp(10));root.addView(hint);
        ScrollView sc=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);list.setPadding(dp(14),0,dp(14),dp(18));sc.addView(list);root.addView(sc,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }

    private void render(){
        if(list==null)return;list.removeAllViews();
        for(AccountProfile a:db.listAccounts()) list.addView(row(a));
    }

    private View row(AccountProfile a){
        LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(12),dp(9),dp(8),dp(9));card.setBackground(round(Color.WHITE,17,0xFFE4E8EB,1));
        ImageView avatar=new ImageView(this);avatar.setImageResource(R.drawable.ic_account_24);avatar.setColorFilter(Color.WHITE);avatar.setPadding(dp(10),dp(10),dp(10),dp(10));avatar.setBackground(round(a.active?GREEN:0xFF98A2B3,22,0,0));card.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setGravity(Gravity.CENTER_VERTICAL);info.setPadding(dp(10),0,dp(6),0);
        TextView name=text(a.displayName(),14,true);name.setSingleLine(true);
        String state=a.hasSession()?(a.active?"Активен":"Сессия сохранена"):(a.active?"Активен • нужен вход":"Нужен вход");
        TextView plan=text(a.planLabel()+"  •  "+state,10,true);plan.setTextColor(a.hasSession()?0xFF06764E:0xFF8A5A00);
        long known=db.knownStorageBytes(a.id);String storage=a.hasRemoteQuota()?MainActivity.formatBytes(a.remoteUsedBytes)+" / "+MainActivity.formatBytes(a.remoteQuotaBytes):(known>0?MainActivity.formatBytes(known)+" индексировано":"Объём не определён");
        TextView meta=text(storage+"  •  "+db.countAll(a.id)+" элементов",10,false);meta.setTextColor(MUTED);meta.setSingleLine(true);
        info.addView(name);info.addView(plan);info.addView(meta);card.addView(info,new LinearLayout.LayoutParams(0,dp(54),1));

        TextView action=text(actionLabel(a),11,true);action.setGravity(Gravity.CENTER);action.setTextColor(a.active&&a.hasSession()?MUTED:Color.WHITE);action.setBackground(round(a.active&&a.hasSession()?0xFFF0F2F4:GREEN,12,0,0));action.setEnabled(!(a.active&&a.hasSession()));action.setOnClickListener(v->act(a));
        card.addView(action,new LinearLayout.LayoutParams(dp(76),dp(38)));
        TextView more=text("⋮",21,false);more.setGravity(Gravity.CENTER);more.setOnClickListener(v->menu(v,a));card.addView(more,new LinearLayout.LayoutParams(dp(30),dp(42)));
        if(!a.active)card.setOnClickListener(v->select(a));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(78));p.setMargins(0,0,0,dp(7));card.setLayoutParams(p);return card;
    }

    private String actionLabel(AccountProfile a){if(!a.hasSession())return"Войти";return a.active?"Активен":"Выбрать";}
    private void act(AccountProfile a){if(!a.hasSession())login(a,true);else if(!a.active)select(a);}

    private void select(AccountProfile a){AccountSession.switchTo(this,a.id,()->{notifyRoots();Toast.makeText(this,"Переключено: "+a.displayName(),Toast.LENGTH_SHORT).show();render();});}
    private void login(AccountProfile a,boolean fresh){Intent i=new Intent(this,BrowserActivity.class);i.putExtra(BrowserActivity.EXTRA_SECTION,"Library");i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,a.id);i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN,fresh);startActivity(i);}
    private void addAccount(){long id=db.addAccount("Новый аккаунт");AccountProfile a=db.accountById(id);login(a,true);}

    private void menu(View anchor,AccountProfile a){
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(5),dp(5),dp(5),dp(5));panel.setBackground(round(Color.WHITE,14,0xFFE4E8EB,1));
        TextView sync=popupAction(a.hasSession()?"Открыть и синхронизировать":"Войти в аккаунт");panel.addView(sync,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView del=popupAction("Удалить аккаунт");del.setTextColor(0xFFD92D20);panel.addView(del,new LinearLayout.LayoutParams(-1,dp(44)));
        PopupWindow w=new PopupWindow(panel,dp(210),-2,true);w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));w.setOutsideTouchable(true);w.setElevation(dp(10));
        sync.setOnClickListener(v->{w.dismiss();login(a,!a.hasSession());});
        del.setOnClickListener(v->{w.dismiss();if(a.active)Toast.makeText(this,"Сначала переключись на другой аккаунт",Toast.LENGTH_SHORT).show();else if(db.deleteAccount(a.id)){notifyRoots();render();}});
        w.showAsDropDown(anchor,anchor.getWidth()-dp(210),-dp(2));
    }

    private TextView popupAction(String s){TextView t=text(s,12,false);t.setGravity(Gravity.CENTER_VERTICAL);t.setPadding(dp(12),0,dp(12),0);return t;}
    private void notifyRoots(){try{getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null);}catch(Exception ignored){}}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(TEXT);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius,int stroke,int strokeDp){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));if(strokeDp>0)g.setStroke(dp(strokeDp),stroke);return g;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
