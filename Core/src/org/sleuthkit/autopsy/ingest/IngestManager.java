/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2017 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.ingest;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.awt.EventQueue;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.swing.JOptionPane;
import org.netbeans.api.progress.ProgressHandle;
import org.openide.util.Cancellable;
import org.openide.util.NbBundle;
import org.sleuthkit.autopsy.casemodule.Case;
import org.sleuthkit.autopsy.core.RuntimeProperties;
import org.sleuthkit.autopsy.core.ServicesMonitor;
import org.sleuthkit.autopsy.core.UserPreferences;
import org.sleuthkit.autopsy.coreutils.Logger;
import org.sleuthkit.autopsy.coreutils.MessageNotifyUtil;
import org.sleuthkit.autopsy.events.AutopsyEvent;
import org.sleuthkit.autopsy.events.AutopsyEventException;
import org.sleuthkit.autopsy.events.AutopsyEventPublisher;
import org.sleuthkit.autopsy.ingest.events.BlackboardPostEvent;
import org.sleuthkit.autopsy.ingest.events.ContentChangedEvent;
import org.sleuthkit.autopsy.ingest.events.DataSourceAnalysisCompletedEvent;
import org.sleuthkit.autopsy.ingest.events.DataSourceAnalysisStartedEvent;
import org.sleuthkit.autopsy.ingest.events.FileAnalyzedEvent;
import org.sleuthkit.datamodel.AbstractFile;
import org.sleuthkit.datamodel.Content;

/**
 * Manages the creation and execution of ingest jobs, i.e., the processing of
 * data sources by ingest modules.
 */
public class IngestManager {

    private static final Logger LOGGER = Logger.getLogger(IngestManager.class.getName());
    private static IngestManager instance;

