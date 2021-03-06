package training.actions

import com.intellij.ide.scratch.ScratchFileService
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.ide.startup.StartupManagerEx
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ui.configuration.ProjectSettingsService
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import training.lang.LangManager
import training.lang.LangSupport
import training.learn.CourseManager
import training.learn.LearnBundle
import training.learn.Module
import training.learn.NewLearnProjectUtil
import training.learn.dialogs.AskToSwitchToLearnProjectBackDialog
import training.learn.dialogs.SdkModuleProblemDialog
import training.learn.exceptons.*
import training.learn.lesson.Lesson
import training.learn.lesson.LessonProcessor
import training.learn.lesson.listeners.NextLessonListener
import training.learn.lesson.listeners.StatisticLessonListener
import training.ui.LearnToolWindowFactory
import training.ui.UiManager
import training.util.findLanguageByID
import java.awt.FontFormatException
import java.io.IOException
import java.util.concurrent.ExecutionException

/**
 * @author Sergey Karashevich
 */
class OpenLessonAction : AnAction() {

  private val LOG = Logger.getInstance(this.javaClass)
  private var myProject: Project? = null

  override fun actionPerformed(e: AnActionEvent) {

    val lesson = e.getData(LESSON_DATA_KEY)
    val whereToStartLessonProject = e.getData(PROJECT_WHERE_TO_OPEN_DATA_KEY)
    myProject = e.project

    if (lesson != null && myProject != null && whereToStartLessonProject != null) {
      LOG.debug("${myProject?.name}: Action performed -> openLesson(${lesson.name})")
      openLesson(myProject!!, whereToStartLessonProject, lesson)
    } else {
      //in case of starting from Welcome Screen
      LOG.debug("${myProject?.name}: Action performed -> openLearnToolWindowAndShowModules(${lesson?.name}))")
      val myLearnProject = initLearnProject(myProject)
      openLearnToolWindowAndShowModules(myLearnProject!!)
    }
  }

