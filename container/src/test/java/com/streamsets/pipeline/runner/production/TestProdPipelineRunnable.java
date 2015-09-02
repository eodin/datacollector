/**
 * (c) 2014 StreamSets, Inc. All rights reserved. May not
 * be copied, modified, or distributed in whole or part without
 * written consent of StreamSets, Inc.
 */
package com.streamsets.pipeline.runner.production;

import com.codahale.metrics.MetricRegistry;
import com.streamsets.pipeline.api.BatchMaker;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.api.base.BaseSource;
import com.streamsets.pipeline.config.DeliveryGuarantee;
import com.streamsets.pipeline.config.MemoryLimitConfiguration;
import com.streamsets.pipeline.main.RuntimeInfo;
import com.streamsets.pipeline.main.RuntimeModule;
import com.streamsets.pipeline.prodmanager.PipelineManagerException;
import com.streamsets.pipeline.prodmanager.StandalonePipelineManagerTask;
import com.streamsets.pipeline.prodmanager.State;
import com.streamsets.pipeline.runner.MockStages;
import com.streamsets.pipeline.runner.PipelineRuntimeException;
import com.streamsets.pipeline.runner.SourceOffsetTracker;
import com.streamsets.pipeline.snapshotstore.SnapshotStatus;
import com.streamsets.pipeline.snapshotstore.impl.FileSnapshotStore;
import com.streamsets.pipeline.stagelibrary.StageLibraryTask;
import com.streamsets.pipeline.store.PipelineStoreTask;
import com.streamsets.pipeline.store.impl.FilePipelineStoreTask;
import com.streamsets.pipeline.util.Configuration;
import com.streamsets.pipeline.util.TestUtil;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

public class TestProdPipelineRunnable {

  private static final String PIPELINE_NAME = "xyz";
  private static final String REVISION = "1.0";
  private static final String SNAPSHOT_NAME = "snapshot";
  private StandalonePipelineManagerTask manager;
  private PipelineStoreTask pipelineStoreTask;
  private RuntimeInfo info;

