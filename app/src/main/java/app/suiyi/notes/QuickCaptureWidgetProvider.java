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

public class QuickCaptureWidgetProvider extends AppWidgetProvider {
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids){for(int id:ids)update(context,manager,id);}
    @Override public void onAppWidgetOptionsChanged(Context context,AppWidgetManager manager,int id,Bundle options){update(context,manager,id);}
    @Override public void onReceive(Context context,Intent intent){super.onReceive(context,intent);if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))refresh(context);}

    private static void update(Context context,AppWidgetManager manager,int id){
        RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.widget_quick_capture);
        Bundle options=manager.getAppWidgetOptions(id);
        int width=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,70);
        int height=options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,80);
        views.setViewVisibility(R.id.widget_quick_label,width<64||height<80?View.GONE:View.VISIBLE);
        Intent intent=new Intent(context,MainActivity.class).setAction(MainActivity.ACTION_WIDGET_NEW).setData(Uri.parse("suiyi://widget/quick/"+id)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending=PendingIntent.getActivity(context,7100+id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_quick_capture,pending);
        manager.updateAppWidget(id,views);
    }

    static void refresh(Context context){AppWidgetManager manager=AppWidgetManager.getInstance(context);int[] ids=manager.getAppWidgetIds(new ComponentName(context,QuickCaptureWidgetProvider.class));if(ids.length>0)new QuickCaptureWidgetProvider().onUpdate(context,manager,ids);}
}
