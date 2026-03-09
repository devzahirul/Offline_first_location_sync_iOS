import Foundation

/// Comprehensive error hierarchy for RTLSync operations.
///
/// Use these errors to implement intelligent retry logic and user-facing error messages.
/// - Example:
/// ```swift
/// do {
///     try await syncClient.insert(points)
/// } catch let error as RTLSyncError {
///     switch error {
///     case .networkUnavailable:
///         // Queue for later retry
///     case .authenticationFailed(let tokenExpired):
///         // Trigger token refresh
///     case .serverError(let code, _):
///         // Log and alert on 5xx errors
///     default:
///         // Handle other cases
///     }
/// }
/// ```
public enum RTLSyncError: LocalizedError, Sendable {
    // MARK: - Network Errors

    /// Network is unavailable. Check NetworkMonitor before retrying.
    case networkUnavailable

    /// Request timed out while waiting for server response.
    case requestTimeout(TimeInterval)

    /// HTTP transport error with underlying cause.
    case transportError(Error)

    // MARK: - Authentication Errors

    /// Authentication failed. If tokenExpired is true, refresh the token and retry.
    case authenticationFailed(tokenExpired: Bool)

    /// Invalid or malformed auth token.
    case invalidToken(String)

    // MARK: - Server Errors

    /// Server returned an error response.
    case serverError(code: Int, message: String)

    /// Server returned an unexpected response format.
    case invalidResponse(Error)

    /// Rate limited by server. Retry after the specified interval.
    case rateLimited(retryAfter: TimeInterval)

    // MARK: - Database Errors

    /// SQLite operation failed.
    case databaseError(underlying: Error)

    /// Database is corrupted or in an inconsistent state.
    case databaseCorrupted(String)

    /// Transaction failed (original error + rollback error).
    case transactionFailed(original: Error, rollback: Error)

    // MARK: - Location Errors

    /// Location permission was denied by the user.
    case locationPermissionDenied

    /// Location service is unavailable or disabled.
    case locationServiceUnavailable

    /// Invalid location data (e.g., out of range coordinates).
    case invalidLocationData(String)

    // MARK: - Sync Engine Errors

    /// Sync operation was cancelled.
    case cancelled

    /// Sync timeout - operation took too long.
    case syncTimeout(TimeInterval)

    /// Merge conflict during bidirectional sync.
    case mergeConflict(local: String, server: String)

    // MARK: - WebSocket Errors

    /// WebSocket connection failed.
    case webSocketConnectionFailed(String)

    /// WebSocket disconnected unexpectedly.
    case webSocketDisconnected

    /// Invalid WebSocket message format.
    case invalidWebSocketMessage(String)

    // MARK: - LocalizedError Conformance

    public var errorDescription: String? {
        switch self {
        case .networkUnavailable:
            return "Network is unavailable. Changes will sync when connection is restored."
        case .requestTimeout(let duration):
            return "Request timed out after \(String(format: "%.1f", duration)) seconds."
        case .transportError(let error):
            return "Network transport error: \(error.localizedDescription)"
        case .authenticationFailed(tokenExpired: let expired):
            return expired ? "Authentication token has expired." : "Authentication failed."
        case .invalidToken(let details):
            return "Invalid authentication token: \(details)"
        case .serverError(code: let code, message: let msg):
            return "Server error (\(code)): \(msg)"
        case .invalidResponse(let error):
            return "Invalid server response: \(error.localizedDescription)"
        case .rateLimited(retryAfter: let interval):
            return "Rate limited. Retry after \(String(format: "%.0f", interval)) seconds."
        case .databaseError(let error):
            return "Database error: \(error.localizedDescription)"
        case .databaseCorrupted(let details):
            return "Database corrupted: \(details)"
        case .transactionFailed(original: let orig, rollback: let rb):
            return "Transaction failed: \(orig.localizedDescription). Rollback also failed: \(rb.localizedDescription)"
        case .locationPermissionDenied:
            return "Location permission denied. Enable in Settings to resume tracking."
        case .locationServiceUnavailable:
            return "Location service is unavailable or disabled."
        case .invalidLocationData(let details):
            return "Invalid location data: \(details)"
        case .cancelled:
            return "Operation was cancelled."
        case .syncTimeout(let duration):
            return "Sync timed out after \(String(format: "%.1f", duration)) seconds."
        case .mergeConflict(_, _):
            return "Merge conflict detected during sync."
        case .webSocketConnectionFailed(let details):
            return "WebSocket connection failed: \(details)"
        case .webSocketDisconnected:
            return "WebSocket disconnected unexpectedly."
        case .invalidWebSocketMessage(let details):
            return "Invalid WebSocket message: \(details)"
        }
    }

    public var failureReason: String? {
        switch self {
        case .networkUnavailable:
            return "No internet connection available"
        case .authenticationFailed(tokenExpired: true):
            return "Token has expired and needs refresh"
        case .locationPermissionDenied:
            return "User explicitly denied location permission"
        case .rateLimited:
            return "Too many requests sent in short time"
        default:
            return nil
        }
    }

    public var recoverySuggestion: String? {
        switch self {
        case .networkUnavailable:
            return "Check your internet connection and try again"
        case .authenticationFailed(tokenExpired: true):
            return "Please log in again to refresh your session"
        case .locationPermissionDenied:
            return "Open Settings > Privacy > Location Services and enable for this app"
        case .rateLimited(retryAfter: let interval):
            return "Wait \(String(format: "%.0f", interval)) seconds before retrying"
        case .databaseCorrupted:
            return "Contact support - data may need to be recovered from backup"
        default:
            return nil
        }
    }
}

// MARK: - Convenience Extensions

extension RTLSyncError {
    /// Returns true if this error is recoverable with retry.
    public var isRetryable: Bool {
        switch self {
        case .networkUnavailable, .requestTimeout, .transportError,
             .rateLimited, .syncTimeout, .webSocketDisconnected:
            return true
        case .serverError(code: let code, _) where code >= 500:
            return true
        case .serverError:
            return false
        default:
            return false
        }
    }

    /// Returns true if this error indicates an authentication problem.
    public var isAuthenticationError: Bool {
        switch self {
        case .authenticationFailed, .invalidToken:
            return true
        default:
            return false
        }
    }

    /// Returns true if this error indicates a client-side permission issue.
    public var isPermissionError: Bool {
        switch self {
        case .locationPermissionDenied, .locationServiceUnavailable:
            return true
        default:
            return false
        }
    }

    /// For server errors, returns the HTTP status code if available.
    public var httpStatusCode: Int? {
        switch self {
        case .serverError(code: let code, _):
            return code
        case .rateLimited:
            return 429
        default:
            return nil
        }
    }
}
