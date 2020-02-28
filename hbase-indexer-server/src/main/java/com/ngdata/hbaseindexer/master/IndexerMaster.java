/*
 * Copyright 2013 NGDATA nv
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
package com.ngdata.hbaseindexer.master;

import static com.ngdata.hbaseindexer.model.api.IndexerModelEventType.INDEXER_ADDED;
import static com.ngdata.hbaseindexer.model.api.IndexerModelEventType.INDEXER_UPDATED;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.google.common.base.Optional;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.ngdata.hbaseindexer.ConfKeys;
import com.ngdata.hbaseindexer.SolrConnectionParams;
import com.ngdata.hbaseindexer.conf.IndexerComponentFactory;
import com.ngdata.hbaseindexer.conf.IndexerComponentFactoryUtil;
import com.ngdata.hbaseindexer.conf.IndexerConf;
import com.ngdata.hbaseindexer.model.api.BatchBuildInfo;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition.BatchIndexingState;
import com.ngdata.hbaseindexer.model.api.IndexerDefinition.IncrementalIndexingState;
import com.ngdata.hbaseindexer.model.api.IndexerDefinitionBuilder;
import com.ngdata.hbaseindexer.model.api.IndexerLifecycleListener;
import com.ngdata.hbaseindexer.model.api.IndexerModelEvent;
import com.ngdata.hbaseindexer.model.api.IndexerModelListener;
import com.ngdata.hbaseindexer.model.api.IndexerNotFoundException;
import com.ngdata.hbaseindexer.model.api.WriteableIndexerModel;
import com.ngdata.hbaseindexer.mr.HBaseMapReduceIndexerTool;
import com.ngdata.hbaseindexer.mr.JobProcessCallback;
import com.ngdata.hbaseindexer.util.solr.SolrConnectionParamUtil;
import com.ngdata.hbaseindexer.util.zookeeper.LeaderElection;
import com.ngdata.hbaseindexer.util.zookeeper.LeaderElectionCallback;
import com.ngdata.hbaseindexer.util.zookeeper.LeaderElectionSetupException;
import com.ngdata.sep.SepModel;
import com.ngdata.sep.util.io.Closer;
import com.ngdata.sep.util.zookeeper.ZooKeeperItf;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.zookeeper.KeeperException;

/**
 * The indexer master is active on only one hbase-indexer node and is responsible for things such as launching
 * batch indexing jobs, assigning or removing SEP subscriptions, and the like.
 */
public class IndexerMaster {
    private final ZooKeeperItf zk;

    private final WriteableIndexerModel indexerModel;

    private final Configuration mapReduceConf;

    private final Configuration hbaseConf;

    private final String zkConnectString;

    // TODO
//    private final SolrClientConfig solrClientConfig;

    private LeaderElection leaderElection;

    private IndexerModelListener listener = new MyListener();

    private final List<IndexerLifecycleListener> lifecycleListeners = Lists.newArrayList();

    private EventWorker eventWorker = new EventWorker();

    private JobClient jobClient;

    private final Log log = LogFactory.getLog(getClass());

    private SepModel sepModel;

    /**
     * Total number of IndexerModel events processed (useful in test cases).
     */
    private final AtomicInteger eventCount = new AtomicInteger();

    public IndexerMaster(ZooKeeperItf zk, WriteableIndexerModel indexerModel, Configuration mapReduceConf,
                         Configuration hbaseConf, String zkConnectString, SepModel sepModel) {

        this.zk = zk;
        this.indexerModel = indexerModel;
        this.mapReduceConf = mapReduceConf;
        this.hbaseConf = hbaseConf;
        this.zkConnectString = zkConnectString;
        this.sepModel = sepModel;

        registerLifecycleListeners();
    }

