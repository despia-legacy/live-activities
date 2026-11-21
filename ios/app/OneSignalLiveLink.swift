import ActivityKit
import Foundation
#if !ONESIGNAL_EXCLUDED
import OneSignalFramework
import OneSignalLiveActivities
#endif

/// The only file of this extension that calls OneSignal.
///
/// One owner: the app's built-in OneSignal integration initializes the SDK (AppDelegate,
/// `OneSignal.initialize`) and owns push. This file never initializes it, never asks for push
/// permission and never touches the APNs device token. It uses OneSignal's token API for Live
/// Activities, the "enter/exit with manual token observation" form of OneSignal's iOS SDK
/// (OneSignalLiveActivitiesManagerImpl: enter(_:withToken:), exit(_:),
/// setPushToStartToken(_:withToken:)): the app observes ActivityKit's tokens itself and gives them
/// to OneSignal. That form needs no OneSignal code in the widget extension, so the widget links no
/// SDK and the app links only the OneSignalFramework product the base app already has.
///
/// Built without OneSignal (the dashboard turned it off: ONESIGNAL_EXCLUDED), every call here is a
/// no-op, `linked` is false and the page still receives every token through the `token` event and
/// `list`, for a server that talks to APNs itself.
enum OneSignalLiveLink {
    static var linked: Bool {
        #if ONESIGNAL_EXCLUDED || PUSH_DISABLED
        return false
        #else
        return true
        #endif
    }

    /// An update token for a running activity: OneSignal's
    /// POST /apps/{app_id}/live_activities/{activity_id}/notifications reaches it.
    static func enter(_ activityId: String, token: String) {
        #if !ONESIGNAL_EXCLUDED && !PUSH_DISABLED
        OneSignal.LiveActivities.enter(activityId, withToken: token)
        #endif
    }

    /// The activity ended or was dismissed: OneSignal stops sending to it.
    static func exit(_ activityId: String) {
        #if !ONESIGNAL_EXCLUDED && !PUSH_DISABLED
        OneSignal.LiveActivities.exit(activityId)
        #endif
    }

    /// A push-to-start token for one template (iOS 17.2+): OneSignal's
    /// POST /apps/{app_id}/activities/activity/{Attributes type name} starts one.
    @available(iOS 17.2, *)
    static func setPushToStart<T: ActivityAttributes>(_ type: T.Type, token: String) {
        #if !ONESIGNAL_EXCLUDED && !PUSH_DISABLED
        OneSignal.LiveActivities.setPushToStartToken(type, withToken: token)
        #endif
    }
}
