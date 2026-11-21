import ActivityKit
import Foundation
import UIKit

/// Live Activities (ActivityKit) driven by the page and by OneSignal pushes.
///
/// - The page starts, updates, ends and lists activities of any registered template
///   (LiveTemplateRegistry.swift). Attributes and state are the template's own JSON.
/// - Every activity is requested with `pushType: .token` (nil in a build without push, PUSH_DISABLED,
///   whose app has no aps-environment; local start, update and end still work). Its update token, and each template's
///   push-to-start token (iOS 17.2+), go to OneSignal (OneSignalLiveLink.swift) and to the page
///   (`token` event, `list`), so a server can update, end or start activities while the app is
///   closed.
/// - Activities started by a push are adopted like the app's own: tracked, tokens handed on,
///   state reported.
/// - The page gets the activity id it, the server and OneSignal share, never the system's id.
///
/// - Each system activity is one life of its activity id. Only the current life reports, so a
///   late end or dismissal of an earlier activity with the same id never touches the one running now.
///
/// Main actor, as the Despia app's NativeExtension protocol requires. After every await the route
/// checks `call.isActive` before it starts anything new.
#if PUSH_DISABLED
private let livePushType: PushType? = nil
#else
private let livePushType: PushType? = .token
#endif

@MainActor
final class LiveActivityExtension: NativeExtension {
    private var ctx: NativeExtContext!
    private var drivers: [LiveTemplateDriver] = []
    let ledger = LiveLedger()
    let tokens = LiveTokenBook()
    /// "app", "push" or "restored", per activity id.
    var origins: [String: String] = [:]

    required init() {}

    func didLaunch(_ context: NativeExtContext) {
        ctx = context
        var seen = Set<String>()
        for kind in LiveTemplateRegistry.all where seen.insert(kind.name).inserted {
            drivers.append(kind.make(self))
        }
        for d in drivers { d.observe() }
    }

    func handle(_ call: NativeExtCall) async -> NativeExtResult {
        switch call.route {
        case "start": return start(call)
        case "update": return await update(call)
        case "end": return await end(call)
        case "list": return list()
        case "permission": return .ok(authorization())
        case "settings": return await settings(call)
        default: return .error(code: "not_registered", message: call.route)
        }
    }

    // MARK: routes

