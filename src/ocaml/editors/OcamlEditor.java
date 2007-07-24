package ocaml.editors;

import ocaml.OcamlPlugin;
import ocaml.debugging.DebugVisuals;
import ocaml.editor.completion.CompletionJob;
import ocaml.editors.util.OcamlCharacterPairMatcher;
import ocaml.natures.OcamlNatureMakefile;
import ocaml.parser.Def;
import ocaml.popup.actions.CompileProjectAction;
import ocaml.preferences.PreferenceConstants;
import ocaml.views.outline.OcamlOutlineControl;
import ocaml.views.outline.OutlineJob;
import ocaml.views.outline.SynchronizeOutlineJob;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceDescription;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextListener;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.PaintManager;
import org.eclipse.jface.text.TextEvent;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.TextViewer;
import org.eclipse.jface.text.source.MatchingCharacterPainter;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IFileEditorInput;
import org.eclipse.ui.editors.text.TextEditor;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.views.contentoutline.IContentOutlinePage;

/**
 * Configures the O'Caml editor, and manages the events raised by this editor, by overloading
 * methods of the TextEditor class.
 */
public class OcamlEditor extends TextEditor {

	/**
	 * used to notify that the outline job is finished (notified by OutlineJob to
	 * OcamlHyperlinkDetector)
	 */
	public Object outlineSignal = new Object();

	private OcamlOutlineControl outline;

	protected PaintManager paintManager;

	protected MatchingCharacterPainter matchingCharacterPainter;

	private OutlineJob outlineJob = null;

	private SynchronizeOutlineJob synchronizeOutlineJob = null;

	public static final String ML_EDITOR_ID = "ocaml.editors.mlEditor";

	public OcamlEditor() {
		this.setSourceViewerConfiguration(new OcamlSourceViewerConfig(this));
		// this.setRangeIndicator(new DefaultRangeIndicator());
	}

	/** The debug cursor (as a red I-beam) */
	private DebugVisuals caret;

	public ITextViewer getTextViewer() {
		return this.getSourceViewer();
	}

