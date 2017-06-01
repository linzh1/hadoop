/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.uam;

import java.io.IOException;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience.Public;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.security.token.Token;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.ApplicationMasterProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateRequest;
import org.apache.hadoop.yarn.api.protocolrecords.AllocateResponse;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.FinishApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationAttemptReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationReportRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationRequest;
import org.apache.hadoop.yarn.api.protocolrecords.KillApplicationResponse;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterRequest;
import org.apache.hadoop.yarn.api.protocolrecords.RegisterApplicationMasterResponse;
import org.apache.hadoop.yarn.api.protocolrecords.SubmitApplicationRequest;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptReport;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.api.records.ApplicationSubmissionContext;
import org.apache.hadoop.yarn.api.records.ContainerLaunchContext;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.YarnApplicationAttemptState;
import org.apache.hadoop.yarn.api.records.YarnApplicationState;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.factories.RecordFactory;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.security.AMRMTokenIdentifier;
import org.apache.hadoop.yarn.server.utils.AMRMClientUtils;
import org.apache.hadoop.yarn.server.utils.BuilderUtils;
import org.apache.hadoop.yarn.server.utils.YarnServerSecurityUtils;
import org.apache.hadoop.yarn.util.AsyncCallback;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

/**
 * UnmanagedApplicationManager is used to register unmanaged application and
 * negotiate for resources from resource managers. An unmanagedAM is an AM that
 * is not launched and managed by the RM. Allocate calls are handled
 * asynchronously using {@link AsyncCallback}.
 */
@Public
@Unstable
public class UnmanagedApplicationManager {
  private static final Logger LOG =
      LoggerFactory.getLogger(UnmanagedApplicationManager.class);
  private static final long AM_STATE_WAIT_TIMEOUT_MS = 10000;
  private static final String APP_NAME = "UnmanagedAM";
  private static final String DEFAULT_QUEUE_CONFIG = "uam.default.queue.name";

  private BlockingQueue<AsyncAllocateRequestInfo> requestQueue;
  private AMRequestHandlerThread handlerThread;
  private ApplicationMasterProtocol rmProxy;
  private ApplicationId applicationId;
  private ApplicationAttemptId attemptId;
  private String submitter;
  private String appNameSuffix;
  private Configuration conf;
  private String queueName;
  private UserGroupInformation userUgi;
  private RegisterApplicationMasterRequest registerRequest;
  private int lastResponseId;
  private ApplicationClientProtocol rmClient;
  private long asyncApiPollIntervalMillis;
  private RecordFactory recordFactory;

  public UnmanagedApplicationManager(Configuration conf, ApplicationId appId,
      String queueName, String submitter, String appNameSuffix) {
    Preconditions.checkNotNull(conf, "Configuration cannot be null");
    Preconditions.checkNotNull(appId, "ApplicationId cannot be null");
    Preconditions.checkNotNull(submitter, "App submitter cannot be null");

    this.conf = conf;
    this.applicationId = appId;
    this.queueName = queueName;
    this.submitter = submitter;
    this.appNameSuffix = appNameSuffix;
    this.handlerThread = new AMRequestHandlerThread();
    this.requestQueue = new LinkedBlockingQueue<>();
    this.rmProxy = null;
    this.registerRequest = null;
    this.recordFactory = RecordFactoryProvider.getRecordFactory(conf);
    this.asyncApiPollIntervalMillis = conf.getLong(
        YarnConfiguration.
            YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_INTERVAL_MS,
        YarnConfiguration.
            DEFAULT_YARN_CLIENT_APPLICATION_CLIENT_PROTOCOL_POLL_INTERVAL_MS);
  }

