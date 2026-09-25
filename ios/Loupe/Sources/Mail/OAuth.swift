import AuthenticationServices
import CryptoKit
import Foundation
import UIKit

/// OAuth for Gmail (the Gmail REST API, read-only scope) and Outlook mail (IMAP with SASL XOAUTH2), by
/// the installed-app flow with PKCE (RFC 7636, S256) and a `state` check, through
/// `ASWebAuthenticationSession`. **Gated:** it needs an OAuth client ID. The IDs come from Info.plist
/// keys `LoupeGoogleOAuthClientID` (build setting `GOOGLE_IOS_CLIENT_ID`, its reversed form registered
/// as a URL scheme from `GOOGLE_IOS_URL_SCHEME`) / `LoupeMicrosoftOAuthClientID`; while one is empty
/// the Mail screen says so and offers app-password IMAP instead. No client secret is
/// used or stored (public client).
enum OAuthProvider: String, CaseIterable, Identifiable {
    case google, microsoft

    static let gmailReadonly = "https://www.googleapis.com/auth/gmail.readonly"

    var id: String { rawValue }
    var name: String { self == .google ? "Google" : "Microsoft" }
    var mailName: String { self == .google ? "Gmail" : "Outlook" }
    var imapHost: String { self == .google ? "imap.gmail.com" : "outlook.office365.com" }

    var authorizeURL: URL {
        URL(string: self == .google ? "https://accounts.google.com/o/oauth2/v2/auth"
            : "https://login.microsoftonline.com/common/oauth2/v2.0/authorize")!
    }

    var tokenURL: URL {
        URL(string: self == .google ? "https://oauth2.googleapis.com/token"
            : "https://login.microsoftonline.com/common/oauth2/v2.0/token")!
    }

    /// Google: the Gmail API's read-only scope and nothing else (least privilege; the address comes
    /// from users.getProfile). Microsoft: IMAP read access plus a refresh token.
    var scopes: [String] {
        self == .google ? [Self.gmailReadonly]
            : ["https://outlook.office.com/IMAP.AccessAsUser.All", "offline_access", "email"]
    }

    /// The redirect scheme an iOS client of each provider is registered with.
    func redirectURI(clientId: String) -> String {
        switch self {
        case .google:
            // Google's iOS clients redirect to the reversed client ID.
            let reversed = clientId.split(separator: ".").reversed().joined(separator: ".")
            return "\(reversed):/oauth2redirect"
        case .microsoft:
            return "msauth.com.loupe-ai.ios://auth"
        }
    }

    func callbackScheme(clientId: String) -> String {
        String(redirectURI(clientId: clientId).prefix { $0 != ":" })
    }
}

/// The client IDs, read from Info.plist. Empty by default: owner-blocked (see docs/BUILD.md).
struct OAuthConfig: Equatable {
    var googleClientId: String
    var microsoftClientId: String

    static let infoKeys: [OAuthProvider: String] = [.google: "LoupeGoogleOAuthClientID", .microsoft: "LoupeMicrosoftOAuthClientID"]

    static func fromBundle(_ bundle: Bundle = .main) -> OAuthConfig {
        func read(_ p: OAuthProvider) -> String {
            ((bundle.object(forInfoDictionaryKey: infoKeys[p]!) as? String) ?? "").trimmingCharacters(in: .whitespaces)
        }
        return OAuthConfig(googleClientId: read(.google), microsoftClientId: read(.microsoft))
    }

    func clientId(_ p: OAuthProvider) -> String { p == .google ? googleClientId : microsoftClientId }

    enum Availability: Equatable {
        case ready(clientId: String)
        case needsClientId(String)
    }

    func availability(_ p: OAuthProvider) -> Availability {
        let id = clientId(p)
        if id.isEmpty {
            return .needsClientId("Needs a \(p.name) OAuth client ID. Signing in with \(p.name) is built but switched off until one is registered for Loupe. Until then, use \(p.mailName) with an app password.")
        }
        return .ready(clientId: id)
    }
}

/// PKCE (RFC 7636): a random verifier and its S256 challenge.
struct PKCE: Equatable {
    let verifier: String
    let challenge: String

    init(verifier: String) {
        self.verifier = verifier
        challenge = Self.base64url(Data(SHA256.hash(data: Data(verifier.utf8))))
    }

    static func random() -> PKCE {
        var bytes = [UInt8](repeating: 0, count: 32)
        _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        return PKCE(verifier: base64url(Data(bytes)))
    }