    private void registerLifecycleListeners() {
        String listenerConf = hbaseConf.get("hbaseindexer.lifecycle.listeners", "");
        Iterable<String> classNames =  Splitter.on(",").trimResults().omitEmptyStrings().split(listenerConf);
        for (String className : classNames) {
            try {
                Class<IndexerLifecycleListener> listenerClass = (Class<IndexerLifecycleListener>)getClass()
                        .getClassLoader().loadClass(className);
                registerLifecycleListener(listenerClass.newInstance());
            } catch (Exception e) {
                log.error("Could not add an instance of " + className + " to the indexerMaster lifecycle listeners." + e);
            }
        }
    }

    @VisibleForTesting
    public void registerLifecycleListener(IndexerLifecycleListener indexerLifecycleListener) {
        if (indexerLifecycleListener instanceof Configurable) {
            ((Configurable)indexerLifecycleListener).setConf(hbaseConf);
        }

        lifecycleListeners.add(indexerLifecycleListener);
    }

    @PostConstruct
    public void start() throws LeaderElectionSetupException, IOException, InterruptedException, KeeperException {
        leaderElection = new LeaderElection(zk, "Indexer Master",
                hbaseConf.get(ConfKeys.ZK_ROOT_NODE) + "/masters",
                new MyLeaderElectionCallback());
    }

    @PreDestroy
    public void stop() {
        try {
            if (leaderElection != null)
                leaderElection.stop();
        } catch (InterruptedException e) {
            log.info("Interrupted while shutting down leader election.");
        }

        Closer.close(jobClient);
    }

    public int getEventCount() {
        return eventCount.intValue();
    }

    private synchronized JobClient getJobClient() throws IOException {
        if (jobClient == null) {
            jobClient = new JobClient(new JobConf(mapReduceConf));
        }
        return jobClient;
    }

    private class MyLeaderElectionCallback implements LeaderElectionCallback {
        @Override
        public void activateAsLeader() throws Exception {
            log.info("Starting up as indexer master.");

            // Start these processes, but it is not until we have registered our model listener
            // that these will receive work.
            eventWorker.start();

            Collection<IndexerDefinition> indexers = indexerModel.getIndexers(listener);

            // Rather than performing any work that might to be done for the indexers here,
            // we push out fake events. This way there's only one place where these actions
            // need to be performed.
            for (IndexerDefinition index : indexers) {
                eventWorker.putEvent(new IndexerModelEvent(INDEXER_UPDATED, index.getName()));
            }

            log.info("Startup as indexer master successful.");
        }

        @Override
        public void deactivateAsLeader() throws Exception {
            log.info("Shutting down as indexer master.");

            indexerModel.unregisterListener(listener);

            // Argument false for shutdown: we do not interrupt the event worker thread: if there
            // was something running there that is blocked until the ZK connection comes back up
            // we want it to finish (e.g. a lock taken that should be released again)
            eventWorker.shutdown(false);

            log.info("Shutdown as indexer master successful.");
        }
    }

    private boolean needsSubscriptionIdAssigned(IndexerDefinition indexer) {
        return !indexer.getLifecycleState().isDeleteState() &&
                indexer.getIncrementalIndexingState() != IncrementalIndexingState.DO_NOT_SUBSCRIBE
                && indexer.getSubscriptionId() == null;
    }

    private boolean needsSubscriptionIdUnassigned(IndexerDefinition indexer) {
        return indexer.getIncrementalIndexingState() == IncrementalIndexingState.DO_NOT_SUBSCRIBE
                && indexer.getSubscriptionId() != null;
    }

    private boolean needsBatchBuildStart(IndexerDefinition indexer) {
        return !indexer.getLifecycleState().isDeleteState() &&
                indexer.getBatchIndexingState() == BatchIndexingState.BUILD_REQUESTED &&
                indexer.getActiveBatchBuildInfo() == null;
    }