  /**
   * Registers this {@link UnmanagedApplicationManager} with the resource
   * manager.
   *
   * @param request the register request
   * @return the register response
   * @throws YarnException if register fails
   * @throws IOException if register fails
   */
  public RegisterApplicationMasterResponse createAndRegisterApplicationMaster(
      RegisterApplicationMasterRequest request)
      throws YarnException, IOException {
    // This need to be done first in this method, because it is used as an
    // indication that this method is called (and perhaps blocked due to RM
    // connection and not finished yet)
    this.registerRequest = request;

    // attemptId will be available after this call
    UnmanagedAMIdentifier identifier =
        initializeUnmanagedAM(this.applicationId);

    try {
      this.userUgi = UserGroupInformation.createProxyUser(
          identifier.getAttemptId().toString(),
          UserGroupInformation.getCurrentUser());
    } catch (IOException e) {
      LOG.error("Exception while trying to get current user", e);
      throw new YarnRuntimeException(e);
    }

    this.rmProxy = createRMProxy(ApplicationMasterProtocol.class, this.conf,
        this.userUgi, identifier.getToken());

    LOG.info("Registering the Unmanaged application master {}", this.attemptId);
    RegisterApplicationMasterResponse response =
        this.rmProxy.registerApplicationMaster(this.registerRequest);

    // Only when register succeed that we start the heartbeat thread
    this.handlerThread.setUncaughtExceptionHandler(
        new HeartBeatThreadUncaughtExceptionHandler());
    this.handlerThread.setDaemon(true);
    this.handlerThread.start();

    this.lastResponseId = 0;
    return response;
  }

  /**
   * Unregisters from the resource manager and stops the request handler thread.
   *
   * @param request the finishApplicationMaster request
   * @return the response
   * @throws YarnException if finishAM call fails
   * @throws IOException if finishAM call fails
   */
  public FinishApplicationMasterResponse finishApplicationMaster(
      FinishApplicationMasterRequest request)
      throws YarnException, IOException {

    this.handlerThread.shutdown();

    if (this.rmProxy == null) {
      if (this.registerRequest != null) {
        // This is possible if the async registerApplicationMaster is still
        // blocked and retrying. Return a dummy response in this case.
        LOG.warn("Unmanaged AM still not successfully launched/registered yet."
            + " Stopping the UAM client thread anyways.");
        return FinishApplicationMasterResponse.newInstance(false);
      } else {
        throw new YarnException("finishApplicationMaster should not "
            + "be called before createAndRegister");
      }
    }
    return AMRMClientUtils.finishAMWithReRegister(request, this.rmProxy,
        this.registerRequest, this.attemptId);
  }

  /**
   * Force kill the UAM.
   *
   * @return kill response
   * @throws IOException if fails to create rmProxy
   * @throws YarnException if force kill fails
   */
  public KillApplicationResponse forceKillApplication()
      throws IOException, YarnException {
    KillApplicationRequest request =
        KillApplicationRequest.newInstance(this.attemptId.getApplicationId());

    this.handlerThread.shutdown();

    if (this.rmClient == null) {
      this.rmClient = createRMProxy(ApplicationClientProtocol.class, this.conf,
          UserGroupInformation.createRemoteUser(this.submitter), null);
    }
    return this.rmClient.forceKillApplication(request);
  }

  /**
   * Sends the specified heart beat request to the resource manager and invokes
   * the callback asynchronously with the response.
   *
   * @param request the allocate request
   * @param callback the callback method for the request
   * @throws YarnException if registerAM is not called yet
   */
  public void allocateAsync(AllocateRequest request,
      AsyncCallback<AllocateResponse> callback) throws YarnException {
    try {
      this.requestQueue.put(new AsyncAllocateRequestInfo(request, callback));
    } catch (InterruptedException ex) {
      // Should not happen as we have MAX_INT queue length
      LOG.debug("Interrupted while waiting to put on response queue", ex);
    }
    // Two possible cases why the UAM is not successfully registered yet:
    // 1. registerApplicationMaster is not called at all. Should throw here.
    // 2. registerApplicationMaster is called but hasn't successfully returned.
    //
    // In case 2, we have already save the allocate request above, so if the
    // registration succeed later, no request is lost.
    if (this.rmProxy == null) {
      if (this.registerRequest != null) {
        LOG.info("Unmanaged AM still not successfully launched/registered yet."
            + " Saving the allocate request and send later.");
      } else {
        throw new YarnException(
            "AllocateAsync should not be called before createAndRegister");
      }
    }
  }

