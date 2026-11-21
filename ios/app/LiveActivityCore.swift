import Foundation

/// Every decision of the Live Activities extension that does not need ActivityKit, OneSignal or
/// UIKit. Foundation only, so the shared rules run it on a plain host. Android's LiveCore.java makes the same decisions from the same vectors.

enum LiveCoreError: Error, Equatable {
    /// The page or server sent something malformed. The message says what.
    case badValue(String)
    /// Static plus dynamic data would pass ActivityKit's 4 KB limit (Apple, "Displaying live data
    /// with Live Activities": "can't exceed a combined size of 4 KB").
    case tooLarge(Int)

    var code: String {
        switch self {
        case .badValue: return "bad_value"
        case .tooLarge: return "too_large"
        }
    }

    var message: String {
        switch self {
        case .badValue(let m): return m
        case .tooLarge(let n): return "attributes and state are \(n) bytes; the limit is \(LiveCore.maxPayloadBytes)"
        }
    }
}

enum LiveCore {
    static let maxPayloadBytes = 4096
    /// The bytes `"activityId":"",` adds when the id is injected into the attributes.
    static let injectedKeyBytes = 16

    /// Ids the page, the server and OneSignal share: 1 to 64 of A-Z a-z 0-9 . _ -
    static func validId(_ id: String) -> Bool {
        guard (1...64).contains(id.utf8.count) else { return false }
        return id.utf8.allSatisfy { c in
            (c >= 0x30 && c <= 0x39) || (c >= 0x41 && c <= 0x5A) || (c >= 0x61 && c <= 0x7A)
                || c == 0x2E || c == 0x5F || c == 0x2D
        }
    }

    /// Template names: 1 to 32 of a-z 0-9 _
    static func validTemplate(_ name: String) -> Bool {
        guard (1...32).contains(name.utf8.count) else { return false }
        return name.utf8.allSatisfy { c in (c >= 0x30 && c <= 0x39) || (c >= 0x61 && c <= 0x7A) || c == 0x5F }
    }

    static func object(_ json: String, _ field: String) throws -> [String: Any] {
        guard let data = json.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw LiveCoreError.badValue("\(field) is not a JSON object")
        }
        return obj
    }

    /// The size rule, measured on what the page or server sent (UTF-8 bytes), plus the injected id.
    static func size(attributes: String, state: String, injectedId: String?) -> Int {
        attributes.utf8.count + state.utf8.count + (injectedId.map { injectedKeyBytes + $0.utf8.count } ?? 0)
    }

    /// Start: the attributes with `activityId` set to `id`, and the state, both checked. An
    /// attributes object that names another activityId is refused, never overwritten.
    static func prepareStart(attributes: String, state: String, id: String) throws -> (attributes: Data, state: Data) {
        guard validId(id) else { throw LiveCoreError.badValue("activity_id must be 1 to 64 of A-Z a-z 0-9 . _ -") }
        var attrs = try object(attributes, "attributes")
        _ = try object(state, "state")
        var injected: String? = id
        if let given = attrs["activityId"] {
            guard let s = given as? String, s == id else {
                throw LiveCoreError.badValue("attributes.activityId differs from activity_id")
            }
            injected = nil
        }
        let n = size(attributes: attributes, state: state, injectedId: injected)
        guard n <= maxPayloadBytes else { throw LiveCoreError.tooLarge(n) }
        attrs["activityId"] = id
        let a = try JSONSerialization.data(withJSONObject: attrs, options: [.sortedKeys])
        return (a, Data(state.utf8))
    }

    /// Update and end: the new state, checked against the stored attributes' size.
    static func prepareState(_ state: String, attributesBytes: Int) throws -> Data {
        _ = try object(state, "state")
        let n = attributesBytes + state.utf8.count
        guard n <= maxPayloadBytes else { throw LiveCoreError.tooLarge(n) }
        return Data(state.utf8)
    }

    /// An update token is handed on only while the activity runs (active or stale). A token
    /// that arrives after the end is never entered, so OneSignal never targets a finished activity.
    static func acceptsUpdateToken(_ phase: LiveLedger.Phase?) -> Bool {
        phase?.rank == 1
    }

    static func hex(_ data: Data) -> String {
        data.map { String(format: "%02x", $0) }.joined()
    }
}

