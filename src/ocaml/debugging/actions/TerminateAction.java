package ocaml.debugging.actions;

import ocaml.debugging.OcamlDebugger;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.IWorkbenchWindowActionDelegate;


/**
 * This action is called by the "Terminate" menu item in the "Debug" menu, in the O'Caml Debug
 * perspective.<br>
 * Ask the debugger to quit.
 */
public class TerminateAction implements IWorkbenchWindowActionDelegate {

	public void run(IAction action) {
		OcamlDebugger debugger = OcamlDebugger.getInstance();
		debugger.quit();
	}

	public void dispose() {
	}

	public void init(IWorkbenchWindow window) {
	}

	public void selectionChanged(IAction action, ISelection selection) {
	}
}