  @Synchronized
  @Throws(BadModuleException::class, BadLessonException::class, IOException::class, FontFormatException::class, InterruptedException::class, ExecutionException::class, LessonIsOpenedException::class)
  private fun openLesson(project: Project, projectWhereToStartLesson: Project, lesson: Lesson) {
    LOG.debug("${projectWhereToStartLesson.name}: start openLesson method")
    try {
      val langSupport = LangManager.getInstance().getLangSupport()
      if (lesson.isOpen) throw LessonIsOpenedException(lesson.name + " is opened")

      //If lesson doesn't have parent module
      if (lesson.module == null)
        throw BadLessonException("Unable to open lesson without specified module")

      val scratchFileName = "Learning"
      val vf: VirtualFile?
      var learnProject = CourseManager.instance.learnProject
      LOG.debug("${projectWhereToStartLesson.name}: trying to get cached LearnProject ${learnProject != null}")
      if (learnProject == null) learnProject = findLearnProjectInOpenedProjects(langSupport)
      LOG.debug("${projectWhereToStartLesson.name}: trying to find LearnProject in opened projects ${learnProject != null}")
      if (learnProject != null) CourseManager.instance.learnProject = learnProject

      if (lesson.module == null || lesson.module!!.moduleType == Module.ModuleType.SCRATCH) {
        LOG.debug("${projectWhereToStartLesson.name}: scratch based lesson")
        CourseManager.instance.checkEnvironment(projectWhereToStartLesson)
        vf = getScratchFile(projectWhereToStartLesson, lesson, scratchFileName)
      } else { //if this file should be opened in LearnProject
        //0. learnProject == null but this project is LearnProject then just getFileInLearnProject
        if ((learnProject == null || learnProject.isDisposed) && projectWhereToStartLesson.name == langSupport.defaultProjectName) {
          LOG.debug("${projectWhereToStartLesson.name}: 0. learnProject is null but the current project (${projectWhereToStartLesson.name}) is LearnProject then just getFileInLearnProject")
          CourseManager.instance.learnProject = projectWhereToStartLesson
          vf = getFileInLearnProject(lesson)
          //1. learnProject == null and current project has different name then initLearnProject and register post startup open lesson
        } else if (learnProject == null && projectWhereToStartLesson.name != langSupport.defaultProjectName) {
          LOG.debug("${projectWhereToStartLesson.name}: 1. learnProject is null and current project ((${projectWhereToStartLesson.name})) has different name. Start LearnProject and register post startup open lesson")
          val myLearnProject = initLearnProject(projectWhereToStartLesson) ?: return
          // in case of user aborted to create a LearnProject
          LOG.debug("${projectWhereToStartLesson.name}: 1. ... LearnProject has been started")
          openLessonWhenLearnProjectStart(lesson, myLearnProject)
          LOG.debug("${projectWhereToStartLesson.name}: 1. ... open lesson when learn project has been started")
          return
          //2. learnProject != null and learnProject is disposed then reinitProject and getFileInLearnProject
        } else if (learnProject!!.isDisposed) {
          LOG.debug("${projectWhereToStartLesson.name}: 2. LearnProject is disposed. Start learn project again")
          val myLearnProject = initLearnProject(projectWhereToStartLesson) ?: return
          LOG.debug("${projectWhereToStartLesson.name}: 2. ... LearnProject has been started")
          // in case of user aborted to create a LearnProject
          openLessonWhenLearnProjectStart(lesson, myLearnProject)
          LOG.debug("${projectWhereToStartLesson.name}: 2. ... open lesson when learn project has been started")
          return
          //3. learnProject != null and learnProject is opened but not focused then focus Project and getFileInLearnProject
        } else if (learnProject.isOpen && projectWhereToStartLesson != learnProject) {
          LOG.debug("${projectWhereToStartLesson.name}: 3. LearnProject is opened but not focused. Ask user to focus to LearnProject")
          val currentProject = projectWhereToStartLesson ?: return
          askSwitchToLearnProjectBack(learnProject, currentProject)
          return
          //4. learnProject != null and learnProject is opened and focused getFileInLearnProject
        } else if (learnProject.isOpen && projectWhereToStartLesson == learnProject) {
          LOG.debug("${projectWhereToStartLesson.name}: 4. LearnProject is the current project")
          vf = getFileInLearnProject(lesson)
        } else {
          throw Exception("Unable to start Learn project")
        }
      }

      LOG.debug("${projectWhereToStartLesson.name}: VirtualFile for lesson has been created/found")
      val currentProject = if (lesson.module != null && lesson.module!!.moduleType != Module.ModuleType.SCRATCH) CourseManager.instance.learnProject!! else projectWhereToStartLesson
      if (vf == null) return  //if user aborts opening lesson in LearnProject or Virtual File couldn't be computed

      LOG.debug("${projectWhereToStartLesson.name}: Add listeners to lesson")
      addNextLessonListenerIfNeeded(currentProject, lesson)
      addStatisticLessonListenerIfNeeded(currentProject, lesson)

      //open next lesson if current is passed
      LOG.debug("${projectWhereToStartLesson.name}: Set lesson view")
      UiManager.setLessonView()
      LOG.debug("${projectWhereToStartLesson.name}: Lesson onStart()")
      lesson.onStart()

      //to start any lesson we need to do 4 steps:
      //1. open editor or find editor
      LOG.debug("${projectWhereToStartLesson.name}: PREPARING TO START LESSON:")
      LOG.debug("${projectWhereToStartLesson.name}: 1. Open or find editor")
      var textEditor: TextEditor? = null
      if (FileEditorManager.getInstance(projectWhereToStartLesson).isFileOpen(vf)) {
        val editors = FileEditorManager.getInstance(projectWhereToStartLesson).getEditors(vf)
        for (fileEditor in editors) {
          if (fileEditor is TextEditor) {
            textEditor = fileEditor
          }
        }
      }
      if (textEditor == null) {
        val editors = FileEditorManager.getInstance(projectWhereToStartLesson).openFile(vf, true, true)
        for (fileEditor in editors) {
          if (fileEditor is TextEditor) {
            textEditor = fileEditor
          }
        }
      }
      if (textEditor!!.editor.isDisposed) throw Exception("Editor is already disposed!")

      //2. set the focus on this editor
      //FileEditorManager.getInstance(project).setSelectedEditor(vf, TextEditorProvider.getInstance().getEditorTypeId());
      LOG.debug("${projectWhereToStartLesson.name}: 2. Set the focus on this editor")
      FileEditorManager.getInstance(projectWhereToStartLesson).openEditor(OpenFileDescriptor(projectWhereToStartLesson, vf), true)

      //3. update tool window
      LOG.debug("${projectWhereToStartLesson.name}: 3. Update tool window")
      UiManager.clearLearnPanels()

      //4. Process lesson
      LOG.debug("${projectWhereToStartLesson.name}: 4. Process lesson")
      LessonProcessor.process(projectWhereToStartLesson, lesson, textEditor.editor)

    } catch (noSdkException: NoSdkException) {
      Messages.showMessageDialog(projectWhereToStartLesson, LearnBundle.message("dialog.noSdk.message", LangManager.getInstance().getLanguageDisplayName()), LearnBundle.message("dialog.noSdk.title"), Messages.getErrorIcon())
      if (ProjectSettingsService.getInstance(projectWhereToStartLesson).chooseAndSetSdk() != null) openLesson(projectWhereToStartLesson, projectWhereToStartLesson, lesson)
    } catch (noSdkException: InvalidSdkException) {
      Messages.showMessageDialog(projectWhereToStartLesson, LearnBundle.message("dialog.noSdk.message", LangManager.getInstance().getLanguageDisplayName()), LearnBundle.message("dialog.noSdk.title"), Messages.getErrorIcon())
      if (ProjectSettingsService.getInstance(projectWhereToStartLesson).chooseAndSetSdk() != null) openLesson(projectWhereToStartLesson, projectWhereToStartLesson, lesson)
    } catch (noJavaModuleException: NoJavaModuleException) {
      showModuleProblemDialog(projectWhereToStartLesson)
    } catch (e: Exception) {
      e.printStackTrace()
    }

  }

