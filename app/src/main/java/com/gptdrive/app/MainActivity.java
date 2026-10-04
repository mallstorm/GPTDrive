package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.DateFormat;
import java.util.List;

public final class MainActivity extends Activity {
    private static final String AUTHORITY = "com.gptdrive.app.documents";
    private DriveDb db;
    private LinearLayout recent, categories;
    private TextView session;
    private Button accountButton;
    private long activeId;

    @Override protected void onCreate(Bundle b) { super.onCreate(b); db = new DriveDb(this); draw(); }
    @Override protected void onResume() { super.onResume(); refresh(); }

    private void draw() {
        ScrollView sc = new ScrollView(this); sc.setFillViewport(true); sc.setBackgroundColor(0xFFF7F7F7);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(18),dp(18),dp(24));
        root.addView(text("GPT Drive",29,true));
        TextView sub=text("ChatGPT Library как облачный диск",14,false); sub.setTextColor(0xFF666666); sub.setPadding(0,dp(3),0,dp(10)); root.addView(sub);

        accountButton = new Button(this);
        accountButton.setAllCaps(false);
        accountButton.setOnClickListener(this::showAccountSwitcher);
        root.addView(accountButton,new LinearLayout.LayoutParams(-1,dp(52)));
        session=text("",12,false); session.setPadding(0,dp(5),0,dp(10)); root.addView(session);

        Button sync=new Button(this); sync.setText("Синхронизировать Library"); sync.setAllCaps(false); sync.setOnClickListener(v->openSync("Library")); root.addView(sync,new LinearLayout.LayoutParams(-1,dp(52)));
        TextView sections=text("Облачный диск",17,true); sections.setPadding(dp(4),dp(18),0,dp(8)); root.addView(sections);
        categories=new LinearLayout(this); categories.setOrientation(LinearLayout.VERTICAL); root.addView(categories);