/// The page-visible life of each activity id, shared by both platforms' vectors.
///
/// L1 Phases only move forward: active and stale may alternate; ended and dismissed are final
///    (dismissed after ended is the one move between them). Anything else is ignored.
/// L2 Each change is reported once (`state`); a repeat of the current phase reports nothing.
/// L3 The push registration is released exactly once, on the first of ended or dismissed
///    (iOS: OneSignal exit; Android: the stored record).
/// L4 `dismissed` says who removed it: "user" when it was still running, "end" after an end.
///    An end the app asked for (`ending`) that removes it at once (iOS dismissal policy immediate:
///    the system reports dismissed with no ended phase) is reported as ended, then dismissed by "end".
/// L5 A new start (by the page or a push) of an id that ended or was dismissed resets it.
/// L6 Each start is a life of its id (iOS: the system activity's id; Android: its notification id).
///    Only the current life reports: a late end or dismissal of an earlier life of the same id is
///    ignored, so it never ends, releases or dismisses the activity running now.
final class LiveLedger {
    enum Phase: String {
        case active, stale, ended, dismissed

        var rank: Int {
            switch self {
            case .active, .stale: return 1
            case .ended: return 2
            case .dismissed: return 3
            }
        }
    }

    enum Effect: Equatable, CustomStringConvertible {
        case state(String, Phase)
        case dismissed(String, by: String)
        case release(String)

        var description: String {
            switch self {
            case .state(let id, let p): return "state:\(id):\(p.rawValue)"
            case .dismissed(let id, let by): return "dismissed:\(id):\(by)"
            case .release(let id): return "release:\(id)"
            }
        }
    }

    private(set) var phases: [String: Phase] = [:]
    private(set) var lives: [String: String] = [:]
    /// The life whose end the app asked for, per id (who dismissed it).
    private var endings: [String: String] = [:]

    func phase(_ id: String) -> Phase? { phases[id] }

    /// The id's current life, nil before its first one.
    func life(_ id: String) -> String? { lives[id] }

    /// Whether `life` is the id's current life. An id with no life yet takes the first it sees.
    func isCurrent(_ id: String, _ life: String) -> Bool {
        (lives[id] ?? life) == life
    }

    /// A new start begins a new life of its id; a phase that ended or was dismissed is forgotten.
    func begin(_ id: String, life: String) {
        lives[id] = life
        endings[id] = nil
        if let p = phases[id], p.rank > 1 {
            phases.removeValue(forKey: id)
        }
    }

    /// Who dismissed it: the app asked to end the current life of `id`; nil `life` withdraws it (the end failed).
    func ending(_ id: String, life: String?) {
        guard let life else { endings[id] = nil; return }
        if isCurrent(id, life) { endings[id] = life }
    }

    func observe(_ id: String, _ next: Phase, life: String) -> [Effect] {
        guard isCurrent(id, life) else { return [] }
        lives[id] = life
        let current = phases[id]
        if next == .dismissed, endings[id] == life, current == nil || current!.rank == 1 {
            return observe(id, .ended, life: life) + observe(id, .dismissed, life: life)
        }
        if let c = current {
            if c == next { return [] }
            if next.rank < c.rank { return [] }
            if c.rank == next.rank && c.rank > 1 { return [] }
        }
        phases[id] = next
        var out: [Effect] = [.state(id, next)]
        let wasRunning = current == nil || current!.rank == 1
        if next == .dismissed {
            out.append(.dismissed(id, by: current == .ended ? "end" : "user"))
        }
        if next.rank >= 2 && wasRunning {
            out.append(.release(id))
        }
        return out
    }
}

/// Push tokens are handed on (OneSignal, the page) only when they change. Keys are
/// "update:<activityId>" and "start:<template>".
final class LiveTokenBook {
    private(set) var tokens: [String: String] = [:]

    func record(_ key: String, _ token: String) -> Bool {
        if tokens[key] == token { return false }
        tokens[key] = token
        return true
    }

    func remove(_ key: String) {
        tokens.removeValue(forKey: key)
    }
}
