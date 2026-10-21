package app.suiyi.notes;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

public class RecentNotesWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){for(int id:ids)update(context,manager,id);}
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options){update(context,manager,id);}
    @Override public void onReceive(Context context,Intent intent){super.onReceive(context,intent);if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))refresh(context);}

    private static void update(Context context,AppWidgetManager manager,int id){
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.widget_recent_notes);
        WidgetNoteStore.Preview preview=WidgetNoteStore.latest(context);
        String message="还没有笔记";
        Intent intent=new Intent(context,MainActivity.class).setAction(MainActivity.ACTION_WIDGET_NEW).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if(preview!=null){message=preview.title+(preview.body.isEmpty()?"":"\n"+preview.body);intent.setAction(MainActivity.ACTION_WIDGET_OPEN).putExtra(MainActivity.EXTRA_WIDGET_NOTE_ID,preview.id);}
        else if(!context.getSharedPreferences("suiyi",Context.MODE_PRIVATE).getString("lockHash","").isEmpty())message="已开启应用锁\n解锁后在碎忆中查看";
        Bundle options=manager.getAppWidgetOptions(id);
        boolean compact=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)<105||options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)<165;
        views.setViewVisibility(R.id.widget_recent_heading,compact?View.GONE:View.VISIBLE);
        views.setInt(R.id.widget_recent_text,"setMaxLines",compact?2:4);
        views.setTextViewText(R.id.widget_recent_text,message);
        intent.setData(Uri.parse("suiyi://widget/recent/"+id+(preview==null?"":"/"+Uri.encode(preview.id))));
        PendingIntent pending=PendingIntent.getActivity(context,8100+id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_recent_root,pending);
        manager.updateAppWidget(id,views);
    }

    static void refresh(Context context){AppWidgetManager manager=AppWidgetManager.getInstance(context);int[] ids=manager.getAppWidgetIds(new ComponentName(context,RecentNotesWidgetProvider.class));if(ids.length>0)new RecentNotesWidgetProvider().onUpdate(context,manager,ids);}
}