  /**
   * Returns the application attempt id of the UAM.
   *
   * @return attempt id of the UAM
   */
  public ApplicationAttemptId getAttemptId() {
    return this.attemptId;
  }

  /**
   * Returns RM proxy for the specified protocol type. Unit test cases can
   * override this method and return mock proxy instances.
   *
   * @param protocol protocal of the proxy
   * @param config configuration
   * @param user ugi for the proxy connection
   * @param token token for the connection
   * @param <T> type of the proxy
   * @return the proxy instance
   * @throws IOException if fails to create the proxy
   */
  protected <T> T createRMProxy(Class<T> protocol, Configuration config,
      UserGroupInformation user, Token<AMRMTokenIdentifier> token)
      throws IOException {
    return AMRMClientUtils.createRMProxy(config, protocol, user, token);
  }

  /**
   * Launch and initialize an unmanaged AM. First, it creates a new application
   * on the RM and negotiates a new attempt id. Then it waits for the RM
   * application attempt state to reach YarnApplicationAttemptState.LAUNCHED
   * after which it returns the AM-RM token and the attemptId.
   *
   * @param appId application id
   * @return the UAM identifier
   * @throws IOException if initialize fails
   * @throws YarnException if initialize fails
   */
  protected UnmanagedAMIdentifier initializeUnmanagedAM(ApplicationId appId)
      throws IOException, YarnException {
    try {
      UserGroupInformation appSubmitter =
          UserGroupInformation.createRemoteUser(this.submitter);
      this.rmClient = createRMProxy(ApplicationClientProtocol.class, this.conf,
          appSubmitter, null);

      // Submit the application
      submitUnmanagedApp(appId);

      // Monitor the application attempt to wait for launch state
      ApplicationAttemptReport attemptReport = monitorCurrentAppAttempt(appId,
          EnumSet.of(YarnApplicationState.ACCEPTED,
              YarnApplicationState.RUNNING, YarnApplicationState.KILLED,
              YarnApplicationState.FAILED, YarnApplicationState.FINISHED),
          YarnApplicationAttemptState.LAUNCHED);
      this.attemptId = attemptReport.getApplicationAttemptId();
      return getUAMIdentifier();
    } finally {
      this.rmClient = null;
    }
  }

  private void submitUnmanagedApp(ApplicationId appId)
      throws YarnException, IOException {
    SubmitApplicationRequest submitRequest =
        this.recordFactory.newRecordInstance(SubmitApplicationRequest.class);

    ApplicationSubmissionContext context = this.recordFactory
        .newRecordInstance(ApplicationSubmissionContext.class);

    context.setApplicationId(appId);
    context.setApplicationName(APP_NAME + "-" + appNameSuffix);
    if (StringUtils.isBlank(this.queueName)) {
      context.setQueue(this.conf.get(DEFAULT_QUEUE_CONFIG,
          YarnConfiguration.DEFAULT_QUEUE_NAME));
    } else {
      context.setQueue(this.queueName);
    }

    ContainerLaunchContext amContainer =
        this.recordFactory.newRecordInstance(ContainerLaunchContext.class);
    Resource resource = BuilderUtils.newResource(1024, 1);
    context.setResource(resource);
    context.setAMContainerSpec(amContainer);
    submitRequest.setApplicationSubmissionContext(context);

    context.setUnmanagedAM(true);

    LOG.info("Submitting unmanaged application {}", appId);
    this.rmClient.submitApplication(submitRequest);
  }

