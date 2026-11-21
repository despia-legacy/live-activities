import ActivityKit
import SwiftUI
import WidgetKit

// Template "timer": a countdown the system animates by itself, with no update per second. Edit
// freely: the fields are the JSON keys your page and your server send (Android's
// TimerTemplate.java reads the same keys).
//
// attributes: {"title": "Pizza oven"}
// state:      {"startedAt": 1763650000, "endsAt": 1763650900, "label": "Baking"}
// paused:     {"startedAt": 1763650000, "endsAt": 1763650900, "label": "Paused", "pausedRemaining": 312}

struct TimerAttributes: NXLiveTemplate {
    static let templateName = "timer"

    struct ContentState: Codable, Hashable {
        /// Unix seconds.
        var startedAt: Double
        /// Unix seconds. The countdown reaches zero here.
        var endsAt: Double
        var label: String
        /// Seconds left while paused. Omit while running.
        var pausedRemaining: Double?
    }

    var activityId: String
    var title: String
}

private extension TimerAttributes.ContentState {
    var range: ClosedRange<Date> {
        let start = Date(timeIntervalSince1970: startedAt)
        let end = Date(timeIntervalSince1970: max(endsAt, startedAt))
        return start...end
    }
    var paused: Bool { pausedRemaining != nil }
    var pausedText: String {
        let s = Int(max(0, pausedRemaining ?? 0))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

private struct TimerClock: View {
    let state: TimerAttributes.ContentState
    var body: some View {
        if state.paused {
            Text(state.pausedText)
        } else {
            Text(timerInterval: state.range, countsDown: true)
        }
    }
}

private struct TimerLockScreen: View {
    let context: ActivityViewContext<TimerAttributes>
    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 4) {
                Text(context.attributes.title).font(.headline).lineLimit(1)
                Label(context.state.label, systemImage: context.state.paused ? "pause.fill" : "timer")
                    .font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            TimerClock(state: context.state)
                .font(.system(.title, design: .rounded).weight(.semibold).monospacedDigit())
                .multilineTextAlignment(.trailing)
                .frame(maxWidth: 140, alignment: .trailing)
        }
        .padding(16)
    }
}

struct TimerLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TimerAttributes.self) { context in
            TimerLockScreen(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.title, systemImage: "timer").font(.headline).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TimerClock(state: context.state)
                        .font(.title2.monospacedDigit())
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 110, alignment: .trailing)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    if context.state.paused {
                        Text(context.state.label).font(.subheadline).foregroundStyle(.secondary)
                    } else {
                        ProgressView(timerInterval: context.state.range, countsDown: true) { EmptyView() } currentValueLabel: { EmptyView() }
                            .tint(Color.accentColor)
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.paused ? "pause.fill" : "timer").foregroundStyle(Color.accentColor)
            } compactTrailing: {
                TimerClock(state: context.state)
                    .font(.caption.monospacedDigit())
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 52)
            } minimal: {
                Image(systemName: "timer").foregroundStyle(Color.accentColor)
            }
            .keylineTint(Color.accentColor)
        }
    }
}