	@Override
	protected void createActions() {
		super.createActions();

		try {
			paintManager = new PaintManager(getSourceViewer());
			matchingCharacterPainter = new MatchingCharacterPainter(getSourceViewer(),
					new OcamlCharacterPairMatcher());
			matchingCharacterPainter.setColor(new Color(Display.getCurrent(),
					new RGB(160, 160, 160)));
			paintManager.addPainter(matchingCharacterPainter);

			/*
			 * AnnotationPainter annotationPainter = new AnnotationPainter(getSourceViewer(), null);
			 * annotationPainter.addAnnotationType("Ocaml.ocamlSyntaxErrorMarker");
			 * 
			 * paintManager.addPainter(annotationPainter);
			 */

		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		try {
			StyledText text = this.getSourceViewer().getTextWidget();
			caret = new DebugVisuals(text);
			text.addPaintListener(caret);

			IFile file = getFileBeingEdited();
			if (file != null)
				caret.setFilename(file.getName());
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		// parse the O'Caml libraries in a background thread
		try {
			CompletionJob job = new CompletionJob("Parsing ocaml library mli files", null);
			job.setPriority(CompletionJob.DECORATE);
			job.schedule();
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		try {
			OcamlPlugin.getInstance().checkPaths();
		} catch (Exception e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		this.getSourceViewer().addTextListener(new ITextListener() {

			public void textChanged(TextEvent event) {
				if (event.getDocumentEvent() != null)
					rebuildOutline(500);
			}
		});
	}

	@Override
	/** Initializes the context for the O'Caml editor shortcuts */
	protected void initializeKeyBindingScopes() {
		setKeyBindingScopes(new String[] { "Ocaml.editor.context" });
	}

	@Override
	public void doSetInput(IEditorInput input) throws CoreException {
		super.doSetInput(input);
		rebuildOutline(100);

		// parse the project interfaces in a background thread
		CompletionJob job = new CompletionJob("Parsing ocaml project mli files", this.getProject());
		job.setPriority(CompletionJob.DECORATE);
		job.schedule();

		if (input instanceof IFileEditorInput) {
			IFileEditorInput fileEditorInput = (IFileEditorInput) input;

			if (caret != null)
				caret.setFilename(fileEditorInput.getFile().getName());
		}

		/*
		 * if (this.fOutlinePage != null) this.fOutlinePage.setInput(input);
		 */
	}

	/**
	 * We give the outline to Eclipse when it asks for an adapter with the outline class.
	 */
	@Override
	public Object getAdapter(Class required) {
		if (IContentOutlinePage.class.equals(required)) {
			if ("mlp".equals(this.getFileBeingEdited().getFileExtension())
					|| "ml4".equals(this.getFileBeingEdited().getFileExtension()))
				return null;

			if (this.outline == null)
				this.outline = new OcamlOutlineControl(this);
			rebuildOutline(100);
			return this.outline;
		}
		return super.getAdapter(required);
	}

	/*
	 * public Hashtable<Integer,String> getHashTypes() { return this.hashtableTypes; }
	 */

	@Override
	public void createPartControl(Composite parent) {
		super.createPartControl(parent);
		StyledText styledText = this.getSourceViewer().getTextWidget();
		styledText.setTabs(getTabSize());
	}

	public static int getTabSize() {
		return OcamlPlugin.getInstance().getPreferenceStore().getInt(
				PreferenceConstants.P_EDITOR_TABS);
	}

	public void redraw() {
		getSourceViewer().getTextWidget().redraw();
	}

	/** Return the caret position in the editor */
	public int getCaretOffset() {
		ISelection sel = this.getSelectionProvider().getSelection();
		if (sel instanceof TextSelection) {
			TextSelection selection = (TextSelection) sel;
			return selection.getOffset();
		}

		OcamlPlugin.logError("selection is not instanceof TextSelection");
		return -1;
	}

	/** Synchronize the outline with the cursor line in the editor */
	public void synchronizeOutline() {

		if (this.outline == null)
			return;

		if (synchronizeOutlineJob == null)
			synchronizeOutlineJob = new SynchronizeOutlineJob("Synchronizing outline with editor");
		else if (synchronizeOutlineJob.getState() == SynchronizeOutlineJob.RUNNING)
			return;
		// only one job at a time
		else
			synchronizeOutlineJob.cancel();

		synchronizeOutlineJob.setPriority(CompletionJob.DECORATE);
		synchronizeOutlineJob.setEditor(this);
		synchronizeOutlineJob.setOutlineJob(outlineJob);
		synchronizeOutlineJob.schedule(50);
	}

	public IContainer getWorkingLocation() {
		FileEditorInput editorInput = (FileEditorInput) this.getEditorInput();
		if (editorInput == null || editorInput.getFile() == null) {
			return null;
		}
		return editorInput.getFile().getParent();
	}

	public IProject getProject() {
		FileEditorInput editorInput = (FileEditorInput) this.getEditorInput();
		if (editorInput == null || editorInput.getFile() == null) {
			return null;
		}
		return editorInput.getFile().getProject();
	}

	public IFile getFile(String filename) {
		IContainer container = this.getWorkingLocation();
		Path path = new Path(filename);
		IFile file = container.getFile(path);
		return file;
	}

	public IFile getFileBeingEdited() {
		FileEditorInput editorInput = (FileEditorInput) this.getEditorInput();
		if (editorInput == null || editorInput.getFile() == null) {
			return null;
		}
		return editorInput.getFile();
	}

	public String getAnnotFileName(String filename) {
		return filename.substring(0, filename.lastIndexOf(".")) + ".annot";
	}

	public String getFullPathFileName(String filename) {
		// String workspaceDir = "";
		// IContainer container = getWorkingLocation();
		return this.getFile(filename).getLocation().toString();
	}

	public String getFullPath(String filename) {
		String workspaceDir = "";
		IContainer container = this.getWorkingLocation();
		workspaceDir = container.getLocation().toString();
		return workspaceDir;
	}

	public IProgressMonitor getMonitor() {
		return this.getProgressMonitor();
	}

	@Override
	public void doSave(IProgressMonitor monitor) {
		super.doSave(monitor);

		boolean bMakefileNature = false;
		try {
			bMakefileNature = this.getProject().getNature(OcamlNatureMakefile.ID) != null;
		} catch (CoreException e) {
			OcamlPlugin.logError("ocaml plugin error", e);
		}

		/*
		 * If this project is a makefile project, then we compile manually each time the user saves
		 * (because the automatic compiling provided by Eclipse is disabled on makefile projects)
		 */
		if (bMakefileNature) {
			IWorkspace ws = ResourcesPlugin.getWorkspace();
			IWorkspaceDescription desc = ws.getDescription();
			if (desc.isAutoBuilding())
				CompileProjectAction.compileProject(this.getProject());
		}
	}

	/*
	 * @Override protected void editorContextMenuAboutToShow(IMenuManager menu) { IFile file =
	 * this.getFileBeingEdited(); super.editorContextMenuAboutToShow(menu);
	 * 
	 * MenuManager ocamlGroup = new MenuManager("OCaml"); menu.add(new Separator());
	 * menu.add(ocamlGroup); ocamlGroup.add(new GenDocAction("GenDoc", file)); }
	 */

	/*
	 * public OcamlOutlineControl getOutlinePage() { if (this.fOutlinePage == null)
	 * this.fOutlinePage = new OcamlOutlineControl(this.getDocumentProvider(), this); return
	 * this.fOutlinePage; }
	 */

	public void rebuildOutline(int delay) {

		IEditorInput input = this.getEditorInput();
		IDocument document = this.getDocumentProvider().getDocument(input);
		// String doc = document.get();

		if (outlineJob == null)
			outlineJob = new OutlineJob("Rebuilding outline");
		else if (outlineJob.getState() == OutlineJob.RUNNING)
			return;
		// only one Job at a time
		else
			outlineJob.cancel();

		outlineJob.setPriority(CompletionJob.DECORATE);
		outlineJob.setOutline(this.outline);
		outlineJob.setDoc(document);
		outlineJob.setEditor(this);

		outlineJob.schedule(delay);
	}

	/** when the caret position changes, we synchronize the outline with the editor */
	@Override
	public void handleCursorPositionChanged() {
		super.handleCursorPositionChanged();
		synchronizeOutline();

		if (OcamlPlugin.getInstance().getPreferenceStore().getBoolean(
				PreferenceConstants.P_SHOW_TYPES_IN_STATUS_BAR)) {
			final String annot = OcamlTextHover.getAnnotAt(this,
					(TextViewer) this.getSourceViewer(), this.getCaretOffset()).trim();
			final OcamlEditor editor = this;
			Display.getCurrent().asyncExec(new Runnable() {

				public void run() {
					if (editor == null)
						return;
					if (!annot.equals(""))
						editor.setStatusLineMessage(annot);
					else
						editor.setStatusLineMessage(null); // clear
				}
			});
		}
	}

	private Def codeDefinitionsTree = null;
	private Def codeOutlineDefinitionsTree = null;

	/** Allows the code parsing Job to send back the definitions tree to the editor */
	public synchronized void setDefinitionsTree(Def def) {
		this.codeDefinitionsTree = def;
	}

	public void setOutlineDefinitionsTree(Def outlineDefinitions) {
		this.codeOutlineDefinitionsTree = outlineDefinitions;

	}

	/** Return the last definitions tree computed, or null if none */
	public synchronized Def getDefinitionsTree() {
		return this.codeDefinitionsTree;
	}

	/** Return the last outline definitions tree computed, or null if none */
	public synchronized Def getOutlineDefinitionsTree() {
		return this.codeOutlineDefinitionsTree;
	}

	public void highlightLineAtOffset(int offset) {
		IDocument document = this.getDocumentProvider().getDocument(this.getEditorInput());

		IRegion region = null;
		try {
			region = document.getLineInformationOfOffset(offset);
		} catch (BadLocationException e) {
			OcamlPlugin.logError("ocaml plugin error (bad location)", e);
			return;
		}

		if (region != null)
			this.selectAndReveal(region.getOffset(), 0);

	}

	public OcamlOutlineControl getOutline() {
		return outline;
	}

}
