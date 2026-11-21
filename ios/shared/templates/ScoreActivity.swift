import ActivityKit
import SwiftUI
import WidgetKit

// Template "score": a live sports score card. Edit freely: the fields are the JSON keys your page
// and your server send (Android's ScoreTemplate.java reads the same keys).
//
// attributes: {"league": "Premier League", "homeTeam": "Arsenal", "awayTeam": "Chelsea",
//              "homeShort": "ARS", "awayShort": "CHE"}
// state:      {"homeScore": 2, "awayScore": 1, "period": "2nd half", "clock": "67'", "final": false}

struct ScoreAttributes: NXLiveTemplate {
    static let templateName = "score"

    struct ContentState: Codable, Hashable {
        var homeScore: Int
        var awayScore: Int
        /// "1st half", "Q3", "Top 7th".
        var period: String
        /// Game clock as text ("67'", "04:12"). Omit when the sport has none.
        var clock: String?
        /// True once the game is over.
        var final: Bool?
    }

    var activityId: String
    var league: String
    var homeTeam: String
    var awayTeam: String
    /// Three-letter codes for the compact Dynamic Island. Omit to use the first three letters.
    var homeShort: String?
    var awayShort: String?
}

private extension ScoreAttributes {
    var home3: String { homeShort ?? String(homeTeam.prefix(3)).uppercased() }
    var away3: String { awayShort ?? String(awayTeam.prefix(3)).uppercased() }
}

private extension ScoreAttributes.ContentState {
    var isFinal: Bool { final ?? false }
    var status: String { isFinal ? "Final" : [period, clock ?? ""].filter { !$0.isEmpty }.joined(separator: " · ") }
}

private struct ScoreTeam: View {
    let name: String
    let score: Int
    let leading: Bool
    var body: some View {
        VStack(spacing: 2) {
            Text("\(score)")
                .font(.system(.largeTitle, design: .rounded).weight(.semibold).monospacedDigit())
                .contentTransition(.numericText())
            Text(name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: leading ? .leading : .trailing)
    }
}

private struct ScoreLockScreen: View {
    let context: ActivityViewContext<ScoreAttributes>
    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Text(context.attributes.league).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Spacer()
                Text(context.state.status)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(context.state.isFinal ? Color.secondary : Color.red)
            }
            HStack {
                ScoreTeam(name: context.attributes.homeTeam, score: context.state.homeScore, leading: true)
                Text("–").font(.title2).foregroundStyle(.secondary)
                ScoreTeam(name: context.attributes.awayTeam, score: context.state.awayScore, leading: false)
            }
        }
        .padding(16)
    }
}

struct ScoreLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: ScoreAttributes.self) { context in
            ScoreLockScreen(context: context)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    ScoreTeam(name: context.attributes.home3, score: context.state.homeScore, leading: true)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    ScoreTeam(name: context.attributes.away3, score: context.state.awayScore, leading: false)
                }
                DynamicIslandExpandedRegion(.center) {
                    Text(context.attributes.league).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.status).font(.subheadline.weight(.semibold))
                }
            } compactLeading: {
                Text("\(context.attributes.home3) \(context.state.homeScore)").font(.caption.monospacedDigit())
            } compactTrailing: {
                Text("\(context.state.awayScore) \(context.attributes.away3)").font(.caption.monospacedDigit())
            } minimal: {
                Text("\(context.state.homeScore)-\(context.state.awayScore)").font(.caption2.monospacedDigit())
            }
        }
    }
}
