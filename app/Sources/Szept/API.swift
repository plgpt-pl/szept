import Foundation

/// Klient serwera-skrzynki. Żadnych identyfikatorów poza losowym account_id i tokenem.
final class SzeptAPI {
    var baseURL: URL
    var token: String?
    private let session: URLSession

    init(baseURL: URL, token: String?) {
        self.baseURL = baseURL
        self.token = token
        let cfg = URLSessionConfiguration.ephemeral          // bez ciasteczek i cache na dysku
        cfg.timeoutIntervalForRequest = 30
        cfg.waitsForConnectivity = false
        session = URLSession(configuration: cfg)
    }

    struct Registered: Decodable { var account_id: String; var token: String }
    struct Pending: Decodable { var id: Int; var content: String; var ts: Double }
    private struct PendingList: Decodable { var messages: [Pending] }
    private struct KeyCounts: Decodable { var prekeys: Int; var kyber_prekeys: Int }
    private struct Uploaded: Decodable { var id: String }

    private func request(_ method: String, _ path: String, json: Encodable? = nil, body: Data? = nil, auth: Bool) throws -> URLRequest {
        var r = URLRequest(url: baseURL.appendingPathComponent(path))
        r.httpMethod = method
        if auth {
            guard let token else { throw SzeptError.notRegistered }
            r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let json {
            r.httpBody = try JSONEncoder().encode(json)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if let body {
            r.httpBody = body
            r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        }
        return r
    }

    private func send(_ r: URLRequest) async throws -> Data {
        let (data, resp) = try await session.data(for: r)
        guard let http = resp as? HTTPURLResponse else { throw SzeptError.server("brak odpowiedzi") }
        guard (200..<300).contains(http.statusCode) else {
            throw SzeptError.server("\(http.statusCode) \(String(data: data, encoding: .utf8) ?? "")".trimmingCharacters(in: .whitespaces))
        }
        return data
    }

    private func call<T: Decodable>(_ method: String, _ path: String, json: Encodable? = nil, auth: Bool = true) async throws -> T {
        try JSONDecoder().decode(T.self, from: try await send(try request(method, path, json: json, auth: auth)))
    }

    func health() async throws -> Bool {
        struct H: Decodable { var ok: Bool }
        let h: H = try await call("GET", "health", auth: false)
        return h.ok
    }

    func register(_ p: RegistrationPayload) async throws -> Registered {
        try await call("POST", "v1/accounts", json: p, auth: false)
    }

    func uploadKeys(_ p: KeyUploadPayload) async throws -> (Int, Int) {
        let c: KeyCounts = try await call("PUT", "v1/keys", json: p)
        return (c.prekeys, c.kyber_prekeys)
    }

    func keyCounts() async throws -> (Int, Int) {
        let c: KeyCounts = try await call("GET", "v1/keys")
        return (c.prekeys, c.kyber_prekeys)
    }

    func bundle(for accountId: String) async throws -> BundleJSON {
        try await call("GET", "v1/keys/\(accountId)", auth: false)
    }

    /// Wysyłka bez uwierzytelnienia – serwer nie wie, kto jest nadawcą.
    func deliver(_ sealed: [Data], to accountId: String) async throws {
        struct Body: Encodable { struct M: Encodable { var content: String }; var messages: [M] }
        _ = try await send(try request("PUT", "v1/messages/\(accountId)",
                                       json: Body(messages: sealed.map { .init(content: $0.base64EncodedString()) }), auth: false))
    }

    func pending() async throws -> [Pending] {
        let l: PendingList = try await call("GET", "v1/messages")
        return l.messages
    }

    func ack(_ id: Int) async throws {
        _ = try await send(try request("DELETE", "v1/messages/\(id)", auth: true))
    }

    func uploadAttachment(_ data: Data) async throws -> String {
        let u: Uploaded = try JSONDecoder().decode(Uploaded.self, from: try await send(try request("POST", "v1/attachments", body: data, auth: true)))
        return u.id
    }

    func downloadAttachment(_ id: String) async throws -> Data {
        let d = try await send(try request("GET", "v1/attachments/\(id)", auth: false))
        Task { _ = try? await send(try request("DELETE", "v1/attachments/\(id)", auth: false)) }
        return d
    }

    func deleteAccount() async throws {
        _ = try await send(try request("DELETE", "v1/accounts", auth: true))
    }

    // MARK: WebSocket

    func webSocket() throws -> URLSessionWebSocketTask {
        guard let token else { throw SzeptError.notRegistered }
        var comps = URLComponents(url: baseURL.appendingPathComponent("v1/ws"), resolvingAgainstBaseURL: false)!
        comps.scheme = comps.scheme == "http" ? "ws" : "wss"
        var r = URLRequest(url: comps.url!)
        r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return session.webSocketTask(with: r)
    }
}