    private func start(_ call: NativeExtCall) -> NativeExtResult {
        let name = call.params["template"] as? String ?? ""
        guard LiveCore.validTemplate(name), let driver = drivers.first(where: { $0.name == name }) else {
            return .error(code: "unknown_template", message: "no template \(name.isEmpty ? "given" : name); see LiveTemplateRegistry.swift")
        }
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            return .error(code: "activities_disabled", message: "Live Activities are turned off for this app in Settings")
        }
        let id = (call.params["activity_id"] as? String) ?? "la-" + UUID().uuidString.lowercased()
        if drivers.contains(where: { $0.knows(id) }) {
            return .error(code: "exists", message: "an activity with this id is running")
        }
        let prepared: (attributes: Data, state: Data)
        do {
            prepared = try LiveCore.prepareStart(attributes: call.params["attributes"] as? String ?? "",
                                                 state: call.params["state"] as? String ?? "", id: id)
        } catch let e as LiveCoreError {
            return .error(code: e.code, message: e.message)
        } catch {
            return .error(code: "bad_value", message: "attributes or state could not be read")
        }
        let stale = (call.params["stale_minutes"] as? Int).map { Date().addingTimeInterval(TimeInterval($0 * 60)) }
        let relevance = Double(call.params["relevance"] as? Int ?? 0)
        do {
            try driver.start(id: id, attributes: prepared.attributes, state: prepared.state, staleDate: stale, relevance: relevance)
        } catch let e as DecodingError {
            return .error(code: "bad_value", message: Self.describe(e, template: name))
        } catch let e as ActivityAuthorizationError {
            let (code, message) = Self.describe(e)
            return .error(code: code, message: message)
        } catch {
            return .error(code: "activity_failed", message: "the Live Activity could not start")
        }
        return .ok(["activityId": id, "template": name, "state": "active"])
    }

    private func update(_ call: NativeExtCall) async -> NativeExtResult {
        let id = call.params["activity_id"] as? String ?? ""
        guard let driver = drivers.first(where: { $0.knows(id) }), let attrBytes = driver.attributesBytes(id) else {
            return .error(code: "unknown_activity", message: "no running activity with this id")
        }
        let state: Data
        do {
            state = try LiveCore.prepareState(call.params["state"] as? String ?? "", attributesBytes: attrBytes)
        } catch let e as LiveCoreError {
            return .error(code: e.code, message: e.message)
        } catch {
            return .error(code: "bad_value", message: "state could not be read")
        }
        let stale = (call.params["stale_minutes"] as? Int).map { Date().addingTimeInterval(TimeInterval($0 * 60)) }
        let relevance = (call.params["relevance"] as? Int).map(Double.init)
        let title = call.params["alert_title"] as? String
        let body = call.params["alert_body"] as? String
        let alert = (title != nil || body != nil) ? (title ?? "", body ?? "") : nil
        do {
            guard try await driver.update(id: id, state: state, staleDate: stale, relevance: relevance, alert: alert) else {
                return .error(code: "unknown_activity", message: "the activity ended")
            }
        } catch let e as DecodingError {
            return .error(code: "bad_value", message: Self.describe(e, template: driver.name))
        } catch {
            return .error(code: "activity_failed", message: "the update was not applied")
        }
        guard call.isActive else { return .error(code: "timeout", message: "update timed out") }
        return .ok(["activityId": id, "template": driver.name, "state": ledger.phase(id)?.rawValue ?? "active"])
    }

    private func end(_ call: NativeExtCall) async -> NativeExtResult {
        let id = call.params["activity_id"] as? String ?? ""
        guard let driver = drivers.first(where: { $0.knows(id) }), let attrBytes = driver.attributesBytes(id) else {
            return .error(code: "unknown_activity", message: "no running activity with this id")
        }
        let policy: ActivityUIDismissalPolicy
        switch call.params["dismiss"] as? String ?? "default" {
        case "immediate": policy = .immediate
        case "after":
            guard let s = call.params["dismiss_minutes"] as? Int else {
                return .error(code: "bad_value", message: "dismiss=after needs dismiss_minutes")
            }
            policy = .after(Date().addingTimeInterval(TimeInterval(s * 60)))
        default: policy = .default
        }
        var state: Data?
        if let s = call.params["state"] as? String {
            do {
                state = try LiveCore.prepareState(s, attributesBytes: attrBytes)
            } catch let e as LiveCoreError {
                return .error(code: e.code, message: e.message)
            } catch {
                return .error(code: "bad_value", message: "state could not be read")
            }
        }
        // Who dismissed it: an immediate dismissal reaches the ledger as dismissed with no ended phase; the mark
        // reports it as this end, not as the person's swipe.
        ledger.ending(id, life: ledger.life(id))
        do {
            guard try await driver.end(id: id, state: state, policy: policy) else {
                ledger.ending(id, life: nil)
                return .error(code: "unknown_activity", message: "the activity already ended")
            }
        } catch let e as DecodingError {
            ledger.ending(id, life: nil)
            return .error(code: "bad_value", message: Self.describe(e, template: driver.name))
        } catch {
            ledger.ending(id, life: nil)
            return .error(code: "activity_failed", message: "the activity could not end")
        }
        guard call.isActive else { return .error(code: "timeout", message: "end timed out") }
        return .ok(["activityId": id, "template": driver.name, "state": "ended"])
    }

    private func list() -> NativeExtResult {
        var data = authorization()
        data["templates"] = drivers.map { $0.name }
        data["activities"] = drivers.flatMap { $0.rows() }
        var starts: [String: Any] = [:]
        for d in drivers { starts[d.name] = tokens.tokens["start:\(d.name)"] ?? NSNull() }
        data["pushToStart"] = starts
        return .ok(data)
    }

    private func authorization() -> [String: Any] {
        let info = ActivityAuthorizationInfo()
        return ["enabled": info.areActivitiesEnabled, "frequentPushes": info.frequentPushesEnabled,
                "onesignal": OneSignalLiveLink.linked ? "linked" : "not_linked", "platform": "ios"]
    }

    /// Live Activities have no permission prompt on iOS: a person turns them off and on in the app's
    /// page of the Settings app, which this opens.
    private func settings(_ call: NativeExtCall) async -> NativeExtResult {
        guard let url = URL(string: UIApplication.openSettingsURLString) else {
            return .error(code: "unavailable", message: "no settings page")
        }
        let opened = await UIApplication.shared.open(url)
        guard call.isActive else { return .error(code: "timeout", message: "settings timed out") }
        return .ok(["opened": opened])
    }

    // MARK: what the drivers report

    func phase(_ id: String, life: String, template: String, _ phase: LiveLedger.Phase?) {
        guard let phase else { return }
        for effect in ledger.observe(id, phase, life: life) {
            switch effect {
            case .state(let id, let p):
                ctx.emit("state", ["activityId": id, "template": template, "state": p.rawValue, "origin": origins[id] ?? "app"])
            case .dismissed(let id, let by):
                ctx.emit("dismissed", ["activityId": id, "template": template, "by": by])
            case .release(let id):
                OneSignalLiveLink.exit(id)
                tokens.remove("update:\(id)")
            }
        }
    }

    func updateToken(_ id: String, life: String, template: String, token: String) {
        guard ledger.isCurrent(id, life), LiveCore.acceptsUpdateToken(ledger.phase(id)),
              tokens.record("update:\(id)", token) else { return }
        OneSignalLiveLink.enter(id, token: token)
        ctx.emit("token", ["kind": "update", "activityId": id, "template": template, "token": token])
    }

    func startToken(template: String, token: String, register: (String) -> Void) {
        guard tokens.record("start:\(template)", token) else { return }
        register(token)
        ctx.emit("token", ["kind": "push_to_start", "template": template, "token": token])
    }

    // MARK: messages

    static func describe(_ e: DecodingError, template: String) -> String {
        func path(_ c: DecodingError.Context) -> String {
            c.codingPath.map { $0.stringValue }.joined(separator: ".")
        }
        switch e {
        case .keyNotFound(let key, _): return "\(template): missing field \(key.stringValue)"
        case .typeMismatch(_, let c), .valueNotFound(_, let c): return "\(template): wrong type at \(path(c))"
        case .dataCorrupted(let c): return "\(template): unreadable value at \(path(c))"
        @unknown default: return "\(template): fields do not match the template"
        }
    }

    static func describe(_ e: ActivityAuthorizationError) -> (String, String) {
        switch e {
        case .denied: return ("activities_disabled", "Live Activities are turned off for this app in Settings")
        case .attributesTooLarge: return ("too_large", "attributes and state pass the 4 KB limit")
        case .globalMaximumExceeded, .targetMaximumExceeded: return ("too_many", "the device runs as many Live Activities as it allows")
        case .visibility: return ("not_foreground", "a Live Activity starts only while the app is in the foreground")
        case .unsupported, .unsupportedTarget: return ("unsupported", "this device does not support Live Activities")
        default: return ("activity_failed", "the Live Activity could not start")
        }
    }
}

