package com.gptdrive.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

public final class NativeBrowserActivity extends Activity {
    public static final String EXTRA_CATEGORY = "category";
    public static final String EXTRA_ACCOUNT_ID = "account_id";
    private String category;
    private long accountId;
    private DriveDb db;
    private LinearLayout content;
    private TextView summary;
    private EditText search;
    private List<DriveItem> all = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = new DriveDb(this);
        category = getIntent().getStringExtra(EXTRA_CATEGORY); if (category == null) category = "Library";
        accountId = getIntent().getLongExtra(EXTRA_ACCOUNT_ID, db.activeAccountId());
        buildUi();
    }
    @Override protected void onResume() { super.onResume(); reload(); }

    private void buildUi() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xFFF7F7F7);
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(8),dp(8),dp(4));
        Button back=new Button(this);back.setText("‹");back.setOnClickListener(v->finish());
        AccountProfile a=db.accountById(accountId);TextView title=text(category,21,true);title.setPadding(dp(8),0,0,0);
        Button sync=new Button(this);sync.setText("Синхр.");sync.setAllCaps(false);sync.setOnClickListener(v->sync());
        bar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(48)));bar.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));bar.addView(sync,new LinearLayout.LayoutParams(dp(92),dp(48)));root.addView(bar);
        TextView account=text(a==null?"":a.displayName(),12,false);account.setTextColor(0xFF777777);account.setPadding(dp(18),0,dp(18),dp(2));root.addView(account);
        summary=text("",13,false);summary.setTextColor(0xFF707070);summary.setPadding(dp(18),0,dp(18),dp(8));root.addView(summary);
        search=new EditText(this);search.setSingleLine(true);search.setHint("Поиск в "+category);search.setTextSize(15);search.setPadding(dp(14),0,dp(14),0);LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(-1,dp(48));sp.setMargins(dp(14),0,dp(14),dp(10));root.addView(search,sp);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int b,int c){render();}public void afterTextChanged(Editable e){}});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(14),0,dp(14),dp(24));scroll.addView(content,new ScrollView.LayoutParams(-1,-2));root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);
    }

    private void reload(){all=db.list(accountId,category,1000);long files=0;for(DriveItem x:all)if(!DriveActions.isLink(x))files++;summary.setText(all.size()+" элементов"+(files!=all.size()?" • "+files+" файлов":""));render();}
    private void render(){if(content==null)return;content.removeAllViews();String q=search==null?"":search.getText().toString().trim().toLowerCase();ArrayList<DriveItem> shown=new ArrayList<>();for(DriveItem x:all)if(q.isEmpty()||x.name.toLowerCase().contains(q)||DriveActions.shortType(x).toLowerCase().contains(q))shown.add(x);if(shown.isEmpty()){empty();return;}if("Images".equals(category))renderImages(shown);else renderList(shown);}

    private void renderImages(List<DriveItem> items){GridLayout grid=new GridLayout(this);grid.setColumnCount(2);grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);int w=(getResources().getDisplayMetrics().widthPixels-dp(42))/2;for(DriveItem x:items){if(!DriveActions.isImage(x))continue;LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setBackgroundColor(0xFFFFFFFF);cell.setPadding(dp(4),dp(4),dp(4),dp(8));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(0xFFE9E9E9);cell.addView(image,new LinearLayout.LayoutParams(-1,dp(150)));TextView name=text(x.name,13,true);name.setMaxLines(2);name.setPadding(dp(5),dp(7),dp(5),0);cell.addView(name);cell.setOnClickListener(v->DriveActions.open(this,x));cell.setOnLongClickListener(v->{menu(v,x);return true;});GridLayout.LayoutParams gp=new GridLayout.LayoutParams();gp.width=w;gp.height=dp(205);gp.setMargins(dp(3),dp(3),dp(3),dp(3));grid.addView(cell,gp);ImageLoader.load(image,x.url);}if(grid.getChildCount()==0){empty();return;}content.addView(grid);}

    private void renderList(List<DriveItem> items){DateFormat df=DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT);for(DriveItem x:items){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(12),dp(10),dp(6),dp(10));row.setBackgroundColor(0xFFFFFFFF);TextView icon=text(iconFor(x),24,false);icon.setGravity(Gravity.CENTER);row.addView(icon,new LinearLayout.LayoutParams(dp(44),dp(54)));LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.setGravity(Gravity.CENTER_VERTICAL);TextView n=text(x.name,15,true);n.setMaxLines(2);TextView m=text(DriveActions.shortType(x)+" • "+df.format(x.updated),12,false);m.setTextColor(0xFF777777);info.addView(n);info.addView(m);row.addView(info,new LinearLayout.LayoutParams(0,dp(58),1));ImageButton more=new ImageButton(this);more.setImageResource(android.R.drawable.ic_menu_more);more.setBackgroundColor(0x00000000);more.setOnClickListener(v->menu(v,x));row.addView(more,new LinearLayout.LayoutParams(dp(46),dp(46)));row.setOnClickListener(v->DriveActions.open(this,x));LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.setMargins(0,0,0,dp(7));content.addView(row,rp);}}

    private void menu(View anchor,DriveItem item){PopupMenu m=new PopupMenu(this,anchor);m.getMenu().add(0,1,0,"Открыть");if(!DriveActions.isLink(item))m.getMenu().add(0,2,1,"Скачать");m.getMenu().add(0,3,2,"Открыть источник в ChatGPT");m.setOnMenuItemClickListener(mi->{if(mi.getItemId()==1)DriveActions.open(this,item);else if(mi.getItemId()==2)DriveActions.download(this,item);else if(mi.getItemId()==3){Intent i=new Intent(this,BrowserActivity.class);i.putExtra(BrowserActivity.EXTRA_SECTION,category);i.putExtra(BrowserActivity.EXTRA_URL,item.url);i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,item.accountId);startActivity(i);}return true;});m.show();}
    private void empty(){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setGravity(Gravity.CENTER);box.setPadding(dp(24),dp(70),dp(24),dp(24));box.addView(text("Пока пусто",20,true));TextView d=text("Запусти синхронизацию выбранного аккаунта.",14,false);d.setGravity(Gravity.CENTER);d.setTextColor(0xFF777777);d.setPadding(0,dp(8),0,dp(16));box.addView(d);Button b=new Button(this);b.setText("Синхронизировать");b.setAllCaps(false);b.setOnClickListener(v->sync());box.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));content.addView(box);}
    private void sync(){Intent i=new Intent(this,BrowserActivity.class);i.putExtra(BrowserActivity.EXTRA_SECTION,category);i.putExtra(BrowserActivity.EXTRA_ACCOUNT_ID,accountId);startActivity(i);}
    private String iconFor(DriveItem x){if(DriveActions.isLink(x))return"↗";if(DriveActions.isImage(x))return"▣";if(x.mime!=null&&x.mime.equals("application/pdf"))return"PDF";return"□";}
    private TextView text(String v,int sp,boolean bold){TextView t=new TextView(this);t.setText(v);t.setTextSize(sp);t.setTextColor(0xFF111111);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
