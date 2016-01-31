package training.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.wm.ex.WindowManagerEx;
import training.lesson.dialogs.LessonDialog;

import java.awt.*;

/**
 * Created by karashevich on 15/01/16.
 */
public class OpenLessonDialog extends AnAction {

    @Override
    public void actionPerformed(AnActionEvent e) {
        // TODO: insert action logic here
        final LessonDialog lessonDialog = LessonDialog.createForProject(e.getProject());
        lessonDialog.setContent("blockCaret.html");
        lessonDialog.show();
    }
}