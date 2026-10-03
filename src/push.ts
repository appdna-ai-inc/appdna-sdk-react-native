import { AppdnaModule, addNativeListener } from './nativeModule';

/** One structured notification action button. `id` echoes back as `onPushTapped`'s actionId. */
export interface PushAction {
  id: string;
  label: string;
  action_type: string;
  action_value?: string;
}

export interface PushPayload {
  push_id: string;
  title: string;
  body: string;
  image_url?: string;
  data?: Record<string, string>;
  /** The notification-body action, flattened. */
  action_type?: string;
  action_value?: string;
  /** The registered action BUTTONS. `onPushTapped(actionId)` identifies which was tapped — look it
   *  up here for its type/value/label. (Native has always sent these; the wrappers used to drop them.) */
  actions?: PushAction[];
}

/** The native event payloads. Native wraps the notification, because an event payload is an object. */
type PushReceivedPayload = { payload: PushPayload; inForeground: boolean };
type PushTappedPayload = { payload: PushPayload; actionId?: string };

/**
 * Push notification management for AppDNA React Native SDK.
 */
export class AppDNAPush {
  /** Request push notification permission. */
  static async requestPermission(): Promise<boolean> {
    return AppdnaModule.requestPushPermission();
  }

  /** Listen for push received events. Returns unsubscribe function. */
  static onPushReceived(callback: (payload: PushPayload, inForeground: boolean) => void): () => void {
    const subscription = addNativeListener<PushReceivedPayload>('onPushReceived', (data) =>
      callback(data.payload, data.inForeground),
    );
    return () => subscription.remove();
  }

  /** Listen for push tapped events. Returns unsubscribe function. */
  static onPushTapped(callback: (payload: PushPayload, actionId?: string) => void): () => void {
    const subscription = addNativeListener<PushTappedPayload>('onPushTapped', (data) =>
      callback(data.payload, data.actionId),
    );
    return () => subscription.remove();
  }
}
