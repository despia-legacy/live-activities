/// The templates the app can start, track and hand to OneSignal. One line per template
/// (ios/shared/templates/). A template missing here still draws if the widget lists it, but the app
/// never sees its activities, tokens or push-to-start.
enum LiveTemplateRegistry {
    static let all: [LiveTemplateKind] = [
        .of(DeliveryAttributes.self),
        .of(ScoreAttributes.self),
        .of(TimerAttributes.self),
    ]
}