  private fun addNextLessonListenerIfNeeded(currentProject: Project, lesson: Lesson) {
    val lessonListener = NextLessonListener(currentProject)
    if (!lesson.lessonListeners.any { it is NextLessonListener })
      lesson.addLessonListener(lessonListener)
  }

  private fun addStatisticLessonListenerIfNeeded(currentProject: Project, lesson: Lesson) {
    val statLessonListener = StatisticLessonListener(currentProject)
    if (!lesson.lessonListeners.any { it is StatisticLessonListener })
      lesson.addLessonListener(statLessonListener)
  }

  //
  private fun openLearnToolWindowAndShowModules(myLearnProject: Project) {
    if (myLearnProject.isOpen && myLearnProject.isInitialized) {
      showModules(myLearnProject)
    } else {
      StartupManager.getInstance(myLearnProject).registerPostStartupActivity { showModules(myLearnProject) }
    }
  }

  private fun showModules(project: Project) {
    val toolWindowManager = ToolWindowManager.getInstance(project)
    val learnToolWindow = toolWindowManager.getToolWindow(LearnToolWindowFactory.LEARN_TOOL_WINDOW)
    if (learnToolWindow != null) {
      learnToolWindow.show(null)
      try {
        UiManager.setModulesView()
      } catch (e: Exception) {
        e.printStackTrace()
      }

    }
  }