  /**
   * Monitor the submitted application and attempt until it reaches certain
   * states.
   *
   * @param appId Application Id of application to be monitored
   * @param appStates acceptable application state
   * @param attemptState acceptable application attempt state
   * @return the application report
   * @throws YarnException if getApplicationReport fails
   * @throws IOException if getApplicationReport fails
   */
  private ApplicationAttemptReport monitorCurrentAppAttempt(ApplicationId appId,
      Set<YarnApplicationState> appStates,
      YarnApplicationAttemptState attemptState)
      throws YarnException, IOException {

    long startTime = System.currentTimeMillis();
    ApplicationAttemptId appAttemptId = null;
    while (true) {
      if (appAttemptId == null) {
        // Get application report for the appId we are interested in
        ApplicationReport report = getApplicationReport(appId);
        YarnApplicationState state = report.getYarnApplicationState();
        if (appStates.contains(state)) {
          if (state != YarnApplicationState.ACCEPTED) {
            throw new YarnRuntimeException(
                "Received non-accepted application state: " + state
                    + ". Application " + appId + " not the first attempt?");
          }
          appAttemptId =
              getApplicationReport(appId).getCurrentApplicationAttemptId();
        } else {
          LOG.info("Current application state of {} is {}, will retry later.",
              appId, state);
        }
      }

      if (appAttemptId != null) {
        GetApplicationAttemptReportRequest req = this.recordFactory
            .newRecordInstance(GetApplicationAttemptReportRequest.class);
        req.setApplicationAttemptId(appAttemptId);
        ApplicationAttemptReport attemptReport = this.rmClient
            .getApplicationAttemptReport(req).getApplicationAttemptReport();
        if (attemptState
            .equals(attemptReport.getYarnApplicationAttemptState())) {
          return attemptReport;
        }
        LOG.info("Current attempt state of " + appAttemptId + " is "
            + attemptReport.getYarnApplicationAttemptState()
            + ", waiting for current attempt to reach " + attemptState);
      }

      try {
        Thread.sleep(this.asyncApiPollIntervalMillis);
      } catch (InterruptedException e) {
        LOG.warn("Interrupted while waiting for current attempt of " + appId
            + " to reach " + attemptState);
      }

      if (System.currentTimeMillis() - startTime > AM_STATE_WAIT_TIMEOUT_MS) {
        throw new RuntimeException("Timeout for waiting current attempt of "
            + appId + " to reach " + attemptState);
      }
    }
  }

  /**
   * Gets the identifier of the unmanaged AM.
   *
   * @return the identifier of the unmanaged AM.
   * @throws IOException if getApplicationReport fails
   * @throws YarnException if getApplicationReport fails
   */
  protected UnmanagedAMIdentifier getUAMIdentifier()
      throws IOException, YarnException {
    Token<AMRMTokenIdentifier> token = null;
    org.apache.hadoop.yarn.api.records.Token amrmToken =
        getApplicationReport(this.attemptId.getApplicationId()).getAMRMToken();
    if (amrmToken != null) {
      token = ConverterUtils.convertFromYarn(amrmToken, (Text) null);
    } else {
      LOG.warn(
          "AMRMToken not found in the application report for application: {}",
          this.attemptId.getApplicationId());
    }
    return new UnmanagedAMIdentifier(this.attemptId, token);
  }

  private ApplicationReport getApplicationReport(ApplicationId appId)
      throws YarnException, IOException {
    GetApplicationReportRequest request =
        this.recordFactory.newRecordInstance(GetApplicationReportRequest.class);
    request.setApplicationId(appId);
    return this.rmClient.getApplicationReport(request).getApplicationReport();
  }

  /**
   * Data structure that encapsulates the application attempt identifier and the
   * AMRMTokenIdentifier. Make it public because clients with HA need it.
   */
  public static class UnmanagedAMIdentifier {
    private ApplicationAttemptId attemptId;
    private Token<AMRMTokenIdentifier> token;

    public UnmanagedAMIdentifier(ApplicationAttemptId attemptId,
        Token<AMRMTokenIdentifier> token) {
      this.attemptId = attemptId;
      this.token = token;
    }

    public ApplicationAttemptId getAttemptId() {
      return this.attemptId;
    }

    public Token<AMRMTokenIdentifier> getToken() {
      return this.token;
    }
  }