    /*
     * Every ingest job that is submitted to the ingest manager is passed to an
     * ingest task scheduler to be broken down into data source level and file
     * level ingest job tasks that are put into queues for execution by the
     * ingest manager's executors. The process of starting an ingest job is
     * handled by a single-threaded executor, the processing of data source
     * level ingest tasks is handled by another single-threaded executor, and
     * the processing of file level ingest jobs is handled by an executor with a
     * configurable number of threads.
     */
    private volatile boolean ingestJobCreationIsEnabled;
    private final Map<Long, IngestJob> ingestJobsById = new HashMap<>();
    private final AtomicLong nextIngestManagerTaskId = new AtomicLong(0L);
    private final Map<Long, Future<Void>> startIngestJobTasks = new ConcurrentHashMap<>();
    private final ExecutorService startIngestJobTasksExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("IM-start-ingest-jobs-%d").build()); //NON-NLS;
    private final ExecutorService dataSourceLevelIngestJobTasksExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("IM-data-source-ingest-%d").build()); //NON-NLS;
    private static final int MIN_NUM_FILE_INGEST_THREADS = 1;
    private static final int MAX_NUM_FILE_INGEST_THREADS = 16;
    private static final int DEFAULT_NUM_FILE_INGEST_THREADS = 2;
    private int numberOfFileIngestThreads;
    private ExecutorService fileLevelIngestJobTasksExecutor;

    /*
     * The ingest manager publishes two kinds of application events: ingest job
     * events and ingest module events. Ingest job events are published when an
     * ingest job changes states, e.g., an ingest job is started or completed.
     * Ingest module events are published on behalf of ingest modules working on
     * an ingest job, e.g., content or an artifact was added to the case. Each
     * event type is handled by a separate event publisher with its own remore
     * event channel, but all event publishing is handled by a dedicated
     * executor.
     */
    private static final String JOB_EVENT_CHANNEL_NAME = "%s-Ingest-Job-Events"; //NON-NLS
    private static final Set<String> INGEST_JOB_EVENT_NAMES = Stream.of(IngestJobEvent.values()).map(IngestJobEvent::toString).collect(Collectors.toSet());
    private AutopsyEventPublisher jobEventPublisher = new AutopsyEventPublisher();
    private static final String MODULE_EVENT_CHANNEL_NAME = "%s-Ingest-Module-Events"; //NON-NLS
    private static final Set<String> INGEST_MODULE_EVENT_NAMES = Stream.of(IngestModuleEvent.values()).map(IngestModuleEvent::toString).collect(Collectors.toSet());
    private AutopsyEventPublisher moduleEventPublisher = new AutopsyEventPublisher();
    private final ExecutorService eventPublishingExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("IM-ingest-events-%d").build()); //NON-NLS;

    /*
     * The ingest manager uses an ingest monitor to determine when system
     * resources are under pressure. If the ingest monitor detects such a
     * situation, it calls back to the ingest manager to cancel all ingest jobs
     * in progress.
     *
     * The ingest manager also uses a service monitor to watch for service
     * outages. If a key services goes down, the ingest manager cancels all
     * ingest jobs in progress.
     */
    private final ServicesMonitor servicesMonitor = ServicesMonitor.getInstance();
    private final IngestMonitor ingestMonitor = new IngestMonitor();

    /*
     * The ingest manager provides access to a top component that is used by
     * ingest modules to post messages for the user. A count of the posts is
     * used as a cap to avoid bogging down the application.
     */
    private static final int MAX_ERROR_MESSAGE_POSTS = 200;
    private final AtomicLong ingestErrorMessagePosts = new AtomicLong(0L);
    private final Object ingestMessageBoxLock = new Object();
    private volatile IngestMessageTopComponent ingestMessageBox;

    /*
     * The ingest manager supports reporting of ingest processing progress by
     * collecting snapshots of the activities of the ingest threads, ingest job
     * progress, and ingest module run times.
     */
    private final ConcurrentHashMap<Long, IngestThreadActivitySnapshot> ingestThreadActivitySnapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ingestModuleRunTimes = new ConcurrentHashMap<>();

    /**
     * Gets the manager of the creation and execution of ingest jobs, i.e., the
     * processing of data sources by ingest modules.
     *
     * @return A singleton ingest manager object.
     */
    public synchronized static IngestManager getInstance() {
        if (instance == null) {
            /**
             * Two stage construction to avoid allowing the "this" reference to
             * be prematurely published from the constructor via the Case
             * property change listener.
             */
            instance = new IngestManager();
            instance.subscribeToCaseEvents();
        }
        return instance;
    }

    /**
     * Constructs a manager of the creation and execution of ingest jobs, i.e.,
     * the processing of data sources by ingest modules. The manager immediately
     * submits ingest job task ingest processing tasks to its executors. These
     * tasks normally run as long as the application runs.
     */
    private IngestManager() {
        subscribeToServiceMonitorEvents();
        startDataSourceLevelIngestJobTaskExecutor();
        startFileLevelIngestJobTasksExecutor();
    }

    /**
     * Subscribes ingest manager to events published by its service monitor. The
     * event handler cancels all ingest jobs if one a key service goes down.
     */
    private void subscribeToServiceMonitorEvents() {
        PropertyChangeListener propChangeListener = (PropertyChangeEvent evt) -> {
            if (evt.getNewValue().equals(ServicesMonitor.ServiceStatus.DOWN.toString())) {

                /*
                 * The application services considered to be key services are
                 * only necessary for multi-user cases.
                 */
                try {
                    if (Case.getCurrentCase().getCaseType() != Case.CaseType.MULTI_USER_CASE) {
                        return;
                    }
                } catch (IllegalStateException noCaseOpenException) {
                    return;
                }

                String serviceDisplayName = ServicesMonitor.Service.valueOf(evt.getPropertyName()).getDisplayName();
                LOGGER.log(Level.SEVERE, "Service {0} is down, cancelling all running ingest jobs", serviceDisplayName); //NON-NLS
                if (isIngestRunning() && RuntimeProperties.runningWithGUI()) {
                    EventQueue.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            JOptionPane.showMessageDialog(null,
                                    NbBundle.getMessage(this.getClass(), "IngestManager.cancellingIngest.msgDlg.text"),
                                    NbBundle.getMessage(this.getClass(), "IngestManager.serviceIsDown.msgDlg.text", serviceDisplayName),
                                    JOptionPane.ERROR_MESSAGE);
                        }
                    });
                }
                cancelAllIngestJobs(IngestJob.CancellationReason.SERVICES_DOWN);
            }
        };

        /*
         * The key services for multi-user cases are currently the case database
         * server and the Solr server. The Solr server is a key service not
         * because search is essential, but because the coordination service
         * (ZooKeeper) is running embedded within the Solr server.
         */
        Set<String> servicesList = new HashSet<>();
        servicesList.add(ServicesMonitor.Service.REMOTE_CASE_DATABASE.toString());
        servicesList.add(ServicesMonitor.Service.REMOTE_KEYWORD_SEARCH.toString());
        this.servicesMonitor.addSubscriber(servicesList, propChangeListener);
    }

    /**
     * Submits a task for processing data source level ingest job tasks to the
     * data source level ingest job tasks executor.
     */
    private void startDataSourceLevelIngestJobTaskExecutor() {
        long threadId = nextIngestManagerTaskId.incrementAndGet();
        dataSourceLevelIngestJobTasksExecutor.submit(new ExecuteIngestJobsTask(threadId, IngestTasksScheduler.getInstance().getDataSourceIngestTaskQueue()));
        ingestThreadActivitySnapshots.put(threadId, new IngestThreadActivitySnapshot(threadId));
    }

    /**
     * Submits a configurable number of tasks for processing file level ingest
     * job tasks to the file level ingest job tasks executor.
     */
    private void startFileLevelIngestJobTasksExecutor() {
        numberOfFileIngestThreads = UserPreferences.numberOfFileIngestThreads();
        if ((numberOfFileIngestThreads < MIN_NUM_FILE_INGEST_THREADS) || (numberOfFileIngestThreads > MAX_NUM_FILE_INGEST_THREADS)) {
            numberOfFileIngestThreads = DEFAULT_NUM_FILE_INGEST_THREADS;
            UserPreferences.setNumberOfFileIngestThreads(numberOfFileIngestThreads);
        }
        fileLevelIngestJobTasksExecutor = Executors.newFixedThreadPool(numberOfFileIngestThreads, new ThreadFactoryBuilder().setNameFormat("IM-file-ingest-%d").build()); //NON-NLS
        for (int i = 0; i < numberOfFileIngestThreads; ++i) {
            long threadId = nextIngestManagerTaskId.incrementAndGet();
            fileLevelIngestJobTasksExecutor.submit(new ExecuteIngestJobsTask(threadId, IngestTasksScheduler.getInstance().getFileIngestTaskQueue()));
            ingestThreadActivitySnapshots.put(threadId, new IngestThreadActivitySnapshot(threadId));
        }
    }

    /**
     * Subscribes this ingest manager to local and remote case-related events.
     */
    private void subscribeToCaseEvents() {
        Case.addEventSubscriber(Case.Events.CURRENT_CASE.toString(), (PropertyChangeEvent event) -> {
            if (event.getNewValue() != null) {
                handleCaseOpened();
            } else {
                handleCaseClosed();
            }
        });
    }

    /*
     * Handles a case opened event.
     */
    synchronized void handleCaseOpened() {
        this.ingestJobCreationIsEnabled = true;
        clearIngestMessageBox();
        try {
            /**
             * Use the text index name as the remote event channel name prefix
             * since it is unique, the same as the case database name for a
             * multiuser case, and is readily available through the
             * Case.getTextIndexName() API.
             */
            Case openedCase = Case.getCurrentCase();
            String channelPrefix = openedCase.getName();
            if (Case.CaseType.MULTI_USER_CASE == openedCase.getCaseType()) {
                jobEventPublisher.openRemoteEventChannel(String.format(JOB_EVENT_CHANNEL_NAME, channelPrefix));
                moduleEventPublisher.openRemoteEventChannel(String.format(MODULE_EVENT_CHANNEL_NAME, channelPrefix));
            }
        } catch (IllegalStateException | AutopsyEventException ex) {
            LOGGER.log(Level.SEVERE, "Failed to open remote events channel", ex); //NON-NLS
            MessageNotifyUtil.Notify.error(NbBundle.getMessage(IngestManager.class, "IngestManager.OpenEventChannel.Fail.Title"),
                    NbBundle.getMessage(IngestManager.class, "IngestManager.OpenEventChannel.Fail.ErrMsg"));
        }
    }

    /*
     * Handles a case closed event.
     */
    synchronized void handleCaseClosed() {
        /*
         * TODO (JIRA-2227): IngestManager should wait for cancelled ingest jobs
         * to complete when a case is closed.
         */
        this.cancelAllIngestJobs(IngestJob.CancellationReason.CASE_CLOSED);
        jobEventPublisher.closeRemoteEventChannel();
        moduleEventPublisher.closeRemoteEventChannel();
        this.ingestJobCreationIsEnabled = false;
        clearIngestMessageBox();
    }

    /**
     * Causes the ingest manager to get the top component used to display ingest
     * inbox messages. Called by the custom installer for this package once the
     * window system is initialized.
     */
    void initIngestMessageInbox() {
        synchronized (this.ingestMessageBoxLock) {
            ingestMessageBox = IngestMessageTopComponent.findInstance();
        }
    }

    /**
     * Posts a message to the ingest messages inbox.
     *
     * @param message The message to be posted.
     */
    void postIngestMessage(IngestMessage message) {
        synchronized (this.ingestMessageBoxLock) {
            if (ingestMessageBox != null && RuntimeProperties.runningWithGUI()) {
                if (message.getMessageType() != IngestMessage.MessageType.ERROR && message.getMessageType() != IngestMessage.MessageType.WARNING) {
                    ingestMessageBox.displayMessage(message);
                } else {
                    long errorPosts = ingestErrorMessagePosts.incrementAndGet();
                    if (errorPosts <= MAX_ERROR_MESSAGE_POSTS) {
                        ingestMessageBox.displayMessage(message);
                    } else if (errorPosts == MAX_ERROR_MESSAGE_POSTS + 1) {
                        IngestMessage errorMessageLimitReachedMessage = IngestMessage.createErrorMessage(
                                NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.title"),
                                NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.subject"),
                                NbBundle.getMessage(this.getClass(), "IngestManager.IngestMessage.ErrorMessageLimitReached.msg", MAX_ERROR_MESSAGE_POSTS));
                        ingestMessageBox.displayMessage(errorMessageLimitReachedMessage);
                    }
                }
            }
        }
    }

    /*
     * Clears the ingest messages inbox.
     */
    private void clearIngestMessageBox() {
        synchronized (this.ingestMessageBoxLock) {
            if (ingestMessageBox != null) {
                ingestMessageBox.clearMessages();
            }
            ingestErrorMessagePosts.set(0);
        }
    }

    /**
     * Gets the number of file ingest threads the ingest manager will use to do
     * ingest jobs.
     *
     * @return The number of file ingest threads.
     */
    public int getNumberOfFileIngestThreads() {
        return numberOfFileIngestThreads;
    }

    /**
     * Queues an ingest job for a collection of data sources.
     *
     * @param dataSources The data sources to process.
     * @param settings    The settings for the ingest job.
     */
    public void queueIngestJob(Collection<Content> dataSources, IngestJobSettings settings) {
        if (ingestJobCreationIsEnabled) {
            IngestJob job = new IngestJob(dataSources, settings, RuntimeProperties.runningWithGUI());
            if (job.hasIngestPipeline()) {
                long taskId = nextIngestManagerTaskId.incrementAndGet();
                Future<Void> task = startIngestJobTasksExecutor.submit(new StartIngestJobTask(taskId, job));
                startIngestJobTasks.put(taskId, task);
            }
        }
    }

    /**
     * Immdiately starts an ingest job for a collection of data sources.
     *
     * @param dataSources The data sources to process.
     * @param settings    The settings for the ingest job.
     *
     * @return The IngestJobStartResult describing the results of attempting to
     *         start the ingest job.
     */
    public synchronized IngestJobStartResult beginIngestJob(Collection<Content> dataSources, IngestJobSettings settings) {
        if (this.ingestJobCreationIsEnabled) {
            IngestJob job = new IngestJob(dataSources, settings, RuntimeProperties.runningWithGUI());
            if (job.hasIngestPipeline()) {
                return this.startIngestJob(job); // Start job
            }
            return new IngestJobStartResult(null, new IngestManagerException("No ingest pipeline created, likely due to no ingest modules being enabled."), null);
        }
        return new IngestJobStartResult(null, new IngestManagerException("No case open"), null);
    }

    /**
     * Starts an ingest job for a collection of data sources.
     *
     * @param job The ingest job to start.
     *
     * @return The IngestJobStartResult describing the results of attempting to
     *         start the ingest job.
     */
    @NbBundle.Messages({
        "IngestManager.startupErr.dlgTitle=Ingest Module Startup Failure",
        "IngestManager.startupErr.dlgMsg=Unable to start up one or more ingest modules, ingest cancelled.",
        "IngestManager.startupErr.dlgSolution=Please disable the failed modules or fix the errors before restarting ingest.",
        "IngestManager.startupErr.dlgErrorList=Errors:"
    })
    private IngestJobStartResult startIngestJob(IngestJob job) {
        List<IngestModuleError> errors = null;
        if (this.ingestJobCreationIsEnabled) {
            if (Case.getCurrentCase().getCaseType() == Case.CaseType.MULTI_USER_CASE) {
                try {
                    if (!servicesMonitor.getServiceStatus(ServicesMonitor.Service.REMOTE_CASE_DATABASE.toString()).equals(ServicesMonitor.ServiceStatus.UP.toString())) {
                        if (RuntimeProperties.runningWithGUI()) {
                            EventQueue.invokeLater(new Runnable() {
                                @Override
                                public void run() {
                                    String serviceDisplayName = ServicesMonitor.Service.REMOTE_CASE_DATABASE.getDisplayName();
                                    JOptionPane.showMessageDialog(null,
                                            NbBundle.getMessage(this.getClass(), "IngestManager.cancellingIngest.msgDlg.text"),
                                            NbBundle.getMessage(this.getClass(), "IngestManager.serviceIsDown.msgDlg.text", serviceDisplayName),
                                            JOptionPane.ERROR_MESSAGE);
                                }
                            });
                        }
                        return new IngestJobStartResult(null, new IngestManagerException("Ingest aborted. Remote database is down"), Collections.<IngestModuleError>emptyList());
                    }
                } catch (ServicesMonitor.ServicesMonitorException ex) {
                    return new IngestJobStartResult(null, new IngestManagerException("Database server is down.", ex), Collections.<IngestModuleError>emptyList());
                }
            }

            if (!ingestMonitor.isRunning()) {
                ingestMonitor.start();
            }

            synchronized (ingestJobsById) {
                ingestJobsById.put(job.getId(), job);
            }
            errors = job.start();
            if (errors.isEmpty()) {
                this.fireIngestJobStarted(job.getId());
                IngestManager.LOGGER.log(Level.INFO, "Ingest job {0} started", job.getId()); //NON-NLS
            } else {
                synchronized (ingestJobsById) {
                    this.ingestJobsById.remove(job.getId());
                }
                for (IngestModuleError error : errors) {
                    LOGGER.log(Level.SEVERE, String.format("Error starting %s ingest module for job %d", error.getModuleDisplayName(), job.getId()), error.getThrowable()); //NON-NLS
                }
                IngestManager.LOGGER.log(Level.SEVERE, "Ingest job {0} could not be started", job.getId()); //NON-NLS
                if (RuntimeProperties.runningWithGUI()) {
                    final StringBuilder message = new StringBuilder(1024);
                    message.append(Bundle.IngestManager_startupErr_dlgMsg()).append("\n");
                    message.append(Bundle.IngestManager_startupErr_dlgSolution()).append("\n\n");
                    message.append(Bundle.IngestManager_startupErr_dlgErrorList()).append("\n");
                    for (IngestModuleError error : errors) {
                        String moduleName = error.getModuleDisplayName();
                        String errorMessage = error.getThrowable().getLocalizedMessage();
                        message.append(moduleName).append(": ").append(errorMessage).append("\n");
                    }
                    message.append("\n\n");
                    EventQueue.invokeLater(() -> {
                        JOptionPane.showMessageDialog(null, message, Bundle.IngestManager_startupErr_dlgTitle(), JOptionPane.ERROR_MESSAGE);
                    });
                }
                return new IngestJobStartResult(null, new IngestManagerException("Errors occurred while starting ingest"), errors);
            }
        }

        return new IngestJobStartResult(job, null, errors);
    }

    /**
     * Cleans up a completed ingest job.
     *
     * @param job The completed job.
     */
    synchronized void finishIngestJob(IngestJob job) {
        long jobId = job.getId();
        synchronized (ingestJobsById) {
            ingestJobsById.remove(jobId);
        }
        if (!job.isCancelled()) {
            IngestManager.LOGGER.log(Level.INFO, "Ingest job {0} completed", jobId); //NON-NLS
            fireIngestJobCompleted(jobId);
        } else {
            IngestManager.LOGGER.log(Level.INFO, "Ingest job {0} cancelled", jobId); //NON-NLS
            fireIngestJobCancelled(jobId);
        }
    }

    /**
     * Queries whether or not any ingest jobs are in progress at the time of the
     * call.
     *
     * @return True or false.
     */
    public boolean isIngestRunning() {
        synchronized (ingestJobsById) {
            return !ingestJobsById.isEmpty();
        }
    }

    /**
     * Cancels all ingest jobs in progress.
     *
     * @param reason The cancellation reason.
     */
    public void cancelAllIngestJobs(IngestJob.CancellationReason reason) {
        /*
         * Cancel the start job tasks.
         */
        for (Future<Void> handle : startIngestJobTasks.values()) {
            handle.cancel(true);
        }

        /*
         * Cancel the jobs in progress.
         */
        synchronized (ingestJobsById) {
            for (IngestJob job : this.ingestJobsById.values()) {
                job.cancel(reason);
            }
        }
    }

    /**
     * Adds an ingest job event property change listener.
     *
     * @param listener The PropertyChangeListener to be added.
     */
    public void addIngestJobEventListener(final PropertyChangeListener listener) {
        jobEventPublisher.addSubscriber(INGEST_JOB_EVENT_NAMES, listener);
    }

    /**
     * Removes an ingest job event property change listener.
     *
     * @param listener The PropertyChangeListener to be removed.
     */
    public void removeIngestJobEventListener(final PropertyChangeListener listener) {
        jobEventPublisher.removeSubscriber(INGEST_JOB_EVENT_NAMES, listener);
    }

    /**
     * Adds an ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to be added.
     */
    public void addIngestModuleEventListener(final PropertyChangeListener listener) {
        moduleEventPublisher.addSubscriber(INGEST_MODULE_EVENT_NAMES, listener);
    }

    /**
     * Removes an ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to be removed.
     */
    public void removeIngestModuleEventListener(final PropertyChangeListener listener) {
        moduleEventPublisher.removeSubscriber(INGEST_MODULE_EVENT_NAMES, listener);
    }

    /**
     * Publishes an ingest job event signifying an ingest job started.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobStarted(long ingestJobId) {
        AutopsyEvent event = new AutopsyEvent(IngestJobEvent.STARTED.toString(), ingestJobId, null);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest event signifying an ingest job finished.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobCompleted(long ingestJobId) {
        AutopsyEvent event = new AutopsyEvent(IngestJobEvent.COMPLETED.toString(), ingestJobId, null);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest event signifying an ingest job was canceled.
     *
     * @param ingestJobId The ingest job id.
     */
    void fireIngestJobCancelled(long ingestJobId) {
        AutopsyEvent event = new AutopsyEvent(IngestJobEvent.CANCELLED.toString(), ingestJobId, null);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest job event signifying analysis of a data source
     * started.
     *
     * @param ingestJobId           The ingest job id.
     * @param dataSourceIngestJobId The data source ingest job id.
     * @param dataSource            The data source.
     */
    void fireDataSourceAnalysisStarted(long ingestJobId, long dataSourceIngestJobId, Content dataSource) {
        AutopsyEvent event = new DataSourceAnalysisStartedEvent(ingestJobId, dataSourceIngestJobId, dataSource);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest job event signifying analysis of a data source
     * finished.
     *
     * @param ingestJobId           The ingest job id.
     * @param dataSourceIngestJobId The data source ingest job id.
     * @param dataSource            The data source.
     */
    void fireDataSourceAnalysisCompleted(long ingestJobId, long dataSourceIngestJobId, Content dataSource) {
        AutopsyEvent event = new DataSourceAnalysisCompletedEvent(ingestJobId, dataSourceIngestJobId, dataSource, DataSourceAnalysisCompletedEvent.Reason.ANALYSIS_COMPLETED);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest job event signifying analysis of a data source was
     * canceled.
     *
     * @param ingestJobId           The ingest job id.
     * @param dataSourceIngestJobId The data source ingest job id.
     * @param dataSource            The data source.
     */
    void fireDataSourceAnalysisCancelled(long ingestJobId, long dataSourceIngestJobId, Content dataSource) {
        AutopsyEvent event = new DataSourceAnalysisCompletedEvent(ingestJobId, dataSourceIngestJobId, dataSource, DataSourceAnalysisCompletedEvent.Reason.ANALYSIS_CANCELLED);
        eventPublishingExecutor.submit(new PublishEventTask(event, jobEventPublisher));
    }

    /**
     * Publishes an ingest module event signifying the ingest of a file was
     * completed.
     *
     * @param file The file.
     */
    void fireFileIngestDone(AbstractFile file) {
        AutopsyEvent event = new FileAnalyzedEvent(file);
        eventPublishingExecutor.submit(new PublishEventTask(event, moduleEventPublisher));
    }

    /**
     * Publishes an ingest module event signifying a blackboard post by an
     * ingest module.
     *
     * @param moduleDataEvent A ModuleDataEvent with the details of the
     *                        blackboard post.
     */
    void fireIngestModuleDataEvent(ModuleDataEvent moduleDataEvent) {
        AutopsyEvent event = new BlackboardPostEvent(moduleDataEvent);
        eventPublishingExecutor.submit(new PublishEventTask(event, moduleEventPublisher));
    }

    /**
     * Publishes an ingest module event signifying discovery of additional
     * content by an ingest module.
     *
     * @param moduleDataEvent A ModuleContentEvent with the details of the new
     *                        content.
     */
    void fireIngestModuleContentEvent(ModuleContentEvent moduleContentEvent) {
        AutopsyEvent event = new ContentChangedEvent(moduleContentEvent);
        eventPublishingExecutor.submit(new PublishEventTask(event, moduleEventPublisher));
    }

    /**
     * Updates the ingest job snapshot when a data source level ingest job task
     * starts to be processd by a data source ingest module in the data source
     * ingest modules pipeline of an ingest job.
     *
     * @param task                    The data source level ingest job task that
     *                                was started.
     * @param ingestModuleDisplayName The dislpay name of the data source level
     *                                ingest module that has started processing
     *                                the task.
     */
    void setIngestTaskProgress(DataSourceIngestTask task, String ingestModuleDisplayName) {
        ingestThreadActivitySnapshots.put(task.getThreadId(), new IngestThreadActivitySnapshot(task.getThreadId(), task.getIngestJob().getId(), ingestModuleDisplayName, task.getDataSource()));
    }

    /**
     * Updates the ingest job snapshot when a file source level ingest job task
     * starts to be processed by a file level ingest module in the file ingest
     * modules pipeline of an ingest job.
     *
     * @param task                    The file level ingest job task that was
     *                                started.
     * @param ingestModuleDisplayName The dislpay name of the file level ingest
     *                                module that has started processing the
     *                                task.
     */
    void setIngestTaskProgress(FileIngestTask task, String ingestModuleDisplayName) {
        IngestThreadActivitySnapshot prevSnap = ingestThreadActivitySnapshots.get(task.getThreadId());
        IngestThreadActivitySnapshot newSnap = new IngestThreadActivitySnapshot(task.getThreadId(), task.getIngestJob().getId(), ingestModuleDisplayName, task.getDataSource(), task.getFile());
        ingestThreadActivitySnapshots.put(task.getThreadId(), newSnap);
        incrementModuleRunTime(prevSnap.getActivity(), newSnap.getStartTime().getTime() - prevSnap.getStartTime().getTime());
    }

    /**
     * Updates the ingest job snapshot when a data source level ingest job task
     * is completed by the data source ingest modules in the data source ingest
     * modules pipeline of an ingest job.
     *
     * @param task The data source level ingest job task that was completed.
     */
    void setIngestTaskProgressCompleted(DataSourceIngestTask task) {
        ingestThreadActivitySnapshots.put(task.getThreadId(), new IngestThreadActivitySnapshot(task.getThreadId()));
    }

    /**
     * Updates the ingest job snapshot when a file level ingest job task is
     * completed by the file ingest modules in the file ingest modules pipeline
     * of an ingest job.
     *
     * @param task The file level ingest job task that was completed.
     */
    void setIngestTaskProgressCompleted(FileIngestTask task) {
        IngestThreadActivitySnapshot prevSnap = ingestThreadActivitySnapshots.get(task.getThreadId());
        IngestThreadActivitySnapshot newSnap = new IngestThreadActivitySnapshot(task.getThreadId());
        ingestThreadActivitySnapshots.put(task.getThreadId(), newSnap);
        incrementModuleRunTime(prevSnap.getActivity(), newSnap.getStartTime().getTime() - prevSnap.getStartTime().getTime());
    }

    /**
     * Updates the cumulative run time for a given ingest module.
     *
     * @param moduleDisplayName The diplay name of the ingest module.
     * @param duration
     */
    private void incrementModuleRunTime(String moduleDisplayName, Long duration) {
        if (moduleDisplayName.equals("IDLE")) { //NON-NLS
            return;
        }

        synchronized (ingestModuleRunTimes) {
            Long prevTimeL = ingestModuleRunTimes.get(moduleDisplayName);
            long prevTime = 0;
            if (prevTimeL != null) {
                prevTime = prevTimeL;
            }
            prevTime += duration;
            ingestModuleRunTimes.put(moduleDisplayName, prevTime);
        }
    }

    /**
     * Gets the cumulative run times for the ingest module.
     *
     * @return Map of module name to run time (in milliseconds)
     */
    Map<String, Long> getModuleRunTimes() {
        synchronized (ingestModuleRunTimes) {
            Map<String, Long> times = new HashMap<>(ingestModuleRunTimes);
            return times;
        }
    }

    /**
     * Gets snapshots of the current state of each ingest manager ingest task
     * (ingest thread).
     *
     * @return A collection of ingest manager ingest task snapshots.
     */
    List<IngestThreadActivitySnapshot> getIngestThreadActivitySnapshots() {
        return new ArrayList<>(ingestThreadActivitySnapshots.values());
    }

    /**
     * Gets snapshots of the state of all running ingest jobs.
     *
     * @return A list of ingest job state snapshots.
     */
    List<DataSourceIngestJob.Snapshot> getIngestJobSnapshots() {
        List<DataSourceIngestJob.Snapshot> snapShots = new ArrayList<>();
        synchronized (ingestJobsById) {
            ingestJobsById.values().forEach((job) -> {
                snapShots.addAll(job.getDataSourceIngestJobSnapshots());
            });
        }
        return snapShots;
    }

    /**
     * Gets the free disk space of the drive to which ingest data is being
     * written, as reported by the ingest monitor.
     *
     * @return Free disk space, -1 if unknown.
     */
    long getFreeDiskSpace() {
        if (ingestMonitor != null) {
            return ingestMonitor.getFreeSpace();
        } else {
            return -1;
        }
    }

    /**
     * Creates and starts an ingest job for a collection of data sources.
     */
    private final class StartIngestJobTask implements Callable<Void> {

        private final long threadId;
        private final IngestJob job;
        private ProgressHandle progress;

        StartIngestJobTask(long threadId, IngestJob job) {
            this.threadId = threadId;
            this.job = job;
        }

        @Override
        public Void call() {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    synchronized (ingestJobsById) {
                        ingestJobsById.remove(job.getId());
                    }
                    return null;
                }

                if (RuntimeProperties.runningWithGUI()) {
                    final String displayName = NbBundle.getMessage(this.getClass(), "IngestManager.StartIngestJobsTask.run.displayName");
                    this.progress = ProgressHandle.createHandle(displayName, new Cancellable() {
                        @Override
                        public boolean cancel() {
                            if (progress != null) {
                                progress.setDisplayName(NbBundle.getMessage(this.getClass(), "IngestManager.StartIngestJobsTask.run.cancelling", displayName));
                            }
                            Future<?> handle = startIngestJobTasks.remove(threadId);
                            handle.cancel(true);
                            return true;
                        }
                    });
                    progress.start();
                }

                startIngestJob(job);
                return null;

            } finally {
                if (null != progress) {
                    progress.finish();
                }
                startIngestJobTasks.remove(threadId);
            }
        }

    }

    /**
     * Executes ingest jobs by acting as a consumer for an ingest tasks queue.
     */
    private final class ExecuteIngestJobsTask implements Runnable {

        private final long threadId;
        private final IngestTaskQueue tasks;

        ExecuteIngestJobsTask(long threadId, IngestTaskQueue tasks) {
            this.threadId = threadId;
            this.tasks = tasks;
        }

        @Override
        public void run() {
            while (true) {
                try {
                    IngestTask task = tasks.getNextTask(); // Blocks.
                    task.execute(threadId);
                } catch (InterruptedException ex) {
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
            }
        }
    }

    /**
     * Publishes ingest events to both local and remote subscribers.
     */
    private static final class PublishEventTask implements Runnable {

        private final AutopsyEvent event;
        private final AutopsyEventPublisher publisher;

        /**
         * Constructs an object that publishes ingest events to both local and
         * remote subscribers.
         *
         * @param event     The event to publish.
         * @param publisher The event publisher.
         */
        PublishEventTask(AutopsyEvent event, AutopsyEventPublisher publisher) {
            this.event = event;
            this.publisher = publisher;
        }

        @Override
        public void run() {
            publisher.publish(event);
        }

    }

    static final class IngestThreadActivitySnapshot {

        private final long threadId;
        private final Date startTime;
        private final String activity;
        private final String dataSourceName;
        private final String fileName;
        private final long jobId;

        // nothing is running on the thread
        IngestThreadActivitySnapshot(long threadId) {
            this.threadId = threadId;
            startTime = new Date();
            this.activity = NbBundle.getMessage(this.getClass(), "IngestManager.IngestThreadActivitySnapshot.idleThread");
            this.dataSourceName = "";
            this.fileName = "";
            this.jobId = 0;
        }

        // data souce thread
        IngestThreadActivitySnapshot(long threadId, long jobId, String activity, Content dataSource) {
            this.threadId = threadId;
            this.jobId = jobId;
            startTime = new Date();
            this.activity = activity;
            this.dataSourceName = dataSource.getName();
            this.fileName = "";
        }

        // file ingest thread
        IngestThreadActivitySnapshot(long threadId, long jobId, String activity, Content dataSource, AbstractFile file) {
            this.threadId = threadId;
            this.jobId = jobId;
            startTime = new Date();
            this.activity = activity;
            this.dataSourceName = dataSource.getName();
            this.fileName = file.getName();
        }

        long getJobId() {
            return jobId;
        }

        long getThreadId() {
            return threadId;
        }

        Date getStartTime() {
            return startTime;
        }

        String getActivity() {
            return activity;
        }

        String getDataSourceName() {
            return dataSourceName;
        }

        String getFileName() {
            return fileName;
        }

    }

    /**
     * Ingest job events.
     */
    public enum IngestJobEvent {

        /**
         * Property change event fired when an ingest job is started. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        STARTED,
        /**
         * Property change event fired when an ingest job is completed. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        COMPLETED,
        /**
         * Property change event fired when an ingest job is canceled. The old
         * value of the PropertyChangeEvent object is set to the ingest job id,
         * and the new value is set to null.
         */
        CANCELLED,
        /**
         * Property change event fired when analysis (ingest) of a data source
         * included in an ingest job is started. Both the old and new values of
         * the ProerptyChangeEvent are set to null - cast the
         * PropertyChangeEvent to
         * org.sleuthkit.autopsy.ingest.events.DataSourceAnalysisStartedEvent to
         * access event data.
         */
        DATA_SOURCE_ANALYSIS_STARTED,
        /**
         * Property change event fired when analysis (ingest) of a data source
         * included in an ingest job is completed. Both the old and new values
         * of the ProerptyChangeEvent are set to null - cast the
         * PropertyChangeEvent to
         * org.sleuthkit.autopsy.ingest.events.DataSourceAnalysisCompletedEvent
         * to access event data.
         */
        DATA_SOURCE_ANALYSIS_COMPLETED,
    }

    /**
     * Ingest module events.
     */
    public enum IngestModuleEvent {

        /**
         * Property change event fired when an ingest module adds new data to a
         * case, usually by posting to the blackboard. The old value of the
         * PropertyChangeEvent is a ModuleDataEvent object, and the new value is
         * set to null.
         */
        DATA_ADDED,
        /**
         * Property change event fired when an ingest module adds new content to
         * a case or changes a recorded attribute of existing content. For
         * example, if a module adds an extracted or carved file to a case, the
         * module should fire this event. The old value of the
         * PropertyChangeEvent is a ModuleContentEvent object, and the new value
         * is set to null.
         */
        CONTENT_CHANGED,
        /**
         * Property change event fired when the ingest of a file is completed.
         * The old value of the PropertyChangeEvent is the Autopsy object ID of
         * the file. The new value is the AbstractFile for that ID.
         */
        FILE_DONE,
    }

    /**
     * An exception thrown by the ingest manager.
     */
    public final static class IngestManagerException extends Exception {

        private static final long serialVersionUID = 1L;

        /**
         * Creates an exception containing an error message.
         *
         * @param message The message.
         */
        private IngestManagerException(String message) {
            super(message);
        }

        /**
         * Creates an exception containing an error message and a cause.
         *
         * @param message The message
         * @param cause   The cause.
         */
        private IngestManagerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Adds an ingest job and ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to register.
     *
     * @deprecated Use addIngestJobEventListener() and/or
     * addIngestModuleEventListener().
     */
    @Deprecated
    public static void addPropertyChangeListener(final PropertyChangeListener listener) {
        instance.addIngestJobEventListener(listener);
        instance.addIngestModuleEventListener(listener);
    }

    /**
     * Removes an ingest job and ingest module event property change listener.
     *
     * @param listener The PropertyChangeListener to unregister.
     *
     * @deprecated Use removeIngestJobEventListener() and/or
     * removeIngestModuleEventListener().
     */
    @Deprecated
    public static void removePropertyChangeListener(final PropertyChangeListener listener) {
        instance.removeIngestJobEventListener(listener);
        instance.removeIngestModuleEventListener(listener);
    }

    /**
     * Starts an ingest job that will process a collection of data sources.
     *
     * @param dataSources The data sources to process.
     * @param settings    The settings for the ingest job.
     *
     * @return The ingest job that was started on success or null on failure.
     *
     * @deprecated. Use beginIngestJob() instead.
     */
    @Deprecated
    public synchronized IngestJob startIngestJob(Collection<Content> dataSources, IngestJobSettings settings) {
        return beginIngestJob(dataSources, settings).getJob();
    }

    /**
     * Cancels all ingest jobs in progress.
     *
     * @deprecated Use cancelAllIngestJobs(IngestJob.CancellationReason reason)
     * instead.
     */
    @Deprecated
    public void cancelAllIngestJobs() {
        cancelAllIngestJobs(IngestJob.CancellationReason.USER_CANCELLED);
    }

}
