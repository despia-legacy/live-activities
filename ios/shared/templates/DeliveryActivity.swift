import ActivityKit
import SwiftUI
import WidgetKit

// Template "delivery": order or delivery tracking with step progress and an arrival time.
// Edit freely: the fields are the JSON keys your page and your server send (Android's
// DeliveryTemplate.java reads the same keys).
//
// attributes: {"orderNumber": "A-1042", "merchant": "Corner Bakery"}
// state:      {"status": "On the way", "step": 2, "stepCount": 4, "etaEpoch": 1763650800, "courier": "Sam"}

struct DeliveryAttributes: NXLiveTemplate {
    static let templateName = "delivery"

    struct ContentState: Codable, Hashable {
        var status: String
        /// Completed steps, 0...stepCount.
        var step: Int
        var stepCount: Int
        /// Expected arrival, Unix seconds. Omit or 0 when unknown.
        var etaEpoch: Double?
        var courier: String?
    }

    var activityId: String
    var orderNumber: String
    var merchant: String
}

private extension DeliveryAttributes.ContentState {
    var fraction: Double { stepCount > 0 ? min(1, max(0, Double(step) / Double(stepCount))) : 0 }
    var done: Bool { stepCount > 0 && step >= stepCount }
    var symbol: String { done ? "checkmark.circle.fill" : "shippingbox.fill" }
}

private struct DeliveryETA: View {
    let state: DeliveryAttributes.ContentState
    var body: some View {
        if state.done {
            Text("Delivered")
        } else if let eta = nxLiveDate(state.etaEpoch) {
            Text(eta, style: .time)
        } else {
            Text("\(state.step)/\(state.stepCount)")
        }
    }
}

private struct DeliverySteps: View {
    let state: DeliveryAttributes.ContentState
    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<max(1, min(state.stepCount, 8)), id: \.self) { i in
                Capsule()
                    .fill(i < state.step ? Color.accentColor : Color.secondary.opacity(0.3))
                    .frame(height: 6)
            }
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Step \(state.step) of \(state.stepCount)"))
    }
}

private struct DeliveryLockScreen: View {
    let context: ActivityViewContext<DeliveryAttributes>
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Label(context.attributes.merchant, systemImage: context.state.symbol)
                    .font(.headline)
                    .lineLimit(1)
                Spacer()
                DeliveryETA(state: context.state)
                    .font(.headline.monospacedDigit())
            }
            DeliverySteps(state: context.state)
            HStack {
                Text(context.state.status).font(.subheadline).lineLimit(1)
                Spacer()
                if let courier = context.state.courier, !courier.isEmpty {
                    Text(courier).font(.subheadline).foregroundStyle(.secondary).lineLimit(1)
                } else {
                    Text("#\(context.attributes.orderNumber)").font(.subheadline).foregroundStyle(.secondary)
                }
            }
        }
        .padding(16)
    }
}

struct DeliveryLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DeliveryAttributes.self) { context in
            DeliveryLockScreen(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(context.attributes.merchant, systemImage: context.state.symbol)
                        .font(.headline)
                        .lineLimit(1)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    DeliveryETA(state: context.state)
                        .font(.headline.monospacedDigit())
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        DeliverySteps(state: context.state)
                        Text(context.state.status).font(.subheadline).lineLimit(1)
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.symbol).foregroundStyle(Color.accentColor)
            } compactTrailing: {
                DeliveryETA(state: context.state)
                    .font(.caption.monospacedDigit())
                    .frame(maxWidth: 56)
            } minimal: {
                ProgressView(value: context.state.fraction) {
                    Image(systemName: context.state.symbol)
                }
                .progressViewStyle(.circular)
                .tint(Color.accentColor)
            }
            .keylineTint(Color.accentColor)
        }
    }
}
