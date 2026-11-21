import SwiftUI
import WidgetKit

/// The NXLiveActivity widget extension: it draws the Live Activities on the Lock Screen and in the
/// Dynamic Island. One line per template (ios/shared/templates/). The app's built-in
/// ActivityKitExtension draws only its own download activity, a different attributes type, so the
/// two extensions never draw each other's activities.
@main
struct NXLiveActivityBundle: WidgetBundle {
    var body: some Widget {
        DeliveryLiveActivity()
        ScoreLiveActivity()
        TimerLiveActivity()
    }
}