        LinearLayout filesRow=new LinearLayout(this); filesRow.setGravity(Gravity.CENTER_VERTICAL); filesRow.setPadding(dp(4),dp(14),dp(4),dp(6));
        filesRow.addView(text("Недавние",17,true),new LinearLayout.LayoutParams(0,-2,1));
        Button system=new Button(this); system.setText("Android Files"); system.setAllCaps(false); system.setOnClickListener(v->openSystemFiles()); filesRow.addView(system); root.addView(filesRow);
        recent=new LinearLayout(this); recent.setOrientation(LinearLayout.VERTICAL); root.addView(recent);
        sc.addView(root); setContentView(sc); refresh();
    }

    private void refresh() {
        if (db==null || categories==null) return;
        AccountProfile a=db.activeAccount(); if (a==null) return; activeId=a.id;
        accountButton.setText(a.displayName()+"  ▾");
        session.setText(a.hasSession()?"● Активный аккаунт подключён":"○ Нужен вход в ChatGPT");
        session.setTextColor(a.hasSession()?0xFF287A42:0xFF777777);
        categories.removeAllViews();
        categories.addView(card("▣","Library","Документы и загруженные файлы",db.count(activeId,"Library")));
        categories.addView(card("◫","Images","Сгенерированные и отправленные изображения",db.count(activeId,"Images")));
        categories.addView(card("⌂","Projects","Проекты и их содержимое",db.count(activeId,"Projects")));
        categories.addView(card("□","Archived","Архивированные чаты и вложения",db.count(activeId,"Archived")));
        fillRecent();
    }

    private void showAccountSwitcher(View anchor) {
        List<AccountProfile> accounts = db.listAccounts();
        PopupMenu menu = new PopupMenu(this, anchor);
        int id = 100;
        for (AccountProfile a : accounts) {
            String label = (a.active ? "✓  " : "") + a.displayName();
            menu.getMenu().add(0, id++, 0, label);
        }
        menu.getMenu().add(0, 9000, 1, "＋ Добавить аккаунт");
        menu.getMenu().add(0, 9001, 2, "Управление аккаунтами");
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 9000) {
                long newId = db.addAccount("Новый аккаунт");
                Intent i = new Intent(this, BrowserActivity.class);
                i.putExtra(BrowserActivity.EXTRA_SECTION, "Library");
                i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID, newId);
                i.putExtra(BrowserActivity.EXTRA_NEW_LOGIN, true);
                startActivity(i);
                return true;
            }
            if (item.getItemId() == 9001) {
                startActivity(new Intent(this, AccountManagerActivity.class));
                return true;
            }
            int index = item.getItemId() - 100;
            if (index >= 0 && index < accounts.size()) {
                AccountProfile target = accounts.get(index);
                if (target.id == activeId) return true;
                AccountSession.switchTo(this, target.id, () -> {
                    notifyRoots();
                    refresh();
                    Toast.makeText(this, "Переключено: " + target.displayName(), Toast.LENGTH_SHORT).show();
                });
                return true;
            }
            return false;
        });
        menu.show();
    }

    private View card(String icon,String title,String desc,long count) {
        LinearLayout c=new LinearLayout(this); c.setGravity(Gravity.CENTER_VERTICAL); c.setPadding(dp(14),dp(12),dp(10),dp(12)); c.setBackgroundColor(0xFFFFFFFF);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,dp(82)); cp.setMargins(0,0,0,dp(9)); c.setLayoutParams(cp);
        TextView ic=text(icon,27,false); ic.setGravity(Gravity.CENTER); c.addView(ic,new LinearLayout.LayoutParams(dp(48),-1));
        LinearLayout tx=new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setGravity(Gravity.CENTER_VERTICAL); tx.addView(text(title,18,true)); TextView d=text(desc,13,false); d.setTextColor(0xFF777777); tx.addView(d); c.addView(tx,new LinearLayout.LayoutParams(0,-1,1));
        TextView n=text(Long.toString(count),14,true); n.setGravity(Gravity.CENTER); n.setTextColor(0xFF555555); c.addView(n,new LinearLayout.LayoutParams(dp(42),-1)); TextView a=text("›",30,false); a.setGravity(Gravity.CENTER); c.addView(a,new LinearLayout.LayoutParams(dp(28),-1));
        c.setOnClickListener(v->openCategory(title)); return c;
    }

    private void fillRecent() {
        recent.removeAllViews(); List<DriveItem> xs=db.list(activeId,null,12);
        if(xs.isEmpty()){TextView e=text("После синхронизации здесь появятся файлы этого аккаунта.",14,false);e.setTextColor(0xFF777777);e.setPadding(dp(4),dp(8),dp(4),dp(8));recent.addView(e);return;}
        DateFormat df=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);
        for(DriveItem x:xs){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);r.setPadding(dp(12),dp(10),dp(12),dp(10));r.setBackgroundColor(0xFFFFFFFF);TextView n=text(x.name,15,true);n.setMaxLines(2);TextView m=text(x.category+" • "+DriveActions.shortType(x)+" • "+df.format(x.updated),12,false);m.setTextColor(0xFF777777);r.addView(n);r.addView(m);r.setOnClickListener(v->DriveActions.open(this,x));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(6));recent.addView(r,p);}
    }

    private void openCategory(String category){Intent i=new Intent(this,NativeBrowserActivity.class);i.putExtra(NativeBrowserActivity.EXTRA_CATEGORY,category);i.putExtra(NativeBrowserActivity.EXTRA_ACCOUNT_ID,activeId);startActivity(i);}
    private void openSync(String section){Intent i=new Intent(this,BrowserActivity.class);i.putExtra(BrowserActivity.EXTRA_SECTION,section);i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,activeId);startActivity(i);}
    private void openSystemFiles(){try{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,100);}catch(Exception ignored){}}
    private void notifyRoots(){try{getContentResolver().notifyChange(DocumentsContract.buildRootsUri(AUTHORITY),null);}catch(Exception ignored){}}
    private TextView text(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(sp);t.setTextColor(0xFF111111);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