    private void assignSubscription(String indexerName) {
        try {
            String lock = indexerModel.lockIndexer(indexerName);
            try {
                // Read current situation of record and assure it is still actual
                IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);
                if (needsSubscriptionIdAssigned(indexer)) {
                    // We assume we are the only process which creates subscriptions which begin with the
                    // prefix "Indexer:". This way we are sure there are no naming conflicts or conflicts
                    // due to concurrent operations (e.g. someone deleting this subscription right after we
                    // created it).
                    String subscriptionId = subscriptionId(indexer.getName());

                    IndexerComponentFactory factory = IndexerComponentFactoryUtil.getComponentFactory(indexer.getIndexerComponentFactory(), new ByteArrayInputStream(indexer.getConfiguration()), indexer.getConnectionParams());
                    IndexerConf indexerConf = factory.createIndexerConf();
                    if (indexerConf.tableNameIsRegex()) {
                        // TODO: perf enhancement: consider fetching all hbase table names and here only pass in the table names that match the regex?
                        sepModel.addSubscription(subscriptionId);
                    } else {                     
                        sepModel.addSubscription(subscriptionId, indexerConf.getTable());
                    }

                    indexer = new IndexerDefinitionBuilder().startFrom(indexer).subscriptionId(subscriptionId).build();
                    indexerModel.updateIndexerInternal(indexer);
                    log.info("Assigned subscription ID '" + subscriptionId + "' to indexer '" + indexerName + "'");
                }
            } finally {
                indexerModel.unlockIndexer(lock);
            }
        } catch (Throwable t) {
            log.error("Error trying to assign a subscription to index " + indexerName, t);
        }
    }

    private void unassignSubscription(String indexerName) {
        try {
            String lock = indexerModel.lockIndexer(indexerName);
            try {
                // Read current situation of record and assure it is still actual
                IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);
                if (needsSubscriptionIdUnassigned(indexer)) {
                    sepModel.removeSubscription(indexer.getSubscriptionId());
                    log.info("Deleted queue subscription for indexer " + indexerName);
                    indexer = new IndexerDefinitionBuilder().startFrom(indexer).subscriptionId(null).build();
                    indexerModel.updateIndexerInternal(indexer);
                }
            } finally {
                indexerModel.unlockIndexer(lock);
            }
        } catch (Throwable t) {
            log.error("Error trying to delete the subscription for indexer " + indexerName, t);
        }
    }

    private String subscriptionId(String indexerName) {
        return "Indexer_" + indexerName;
    }

    private void startFullIndexBuild(final String indexerName) {
        try {
            String lock = indexerModel.lockIndexer(indexerName);
            try {
                // Read current situation of record and assure it is still actual
                final IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);
                IndexerDefinitionBuilder updatedIndexer = new IndexerDefinitionBuilder().startFrom(indexer);
                final String[] batchArguments = createBatchArguments(indexer);
                if (needsBatchBuildStart(indexer)) {
                    final ListeningExecutorService executor =
                            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
                    ListenableFuture<Integer> future = executor.submit(new Callable<Integer>() {
                        @Override
                        public Integer call() throws Exception {
                            HBaseMapReduceIndexerTool tool = new HBaseMapReduceIndexerTool();
                            tool.setConf(hbaseConf);
                            return tool.run(batchArguments, new IndexerDefinitionUpdaterJobProgressCallback(indexerName));
                        }
                    });

                    Futures.addCallback(future, new FutureCallback<Integer>() {
                        @Override
                        public void onSuccess(Integer exitCode) {
                            markBatchBuildCompleted(indexerName, exitCode == 0);
                            executor.shutdownNow();
                        }

                        @Override
                        public void onFailure(Throwable throwable) {
                            log.error("batch index build failed", throwable);
                            markBatchBuildCompleted(indexerName, false);
                            executor.shutdownNow();
                        }
                    }, MoreExecutors.directExecutor());

                    BatchBuildInfo jobInfo = new BatchBuildInfo(System.currentTimeMillis(), null, null, batchArguments);
                    updatedIndexer
                            .activeBatchBuildInfo(jobInfo)
                            .batchIndexingState(BatchIndexingState.BUILDING)
                            .batchIndexCliArguments(null)
                            .build();

                    indexerModel.updateIndexerInternal(updatedIndexer.build());

                    log.info("Started batch index build for index " + indexerName);

                }
            } finally {
                indexerModel.unlockIndexer(lock);
            }
        } catch (Throwable t) {
            log.error("Error trying to start index build job for index " + indexerName, t);
        }
    }

    private void markBatchBuildCompleted(String indexerName, boolean success) {
        try {
            // Lock internal bypasses the index-in-delete-state check, which does not matter (and might cause
            // failure) in our case.
            String lock = indexerModel.lockIndexerInternal(indexerName, false);
            try {
                // Read current situation of record and assure it is still actual
                IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);

                BatchBuildInfo activeJobInfo = indexer.getActiveBatchBuildInfo();

                if (activeJobInfo == null) {
                    // This might happen if we got some older update event on the indexer right after we
                    // marked this job as finished.
                    log.warn("Unexpected situation: indexer batch build completed but indexer does not have an active" +
                            " build job. Index: " + indexer.getName() + ". Ignoring this event.");
                    return;
                }

                BatchBuildInfo batchBuildInfo = new BatchBuildInfo(activeJobInfo);
                batchBuildInfo = batchBuildInfo.finishedSuccessfully(success);

                indexer = new IndexerDefinitionBuilder()
                        .startFrom(indexer)
                        .lastBatchBuildInfo(batchBuildInfo)
                        .activeBatchBuildInfo(null)
                        .batchIndexingState(BatchIndexingState.INACTIVE)
                        .build();

                indexerModel.updateIndexerInternal(indexer);

                log.info("Marked indexer batch build as finished for indexer " + indexerName);
            } finally {
                indexerModel.unlockIndexer(lock, true);
            }
        } catch (Throwable t) {
            log.error("Error trying to mark index batch build as finished for indexer " + indexerName, t);
        }
    }

    private String[] createBatchArguments(IndexerDefinition indexer) {
        String[] batchIndexArguments = indexer.getBatchIndexCliArgumentsOrDefault();
        List<String> args = Lists.newArrayList();

        String mode =
                Optional.fromNullable(indexer.getConnectionParams().get(SolrConnectionParams.MODE)).or("cloud").toLowerCase();

        if ("cloud".equals(mode)) { // cloud mode is the default
            args.add("--zk-host");
            args.add(indexer.getConnectionParams().get(SolrConnectionParams.ZOOKEEPER));
        } else {
            for (String shard : SolrConnectionParamUtil.getShards(indexer.getConnectionParams())) {
                args.add("--shard-url");
                args.add(shard);
            }
        }
        args.add("--hbase-indexer-zk");
        args.add(zkConnectString);

        args.add("--hbase-indexer-name");
        args.add(indexer.getName());

        args.add("--reducers");
        args.add("0");

        // additional arguments that were configured on the index (e.g. HBase scan parameters)
        args.addAll(Lists.newArrayList(batchIndexArguments));

        return args.toArray(new String[args.size()]);
    }


    private void prepareDeleteIndex(String indexerName) {
        // We do not have to take a lock on the indexer, since once in delete state the indexer cannot
        // be modified anymore by ordinary users.
        boolean canBeDeleted = false;
        try {
            // Read current situation of record and assure it is still actual
            IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);
            log.debug("EventWorker indexer.getLifecycleState: " + indexer.getLifecycleState());
            if (indexer.getLifecycleState() == IndexerDefinition.LifecycleState.DELETE_REQUESTED) {
                canBeDeleted = true;

                String queueSubscriptionId = indexer.getSubscriptionId();
                log.debug("EventWorker before removeSubscription for " + queueSubscriptionId);
                if (queueSubscriptionId != null) {
                    sepModel.removeSubscription(indexer.getSubscriptionId());
                    // We leave the subscription ID in the indexer definition FYI
                }
                log.debug("EventWorker after removeSubscription for " + queueSubscriptionId);

                if (indexer.getActiveBatchBuildInfo() != null) {
                    JobClient jobClient = getJobClient();
                    Set<String> jobs = indexer.getActiveBatchBuildInfo().getMapReduceJobTrackingUrls().keySet();
                    for (String jobId : jobs) {
                        RunningJob job = jobClient.getJob(jobId);
                        if (job != null) {
                            job.killJob();
                            log.info("Kill indexer build job for indexer " + indexerName + ", job ID =  " + jobId);
                        }
                        canBeDeleted = false;
                    }
                }

                if (!canBeDeleted) {
                    indexer = new IndexerDefinitionBuilder()
                            .startFrom(indexer).lifecycleState(IndexerDefinition.LifecycleState.DELETING).build();
                    indexerModel.updateIndexerInternal(indexer);
                }
            } else if (indexer.getLifecycleState() == IndexerDefinition.LifecycleState.DELETING) {
                // Check if the build job is already finished, if so, allow delete
                if (indexer.getActiveBatchBuildInfo() == null) {
                    canBeDeleted = true;
                }
            }
        } catch (Throwable t) {
            log.error("Error preparing deletion of indexer " + indexerName, t);
        }

        if (canBeDeleted) {
            deleteIndexer(indexerName);
        }
    }

    private void deleteIndexer(String indexerName) {
        // delete model
        boolean failedToDeleteIndexer = false;
        try {
            indexerModel.deleteIndexerInternal(indexerName);
        } catch (Throwable t) {
            log.error("Failed to delete indexer " + indexerName, t);
            failedToDeleteIndexer = true;
        }

        if (failedToDeleteIndexer) {
            try {
                IndexerDefinition indexer = indexerModel.getFreshIndexer(indexerName);
                indexer = new IndexerDefinitionBuilder().startFrom(indexer)
                        .lifecycleState(IndexerDefinition.LifecycleState.DELETE_FAILED).build();
                indexerModel.updateIndexerInternal(indexer);
            } catch (Throwable t) {
                log.error("Failed to set indexer state to " + IndexerDefinition.LifecycleState.DELETE_FAILED, t);
            }
        }
    }

    private class MyListener implements IndexerModelListener {
        @Override
        public void process(IndexerModelEvent event) {
            try {
                // Let the events be processed by another thread. Especially important since
                // we take ZkLock's in the event handlers (see ZkLock javadoc).
                eventWorker.putEvent(event);
            } catch (InterruptedException e) {
                log.info("IndexerMaster.IndexerModelListener interrupted.");
            }
        }
    }

    private class EventWorker implements Runnable {

        private BlockingQueue<IndexerModelEvent> eventQueue = new LinkedBlockingQueue<IndexerModelEvent>();

        private boolean stop;

        private Thread thread;

        public synchronized void shutdown(boolean interrupt) throws InterruptedException {
            stop = true;
            eventQueue.clear();

            if (!thread.isAlive()) {
                return;
            }

            if (interrupt)
                thread.interrupt();
            thread.join();
            thread = null;
        }

        public synchronized void start() throws InterruptedException {
            if (thread != null) {
                log.warn("EventWorker start was requested, but old thread was still there. Stopping it now.");
                thread.interrupt();
                thread.join();
            }
            eventQueue.clear();
            stop = false;
            thread = new Thread(this, "IndexerMasterEventWorker");
            thread.start();
        }

        public void putEvent(IndexerModelEvent event) throws InterruptedException {
            if (stop) {
                throw new RuntimeException("This EventWorker is stopped, no events should be added.");
            }
            eventQueue.put(event);
        }

        @Override
        public void run() {
            long startedAt = System.currentTimeMillis();

            while (!stop && !Thread.interrupted()) {
                try {
                    IndexerModelEvent event = null;
                    while (!stop && event == null) {
                        event = eventQueue.poll(1000, TimeUnit.MILLISECONDS);
                    }

                    if (stop || event == null || Thread.interrupted()) {
                        return;
                    }

                    // Warn if the queue is getting large, but do not do this just after we started, because
                    // on initial startup a fake update event is added for every defined index, which would lead
                    // to this message always being printed on startup when more than 10 indexes are defined.
                    int queueSize = eventQueue.size();
                    if (queueSize >= 10 && (System.currentTimeMillis() - startedAt > 5000)) {
                        log.warn("EventWorker queue getting large, size = " + queueSize);
                    }

                    log.debug("EventWorker received event type = " + event.getType());
                    if (event.getType() == INDEXER_ADDED || event.getType() == INDEXER_UPDATED) {
                        IndexerDefinition indexer = null;
                        try {
                            indexer = indexerModel.getIndexer(event.getIndexerName());
                        } catch (IndexerNotFoundException e) {
                            // ignore, indexer has meanwhile been deleted, we will get another event for this
                        }

                        log.debug("EventWorker indexer isNull:" + (indexer == null));
                        if (indexer != null) {
                            if (indexer.getLifecycleState() == IndexerDefinition.LifecycleState.DELETE_REQUESTED ||
                                    indexer.getLifecycleState() == IndexerDefinition.LifecycleState.DELETING) {
                                log.debug("EventWorker before prepareDeleteIndex " + indexer.getName());
                                prepareDeleteIndex(indexer.getName());
                                log.debug("EventWorker after prepareDeleteIndex " + indexer.getName());
                                for (IndexerLifecycleListener lifecycleListener : lifecycleListeners) {
                                    lifecycleListener.onDelete(indexer);
                                }

                                // in case of delete, we do not need to handle any other cases
                            } else {
                                if (needsSubscriptionIdAssigned(indexer)) {
                                    assignSubscription(indexer.getName());
                                    for (IndexerLifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.onSubscribe(indexer);
                                    }
                                }

                                if (needsSubscriptionIdUnassigned(indexer)) {
                                    unassignSubscription(indexer.getName());
                                    for (IndexerLifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.onUnsubscribe(indexer);
                                    }
                                }

                                if (needsBatchBuildStart(indexer)) {
                                    startFullIndexBuild(indexer.getName());
                                    for (IndexerLifecycleListener lifecycleListener : lifecycleListeners) {
                                        lifecycleListener.onBatchBuild(indexer);
                                    }
                                }
                            }
                        }
                    }
                    eventCount.incrementAndGet();
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable t) {
                    log.error("Error processing indexer model event in IndexerMaster.", t);
                }
            }
        }
    }

    /**
     * Updates the index definition with information about MR jobs that are involved in the batch build (tracking urls mainly).
     */
    private final class IndexerDefinitionUpdaterJobProgressCallback implements JobProcessCallback {

        private final String indexerName;

        private IndexerDefinitionUpdaterJobProgressCallback(String indexerName) {
            this.indexerName = indexerName;
        }

        @Override
        public void jobStarted(String jobId, String trackingUrl) {
            try {
                // Lock internal bypasses the index-in-delete-state check, which does not matter (and might cause
                // failure) in our case.
                String lock = indexerModel.lockIndexerInternal(indexerName, false);
                try {
                    IndexerDefinition definition = indexerModel.getFreshIndexer(indexerName);
                    BatchBuildInfo batchBuildInfo =
                            new BatchBuildInfo(definition.getActiveBatchBuildInfo()).withJob(jobId, trackingUrl);
                    IndexerDefinition updatedDefinition = new IndexerDefinitionBuilder().startFrom(definition)
                            .activeBatchBuildInfo(batchBuildInfo)
                            .build();
                    indexerModel.updateIndexerInternal(updatedDefinition);

                    log.info("Updated indexer batch build state for indexer " + indexerName);
                } finally {
                    indexerModel.unlockIndexer(lock, true);
                }
            } catch (Exception e) {
                log.error("failed to update indexer batch build state for indexer " + indexerName);
            }
        }
    }
}
