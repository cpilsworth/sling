/*************************************************************************
 *
 * ADOBE CONFIDENTIAL
 * __________________
 *
 *  Copyright 2014 Adobe Systems Incorporated
 *  All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of Adobe Systems Incorporated and its suppliers,
 * if any.  The intellectual and technical concepts contained
 * herein are proprietary to Adobe Systems Incorporated and its
 * suppliers and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from Adobe Systems Incorporated.
 **************************************************************************/
package org.apache.sling.ide.eclipse.ui.wizards;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import org.apache.sling.ide.eclipse.core.ConfigurationHelper;
import org.apache.sling.ide.eclipse.ui.internal.Activator;
import org.apache.sling.ide.eclipse.ui.internal.SharedImages;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jface.dialogs.IMessageProvider;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.IWizardPage;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.jface.wizard.WizardPage;
import org.eclipse.ui.INewWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.eclipse.wst.server.core.IModule;
import org.eclipse.wst.server.core.IServer;
import org.eclipse.wst.server.core.IServerWorkingCopy;
import org.eclipse.wst.server.core.ServerUtil;

/**
 * The <tt>AbstractNewSlingApplicationWizard</tt> is a support class for wizards which create Sling applications
 *
 */
public abstract class AbstractNewSlingApplicationWizard extends Wizard implements INewWizard {

    private SetupServerWizardPage setupServerWizardPage = new SetupServerWizardPage(this);

    public void init(IWorkbench workbench, IStructuredSelection selection) {
    }

    /**
     * 
     * @return the current wizard page, possibly null
     */
    protected WizardPage getCurrentWizardPage() {
        IWizardPage currentPage = getContainer().getCurrentPage();
        if (currentPage instanceof WizardPage) {
            return (WizardPage) currentPage;
        }

        return null;
    }

    public void reportError(CoreException e) {
        WizardPage currentPage = getCurrentWizardPage();
        if (currentPage != null) {
            currentPage.setMessage(e.getMessage(), IMessageProvider.ERROR);
        } else {
            MessageDialog.openError(getShell(), "Unexpected error", e.getMessage());
        }

        Activator.getDefault().getLog().log(e.getStatus());
    }

    public void reportError(Throwable t) {
        if (t instanceof CoreException) {
            reportError((CoreException) t);
            return;
        }

        IStatus status = new Status(IStatus.ERROR, Activator.PLUGIN_ID, t.getMessage(), t);
        reportError(new CoreException(status));
    }

    protected SetupServerWizardPage getSetupServerWizardPage() {
        return setupServerWizardPage;
    }
    /**
     * This method is called when 'Finish' button is pressed in the wizard. We will create an operation and run it using
     * wizard as execution context.
     */
    public boolean performFinish() {

        try {
            // create projects
            final List<IProject> createdProjects = new ArrayList<IProject>();
            getContainer().run(false, true, new WorkspaceModifyOperation() {
                @Override
                protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException,
                        InterruptedException {
                    createdProjects.addAll(createProjects(monitor));
                }
            });

            // configure projects
            final Projects[] projects = new Projects[1];
            getContainer().run(false, true, new WorkspaceModifyOperation() {
                @Override
                protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException,
                        InterruptedException {
                    projects[0] = configureCreatedProjects(createdProjects, monitor);
                }
            });

            // deploy the projects on server
            getContainer().run(false, true, new WorkspaceModifyOperation() {
                @Override
                protected void execute(IProgressMonitor monitor) throws CoreException, InvocationTargetException,
                        InterruptedException {
                    deployProjectsOnServer(projects[0], monitor);
                }
            });

            // ensure server is started and all modules are published
            getContainer().run(false, false, new IRunnableWithProgress() {

                @Override
                public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                    try {
                        publishModules(createdProjects, monitor);
                    } catch (CoreException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            reportError(e.getTargetException());
            return false;
        }

        return true;
    }

    protected abstract List<IProject> createProjects(IProgressMonitor monitor) throws CoreException;

    protected abstract Projects configureCreatedProjects(List<IProject> createdProjects, IProgressMonitor monitor)
            throws CoreException;

    protected void deployProjectsOnServer(Projects projects, IProgressMonitor monitor) throws CoreException {

        IServer server = setupServerWizardPage.getOrCreateServer(monitor);
        advance(monitor, 1);

        IServerWorkingCopy wc = server.createWorkingCopy();
        // add the bundle and content projects, ie modules, to the server
        List<IModule> modules = new LinkedList<IModule>();
        for (IProject project : projects.getBundleProjects()) {
            IModule module = ServerUtil.getModule(project);
            if (module != null) {
                modules.add(module);
            }
        }
        for (IProject project : projects.getContentProjects()) {
            IModule module = ServerUtil.getModule(project);
            if (module != null) {
                modules.add(module);
            }
        }
        wc.modifyModules(modules.toArray(new IModule[modules.size()]), new IModule[0], monitor);
        wc.save(true, monitor);

        advance(monitor, 2);

        monitor.done();
    }

    protected void publishModules(final List<IProject> createdProjects, IProgressMonitor monitor) throws CoreException {
        IServer server = setupServerWizardPage.getOrCreateServer(monitor);
        server.start(ILaunchManager.RUN_MODE, monitor);
        List<IModule[]> modules = new ArrayList<IModule[]>();
        for (IProject project : createdProjects) {
            IModule module = ServerUtil.getModule(project);
            if (module != null) {
                modules.add(new IModule[] { module });
            }
        }

        if (modules.size() > 0) {
            server.publish(IServer.PUBLISH_FULL, modules, null, null);
        }
    }

    protected void configureBundleProject(IProject aBundleProject, List<IProject> projects, IProgressMonitor monitor)
            throws CoreException {
        ConfigurationHelper.convertToBundleProject(aBundleProject);
    }

    protected void configureContentProject(IProject aContentProject, List<IProject> projects, IProgressMonitor monitor)
            throws CoreException {
        ConfigurationHelper.convertToContentPackageProject(aContentProject, monitor, "src/main/content/jcr_root");
    }

    protected void configureReactorProject(IProject reactorProject, IProgressMonitor monitor) throws CoreException {
        // nothing to be done
    }

    protected void advance(IProgressMonitor monitor, int step) {

        monitor.worked(step);
        if (monitor.isCanceled()) {
            throw new OperationCanceledException();
        }
    }

    protected void finishConfiguration(List<IProject> projects, IServer server, IProgressMonitor monitor)
            throws CoreException {
        // nothing to be done by default - hook for subclasses
    }

    public ImageDescriptor getLogo() {
        return SharedImages.SLING_LOG;
    }

    public abstract String doGetWindowTitle();
}