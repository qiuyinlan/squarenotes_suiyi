package app.suiyi.notes;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Runs on a disposable emulator; restores the note store after exercising real activity code. */
public class EditorRegressionInstrumentation extends Instrumentation {
    MainActivity app;
    JSONObject original;
    MainActivity.Note first, second;
    MainActivity.Block top, middle, last;

    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    void main(Runnable task) {
        Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { task.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
        waitForIdleSync();
    }

    MainActivity.Block block(String text, int y) {
        MainActivity.Block block = app.new Block(); block.text = text; block.y = y;
        block.order = app.note.blocks.size(); app.note.blocks.add(block); return block;
    }

    void touch(int action, float x, float y) {
        main(() -> { long now = SystemClock.uptimeMillis(); MotionEvent e = MotionEvent.obtain(now, now, action, x, y, 0); app.board.dispatchTouchEvent(e); e.recycle(); });
    }

    void screenshot(String name) throws Exception {
        CountDownLatch drawn = new CountDownLatch(1);
        android.view.ViewTreeObserver observer = app.root.getViewTreeObserver();
        android.view.ViewTreeObserver.OnDrawListener listener = drawn::countDown;
        main(() -> { app.root.requestLayout(); app.root.invalidate(); observer.addOnDrawListener(listener); });
        check(drawn.await(2, TimeUnit.SECONDS), "screen did not finish drawing before screenshot: " + name);
        Bitmap[] captured = new Bitmap[1];
        main(() -> {
            android.view.View decor = app.getWindow().getDecorView();
            int width = decor.getWidth(), height = decor.getHeight();
            check(width > 0 && height > 0, "activity window has no drawable size: " + name);
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            decor.draw(new Canvas(bitmap));
            captured[0] = bitmap;
        });
        Bitmap shot = captured[0];
        observer.removeOnDrawListener(listener);
        if (shot != null) { try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), name))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); } shot.recycle(); }
    }

    void checkStandalonePage() {
        android.view.ViewGroup content = app.findViewById(android.R.id.content);
        check(app.screen == 8, "trash screen state was not set");
        check(app.root != null && app.root.getParent() == content, "trash root is not attached to the activity content");
        check(content.getChildCount() == 1 && content.getChildAt(0) == app.root, "trash page did not replace the editor content");
        check(app.root.getChildCount() >= 2, "trash page has no list content");
        android.view.View headerView = app.root.getChildAt(0);
        check(headerView instanceof android.view.ViewGroup, "trash page header is missing");
        android.view.View titleView = ((android.view.ViewGroup) headerView).getChildAt(1);
        check(titleView instanceof TextView && !((TextView) titleView).getText().toString().equals(first.title), "trash header still shows the editor note");
    }

    @Override public void onStart() {
        Bundle result = new Bundle(); boolean welcome = getTargetContext().getSharedPreferences("suiyi", 0).getBoolean("welcomeLetterSeen", false);
        getTargetContext().getSharedPreferences("suiyi", 0).edit().putBoolean("welcomeLetterSeen", true).commit();
        int status = Activity.RESULT_OK;
        try {
            Intent launch = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            app = (MainActivity) startActivitySync(launch); waitForIdleSync();
            main(() -> {
                String markdown="头<u>左转**</u>平移，右看抵舌头，躯干左转，拉伸活动腿。\n**多字\n加粗** 和 ==跨\n行荧光==";
                MainActivity.MarkdownResult rendered=app.parseMarkdown(markdown);
                check(!rendered.rendered.toString().contains("**")&&!rendered.rendered.toString().contains("==")&&!rendered.rendered.toString().contains("<u>"),"Markdown preview exposed formatting markers");
                check(rendered.rendered.toString().startsWith("头左转平移")&&rendered.styles.size()==4,"nested or multiline Markdown formatting did not parse");
                check(!app.markdownPlainText(markdown).contains("*")&&!app.markdownPlainText(markdown).contains("=="),"copied Markdown text retained formatting markers");
                MainActivity.Note sample=app.new Note();sample.text=markdown;check(!app.preview(sample).contains("*")&&!app.preview(sample).contains("=="),"home note preview retained formatting markers");
                check(!app.text(markdown,16,app.ink,false).getText().toString().contains("**"),"formatted note TextView exposed Markdown markers");
                android.widget.EditText listEditor=new android.widget.EditText(app);app.continueListOnEnter(listEditor);listEditor.setText("1.");listEditor.setSelection(2);listEditor.getText().insert(2," ");
                check(listEditor.getText().toString().equals("  1. ")&&listEditor.getSelectionStart()==5,"typing an ordered-list trigger did not indent the list marker");
                app.linear=new android.widget.EditText(app);app.linear.setText("item");app.linear.setSelection(0);app.insertListPrefix("1. ",true);
                check(app.linear.getText().toString().equals("  1. item")&&app.linear.getSelectionStart()==5,"ordered-list toolbar indentation or caret position differed from typed markers");
                check(app.markdownPlainText("  - list item").equals("  • list item"),"unordered list preview did not render a bullet");
                android.text.SpannableString bulletText=new android.text.SpannableString("  - list item");app.applyMarkdownStyles(bulletText);
                check(bulletText.getSpans(0,bulletText.length(),MainActivity.MarkdownBulletSpan.class).length==1,"unordered list editor did not render a bullet span");
                app.linear=new android.widget.EditText(app);app.linear.setText("==a==bc");app.linear.setSelection(2,7);app.formatEditorSelection("==","==");
                check(app.linear.getText().toString().equals("==abc=="),"applying highlight to a selection with existing highlighted text did not merge the range");
                check(app.linear.getSelectionStart()==2&&app.linear.getSelectionEnd()==5,"applying highlight cleared the selected text");
                app.linear.setText("<u>abc</u>");app.linear.setSelection(3,6);app.formatEditorSelection("<u>","</u>");
                check(app.linear.getText().toString().equals("abc"),"reapplying underline did not remove it");
                check(app.linear.getSelectionStart()==0&&app.linear.getSelectionEnd()==3,"removing underline cleared the selected text");
                app.linear.setText("**abc**");app.linear.setSelection(2,5);app.formatEditorSelection("**","**");
                check(app.linear.getText().toString().equals("abc"),"reapplying bold did not remove it");
                app.linear.setText("<u>a</u>bc");app.linear.setSelection(3,10);app.formatEditorSelection("<u>","</u>");
                check(app.linear.getText().toString().equals("<u>abc</u>"),"partially formatted underline selection did not merge");
                android.text.SpannableString editable=new android.text.SpannableString(markdown);app.applyMarkdownStyles(editable);
                check(editable.getSpans(0,editable.length(),MainActivity.MarkdownMarkerSpan.class).length==rendered.markers.size(),"editor did not hide every Markdown marker");
                try { original = app.backupJson(); } catch (Exception e) { throw new AssertionError(e); }
                app.notes.clear(); app.trash.clear(); app.labels.clear(); app.pinnedLabels.clear(); app.notebooks.clear();
                first = app.new Note(); first.title = "回归笔记 A"; second = app.new Note(); second.title = "回归笔记 B";
                app.notes.add(first); app.notes.add(second); app.openNote(first, "");
                app.saveUndo(); first.title = "A 已编辑"; Collections.swap(app.notes, 0, 1); app.current = 0;
                app.undo(); check(app.notes.get(0) == second && second.title.equals("回归笔记 B"), "undo overwrote B with stale index");
                check(app.note.id.equals(first.id) && app.note.title.equals("回归笔记 A"), "undo did not restore A");
                app.redo(); check(app.note.title.equals("A 已编辑"), "redo failed");
                app.buildHome(); app.openNote(second, ""); check(app.undo.isEmpty() && app.redo.isEmpty(), "history leaked into B");
                app.undo(); check(app.notes.size() == 2 && second.title.equals("回归笔记 B"), "undo changed another note");
                app.isLinear = false; top = block("第一个片段", 2); middle = block("未选中的片段", 6); last = block("最后一个片段", 10); app.persist(); app.build();
            });
            main(() -> {
                app.blockSelectionMode = true; app.selectedBlocks.add(top); app.selectedBlocks.add(last); app.build();
                String before; try { before = app.note.json().toString(); } catch (Exception e) { throw new AssertionError(e); }
                app.openSelectionTextPreview(); check(app.selectionTextPreview, "selected text preview did not open");
                check(app.selectionPreviewText.equals(top.text + "\n\n" + last.text), "preview included unselected text");
                try { check(before.equals(app.note.json().toString()), "preview mutated original blocks"); } catch (Exception e) { throw new AssertionError(e); }
            });
            screenshot("qa-selected-text.png");
            main(() -> { app.closeSelectionTextPreview(); check(app.blockSelectionMode && app.selectedBlocks.size() == 2, "preview lost selection"); app.toggleBlockSelection(); });
            float[] center = new float[2];
            main(() -> { android.graphics.RectF rect = app.board.blockRect(top); center[0] = rect.centerX(); center[1] = rect.centerY(); });
            touch(MotionEvent.ACTION_DOWN, center[0], center[1]); touch(MotionEvent.ACTION_UP, center[0], center[1]); SystemClock.sleep(400); waitForIdleSync();
            main(() -> check(app.board.selectedBlock == top && app.board.resizeSelected == null, "single tap did not select logical ports"));
            touch(MotionEvent.ACTION_DOWN, center[0], center[1]); SystemClock.sleep(700); touch(MotionEvent.ACTION_UP, center[0], center[1]);
            main(() -> check(app.board.resizeSelected == top && app.board.selectedBlock == top, "long press release lost resize handles"));
            screenshot("qa-long-press.png");
            float[] empty = new float[2]; int blockCount;
            main(() -> { app.board.resizeSelected = null; empty[0] = app.board.offsetX + app.board.cell * 6.5f; empty[1] = app.board.offsetY + app.board.cell * 4.5f; });
            blockCount = app.note.blocks.size();
            touch(MotionEvent.ACTION_DOWN, empty[0], empty[1]); touch(MotionEvent.ACTION_UP, empty[0], empty[1]); SystemClock.sleep(450); waitForIdleSync();
            main(() -> check(app.blockDialog == null && app.note.blocks.size() == blockCount, "single tap on empty canvas opened a new block"));
            touch(MotionEvent.ACTION_DOWN, empty[0], empty[1]); touch(MotionEvent.ACTION_UP, empty[0], empty[1]);
            touch(MotionEvent.ACTION_DOWN, empty[0], empty[1]); touch(MotionEvent.ACTION_UP, empty[0], empty[1]); waitForIdleSync();
            main(() -> { check(app.blockDialog != null && app.blockDialog.isShowing(), "double tap on empty canvas did not open a new block"); app.blockDialog.dismiss(); });
            main(() -> {
                app.labels.clear(); app.labels.addAll(Arrays.asList("父标签", "父标签/子标签", "父标签/子标签/孙标签"));
                app.collapsedLabelFolders.clear(); app.collapsedLabelFolders.addAll(app.labels); app.drawerRootsCollapsed = true;
                app.showDrawer(); check(app.visibleDrawerTags().size() == 1, "tags not initially collapsed");
                MainActivity.DrawerTagRow rootTag=(MainActivity.DrawerTagRow)app.sideTagAdapter.getView(0,null,null);rootTag.disclosure.performClick(); check(app.visibleDrawerTags().size() == 2, "individual expand failed after collapse");
                MainActivity.DrawerTagRow childTag=(MainActivity.DrawerTagRow)app.sideTagAdapter.getView(1,null,null);childTag.disclosure.performClick(); check(app.visibleDrawerTags().size() == 3, "nested tag expand failed");
                TextView all = app.sideDrawerOverlay.findViewWithTag("tag-collapse-all"); all.performClick(); check(app.visibleDrawerTags().size() == 1, "collapse all failed");
                rootTag=(MainActivity.DrawerTagRow)app.sideTagAdapter.getView(0,null,null);rootTag.disclosure.performClick(); check(app.visibleDrawerTags().size() == 2, "individual expand failed after collapse all"); app.closeDrawer();
                app.buildHome(); for (MainActivity.Note item : app.notes) if (item.id.equals(first.id)) { first = item; break; } app.notes.remove(first); app.trash.add(first); app.showTrash(); checkStandalonePage();
            });
            screenshot("qa-trash-page.png");
            main(() -> {
                app.buildTrashReader(first); check(app.screen == 9 && app.trash.contains(first) && !app.notes.contains(first), "reading trash restored or edited a note");
                app.handleBack(); checkStandalonePage();
                app.trashSelectionMode = true; app.selectedTrash.add(first); app.buildTrashKeepingPosition();
                check(app.trashRestoreButton.isEnabled(), "selected trash restore disabled"); app.trashRestoreButton.performClick();
                check(app.trash.isEmpty() && app.notes.contains(first) && app.screen == 8, "batch restore failed");
            });
            previewRegression();
            notebookRegression();
            result.putString("stream", "PASS: editor regression, notebook creation/rename/color/multi-select/delete, note assignment, combined walk filters, backup round trip and legacy defaults\n");
        } catch (Throwable failure) { status = Activity.RESULT_CANCELED; result.putString("stream", android.util.Log.getStackTraceString(failure)); }
        finally {
            if (app != null && original != null) main(() -> { app.finishEditorSession(); app.screen = 0; app.notes.clear(); app.trash.clear(); app.labels.clear(); app.pinnedLabels.clear(); app.timeline.clear(); app.notebooks.clear(); app.walkNotebookId=null; app.activeNotebookId=null; try { app.parseStore(original); } catch (Exception e) { throw new AssertionError(e); } app.persist(); app.buildHome(); });
            getTargetContext().getSharedPreferences("suiyi", 0).edit().putBoolean("welcomeLetterSeen", welcome).commit();
        }
        finish(status, result);
    }

    void clickUi(String label) {
        waitForIdleSync();
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        check(root!=null,"no active window for "+label);
        for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label)) {
            if(node.getText()!=null&&node.getText().toString().equals(label)) {
                while(node!=null&&!node.isClickable())node=node.getParent();
                check(node!=null&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"cannot click "+label);
                waitForIdleSync();return;
            }
        }
        throw new AssertionError("missing UI option: "+label);
    }
    void inputUi(String value) {
        waitForIdleSync();
        android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        check(setInput(root,value),"missing name input");waitForIdleSync();
    }
    boolean setInput(android.view.accessibility.AccessibilityNodeInfo node,String value) {
        if(node==null)return false;
        if(node.isEditable()){Bundle args=new Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);}
        for(int i=0;i<node.getChildCount();i++)if(setInput(node.getChild(i),value))return true;
        return false;
    }
    android.view.View notebookCover() {
        android.widget.FrameLayout area=(android.widget.FrameLayout)app.root.getChildAt(1);
        android.widget.ScrollView scroll=(android.widget.ScrollView)area.getChildAt(0);
        android.view.ViewGroup list=(android.view.ViewGroup)scroll.getChildAt(0);
        return ((android.view.ViewGroup)list.getChildAt(0)).getChildAt(0);
    }
    void notebookRegression() throws Exception {
        main(()->{app.buildNotebooks();app.nameNotebook(null);});inputUi("日常手记");clickUi("保存");
        main(()->{check(app.notebooks.size()==1,"notebook not created");app.nameNotebook(app.notebooks.get(0));});
        inputUi("生活手记");clickUi("保存");
        main(()->{check(app.notebooks.get(0).name.equals("生活手记"),"rename failed");app.notebookMenu(notebookCover(),app.notebooks.get(0));});
        clickUi("改变颜色");clickUi("雾蓝");
        main(()->{
            check(app.notebooks.get(0).color==app.notebookColors[2],"color change failed");
            app.notebooks.add(app.new Notebook("旅行"));app.buildNotebooks();
        });screenshot("qa-notebooks.png");
        main(()->{
            notebookCover().performLongClick();check(app.notebookSelection&&app.selectedNotebooks.size()==1,"long press selection failed");
            app.handleBack();check(!app.notebookSelection&&app.screen==10,"back did not leave selection");
            notebookCover().performClick();check(app.screen==11,"cover did not open notes");app.createNote();
            check(app.note.notebookId.equals(app.activeNotebookId),"new note in notebook should inherit the notebook");app.isLinear=true;app.buildEditor();app.linear.setText("记本回归测试");
            MainActivity.Note created=app.note;app.handleBack();check(app.screen==11&&app.notes.contains(created)&&app.notebookCount(app.activeNotebookId)==1,"notebook note disappeared on exit");
            app.openNote(created,"");app.assignNotebook(app.note);
        });clickUi("生活手记");
        main(()->{
            check(app.note.notebookId.equals(app.notebooks.get(0).id),"assignment failed");app.handleBack();check(app.screen==11,"editor did not return to notebook");
            app.walkNotebookId=app.notebooks.get(0).id;app.walkFilter=0;app.refreshWalkNotes();check(app.walkNotes.size()==1,"notebook walk filter leaked other notes");
            app.walkFilter=3;app.refreshWalkNotes();check(app.walkNotes.isEmpty(),"date and notebook filters did not combine");
            app.walkFilter=0;app.buildWalk();app.showWalkFilter();
        });clickUi("选择记本（生活手记）");clickUi("无记本");
        main(()->{
            for(MainActivity.Note n:app.walkNotes)check(n.notebookId.isEmpty(),"unassigned walk filter failed");
            try{
                JSONObject snapshot=app.backupJson();app.notebooks.clear();app.notes.clear();app.trash.clear();app.timeline.clear();app.pinnedLabels.clear();app.parseStore(snapshot);
                check(app.notebooks.size()==2&&app.notebooks.get(0).color==app.notebookColors[2],"notebook backup round trip failed");
                check(app.new Note(new JSONObject()).notebookId.isEmpty(),"old note no longer defaults to no notebook");
            }catch(Exception e){throw new AssertionError(e);}
            app.buildNotebooks();notebookCover().performLongClick();
            android.view.ViewGroup shelf=(android.view.ViewGroup)notebookCover().getParent();shelf.getChildAt(1).performClick();
            check(app.selectedNotebooks.size()==2,"multi select failed");app.deleteNotebooks(app.selectedNotebooks);
        });clickUi("删除");
        main(()->{
            check(app.notebooks.isEmpty(),"notebooks not deleted");boolean found=false;for(MainActivity.Note n:app.notes){check(n.notebookId.isEmpty(),"deleted notebook reference retained");if(n.text.equals("记本回归测试"))found=true;}check(found,"deleting notebook deleted its note");
        });
    }
    void previewRegression() {
        main(()->{
            app.note=first;app.isLinear=true;app.note.text="预览内容保持只读";app.previewMode=false;app.buildEditor();
            app.togglePreviewMode();check(app.previewMode&&app.linear==null,"preview still exposes an editable text field");
            check(app.content.getChildAt(0) instanceof android.widget.ScrollView,"preview is not scrollable");
            android.widget.ScrollView scroll=(android.widget.ScrollView)app.content.getChildAt(0);
            android.view.ViewGroup page=(android.view.ViewGroup)scroll.getChildAt(0);TextView body=(TextView)page.getChildAt(0);
            check(body.getText().toString().equals("预览内容保持只读"),"preview text does not match the note");
            android.view.MotionEvent down=android.view.MotionEvent.obtain(10,10,android.view.MotionEvent.ACTION_DOWN,dp(24),dp(24),0);
            android.view.MotionEvent up=android.view.MotionEvent.obtain(10,20,android.view.MotionEvent.ACTION_UP,dp(24),dp(24),0);
            body.dispatchTouchEvent(down);body.dispatchTouchEvent(up);down.recycle();up.recycle();
            check(app.previewMode&&app.note.text.equals("预览内容保持只读"),"preview touch changed editor state or note text");
            app.togglePreviewMode();check(!app.previewMode&&app.linear!=null,"write icon did not restore editing");
            app.linear.setText("退出预览后继续编辑");app.togglePreviewMode();
            check(app.previewMode&&app.note.text.equals("退出预览后继续编辑"),"preview transition lost unsaved text");
            app.togglePreviewMode();
        });
    }
    int dp(int value) { return (int)(app.getResources().getDisplayMetrics().density*value+.5f); }
}
