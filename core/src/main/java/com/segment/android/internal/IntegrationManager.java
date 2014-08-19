package com.segment.android.internal;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import com.segment.android.Segment;
import com.segment.android.internal.integrations.AbstractIntegration;
import com.segment.android.internal.integrations.AmplitudeIntegration;
import com.segment.android.internal.integrations.InvalidConfigurationException;
import com.segment.android.internal.payload.AliasPayload;
import com.segment.android.internal.payload.GroupPayload;
import com.segment.android.internal.payload.IdentifyPayload;
import com.segment.android.internal.payload.ScreenPayload;
import com.segment.android.internal.payload.TrackPayload;
import com.segment.android.internal.settings.AmplitudeSettings;
import com.segment.android.internal.settings.ProjectSettings;
import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static com.segment.android.internal.Utils.defaultSingleThreadedExecutor;

/**
 * Manages bundled integrations. This class will maintain it's own queue for events to account for
 * the latency between receiving the first event, fetching remote settings and enabling the
 * integrations. Once we enable all integrations - we'll replay any events on disk. This should only
 * affect the first app install, subsequent launches will be use a cached value from disk. Note that
 * none of the activity lifecycle events are queued to disk.
 */
public class IntegrationManager {
  enum Integration {
    AMPLITUDE("com.amplitude.api.Amplitude");

    private final String className;

    Integration(String className) {
      this.className = className;
    }
  }

  // A set of integrations available on the device
  private final Set<Integration> availableBundledIntegrations = new HashSet<Integration>();
  // A set of integrations that are available and have been enabled for this project.
  private final List<AbstractIntegration> enabledIntegrations =
      new LinkedList<AbstractIntegration>();

  final Context context;
  final SegmentHTTPApi segmentHTTPApi;
  final Handler mainThreadHandler;
  final ExecutorService service;

  public static IntegrationManager create(Context context, Handler mainThreadHandler,
      SegmentHTTPApi segmentHTTPApi) {
    ExecutorService service = defaultSingleThreadedExecutor();
    return new IntegrationManager(context, mainThreadHandler, segmentHTTPApi, service);
  }

  IntegrationManager(Context context, Handler mainThreadHandler, SegmentHTTPApi segmentHTTPApi,
      ExecutorService service) {
    this.context = context;
    this.segmentHTTPApi = segmentHTTPApi;
    this.mainThreadHandler = mainThreadHandler;
    this.service = service;

    // Look up all the integrations available on the device. This is done early so that we can
    // disable sending to these integrations from the server.
    for (Integration integration : Integration.values()) {
      addBundledIntegration(integration);
    }

    service.submit(new Runnable() {
      @Override public void run() {
        performFetch();
      }
    });
  }

  private void addBundledIntegration(Integration integration) {
    try {
      Class.forName(integration.className);
      availableBundledIntegrations.add(integration);
    } catch (ClassNotFoundException e) {
      Logger.d("%s integration not bundled.", integration.className);
    }
  }

  void performFetch() {
    try {
      final ProjectSettings projectSettings = segmentHTTPApi.fetchSettings();
      Segment.HANDLER.post(new Runnable() {
        @Override public void run() {
          // todo : does this need to be on the main thread?
          initialize(projectSettings);
        }
      });
    } catch (IOException e) {
      Logger.e(e, "Failed to fetch settings");
      performFetch(); // todo: terminate retry
    }
  }

  private void initialize(ProjectSettings projectSettings) {
    // Amplitude
    if (availableBundledIntegrations.contains(Integration.AMPLITUDE)) {
      AmplitudeSettings amplitudeSettings = projectSettings.getAmplitudeSettings();
      if (amplitudeSettings != null) {
        try {
          AbstractIntegration amplitude = new AmplitudeIntegration(context, amplitudeSettings);
          enabledIntegrations.add(amplitude);
        } catch (InvalidConfigurationException e) {
          Logger.e(e, "Could not initialize Amplitude.");
        }
      }
    }
  }

  public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityCreated(activity, savedInstanceState);
    }
  }

  void onActivityStarted(Activity activity) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityStarted(activity);
    }
  }

  void onActivityResumed(Activity activity) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityResumed(activity);
    }
  }

  void onActivityPaused(Activity activity) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityPaused(activity);
    }
  }

  void onActivityStopped(Activity activity) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityStopped(activity);
    }
  }

  void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivitySaveInstanceState(activity, outState);
    }
  }

  void onActivityDestroyed(Activity activity) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.onActivityDestroyed(activity);
    }
  }

  // Analytics Actions
  void identify(IdentifyPayload identify) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.identify(identify);
    }
  }

  void group(GroupPayload group) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.group(group);
    }
  }

  public void track(TrackPayload track) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.track(track);
    }
  }

  void alias(AliasPayload alias) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.alias(alias);
    }
  }

  void screen(ScreenPayload screen) {
    for (AbstractIntegration integration : enabledIntegrations) {
      integration.screen(screen);
    }
  }
}
