import ActivityKit
import Foundation

/// What every Live Activity template's attributes conform to. Compiled into the app (which starts
/// and tracks activities) and into the NXLiveActivity widget extension (which draws them).
///
/// A template is one file under ios/shared/templates/: its `ActivityAttributes` struct and its
/// `Widget`. To add one, copy a template file, rename the types and `templateName`, then add one
/// line to ios/app/LiveTemplateRegistry.swift, one line to
/// ios/targets/NXLiveActivity/NXLiveActivityBundle.swift and the file to both source lists of
/// liveactivity/manifest.json.
///
/// Rules every template keeps (the tests check them):
/// - `activityId` is a stored attribute. It is the id the page, your server and OneSignal use for
///   this activity (the `activity_id` of OneSignal's REST API). For a push-to-start your server
///   sends the same value in `event_attributes.activityId`.
/// - No `Date` property. ActivityKit decodes pushed JSON with its default strategy (a `Date` is
///   seconds since 2001), so times travel as `Double` Unix seconds and the view converts them.
/// - Field names are the JSON keys your server sends. Android's template of the same name reads
///   the same keys, so one payload drives both platforms.
protocol NXLiveTemplate: ActivityAttributes {
    /// The `template` value the page and the server use. Lower case, at most 32 characters.
    static var templateName: String { get }
    var activityId: String { get }
}

/// Unix seconds to a date, for template views. Nil and 0 mean "not set".
func nxLiveDate(_ seconds: Double?) -> Date? {
    guard let s = seconds, s > 0 else { return nil }
    return Date(timeIntervalSince1970: s)
}
