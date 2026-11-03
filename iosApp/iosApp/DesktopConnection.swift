import SwiftUI
import CryptoKit
import Security

private final class DesktopTrust: NSObject, URLSessionDelegate, URLSessionTaskDelegate {
    let fingerprint: String

    init(fingerprint: String) { self.fingerprint = fingerprint }

    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let certificates = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let certificate = certificates.first else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        let data = SecCertificateCopyData(certificate) as Data
        let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        guard actual == fingerprint else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        SecTrustSetPolicies(trust, SecPolicyCreateBasicX509())
        SecTrustSetAnchorCertificates(trust, [certificate] as CFArray)
        SecTrustSetAnchorCertificatesOnly(trust, true)
        guard SecTrustEvaluateWithError(trust, nil) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@MainActor
final class DesktopConnection: ObservableObject {
    @Published var status = "Desktop not paired"
    @Published var paired = false
    private var baseURL: URL?
    private var key = ""
    private var fingerprint = ""

    func pair(url: String, key: String, fingerprint: String) async throws {
        guard let components = URLComponents(string: url),
              components.scheme == "https", components.host != nil,
              let port = components.port, (1...65535).contains(port),
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.path.isEmpty || components.path == "/",
              key.range(of: "^[a-f0-9]{48}$", options: .regularExpression) != nil,
              fingerprint.range(of: "^[a-f0-9]{64}$", options: .regularExpression) != nil,
              let base = components.url else {
            throw NSError(domain: "TesterDesktop", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "Copy the HTTPS URL, key and fingerprint from Desktop."])
        }
        let result = try await check(base: base, key: key, fingerprint: fingerprint)
        self.baseURL = base
        self.key = key
        self.fingerprint = fingerprint
        paired = true
        status = result
    }

    func refresh() async {
        guard let baseURL else { return }
        do {
            status = try await check(base: baseURL, key: key, fingerprint: fingerprint)
        } catch {
            status = "Desktop unavailable: \(error.localizedDescription)"
        }
    }

    func forget() {
        baseURL = nil
        key = ""
        fingerprint = ""
        paired = false
        status = "Desktop not paired"
    }

    private func check(base: URL, key: String, fingerprint: String) async throws -> String {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 10
        let session = URLSession(configuration: config, delegate: DesktopTrust(fingerprint: fingerprint), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        var request = URLRequest(url: base.appendingPathComponent("status"))
        request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse, http.statusCode == 200, data.count <= 4096 else {
            throw NSError(domain: "TesterDesktop", code: 2,
                          userInfo: [NSLocalizedDescriptionKey: "Desktop rejected the status check. Check pairing and receiver status."])
        }
        return String(decoding: data, as: UTF8.self)
    }
}

struct DesktopConnectionPanel: View {
    @ObservedObject var connection: DesktopConnection
    @State private var url = "https://localhost:8766"
    @State private var key = ""
    @State private var fingerprint = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Tester Desktop") {
                    Text(connection.status)
                    Text("Detection only on iOS. Flow evidence sending is available on Android.")
                    Text("Pairing stays in memory for this app session. Use the Mac's Wi-Fi URL on a physical iPhone; localhost works only in the simulator.")
                }
                Section("Pair with the enabled Desktop receiver") {
                    TextField("HTTPS URL", text: $url).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("Private pairing key", text: $key)
                    TextField("SHA-256 fingerprint", text: $fingerprint).textInputAutocapitalization(.never).autocorrectionDisabled()
                    Text("Compare the fingerprint with Desktop before pairing.")
                    if let error { Text(error).foregroundStyle(.red) }
                    Button(busy ? "Pairing..." : "Pair") {
                        busy = true
                        Task {
                            do {
                                try await connection.pair(url: url.trimmingCharacters(in: .whitespacesAndNewlines),
                                    key: key.trimmingCharacters(in: .whitespacesAndNewlines),
                                    fingerprint: fingerprint.trimmingCharacters(in: .whitespacesAndNewlines).lowercased())
                                key = ""
                                error = nil
                            } catch { self.error = error.localizedDescription }
                            busy = false
                        }
                    }.disabled(busy)
                    if connection.paired {
                        Button("Forget Desktop") { connection.forget() }.disabled(busy)
                    }
                }
            }.navigationTitle("Desktop connection")
        }
    }
}