  /**
   * Data structure that encapsulates AllocateRequest and AsyncCallback
   * instance.
   */
  public static class AsyncAllocateRequestInfo {
    private AllocateRequest request;
    private AsyncCallback<AllocateResponse> callback;

    public AsyncAllocateRequestInfo(AllocateRequest request,
        AsyncCallback<AllocateResponse> callback) {
      Preconditions.checkArgument(request != null,
          "AllocateRequest cannot be null");
      Preconditions.checkArgument(callback != null, "Callback cannot be null");

      this.request = request;
      this.callback = callback;
    }

    public AsyncCallback<AllocateResponse> getCallback() {
      return this.callback;
    }

    public AllocateRequest getRequest() {
      return this.request;
    }
  }

  @VisibleForTesting
  public int getRequestQueueSize() {
    return this.requestQueue.size();
  }

  /**
   * Extends Thread and provides an implementation that is used for processing
   * the AM heart beat request asynchronously and sending back the response
   * using the callback method registered with the system.
   */
  public class AMRequestHandlerThread extends Thread {

    // Indication flag for the thread to keep running
    private volatile boolean keepRunning;

    public AMRequestHandlerThread() {
      super("UnmanagedApplicationManager Heartbeat Handler Thread");
      this.keepRunning = true;
    }

    /**
     * Shutdown the thread.
     */
    public void shutdown() {
      this.keepRunning = false;
      this.interrupt();
    }

    @Override
    public void run() {
      while (keepRunning) {
        AsyncAllocateRequestInfo requestInfo;
        try {
          requestInfo = requestQueue.take();
          if (requestInfo == null) {
            throw new YarnException(
                "Null requestInfo taken from request queue");
          }
          if (!keepRunning) {
            break;
          }

          // change the response id before forwarding the allocate request as we
          // could have different values for each UAM
          AllocateRequest request = requestInfo.getRequest();
          if (request == null) {
            throw new YarnException("Null allocateRequest from requestInfo");
          }
          if (LOG.isDebugEnabled()) {
            LOG.debug("Sending Heartbeat to Unmanaged AM. AskList:"
                + ((request.getAskList() == null) ? " empty"
                    : request.getAskList().size()));
          }

          request.setResponseId(lastResponseId);
          AllocateResponse response = AMRMClientUtils.allocateWithReRegister(
              request, rmProxy, registerRequest, attemptId);
          if (response == null) {
            throw new YarnException("Null allocateResponse from allocate");
          }

          lastResponseId = response.getResponseId();
          // update token if RM has reissued/renewed
          if (response.getAMRMToken() != null) {
            LOG.debug("Received new AMRMToken");
            YarnServerSecurityUtils.updateAMRMToken(response.getAMRMToken(),
                userUgi, conf);
          }

          if (LOG.isDebugEnabled()) {
            LOG.debug("Received Heartbeat reply from RM. Allocated Containers:"
                + ((response.getAllocatedContainers() == null) ? " empty"
                    : response.getAllocatedContainers().size()));
          }

          if (requestInfo.getCallback() == null) {
            throw new YarnException("Null callback from requestInfo");
          }
          requestInfo.getCallback().callback(response);
        } catch (InterruptedException ex) {
          if (LOG.isDebugEnabled()) {
            LOG.debug("Interrupted while waiting for queue", ex);
          }
        } catch (IOException ex) {
          LOG.warn(
              "IO Error occurred while processing heart beat for " + attemptId,
              ex);
        } catch (Throwable ex) {
          LOG.warn(
              "Error occurred while processing heart beat for " + attemptId,
              ex);
        }
      }

      LOG.info("UnmanagedApplicationManager has been stopped for {}. "
          + "AMRequestHandlerThread thread is exiting", attemptId);
    }
  }

  /**
   * Uncaught exception handler for the background heartbeat thread.
   */
  protected class HeartBeatThreadUncaughtExceptionHandler
      implements UncaughtExceptionHandler {
    @Override
    public void uncaughtException(Thread t, Throwable e) {
      LOG.error("Heartbeat thread {} for application attempt {} crashed!",
          t.getName(), attemptId, e);
    }
  }
}