  @BeforeClass
  public static void beforeClass() throws IOException {
    System.setProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR, "./target/var");
    File f = new File(System.getProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR));
    FileUtils.deleteDirectory(f);
  }

  @AfterClass
  public static void afterClass() throws IOException {
    System.getProperties().remove(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR);
  }

  @Before()
  public void setUp() {
    MockStages.resetStageCaptures();
    info = new RuntimeInfo(RuntimeModule.SDC_PROPERTY_PREFIX, new MetricRegistry(),
      Arrays.asList(getClass().getClassLoader()));
    pipelineStoreTask = Mockito.mock(FilePipelineStoreTask.class);
    Mockito.when(pipelineStoreTask.hasPipeline(PIPELINE_NAME)).thenReturn(true);
    manager = new StandalonePipelineManagerTask(info, Mockito.mock(Configuration.class), pipelineStoreTask,
      Mockito.mock(StageLibraryTask.class));
    manager.init();
  }

  @After
  public void tearDown() {
    manager.getStateTracker().getStateFile().delete();
  }

  @Test
  public void testRun() throws Exception {

    TestUtil.captureMockStages();

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, true);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(null, manager, pipeline, PIPELINE_NAME, REVISION,
      Collections.<Future<?>>emptyList());
    manager.getStateTracker().setState(PIPELINE_NAME, REVISION, State.RUNNING, null, null, null);
    runnable.run();

    //The source returns null offset because all the data from source was read
    Assert.assertNull(pipeline.getCommittedOffset());

    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  @Test
  public void testStop() throws Exception {

    TestUtil.captureMockStages();

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, false);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(null, manager, pipeline, PIPELINE_NAME, REVISION,
      Collections.<Future<?>>emptyList());
    manager.getStateTracker().setState(PIPELINE_NAME, REVISION, State.STOPPING, null, null, null);
    runnable.stop(false);
    Assert.assertTrue(pipeline.wasStopped());

    //Stops after the first batch
    runnable.run();

    //Offset 1 expected as pipeline was stopped after the first batch
    Assert.assertEquals("1", pipeline.getCommittedOffset());
    //no output as capture was not set to true
    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  private volatile CountDownLatch latch;
  private volatile boolean stopInterrupted;

  @Test
  public void testStopInterrupt() throws Exception {
    latch = new CountDownLatch(1);
    stopInterrupted = false;
    MockStages.setSourceCapture(new BaseSource() {
      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        try {
          latch.countDown();
          Thread.currentThread().sleep(1000000);
        } catch (InterruptedException ex) {
          stopInterrupted = true;
        }
        return null;
      }
    });

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, false);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(null, manager, pipeline, PIPELINE_NAME, REVISION,
      Collections.<Future<?>>emptyList());

    Thread t = new Thread(runnable);
    t.start();
    latch.await();
    runnable.stop(false);
    t.join();
    Assert.assertTrue(stopInterrupted);
  }

  @Test
  public void testErrorState() throws Exception {
    System.setProperty(RuntimeModule.SDC_PROPERTY_PREFIX + RuntimeInfo.DATA_DIR, "./target/var");

    MockStages.setSourceCapture(new BaseSource() {
      @Override
      public String produce(String lastSourceOffset, int maxBatchSize, BatchMaker batchMaker) throws StageException {
        throw new RuntimeException("Simulate runtime failure in source");
      }
    });

    ProductionPipeline pipeline = createProductionPipeline(DeliveryGuarantee.AT_MOST_ONCE, true);
    ProductionPipelineRunnable runnable = new ProductionPipelineRunnable(null, manager, pipeline, PIPELINE_NAME, REVISION,
      Collections.<Future<?>>emptyList());

    //Stops after the first batch
    runnable.run();

    Assert.assertEquals(State.ERROR, manager.getPipelineState().getState());
    //Offset 1 expected as there was a Runtime exception
    Assert.assertEquals("1", pipeline.getCommittedOffset());
    //no output as captured as there was an exception in source
    Assert.assertTrue(pipeline.getPipeline().getRunner().getBatchesOutput().isEmpty());
  }

  private ProductionPipeline createProductionPipeline(DeliveryGuarantee deliveryGuarantee, boolean captureNextBatch)
    throws PipelineRuntimeException, PipelineManagerException, StageException {
    RuntimeInfo runtimeInfo = Mockito.mock(RuntimeInfo.class);
    Mockito.when(runtimeInfo.getId()).thenReturn("id");

    SourceOffsetTracker tracker = new TestUtil.SourceOffsetTrackerImpl("1");
    FileSnapshotStore snapshotStore = Mockito.mock(FileSnapshotStore.class);

    Mockito.when(snapshotStore.getSnapshotStatus(PIPELINE_NAME, REVISION, SNAPSHOT_NAME)).
      thenReturn(new SnapshotStatus(false, false));
    BlockingQueue<Object> productionObserveRequests = new ArrayBlockingQueue<>(100, true /*FIFO*/);
    ProductionPipelineRunner runner = new ProductionPipelineRunner(runtimeInfo, snapshotStore, deliveryGuarantee,
      PIPELINE_NAME, REVISION, productionObserveRequests, new Configuration(), new MemoryLimitConfiguration());
    ProductionPipeline pipeline = new ProductionPipelineBuilder(MockStages.createStageLibrary(), PIPELINE_NAME,
        REVISION, runtimeInfo, MockStages.createPipelineConfigurationSourceProcessorTarget())
      .build(runner, tracker, null);
    manager.getStateTracker().register(PIPELINE_NAME, REVISION);
    manager.getStateTracker().setState(PIPELINE_NAME, REVISION, State.STOPPED, null, null, null);

    if(captureNextBatch) {
      runner.captureNextBatch(SNAPSHOT_NAME, 1);
    }

    return pipeline;
  }

}