// MARK: - templates, type-erased

/// One registered template. `make` builds its driver once, at launch.
struct LiveTemplateKind {
    let name: String
    let make: @MainActor (LiveActivityExtension) -> LiveTemplateDriver

    static func of<T: NXLiveTemplate>(_ type: T.Type) -> LiveTemplateKind {
        LiveTemplateKind(name: T.templateName, make: { owner in TemplateDriver<T>(owner: owner) })
    }
}

@MainActor
protocol LiveTemplateDriver: AnyObject {
    var name: String { get }
    func observe()
    func knows(_ activityId: String) -> Bool
    func attributesBytes(_ activityId: String) -> Int?
    func start(id: String, attributes: Data, state: Data, staleDate: Date?, relevance: Double) throws
    func update(id: String, state: Data, staleDate: Date?, relevance: Double?, alert: (String, String)?) async throws -> Bool
    func end(id: String, state: Data?, policy: ActivityUIDismissalPolicy) async throws -> Bool
    func rows() -> [[String: Any]]
}

/// Everything that needs the template's concrete type: ActivityKit requests, the system's
/// activity, token and state sequences, and JSON in the system's default coding (the same coding
/// ActivityKit applies to pushed payloads).
@MainActor
final class TemplateDriver<T: NXLiveTemplate>: LiveTemplateDriver {
    private unowned let owner: LiveActivityExtension
    /// System activity id -> activity, for every activity this process has seen.
    private var tracked: [String: Activity<T>] = [:]

    init(owner: LiveActivityExtension) {
        self.owner = owner
    }

    var name: String { T.templateName }

