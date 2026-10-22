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
                try { original = app.backupJson(); } catch (Exception e) { throw new AssertionError(e); }
                app.notes.clear(); app.trash.clear(); app.labels.clear(); app.pinnedLabels.clear();
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
            result.putString("stream", "PASS: real Android undo/redo identity, session isolation, read-only selection preview, tap/long-press gestures, nested tag collapse/expand, trash navigation and batch restore\n");
        } catch (Throwable failure) { status = Activity.RESULT_CANCELED; result.putString("stream", android.util.Log.getStackTraceString(failure)); }
        finally {
            if (app != null && original != null) main(() -> { app.finishEditorSession(); app.screen = 0; app.notes.clear(); app.trash.clear(); app.labels.clear(); app.pinnedLabels.clear(); app.timeline.clear(); try { app.parseStore(original); } catch (Exception e) { throw new AssertionError(e); } app.persist(); app.buildHome(); });
            getTargetContext().getSharedPreferences("suiyi", 0).edit().putBoolean("welcomeLetterSeen", welcome).commit();
        }
        finish(status, result);
    }
}