  private fun openLessonWhenLearnProjectStart(lesson: Lesson?, myLearnProject: Project) {
    val startupManager = StartupManager.getInstance(myLearnProject)
    if (startupManager is StartupManagerEx && startupManager.postStartupActivityPassed()) LOG.warn("Post startup activity has been already passed")
    startupManager.registerPostStartupActivity {
      val toolWindowManager = ToolWindowManager.getInstance(myLearnProject)
      val learnToolWindow = toolWindowManager.getToolWindow(LearnToolWindowFactory.LEARN_TOOL_WINDOW)
      if (learnToolWindow != null) {
        learnToolWindow.show(null)
        try {
          UiManager.setLessonView()
          LOG.debug("${myProject?.name}: openLessonWhenLearnProjectStart: lesson: (${lesson?.name}) learnProject: (${myLearnProject.name})")
          CourseManager.instance.openLesson(myLearnProject, lesson)
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }
    }
  }

  @Throws(IOException::class)
  private fun getScratchFile(project: Project, lesson: Lesson?, filename: String): VirtualFile? {
    var vf: VirtualFile? = null
    assert(lesson != null)
    assert(lesson!!.module != null)
    val myLanguage = lesson.lang

    val languageByID = findLanguageByID(myLanguage)
    if (CourseManager.instance.mapModuleVirtualFile.containsKey(lesson.module)) {
      vf = CourseManager.instance.mapModuleVirtualFile[lesson.module]
      ScratchFileService.getInstance().scratchesMapping.setMapping(vf, languageByID)
    }
    if (vf == null || !vf.isValid) {
      //while module info is not stored

      //find file if it is existed
      vf = ScratchFileService.getInstance().findFile(ScratchRootType.getInstance(), filename, ScratchFileService.Option.existing_only)
      if (vf != null) {
        FileEditorManager.getInstance(project).closeFile(vf)
        ScratchFileService.getInstance().scratchesMapping.setMapping(vf, languageByID)
      }

      if (vf == null || !vf.isValid) {
        vf = ScratchRootType.getInstance().createScratchFile(project, filename, languageByID, "")
        assert(vf != null)
      }
      if (lesson.module != null) CourseManager.instance.registerVirtualFile(lesson.module!!, vf!!)
    }
    return vf
  }

  //
//    private fun showSdkProblemDialog(project: Project, sdkMessage: String) {
//        val dialog = SdkProjectProblemDialog(project, sdkMessage)
//        dialog.show()
//    }
//
  private fun showModuleProblemDialog(project: Project) {
    val dialog = SdkModuleProblemDialog(project)
    dialog.show()
  }

  private fun askSwitchToLearnProjectBack(learnProject: Project, currentProject: Project) {
    val dialog = AskToSwitchToLearnProjectBackDialog(learnProject, currentProject)
    dialog.show()
  }

  //
  @Throws(IOException::class)
  private fun getFileInLearnProject(lesson: Lesson): VirtualFile {

    if (lesson.module == null) throw Exception("Error: cannot create learning file in project for a lesson (${lesson.name}) without module (or module is null)")
    val function = object : Computable<VirtualFile> {

      override fun compute(): VirtualFile {
        val learnProject = CourseManager.instance.learnProject!!
        val sourceRootFile = ProjectRootManager.getInstance(learnProject).contentSourceRoots[0]
        val myLanguage = lesson.lang
        val languageByID = findLanguageByID(myLanguage)
        val extensionFile = languageByID!!.associatedFileType!!.defaultExtension

        var fileName = "Test." + extensionFile
        if (lesson.module != null) {
          fileName = lesson.module!!.sanitizedName + "." + extensionFile
        }

        var lessonVirtualFile: VirtualFile? = null
        for (file in ProjectRootManager.getInstance(learnProject).contentSourceRoots) {
          if (file.name == fileName) {
            lessonVirtualFile = file
            break
          } else {
            lessonVirtualFile = file.findChild(fileName)
            if (lessonVirtualFile != null) {
              break
            }
          }
        }
        if (lessonVirtualFile == null) {
          try {
            lessonVirtualFile = sourceRootFile.createChildData(this, fileName)
          } catch (e: IOException) {
            e.printStackTrace()
          }

        }

        if (lesson.module != null) CourseManager.instance.registerVirtualFile(lesson.module!!, lessonVirtualFile!!)
        return lessonVirtualFile!!
      }
    }

    val vf = ApplicationManager.getApplication().runWriteAction(function)
    assert(vf is VirtualFile)
    return vf
  }

  private fun initLearnProject(projectToClose: Project?): Project? {
    var myLearnProject: Project? = null
    val langSupport = LangManager.getInstance().getLangSupport()
    //if projectToClose is open
    myLearnProject = findLearnProjectInOpenedProjects(langSupport)
    if (myLearnProject != null) return myLearnProject

    if (myLearnProject == null || myLearnProject.projectFile == null) {

      if (!ApplicationManager.getApplication().isUnitTestMode && projectToClose != null)
        if (!NewLearnProjectUtil.showDialogOpenLearnProject(projectToClose))
          return null //if user abort to open lesson in a new Project
      if (CourseManager.instance.learnProjectPath != null) {
        try {
          myLearnProject = ProjectManager.getInstance().loadAndOpenProject(CourseManager.instance.learnProjectPath!!)
        } catch (e: Exception) {
          e.printStackTrace()
        }

      } else {
        try {
          myLearnProject = NewLearnProjectUtil.createLearnProject(projectToClose, langSupport)
        } catch (e: IOException) {
          e.printStackTrace()
        }

      }

      langSupport.applyToProjectAfterConfigure().invoke(myLearnProject!!)
    }

    CourseManager.instance.learnProject = myLearnProject

    assert(CourseManager.instance.learnProject != null)

    CourseManager.instance.learnProjectPath = CourseManager.instance.learnProject!!.basePath

    return myLearnProject

  }

  private fun findLearnProjectInOpenedProjects(langSupport: LangSupport): Project? {
    val openProjects = ProjectManager.getInstance().openProjects
    return openProjects.firstOrNull { it.name == langSupport.defaultProjectName }
  }

  companion object {
    val LESSON_DATA_KEY = DataKey.create<Lesson>("LESSON_DATA_KEY")
    val PROJECT_WHERE_TO_OPEN_DATA_KEY = DataKey.create<Project>("PROJECT_WHERE_TO_OPEN_DATA_KEY")
  }

}