    static func base64url(_ d: Data) -> String {
        d.base64EncodedString().replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

/// Tokens as the provider returns them.
struct OAuthTokens: Codable, Equatable {
    let accessToken: String
    let refreshToken: String?
    let expiresIn: Int?

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token", refreshToken = "refresh_token", expiresIn = "expires_in"
    }
}

/// The request builders are pure (and tested); `signIn` and `exchange` do the I/O.
struct OAuthFlow {
    let provider: OAuthProvider
    let clientId: String

    func authorizationURL(pkce: PKCE, state: String, loginHint: String?) -> URL {
        var c = URLComponents(url: provider.authorizeURL, resolvingAgainstBaseURL: false)!
        var q = [URLQueryItem(name: "client_id", value: clientId),
                 URLQueryItem(name: "redirect_uri", value: provider.redirectURI(clientId: clientId)),
                 URLQueryItem(name: "response_type", value: "code"),
                 URLQueryItem(name: "scope", value: provider.scopes.joined(separator: " ")),
                 URLQueryItem(name: "state", value: state),
                 URLQueryItem(name: "code_challenge", value: pkce.challenge),
                 URLQueryItem(name: "code_challenge_method", value: "S256")]
        if provider == .google { q.append(URLQueryItem(name: "access_type", value: "offline")) }
        if let loginHint, !loginHint.isEmpty { q.append(URLQueryItem(name: "login_hint", value: loginHint)) }
        c.queryItems = q
        return c.url!
    }

    /// The authorization code from the redirect, after checking `state`.
    static func code(from callback: URL, expectedState: String) throws -> String {
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let error = items.first(where: { $0.name == "error" })?.value {
            throw PhoneSourceError("Sign-in was not completed: \(error).")
        }
        guard items.first(where: { $0.name == "state" })?.value == expectedState else {
            throw PhoneSourceError("Sign-in was refused: the reply did not match the request (state check).")
        }
        guard let code = items.first(where: { $0.name == "code" })?.value, !code.isEmpty else {
            throw PhoneSourceError("Sign-in returned no authorization code.")
        }
        return code
    }

    func tokenRequest(code: String, pkce: PKCE) -> URLRequest {
        form(["grant_type": "authorization_code", "code": code, "client_id": clientId,
              "redirect_uri": provider.redirectURI(clientId: clientId), "code_verifier": pkce.verifier])
    }

    func refreshRequest(refreshToken: String) -> URLRequest {
        form(["grant_type": "refresh_token", "refresh_token": refreshToken, "client_id": clientId])
    }

    private func form(_ fields: [String: String]) -> URLRequest {
        var r = URLRequest(url: provider.tokenURL)
        r.httpMethod = "POST"
        r.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        r.httpBody = Data(fields.sorted { $0.key < $1.key }.map { k, v in
            "\(k)=\(v.addingPercentEncoding(withAllowedCharacters: allowed) ?? v)"
        }.joined(separator: "&").utf8)
        return r
    }

    static func decode(_ data: Data, response: URLResponse?) throws -> OAuthTokens {
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw PhoneSourceError("The \(http.url?.host ?? "sign-in") server refused the token request (HTTP \(http.statusCode)).")
        }
        return try JSONDecoder().decode(OAuthTokens.self, from: data)
    }

    func exchange(_ request: URLRequest, session: URLSession = .shared) async throws -> OAuthTokens {
        let (data, response) = try await session.data(for: request)
        return try Self.decode(data, response: response)
    }

    /// Runs the browser sign-in and returns tokens. Only reachable when a client ID is configured.
    @MainActor
    func signIn(loginHint: String?, session urlSession: URLSession = .shared) async throws -> OAuthTokens {
        let pkce = PKCE.random()
        let state = PKCE.random().verifier
        let url = authorizationURL(pkce: pkce, state: state, loginHint: loginHint)
        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: provider.callbackScheme(clientId: clientId)) { url, error in
                if let url { cont.resume(returning: url) } else {
                    cont.resume(throwing: error ?? PhoneSourceError("Sign-in was cancelled."))
                }
            }
            session.presentationContextProvider = AnchorProvider.shared
            session.prefersEphemeralWebBrowserSession = true
            if !session.start() { cont.resume(throwing: PhoneSourceError("The sign-in window could not be opened.")) }
        }
        return try await exchange(tokenRequest(code: try Self.code(from: callback, expectedState: state), pkce: pkce), session: urlSession)
    }
}

private final class AnchorProvider: NSObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = AnchorProvider()
    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?.windows.first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }
}