    func observe() {
        for a in Activity<T>.activities { track(a, origin: "restored") }
        Task { [weak self] in
            for await a in Activity<T>.activityUpdates { self?.track(a, origin: "push") }
        }
        if #available(iOS 17.2, *) {
            Task { [weak self] in
                for await data in Activity<T>.pushToStartTokenUpdates {
                    self?.owner.startToken(template: T.templateName, token: LiveCore.hex(data)) { token in
                        OneSignalLiveLink.setPushToStart(T.self, token: token)
                    }
                }
            }
        }
    }

    /// The current life of the id, while it runs.
    private func running(_ id: String) -> Activity<T>? {
        guard let life = owner.ledger.life(id), let a = tracked[life] else { return nil }
        return a.activityState == .active || a.activityState == .stale ? a : nil
    }

    func knows(_ activityId: String) -> Bool { running(activityId) != nil }

    func attributesBytes(_ activityId: String) -> Int? {
        guard let a = running(activityId) else { return nil }
        return (try? JSONEncoder().encode(a.attributes))?.count ?? 0
    }

    func start(id: String, attributes: Data, state: Data, staleDate: Date?, relevance: Double) throws {
        let attrs = try JSONDecoder().decode(T.self, from: attributes)
        let content = try JSONDecoder().decode(T.ContentState.self, from: state)
        let a = try Activity<T>.request(attributes: attrs,
                                        content: ActivityContent(state: content, staleDate: staleDate, relevanceScore: relevance),
                                        pushType: livePushType)
        track(a, origin: "app")
    }

    func update(id: String, state: Data, staleDate: Date?, relevance: Double?, alert: (String, String)?) async throws -> Bool {
        guard let a = running(id) else { return false }
        let content = ActivityContent(state: try JSONDecoder().decode(T.ContentState.self, from: state),
                                      staleDate: staleDate ?? a.content.staleDate,
                                      relevanceScore: relevance ?? a.content.relevanceScore)
        if let (title, body) = alert {
            await a.update(content, alertConfiguration: AlertConfiguration(title: LocalizedStringResource(stringLiteral: title),
                                                                           body: LocalizedStringResource(stringLiteral: body),
                                                                           sound: .default))
        } else {
            await a.update(content)
        }
        return true
    }

    func end(id: String, state: Data?, policy: ActivityUIDismissalPolicy) async throws -> Bool {
        guard let a = running(id) else { return false }
        let content = try state.map { ActivityContent(state: try JSONDecoder().decode(T.ContentState.self, from: $0), staleDate: nil) }
        await a.end(content, dismissalPolicy: policy)
        return true
    }

    func rows() -> [[String: Any]] {
        tracked.values.compactMap { a in
            let id = a.attributes.activityId
            guard owner.ledger.life(id) == a.id, let phase = owner.ledger.phase(id), phase.rank == 1 else { return nil }
            return ["activityId": id, "template": name, "state": phase.rawValue, "origin": owner.origins[id] ?? "app",
                    "pushToken": owner.tokens.tokens["update:\(id)"] ?? NSNull(),
                    "attributes": Self.json(a.attributes), "content": Self.json(a.content.state)]
        }
    }

    /// Tracks a system activity once: its state and update-token sequences report to the owner,
    /// tagged with its life (the system id), so only the current life of its activity id counts.
    private func track(_ a: Activity<T>, origin: String) {
        guard tracked[a.id] == nil else { return }
        tracked[a.id] = a
        let id = a.attributes.activityId
        let life = a.id
        // A running system activity is a new life of its id, also for an id that ended
        // before. One that already ended (restored at launch) takes the id only if nothing else has.
        if Self.phase(a.activityState)?.rank == 1 || owner.ledger.life(id) == nil {
            if let p = owner.ledger.phase(id), p.rank > 1 { owner.origins[id] = nil }
            owner.ledger.begin(id, life: life)
        }
        if owner.ledger.isCurrent(id, life), owner.origins[id] == nil { owner.origins[id] = origin }
        owner.phase(id, life: life, template: name, Self.phase(a.activityState))
        Task { [weak self] in
            for await data in a.pushTokenUpdates {
                self?.owner.updateToken(id, life: life, template: T.templateName, token: LiveCore.hex(data))
            }
        }
        Task { [weak self] in
            for await s in a.activityStateUpdates {
                self?.owner.phase(id, life: life, template: T.templateName, Self.phase(s))
            }
        }
    }

    /// Nil for a state this code does not know (a newer iOS): it is not reported.
    static func phase(_ s: ActivityState) -> LiveLedger.Phase? {
        switch s {
        case .active: return .active
        case .stale: return .stale
        case .ended: return .ended
        case .dismissed: return .dismissed
        @unknown default: return nil
        }
    }

    static func json<V: Encodable>(_ value: V) -> Any {
        guard let d = try? JSONEncoder().encode(value), let o = try? JSONSerialization.jsonObject(with: d) else { return NSNull() }
        return o
    }
}
